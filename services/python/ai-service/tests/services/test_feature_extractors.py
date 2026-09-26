"""Per-line extractor tests. One canonical happy-path per line + edge cases."""
from datetime import date
from uuid import uuid4

import pytest

from app.schemas.fraud import FraudCheckRequest
from app.schemas.insurance_line import InsuranceLine
from app.services.feature_extractors import ExtractorRegistry, FraudContext


@pytest.fixture
def registry():
    return ExtractorRegistry()


@pytest.fixture
def ctx():
    return FraudContext()


def _req(line: InsuranceLine, line_features: dict, amount: float = 1500.0):
    return FraudCheckRequest(
        claim_id=uuid4(),
        insurance_line=line,
        subject_id=uuid4(),
        provider_id=uuid4(),
        claimed_amount=amount,
        currency_code="USD",
        service_date=date(2026, 9, 15),
        line_features=line_features,
    )


@pytest.mark.parametrize("line,line_features,expected_line_keys", [
    (InsuranceLine.HEALTH,
     {"diagnosis_codes": ["K35"], "procedure_codes": ["23410"]},
     {"diagnosis_codes", "procedure_codes"}),
    (InsuranceLine.VEHICLE,
     {"damage_type": "collision", "part_codes": ["WSD-01"],
      "cause_of_loss": "accident", "odometer_km": 45000},
     {"damage_type", "part_codes", "cause_of_loss", "odometer_km"}),
    (InsuranceLine.PROPERTY,
     {"damage_type": "fire", "peril": "fire", "repair_category": "structural"},
     {"damage_type", "peril", "repair_category"}),
    (InsuranceLine.LIFE,
     {"benefit_type": "NATURAL", "cause": "myocardial_infarction"},
     {"benefit_type", "cause"}),
    (InsuranceLine.FUNERAL,
     {"benefit_tier": "STANDARD", "deceased_relationship": "parent"},
     {"benefit_tier", "deceased_relationship"}),
    (InsuranceLine.DISABILITY,
     {"benefit_type": "TEMPORARY", "cause": "back_injury",
      "waiting_period_days": 30},
     {"benefit_type", "cause", "waiting_period_days"}),
    (InsuranceLine.TRAVEL,
     {"coverage_category": "MEDICAL", "destination_country": "ZA",
      "trip_duration_days": 14},
     {"coverage_category", "destination_country", "trip_duration_days"}),
])
@pytest.mark.asyncio
async def test_extractor_returns_canonical_5_tuple_and_line_specific_echo(
    line, line_features, expected_line_keys, registry, ctx,
):
    req = _req(line, line_features)
    canonical, normalized, indicators = await registry.for_line(line).extract(
        req, "tenant-a", ctx,
    )
    assert len(canonical) == 5
    assert set(normalized.keys()) == expected_line_keys
    assert isinstance(indicators, list)


@pytest.mark.asyncio
async def test_group_dispatches_via_underlying_line(registry, ctx):
    req = _req(
        InsuranceLine.GROUP,
        {"underlying_line": "LIFE", "benefit_type": "NATURAL",
         "cause": "myocardial_infarction"},
    )
    canonical, normalized, indicators = await registry.for_line(
        InsuranceLine.GROUP,
    ).extract(req, "tenant-a", ctx)
    assert len(canonical) == 5
    # Life extractor echoes benefit_type + cause
    assert "benefit_type" in normalized
    assert "cause" in normalized


@pytest.mark.asyncio
async def test_group_without_underlying_line_returns_raw_bag(registry, ctx):
    req = _req(InsuranceLine.GROUP, {"foo": "bar"})
    canonical, normalized, indicators = await registry.for_line(
        InsuranceLine.GROUP,
    ).extract(req, "tenant-a", ctx)
    assert len(canonical) == 5
    assert normalized == {"foo": "bar"}
    assert indicators == []


@pytest.mark.asyncio
async def test_group_with_unknown_underlying_line_falls_through(registry, ctx):
    req = _req(InsuranceLine.GROUP,
               {"underlying_line": "SPACE_INSURANCE", "foo": "bar"})
    canonical, normalized, indicators = await registry.for_line(
        InsuranceLine.GROUP,
    ).extract(req, "tenant-a", ctx)
    assert len(canonical) == 5
    assert "foo" in normalized


@pytest.mark.asyncio
async def test_health_missing_diagnosis_indicator(registry, ctx):
    req = _req(InsuranceLine.HEALTH, {"procedure_codes": ["23410"]})
    _, _, indicators = await registry.for_line(InsuranceLine.HEALTH).extract(
        req, "tenant-a", ctx,
    )
    assert "missing_diagnosis" in indicators


@pytest.mark.asyncio
async def test_vehicle_high_value_theft_indicator(registry, ctx):
    req = _req(InsuranceLine.VEHICLE,
               {"cause_of_loss": "theft", "damage_type": "theft"},
               amount=20_000)
    _, _, indicators = await registry.for_line(InsuranceLine.VEHICLE).extract(
        req, "tenant-a", ctx,
    )
    assert "high_value_theft" in indicators


@pytest.mark.asyncio
async def test_life_early_policy_claim_indicator(registry):
    ctx_new_policy = FraudContext(days_since_start=30)
    req = _req(InsuranceLine.LIFE, {"benefit_type": "NATURAL"})
    _, _, indicators = await registry.for_line(InsuranceLine.LIFE).extract(
        req, "tenant-a", ctx_new_policy,
    )
    assert "early_policy_claim" in indicators


def test_fraud_module_does_not_import_random():
    """Guard against regression: no randomness in fraud path."""
    import app.api.fraud as fraud_module
    import app.services.feature_extractors as extractors_module
    import app.services.fraud_service as service_module

    for mod in (fraud_module, service_module, extractors_module):
        assert not hasattr(mod, "random") or getattr(mod, "random", None) is None, (
            f"{mod.__name__} must not import random"
        )
