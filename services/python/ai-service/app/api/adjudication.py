"""AI-assisted adjudication endpoints."""
from fastapi import APIRouter, Header
from pydantic import BaseModel
from typing import Optional
import logging

from app.services.adjudication_service import AdjudicationService
from app.services.duplicate_detection import DuplicateDetector
from app.core.gemini_client import gemini_client

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/ai/adjudication", tags=["AI Adjudication"])

_detector = DuplicateDetector()


class AdjudicationRequest(BaseModel):
    claim_id: str
    member_id: str
    provider_id: str
    diagnosis_codes: list[str] = []
    procedure_codes: list[str] = []
    claimed_amount: float
    currency_code: str = "USD"
    claim_type: str = "medical"
    service_date: str


class AdjudicationRecommendation(BaseModel):
    claim_id: str
    recommendation: str
    confidence: float
    approved_amount: Optional[float] = None
    reasoning: str
    flags: list[str] = []
    model_version: str = "1.0.0"


class DuplicateCheckRequest(BaseModel):
    claim: dict
    recent_claims: list[dict]


class TariffSuggestionRequest(BaseModel):
    description: str
    diagnosis_codes: list[str] = []


class TariffSuggestionResponse(BaseModel):
    suggestions: list[dict] = []
    source: str = "ai"


@router.post("/recommend", response_model=AdjudicationRecommendation)
async def recommend_adjudication(
    request: AdjudicationRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
):
    """AI-assisted adjudication recommendation."""
    svc = AdjudicationService(gemini_client)
    prediction = await svc.analyze_claim(request.model_dump(), x_tenant_id)

    output = prediction.output
    return AdjudicationRecommendation(
        claim_id=request.claim_id,
        recommendation=output.get("recommendation", "REVIEW"),
        confidence=output.get("confidence", 0.5),
        approved_amount=output.get("approved_amount"),
        reasoning=output.get("reasoning", ""),
        flags=output.get("flags", []),
        model_version=prediction.model_version,
    )


@router.post("/check-duplicate")
async def check_duplicate(
    request: DuplicateCheckRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
):
    """Check if a claim is a duplicate of recent claims."""
    result = _detector.check_duplicate(request.claim, request.recent_claims)
    return result


@router.post("/suggest-tariff", response_model=TariffSuggestionResponse)
async def suggest_tariff(
    request: TariffSuggestionRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
):
    """Suggest tariff codes for a service description using AI."""
    if gemini_client.available:
        result = await gemini_client.complete_json(
            system_prompt="You are a healthcare tariff code expert. Suggest appropriate procedure/tariff codes.",
            messages=[{"role": "user", "content": f"Suggest tariff codes for: {request.description}\nDiagnosis codes: {request.diagnosis_codes}"}],
        )
        if result and "suggestions" in result:
            return TariffSuggestionResponse(suggestions=result["suggestions"], source="ai")

    return TariffSuggestionResponse(
        suggestions=[{"note": "AI unavailable: manual tariff code lookup required"}],
        source="fallback",
    )
