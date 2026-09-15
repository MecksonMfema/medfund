"""Phase 2 unit tests — fraud trainer + artifact registry + promote flow."""
from __future__ import annotations

import io
import json
from typing import Any

import joblib
import numpy as np
import pandas as pd
import pytest
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import StandardScaler

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine

from scripts import _registry, promote_model, train_fraud
from scripts._corpus import FRAUD_FEATURE_COLS
from tests.scripts._fake_minio import FakeMinioClient


@pytest.fixture
def fake_minio(monkeypatch):
    fake = FakeMinioClient()
    monkeypatch.setattr(_registry, "_client", fake)
    monkeypatch.setattr(_registry, "_ml_client", lambda: fake)
    yield fake


def _mk_frame(n_positive: int, n_negative: int, seed: int = 7) -> pd.DataFrame:
    """Build a synthetic labeled corpus with two well-separated classes."""
    rng = np.random.RandomState(seed)
    positive = pd.DataFrame({
        "amount":              rng.lognormal(8, 0.4, n_positive),
        "days_since_start":    rng.uniform(10, 90, n_positive),
        "claim_frequency_30d": rng.poisson(5.0, n_positive),
        "provider_flag_count": rng.poisson(1.5, n_positive),
        "subject_flag_count":  rng.poisson(1.5, n_positive),
        "label":               1,
        "tenant_id":           ["tenant-A"] * n_positive,
    })
    negative = pd.DataFrame({
        "amount":              rng.lognormal(5, 0.6, n_negative),
        "days_since_start":    rng.uniform(200, 2000, n_negative),
        "claim_frequency_30d": rng.poisson(0.5, n_negative),
        "provider_flag_count": rng.poisson(0.05, n_negative),
        "subject_flag_count":  rng.poisson(0.05, n_negative),
        "label":               0,
        "tenant_id":           ["tenant-A"] * n_negative,
    })
    return pd.concat([positive, negative], ignore_index=True)


# ── Trainer ─────────────────────────────────────────────────────────────────


def test_train_writes_expected_artifact_shape():
    args = train_fraud.TrainerArgs(
        line=InsuranceLine.HEALTH, output_version="v-test",
        min_samples=50, seed=42,
    )
    frame = _mk_frame(n_positive=100, n_negative=400)
    result = train_fraud.train(args, frame)

    artifact = joblib.load(io.BytesIO(result.artifact_bytes))
    assert isinstance(artifact["model"], RandomForestClassifier)
    assert isinstance(artifact["scaler"], StandardScaler)
    assert isinstance(artifact["calibrator"], CalibratedClassifierCV)
    assert artifact["feature_names"] == FRAUD_FEATURE_COLS
    assert artifact["canonical_features_schema"] == settings.canonical_features_schema_version
    assert artifact["model_version"] == "fraud-health-v-test"


def test_train_metadata_contains_expected_fields():
    args = train_fraud.TrainerArgs(
        line=InsuranceLine.VEHICLE, output_version="v1",
        min_samples=50, seed=42,
    )
    frame = _mk_frame(n_positive=100, n_negative=400)
    result = train_fraud.train(args, frame)

    md = result.metadata
    assert md["model_version"] == "fraud-vehicle-v1"
    assert md["line"] == "VEHICLE"
    assert md["model_type"] == "fraud"
    assert md["feature_names"] == FRAUD_FEATURE_COLS
    assert md["canonical_features_schema"] == settings.canonical_features_schema_version
    assert md["seed"] == 42
    assert md["train_samples"] > 0
    assert md["val_samples"] > 0
    assert md["test_samples"] > 0
    assert "precision" in md["val_metrics"]
    assert "recall" in md["val_metrics"]
    assert "auc" in md["val_metrics"]
    assert md["trained_at"]  # non-empty ISO timestamp


def test_train_fails_below_min_samples():
    args = train_fraud.TrainerArgs(
        line=InsuranceLine.HEALTH, output_version="v-test",
        min_samples=200, seed=42,
    )
    frame = _mk_frame(n_positive=10, n_negative=50)  # 60 rows < 200 min
    with pytest.raises(ValueError, match="insufficient data"):
        train_fraud.train(args, frame)


def test_train_calibrated_probabilities_are_valid():
    args = train_fraud.TrainerArgs(
        line=InsuranceLine.HEALTH, output_version="v-test",
        min_samples=50, seed=42,
    )
    frame = _mk_frame(n_positive=100, n_negative=400)
    result = train_fraud.train(args, frame)

    artifact = joblib.load(io.BytesIO(result.artifact_bytes))
    X = frame[FRAUD_FEATURE_COLS].to_numpy(dtype=float)
    X_scaled = artifact["scaler"].transform(X)
    proba = artifact["calibrator"].predict_proba(X_scaled)
    assert proba.shape == (len(frame), 2)
    assert (proba >= 0).all() and (proba <= 1).all()
    assert np.allclose(proba.sum(axis=1), 1.0)


def test_train_deterministic_across_runs():
    args = train_fraud.TrainerArgs(
        line=InsuranceLine.HEALTH, output_version="v1",
        min_samples=50, seed=42,
    )
    frame = _mk_frame(n_positive=100, n_negative=400)
    a = train_fraud.train(args, frame)
    b = train_fraud.train(args, frame)
    assert a.metadata["val_metrics"] == b.metadata["val_metrics"]


# ── Registry ────────────────────────────────────────────────────────────────


