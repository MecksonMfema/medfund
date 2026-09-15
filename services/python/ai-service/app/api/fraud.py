"""Line-aware fraud check endpoint.

Replaces the earlier hardcoded-formula + random-noise implementation.
Every request carries an ``insurance_line`` and a per-line ``line_features``
bag; scoring dispatches through ``FraudService.check_fraud_request`` which
feeds a canonical 5-tuple to the shared IsolationForest. Deterministic —
same input, same score.

Per Critical Rule #3 every response persists an audit row to
``ai_predictions_store`` (best-effort — a missing DB never fails the
request).
"""
from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Header
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.anonymize import anonymize_features
from app.core.database import get_optional_session
from app.schemas.fraud import FraudCheckRequest, FraudCheckResponse
from app.services.fraud_service import FraudService
from app.services.prediction_repository import try_record_ai_prediction

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/ai/fraud", tags=["Fraud Detection"])


def get_fraud_service() -> FraudService:
    return FraudService()


@router.post("/check", response_model=FraudCheckResponse)
async def check_fraud(
    request: FraudCheckRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    fraud_service: FraudService = Depends(get_fraud_service),
    session: AsyncSession | None = Depends(get_optional_session),
) -> FraudCheckResponse:
    """Line-aware fraud scoring. Response is deterministic."""
    result = await fraud_service.check_fraud_request(request, x_tenant_id)

    response = FraudCheckResponse(
        claim_id=request.claim_id,
        insurance_line=request.insurance_line,
        risk_score=result["risk_score"],
        risk_level=result["risk_level"],
        indicators=result["indicators"],
        model_version=result["model_version"],
    )

    # Audit — line_features and canonical_features go into the stored bag
    # for later per-line training data. anonymize_features strips
    # member/subject/provider/claim IDs.
    input_bag = anonymize_features(request.model_dump(mode="json"))
    output_bag = response.model_dump(mode="json")
    output_bag["line_features"] = result.get("line_features", {})
    output_bag["canonical_features"] = result.get("canonical_features", [])
    await try_record_ai_prediction(
        session,
        tenant_id=x_tenant_id,
        insurance_line=request.insurance_line.value,
        entity_type="claim",
        entity_id=str(request.claim_id),
        prediction_type="fraud",
        model_version=result["model_version"],
        input_features=input_bag,
        output=output_bag,
        confidence=1.0 - result["risk_score"],
    )
    return response
