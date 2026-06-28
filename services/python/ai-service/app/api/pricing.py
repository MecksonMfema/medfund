"""Pricing risk-score endpoint.

Returns a multiplier that the billing engine applies to the base
contribution amount. Inputs are the per-member signals
ContributionFactBuilder already projects from members.medical_history;
output is a single float in [0.5, 3.0] with a human-readable rationale
list of the factors that contributed.

Implementation today is rule-based — no LLM call yet — so the response
is deterministic and self-explanatory in the rationale. The shape
matches what a future Gemini-backed scorer would return, so swapping
the engine is a one-method change without touching the contract.

Per the platform's audit/AI traceability rule (CLAUDE.md): every
score returned MUST carry the model version + the input features +
the resulting multiplier so the billing-side audit row can persist
them alongside the contribution. {@code model_version} stays at
``rule-v1`` until a real model lands.
"""

from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, status
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/v1/pricing", tags=["pricing"])


# ── Request / response models ────────────────────────────────────────


class ScoreRequest(BaseModel):
    """Per-member signals the billing engine has at the time of scoring.

    Every field is optional so a sparse profile still scores — missing
    signals just don't contribute to the multiplier.
    """

    member_id: str = Field(..., description="UUID of the member being scored")
    tenant_id: str = Field(..., description="Tenant the member belongs to")
    base_amount: float = Field(
        ..., gt=0, description="Scheme-default amount the multiplier will scale"
    )
    currency_code: str = Field(..., min_length=3, max_length=3)

    age: Optional[int] = Field(None, description="Member's age at start of billing period")
    gender: Optional[str] = Field(None)
    dependant_count: int = Field(0)
    chronic_condition_count: int = Field(0)
    smoking_status: Optional[str] = Field(
        None, description="NEVER | FORMER | CURRENT"
    )
    bmi: Optional[float] = Field(None)
    medication_count: int = Field(0)


class ScoreResponse(BaseModel):
    multiplier: float = Field(
        ..., description="What to multiply the base amount by; in [0.5, 3.0]"
    )
    adjusted_amount: float = Field(
        ..., description="base_amount * multiplier, rounded to 4dp"
    )
    rationale: list[str] = Field(
        default_factory=list,
        description="Each factor that moved the multiplier, in display order",
    )
    model_version: str = Field(
        default="rule-v1",
        description="Identifier for the scoring engine that produced this result. "
        "Captured on the audit trail so a future model can be back-tested.",
    )


# ── Rule-based scorer ────────────────────────────────────────────────


# Hard cap so a deeply degenerate profile can't quintuple a premium —
# tenant operators expect predictable bounds. Mirrors the
# {@code [0.5, 3.0]} range we'd impose on a future ML model.
MIN_MULTIPLIER = 0.5
MAX_MULTIPLIER = 3.0


def score(req: ScoreRequest) -> ScoreResponse:
    """Deterministic, rule-based risk scorer.

    Additive on log space would be more principled, but for an MVP the
    multiplicative-with-cap shape below is readable in the audit trail
    and easy for a tenant operator to reason about. The model_version
    in the response gives us a clean swap-in path when a real model
    lands.
    """

    multiplier = 1.0
    rationale: list[str] = []

    # Age — gentle U-curve; very young + older skew up.
    if req.age is not None:
        if req.age >= 65:
            multiplier *= 1.30
            rationale.append(f"Age {req.age} → +30% (senior risk)")
        elif req.age >= 50:
            multiplier *= 1.15
            rationale.append(f"Age {req.age} → +15% (mid-senior)")
        elif req.age < 5:
            multiplier *= 1.10
            rationale.append(f"Age {req.age} → +10% (paediatric)")

    # Chronic conditions — each one adds 10%, capped at +50%.
    if req.chronic_condition_count > 0:
        bump = min(0.10 * req.chronic_condition_count, 0.50)
        multiplier *= 1.0 + bump
        rationale.append(
            f"{req.chronic_condition_count} chronic condition(s) → +{int(bump * 100)}%"
        )

    # Smoking — CURRENT smokers carry a meaningful loading.
    if req.smoking_status == "CURRENT":
        multiplier *= 1.25
        rationale.append("Current smoker → +25%")
    elif req.smoking_status == "FORMER":
        multiplier *= 1.05
        rationale.append("Former smoker → +5%")

    # BMI — obese (>30) loads; severely underweight (<18.5) loads less.
    if req.bmi is not None:
        if req.bmi >= 35:
            multiplier *= 1.20
            rationale.append(f"BMI {req.bmi} → +20% (severely obese)")
        elif req.bmi >= 30:
            multiplier *= 1.10
            rationale.append(f"BMI {req.bmi} → +10% (obese)")
        elif req.bmi < 18.5:
            multiplier *= 1.05
            rationale.append(f"BMI {req.bmi} → +5% (underweight)")

    # Medication count — proxy for polypharmacy / complex case management.
    if req.medication_count >= 5:
        multiplier *= 1.10
        rationale.append(
            f"{req.medication_count} active medications → +10% (polypharmacy)"
        )

    # Clamp to the policy band.
    if multiplier > MAX_MULTIPLIER:
        rationale.append(f"Capped at {MAX_MULTIPLIER}× (policy ceiling)")
        multiplier = MAX_MULTIPLIER
    elif multiplier < MIN_MULTIPLIER:
        rationale.append(f"Floored at {MIN_MULTIPLIER}× (policy floor)")
        multiplier = MIN_MULTIPLIER

    if not rationale:
        rationale.append("No risk signals captured — baseline multiplier 1.0")

    return ScoreResponse(
        multiplier=round(multiplier, 4),
        adjusted_amount=round(req.base_amount * multiplier, 4),
        rationale=rationale,
    )


# ── Route ────────────────────────────────────────────────────────────


@router.post(
    "/score",
    response_model=ScoreResponse,
    status_code=status.HTTP_200_OK,
    summary="Compute a per-member risk multiplier for the contribution amount",
)
async def score_route(req: ScoreRequest) -> ScoreResponse:
    return score(req)