def test_upload_and_download_roundtrip(fake_minio):
    upload = _registry.ArtifactUpload(
        model_type="fraud",
        line=InsuranceLine.HEALTH,
        version="v1",
        joblib_bytes=b"fake-joblib-bytes",
        metadata={"model_version": "fraud-health-v1", "auc": 0.85},
    )
    ref = _registry.upload_artifact(upload)
    assert ref == f"s3://{settings.model_artifacts_bucket}/fraud-health-v1.joblib"
    assert _registry.artifact_exists("fraud", InsuranceLine.HEALTH, "v1")

    downloaded = _registry.download_artifact("fraud", InsuranceLine.HEALTH, "v1")
    assert downloaded == b"fake-joblib-bytes"

    metadata = _registry.download_metadata("fraud", InsuranceLine.HEALTH, "v1")
    assert metadata["auc"] == 0.85


def test_manifest_empty_when_absent(fake_minio):
    assert _registry.load_manifest() == {}
    assert _registry.active_version("fraud", InsuranceLine.HEALTH) is None


def test_promote_manifest_entry_records_before_after(fake_minio):
    before, after = _registry.promote_manifest_entry(
        "fraud", InsuranceLine.HEALTH, "v1",
    )
    assert before is None
    assert after == "v1"

    before2, after2 = _registry.promote_manifest_entry(
        "fraud", InsuranceLine.HEALTH, "v2",
    )
    assert before2 == "v1"
    assert after2 == "v2"
    assert _registry.active_version("fraud", InsuranceLine.HEALTH) == "v2"


def test_object_name_lowercases_line():
    assert _registry.object_name_for("fraud", InsuranceLine.HEALTH, "v1") == "fraud-health-v1.joblib"
    assert _registry.object_name_for("fraud", InsuranceLine.VEHICLE, "v2") == "fraud-vehicle-v2.joblib"
    assert _registry.metadata_name_for("fraud", InsuranceLine.HEALTH, "v1") == "fraud-health-v1.metadata.json"


# ── Promote flow ────────────────────────────────────────────────────────────


def _seed_artifact(fake: FakeMinioClient, line: InsuranceLine, version: str) -> None:
    _registry.upload_artifact(_registry.ArtifactUpload(
        model_type="fraud", line=line, version=version,
        joblib_bytes=b"fake", metadata={"model_version": f"fraud-{line.value.lower()}-{version}"},
    ))


def test_promote_missing_artifact_raises(fake_minio):
    args = promote_model.PromoteArgs(
        model_type="fraud", line=InsuranceLine.HEALTH, version="v-nope",
        actor_id="user-1", actor_email="a@b.c", reason=None,
        tenant_id="platform", correlation_id="cid-1",
    )
    with pytest.raises(FileNotFoundError):
        promote_model.promote(args, publish=False)


def test_promote_updates_manifest_and_emits_audit_event(fake_minio):
    _seed_artifact(fake_minio, InsuranceLine.HEALTH, "v1")
    _seed_artifact(fake_minio, InsuranceLine.HEALTH, "v2")

    # First promotion — no `before`.
    args = promote_model.PromoteArgs(
        model_type="fraud", line=InsuranceLine.HEALTH, version="v1",
        actor_id="user-1", actor_email="admin@example.com",
        reason="pilot bootstrap",
        tenant_id="platform", correlation_id="cid-1",
    )
    before, after, event = promote_model.promote(args, publish=False)
    assert before is None
    assert after == "v1"

    assert event["entityType"] == "AI_MODEL"
    assert event["entityName"] == "fraud model for HEALTH"
    assert event["actorId"] == "user-1"
    assert event["actorEmail"] == "admin@example.com"
    assert event["action"] == "PROMOTE"
    assert event["newValue"]["active_version"] == "v1"
    assert event["oldValue"] == {}

    # Second promotion — before/after populated.
    args = promote_model.PromoteArgs(
        model_type="fraud", line=InsuranceLine.HEALTH, version="v2",
        actor_id="user-1", actor_email="admin@example.com",
        reason=None,
        tenant_id="platform", correlation_id="cid-2",
    )
    before, after, event = promote_model.promote(args, publish=False)
    assert before == "v1"
    assert after == "v2"
    assert event["oldValue"] == {"active_version": "v1"}
    assert event["newValue"]["active_version"] == "v2"


def test_promote_entity_name_is_friendly_text_not_uuid():
    """Guard against feedback_audit_entity_name — never the UUID."""
    args = promote_model.PromoteArgs(
        model_type="fraud", line=InsuranceLine.VEHICLE, version="v3",
        actor_id="u", actor_email="a@b.c", reason=None,
        tenant_id="platform", correlation_id="cid",
    )
    ev = promote_model._audit_event(args, before="v2", after="v3")
    assert ev["entityName"] == "fraud model for VEHICLE"
    # entityId is the composite key — informative, not a raw UUID:
    assert ev["entityId"] == "fraud/VEHICLE"


def test_promote_audit_event_actor_email_present():
    """Guard against feedback_audit_actor_email — never null."""
    args = promote_model.PromoteArgs(
        model_type="fraud", line=InsuranceLine.HEALTH, version="v1",
        actor_id="u", actor_email="reviewer@example.com",
        reason=None, tenant_id="platform", correlation_id="cid",
    )
    ev = promote_model._audit_event(args, before=None, after="v1")
    assert ev["actorEmail"] == "reviewer@example.com"


def test_promote_cli_rejects_missing_actor_email():
    with pytest.raises(SystemExit):
        promote_model._parse_args([
            "--model-type", "fraud",
            "--line", "HEALTH",
            "--version", "v1",
            "--actor-id", "u",
            "--actor-email", "",
        ])
