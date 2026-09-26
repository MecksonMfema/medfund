"""Phase 3 unit tests — fraud_registry (resolver + poll + fallback + G7)."""
from __future__ import annotations

import io

import joblib
import numpy as np
import pandas as pd
import pytest

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine
from app.services import fraud_registry
from app.services.fraud_registry import (
    cached_version,
    refresh_from_manifest,
    reset_for_tests,
    resolve_fraud_model,
    schema_mismatch_status,
)
from app.services.ml_models import CANONICAL_FALLBACK_VERSION
from scripts import _registry, train_fraud
from tests.scripts._fake_minio import FakeMinioClient

# ── Fixtures ────────────────────────────────────────────────────────────


@pytest.fixture
def fake_minio(monkeypatch):
    fake = FakeMinioClient()
    monkeypatch.setattr(_registry, "_client", fake)
    monkeypatch.setattr(_registry, "_ml_client", lambda: fake)
    reset_for_tests()
    yield fake
    reset_for_tests()


@pytest.fixture
def sample_frame():
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


def _seed_trained_artifact(
    line: InsuranceLine,
    version: str,
    frame: pd.DataFrame,
    *,
    schema_override: str | None = None,
) -> bytes:
    """Train a real artifact, optionally overriding the schema tag, and
    upload it to the fake MinIO bucket. Returns the raw joblib bytes."""
    result = train_fraud.train(
        train_fraud.TrainerArgs(
            line=line, output_version=version,
            min_samples=50, seed=42,
        ),
        frame,
    )
    artifact_bytes = result.artifact_bytes
    if schema_override is not None:
        payload = joblib.load(io.BytesIO(artifact_bytes))
        payload["canonical_features_schema"] = schema_override
        buf = io.BytesIO()
        joblib.dump(payload, buf)
        artifact_bytes = buf.getvalue()
        result.metadata["canonical_features_schema"] = schema_override
    _registry.upload_artifact(_registry.ArtifactUpload(
        model_type="fraud", line=line, version=version,
        joblib_bytes=artifact_bytes, metadata=result.metadata,
    ))
    return artifact_bytes


# ── Cold-start / manifest-absent behaviour ─────────────────────────────


def test_resolve_returns_canonical_when_no_manifest(fake_minio):
    model = resolve_fraud_model(InsuranceLine.HEALTH)
    assert model.model_version == CANONICAL_FALLBACK_VERSION
    assert cached_version(InsuranceLine.HEALTH) is None


def test_resolve_returns_canonical_when_manifest_omits_line(fake_minio):
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v1")
    model = resolve_fraud_model(InsuranceLine.VEHICLE)
    assert model.model_version == CANONICAL_FALLBACK_VERSION


# ── Trained-artifact resolution ─────────────────────────────────────────


