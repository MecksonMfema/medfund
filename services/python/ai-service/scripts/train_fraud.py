"""Train a per-line fraud classifier from the labeled audit corpus.

Usage:
    uv run python scripts/train_fraud.py --line HEALTH --output-version v2
    uv run python scripts/train_fraud.py --line VEHICLE --output-version v3 \
        --min-samples 300 --seed 7

Pipeline:
    1. Audit gate check (Phase 0.5 / G4) — refuses to run when
       MEDFUND_TRAINING_AUDIT_SIGNED_OFF is unset.
    2. Read labeled fraud corpus for the line (cross-tenant per G4).
    3. Stratified train / val / test split (fixed seed).
    4. Fit StandardScaler on the training features.
    5. Fit RandomForestClassifier (line-specific hyperparams).
    6. Wrap in CalibratedClassifierCV (G2 — well-calibrated probabilities).
    7. Emit joblib artifact + metadata sidecar to MinIO (G6). Every
       artifact carries the extractor's canonical_features_schema (G7)
       so Phase 3's loader can reject stale artifacts.
    8. Print metrics as JSON.

Exit codes:
    0 — success
    1 — insufficient data
    3 — audit gate closed
"""
from __future__ import annotations

import argparse
import asyncio
import io
import json
import sys
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

import joblib
import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import precision_score, recall_score, roc_auc_score
from sklearn.preprocessing import StandardScaler
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine
from scripts._audit_gate import require_training_audit_signoff
from scripts._corpus import (
    FRAUD_FEATURE_COLS,
    read_fraud_corpus,
    stratified_split,
)
from scripts._registry import ArtifactUpload, upload_artifact

DEFAULT_MIN_SAMPLES = 200
EXIT_INSUFFICIENT_DATA = 1


# Per-line hyperparams. Every line ships with a working default;
# tuning is a Tranche 2 concern.
LINE_HYPERPARAMS: dict[InsuranceLine, dict[str, Any]] = {
    InsuranceLine.HEALTH:     {"n_estimators": 100, "max_depth": 8},
    InsuranceLine.LIFE:       {"n_estimators": 80,  "max_depth": 6},
    InsuranceLine.FUNERAL:    {"n_estimators": 80,  "max_depth": 6},
    InsuranceLine.GROUP:      {"n_estimators": 80,  "max_depth": 6},
    InsuranceLine.TRAVEL:     {"n_estimators": 80,  "max_depth": 6},
    InsuranceLine.DISABILITY: {"n_estimators": 80,  "max_depth": 6},
    InsuranceLine.VEHICLE:    {"n_estimators": 120, "max_depth": 10},
    InsuranceLine.PROPERTY:   {"n_estimators": 120, "max_depth": 10},
}


@dataclass
class TrainerArgs:
    line: InsuranceLine
    output_version: str
    min_samples: int
    seed: int
    dry_run: bool = False
    metadata_extras: dict[str, Any] = field(default_factory=dict)


@dataclass
class TrainingResult:
    artifact_bytes: bytes
    metadata: dict[str, Any]
    metrics: dict[str, float]


def _fit_pipeline(
    train_X: np.ndarray, train_y: np.ndarray, *,
    hyperparams: dict[str, Any], seed: int,
) -> tuple[StandardScaler, RandomForestClassifier, CalibratedClassifierCV]:
    scaler = StandardScaler().fit(train_X)
    X_scaled = scaler.transform(train_X)

    # CalibratedClassifierCV wraps the base classifier and does internal
    # k-fold cross-validation to fit both trees and Platt calibrator (G2).
    # ``cv`` is clamped to the minimum class size when the corpus is
    # small — sklearn's k-fold needs at least ``cv`` samples per class.
    class_counts = np.bincount(train_y)
    cv = int(max(2, min(3, class_counts.min())))
    base = RandomForestClassifier(
        random_state=seed,
        class_weight="balanced",
        **hyperparams,
    )
    calibrator = CalibratedClassifierCV(base, method="sigmoid", cv=cv)
    calibrator.fit(X_scaled, train_y)
    # Also fit a standalone copy for interpretability tooling / metadata —
    # calibrator holds cv-many sub-estimators, not a single one.
    base_fit = RandomForestClassifier(
        random_state=seed,
        class_weight="balanced",
        **hyperparams,
    ).fit(X_scaled, train_y)
    return scaler, base_fit, calibrator


def _evaluate(
    calibrator: CalibratedClassifierCV, scaler: StandardScaler,
    X: np.ndarray, y: np.ndarray,
) -> dict[str, float]:
    X_scaled = scaler.transform(X)
    proba = calibrator.predict_proba(X_scaled)[:, 1]
    preds = (proba >= 0.5).astype(int)
    metrics: dict[str, float] = {
        "precision": float(precision_score(y, preds, zero_division=0)),
        "recall":    float(recall_score(y, preds, zero_division=0)),
        "samples":   int(len(y)),
    }
    if len(np.unique(y)) >= 2:
        metrics["auc"] = float(roc_auc_score(y, proba))
    else:
        # Class-only slice — AUC is undefined; caller sees NaN.
        metrics["auc"] = float("nan")
    return metrics


