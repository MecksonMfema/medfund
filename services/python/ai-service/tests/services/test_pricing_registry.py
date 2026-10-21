"""Phase 4 unit tests — pricing_registry.

Per G3 no trained pricing artifacts ship in Tranche 1, so every case
here exercises the rule-adapter fallback: manifest empty, manifest
absent, unreadable manifest, artifact download failing. These are the
paths the pilot will actually hit while pricing training waits for
loss-ratio labels.
"""
from __future__ import annotations

import io

import joblib
import pytest

from app.schemas.insurance_line import InsuranceLine
from app.services import pricing_registry
from app.services.pricing_registry import (
    RulePricingModel,
    cached_version,
    refresh_from_manifest,
    reset_for_tests,
    resolve_pricing_model,
    schema_mismatch_status,
)
from scripts import _registry
from tests.scripts._fake_minio import FakeMinioClient


@pytest.fixture
def fake_minio(monkeypatch):
    fake = FakeMinioClient()
    monkeypatch.setattr(_registry, "_client", fake)
    monkeypatch.setattr(_registry, "_ml_client", lambda: fake)
    reset_for_tests()
    yield fake
    reset_for_tests()


@pytest.mark.parametrize("line", list(InsuranceLine))
def test_resolver_returns_rule_adapter_for_every_line(fake_minio, line):
    model = resolve_pricing_model(line)
    assert isinstance(model, RulePricingModel)
    assert model.model_version == "rule-v1"
    assert cached_version(line) is None


def test_rule_adapter_score_delegates_to_free_function(fake_minio):
    from app.api.pricing import ScoreRequest

    req = ScoreRequest(
        tenant_id="t1", insurance_line="HEALTH", base_amount=100.0,
        currency_code="USD", age=70, chronic_condition_count=2,
        smoking_status="CURRENT",
    )
    model = resolve_pricing_model(InsuranceLine.HEALTH)
    response = model.score(req)
    assert response.model_version == "rule-v1"
    # Age 70 + chronic 2 + smoker → clearly above 1.0.
    assert response.multiplier > 1.0


def test_resolver_falls_back_when_manifest_unreadable(fake_minio, monkeypatch):
    def boom():
        raise RuntimeError("simulated MinIO outage")
    monkeypatch.setattr(pricing_registry, "load_manifest", boom)
    model = resolve_pricing_model(InsuranceLine.HEALTH)
    assert isinstance(model, RulePricingModel)


def test_resolver_falls_back_when_artifact_missing_from_bucket(fake_minio):
    """Manifest points at a version but the joblib isn't in the bucket."""
    _registry.promote_manifest_entry("pricing", InsuranceLine.HEALTH, "v1")
    model = resolve_pricing_model(InsuranceLine.HEALTH)
    assert isinstance(model, RulePricingModel)


def test_refresh_from_manifest_is_noop_when_no_pricing_entries(fake_minio):
    _registry.promote_manifest_entry("fraud", InsuranceLine.HEALTH, "v1")
    resolve_pricing_model(InsuranceLine.HEALTH)
    # Second refresh should still leave the rule adapter in place.
    refresh_from_manifest()
    assert isinstance(resolve_pricing_model(InsuranceLine.HEALTH), RulePricingModel)


def test_refresh_swallows_manifest_read_errors(fake_minio, monkeypatch):
    def boom():
        raise RuntimeError("simulated MinIO outage")
    monkeypatch.setattr(pricing_registry, "load_manifest", boom)
    refresh_from_manifest()  # must not raise


def test_schema_mismatch_recorded_when_trained_artifact_ships_with_wrong_schema(
    fake_minio,
):
    """Guards the G7 wiring so Tranche 2's trained pricing artifacts
    with a stale schema flip the badge red instead of silently serving."""
    buf = io.BytesIO()
    joblib.dump({
        "model":                     object(),
        "scaler":                    object(),
        "feature_names":             ["age"],
        "canonical_features_schema": "v99-future",
        "model_version":             "pricing-health-v-future",
    }, buf)
    _registry.upload_artifact(_registry.ArtifactUpload(
        model_type="pricing", line=InsuranceLine.HEALTH, version="v-future",
        joblib_bytes=buf.getvalue(),
        metadata={"model_version": "pricing-health-v-future"},
    ))
    _registry.promote_manifest_entry(
        "pricing", InsuranceLine.HEALTH, "v-future",
    )

    model = resolve_pricing_model(InsuranceLine.HEALTH)
    assert isinstance(model, RulePricingModel)  # fell back
    status = schema_mismatch_status("pricing", InsuranceLine.HEALTH)
    assert status is not None
    assert "v99-future" in status
