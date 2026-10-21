"""End-to-end tests for the line-aware /suggest-codes endpoint."""
from __future__ import annotations

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

TENANT = "00000000-0000-0000-0000-000000000099"


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


@pytest.mark.parametrize("line,context,expected_prefix", [
    ("HEALTH",     {"diagnosis_codes": ["K35.0"]},                    "23"),
    ("VEHICLE",    {"damage_type": "collision"},                       "PANEL"),
    ("PROPERTY",   {"peril": "fire"},                                  "STRUCT-FIRE"),
    ("LIFE",       {"benefit_type": "NATURAL"},                        "LIFE-NAT"),
    ("FUNERAL",    {"benefit_tier": "STANDARD"},                       "FUN-STD"),
    ("DISABILITY", {"benefit_type": "TEMPORARY"},                      "DIS-TMP"),
    ("TRAVEL",     {"coverage_category": "MEDICAL"},                   "TRV-MED"),
    ("GROUP",      {"underlying_line": "LIFE",
                    "benefit_type": "NATURAL"},                        "LIFE-NAT"),
])
def test_suggest_codes_returns_line_specific_seed_when_llm_unavailable(
    line, context, expected_prefix, client,
):
    r = client.post(
        "/api/v1/ai/adjudication/suggest-codes",
        json={
            "insurance_line": line,
            "description": "test description",
            "line_context": context,
        },
        headers={"X-Tenant-ID": TENANT},
    )

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["insurance_line"] == line
    assert body["source"] == "rules"
    assert len(body["suggestions"]) >= 1
    assert any(s["code"].startswith(expected_prefix) for s in body["suggestions"]), (
        f"Expected a code with prefix {expected_prefix} in {body['suggestions']}"
    )
    assert line.lower() in body["model_version"]
    assert "rules" in body["model_version"]


def test_suggest_codes_defaults_to_health_and_uses_legacy_diagnosis_codes(client):
    """Legacy callers omit line_context and pass `diagnosis_codes` at top level."""
    r = client.post(
        "/api/v1/ai/adjudication/suggest-codes",
        json={
            "description": "appendectomy",
            "diagnosis_codes": ["K35.0"],
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["insurance_line"] == "HEALTH"
    assert body["source"] == "rules"
    assert len(body["suggestions"]) >= 1


def test_suggest_codes_unknown_key_returns_empty_suggestions(client):
    r = client.post(
        "/api/v1/ai/adjudication/suggest-codes",
        json={
            "insurance_line": "HEALTH",
            "description": "unknown",
            "line_context": {"diagnosis_codes": ["ZZZ-NOT-REAL"]},
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["suggestions"] == []
    assert body["source"] == "rules"


@pytest.mark.asyncio
async def test_suggest_codes_persists_line_and_hashed_entity_id(
    client, audit_session_factory,
):
    r = client.post(
        "/api/v1/ai/adjudication/suggest-codes",
        json={
            "insurance_line": "VEHICLE",
            "description": "front-end collision",
            "line_context": {"damage_type": "collision"},
        },
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200

    async with audit_session_factory() as session:
        rows = list((await session.execute(select(AIPredictionDB))).scalars().all())
    assert len(rows) == 1
    row = rows[0]
    assert row.prediction_type == "code_suggestion"
    assert row.insurance_line == "VEHICLE"
    assert row.entity_type == "claim"
    # entity_id is a hex-hash of (description, line) — 40-char SHA1
    assert len(row.entity_id) == 40
