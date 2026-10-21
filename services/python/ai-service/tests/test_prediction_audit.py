"""Integration-flavoured tests that every AI endpoint writes an
audit row to ai_predictions_store with the correct insurance_line
and anonymized input_features."""
from __future__ import annotations

import io
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.database import Base, get_optional_session
from app.main import app
from app.models.db_models import AIPredictionDB


@pytest.fixture
async def audit_session_factory():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    yield factory
    await engine.dispose()


@pytest.fixture
def client(audit_session_factory):
    async def override():
        async with audit_session_factory() as session:
            yield session
    app.dependency_overrides[get_optional_session] = override
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.pop(get_optional_session, None)


TENANT = "00000000-0000-0000-0000-000000000099"


async def _rows(factory) -> list[AIPredictionDB]:
    async with factory() as session:
        result = await session.execute(select(AIPredictionDB))
        return list(result.scalars().all())


@pytest.mark.parametrize("line,line_features", [
    ("HEALTH", {"diagnosis_codes": ["K35"], "procedure_codes": ["23410"]}),
    ("VEHICLE", {"damage_type": "collision", "part_codes": ["WSD-01"]}),
    ("PROPERTY", {"damage_type": "fire", "peril": "fire"}),
    ("LIFE", {"benefit_type": "NATURAL", "cause": "MI"}),
    ("FUNERAL", {"benefit_tier": "STANDARD"}),
    ("DISABILITY", {"benefit_type": "TEMPORARY"}),
    ("TRAVEL", {"coverage_category": "MEDICAL"}),
    ("GROUP", {"underlying_line": "LIFE", "benefit_type": "NATURAL"}),
])
@pytest.mark.asyncio
async def test_fraud_endpoint_persists_per_line(
    line, line_features, client, audit_session_factory,
):
    claim_id = str(uuid4())
    r = client.post(
        "/api/v1/ai/fraud/check",
        json={
            "claim_id": claim_id,
            "insurance_line": line,
            "subject_id": str(uuid4()),
            "provider_id": str(uuid4()),
            "claimed_amount": 1500.0,
            "currency_code": "USD",
            "service_date": "2026-09-15",
            "line_features": line_features,
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    rows = await _rows(audit_session_factory)
    assert len(rows) == 1
    row = rows[0]
    assert row.prediction_type == "fraud"
    assert row.insurance_line == line
    assert row.tenant_id == TENANT
    assert row.entity_type == "claim"
    assert row.entity_id == claim_id
    assert row.model_version == "fraud-isolation-forest-v1-canonical"
    for stripped in ("member_id", "provider_id", "claim_id", "subject_id"):
        assert stripped not in row.input_features


@pytest.mark.asyncio
async def test_adjudication_recommend_persists(client, audit_session_factory):
    claim_id = str(uuid4())
    r = client.post(
        "/api/v1/ai/adjudication/recommend",
        json={
            "claim_id": claim_id,
            "insurance_line": "HEALTH",
            "member_id": str(uuid4()),
            "provider_id": str(uuid4()),
            "diagnosis_codes": ["K35"],
            "procedure_codes": ["23410"],
            "claimed_amount": 500.0,
            "currency_code": "USD",
            "claim_type": "medical",
            "service_date": "2026-09-15",
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    rows = await _rows(audit_session_factory)
    assert len(rows) == 1
    row = rows[0]
    assert row.prediction_type == "adjudication"
    assert row.insurance_line == "HEALTH"
    assert row.entity_id == claim_id
    assert "member_id" not in row.input_features


@pytest.mark.asyncio
async def test_check_duplicate_persists(client, audit_session_factory):
    claim_id = str(uuid4())
    r = client.post(
        "/api/v1/ai/adjudication/check-duplicate",
        json={
            "claim": {
                "id": claim_id,
                "member_id": str(uuid4()),
                "insurance_line": "VEHICLE",
                "claimed_amount": 800.0,
            },
            "recent_claims": [],
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    rows = await _rows(audit_session_factory)
    assert len(rows) == 1
    assert rows[0].prediction_type == "duplicate"
    assert rows[0].insurance_line == "VEHICLE"


@pytest.mark.asyncio
async def test_suggest_codes_persists(client, audit_session_factory):
    r = client.post(
        "/api/v1/ai/adjudication/suggest-codes",
        json={
            "description": "appendectomy",
            "diagnosis_codes": ["K35"],
            "insurance_line": "HEALTH",
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    rows = await _rows(audit_session_factory)
    assert len(rows) == 1
    assert rows[0].prediction_type == "code_suggestion"
    assert rows[0].insurance_line == "HEALTH"


@pytest.mark.asyncio
async def test_chat_persists_with_no_line(client, audit_session_factory):
    conv_id = str(uuid4())
    r = client.post(
        "/api/v1/ai/chat/message",
        json={
            "message": "How much of my medical is left?",
            "conversation_id": conv_id,
            "context": {"member_id": str(uuid4())},
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    rows = await _rows(audit_session_factory)
    assert len(rows) == 1
    row = rows[0]
    assert row.prediction_type == "chat"
    assert row.insurance_line is None
    assert row.entity_type == "conversation"
    assert row.entity_id == conv_id
    # Nested member_id in context is stripped
    assert row.input_features.get("context") == {}


@pytest.mark.asyncio
async def test_ocr_persists(client, audit_session_factory):
    fake = io.BytesIO(b"dummy pdf bytes")
    r = client.post(
        "/api/v1/ai/ocr/extract",
        files={"file": ("scan.pdf", fake, "application/pdf")},
        data={"insurance_line": "PROPERTY"},
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    rows = await _rows(audit_session_factory)
    assert len(rows) == 1
    assert rows[0].prediction_type == "ocr"
    assert rows[0].insurance_line == "PROPERTY"
    assert rows[0].entity_type == "document"


@pytest.mark.asyncio
async def test_pricing_persists_for_person_centric_line(
    client, audit_session_factory,
):
    member_id = str(uuid4())
    r = client.post(
        "/api/v1/pricing/score",
        json={
            "member_id": member_id,
            "tenant_id": TENANT,
            "insurance_line": "HEALTH",
            "base_amount": 100.0,
            "currency_code": "USD",
            "age": 35,
            "chronic_condition_count": 1,
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    rows = await _rows(audit_session_factory)
    assert len(rows) == 1
    row = rows[0]
    assert row.prediction_type == "pricing"
    assert row.insurance_line == "HEALTH"
    assert row.entity_type == "member"
    assert "member_id" not in row.input_features


@pytest.mark.asyncio
async def test_pricing_persists_for_asset_line(
    client, audit_session_factory,
):
    r = client.post(
        "/api/v1/pricing/score",
        json={
            "member_id": str(uuid4()),
            "tenant_id": TENANT,
            "insurance_line": "VEHICLE",
            "base_amount": 500.0,
            "currency_code": "USD",
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    rows = await _rows(audit_session_factory)
    assert len(rows) == 1
    assert rows[0].entity_type == "asset"
    assert rows[0].insurance_line == "VEHICLE"
