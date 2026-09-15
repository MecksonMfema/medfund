"""Held-out evaluation for a candidate model artifact.

Usage:
    uv run python scripts/evaluate_model.py --line HEALTH --candidate v2 --model-type fraud
    uv run python scripts/evaluate_model.py --line VEHICLE --candidate v3 --model-type fraud \
        --regression-threshold 0.03

Loads the candidate joblib, reads the test split from the shared corpus
reader, computes precision / recall / AUC (fraud) or R² / MAE (pricing),
and compares against ``latest`` when it exists. Exits non-zero when the
candidate regresses on the primary metric (AUC for fraud, R² for
pricing) by more than ``--regression-threshold``.

Phase 2 wires this up to the MinIO manifest; Phase 1 ships the CLI +
metrics-comparison logic against a local joblib path so the harness is
runnable before the artifact registry lands.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

import joblib
import numpy as np

from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine

from scripts._corpus import (
    FRAUD_FEATURE_COLS,
    read_fraud_corpus,
    stratified_split,
)


LOCAL_ARTIFACT_DIR_DEFAULT = Path("app/artifacts")
EXIT_REGRESSION = 2
EXIT_MISSING_CANDIDATE = 4


def artifact_path(base: Path, model_type: str, line: InsuranceLine, version: str) -> Path:
    return base / f"{model_type}-{line.value.lower()}-{version}.joblib"


def load_artifact(path: Path) -> dict:
    """Load a joblib artifact from disk.

    Returns a ``dict`` with at minimum ``model`` and ``scaler`` keys.
    Phase 2 extends the artifact shape with ``calibrator``,
    ``canonical_features_schema``, and ``feature_names``.
    """
    return joblib.load(path)


def _score_probabilities(artifact: dict, X: np.ndarray) -> np.ndarray:
    """Return per-row P(label=1)."""
    scaler = artifact.get("scaler")
    model = artifact["model"]
    X_prepped = scaler.transform(X) if scaler is not None else X
    calibrator = artifact.get("calibrator")
    estimator = calibrator if calibrator is not None else model
    proba = estimator.predict_proba(X_prepped)
    return proba[:, 1]


def _eval_fraud(artifact: dict, X: np.ndarray, y: np.ndarray) -> dict[str, float]:
    from sklearn.metrics import precision_score, recall_score, roc_auc_score

    proba = _score_probabilities(artifact, X)
    preds = (proba >= 0.5).astype(int)
    metrics: dict[str, float] = {
        "precision": float(precision_score(y, preds, zero_division=0)),
        "recall":    float(recall_score(y, preds, zero_division=0)),
        "samples":   float(len(y)),
    }
    if len(np.unique(y)) >= 2:
        metrics["auc"] = float(roc_auc_score(y, proba))
    else:
        metrics["auc"] = float("nan")
    return metrics


@dataclass
class EvalArgs:
    line: InsuranceLine
    candidate: str
    model_type: str
    regression_threshold: float
    artifact_dir: Path


async def _load_test_split(model_type: str, line: InsuranceLine) -> tuple[np.ndarray, np.ndarray]:
    engine = create_async_engine(settings.database_url)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    try:
        async with factory() as session:
            if model_type == "fraud":
                frame = await read_fraud_corpus(session, line)
            else:
                raise NotImplementedError(
                    "pricing eval lands with the Tranche 2 pricing trainer (G3)"
                )
    finally:
        await engine.dispose()

    _, _, test = stratified_split(frame, label_col="label")
    if test.empty:
        return np.empty((0, len(FRAUD_FEATURE_COLS))), np.empty(0, dtype=int)
    X = test[FRAUD_FEATURE_COLS].to_numpy(dtype=float)
    y = test["label"].to_numpy(dtype=int)
    return X, y


def _format_metrics(name: str, metrics: dict[str, float]) -> str:
    if not metrics:
        return f"{name}: (no data)"
    parts = [
        f"precision={metrics.get('precision', float('nan')):.4f}",
        f"recall={metrics.get('recall', float('nan')):.4f}",
        f"auc={metrics.get('auc', float('nan')):.4f}",
        f"n={int(metrics.get('samples', 0))}",
    ]
    return f"{name}: " + " ".join(parts)


def _regressed(
    latest: dict[str, float] | None,
    candidate: dict[str, float],
    primary: str,
    threshold: float,
) -> bool:
    if not latest or primary not in latest or primary not in candidate:
        return False
    return candidate[primary] < latest[primary] - threshold


def _parse_args() -> EvalArgs:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--line", required=True,
                        choices=[l.value for l in InsuranceLine])
    parser.add_argument("--candidate", required=True, help="e.g. 'v2'")
    parser.add_argument("--model-type", choices=["fraud", "pricing"], required=True)
    parser.add_argument("--regression-threshold", type=float, default=0.02,
                        help="Fail if candidate < latest - threshold on primary metric")
    parser.add_argument("--artifact-dir", default=str(LOCAL_ARTIFACT_DIR_DEFAULT))
    parsed = parser.parse_args()
    return EvalArgs(
        line=InsuranceLine(parsed.line),
        candidate=parsed.candidate,
        model_type=parsed.model_type,
        regression_threshold=parsed.regression_threshold,
        artifact_dir=Path(parsed.artifact_dir),
    )


def main() -> None:
    args = _parse_args()

    if args.model_type != "fraud":
        sys.stderr.write(
            "pricing evaluation is Tranche 2 scope (per grilling decision G3).\n"
        )
        sys.exit(EXIT_MISSING_CANDIDATE)

    candidate_path = artifact_path(
        args.artifact_dir, args.model_type, args.line, args.candidate,
    )
    if not candidate_path.exists():
        sys.stderr.write(f"missing candidate artifact: {candidate_path}\n")
        sys.exit(EXIT_MISSING_CANDIDATE)
    candidate = load_artifact(candidate_path)

    latest_path = artifact_path(
        args.artifact_dir, args.model_type, args.line, "latest",
    )
    latest = load_artifact(latest_path) if latest_path.exists() else None

    X, y = asyncio.run(_load_test_split(args.model_type, args.line))
    if X.shape[0] == 0:
        sys.stderr.write("no labeled test data available for this line.\n")
        sys.exit(EXIT_MISSING_CANDIDATE)

    candidate_metrics = _eval_fraud(candidate, X, y)
    print(_format_metrics(f"candidate ({args.candidate})", candidate_metrics))

    latest_metrics: Optional[dict[str, float]] = None
    if latest is not None:
        latest_metrics = _eval_fraud(latest, X, y)
        print(_format_metrics("latest", latest_metrics))

    primary = "auc"
    if _regressed(latest_metrics, candidate_metrics, primary, args.regression_threshold):
        sys.stderr.write(
            f"candidate regressed on {primary} by more than "
            f"{args.regression_threshold} — promotion blocked.\n"
        )
        sys.exit(EXIT_REGRESSION)

    print(json.dumps({
        "line":        args.line.value,
        "candidate":   args.candidate,
        "model_type":  args.model_type,
        "candidate_metrics": candidate_metrics,
        "latest_metrics":    latest_metrics,
    }, indent=2, default=lambda o: None if isinstance(o, float) and np.isnan(o) else o))


def _ensure_json_ready(obj: Any) -> Any:
    """Convert NaN → None so json.dumps produces valid JSON."""
    if isinstance(obj, float) and np.isnan(obj):
        return None
    return obj


if __name__ == "__main__":
    main()
