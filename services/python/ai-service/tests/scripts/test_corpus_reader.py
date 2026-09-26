"""Phase 1 unit tests — corpus reader label semantics + split + sufficiency."""
from __future__ import annotations

from datetime import UTC, datetime
from uuid import uuid4

import pandas as pd
import pytest
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.database import Base
from app.models.db_models import AIPredictionDB
from app.schemas.insurance_line import InsuranceLine
from scripts._corpus import (
    _fraud_label,
    positive_class_fraction,
    read_fraud_corpus,
    stratified_split,
)
from scripts.data_sufficiency import report

TENANT = "00000000-0000-0000-0000-00000000000a"


@pytest.fixture
async def session_factory():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    yield factory
    await engine.dispose()


async def _seed_fraud(
    factory,
    *,
    tenant_id: str = TENANT,
    line: str = "HEALTH",
    risk_level: str = "HIGH",
    accepted: bool | None = True,
    canonical: tuple[float, ...] = (100.0, 30.0, 2.0, 0.0, 0.0),
    prediction_type: str = "fraud",
) -> AIPredictionDB:
    async with factory() as session:
        row = AIPredictionDB(
            id=str(uuid4()),
            tenant_id=tenant_id,
            insurance_line=line,
            entity_type="claim",
            entity_id=str(uuid4()),
            prediction_type=prediction_type,
            model_version="fraud-canonical",
            input_features={},
            output={
                "risk_level": risk_level,
                "canonical_features": list(canonical),
            },
            confidence=0.7,
            accepted=accepted,
            created_at=datetime.now(UTC),
        )
        session.add(row)
        await session.commit()
        await session.refresh(row)
        return row


def _mk(risk_level: str, accepted: bool) -> AIPredictionDB:
    return AIPredictionDB(
        id="p",
        tenant_id=TENANT,
        insurance_line="HEALTH",
        entity_type="claim",
        entity_id="c",
        prediction_type="fraud",
        model_version="fraud-canonical",
        output={"risk_level": risk_level, "canonical_features": [0, 0, 0, 0, 0]},
        accepted=accepted,
    )


@pytest.mark.parametrize("risk_level,accepted,label", [
    ("HIGH",   True,  1),   # model right, is fraud
    ("HIGH",   False, 0),   # false positive, not fraud
    ("LOW",    True,  0),   # model right, not fraud
    ("LOW",    False, 1),   # false negative, is fraud
    ("MEDIUM", True,  0),   # MEDIUM is treated as "not flagged as fraud"
    ("MEDIUM", False, 1),
])
def test_fraud_label_inversion_when_reviewer_overrides(risk_level, accepted, label):
    assert _fraud_label(_mk(risk_level, accepted)) == label


@pytest.mark.asyncio
async def test_read_fraud_corpus_filters_unlabeled_rows(session_factory):
    await _seed_fraud(session_factory, accepted=True)
    await _seed_fraud(session_factory, accepted=False)
    await _seed_fraud(session_factory, accepted=None)  # unlabeled — should be excluded

    async with session_factory() as session:
        frame = await read_fraud_corpus(session, InsuranceLine.HEALTH)
    assert len(frame) == 2
    assert set(frame["label"].tolist()).issubset({0, 1})


@pytest.mark.asyncio
async def test_read_fraud_corpus_line_scoped(session_factory):
    await _seed_fraud(session_factory, line="HEALTH", accepted=True)
    await _seed_fraud(session_factory, line="VEHICLE", accepted=True)

    async with session_factory() as session:
        frame = await read_fraud_corpus(session, InsuranceLine.HEALTH)
    assert len(frame) == 1


