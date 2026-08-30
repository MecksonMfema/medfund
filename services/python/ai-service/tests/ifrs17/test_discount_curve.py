"""Unit tests for the Phase 15 §12 yield-curve helper.

Covers:

* Linear interpolation between two consecutive tenors.
* Flat extrapolation past the shortest / longest tenor (per IFRS 17 IE).
* Boundary cases — exact-tenor match, single-point curve, unsorted input.
* Decimal-vs-float coercion — no float round-trip inside the helper.
* ``discount_factor`` edge cases (zero/negative months, integer vs
  fractional exponents, negative-base guard).
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.ifrs17.discount_curve import discount_factor, interpolate_spot_rate


# ── interpolate_spot_rate ────────────────────────────────────────────


def test_interpolates_linearly_between_two_tenors():
    curve = [
        {"tenor_months": 12, "spot_rate": "0.04"},
        {"tenor_months": 24, "spot_rate": "0.06"},
    ]
    # Midpoint (18mo) → 5% flat.
    assert interpolate_spot_rate(curve, 18) == Decimal("0.05")


def test_flat_extrapolation_below_shortest_tenor():
    curve = [
        {"tenor_months": 12, "spot_rate": "0.04"},
        {"tenor_months": 60, "spot_rate": "0.06"},
    ]
    assert interpolate_spot_rate(curve, 1) == Decimal("0.04")
    assert interpolate_spot_rate(curve, 12) == Decimal("0.04")


def test_flat_extrapolation_above_longest_tenor():
    curve = [
        {"tenor_months": 12, "spot_rate": "0.04"},
        {"tenor_months": 60, "spot_rate": "0.06"},
    ]
    assert interpolate_spot_rate(curve, 60) == Decimal("0.06")
    assert interpolate_spot_rate(curve, 240) == Decimal("0.06")


def test_single_point_curve_returns_that_rate_for_every_tenor():
    curve = [{"tenor_months": 12, "spot_rate": "0.05"}]
    for tenor in (1, 12, 60, 480):
        assert interpolate_spot_rate(curve, tenor) == Decimal("0.05")


def test_unsorted_curve_input_still_interpolates_correctly():
    curve = [
        {"tenor_months": 60, "spot_rate": "0.07"},
        {"tenor_months": 12, "spot_rate": "0.03"},
        {"tenor_months": 36, "spot_rate": "0.05"},
    ]
    # 24mo sits halfway between 12mo (3%) and 36mo (5%) → 4%.
    assert interpolate_spot_rate(curve, 24) == Decimal("0.04")


def test_empty_curve_raises():
    with pytest.raises(ValueError, match="Empty curve snapshot"):
        interpolate_spot_rate([], 12)


def test_float_and_int_inputs_coerce_via_str_for_precision():
    """Passing a float would otherwise pull in binary-float noise —
    the helper coerces via ``str`` to keep 0.05 as ``Decimal('0.05')``."""
    curve = [
        {"tenor_months": 12, "spot_rate": 0.05},
        {"tenor_months": 24, "spot_rate": 0.05},
    ]
    assert interpolate_spot_rate(curve, 18) == Decimal("0.05")


def test_curve_with_decimal_rates_preserved():
    curve = [
        {"tenor_months": 12, "spot_rate": Decimal("0.045")},
        {"tenor_months": 24, "spot_rate": Decimal("0.055")},
    ]
    assert interpolate_spot_rate(curve, 18) == Decimal("0.050")


# ── discount_factor ──────────────────────────────────────────────────


def test_discount_factor_returns_one_at_zero_months():
    assert discount_factor(Decimal("0.05"), 0) == Decimal(1)


def test_discount_factor_treats_negative_months_as_zero():
    assert discount_factor(Decimal("0.05"), -5) == Decimal(1)


def test_discount_factor_at_12mo_matches_annual_discount():
    """1 / (1 + 0.05) = 0.952380952… — full-year exponent path."""
    got = discount_factor(Decimal("0.05"), 12)
    expected = Decimal(1) / (Decimal(1) + Decimal("0.05"))
    assert got == expected


def test_discount_factor_at_24mo_matches_two_year_annual():
    got = discount_factor(Decimal("0.05"), 24)
    expected = Decimal(1) / ((Decimal(1) + Decimal("0.05")) ** 2)
    assert got == expected


def test_discount_factor_fractional_exponent_matches_ln_exp_identity():
    """1mo at 5% ≈ (1.05)^(1/12) ≈ 1.004074… so v ≈ 0.99594…"""
    got = discount_factor(Decimal("0.05"), 1)
    # Cross-check against the ln/exp identity computed independently.
    expected = (
        -Decimal(1) / Decimal(12) * (Decimal(1) + Decimal("0.05")).ln()
    ).exp()
    assert abs(got - expected) < Decimal("1e-20")


def test_discount_factor_rejects_non_positive_base():
    with pytest.raises(ValueError, match="Non-positive discount base"):
        discount_factor(Decimal("-1"), 12)


def test_discount_factor_decreases_monotonically_with_horizon():
    prev = Decimal(1)
    for months in (1, 12, 24, 60, 120, 480):
        current = discount_factor(Decimal("0.05"), months)
        assert current < prev
        prev = current


def test_zero_spot_rate_gives_no_discount():
    assert discount_factor(Decimal("0"), 60) == Decimal(1)
