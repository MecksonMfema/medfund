"""Unit tests for the Phase 15 §15 onerous contract reassessment.

Four core scenarios per the plan:
* (a) non-onerous stays non-onerous → NO_CHANGE, no amount
* (b) auto-fail transitions to ONEROUS → FAILED, positive loss component
* (c) onerous recovers to NON_ONEROUS → RECOVERED, no amount
* (d) still-onerous holds → NO_CHANGE

Additional guards pin the loss-component arithmetic (gap = fcf - premium
- csm), the boundary condition (fcf exactly at threshold → not onerous),
and the UNCERTAIN cohort_type default (treated as non-onerous for
transition direction).
"""

from __future__ import annotations

from decimal import Decimal

from app.ifrs17.onerous_test import (
    COHORT_TYPE_ONEROUS,
    REASON_FAILED,
    REASON_NO_CHANGE,
    REASON_RECOVERED,
    OnerousTestResult,
    test_onerous,
)


# ── core scenarios ──────────────────────────────────────────────────


def test_non_onerous_stays_non_onerous():
    """(a) FCF within (premium + CSM) — no transition, no ledger movement."""
    result = test_onerous(
        projected_fcf=Decimal("80"),
        premium_received=Decimal("100"),
        remaining_csm=Decimal("30"),
        current_cohort_type="NON_ONEROUS",
    )
    assert result == OnerousTestResult(
        is_now_onerous=False,
        transition_reason=REASON_NO_CHANGE,
        loss_component_amount=None,
    )


def test_auto_fail_transitions_to_onerous():
    """(b) FCF exceeds (premium + CSM) on a non-onerous cohort — post
    INITIAL_RECOGNITION for the excess."""
    result = test_onerous(
        projected_fcf=Decimal("150"),
        premium_received=Decimal("100"),
        remaining_csm=Decimal("30"),
        current_cohort_type="NON_ONEROUS",
    )
    # 150 − (100 + 30) = 20
    assert result.is_now_onerous is True
    assert result.transition_reason == REASON_FAILED
    assert result.loss_component_amount == Decimal("20")


def test_onerous_recovers_to_non_onerous():
    """(c) FCF drops back within (premium + CSM) on an onerous cohort —
    reclassify to non-onerous; no amount required (matview handles it)."""
    result = test_onerous(
        projected_fcf=Decimal("80"),
        premium_received=Decimal("100"),
        remaining_csm=Decimal("30"),
        current_cohort_type=COHORT_TYPE_ONEROUS,
    )
    assert result == OnerousTestResult(
        is_now_onerous=False,
        transition_reason=REASON_RECOVERED,
        loss_component_amount=None,
    )


def test_still_onerous_no_change():
    """(d) FCF still exceeds (premium + CSM) on an already-onerous
    cohort — no fresh transition. Callers should skip the POST so we
    don't spam the audit log with steady-state rows."""
    result = test_onerous(
        projected_fcf=Decimal("200"),
        premium_received=Decimal("100"),
        remaining_csm=Decimal("30"),
        current_cohort_type=COHORT_TYPE_ONEROUS,
    )
    assert result == OnerousTestResult(
        is_now_onerous=True,
        transition_reason=REASON_NO_CHANGE,
        loss_component_amount=None,
    )


# ── boundary + guard cases ──────────────────────────────────────────


def test_boundary_fcf_equals_threshold_is_not_onerous():
    """17.19 says fulfilment cash flows must *exceed* the CSM carrying
    amount — equality does not trigger. Pins the strict-greater-than
    comparison so a float rounding tweak upstream cannot silently flip
    to onerous on the edge."""
    result = test_onerous(
        projected_fcf=Decimal("130"),
        premium_received=Decimal("100"),
        remaining_csm=Decimal("30"),
        current_cohort_type="NON_ONEROUS",
    )
    assert result.is_now_onerous is False
    assert result.transition_reason == REASON_NO_CHANGE


def test_uncertain_cohort_type_treated_as_non_onerous_for_direction():
    """The CHECK constraint on ifrs17_cohort.cohort_type allows
    UNCERTAIN as a default seed. For onerous-test direction purposes,
    UNCERTAIN behaves as non-onerous — a failing test transitions
    into ONEROUS, not out of it."""
    result = test_onerous(
        projected_fcf=Decimal("150"),
        premium_received=Decimal("100"),
        remaining_csm=Decimal("30"),
        current_cohort_type="UNCERTAIN",
    )
    assert result.transition_reason == REASON_FAILED
    assert result.loss_component_amount == Decimal("20")


def test_zero_csm_and_zero_premium_arithmetic():
    """Guard the arithmetic in the degenerate case where a group has no
    CSM (all released) and no premium (asset-line cohort with pre-paid
    premiums) — loss component should still equal fcf exactly."""
    result = test_onerous(
        projected_fcf=Decimal("42.50"),
        premium_received=Decimal("0"),
        remaining_csm=Decimal("0"),
        current_cohort_type="NON_ONEROUS",
    )
    assert result.transition_reason == REASON_FAILED
    assert result.loss_component_amount == Decimal("42.50")


def test_result_named_tuple_is_destructurable():
    """The runner destructures the three-tuple directly; pin the field
    order so a positional refactor cannot silently swap loss amount and
    transition reason."""
    is_now_onerous, reason, amount = test_onerous(
        projected_fcf=Decimal("150"),
        premium_received=Decimal("100"),
        remaining_csm=Decimal("30"),
        current_cohort_type="NON_ONEROUS",
    )
    assert is_now_onerous is True
    assert reason == REASON_FAILED
    assert amount == Decimal("20")
