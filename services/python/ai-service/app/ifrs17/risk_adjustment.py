"""Risk Adjustment for non-financial risk — Phase 15 §14.

Two IFRS 17.37 methodologies are supported:

* **CoC** (Solvency II cost-of-capital style): RA is the PV of the
  cost-of-capital charge on the projected required capital, period by
  period, discounted at the cohort's locked-in yield curve.

      RA = Σ CoC × projected_capital(t) × v(t)

* **CI** (confidence-level): given a Phase-14 chain-ladder loss
  distribution (Mack SE), RA is the value-at-risk at the tenant's target
  CI minus the mean — normal approximation, so RA reduces to ``z × σ``
  where ``z`` is the inverse-normal quantile.

Both methodologies are configurable per portfolio in ``tenant_ra_config``
(Phase 15 §2). §17 shaping resolves the row + wires the compute; §18
aggregator surfaces the result on the report envelope alongside the
IFRS 17.119 CI-equivalent disclosure that :func:`coc_to_ci_equivalent`
computes.

Precision
─────────
Decimal-native per Rule 1. The inverse-normal constants below are
Moro (1995) — accurate to ~1e-9, well within materiality for RA. Constants
are ``Decimal(str(const))`` so a float literal like ``0.5`` becomes
``Decimal('0.5')`` and not the binary-lossy expansion.

Symmetry with :mod:`app.ifrs17.csm`
────────────────────────────────────
CoC RA discounting uses the same locked-in curve snapshot as CSM
accretion — the fields align 1:1, so shaping can pass the same
``locked_in_curve`` list to both without conversion.
"""

from __future__ import annotations

from decimal import Decimal, getcontext
from math import erf, sqrt
from typing import Iterable, Mapping, Sequence

from app.ifrs17.discount_curve import discount_factor, interpolate_spot_rate


MODEL_VERSION = "ifrs17-ra-1.0"


# Moro (1995) rational-approximation coefficients for the central 84% of
# the distribution (|p − 0.5| < 0.42).
_A = [
    Decimal("2.50662823884"),
    Decimal("-18.61500062529"),
    Decimal("41.39119773534"),
    Decimal("-25.44106049637"),
]
_B = [
    Decimal("-8.47351093090"),
    Decimal("23.08336743743"),
    Decimal("-21.06224101826"),
    Decimal("3.13082909833"),
]
# Chebyshev-style tail coefficients for the |p − 0.5| ≥ 0.42 range.
_C = [
    Decimal("0.3374754822726147"),
    Decimal("0.9761690190917186"),
    Decimal("0.1607979714918209"),
    Decimal("0.0276438810333863"),
    Decimal("0.0038405729373609"),
    Decimal("0.0003951896511919"),
    Decimal("0.0000321767881768"),
    Decimal("0.0000002888167364"),
    Decimal("0.0000003960315187"),
]


def compute_coc(
    projected_required_capital_by_period: Sequence[Decimal],
    coc_rate: Decimal,
    curve_snapshot: Iterable[Mapping[str, object]],
) -> Decimal:
    """Cost-of-capital RA per Solvency II style.

    Each entry of ``projected_required_capital_by_period`` is the capital
    the insurer expects to hold to cover non-financial risk in month
    ``t+1`` (0-indexed). ``coc_rate`` is the annual cost-of-capital rate
    (e.g. ``Decimal('0.06')`` for 6%). ``curve_snapshot`` is the cohort's
    locked-in yield curve per I18 — same shape :mod:`app.ifrs17.csm`
    and :mod:`app.ifrs17.gmm` consume.

    An empty projection returns ``Decimal(0)`` — a legitimate no-capital
    period (e.g. a terminal-tail cohort with fully-run-off exposure).
    An empty curve raises ``ValueError``: the shaping service must
    populate the snapshot before invoking the compute (per :mod:`csm`).
    """
    capital_seq = list(projected_required_capital_by_period)
    if not capital_seq:
        return Decimal(0)

    curve = list(curve_snapshot)
    if not curve:
        raise ValueError(
            "CoC RA requires a non-empty locked-in curve snapshot; "
            "shaping service (§17) must populate from "
            "ifrs17_cohort.locked_in_yield_curve_snapshot per I18"
        )

    total = Decimal(0)
    for t, capital in enumerate(capital_seq):
        months_ahead = t + 1
        rate = interpolate_spot_rate(curve, tenor_months=months_ahead)
        factor = discount_factor(rate, months_ahead)
        total += coc_rate * Decimal(capital) * factor
    return total


