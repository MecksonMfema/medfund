"""Yield-curve interpolation + discount factors for GMM/VFA (Phase 15 §12).

Called from :mod:`app.ifrs17.gmm` (§12) and :mod:`app.ifrs17.vfa` (§16).

Curve snapshot shape
────────────────────
Every helper takes ``curve_snapshot`` as a ``list[dict]`` of
``{"tenor_months": int, "spot_rate": Decimal|str|float}``. This matches the
JSONB shape written to ``ifrs17_cohort.locked_in_yield_curve_snapshot`` in
Phase 6 (V145) and threaded through the request event's ``ifrs17_json``
slot per I18.

Interpolation policy per IFRS 17 IE
────────────────────────────────────
* **Linear** between two consecutive tenors.
* **Flat extrapolation** beyond the shortest / longest observed tenor —
  reserves are locked-in at the boundary rate, not extrapolated by slope.
* An empty snapshot raises ``ValueError`` — the shaping service must
  either populate the snapshot or fall back to the current tenant curve
  before invoking the compute.

Precision
─────────
Everything runs on :class:`decimal.Decimal` per Rule 1. Callers passing
``float`` / ``str`` get coerced through ``Decimal(str(x))`` to preserve
exactness — the Python ``float`` literal ``0.05`` becomes ``Decimal('0.05')``
not the binary-lossy ``Decimal('0.05000000000000000277…')``.
"""

from __future__ import annotations

from decimal import Decimal, getcontext
from typing import Iterable, Mapping


def _to_decimal(value: object) -> Decimal:
    """Coerce a curve payload value (str|int|float|Decimal) to Decimal exactly."""
    if isinstance(value, Decimal):
        return value
    if isinstance(value, (int, str)):
        return Decimal(str(value))
    if isinstance(value, float):
        return Decimal(str(value))
    raise TypeError(f"Unsupported curve value type: {type(value).__name__}")


def _sorted_curve(
    curve_snapshot: Iterable[Mapping[str, object]],
) -> list[tuple[int, Decimal]]:
    """Return the snapshot sorted by tenor, coerced to (int, Decimal) tuples."""
    points: list[tuple[int, Decimal]] = []
    for row in curve_snapshot:
        tenor = int(row["tenor_months"])
        rate = _to_decimal(row["spot_rate"])
        points.append((tenor, rate))
    if not points:
        raise ValueError("Empty curve snapshot")
    points.sort(key=lambda p: p[0])
    return points


def interpolate_spot_rate(
    curve_snapshot: Iterable[Mapping[str, object]],
    tenor_months: int,
) -> Decimal:
    """Linear interpolation between tenors; flat extrapolation past the ends."""
    points = _sorted_curve(curve_snapshot)
    first_tenor, first_rate = points[0]
    last_tenor, last_rate = points[-1]
    if tenor_months <= first_tenor:
        return first_rate
    if tenor_months >= last_tenor:
        return last_rate
    for i in range(len(points) - 1):
        lo_tenor, lo_rate = points[i]
        hi_tenor, hi_rate = points[i + 1]
        if lo_tenor <= tenor_months <= hi_tenor:
            span = Decimal(hi_tenor - lo_tenor)
            if span == 0:
                return lo_rate
            weight = Decimal(tenor_months - lo_tenor) / span
            return lo_rate + weight * (hi_rate - lo_rate)
    # Unreachable given the boundary guards above, but keep the fallthrough
    # explicit so a future bug in the sort/scan can't silently return None.
    raise ValueError(
        f"Curve interpolation failed for tenor={tenor_months}"
    )


def discount_factor(spot_rate: Decimal, months_ahead: int) -> Decimal:
    """v^t = 1/(1+r)^(t/12) — annual-compounded spot, monthly-fractional t.

    ``months_ahead=0`` returns 1 (undiscounted opening balance). Negative
    months are treated as zero — a shaping bug that flips a period end
    before its start should manifest as "no discount applied" rather than
    an exponent domain error.

    Decimal ** non-integer isn't supported in the stdlib, so the fractional
    exponent is applied via the natural-log identity
    ``(1+r)^t = exp(t · ln(1+r))``.
    """
    if months_ahead <= 0:
        return Decimal(1)
    getcontext().prec = 28
    base = Decimal(1) + spot_rate
    if base <= 0:
        raise ValueError(f"Non-positive discount base ({base})")
    exponent = Decimal(months_ahead) / Decimal(12)
    if exponent == exponent.to_integral_value():
        return Decimal(1) / (base ** int(exponent))
    return (-exponent * base.ln()).exp()


__all__ = [
    "interpolate_spot_rate",
    "discount_factor",
]
