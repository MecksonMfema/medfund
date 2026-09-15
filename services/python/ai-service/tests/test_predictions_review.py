"""End-to-end tests for the AI predictions review endpoints."""
from __future__ import annotations

from datetime import datetime
from typing import Optional
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.database import Base, get_optional_session
from app.main import app
from app.models.db_models import AIPredictionDB


TENANT_A = "00000000-0000-0000-0000-00000000000a"
TENANT_B = "00000000-0000-0000-0000-00000000000b"


@pytest.fixture
async def review_session_factory():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    yield factory
    await engine.dispose()


@pytest.fixture
def client(review_session_factory):
    async def override():
        async with review_session_factory() as session:
            yield session
    app.dependency_overrides[get_optional_session] = override
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.pop(get_optional_session, None)


async def _seed(factory, **overrides) -> AIPredictionDB:
    async with factory() as session:
        row = AIPredictionDB(
            id=overrides.get("id", str(uuid4())),
            tenant_id=overrides.get("tenant_id", TENANT_A),
            insurance_line=overrides.get("insurance_line", "HEALTH"),
            entity_type=overrides.get("entity_type", "claim"),
            entity_id=overrides.get("entity_id", str(uuid4())),
            prediction_type=overrides.get("prediction_type", "adjudication"),
            model_version=overrides.get("model_version", "adj-v1"),
            input_features=overrides.get("input_features", {}),
            output=overrides.get("output", {"recommendation": "APPROVE"}),
            confidence=overrides.get("confidence", 0.9),
        )
        session.add(row)
        await session.commit()
        await session.refresh(row)
        return row


