"""Tests for the canonical fraud ML model."""
from app.services.ml_models import FraudMLModel


def test_model_trains_successfully():
    model = FraudMLModel(seed=42)
    assert model._trained is True
    assert model.model_version == "fraud-isolation-forest-v1-canonical"


def test_predict_canonical_low_risk_normal_claim():
    model = FraudMLModel(seed=42)
    # amount, days_since_start, freq_30d, provider_flags, subject_flags
    result = model.predict_canonical([300.0, 500.0, 1.0, 0.0, 0.0])
    assert 0.0 <= result["risk_score"] <= 1.0
    assert result["risk_level"] in ("LOW", "MEDIUM")
    assert result["model_version"] == "fraud-isolation-forest-v1-canonical"


def test_predict_canonical_indicators_new_subject_high_frequency():
    model = FraudMLModel(seed=42)
    result = model.predict_canonical([50000.0, 30.0, 6.0, 1.0, 1.0])
    assert "new_subject" in result["indicators"]
    assert "high_frequency" in result["indicators"]
    assert "provider_flagged" in result["indicators"]
    assert "subject_flagged" in result["indicators"]


def test_predict_canonical_is_deterministic():
    model = FraudMLModel(seed=42)
    a = model.predict_canonical([1500.0, 200.0, 1.0, 0.0, 0.0])
    b = model.predict_canonical([1500.0, 200.0, 1.0, 0.0, 0.0])
    assert a["risk_score"] == b["risk_score"]
