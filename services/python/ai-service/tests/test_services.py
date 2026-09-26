import pytest

from app.services.adjudication_service import AdjudicationService
from app.services.fraud_service import FraudService


@pytest.mark.asyncio
async def test_adjudication_service_approve():
    svc = AdjudicationService()
    prediction = await svc.analyze_claim(
        {"claim_id": "CLM-1", "claimed_amount": 500, "diagnosis_codes": ["J06.9"]}, "test-tenant"
    )
    assert prediction.entity_id == "CLM-1"
    assert prediction.output["recommendation"] == "APPROVE"
    assert prediction.confidence > 0.5


@pytest.mark.asyncio
async def test_adjudication_service_review():
    svc = AdjudicationService()
    prediction = await svc.analyze_claim(
        {"claim_id": "CLM-2", "claimed_amount": 15000}, "test-tenant"
    )
    assert prediction.output["recommendation"] == "REVIEW"
    assert "HIGH_VALUE_CLAIM" in prediction.output["flags"]


@pytest.mark.asyncio
async def test_fraud_service_low_risk():
    svc = FraudService()
    prediction = await svc.detect_fraud(
        {"claim_id": "CLM-1", "claimed_amount": 200, "procedure_codes": ["0190"]},
        "test-tenant",
    )
    assert prediction.output["risk_level"] in ["LOW", "MEDIUM"]


@pytest.mark.asyncio
async def test_fraud_service_high_risk():
    svc = FraudService()
    prediction = await svc.detect_fraud(
        {
            "claim_id": "CLM-2",
            "claimed_amount": 50000,
            "procedure_codes": ["a", "b", "c", "d", "e", "f"],
        },
        "test-tenant",
    )
    assert prediction.output["risk_score"] > 0.2
