"""Shared reader for the labeled ai_predictions_store corpus.

Phase 2's ``train_fraud.py`` and Phase 1's ``evaluate_model.py`` both
import from here. Consumers get back a ``pandas.DataFrame`` with the
canonical 5-tuple + a training label, plus tenant_id for weighting.

The label semantics deserve their own docstring — see ``_fraud_label``.
"""
from __future__ import annotations

from typing import Optional

import numpy as np
import pandas as pd
from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db_models import AIPredictionDB
from app.schemas.insurance_line import InsuranceLine


FRAUD_FEATURE_COLS: list[str] = [
    "amount",
    "days_since_start",
    "claim_frequency_30d",
    "provider_flag_count",
    "subject_flag_count",
]


def _canonical(pred: AIPredictionDB) -> list[float]:
    """Extract the canonical 5-tuple from a prediction row's output blob."""
    raw = ((pred.output or {}).get("canonical_features") or [0.0] * 5)
    padded = list(raw) + [0.0] * max(0, 5 - len(raw))
    return [float(x or 0) for x in padded[:5]]


def _fraud_label(pred: AIPredictionDB) -> int:
    """Reviewer verdict → training label.

    The AI service scored the row (writing ``output.risk_level``). Then a
    human reviewer either agreed (``accepted=True``) or disagreed
    (``accepted=False``). We recover the *true* fraud label by combining
    those two signals:

        risk_level == HIGH + accepted=True  → 1 (fraud, model was right)
        risk_level == HIGH + accepted=False → 0 (false positive, not fraud)
        risk_level != HIGH + accepted=True  → 0 (not fraud, model was right)
        risk_level != HIGH + accepted=False → 1 (false negative, is fraud)

    Anything other than HIGH counts as "model did not flag as fraud" —
    LOW and MEDIUM alike, per the plan.
    """
    predicted_fraud = (pred.output or {}).get("risk_level") == "HIGH"
    reviewer_agreed = bool(pred.accepted)
    if predicted_fraud:
        return 1 if reviewer_agreed else 0
    return 0 if reviewer_agreed else 1


async def read_fraud_corpus(
    session: AsyncSession,
    line: InsuranceLine,
    limit: Optional[int] = None,
) -> pd.DataFrame:
    """Return labeled fraud rows for the given line.

    Only rows with ``accepted IS NOT NULL`` are returned — unreviewed
    predictions carry no signal.
    """
    stmt = select(AIPredictionDB).where(
        and_(
            AIPredictionDB.insurance_line == line.value,
            AIPredictionDB.prediction_type == "fraud",
            AIPredictionDB.accepted.isnot(None),
        )
    )
    if limit:
        stmt = stmt.limit(limit)
    rows = (await session.execute(stmt)).scalars().all()

    records = []
    for r in rows:
        canonical = _canonical(r)
        records.append({
            "amount":              canonical[0],
            "days_since_start":    canonical[1],
            "claim_frequency_30d": canonical[2],
            "provider_flag_count": canonical[3],
            "subject_flag_count":  canonical[4],
            "label":               _fraud_label(r),
            "tenant_id":           r.tenant_id,
        })
    return pd.DataFrame.from_records(
        records,
        columns=FRAUD_FEATURE_COLS + ["label", "tenant_id"],
    )


async def read_pricing_corpus(
    session: AsyncSession,
    line: InsuranceLine,
    limit: Optional[int] = None,
) -> pd.DataFrame:
    """Return labeled pricing rows: attribute bag + multiplier + acceptance.

    Tranche 2 will consume this; Tranche 1 keeps it here so the shape is
    settled before the pricing trainer arrives (G3).
    """
    stmt = select(AIPredictionDB).where(
        and_(
            AIPredictionDB.insurance_line == line.value,
            AIPredictionDB.prediction_type == "pricing",
            AIPredictionDB.accepted.isnot(None),
        )
    )
    if limit:
        stmt = stmt.limit(limit)
    rows = (await session.execute(stmt)).scalars().all()

    records = []
    for r in rows:
        attributes = ((r.input_features or {}).get("attributes") or {})
        records.append({
            **attributes,
            "predicted_multiplier": (r.output or {}).get("multiplier"),
            "accepted":             bool(r.accepted),
            "tenant_id":            r.tenant_id,
        })
    return pd.DataFrame.from_records(records)


def stratified_split(
    frame: pd.DataFrame,
    label_col: str = "label",
    train_pct: float = 0.70,
    val_pct: float = 0.15,
    seed: int = 42,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Stable-seed stratified train / val / test split.

    Each label class is shuffled independently with the same seed, then
    split into the three buckets, so both classes are represented in
    each bucket at roughly the input proportions.
    """
    if frame.empty:
        empty = frame.iloc[0:0]
        return empty.copy(), empty.copy(), empty.copy()

    train_frames: list[pd.DataFrame] = []
    val_frames: list[pd.DataFrame] = []
    test_frames: list[pd.DataFrame] = []
    for label_value in sorted(frame[label_col].unique()):
        group = frame[frame[label_col] == label_value].sample(
            frac=1, random_state=seed,
        )
        n = len(group)
        n_train = int(n * train_pct)
        n_val = int(n * val_pct)
        train_frames.append(group.iloc[:n_train])
        val_frames.append(group.iloc[n_train:n_train + n_val])
        test_frames.append(group.iloc[n_train + n_val:])

    train = pd.concat(train_frames).sample(frac=1, random_state=seed)
    val = pd.concat(val_frames).sample(frac=1, random_state=seed)
    test = pd.concat(test_frames).sample(frac=1, random_state=seed)
    return train, val, test


def positive_class_fraction(frame: pd.DataFrame, label_col: str = "label") -> float:
    """Fraction of rows with label == 1. Used by tests + eval reports."""
    if frame.empty:
        return 0.0
    return float(np.mean(frame[label_col] == 1))
