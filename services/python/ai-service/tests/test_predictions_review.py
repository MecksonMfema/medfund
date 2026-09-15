"""End-to-end tests for the AI predictions review endpoints."""
from __future__ import annotations

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