@pytest.mark.asyncio
async def test_list_predictions_tenant_scoped(client, review_session_factory):
    await _seed(review_session_factory, tenant_id=TENANT_A)
    await _seed(review_session_factory, tenant_id=TENANT_B)

    r = client.get(
        "/api/v1/ai/predictions",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 1
    assert len(body["items"]) == 1
    assert body["items"][0]["tenant_id"] == TENANT_A


@pytest.mark.asyncio
@pytest.mark.parametrize("line", [
    "HEALTH", "LIFE", "FUNERAL", "GROUP",
    "TRAVEL", "DISABILITY", "VEHICLE", "PROPERTY",
])
async def test_list_predictions_filter_by_line(client, review_session_factory, line):
    # Seed one row per line for tenant A
    for l in ("HEALTH", "LIFE", "FUNERAL", "GROUP",
              "TRAVEL", "DISABILITY", "VEHICLE", "PROPERTY"):
        await _seed(review_session_factory, insurance_line=l)

    r = client.get(
        f"/api/v1/ai/predictions?insurance_line={line}",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 1
    assert body["items"][0]["insurance_line"] == line


@pytest.mark.asyncio
async def test_list_predictions_filter_by_prediction_type(
    client, review_session_factory,
):
    await _seed(review_session_factory, prediction_type="adjudication")
    await _seed(review_session_factory, prediction_type="fraud")
    await _seed(review_session_factory, prediction_type="fraud")

    r = client.get(
        "/api/v1/ai/predictions?prediction_type=fraud",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r.status_code == 200
    assert r.json()["total"] == 2


@pytest.mark.asyncio
async def test_list_predictions_pagination(client, review_session_factory):
    for _ in range(120):
        await _seed(review_session_factory)

    r1 = client.get(
        "/api/v1/ai/predictions?page=0&size=50",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r1.status_code == 200
    body = r1.json()
    assert len(body["items"]) == 50
    assert body["total"] == 120

    r2 = client.get(
        "/api/v1/ai/predictions?page=2&size=50",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r2.status_code == 200
    body = r2.json()
    assert len(body["items"]) == 20  # remainder


@pytest.mark.asyncio
async def test_get_prediction_returns_404_for_wrong_tenant(
    client, review_session_factory,
):
    row = await _seed(review_session_factory, tenant_id=TENANT_A)
    r = client.get(
        f"/api/v1/ai/predictions/{row.id}",
        headers={"X-Tenant-ID": TENANT_B},
    )
    assert r.status_code == 404


@pytest.mark.asyncio
async def test_get_prediction_returns_full_features_and_output(
    client, review_session_factory,
):
    row = await _seed(
        review_session_factory,
        input_features={"amount": 100.0, "line": "HEALTH"},
        output={"recommendation": "APPROVE", "confidence": 0.9},
    )
    r = client.get(
        f"/api/v1/ai/predictions/{row.id}",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r.status_code == 200
    body = r.json()
    assert body["input_features"] == {"amount": 100.0, "line": "HEALTH"}
    assert body["output"]["recommendation"] == "APPROVE"


@pytest.mark.asyncio
async def test_decide_prediction_records_actor_and_flip_state(
    client, review_session_factory,
):
    row = await _seed(review_session_factory, tenant_id=TENANT_A)
    r = client.put(
        f"/api/v1/ai/predictions/{row.id}/decision",
        json={"accepted": False, "feedback": "known regression"},
        headers={
            "X-Tenant-ID": TENANT_A,
            "X-Actor-ID": "user-42",
            "X-Actor-Email": "reviewer@example.com",
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["accepted"] is False
    assert body["reviewed_by"] == "user-42"
    assert body["reviewed_by_email"] == "reviewer@example.com"
    assert body["reviewed_at"] is not None

    # Detail confirms feedback merged into output
    r2 = client.get(
        f"/api/v1/ai/predictions/{row.id}",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r2.json()["output"]["_review_feedback"] == "known regression"


@pytest.mark.asyncio
async def test_decide_prediction_rejects_missing_actor(
    client, review_session_factory,
):
    row = await _seed(review_session_factory)
    r = client.put(
        f"/api/v1/ai/predictions/{row.id}/decision",
        json={"accepted": True},
        headers={"X-Tenant-ID": TENANT_A},   # No actor headers
    )
    assert r.status_code == 400


@pytest.mark.asyncio
async def test_decide_prediction_returns_404_for_wrong_tenant(
    client, review_session_factory,
):
    row = await _seed(review_session_factory, tenant_id=TENANT_A)
    r = client.put(
        f"/api/v1/ai/predictions/{row.id}/decision",
        json={"accepted": True},
        headers={
            "X-Tenant-ID": TENANT_B,
            "X-Actor-ID": "user-42",
            "X-Actor-Email": "reviewer@example.com",
        },
    )
    assert r.status_code == 404


# ── Review queue (Phase 0, per G1 + G2) ─────────────────────────────────────


async def _seed_fraud(
    factory,
    tenant_id: str = TENANT_A,
    risk_level: str = "HIGH",
    accepted: Optional[bool] = None,
    insurance_line: str = "HEALTH",
) -> AIPredictionDB:
    return await _seed(
        factory,
        tenant_id=tenant_id,
        prediction_type="fraud",
        insurance_line=insurance_line,
        model_version="fraud-canonical",
        output={"risk_level": risk_level, "risk_score": 0.8 if risk_level == "HIGH" else 0.2},
        confidence=0.7,
    )


async def _mark_reviewed(factory, row: AIPredictionDB) -> None:
    async with factory() as session:
        r = await session.get(AIPredictionDB, row.id)
        r.accepted = True
        r.reviewed_by = "prev"
        r.reviewed_by_email = "prev@example.com"
        r.reviewed_at = datetime.utcnow()
        await session.commit()


@pytest.mark.asyncio
async def test_review_queue_returns_50_50_high_low_when_both_have_volume(
    client, review_session_factory,
):
    for _ in range(30):
        await _seed_fraud(review_session_factory, risk_level="HIGH")
    for _ in range(30):
        await _seed_fraud(review_session_factory, risk_level="LOW")

    r = client.get(
        "/api/v1/ai/predictions/review-queue?size=20&model_type=fraud&seed=7",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert len(body["items"]) == 20
    levels = [item["output"]["risk_level"] for item in body["items"]]
    assert levels.count("HIGH") == 10
    assert levels.count("LOW") == 10
    assert body["total_unreviewed"] == 60
    assert body["high_count"] == 30
    assert body["low_count"] == 30


@pytest.mark.asyncio
async def test_review_queue_backfills_when_one_bucket_thin(
    client, review_session_factory,
):
    # Only 3 HIGH rows, plenty of LOW. Batch of 10 should return 3 HIGH + 7 LOW.
    for _ in range(3):
        await _seed_fraud(review_session_factory, risk_level="HIGH")
    for _ in range(20):
        await _seed_fraud(review_session_factory, risk_level="LOW")

    r = client.get(
        "/api/v1/ai/predictions/review-queue?size=10&model_type=fraud&seed=1",
        headers={"X-Tenant-ID": TENANT_A},
    )
    assert r.status_code == 200
    body = r.json()
    assert len(body["items"]) == 10
    levels = [item["output"]["risk_level"] for item in body["items"]]
    assert levels.count("HIGH") == 3
    assert levels.count("LOW") == 7


@pytest.mark.asyncio
async def test_review_queue_is_tenant_scoped(client, review_session_factory):
    for _ in range(5):
        await _seed_fraud(review_session_factory, tenant_id=TENANT_A, risk_level="HIGH")
    for _ in range(5):
        await _seed_fraud(review_session_factory, tenant_id=TENANT_B, risk_level="HIGH")

    r = client.get(
        "/api/v1/ai/predictions/review-queue?size=20&model_type=fraud&seed=2",
        headers={"X-Tenant-ID": TENANT_A},
    )
    body = r.json()
    assert body["total_unreviewed"] == 5
    for item in body["items"]:
        assert item["tenant_id"] == TENANT_A


@pytest.mark.asyncio
async def test_review_queue_only_returns_unreviewed(client, review_session_factory):
    reviewed = await _seed_fraud(review_session_factory, risk_level="HIGH")
    await _mark_reviewed(review_session_factory, reviewed)
    pending = await _seed_fraud(review_session_factory, risk_level="HIGH")
    await _seed_fraud(review_session_factory, risk_level="LOW")

    r = client.get(
        "/api/v1/ai/predictions/review-queue?size=10&model_type=fraud&seed=3",
        headers={"X-Tenant-ID": TENANT_A},
    )
    body = r.json()
    ids = {item["id"] for item in body["items"]}
    assert reviewed.id not in ids
    assert pending.id in ids
    assert body["total_unreviewed"] == 2


@pytest.mark.asyncio
async def test_review_queue_filters_by_model_type(client, review_session_factory):
    await _seed_fraud(review_session_factory, risk_level="HIGH")
    await _seed(  # a non-fraud unreviewed row should not appear
        review_session_factory,
        prediction_type="adjudication",
        output={"risk_level": "HIGH", "recommendation": "APPROVE"},
    )
    r = client.get(
        "/api/v1/ai/predictions/review-queue?size=10&model_type=fraud",
        headers={"X-Tenant-ID": TENANT_A},
    )
    body = r.json()
    assert body["total_unreviewed"] == 1
    assert body["items"][0]["prediction_type"] == "fraud"


@pytest.mark.asyncio
async def test_review_queue_filters_by_insurance_line(client, review_session_factory):
    await _seed_fraud(review_session_factory, risk_level="HIGH", insurance_line="HEALTH")
    await _seed_fraud(review_session_factory, risk_level="HIGH", insurance_line="VEHICLE")
    r = client.get(
        "/api/v1/ai/predictions/review-queue?size=10&model_type=fraud&insurance_line=HEALTH",
        headers={"X-Tenant-ID": TENANT_A},
    )
    body = r.json()
    assert body["total_unreviewed"] == 1
    assert body["items"][0]["insurance_line"] == "HEALTH"
