from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_recommend_adjudication_approve():
    response = client.post(
        "/api/v1/ai/adjudication/recommend",
        json={
            "claim_id": "CLM-001",
            "member_id": "MBR-001",
            "provider_id": "PRV-001",
            "diagnosis_codes": ["J06.9"],
            "procedure_codes": ["0190"],
            "claimed_amount": 500.00,
            "service_date": "2026-03-01",
        },
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["claim_id"] == "CLM-001"
    assert data["recommendation"] == "APPROVE"
    assert data["confidence"] > 0.5
    assert data["approved_amount"] == 500.00


def test_recommend_adjudication_review_high_value():
    response = client.post(
        "/api/v1/ai/adjudication/recommend",
        json={
            "claim_id": "CLM-002",
            "member_id": "MBR-001",
            "provider_id": "PRV-001",
            "diagnosis_codes": ["J06.9"],
            "procedure_codes": ["0190"],
            "claimed_amount": 15000.00,
            "service_date": "2026-03-01",
        },
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["recommendation"] == "REVIEW"
    assert "HIGH_VALUE_CLAIM" in data["flags"]


def test_recommend_adjudication_missing_diagnosis():
    response = client.post(
        "/api/v1/ai/adjudication/recommend",
        json={
            "claim_id": "CLM-003",
            "member_id": "MBR-001",
            "provider_id": "PRV-001",
            "diagnosis_codes": [],
            "procedure_codes": ["0190"],
            "claimed_amount": 200.00,
            "service_date": "2026-03-01",
        },
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["recommendation"] == "REVIEW"
    assert "MISSING_DIAGNOSIS" in data["flags"]


def test_recommend_missing_tenant_header():
    response = client.post(
        "/api/v1/ai/adjudication/recommend",
        json={
            "claim_id": "CLM-004",
            "member_id": "MBR-001",
            "provider_id": "PRV-001",
            "claimed_amount": 100.00,
            "service_date": "2026-03-01",
        },
    )
    assert response.status_code == 422  # missing required header
