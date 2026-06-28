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
    """Per-policy signals the billing engine has at the time of scoring.

    Generic by design — the platform covers multiple insurance lines
    (HEALTH, MOTOR, PROPERTY, LIFE, FUNERAL, …), each with its own
    signal vocabulary. Rather than pin the schema to HEALTH columns,
    the caller passes whatever signals are line-appropriate in
    ``attributes`` and the line-specific scorer reads what it knows.

    The HEALTH typed fields stay as well-known optional fields for
    back-compat with the rule-based scorer below — they just get
    mirrored into ``attributes`` if not supplied directly.

    Every field is optional so a sparse profile still scores.
    """

    member_id: Optional[str] = Field(
        None, description="UUID of the policy holder (member, vehicle owner, …)"
    )
    tenant_id: str = Field(..., description="Tenant the policy belongs to")
    insurance_line: Optional[str] = Field(
        None,
        description="HEALTH | MOTOR | PROPERTY | LIFE | FUNERAL | … — picks the line-specific scorer.",
    )
    base_amount: float = Field(
        ..., gt=0, description="Scheme-default amount the multiplier will scale"
    )
    currency_code: str = Field(..., min_length=3, max_length=3)

    # ── Generic attribute bag ───────────────────────────────────────
    # Line-agnostic catch-all. HEALTH fact builder populates
    # {"chronic_condition_count": 2, "smoking_status": "CURRENT", …}
    # MOTOR would populate {"vehicle.age": 12, "driver.claims_3y": 1, …}
    # The line's scorer picks what it knows; unknown keys are ignored.
    attributes: dict = Field(default_factory=dict)

    # ── Well-known HEALTH fields (back-compat) ──────────────────────
    # Older callers populate these directly. New callers SHOULD prefer
    # ``attributes``; the scorer reads either path.
    age: Optional[int] = Field(None)
    gender: Optional[str] = Field(None)
    dependant_count: int = Field(0)
    chronic_condition_count: int = Field(0)
    smoking_status: Optional[str] = Field(None)
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


def _attr(req: ScoreRequest, key: str, fallback=None):
    """Read a signal from req.attributes first, fall back to the typed
    field with the same name. Lets new callers stay generic while
    keeping the old HEALTH-typed API working unchanged."""
    if key in req.attributes and req.attributes[key] is not None:
        return req.attributes[key]
    return fallback


def score(req: ScoreRequest) -> ScoreResponse:
    """Route to the line-specific scorer; default to HEALTH when
    insurance_line is missing (matches the only line shipped today).

    Each line has its own scorer function. New lines slot in by adding
    another branch — same response shape, line-appropriate factors.
    """
    line = (req.insurance_line or "HEALTH").upper()
    if line == "HEALTH":
        return _score_health(req)
    if line == "MOTOR":
        return _score_motor(req)
    # Unknown line — neutral multiplier with a rationale so the audit
    # trail makes the gap obvious.
    return ScoreResponse(
        multiplier=1.0,
        adjusted_amount=round(req.base_amount, 4),
        rationale=[f"No scorer registered for line {line!r} — baseline 1.0"],
    )


def _clamp(multiplier: float, rationale: list[str]) -> float:
    if multiplier > MAX_MULTIPLIER:
        rationale.append(f"Capped at {MAX_MULTIPLIER}× (policy ceiling)")
        return MAX_MULTIPLIER
    if multiplier < MIN_MULTIPLIER:
        rationale.append(f"Floored at {MIN_MULTIPLIER}× (policy floor)")
        return MIN_MULTIPLIER
    return multiplier


def _as_int(v) -> Optional[int]:
    try:
        return int(v) if v is not None else None
    except (TypeError, ValueError):
        return None


def _as_float(v) -> Optional[float]:
    try:
        return float(v) if v is not None else None
    except (TypeError, ValueError):
        return None


