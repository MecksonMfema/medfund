"""Per-line seed-data tests for app.data.code_hints.

Every InsuranceLine value has a registered suggester (asserted below) and
returns non-empty suggestions for its known seed keys.
"""
from __future__ import annotations

import pytest

from app.data.code_hints import registered_lines, suggest_for_line
from app.schemas.insurance_line import InsuranceLine


def test_every_line_has_registered_suggester():
    """Reflection guard — every InsuranceLine value must have a suggester."""
    missing = set(InsuranceLine) - registered_lines()
    assert missing == set(), f"Missing suggesters for {missing}"


@pytest.mark.parametrize("line,context,expected_prefix", [
    (InsuranceLine.HEALTH,     {"diagnosis_codes": ["K35.0"]},         "23"),
    (InsuranceLine.HEALTH,     {"diagnosis_codes": ["K35"]},           "23"),
    (InsuranceLine.HEALTH,     {"diagnosis_codes": ["J18.9"]},         "0"),
    (InsuranceLine.VEHICLE,    {"damage_type": "collision"},           "PANEL"),
    (InsuranceLine.VEHICLE,    {"damage_type": "theft"},               "TOTAL-LOSS"),
    (InsuranceLine.VEHICLE,    {"damage_type": "glass"},               "WSD"),
    (InsuranceLine.PROPERTY,   {"peril": "fire"},                      "STRUCT-FIRE"),
    (InsuranceLine.PROPERTY,   {"peril": "flood"},                     "FLOOD"),
    (InsuranceLine.PROPERTY,   {"peril": "burglary"},                  "BURG"),
    (InsuranceLine.LIFE,       {"benefit_type": "NATURAL"},            "LIFE-NAT"),
    (InsuranceLine.LIFE,       {"benefit_type": "ACCIDENTAL"},         "LIFE-ACC"),
    (InsuranceLine.FUNERAL,    {"benefit_tier": "STANDARD"},           "FUN-STD"),
    (InsuranceLine.FUNERAL,    {"benefit_tier": "PREMIUM"},            "FUN-PRM"),
    (InsuranceLine.DISABILITY, {"benefit_type": "TEMPORARY"},          "DIS-TMP"),
    (InsuranceLine.DISABILITY, {"benefit_type": "PERMANENT"},          "DIS-PTD"),
    (InsuranceLine.TRAVEL,     {"coverage_category": "MEDICAL"},       "TRV-MED"),
    (InsuranceLine.TRAVEL,     {"coverage_category": "BAGGAGE"},       "TRV-BAG"),
])
def test_seed_returns_non_empty_for_known_key(line, context, expected_prefix):
    suggestions = suggest_for_line(line, context)
    assert suggestions, f"{line} returned empty for {context}"
    assert any(s.code.startswith(expected_prefix) for s in suggestions), (
        f"{line} suggestions for {context} do not include prefix {expected_prefix}: "
        f"{[s.code for s in suggestions]}"
    )


@pytest.mark.parametrize("line", list(InsuranceLine))
def test_suggest_for_line_empty_context_returns_empty(line):
    assert suggest_for_line(line, {}) == []


@pytest.mark.parametrize("line,context", [
    (InsuranceLine.HEALTH,     {"diagnosis_codes": ["UNKNOWN-99"]}),
    (InsuranceLine.VEHICLE,    {"damage_type": "not-a-real-damage"}),
    (InsuranceLine.PROPERTY,   {"peril": "not-a-peril"}),
    (InsuranceLine.LIFE,       {"benefit_type": "NOT_A_TYPE"}),
    (InsuranceLine.FUNERAL,    {"benefit_tier": "NOT_A_TIER"}),
    (InsuranceLine.DISABILITY, {"benefit_type": "NOT_A_TYPE"}),
    (InsuranceLine.TRAVEL,     {"coverage_category": "NOT_A_CATEGORY"}),
])
def test_suggest_for_line_unknown_key_returns_empty(line, context):
    assert suggest_for_line(line, context) == []


def test_group_dispatches_via_underlying_line():
    result = suggest_for_line(
        InsuranceLine.GROUP,
        {"underlying_line": "LIFE", "benefit_type": "NATURAL"},
    )
    assert result, "GROUP with LIFE underlying should return LIFE suggestions"
    assert any(s.code.startswith("LIFE-NAT") for s in result)


def test_group_without_underlying_returns_empty():
    assert suggest_for_line(InsuranceLine.GROUP, {}) == []
    assert suggest_for_line(InsuranceLine.GROUP, {"underlying_line": "BOGUS"}) == []
    assert suggest_for_line(InsuranceLine.GROUP, {"underlying_line": "GROUP"}) == []


def test_health_suggestions_sorted_by_confidence_desc():
    result = suggest_for_line(InsuranceLine.HEALTH, {"diagnosis_codes": ["K35.0"]})
    confidences = [s.confidence for s in result]
    assert confidences == sorted(confidences, reverse=True)


def test_health_deduplicates_across_exact_and_prefix():
    """A K35.0 diagnosis matches both the exact code and the K35 prefix — the
    same tariff code shouldn't be duplicated in the response."""
    result = suggest_for_line(InsuranceLine.HEALTH, {"diagnosis_codes": ["K35.0"]})
    codes = [s.code for s in result]
    assert len(codes) == len(set(codes))