@pytest.mark.asyncio
async def test_read_fraud_corpus_extracts_canonical_tuple(session_factory):
    await _seed_fraud(
        session_factory,
        canonical=(1234.5, 60.0, 3.0, 1.0, 2.0),
        risk_level="HIGH", accepted=True,
    )
    async with session_factory() as session:
        frame = await read_fraud_corpus(session, InsuranceLine.HEALTH)
    assert list(frame.columns) == [
        "amount", "days_since_start", "claim_frequency_30d",
        "provider_flag_count", "subject_flag_count", "label", "tenant_id",
    ]
    row = frame.iloc[0]
    assert row["amount"] == 1234.5
    assert row["days_since_start"] == 60.0
    assert row["claim_frequency_30d"] == 3.0
    assert row["provider_flag_count"] == 1.0
    assert row["subject_flag_count"] == 2.0
    assert row["label"] == 1
    assert row["tenant_id"] == TENANT


def test_stratified_split_preserves_label_distribution():
    n_positive = 200
    n_negative = 800
    frame = pd.DataFrame({
        "amount": list(range(n_positive + n_negative)),
        "label": [1] * n_positive + [0] * n_negative,
    })
    train, val, test = stratified_split(frame, label_col="label")
    total = len(train) + len(val) + len(test)
    assert total == len(frame)

    base = positive_class_fraction(frame)
    for name, part in [("train", train), ("val", val), ("test", test)]:
        assert positive_class_fraction(part) == pytest.approx(base, abs=0.05), (
            f"{name} positive fraction {positive_class_fraction(part):.3f} "
            f"drifted from base {base:.3f}"
        )


def test_stratified_split_deterministic():
    frame = pd.DataFrame({"amount": list(range(20)), "label": [0, 1] * 10})
    a = stratified_split(frame, seed=42)
    b = stratified_split(frame, seed=42)
    for x, y in zip(a, b):
        assert list(x["amount"]) == list(y["amount"])


def test_stratified_split_empty_frame():
    frame = pd.DataFrame(columns=["amount", "label"])
    train, val, test = stratified_split(frame)
    assert train.empty and val.empty and test.empty


@pytest.mark.asyncio
async def test_data_sufficiency_flags_lines_below_threshold(session_factory):
    # Threshold 5. Seed 4 → below. Then add one → at threshold → trainable.
    for _ in range(4):
        await _seed_fraud(session_factory, line="HEALTH", accepted=True)

    async with session_factory() as session:
        results = await report(session, min_fraud=5, min_pricing=1)
    by_line = {r.line: r for r in results}
    assert by_line[InsuranceLine.HEALTH].fraud_labeled == 4
    assert by_line[InsuranceLine.HEALTH].is_fraud_trainable is False

    await _seed_fraud(session_factory, line="HEALTH", accepted=False)
    async with session_factory() as session:
        results = await report(session, min_fraud=5, min_pricing=1)
    by_line = {r.line: r for r in results}
    assert by_line[InsuranceLine.HEALTH].fraud_labeled == 5
    assert by_line[InsuranceLine.HEALTH].is_fraud_trainable is True


@pytest.mark.asyncio
async def test_data_sufficiency_covers_every_line(session_factory):
    async with session_factory() as session:
        results = await report(session, min_fraud=200, min_pricing=500)
    lines = [r.line for r in results]
    assert lines == list(InsuranceLine)
    for r in results:
        assert r.fraud_labeled == 0
        assert r.pricing_labeled == 0
        assert r.is_fraud_trainable is False
        assert r.is_pricing_trainable is False


@pytest.mark.asyncio
async def test_data_sufficiency_ignores_unreviewed_rows(session_factory):
    # 3 reviewed + 5 unreviewed for HEALTH; report should count 3 only.
    for _ in range(3):
        await _seed_fraud(session_factory, line="HEALTH", accepted=True)
    for _ in range(5):
        await _seed_fraud(session_factory, line="HEALTH", accepted=None)

    async with session_factory() as session:
        results = await report(session, min_fraud=3, min_pricing=1)
    by_line = {r.line: r for r in results}
    assert by_line[InsuranceLine.HEALTH].fraud_labeled == 3
    assert by_line[InsuranceLine.HEALTH].is_fraud_trainable is True
