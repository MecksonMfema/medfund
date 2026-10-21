"""AI-assisted adjudication endpoints.

Every response persists an audit row per Critical Rule #3 via
``try_record_ai_prediction`` — best-effort so a missing DB never fails
the request.
"""
from __future__ import annotations

import hashlib
import logging
from typing import Any

from fastapi import APIRouter, Depends, Header
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.anonymize import anonymize_features
from app.core.database import get_optional_session
from app.core.llm_dispatch import resolve_llm
from app.data.code_hints import suggest_for_line
from app.schemas.insurance_line import InsuranceLine
from app.services.adjudication_service import AdjudicationService
from app.services.duplicate_detection import DuplicateDetector
from app.services.prediction_repository import try_record_ai_prediction

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/ai/adjudication", tags=["AI Adjudication"])

_detector = DuplicateDetector()


class AdjudicationRequest(BaseModel):
    claim_id: str
    insurance_line: InsuranceLine = InsuranceLine.HEALTH
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
    approved_amount: float | None = None
    reasoning: str
    flags: list[str] = []
    model_version: str = "1.0.0"


class DuplicateCheckRequest(BaseModel):
    claim: dict
    recent_claims: list[dict]


class CodeSuggestionRequest(BaseModel):
    """Line-aware code-suggestion request.

    HEALTH:      line_context = {"diagnosis_codes": ["K35.0"]}
    VEHICLE:     line_context = {"damage_type": "collision", "cause_of_loss": "accident"}
    PROPERTY:    line_context = {"peril": "fire", "damage_type": "structural"}
    LIFE:        line_context = {"benefit_type": "NATURAL", "cause": "..."}
    FUNERAL:     line_context = {"benefit_tier": "STANDARD"}
    DISABILITY:  line_context = {"benefit_type": "TEMPORARY", "waiting_period_days": 30}
    TRAVEL:      line_context = {"coverage_category": "MEDICAL"}
    GROUP:       line_context = {"underlying_line": "LIFE", ...}
    """

    description: str
    insurance_line: InsuranceLine = InsuranceLine.HEALTH
    line_context: dict[str, Any] = Field(default_factory=dict)
    # Legacy HEALTH-only alias — merged into line_context on the way in.
    diagnosis_codes: list[str] = Field(default_factory=list)


class CodeSuggestion(BaseModel):
    code: str
    label: str
    rationale: str = ""
    confidence: float = 0.0


class CodeSuggestionResponse(BaseModel):
    suggestions: list[CodeSuggestion] = []
    source: str = "rules"
    insurance_line: InsuranceLine = InsuranceLine.HEALTH
    model_version: str = "code-suggester-health-rules-v1"


def _hash_id(*parts: str) -> str:
    return hashlib.sha1("|".join(parts).encode("utf-8")).hexdigest()


def _line_prompt_system(line: InsuranceLine) -> str:
    if line == InsuranceLine.HEALTH:
        return ("You are an AHFOZ tariff-code expert. Given an ICD-10 code and description, "
                "suggest 3-5 relevant AHFOZ procedure/tariff codes.")
    if line == InsuranceLine.VEHICLE:
        return ("You are a motor-insurance claims expert. Given damage details, suggest "
                "3-5 repair-part codes or labor line items.")
    if line == InsuranceLine.PROPERTY:
        return ("You are a property-insurance claims expert. Given the peril and damage, "
                "suggest 3-5 damage-category codes covering structure and contents.")
    if line == InsuranceLine.LIFE:
        return ("You are a life-insurance benefits expert. Given the benefit type and cause, "
                "suggest 1-3 benefit codes.")
    if line == InsuranceLine.FUNERAL:
        return ("You are a funeral-cover benefits expert. Given the benefit tier, suggest "
                "3-5 service codes.")
    if line == InsuranceLine.DISABILITY:
        return ("You are a disability-benefits expert. Given benefit type and waiting period, "
                "suggest 1-3 benefit codes.")
    if line == InsuranceLine.TRAVEL:
        return ("You are a travel-insurance benefits expert. Given the coverage category, "
                "suggest 2-4 payout codes.")
    return ("You are an insurance benefits expert. Given the underlying line and context, "
            "suggest 1-5 relevant codes.")


def _build_line_context(request: CodeSuggestionRequest) -> dict[str, Any]:
    """Merge legacy `diagnosis_codes` into `line_context` for backwards compatibility."""
    context = dict(request.line_context or {})
    if request.diagnosis_codes and "diagnosis_codes" not in context:
        context["diagnosis_codes"] = list(request.diagnosis_codes)
    return context


