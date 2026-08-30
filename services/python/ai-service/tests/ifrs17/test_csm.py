"""Unit tests for the Phase 15 §13 CSM roll-forward.

* IASB Example 9 golden pins straight-line release with zero accretion +
  the onerous-transition path where an adverse experience adjustment
  tips CSM below zero, triggering loss-component recognition.
* Hand-crafted cases exercise every branch: interest accretion at a
  non-zero locked-in rate, release cap at 1.0, zero-remaining short-
  circuit, invariant violations on negative inputs, empty-curve
  invariant violation.
"""

from __future__ import annotations

from decimal import Decimal
from pathlib import Path

import pytest
import yaml

from app.ifrs17.csm import CsmRollForwardResult, roll_forward

FIXTURES = Path(__file__).parent / "fixtures" / "iasb_examples"


def _load_fixture(name: str) -> dict:
    with (FIXTURES / name).open() as f:
        return yaml.safe_load(f)


def _walk(fixture_key: str) -> list[CsmRollForwardResult]:
    """Roll the CSM through every period in the fixture, chaining
    ``closing`` back into the next period's ``opening_csm``."""
    fixture = _load_fixture("example_9_csm_rollforward.yaml")[fixture_key]
    csm = Decimal(fixture["opening_csm"])
    curve = fixture["locked_in_curve"]
    results: list[CsmRollForwardResult] = []
    for period in fixture["periods"]:
        result = roll_forward(
            opening_csm=csm,
            locked_in_curve=curve,
            experience_adjustment=Decimal(period["experience_adjustment"]),
            coverage_units_delivered=Decimal(period["coverage_units_delivered"]),
            coverage_units_remaining_before=Decimal(
                period["coverage_units_remaining_before"]
            ),
        )
        results.append(result)
        csm = result.closing
    return results


# ── golden — steady release path ────────────────────────────────────


def test_csm_iasb_ex9_steady_release_matches_hand_computed():
    fixture = _load_fixture("example_9_csm_rollforward.yaml")["steady_release"]
    results = _walk("steady_release")
    for period, result in zip(fixture["periods"], results):
        assert result.released == Decimal(period["expected_released"])
        assert result.closing == Decimal(period["expected_closing"])
        assert result.went_negative is period["expected_went_negative"]
        assert result.loss_component_recognised == Decimal(
            period["expected_loss_component_recognised"]
        )


def test_csm_iasb_ex9_steady_release_total_equals_quarter_of_opening():
    """3 periods × 5 units / 60 remaining = 25% of the coverage delivered
    → 25% of the CSM (75 of 300) released, sum-invariant."""
    results = _walk("steady_release")
    total_released = sum((r.released for r in results), Decimal(0))
    assert total_released == Decimal("75")
    # Sanity: opening - closing == released (zero accretion, zero experience).
    assert Decimal("300") - results[-1].closing == total_released


# ── golden — onerous transition path ────────────────────────────────


def test_csm_iasb_ex9_goes_onerous_matches_hand_computed():
    fixture = _load_fixture("example_9_csm_rollforward.yaml")["goes_onerous"]
    results = _walk("goes_onerous")
    for period, result in zip(fixture["periods"], results):
        assert result.released == Decimal(period["expected_released"])
        assert result.closing == Decimal(period["expected_closing"])
        assert result.went_negative is period["expected_went_negative"]
        assert result.loss_component_recognised == Decimal(
            period["expected_loss_component_recognised"]
        )


def test_csm_iasb_ex9_goes_onerous_recognises_loss_component_once():
    """Only period 2 tips negative — periods 1 and 3 stay at zero
    loss component, and period 3 releases nothing off a zero opening."""
    results = _walk("goes_onerous")
    assert [r.went_negative for r in results] == [False, True, False]
    assert [r.loss_component_recognised for r in results] == [
        Decimal(0),
        Decimal("15"),
        Decimal(0),
    ]


# ── interest accretion ───────────────────────────────────────────────


def test_accretion_at_five_percent_annual_matches_monthly_step():
    """opening 1000, 5% flat, one monthly step, no delivery, no exp adj.
    Expected accreted = 1000 × (1.05)^(1/12) ≈ 1004.0741 …
    Closing equals accreted (nothing released, no experience).
    """
    curve = [
        {"tenor_months": 1, "spot_rate": Decimal("0.05")},
        {"tenor_months": 12, "spot_rate": Decimal("0.05")},
    ]
    result = roll_forward(
        opening_csm=Decimal("1000"),
        locked_in_curve=curve,
        experience_adjustment=Decimal(0),
        coverage_units_delivered=Decimal(0),
        coverage_units_remaining_before=Decimal(100),
    )
    # (1.05)^(1/12) = e^(ln(1.05)/12); ≈ 1.00407412378364835718...
    # 1000 × that ≈ 1004.07412378...
    assert Decimal("1004.07") < result.closing < Decimal("1004.08")
    assert result.released == Decimal(0)
    assert result.went_negative is False


