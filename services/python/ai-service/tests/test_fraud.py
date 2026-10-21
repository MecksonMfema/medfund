"""Endpoint tests for /api/v1/ai/fraud/check (line-aware)."""
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def _payload(**overrides):
    payload = {
        "claim_id": "00000000-0000-0000-0000-000000000001",
        "insurance_line": "HEALTH",
        "subject_id": "00000000-0000-0000-0000-000000000002",
        "provider_id": "00000000-0000-0000-0000-000000000003",
        "claimed_amount": 200.00,
        "currency_code": "USD",
        "service_date": "2026-03-01",
        "line_features": {"diagnosis_codes": ["K35"], "procedure_codes": ["23410"]},
    }
    payload.update(overrides)
    return payload


def test_fraud_check_health_low_risk():
    r = client.post(
        "/api/v1/ai/fraud/check",
        json=_payload(),
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert r.status_code == 200
    data = r.json()
    assert data["insurance_line"] == "HEALTH"
    assert 0.0 <= data["risk_score"] <= 1.0
    assert data["risk_level"] in ("LOW", "MEDIUM")
    assert data["model_version"] == "fraud-isolation-forest-v1-canonical"


def test_fraud_check_health_high_value_indicators():
    r = client.post(
        "/api/v1/ai/fraud/check",
        json=_payload(
            claimed_amount=50000.00,
            line_features={
                "diagnosis_codes": ["K35"],
                "procedure_codes": ["0190", "0191", "0192", "0193", "0194", "0195"],
            },
        ),
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert r.status_code == 200
    data = r.json()
    assert "high_value_claim" in data["indicators"]
    assert "many_procedures" in data["indicators"]


def test_fraud_check_is_deterministic():
    body = _payload(claimed_amount=1500.0)
    r1 = client.post("/api/v1/ai/fraud/check", json=body,
                     headers={"X-Tenant-ID": "test-tenant"})
    r2 = client.post("/api/v1/ai/fraud/check", json=body,
                     headers={"X-Tenant-ID": "test-tenant"})
    assert r1.status_code == r2.status_code == 200
    assert r1.json()["risk_score"] == r2.json()["risk_score"]


def test_fraud_check_vehicle_high_value_theft():
    r = client.post(
        "/api/v1/ai/fraud/check",
        json=_payload(
            insurance_line="VEHICLE",
            claimed_amount=20000.00,
            line_features={
                "damage_type": "theft",
                "part_codes": [],
                "cause_of_loss": "theft",
                "odometer_km": 45000,
            },
        ),
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert r.status_code == 200
    data = r.json()
    assert data["insurance_line"] == "VEHICLE"
    assert "high_value_theft" in data["indicators"]


def test_fraud_check_property_catastrophic_peril():
    r = client.post(
        "/api/v1/ai/fraud/check",
        json=_payload(
            insurance_line="PROPERTY",
            claimed_amount=75000.00,
            line_features={
                "damage_type": "fire",
                "peril": "fire",
                "repair_category": "structural",
            },
        ),
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert r.status_code == 200
    data = r.json()
    assert data["insurance_line"] == "PROPERTY"
    assert "catastrophic_peril_high_value" in data["indicators"]


def test_fraud_check_group_dispatches_via_underlying_line():
    r = client.post(
        "/api/v1/ai/fraud/check",
        json=_payload(
            insurance_line="GROUP",
            claimed_amount=1500.00,
            line_features={
                "underlying_line": "LIFE",
                "benefit_type": "NATURAL",
                "cause": "myocardial_infarction",
            },
        ),
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert r.status_code == 200
    data = r.json()
    assert data["insurance_line"] == "GROUP"
    assert 0.0 <= data["risk_score"] <= 1.0
