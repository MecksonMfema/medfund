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
    insurance_line is missing (HEALTH is the line shipped first;
    matching it keeps the older single-line callers working).

    Each line has its own scorer function with line-appropriate
    factors. Adding a line is one entry here + one scorer function +
    a FactBuilder on the Java side that populates the right
    attribute keys. All scorers share the same {multiplier,
    adjusted_amount, rationale, model_version} response shape so the
    billing-side audit trail is uniform.
    """
    line = (req.insurance_line or "HEALTH").upper()
    scorers = {
        "HEALTH":     _score_health,
        "MOTOR":      _score_motor,
        "LIFE":       _score_life,
        "PROPERTY":   _score_property,
        "FUNERAL":    _score_funeral,
        "TRAVEL":     _score_travel,
        "DISABILITY": _score_disability,
    }
    fn = scorers.get(line)
    if fn:
        return fn(req)
    # Unknown line — neutral multiplier with a rationale so the audit
    # trail makes the gap obvious. GROUP intentionally falls through:
    # a group policy is priced by its underlying line (group-LIFE,
    # group-HEALTH, …) and the billing engine should send that line.
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


def _score_life(req: ScoreRequest) -> ScoreResponse:
    """LIFE risk scorer.

    Mortality is the dominant cost driver. Factors per industry
    convention (Legal & General, Bandhan Life, NY Life): age (steep
    after 50), smoking (one of the strongest single signals — current
    smokers commonly pay 1.5–2× non-smoker rates), occupation hazard
    class, sum-assured band (high cover gets a small disproportionate
    load to fund larger expected payouts), and existing chronic
    conditions. Where the regulator permits, gender is a factor
    (female mortality < male at most ages); the platform keeps it
    optional so EU-style unisex tariffs work too.
    """

    multiplier = 1.0
    rationale: list[str] = []

    # Age — steeper than HEALTH; mortality compounds with age.
    age = _as_int(_attr(req, "age", req.age))
    if age is not None:
        if age >= 70:
            multiplier *= 2.00
            rationale.append(f"Age {age} → ×2.00 (mortality risk)")
        elif age >= 55:
            multiplier *= 1.50
            rationale.append(f"Age {age} → ×1.50")
        elif age >= 40:
            multiplier *= 1.15
            rationale.append(f"Age {age} → ×1.15")

    # Smoking — one of the single strongest signals in life pricing.
    smoking = _attr(req, "smoking_status", req.smoking_status)
    if smoking == "CURRENT":
        multiplier *= 1.65
        rationale.append("Current smoker → ×1.65")
    elif smoking == "FORMER":
        multiplier *= 1.10
        rationale.append("Former smoker → ×1.10")

    # Occupation hazard class: SEDENTARY (1.0), MANUAL (1.2),
    # HAZARDOUS (1.5), VERY_HAZARDOUS (2.0). Keys mirror standard
    # actuarial categorisation.
    occ = (_attr(req, "occupation.hazard_class") or "").upper() or None
    if occ:
        bumps = {"SEDENTARY": 1.0, "MANUAL": 1.20, "HAZARDOUS": 1.50, "VERY_HAZARDOUS": 2.00}
        if occ in bumps and bumps[occ] != 1.0:
            multiplier *= bumps[occ]
            rationale.append(f"Occupation {occ} → ×{bumps[occ]}")

    # Sum assured — small disproportionate load on very high covers
    # (insurer is more exposed to mortality variance on large policies).
    sum_assured = _as_float(_attr(req, "sum_assured"))
    if sum_assured is not None:
        if sum_assured >= 1_000_000:
            multiplier *= 1.10
            rationale.append(f"Sum assured {sum_assured:,.0f} → ×1.10 (large policy load)")

    chronic = _as_int(_attr(req, "chronic_condition_count", req.chronic_condition_count)) or 0
    if chronic > 0:
        bump = min(0.15 * chronic, 0.60)
        multiplier *= 1.0 + bump
        rationale.append(f"{chronic} chronic condition(s) → +{int(bump * 100)}%")

    multiplier = _clamp(multiplier, rationale)
    if not rationale:
        rationale.append("No life risk signals captured — baseline multiplier 1.0")
    return ScoreResponse(
        multiplier=round(multiplier, 4),
        adjusted_amount=round(req.base_amount * multiplier, 4),
        rationale=rationale,
    )


def _score_property(req: ScoreRequest) -> ScoreResponse:
    """PROPERTY (home / contents / buildings) risk scorer.

    Industry factors: construction type (brick > wood/thatch),
    roof material (thatch is high fire risk), location risk band
    (postal-code crime + flood + fire), security features count
    (alarms, electric fencing, response service — common in
    Southern African markets per fanews / hippo.co.za), property age,
    and occupancy status (vacant properties load heavily).
    """

    multiplier = 1.0
    rationale: list[str] = []

    construction = (_attr(req, "property.construction") or "").upper() or None
    if construction:
        bumps = {"BRICK": 1.0, "CONCRETE": 1.0, "WOOD": 1.30, "THATCH": 1.40, "STEEL": 1.05}
        if construction in bumps and bumps[construction] != 1.0:
            multiplier *= bumps[construction]
            rationale.append(f"Construction {construction} → ×{bumps[construction]}")

    roof = (_attr(req, "property.roof") or "").upper() or None
    if roof in ("THATCH", "GRASS"):
        multiplier *= 1.30
        rationale.append(f"Roof {roof} → ×1.30 (fire risk)")

    # Location risk band typically encodes crime + flood + fire from
    # postal code lookup. Tenant FactBuilder populates this from the
    # property's address.
    band = (_attr(req, "location.risk_band") or "").upper() or None
    if band == "HIGH":
        multiplier *= 1.40
        rationale.append("Location risk band HIGH → ×1.40")
    elif band == "MEDIUM":
        multiplier *= 1.15
        rationale.append("Location risk band MEDIUM → ×1.15")
    elif band == "LOW":
        multiplier *= 0.90
        rationale.append("Location risk band LOW → ×0.90 (discount)")

    # Security features — discount per active mitigation.
    security_count = _as_int(_attr(req, "property.security_features")) or 0
    if security_count >= 3:
        multiplier *= 0.85
        rationale.append(f"{security_count} security features → ×0.85 (discount)")
    elif security_count == 2:
        multiplier *= 0.95
        rationale.append("2 security features → ×0.95")

    age_years = _as_int(_attr(req, "property.age_years"))
    if age_years is not None and age_years >= 50:
        multiplier *= 1.15
        rationale.append(f"Property age {age_years}y → ×1.15 (older fabric)")

    occupancy = (_attr(req, "property.occupancy") or "").upper() or None
    if occupancy == "VACANT":
        multiplier *= 1.80
        rationale.append("Vacant property → ×1.80 (theft + unattended damage risk)")
    elif occupancy == "TENANT":
        multiplier *= 1.10
        rationale.append("Tenant-occupied → ×1.10")

    multiplier = _clamp(multiplier, rationale)
    if not rationale:
        rationale.append("No property risk signals captured — baseline multiplier 1.0")
    return ScoreResponse(
        multiplier=round(multiplier, 4),
        adjusted_amount=round(req.base_amount * multiplier, 4),
        rationale=rationale,
    )


def _score_funeral(req: ScoreRequest) -> ScoreResponse:
    """FUNERAL cover scorer.

    Funeral cover in Southern African markets is typically lightly
    underwritten (no medicals), priced on age + cover amount + lives
    covered. A simple health declaration ("any chronic condition?")
    optionally loads the premium. Family policies covering multiple
    lives carry a small per-life surcharge to fund concentration risk.
    """

    multiplier = 1.0
    rationale: list[str] = []

    age = _as_int(_attr(req, "age", req.age))
    if age is not None:
        if age >= 75:
            multiplier *= 1.80
            rationale.append(f"Age {age} → ×1.80")
        elif age >= 60:
            multiplier *= 1.40
            rationale.append(f"Age {age} → ×1.40")
        elif age >= 45:
            multiplier *= 1.15
            rationale.append(f"Age {age} → ×1.15")

    lives = _as_int(_attr(req, "lives_covered")) or 1
    if lives >= 6:
        multiplier *= 1.30
        rationale.append(f"{lives} lives covered → ×1.30 (extended family)")
    elif lives >= 4:
        multiplier *= 1.15
        rationale.append(f"{lives} lives covered → ×1.15")

    if _attr(req, "health_declaration.chronic") is True:
        multiplier *= 1.20
        rationale.append("Declared chronic condition → ×1.20")

    multiplier = _clamp(multiplier, rationale)
    if not rationale:
        rationale.append("No funeral risk signals captured — baseline multiplier 1.0")
    return ScoreResponse(
        multiplier=round(multiplier, 4),
        adjusted_amount=round(req.base_amount * multiplier, 4),
        rationale=rationale,
    )


def _score_travel(req: ScoreRequest) -> ScoreResponse:
    """TRAVEL insurance scorer.

    Trip-based pricing: duration (linear up to a 30-day breakpoint
    then steeper), destination risk band (medical-cost driver — North
    America is the canonical high band), traveler age (senior load),
    pre-existing condition declaration, and coverage level
    (BASIC / STANDARD / COMPREHENSIVE).
    """

    multiplier = 1.0
    rationale: list[str] = []

    duration_days = _as_int(_attr(req, "trip.duration_days")) or 0
    if duration_days > 60:
        multiplier *= 2.20
        rationale.append(f"Trip {duration_days}d → ×2.20")
    elif duration_days > 30:
        multiplier *= 1.50
        rationale.append(f"Trip {duration_days}d → ×1.50")
    elif duration_days > 14:
        multiplier *= 1.15
        rationale.append(f"Trip {duration_days}d → ×1.15")

    # Destination risk: NORTH_AMERICA + parts of Asia are high medical-
    # cost destinations; intra-Africa/Europe is moderate; domestic is low.
    destination = (_attr(req, "trip.destination_band") or "").upper() or None
    if destination == "NORTH_AMERICA":
        multiplier *= 1.80
        rationale.append("Destination North America → ×1.80 (medical cost)")
    elif destination == "EUROPE":
        multiplier *= 1.20
        rationale.append("Destination Europe → ×1.20")
    elif destination == "ASIA":
        multiplier *= 1.30
        rationale.append("Destination Asia → ×1.30")
    elif destination == "DOMESTIC":
        multiplier *= 0.90
        rationale.append("Domestic travel → ×0.90 (discount)")

    age = _as_int(_attr(req, "age", req.age))
    if age is not None and age >= 70:
        multiplier *= 1.50
        rationale.append(f"Traveler age {age} → ×1.50 (senior load)")
    elif age is not None and age >= 60:
        multiplier *= 1.25
        rationale.append(f"Traveler age {age} → ×1.25")

    if _attr(req, "pre_existing_declared") is True:
        multiplier *= 1.30
        rationale.append("Pre-existing condition declared → ×1.30")

    coverage = (_attr(req, "coverage_level") or "").upper() or None
    if coverage == "COMPREHENSIVE":
        multiplier *= 1.35
        rationale.append("Comprehensive cover → ×1.35")
    elif coverage == "BASIC":
        multiplier *= 0.85
        rationale.append("Basic cover → ×0.85")

    multiplier = _clamp(multiplier, rationale)
    if not rationale:
        rationale.append("No travel risk signals captured — baseline multiplier 1.0")
    return ScoreResponse(
        multiplier=round(multiplier, 4),
        adjusted_amount=round(req.base_amount * multiplier, 4),
        rationale=rationale,
    )


def _score_disability(req: ScoreRequest) -> ScoreResponse:
    """DISABILITY / income protection scorer.

    Long-tail line. Drivers: occupation hazard class (manual labour
    has much higher claim frequency than office work), age (claim
    severity rises sharply post-50), smoking (chronic disease driver
    of long-term disability), waiting period (longer = lower premium
    because short claims drop off the insurer), benefit period
    (longer = higher premium), and chronic conditions.
    """

    multiplier = 1.0
    rationale: list[str] = []

    occ = (_attr(req, "occupation.hazard_class") or "").upper() or None
    if occ:
        bumps = {"SEDENTARY": 1.0, "MANUAL": 1.40, "HAZARDOUS": 1.80, "VERY_HAZARDOUS": 2.40}
        if occ in bumps and bumps[occ] != 1.0:
            multiplier *= bumps[occ]
            rationale.append(f"Occupation {occ} → ×{bumps[occ]}")

    age = _as_int(_attr(req, "age", req.age))
    if age is not None:
        if age >= 55:
            multiplier *= 1.40
            rationale.append(f"Age {age} → ×1.40")
        elif age >= 45:
            multiplier *= 1.15
            rationale.append(f"Age {age} → ×1.15")

    smoking = _attr(req, "smoking_status", req.smoking_status)
    if smoking == "CURRENT":
        multiplier *= 1.30
        rationale.append("Current smoker → ×1.30")

    # Waiting period — longer wait shifts short-duration claims off
    # the insurer, lowering expected loss.
    wait_days = _as_int(_attr(req, "waiting_period_days"))
    if wait_days is not None:
        if wait_days >= 180:
            multiplier *= 0.70
            rationale.append(f"Waiting period {wait_days}d → ×0.70")
        elif wait_days >= 90:
            multiplier *= 0.85
            rationale.append(f"Waiting period {wait_days}d → ×0.85")
        elif wait_days <= 14:
            multiplier *= 1.30
            rationale.append(f"Waiting period {wait_days}d → ×1.30 (short — more claims qualify)")

    # Benefit period — longer expected payout window loads the price.
    benefit = (_attr(req, "benefit_period") or "").upper() or None
    if benefit == "TO_AGE_65":
        multiplier *= 1.40
        rationale.append("Benefit to age 65 → ×1.40")
    elif benefit == "5_YEAR":
        multiplier *= 1.10
        rationale.append("5-year benefit → ×1.10")

    chronic = _as_int(_attr(req, "chronic_condition_count", req.chronic_condition_count)) or 0
    if chronic > 0:
        bump = min(0.20 * chronic, 0.60)
        multiplier *= 1.0 + bump
        rationale.append(f"{chronic} chronic condition(s) → +{int(bump * 100)}%")

    multiplier = _clamp(multiplier, rationale)
    if not rationale:
        rationale.append("No disability risk signals captured — baseline multiplier 1.0")
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