def test_zero_rate_curve_produces_no_accretion():
    curve = [
        {"tenor_months": 1, "spot_rate": Decimal(0)},
        {"tenor_months": 12, "spot_rate": Decimal(0)},
    ]
    result = roll_forward(
        opening_csm=Decimal("500"),
        locked_in_curve=curve,
        experience_adjustment=Decimal(0),
        coverage_units_delivered=Decimal(0),
        coverage_units_remaining_before=Decimal(50),
    )
    assert result.closing == Decimal("500")
    assert result.released == Decimal(0)


# ── release edge cases ──────────────────────────────────────────────


def test_release_fraction_at_one_zeroes_closing():
    """Final period of coverage: delivered == remaining_before → full
    release, closing exactly zero."""
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    result = roll_forward(
        opening_csm=Decimal("100"),
        locked_in_curve=curve,
        experience_adjustment=Decimal(0),
        coverage_units_delivered=Decimal(10),
        coverage_units_remaining_before=Decimal(10),
    )
    assert result.closing == Decimal(0)
    assert result.released == Decimal("100")
    assert result.went_negative is False


def test_release_fraction_above_one_caps_at_full_release():
    """Delivered > remaining: cap the release at the after-experience
    CSM rather than over-releasing."""
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    result = roll_forward(
        opening_csm=Decimal("80"),
        locked_in_curve=curve,
        experience_adjustment=Decimal(0),
        coverage_units_delivered=Decimal(15),  # > 10 remaining
        coverage_units_remaining_before=Decimal(10),
    )
    assert result.closing == Decimal(0)
    assert result.released == Decimal("80")


def test_zero_remaining_short_circuits_release():
    """No expected coverage remaining → no release fires; closing
    preserves after-experience."""
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    result = roll_forward(
        opening_csm=Decimal("50"),
        locked_in_curve=curve,
        experience_adjustment=Decimal("10"),
        coverage_units_delivered=Decimal(5),
        coverage_units_remaining_before=Decimal(0),
    )
    assert result.released == Decimal(0)
    assert result.closing == Decimal("60")


def test_positive_experience_adjustment_lifts_after_experience():
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    result = roll_forward(
        opening_csm=Decimal("100"),
        locked_in_curve=curve,
        experience_adjustment=Decimal("50"),
        coverage_units_delivered=Decimal(5),
        coverage_units_remaining_before=Decimal(50),
    )
    # after_exp = 150; release_fraction = 0.10; released = 15; closing = 135
    assert result.released == Decimal("15.0")
    assert result.closing == Decimal("135.0")


# ── invariant violations ────────────────────────────────────────────


def test_negative_opening_csm_raises_invariant_violation():
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    with pytest.raises(ValueError, match="opening_csm .* must be non-negative"):
        roll_forward(
            opening_csm=Decimal("-1"),
            locked_in_curve=curve,
            experience_adjustment=Decimal(0),
            coverage_units_delivered=Decimal(0),
            coverage_units_remaining_before=Decimal(10),
        )


def test_negative_coverage_units_delivered_raises():
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    with pytest.raises(ValueError, match="coverage_units_delivered .* must be non-negative"):
        roll_forward(
            opening_csm=Decimal("10"),
            locked_in_curve=curve,
            experience_adjustment=Decimal(0),
            coverage_units_delivered=Decimal("-1"),
            coverage_units_remaining_before=Decimal(10),
        )


def test_negative_coverage_units_remaining_raises():
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    with pytest.raises(
        ValueError, match="coverage_units_remaining_before .* must be non-negative"
    ):
        roll_forward(
            opening_csm=Decimal("10"),
            locked_in_curve=curve,
            experience_adjustment=Decimal(0),
            coverage_units_delivered=Decimal(0),
            coverage_units_remaining_before=Decimal("-1"),
        )


def test_empty_curve_raises_invariant_violation():
    with pytest.raises(ValueError, match="non-empty locked-in curve snapshot"):
        roll_forward(
            opening_csm=Decimal("10"),
            locked_in_curve=[],
            experience_adjustment=Decimal(0),
            coverage_units_delivered=Decimal(0),
            coverage_units_remaining_before=Decimal(10),
        )


def test_result_tuple_destructures_cleanly():
    """The NamedTuple lets callers destructure without importing the type."""
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    closing, released, went_negative, loss_component = roll_forward(
        opening_csm=Decimal("100"),
        locked_in_curve=curve,
        experience_adjustment=Decimal(0),
        coverage_units_delivered=Decimal(5),
        coverage_units_remaining_before=Decimal(50),
    )
    assert closing == Decimal("90.0")
    assert released == Decimal("10.0")
    assert went_negative is False
    assert loss_component == Decimal(0)