def _score_health(req: ScoreRequest) -> ScoreResponse:
    """HEALTH risk scorer — age curve, chronic count, smoking, BMI,
    medication count. Reads signals from attributes first (line-
    agnostic API) and falls back to the typed back-compat fields.
    Deterministic; ready to be swapped for a Gemini-backed scorer
    behind the same contract.
    """

    multiplier = 1.0
    rationale: list[str] = []

    age = _as_int(_attr(req, "age", req.age))
    if age is not None:
        if age >= 65:
            multiplier *= 1.30
            rationale.append(f"Age {age} → +30% (senior risk)")
        elif age >= 50:
            multiplier *= 1.15
            rationale.append(f"Age {age} → +15% (mid-senior)")
        elif age < 5:
            multiplier *= 1.10
            rationale.append(f"Age {age} → +10% (paediatric)")

    chronic = _as_int(_attr(req, "chronic_condition_count", req.chronic_condition_count)) or 0
    if chronic > 0:
        bump = min(0.10 * chronic, 0.50)
        multiplier *= 1.0 + bump
        rationale.append(f"{chronic} chronic condition(s) → +{int(bump * 100)}%")

    smoking = _attr(req, "smoking_status", req.smoking_status)
    if smoking == "CURRENT":
        multiplier *= 1.25
        rationale.append("Current smoker → +25%")
    elif smoking == "FORMER":
        multiplier *= 1.05
        rationale.append("Former smoker → +5%")

    bmi = _as_float(_attr(req, "bmi", req.bmi))
    if bmi is not None:
        if bmi >= 35:
            multiplier *= 1.20
            rationale.append(f"BMI {bmi} → +20% (severely obese)")
        elif bmi >= 30:
            multiplier *= 1.10
            rationale.append(f"BMI {bmi} → +10% (obese)")
        elif bmi < 18.5:
            multiplier *= 1.05
            rationale.append(f"BMI {bmi} → +5% (underweight)")

    meds = _as_int(_attr(req, "medication_count", req.medication_count)) or 0
    if meds >= 5:
        multiplier *= 1.10
        rationale.append(f"{meds} active medications → +10% (polypharmacy)")

    multiplier = _clamp(multiplier, rationale)
    if not rationale:
        rationale.append("No risk signals captured — baseline multiplier 1.0")

    return ScoreResponse(
        multiplier=round(multiplier, 4),
        adjusted_amount=round(req.base_amount * multiplier, 4),
        rationale=rationale,
    )


def _score_motor(req: ScoreRequest) -> ScoreResponse:
    """MOTOR risk scorer — stub showing the line-extension pattern.

    Reads vehicle.age, vehicle.value, driver.age, driver.claims_3y
    from attributes. Real tariffs will be far more sophisticated
    (postal code risk band, vehicle make/model loss curves, telematics
    score) — this proves the routing wiring without committing to a
    pricing curve we haven't actually validated with an actuary.
    """

    multiplier = 1.0
    rationale: list[str] = []

    vehicle_age = _as_int(_attr(req, "vehicle.age"))
    if vehicle_age is not None:
        if vehicle_age >= 15:
            multiplier *= 1.30
            rationale.append(f"Vehicle age {vehicle_age}y → +30% (older fleet)")
        elif vehicle_age >= 8:
            multiplier *= 1.15
            rationale.append(f"Vehicle age {vehicle_age}y → +15%")
        elif vehicle_age <= 1:
            multiplier *= 1.10
            rationale.append(f"Vehicle age {vehicle_age}y → +10% (new-car loss curve)")

    driver_age = _as_int(_attr(req, "driver.age"))
    if driver_age is not None:
        if driver_age < 25:
            multiplier *= 1.40
            rationale.append(f"Driver age {driver_age} → +40% (young-driver risk)")
        elif driver_age >= 70:
            multiplier *= 1.20
            rationale.append(f"Driver age {driver_age} → +20% (senior driver)")

    claims = _as_int(_attr(req, "driver.claims_3y")) or 0
    if claims > 0:
        bump = min(0.20 * claims, 0.80)
        multiplier *= 1.0 + bump
        rationale.append(f"{claims} claim(s) in last 3y → +{int(bump * 100)}%")

    multiplier = _clamp(multiplier, rationale)
    if not rationale:
        rationale.append("No motor risk signals captured — baseline multiplier 1.0")

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
