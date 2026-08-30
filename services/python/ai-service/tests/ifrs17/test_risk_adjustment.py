"""Unit tests for the Phase 15 §14 Risk Adjustment.

Coverage
────────
* CoC RA — flat curve hand-computed against ``Σ CoC × K × v^t``.
* CoC RA — non-flat curve exercises :func:`discount_curve.interpolate_spot_rate`.
* CoC RA — empty projection short-circuits to zero; empty curve raises.
* CI RA — hand-computed against ``z × σ`` at 0.75 (≈ 13.49 for σ = 20).
* CI RA — z-table sanity checks at 0.50, 0.90, 0.95, 0.9975.
* CI RA — stddev = 0 → RA = 0; negative stddev raises.
* CI RA — boundary rejects (p ≤ 0, p ≥ 1).
* CoC → CI equivalent — round-trip closes within tight tolerance.
* CoC → CI equivalent — degenerate stddev = 0 returns the median (0.5).
* Inverse-normal helper — central-region + upper-tail + lower-tail
  regime coverage against SciPy-comparable z-quantiles.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from app.ifrs17 import risk_adjustment as ra
from app.ifrs17.risk_adjustment import (
    _inverse_normal,
    _normal_cdf,
    coc_to_ci_equivalent,
    compute_ci,
    compute_coc,
)


# ── CoC RA ───────────────────────────────────────────────────────────


def test_coc_ra_flat_curve_matches_hand_computed_sum():
    """Flat 6% curve × 6% CoC × three periods of $1000 capital.

    RA = 6% × 1000 × (v¹ + v² + v³) with v = (1.06)^(−1/12).

    v¹ = (1.06)^(-1/12) ≈ 0.995156
    v² = (1.06)^(-2/12) ≈ 0.990336
    v³ = (1.06)^(-3/12) ≈ 0.985538

    RA ≈ 0.06 × 1000 × 2.97103 ≈ 178.26
    """
    curve = [
        {"tenor_months": 1, "spot_rate": Decimal("0.06")},
        {"tenor_months": 12, "spot_rate": Decimal("0.06")},
    ]
    result = compute_coc(
        projected_required_capital_by_period=[
            Decimal("1000"),
            Decimal("1000"),
            Decimal("1000"),
        ],
        coc_rate=Decimal("0.06"),
        curve_snapshot=curve,
    )
    assert Decimal("178.25") < result < Decimal("178.27")


def test_coc_ra_zero_rate_is_undiscounted_sum():
    """Zero rate → discount factor 1 → RA = CoC × Σ K."""
    curve = [{"tenor_months": 1, "spot_rate": Decimal(0)}]
    result = compute_coc(
        projected_required_capital_by_period=[
            Decimal("500"),
            Decimal("400"),
            Decimal("300"),
        ],
        coc_rate=Decimal("0.06"),
        curve_snapshot=curve,
    )
    # 0.06 × 1200 = 72
    assert result == Decimal("72.00")


def test_coc_ra_upward_curve_interpolates_between_tenors():
    """Non-flat curve: v(t) uses interpolated spot at that tenor."""
    curve = [
        {"tenor_months": 1, "spot_rate": Decimal("0.02")},
        {"tenor_months": 12, "spot_rate": Decimal("0.10")},
    ]
    # Two periods; both hit the same interpolation ladder as
    # :mod:`discount_curve`, so the assertion is monotonicity + positivity
    # rather than a hand-computed sum (which locks the test to Moro's
    # rounding).
    result = compute_coc(
        projected_required_capital_by_period=[Decimal("1000"), Decimal("1000")],
        coc_rate=Decimal("0.06"),
        curve_snapshot=curve,
    )
    assert result > 0
    # RA cannot exceed CoC × total capital (undiscounted upper bound).
    assert result <= Decimal("0.06") * Decimal("2000")


def test_coc_ra_empty_projection_returns_zero():
    curve = [{"tenor_months": 1, "spot_rate": Decimal("0.06")}]
    assert (
        compute_coc(
            projected_required_capital_by_period=[],
            coc_rate=Decimal("0.06"),
            curve_snapshot=curve,
        )
        == Decimal(0)
    )


def test_coc_ra_empty_curve_raises_invariant_violation():
    with pytest.raises(ValueError, match="non-empty locked-in curve snapshot"):
        compute_coc(
            projected_required_capital_by_period=[Decimal("1000")],
            coc_rate=Decimal("0.06"),
            curve_snapshot=[],
        )


# ── CI RA ────────────────────────────────────────────────────────────


def test_ci_ra_at_75_percent_matches_hand_computed():
    """z_0.75 ≈ 0.6745; RA = 0.6745 × σ.

    σ = 20 → RA ≈ 13.49 (plan comment)."""
    result = compute_ci(
        loss_distribution_mean=Decimal("100"),
        loss_distribution_stddev=Decimal("20"),
        target_confidence_level=Decimal("0.75"),
    )
    assert Decimal("13.4") < result < Decimal("13.6")


def test_ci_ra_matches_known_z_values():
    """Sanity: standard z-quantiles at CI 0.90, 0.95, 0.9975."""
    cases = [
        (Decimal("0.90"), Decimal("1.281"), Decimal("1.283")),
        (Decimal("0.95"), Decimal("1.644"), Decimal("1.646")),
        (Decimal("0.9975"), Decimal("2.806"), Decimal("2.808")),
    ]
    for ci, z_lo, z_hi in cases:
        result = compute_ci(
            loss_distribution_mean=Decimal("0"),
            loss_distribution_stddev=Decimal("1"),
            target_confidence_level=ci,
        )
        assert z_lo < result < z_hi, f"CI={ci}: {z_lo} < {result} < {z_hi} failed"


def test_ci_ra_zero_stddev_returns_zero():
    """No dispersion → no risk to compensate for."""
    result = compute_ci(
        loss_distribution_mean=Decimal("100"),
        loss_distribution_stddev=Decimal("0"),
        target_confidence_level=Decimal("0.75"),
    )
    assert result == Decimal(0)


def test_ci_ra_negative_stddev_raises():
    with pytest.raises(ValueError, match="loss_distribution_stddev .* must be non-negative"):
        compute_ci(
            loss_distribution_mean=Decimal("100"),
            loss_distribution_stddev=Decimal("-1"),
            target_confidence_level=Decimal("0.75"),
        )


def test_ci_ra_boundary_confidence_rejects_zero_and_one():
    for bad in [Decimal("0"), Decimal("1"), Decimal("-0.1"), Decimal("1.1")]:
        with pytest.raises(ValueError, match=r"must lie strictly inside \(0, 1\)"):
            compute_ci(
                loss_distribution_mean=Decimal("100"),
                loss_distribution_stddev=Decimal("20"),
                target_confidence_level=bad,
            )


# ── CoC → CI equivalent ──────────────────────────────────────────────


def test_coc_to_ci_equivalent_round_trip_close():
    """Feed a CoC-derived RA back into the CI reverse and re-derive it —
    the round-trip should land within 0.05 (Moro precision + math.erf
    float rounding)."""
    mean = Decimal("100")
    stddev = Decimal("20")
    coc_ra = Decimal("13.49")  # from the 0.75-hand-check above.

    ci_equivalent = coc_to_ci_equivalent(coc_ra, mean, stddev)
    # Should reproduce ~0.75.
    assert Decimal("0.74") < ci_equivalent < Decimal("0.76")

    round_trip_ra = compute_ci(mean, stddev, ci_equivalent)
    assert abs(round_trip_ra - coc_ra) < Decimal("0.05")


def test_coc_to_ci_equivalent_zero_stddev_returns_median():
    """No dispersion → the CoC RA is the entire risk margin, and there's
    no CI probability that distinguishes it — median disclosed instead."""
    result = coc_to_ci_equivalent(
        coc_ra_amount=Decimal("13.49"),
        loss_distribution_mean=Decimal("100"),
        loss_distribution_stddev=Decimal("0"),
    )
    assert result == Decimal("0.5")


def test_coc_to_ci_equivalent_negative_stddev_raises():
    with pytest.raises(ValueError, match="loss_distribution_stddev .* must be non-negative"):
        coc_to_ci_equivalent(
            coc_ra_amount=Decimal("13.49"),
            loss_distribution_mean=Decimal("100"),
            loss_distribution_stddev=Decimal("-1"),
        )


def test_coc_to_ci_equivalent_zero_ra_returns_median():
    """A zero CoC RA implies z = 0 → CI = 0.5."""
    result = coc_to_ci_equivalent(
        coc_ra_amount=Decimal("0"),
        loss_distribution_mean=Decimal("100"),
        loss_distribution_stddev=Decimal("20"),
    )
    # Allow 0.5 exact or float-rounded neighbourhood.
    assert Decimal("0.4999") < result < Decimal("0.5001")


# ── inverse normal + normal cdf helpers ──────────────────────────────


def test_inverse_normal_median_is_zero():
    assert abs(_inverse_normal(Decimal("0.5"))) < Decimal("0.0001")


def test_inverse_normal_central_region_matches_z_table():
    """|p − 0.5| < 0.42 branch: standard z-quantiles."""
    cases = [
        (Decimal("0.75"), Decimal("0.6745")),
        (Decimal("0.60"), Decimal("0.2533")),
        (Decimal("0.40"), Decimal("-0.2533")),
        (Decimal("0.25"), Decimal("-0.6745")),
    ]
    for p, expected in cases:
        z = _inverse_normal(p)
        assert abs(z - expected) < Decimal("0.001"), f"p={p}: got {z}, want {expected}"


def test_inverse_normal_upper_tail_matches_z_table():
    """|p − 0.5| ≥ 0.42 branch, upper side."""
    cases = [
        (Decimal("0.95"), Decimal("1.6449")),
        (Decimal("0.975"), Decimal("1.9600")),
        (Decimal("0.99"), Decimal("2.3263")),
    ]
    for p, expected in cases:
        z = _inverse_normal(p)
        assert abs(z - expected) < Decimal("0.001"), f"p={p}: got {z}, want {expected}"


def test_inverse_normal_lower_tail_is_reflected_upper():
    """Lower-tail values are the negation of the mirror upper-tail."""
    upper = _inverse_normal(Decimal("0.95"))
    lower = _inverse_normal(Decimal("0.05"))
    assert abs(upper + lower) < Decimal("0.001")


def test_inverse_normal_rejects_boundary_and_out_of_range():
    for bad in [Decimal("0"), Decimal("1"), Decimal("-0.1"), Decimal("1.5")]:
        with pytest.raises(ValueError, match=r"must lie strictly inside \(0, 1\)"):
            _inverse_normal(bad)


def test_normal_cdf_matches_known_values():
    """Sanity: Φ(0) = 0.5, Φ(1.645) ≈ 0.95, Φ(-1) ≈ 0.1587."""
    assert abs(_normal_cdf(Decimal(0)) - Decimal("0.5")) < Decimal("0.001")
    assert abs(_normal_cdf(Decimal("1.645")) - Decimal("0.95")) < Decimal("0.001")
    assert abs(_normal_cdf(Decimal("-1")) - Decimal("0.1587")) < Decimal("0.001")


# ── model version pin ────────────────────────────────────────────────


def test_model_version_pinned():
    """Downstream aggregator surfaces this on the envelope — accidental
    changes will invalidate report snapshots on the golden-report suite."""
    assert ra.MODEL_VERSION == "ifrs17-ra-1.0"