@router.post("/recommend", response_model=AdjudicationRecommendation)
async def recommend_adjudication(
    request: AdjudicationRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    session: AsyncSession | None = Depends(get_optional_session),
):
    """AI-assisted adjudication recommendation."""
    svc = AdjudicationService(resolve_llm())
    prediction = await svc.analyze_claim(request.model_dump(), x_tenant_id)

    output = prediction.output
    response = AdjudicationRecommendation(
        claim_id=request.claim_id,
        recommendation=output.get("recommendation", "REVIEW"),
        confidence=output.get("confidence", 0.5),
        approved_amount=output.get("approved_amount"),
        reasoning=output.get("reasoning", ""),
        flags=output.get("flags", []),
        model_version=prediction.model_version,
    )

    await try_record_ai_prediction(
        session,
        tenant_id=x_tenant_id,
        insurance_line=request.insurance_line.value,
        entity_type="claim",
        entity_id=request.claim_id,
        prediction_type="adjudication",
        model_version=prediction.model_version,
        input_features=anonymize_features(request.model_dump()),
        output=response.model_dump(),
        confidence=response.confidence,
    )
    return response


@router.post("/check-duplicate")
async def check_duplicate(
    request: DuplicateCheckRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    session: AsyncSession | None = Depends(get_optional_session),
):
    """Check if a claim is a duplicate of recent claims."""
    result = _detector.check_duplicate(request.claim, request.recent_claims)

    claim_id = str(request.claim.get("id") or request.claim.get("claim_id") or "unknown")
    line_code = request.claim.get("insurance_line") or InsuranceLine.HEALTH.value
    await try_record_ai_prediction(
        session,
        tenant_id=x_tenant_id,
        insurance_line=line_code,
        entity_type="claim",
        entity_id=claim_id,
        prediction_type="duplicate",
        model_version=str(result.get("model_version", "duplicate-v1")),
        input_features=anonymize_features(request.model_dump()),
        output=result if isinstance(result, dict) else {"result": result},
        confidence=None,
    )
    return result


async def _suggest_codes_impl(
    request: CodeSuggestionRequest,
    x_tenant_id: str,
    session: AsyncSession | None,
) -> CodeSuggestionResponse:
    line = request.insurance_line
    context = _build_line_context(request)
    source = "rules"
    model_version = f"code-suggester-{line.value.lower()}-rules-v1"
    suggestions: list[CodeSuggestion] = []

    llm = resolve_llm()
    if llm.available:
        try:
            result = await llm.complete_json(
                system_prompt=_line_prompt_system(line),
                messages=[{
                    "role": "user",
                    "content": (
                        f"Line: {line.value}\n"
                        f"Description: {request.description}\n"
                        f"Context: {context}\n"
                        "Respond with JSON: {\"suggestions\": [{\"code\", \"label\", "
                        "\"rationale\", \"confidence\"}, ...]}"
                    ),
                }],
            )
            if result and isinstance(result.get("suggestions"), list):
                for item in result["suggestions"]:
                    if isinstance(item, dict) and "code" in item:
                        suggestions.append(CodeSuggestion(
                            code=str(item.get("code")),
                            label=str(item.get("label", "")),
                            rationale=str(item.get("rationale", "")),
                            confidence=float(item.get("confidence", 0.5)),
                        ))
                if suggestions:
                    source = "llm"
                    model_version = f"code-suggester-{line.value.lower()}-llm-v1"
        except Exception:
            logger.exception("LLM code-suggestion failed for line %s; falling back", line.value)

    if not suggestions:
        seed = suggest_for_line(line, context)
        suggestions = [CodeSuggestion(**s.to_dict()) for s in seed]

    response = CodeSuggestionResponse(
        suggestions=suggestions,
        source=source,
        insurance_line=line,
        model_version=model_version,
    )

    max_conf = max((s.confidence for s in suggestions), default=0.0)
    await try_record_ai_prediction(
        session,
        tenant_id=x_tenant_id,
        insurance_line=line.value,
        entity_type="claim",
        entity_id=_hash_id(request.description, line.value),
        prediction_type="code_suggestion",
        model_version=model_version,
        input_features=anonymize_features({
            "description": request.description,
            "insurance_line": line.value,
            "line_context": context,
        }),
        output=response.model_dump(),
        confidence=max_conf or None,
    )
    return response


@router.post("/suggest-codes", response_model=CodeSuggestionResponse)
async def suggest_codes(
    request: CodeSuggestionRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    session: AsyncSession | None = Depends(get_optional_session),
):
    """Suggest domain codes for a claim description, dispatched per insurance_line.

    Rule fallback returns non-empty suggestions for every line when the LLM is
    unavailable, thanks to per-line seed data in ``app/data/code_hints``.
    """
    return await _suggest_codes_impl(request, x_tenant_id, session)
