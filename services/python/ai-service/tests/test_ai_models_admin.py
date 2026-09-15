"""Phase 5 unit tests — /api/v1/ai/models list + promote."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.schemas.insurance_line import InsuranceLine
from app.services import fraud_registry, pricing_registry

from scripts import _registry, train_fraud
from tests.scripts._fake_minio import FakeMinioClient


TENANT = "00000000-0000-0000-0000-000000000099"
ACTOR = "user-1"
ACTOR_EMAIL = "admin@example.com"
PROMOTE_PERM = "ai:models:promote"


@pytest.fixture
def fake_minio(monkeypatch):
    fake = FakeMinioClient()
    monkeypatch.setattr(_registry, "_client", fake)
    monkeypatch.setattr(_registry, "_ml_client", lambda: fake)
    fraud_registry.reset_for_tests()
    pricing_registry.reset_for_tests()
    yield fake
    fraud_registry.reset_for_tests()
    pricing_registry.reset_for_tests()


@pytest.fixture
def client(fake_minio, monkeypatch):
    # Neuter the Kafka publish call so tests don't require a running broker.
    from app.api import models as models_api
    async def _noop_publish(event):
        return None
    monkeypatch.setattr(models_api, "_publish_audit_event", _noop_publish)
    with TestClient(app) as c:
        yield c


def _mk_frame() -> pd.DataFrame:
    rng = np.random.RandomState(11)
    positive = pd.DataFrame({
        "amount":              rng.lognormal(8, 0.4, 100),
        "days_since_start":    rng.uniform(10, 90, 100),
        "claim_frequency_30d": rng.poisson(5.0, 100),
        "provider_flag_count": rng.poisson(1.5, 100),
        "subject_flag_count":  rng.poisson(1.5, 100),
        "label":               1,
        "tenant_id":           ["tenant-A"] * 100,
    })
    negative = pd.DataFrame({
        "amount":              rng.lognormal(5, 0.6, 400),
        "days_since_start":    rng.uniform(200, 2000, 400),
        "claim_frequency_30d": rng.poisson(0.5, 400),
        "provider_flag_count": rng.poisson(0.05, 400),
        "subject_flag_count":  rng.poisson(0.05, 400),
        "label":               0,
        "tenant_id":           ["tenant-A"] * 400,
    })
    return pd.concat([positive, negative], ignore_index=True)


def _seed_fraud_artifact(line: InsuranceLine, version: str) -> None:
    result = train_fraud.train(
        train_fraud.TrainerArgs(
            line=line, output_version=version,
            min_samples=50, seed=42,
        ),
        _mk_frame(),
    )
    _registry.upload_artifact(_registry.ArtifactUpload(
        model_type="fraud", line=line, version=version,
        joblib_bytes=result.artifact_bytes,
        metadata=result.metadata,
    ))


# ── GET /api/v1/ai/models ───────────────────────────────────────────────


def test_list_returns_sixteen_rows(client):
    r = client.get("/api/v1/ai/models")
    assert r.status_code == 200
    rows = r.json()
    assert len(rows) == 16  # 2 model_types × 8 lines


def test_list_defaults_every_row_to_fallback_without_manifest(client):
    r = client.get("/api/v1/ai/models")
    rows = r.json()
    for row in rows:
        assert row["is_fallback"] is True
        assert row["schema_status"] == "OK"
        assert row["active_version"] is None
        if row["model_type"] == "fraud":
            assert row["model_version"] == "fraud-isolation-forest-v1-canonical"
        else:
            assert row["model_version"] == "rule-v1"


def test_list_reflects_active_trained_artifact(client):
    _seed_fraud_artifact(InsuranceLine.HEALTH, "v2")
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v2")

    r = client.get("/api/v1/ai/models")
    rows = r.json()
    health_row = next(
        row for row in rows
        if row["model_type"] == "fraud" and row["line"] == "HEALTH"
    )
    assert health_row["active_version"] == "v2"
    assert health_row["model_version"] == "fraud-health-v2"
    assert health_row["is_fallback"] is False
    assert health_row["schema_status"] == "OK"
    assert health_row["train_samples"] > 0
    assert "precision" in health_row["metrics"]


def test_list_flags_schema_mismatch(client, monkeypatch):
    # Seed an artifact whose schema doesn't match runtime.
    import io
    import joblib
    _seed_fraud_artifact(InsuranceLine.HEALTH, "v99")
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v99")

    # Rewrite the joblib in-place with an incompatible schema tag.
    bytes_ = _registry.download_artifact("fraud", InsuranceLine.HEALTH, "v99")
    payload = joblib.load(io.BytesIO(bytes_))
    payload["canonical_features_schema"] = "v99-future"
    buf = io.BytesIO()
    joblib.dump(payload, buf)
    _registry.upload_artifact(_registry.ArtifactUpload(
        model_type="fraud", line=InsuranceLine.HEALTH, version="v99",
        joblib_bytes=buf.getvalue(),
        metadata={
            "model_version": "fraud-health-v99",
            "canonical_features_schema": "v99-future",
        },
    ))

    # Trigger resolver so schema-mismatch is recorded.
    fraud_registry.reset_for_tests()
    fraud_registry.resolve_fraud_model(InsuranceLine.HEALTH)

    r = client.get("/api/v1/ai/models")
    rows = r.json()
    health_row = next(
        row for row in rows
        if row["model_type"] == "fraud" and row["line"] == "HEALTH"
    )
    assert health_row["schema_status"] == "SCHEMA_MISMATCH"
    assert "v99-future" in (health_row["schema_status_detail"] or "")


# ── PUT /api/v1/ai/models/{type}/{line}/promote ──────────────────────────


def _promote_headers(perm: str = PROMOTE_PERM) -> dict:
    return {
        "X-Actor-ID":         ACTOR,
        "X-Actor-Email":      ACTOR_EMAIL,
        "X-Tenant-ID":        TENANT,
        "X-User-Permissions": perm,
    }


def test_promote_rejects_missing_permission(client):
    r = client.put(
        "/api/v1/ai/models/fraud/HEALTH/promote",
        json={"version": "v1"},
        headers={
            "X-Actor-ID": ACTOR, "X-Actor-Email": ACTOR_EMAIL,
            "X-Tenant-ID": TENANT,
            "X-User-Permissions": "ai:models:view",  # view but not promote
        },
    )
    assert r.status_code == 403


def test_promote_rejects_missing_actor(client):
    r = client.put(
        "/api/v1/ai/models/fraud/HEALTH/promote",
        json={"version": "v1"},
        headers={"X-User-Permissions": PROMOTE_PERM, "X-Tenant-ID": TENANT},
    )
    assert r.status_code == 400


def test_promote_404_when_artifact_missing(client):
    r = client.put(
        "/api/v1/ai/models/fraud/HEALTH/promote",
        json={"version": "v-nope"},
        headers=_promote_headers(),
    )
    assert r.status_code == 404


def test_promote_updates_manifest_and_returns_before_after(client):
    _seed_fraud_artifact(InsuranceLine.HEALTH, "v1")
    _seed_fraud_artifact(InsuranceLine.HEALTH, "v2")
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v1")

    r = client.put(
        "/api/v1/ai/models/fraud/HEALTH/promote",
        json={"version": "v2"},
        headers=_promote_headers(),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["before"] == "v1"
    assert body["after"] == "v2"
    assert body["audit_event_id"]
    assert _registry.active_version("fraud", InsuranceLine.HEALTH) == "v2"


def test_promote_rejects_unknown_model_type(client):
    r = client.put(
        "/api/v1/ai/models/mystery/HEALTH/promote",
        json={"version": "v1"},
        headers=_promote_headers(),
    )
    assert r.status_code == 400


def test_promote_rejects_unknown_line(client):
    r = client.put(
        "/api/v1/ai/models/fraud/GALACTIC/promote",
        json={"version": "v1"},
        headers=_promote_headers(),
    )
    assert r.status_code == 400


def test_promote_refreshes_local_resolver_cache(client):
    _seed_fraud_artifact(InsuranceLine.HEALTH, "v1")
    _seed_fraud_artifact(InsuranceLine.HEALTH, "v2")
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v1")
    fraud_registry.resolve_fraud_model(InsuranceLine.HEALTH)
    assert fraud_registry.cached_version(InsuranceLine.HEALTH) == "v1"

    r = client.put(
        "/api/v1/ai/models/fraud/HEALTH/promote",
        json={"version": "v2"},
        headers=_promote_headers(),
    )
    assert r.status_code == 200
    assert fraud_registry.cached_version(InsuranceLine.HEALTH) == "v2"


def test_promote_records_actor_email_in_audit_event(client, monkeypatch):
    _seed_fraud_artifact(InsuranceLine.HEALTH, "v1")
    captured: dict = {}

    async def capture(event):
        captured.update(event)
    from app.api import models as models_api
    monkeypatch.setattr(models_api, "_publish_audit_event", capture)

    r = client.put(
        "/api/v1/ai/models/fraud/HEALTH/promote",
        json={"version": "v1"},
        headers=_promote_headers(),
    )
    assert r.status_code == 200
    assert captured["entityType"] == "AI_MODEL"
    assert captured["entityName"] == "fraud model for HEALTH"
    assert captured["actorEmail"] == ACTOR_EMAIL
    assert captured["actorId"] == ACTOR
    assert captured["newValue"]["active_version"] == "v1"