def _serialize(artifact: dict[str, Any]) -> bytes:
    buf = io.BytesIO()
    joblib.dump(artifact, buf)
    return buf.getvalue()


async def _load_frame(line: InsuranceLine):
    engine = create_async_engine(settings.database_url)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    try:
        async with factory() as session:
            return await read_fraud_corpus(session, line)
    finally:
        await engine.dispose()


def train(args: TrainerArgs, frame) -> TrainingResult:
    """Deterministic in-memory trainer, exposed for tests.

    Callers that need MinIO IO use ``run_training`` which wraps this
    and uploads the result.
    """
    if len(frame) < args.min_samples:
        raise ValueError(
            f"insufficient data: {len(frame)} < {args.min_samples}"
        )

    train_split, val_split, test_split = stratified_split(
        frame, label_col="label", seed=args.seed,
    )
    hyperparams = LINE_HYPERPARAMS[args.line]

    X_train = train_split[FRAUD_FEATURE_COLS].to_numpy(dtype=float)
    y_train = train_split["label"].to_numpy(dtype=int)
    X_val = val_split[FRAUD_FEATURE_COLS].to_numpy(dtype=float)
    y_val = val_split["label"].to_numpy(dtype=int)

    scaler, base, calibrator = _fit_pipeline(
        X_train, y_train, hyperparams=hyperparams, seed=args.seed,
    )

    val_metrics = _evaluate(calibrator, scaler, X_val, y_val)
    model_version = f"fraud-{args.line.value.lower()}-{args.output_version}"

    metadata = {
        "model_version":              model_version,
        "line":                       args.line.value,
        "model_type":                 "fraud",
        "trained_at":                 datetime.now(UTC).isoformat(),
        "seed":                       args.seed,
        "feature_names":              FRAUD_FEATURE_COLS,
        "canonical_features_schema":  settings.canonical_features_schema_version,
        "hyperparams":                hyperparams,
        "train_samples":              int(len(train_split)),
        "val_samples":                int(len(val_split)),
        "test_samples":               int(len(test_split)),
        "val_metrics":                val_metrics,
    }
    metadata.update(args.metadata_extras)

    artifact = {
        "model":                      base,
        "scaler":                     scaler,
        "calibrator":                 calibrator,
        "feature_names":              FRAUD_FEATURE_COLS,
        "canonical_features_schema":  settings.canonical_features_schema_version,
        "model_version":              model_version,
    }
    return TrainingResult(
        artifact_bytes=_serialize(artifact),
        metadata=metadata,
        metrics=val_metrics,
    )


def run_training(args: TrainerArgs) -> TrainingResult:
    """Load corpus, train, and (unless dry-run) upload to MinIO."""
    frame = asyncio.run(_load_frame(args.line))
    result = train(args, frame)
    if not args.dry_run:
        upload_artifact(ArtifactUpload(
            model_type="fraud",
            line=args.line,
            version=args.output_version,
            joblib_bytes=result.artifact_bytes,
            metadata=result.metadata,
        ))
    return result


def _parse_args(argv: list[str] | None = None) -> TrainerArgs:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--line", required=True,
                        choices=[line.value for line in InsuranceLine])
    parser.add_argument("--output-version", required=True, help="e.g. 'v2'")
    parser.add_argument("--min-samples", type=int, default=DEFAULT_MIN_SAMPLES)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--dry-run", action="store_true",
                        help="Train but skip the MinIO upload.")
    parsed = parser.parse_args(argv)
    return TrainerArgs(
        line=InsuranceLine(parsed.line),
        output_version=parsed.output_version,
        min_samples=parsed.min_samples,
        seed=parsed.seed,
        dry_run=parsed.dry_run,
    )


def main(argv: list[str] | None = None) -> None:
    args = _parse_args(argv)
    # Audit gate runs after argparse so --help/-h stays usable without the
    # env var set (operators need to inspect the CLI before signing off).
    # A dry-run is still gated — the point of the gate is to prevent
    # artifact production, and even --dry-run writes joblib bytes locally.
    require_training_audit_signoff(script_name="train_fraud")
    try:
        result = run_training(args)
    except ValueError as e:
        sys.stderr.write(f"train_fraud: {e}\n")
        sys.exit(EXIT_INSUFFICIENT_DATA)
    print(json.dumps({
        "model_version": result.metadata["model_version"],
        "metadata":      result.metadata,
    }, indent=2, default=lambda o: None if isinstance(o, float) and np.isnan(o) else o))


if __name__ == "__main__":
    main()
