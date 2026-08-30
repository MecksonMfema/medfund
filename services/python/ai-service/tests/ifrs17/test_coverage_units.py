"""Unit tests for Phase 15 §13 coverage-unit computation.

Every registered pattern gets a hand-verified value; the dispatcher gets
its unknown-pattern-raises assertion; and the registry gets a stability
check so a rogue decorator can't silently shadow an existing pattern
without a test failure.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.ifrs17 import coverage_units
from app.ifrs17.coverage_units import (
    CoverageUnitInput,
    PolicyInScope,
    compute,
    registered_patterns,
)


# ── registry stability ──────────────────────────────────────────────


def test_registered_patterns_are_stable():
    """Adding a fifth pattern should require a matching update to this
    assertion — the ``IFRS17_MODEL`` rule template needs the same set
    (see ``reference_new_rule_category_checklist``)."""
    assert registered_patterns() == [
        "CLAIM_FREQUENCY_TIME",
        "SUM_AT_RISK_TIME",
        "SUM_INSURED_TIME",
        "TIME",
    ]


def test_unknown_pattern_raises_with_helpful_message():
    payload = CoverageUnitInput(periods_in_scope=1)
    with pytest.raises(ValueError, match="Unknown coverage_unit_pattern"):
        compute("BOGUS_PATTERN", payload)


# ── TIME ────────────────────────────────────────────────────────────


def test_time_pattern_returns_period_count():
    payload = CoverageUnitInput(periods_in_scope=12)
    assert compute("TIME", payload) == Decimal(12)


def test_time_pattern_zero_periods_returns_zero():
    """A no-exposure period is legitimate — §13 releases zero CSM rather
    than divide-by-zero on the release fraction."""
    payload = CoverageUnitInput(periods_in_scope=0)
    assert compute("TIME", payload) == Decimal(0)


# ── SUM_INSURED_TIME ────────────────────────────────────────────────


def test_sum_insured_time_multiplies_si_by_months_across_policies():
    payload = CoverageUnitInput(
        policies_in_scope=[
            PolicyInScope(policy_id="p1", months_in_period=12, sum_insured=Decimal("10000")),
            PolicyInScope(policy_id="p2", months_in_period=6, sum_insured=Decimal("5000")),
        ]
    )
    # 10000 × 12 + 5000 × 6 = 150000
    assert compute("SUM_INSURED_TIME", payload) == Decimal("150000")


def test_sum_insured_time_empty_policy_list_returns_zero():
    payload = CoverageUnitInput()
    assert compute("SUM_INSURED_TIME", payload) == Decimal(0)


# ── SUM_AT_RISK_TIME ────────────────────────────────────────────────


def test_sum_at_risk_time_uses_si_minus_reserve():
    payload = CoverageUnitInput(
        policies_in_scope=[
            PolicyInScope(
                policy_id="p1",
                months_in_period=12,
                sum_insured=Decimal("10000"),
                reserve_balance=Decimal("3000"),
            ),
        ]
    )
    # sar = 10000 - 3000 = 7000; × 12 = 84000
    assert compute("SUM_AT_RISK_TIME", payload) == Decimal("84000")


def test_sum_at_risk_time_floors_at_zero_when_reserve_exceeds_si():
    """Fully-reserved policy contributes nothing (per IFRS 17.B119(a))."""
    payload = CoverageUnitInput(
        policies_in_scope=[
            PolicyInScope(
                policy_id="p1",
                months_in_period=12,
                sum_insured=Decimal("5000"),
                reserve_balance=Decimal("8000"),
            ),
            PolicyInScope(
                policy_id="p2",
                months_in_period=12,
                sum_insured=Decimal("10000"),
                reserve_balance=Decimal("2000"),
            ),
        ]
    )
    # p1 → 0 (floored); p2 → 8000 × 12 = 96000
    assert compute("SUM_AT_RISK_TIME", payload) == Decimal("96000")


# ── CLAIM_FREQUENCY_TIME ────────────────────────────────────────────


def test_claim_frequency_time_multiplies_frequency_by_months():
    payload = CoverageUnitInput(
        policies_in_scope=[
            PolicyInScope(
                policy_id="p1",
                months_in_period=12,
                expected_claim_frequency=Decimal("0.5"),
            ),
            PolicyInScope(
                policy_id="p2",
                months_in_period=6,
                expected_claim_frequency=Decimal("1.0"),
            ),
        ]
    )
    # 0.5 × 12 + 1.0 × 6 = 12
    assert compute("CLAIM_FREQUENCY_TIME", payload) == Decimal("12.0")


def test_claim_frequency_time_zero_frequency_returns_zero():
    payload = CoverageUnitInput(
        policies_in_scope=[
            PolicyInScope(policy_id="p1", months_in_period=12),  # frequency defaults 0
        ]
    )
    assert compute("CLAIM_FREQUENCY_TIME", payload) == Decimal(0)


# ── decorator behaviour ─────────────────────────────────────────────


def test_register_decorator_adds_new_pattern_without_touching_dispatch():
    """A tenant demanding a bespoke pattern lands via one @register call.
    The test dirties the registry so it restores state via try/finally."""

    @coverage_units.register("CUSTOM_TEST_PATTERN")
    def _custom(payload: CoverageUnitInput) -> Decimal:
        return Decimal("42")

    try:
        payload = CoverageUnitInput(periods_in_scope=1)
        assert compute("CUSTOM_TEST_PATTERN", payload) == Decimal("42")
        assert "CUSTOM_TEST_PATTERN" in registered_patterns()
    finally:
        # Unregister to leave the registry as we found it.
        coverage_units._PATTERN_HANDLERS.pop("CUSTOM_TEST_PATTERN", None)

    assert "CUSTOM_TEST_PATTERN" not in registered_patterns()