def compute_ci(
    loss_distribution_mean: Decimal,
    loss_distribution_stddev: Decimal,
    target_confidence_level: Decimal,
) -> Decimal:
    """Confidence-level RA under a normal loss distribution.

    ``RA = VaR(CI) − mean = z × σ`` where ``z = Φ⁻¹(CI)``. The mean
    parameter is kept in the signature (rather than dropped as an
    algebraic simplification) so the caller's intent is legible at the
    call site and a future non-normal upgrade doesn't need to change
    every shaping call site.

    A zero standard deviation returns zero — a fully-known outcome has
    no non-financial risk to compensate for. Target CI must lie strictly
    inside (0, 1); shaping enforces the range on
    ``tenant_ra_config.target_confidence_level`` at admin-time (Phase 15
    §2 NUMERIC(5,4) check), but we defend the boundary here too.
    """
    _ = loss_distribution_mean  # See docstring — kept for interface parity.
    if loss_distribution_stddev == 0:
        return Decimal(0)
    if loss_distribution_stddev < 0:
        raise ValueError(
            f"loss_distribution_stddev ({loss_distribution_stddev}) must be non-negative"
        )
    z = _inverse_normal(target_confidence_level)
    return z * loss_distribution_stddev


def coc_to_ci_equivalent(
    coc_ra_amount: Decimal,
    loss_distribution_mean: Decimal,
    loss_distribution_stddev: Decimal,
) -> Decimal:
    """IFRS 17.119 CI-equivalent disclosure for CoC-configured cohorts.

    Reverse of :func:`compute_ci`: given a CoC-derived RA, back out the
    confidence-level probability that would produce the same RA under the
    provided loss distribution. Returns a value in ``(0, 1)`` — Φ(z).

    Degenerate cases
    ────────────────
    * ``stddev == 0`` → return ``Decimal('0.5')`` (the median — no risk to
      distinguish CI from mean).
    * Negative CoC RA (shouldn't happen — CoC × capital × discount ≥ 0)
      still resolves to a below-median CI via the identity ``z = ra/σ``.
    """
    _ = loss_distribution_mean  # See :func:`compute_ci` docstring.
    if loss_distribution_stddev == 0:
        return Decimal("0.5")
    if loss_distribution_stddev < 0:
        raise ValueError(
            f"loss_distribution_stddev ({loss_distribution_stddev}) must be non-negative"
        )
    z = coc_ra_amount / loss_distribution_stddev
    return _normal_cdf(z)


def _inverse_normal(p: Decimal) -> Decimal:
    """Moro (1995) inverse normal CDF — ~1e-9 accuracy on (0, 1).

    Two regimes:
    * Central: |p − 0.5| < 0.42 → rational approximation on ``y²``.
    * Tail: |p − 0.5| ≥ 0.42 → Chebyshev on ``ln(−ln(r))`` where
      ``r = min(p, 1−p)``.
    """
    if p <= 0 or p >= 1:
        raise ValueError(
            f"target_confidence_level ({p}) must lie strictly inside (0, 1)"
        )

    getcontext().prec = 28
    half = Decimal("0.5")
    y = p - half

    if abs(y) < Decimal("0.42"):
        r = y * y
        num = ((_A[3] * r + _A[2]) * r + _A[1]) * r + _A[0]
        den = (((_B[3] * r + _B[2]) * r + _B[1]) * r + _B[0]) * r + Decimal(1)
        return y * num / den

    # Tail — mirror around the median so the algorithm only sees the
    # lower half, then flip the sign back at the end.
    if p < half:
        r = p
        sign = Decimal(-1)
    else:
        r = Decimal(1) - p
        sign = Decimal(1)

    # Moro tail variable: t = ln(-ln(r)) — r ∈ (0, 0.08] so -ln(r) > 0.
    t = (-r.ln()).ln()

    # Horner's form: x = ((((c8 t + c7) t + c6) t + …) t + c0).
    x = _C[8]
    for coef in reversed(_C[:8]):
        x = x * t + coef
    return sign * x


def _normal_cdf(x: Decimal) -> Decimal:
    """Standard normal CDF via ``0.5 · (1 + erf(x/√2))``.

    stdlib ``math.erf`` operates on floats; we accept the float precision
    loss here — the caller uses this only for the CI-equivalent
    disclosure, which reports a probability to 4 decimal places on the
    admin surface. If a future disclosure surface tightens that, swap
    for ``mpmath.erf`` and stay Decimal-native.
    """
    as_float = float(x)
    cdf = 0.5 * (1.0 + erf(as_float / sqrt(2.0)))
    return Decimal(str(cdf))


__all__ = [
    "MODEL_VERSION",
    "compute_coc",
    "compute_ci",
    "coc_to_ci_equivalent",
]
