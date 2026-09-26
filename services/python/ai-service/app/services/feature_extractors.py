"""Per-line feature extractors for the AI service.

Every extractor returns a 3-tuple:

    (canonical_features, normalized_line_features, rule_indicators)

- canonical_features: 5-tuple [amount, days_since_start,
  claim_frequency_30d, provider_flag_count, subject_flag_count]. This
  line-neutral vector is what the shared FraudMLModel consumes.
- normalized_line_features: dict of line-specific fields, cleaned and
  typed. Persisted on the prediction row for later per-line model
  training.
- rule_indicators: list of string flags like "high_value_claim",
  "many_procedures", "high_value_theft". Displayed to operators
  alongside the ML score; not additive to it.

Every extractor is deterministic. No randomness. All extractors share
the same interface; line-specific detail lives in the body.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.schemas.fraud import FraudCheckRequest
from app.schemas.insurance_line import InsuranceLine

# Per-line "typical claim amount" thresholds for the high_value_claim
# indicator. Rough starter values; tenants can tune later via a settings
# surface (Tranche 2 follow-up).
_HIGH_VALUE_THRESHOLDS: dict[InsuranceLine, float] = {
    InsuranceLine.HEALTH: 5_000.0,
    InsuranceLine.LIFE: 50_000.0,
    InsuranceLine.FUNERAL: 10_000.0,
    InsuranceLine.GROUP: 20_000.0,
    InsuranceLine.TRAVEL: 3_000.0,
    InsuranceLine.DISABILITY: 15_000.0,
    InsuranceLine.VEHICLE: 8_000.0,
    InsuranceLine.PROPERTY: 25_000.0,
}


@dataclass
class FraudContext:
    """Lookups an extractor needs but cannot do itself.

    For Tranche 0 these are stubbed to sensible defaults; Tranche 1
    wires the real historical queries.
    """

    days_since_start: int = 365
    claim_frequency_30d: int = 0
    provider_flag_count: int = 0
    subject_flag_count: int = 0


def _as_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


async def _canonical(
    req: FraudCheckRequest, tenant_id: str, ctx: FraudContext
) -> list[float]:
    """Line-neutral 5-tuple: [amount, days_since_start, claim_frequency_30d,
    provider_flag_count, subject_flag_count]."""
    return [
        float(req.claimed_amount),
        float(ctx.days_since_start),
        float(ctx.claim_frequency_30d),
        float(ctx.provider_flag_count),
        float(ctx.subject_flag_count),
    ]


class FeatureExtractor:
    async def extract(
        self, req: FraudCheckRequest, tenant_id: str, ctx: FraudContext
    ) -> tuple[list[float], dict[str, Any], list[str]]:
        raise NotImplementedError


class HealthExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        diagnosis = list(lf.get("diagnosis_codes") or [])
        procedures = list(lf.get("procedure_codes") or [])

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.HEALTH]:
            indicators.append("high_value_claim")
        if len(procedures) > 5:
            indicators.append("many_procedures")
        if not diagnosis:
            indicators.append("missing_diagnosis")

        return canonical, {
            "diagnosis_codes": diagnosis,
            "procedure_codes": procedures,
        }, indicators


class VehicleExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        damage_type = lf.get("damage_type")
        part_codes = list(lf.get("part_codes") or [])
        cause = lf.get("cause_of_loss")
        odometer = _as_int(lf.get("odometer_km"))

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.VEHICLE]:
            indicators.append("high_value_claim")
        if len(part_codes) > 8:
            indicators.append("many_parts")
        if cause == "theft" and req.claimed_amount > 15_000:
            indicators.append("high_value_theft")

        return canonical, {
            "damage_type": damage_type,
            "part_codes": part_codes,
            "cause_of_loss": cause,
            "odometer_km": odometer,
        }, indicators


class PropertyExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        damage_type = lf.get("damage_type")
        peril = lf.get("peril")
        repair_category = lf.get("repair_category")

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.PROPERTY]:
            indicators.append("high_value_claim")
        if peril in ("fire", "flood") and req.claimed_amount > 50_000:
            indicators.append("catastrophic_peril_high_value")

        return canonical, {
            "damage_type": damage_type,
            "peril": peril,
            "repair_category": repair_category,
        }, indicators


class LifeExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        benefit_type = lf.get("benefit_type")
        cause = lf.get("cause")

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.LIFE]:
            indicators.append("high_value_claim")
        if canonical[1] < 60:
            indicators.append("early_policy_claim")

        return canonical, {"benefit_type": benefit_type, "cause": cause}, indicators


class FuneralExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        benefit_tier = lf.get("benefit_tier")
        deceased_relationship = lf.get("deceased_relationship")

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.FUNERAL]:
            indicators.append("high_value_claim")
        if canonical[1] < 30:
            indicators.append("early_policy_claim")

        return canonical, {
            "benefit_tier": benefit_tier,
            "deceased_relationship": deceased_relationship,
        }, indicators


class DisabilityExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        benefit_type = lf.get("benefit_type")
        cause = lf.get("cause")
        waiting_period_days = _as_int(lf.get("waiting_period_days"))

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.DISABILITY]:
            indicators.append("high_value_claim")

        return canonical, {
            "benefit_type": benefit_type,
            "cause": cause,
            "waiting_period_days": waiting_period_days,
        }, indicators


class TravelExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        coverage_category = lf.get("coverage_category")
        destination_country = lf.get("destination_country")
        trip_duration_days = _as_int(lf.get("trip_duration_days"))

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.TRAVEL]:
            indicators.append("high_value_claim")

        return canonical, {
            "coverage_category": coverage_category,
            "destination_country": destination_country,
            "trip_duration_days": trip_duration_days,
        }, indicators


class GroupExtractor(FeatureExtractor):
    """Group is a wrapper — dispatches to the underlying line's extractor.

    `line_features.underlying_line` names one of HEALTH / LIFE / FUNERAL /
    TRAVEL / DISABILITY. If missing or unknown, falls back to canonical
    features with the raw line_features bag echoed.
    """

    def __init__(self, registry: ExtractorRegistry) -> None:
        self._registry = registry

    async def extract(self, req, tenant_id, ctx):
        underlying = req.line_features.get("underlying_line")
        if not underlying:
            canonical = await _canonical(req, tenant_id, ctx)
            return canonical, dict(req.line_features), []
        try:
            underlying_line = InsuranceLine(underlying)
        except ValueError:
            canonical = await _canonical(req, tenant_id, ctx)
            return canonical, dict(req.line_features), []
        if underlying_line == InsuranceLine.GROUP:
            canonical = await _canonical(req, tenant_id, ctx)
            return canonical, dict(req.line_features), []
        return await self._registry.for_line(underlying_line).extract(req, tenant_id, ctx)


class ExtractorRegistry:
    def __init__(self) -> None:
        self._per_line: dict[InsuranceLine, FeatureExtractor] = {
            InsuranceLine.HEALTH: HealthExtractor(),
            InsuranceLine.VEHICLE: VehicleExtractor(),
            InsuranceLine.PROPERTY: PropertyExtractor(),
            InsuranceLine.LIFE: LifeExtractor(),
            InsuranceLine.FUNERAL: FuneralExtractor(),
            InsuranceLine.DISABILITY: DisabilityExtractor(),
            InsuranceLine.TRAVEL: TravelExtractor(),
        }
        self._per_line[InsuranceLine.GROUP] = GroupExtractor(self)

    def for_line(self, line: InsuranceLine) -> FeatureExtractor:
        return self._per_line[line]