def test_resolve_returns_trained_when_artifact_present(fake_minio, sample_frame):
    _seed_trained_artifact(InsuranceLine.HEALTH, "v2", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v2")

    model = resolve_fraud_model(InsuranceLine.HEALTH)
    assert model.model_version == "fraud-health-v2"
    assert model.is_trained_artifact is True
    assert cached_version(InsuranceLine.HEALTH) == "v2"


def test_resolve_falls_back_on_corrupt_artifact(fake_minio, sample_frame):
    _registry.upload_artifact(_registry.ArtifactUpload(
        model_type="fraud", line=InsuranceLine.HEALTH, version="v-junk",
        joblib_bytes=b"totally-not-a-joblib-payload",
        metadata={"model_version": "fraud-health-v-junk"},
    ))
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v-junk")

    model = resolve_fraud_model(InsuranceLine.HEALTH)
    assert model.model_version == CANONICAL_FALLBACK_VERSION


def test_resolve_falls_back_on_missing_expected_keys(fake_minio):
    # joblib loads fine but the dict is missing keys FraudMLModel needs.
    buf = io.BytesIO()
    joblib.dump({"unexpected": "shape"}, buf)
    _registry.upload_artifact(_registry.ArtifactUpload(
        model_type="fraud", line=InsuranceLine.HEALTH, version="v-broken",
        joblib_bytes=buf.getvalue(),
        metadata={"model_version": "fraud-health-v-broken"},
    ))
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v-broken")

    model = resolve_fraud_model(InsuranceLine.HEALTH)
    assert model.model_version == CANONICAL_FALLBACK_VERSION


def test_resolve_caches_per_line(fake_minio, sample_frame, monkeypatch):
    _seed_trained_artifact(InsuranceLine.VEHICLE, "v1", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.VEHICLE, "v1")

    calls = {"n": 0}
    real_download = fraud_registry.download_artifact

    def counted(model_type, line, version):
        calls["n"] += 1
        return real_download(model_type, line, version)

    monkeypatch.setattr(fraud_registry, "download_artifact", counted)

    m1 = resolve_fraud_model(InsuranceLine.VEHICLE)
    m2 = resolve_fraud_model(InsuranceLine.VEHICLE)
    assert m1 is m2
    assert calls["n"] == 1


def test_group_line_always_uses_fallback(fake_minio, sample_frame):
    # Even with a HEALTH artifact in place, resolving GROUP returns fallback.
    _seed_trained_artifact(InsuranceLine.HEALTH, "v1", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v1")

    model = resolve_fraud_model(InsuranceLine.GROUP)
    assert model.model_version == CANONICAL_FALLBACK_VERSION


# ── G7 SCHEMA_MISMATCH ──────────────────────────────────────────────────


def test_schema_mismatch_falls_back_and_records_status(fake_minio, sample_frame):
    _seed_trained_artifact(
        InsuranceLine.HEALTH, "v3", sample_frame,
        schema_override="v99-future",
    )
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v3")

    model = resolve_fraud_model(InsuranceLine.HEALTH)
    assert model.model_version == CANONICAL_FALLBACK_VERSION

    status = schema_mismatch_status("fraud", InsuranceLine.HEALTH)
    assert status is not None
    assert "v99-future" in status
    assert settings.canonical_features_schema_version in status


def test_schema_mismatch_clears_when_schema_realigns(fake_minio, sample_frame):
    _seed_trained_artifact(
        InsuranceLine.HEALTH, "v3", sample_frame,
        schema_override="v99-future",
    )
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v3")
    resolve_fraud_model(InsuranceLine.HEALTH)
    assert schema_mismatch_status("fraud", InsuranceLine.HEALTH) is not None

    # Retrain with the correct schema (aligned to runtime), reset cache, resolve again.
    _seed_trained_artifact(InsuranceLine.HEALTH, "v4", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v4")
    reset_for_tests()
    resolve_fraud_model(InsuranceLine.HEALTH)
    assert schema_mismatch_status("fraud", InsuranceLine.HEALTH) is None


# ── Poll (refresh_from_manifest) ────────────────────────────────────────


def test_refresh_from_manifest_swaps_trained_version(fake_minio, sample_frame):
    _seed_trained_artifact(InsuranceLine.HEALTH, "v1", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v1")
    m1 = resolve_fraud_model(InsuranceLine.HEALTH)
    assert m1.model_version == "fraud-health-v1"

    _seed_trained_artifact(InsuranceLine.HEALTH, "v2", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v2")

    refresh_from_manifest()
    m2 = resolve_fraud_model(InsuranceLine.HEALTH)
    assert m2.model_version == "fraud-health-v2"
    assert cached_version(InsuranceLine.HEALTH) == "v2"


def test_refresh_from_manifest_reverts_to_fallback_when_version_removed(
    fake_minio, sample_frame,
):
    _seed_trained_artifact(InsuranceLine.HEALTH, "v1", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v1")
    assert resolve_fraud_model(InsuranceLine.HEALTH).model_version == "fraud-health-v1"

    # Remove the entry from the manifest.
    _registry.save_manifest({})

    refresh_from_manifest()
    assert resolve_fraud_model(InsuranceLine.HEALTH).model_version == CANONICAL_FALLBACK_VERSION
    assert cached_version(InsuranceLine.HEALTH) is None


def test_refresh_from_manifest_is_noop_when_up_to_date(
    fake_minio, sample_frame, monkeypatch,
):
    _seed_trained_artifact(InsuranceLine.HEALTH, "v1", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v1")
    resolve_fraud_model(InsuranceLine.HEALTH)

    calls = {"n": 0}
    real_download = fraud_registry.download_artifact

    def counted(model_type, line, version):
        calls["n"] += 1
        return real_download(model_type, line, version)

    monkeypatch.setattr(fraud_registry, "download_artifact", counted)
    refresh_from_manifest()
    assert calls["n"] == 0


def test_refresh_from_manifest_swallows_read_errors(fake_minio, monkeypatch):
    """A MinIO outage must NOT crash the poll loop or clear the cache."""
    def boom():
        raise RuntimeError("simulated MinIO outage")
    monkeypatch.setattr(fraud_registry, "load_manifest", boom)
    # Should return quietly.
    refresh_from_manifest()


# ── FraudService integration ────────────────────────────────────────────


@pytest.mark.parametrize("line", list(InsuranceLine))
def test_fraud_service_uses_line_resolved_model(fake_minio, line):
    """Every line resolves through the registry and returns *some* model."""
    from uuid import uuid4

    from app.schemas.fraud import FraudCheckRequest
    from app.services.fraud_service import FraudService

    lf_by_line: dict[InsuranceLine, dict] = {
        InsuranceLine.HEALTH:     {"diagnosis_codes": ["K35"], "procedure_codes": ["23410"]},
        InsuranceLine.VEHICLE:    {"damage_type": "collision", "part_codes": ["A"],
                                   "cause_of_loss": "collision", "odometer_km": 12000},
        InsuranceLine.PROPERTY:   {"damage_type": "fire", "peril": "fire", "repair_category": "structural"},
        InsuranceLine.LIFE:       {"benefit_type": "NATURAL", "cause": "myocardial_infarction"},
        InsuranceLine.FUNERAL:    {"benefit_tier": "STANDARD", "deceased_relationship": "self"},
        InsuranceLine.DISABILITY: {"benefit_type": "TOTAL", "cause": "injury", "waiting_period_days": 30},
        InsuranceLine.TRAVEL:     {"coverage_category": "STANDARD", "destination_country": "ZA",
                                   "trip_duration_days": 7},
        InsuranceLine.GROUP:      {"underlying_line": "HEALTH", "diagnosis_codes": ["K35"],
                                   "procedure_codes": ["23410"]},
    }
    request = FraudCheckRequest(
        claim_id=uuid4(), insurance_line=line, subject_id=uuid4(),
        provider_id=uuid4(), claimed_amount=200.0, currency_code="USD",
        service_date="2026-03-01",
        line_features=lf_by_line[line],
    )
    service = FraudService()

    import asyncio
    result = asyncio.run(service.check_fraud_request(request, "test-tenant"))
    # Without any artifact seeded, every line falls back to canonical.
    assert result["model_version"] == CANONICAL_FALLBACK_VERSION
    assert 0.0 <= result["risk_score"] <= 1.0
    assert result["insurance_line"] == line.value


def test_fraud_service_returns_trained_version_for_seeded_line(
    fake_minio, sample_frame,
):
    from uuid import uuid4

    from app.schemas.fraud import FraudCheckRequest
    from app.services.fraud_service import FraudService

    _seed_trained_artifact(InsuranceLine.HEALTH, "v2", sample_frame)
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v2")

    request = FraudCheckRequest(
        claim_id=uuid4(), insurance_line=InsuranceLine.HEALTH,
        subject_id=uuid4(), provider_id=uuid4(),
        claimed_amount=1500.0, currency_code="USD",
        service_date="2026-03-01",
        line_features={"diagnosis_codes": ["K35"], "procedure_codes": ["23410"]},
    )
    service = FraudService()

    import asyncio
    result = asyncio.run(service.check_fraud_request(request, "test-tenant"))
    assert result["model_version"] == "fraud-health-v2"
