"""CSM (Contractual Service Margin) roll-forward — Phase 15 §13.

Reference paragraphs:

* .44 — CSM roll-forward (initial recognition + interest accretion +
  changes in fulfilment cash flows + FX effect + release).
* .B96–.B119A — experience adjustments and the coverage-unit release
  mechanic.
* .17–.19 — loss component recognition when the group becomes onerous.

Roll-forward formula
────────────────────

    CSM(t) = accreted + experience_adjustment - release
    accreted = CSM(t-1) × (1 + r_locked)^(1/12)
    release  = min(1, release_fraction) × max(0, after_experience)
    release_fraction = coverage_units_delivered / coverage_units_remaining_before

Where ``r_locked`` is the shortest-tenor spot on the cohort's locked-in
yield curve snapshot (per I18 — the same rate GMM uses for LRC/LIC
finance-expense unwind, so the CSM keeps pace with the rest of the LRC).

Loss component handling
───────────────────────

When ``after_experience`` (post-accretion + experience) is negative, the
group has become onerous (per IFRS 17.48 → 49). No release fires — the
release is defined against a non-negative CSM — and the negative delta
becomes the loss component amount that §15 posts into
``cohort_loss_component_history``. ``closing`` clamps to zero: a
"negative CSM" is not a defined IFRS 17 state.

Release-fraction saturation
───────────────────────────

When the coverage-unit delivery ratio ≥ 1 (last period of coverage, or
a very short remaining tail), release is capped at 1.0 so ``closing``
lands at exactly zero rather than a floating-point sliver-below-zero.
The next report period will see ``opening_csm=0`` and no further release.

Why release is against ``max(0, after_experience)``, not accreted
────────────────────────────────────────────────────────────────

IFRS 17.44(e) attaches release to the "amount of CSM recognised as
insurance revenue for services provided in the period" — read as the
post-experience CSM, because experience adjustments already reflect the
value of the promise as at the period end. A negative experience makes
the promise less valuable, so the release against it is smaller (or
zero — the loss component takes over).
"""

from __future__ import annotations

from decimal import Decimal
from typing import Mapping, NamedTuple, Sequence

from app.ifrs17.discount_curve import interpolate_spot_rate


class CsmRollForwardResult(NamedTuple):
    """Return shape for ``roll_forward`` — three-tuple destructures easily."""

    closing: Decimal
    released: Decimal
    went_negative: bool
    loss_component_recognised: Decimal


def _first_tenor_rate(
    curve_snapshot: Sequence[Mapping[str, object]],
) -> Decimal:
    """Locked-in rate at the shortest observed tenor — flat-extrapolated
    at :func:`~app.ifrs17.discount_curve.interpolate_spot_rate` when the
    caller passes an implausibly short tenor. An empty curve is an
    invariant violation — CSM cannot accrete without a rate."""
    if not curve_snapshot:
        raise ValueError(
            "CSM roll-forward requires a non-empty locked-in curve snapshot; "
            "shaping service (§17) must populate from "
            "ifrs17_cohort.locked_in_yield_curve_snapshot per I18"
        )
    # The shortest tenor drives short-horizon accretion for one monthly step.
    # We do not interpolate for a synthetic "month 1" tenor — the shortest
    # observed tenor is what the cohort locked in at, and using it flat is
    # consistent with IFRS 17.B72(b)'s "top-down" curve construction.
    return interpolate_spot_rate(curve_snapshot, tenor_months=1)


def _monthly_accretion(rate: Decimal) -> Decimal:
    """Compound annual → monthly via (1 + r)^(1/12) − 1."""
    if rate == 0:
        return Decimal(0)
    base = Decimal(1) + rate
    if base <= 0:
        raise ValueError(
            f"Non-positive discount base ({base}) in CSM accretion — "
            "curve snapshot is malformed"
        )
    # Decimal ** fractional is unsupported; fall back to ln/exp identity.
    return (Decimal(1) / Decimal(12) * base.ln()).exp() - Decimal(1)


def roll_forward(
    *,
    opening_csm: Decimal,
    locked_in_curve: Sequence[Mapping[str, object]],
    experience_adjustment: Decimal,
    coverage_units_delivered: Decimal,
    coverage_units_remaining_before: Decimal,
) -> CsmRollForwardResult:
    """Advance a CSM balance by one period.

    Parameters
    ──────────
    opening_csm:
        Prior-period closing CSM. Negative values are treated as an
        invariant violation (should be zero + loss component instead).
    locked_in_curve:
        Cohort's locked-in yield curve snapshot per I18.
    experience_adjustment:
        Cash-flow experience delta per IFRS 17.B96 — positive if actuals
        were more favourable than expected, negative otherwise.
    coverage_units_delivered:
        Coverage units earned this period, from
        :func:`~app.ifrs17.coverage_units.compute`.
    coverage_units_remaining_before:
        Coverage units the cohort was expected to deliver as at the
        start of the period, from the opening projection ladder.

    Returns
    ───────
    :class:`CsmRollForwardResult` — ``closing`` non-negative,
    ``released`` non-negative, ``went_negative`` flagged when the
    accretion+experience combination pushed the CSM below zero, and
    ``loss_component_recognised`` carrying the negative amount as a
    positive number (§15 posts it as INITIAL_RECOGNITION).
    """
    if opening_csm < 0:
        raise ValueError(
            f"opening_csm ({opening_csm}) must be non-negative — a negative "
            "CSM is not a defined IFRS 17 state; the loss component absorbs "
            "the shortfall (see §15)"
        )
    if coverage_units_delivered < 0:
        raise ValueError(
            f"coverage_units_delivered ({coverage_units_delivered}) must be non-negative"
        )
    if coverage_units_remaining_before < 0:
        raise ValueError(
            f"coverage_units_remaining_before ({coverage_units_remaining_before}) "
            "must be non-negative"
        )

    rate = _first_tenor_rate(locked_in_curve)
    monthly_rate = _monthly_accretion(rate)
    accreted = opening_csm + opening_csm * monthly_rate
    after_experience = accreted + experience_adjustment

    if after_experience < 0:
        # Onerous transition — no CSM release, loss component takes over.
        return CsmRollForwardResult(
            closing=Decimal(0),
            released=Decimal(0),
            went_negative=True,
            loss_component_recognised=-after_experience,
        )

    if coverage_units_remaining_before == 0:
        # No expected remaining units → nothing to release against.
        # Typical at the very last coverage period after a full delivery
        # has already zeroed the pool. Preserves after_experience as
        # closing (usually 0 anyway); a stale opening CSM here would
        # surface via the fact that we're carrying a positive closing
        # into a cohort with zero expected coverage — §15 flags that.
        return CsmRollForwardResult(
            closing=after_experience,
            released=Decimal(0),
            went_negative=False,
            loss_component_recognised=Decimal(0),
        )

    release_fraction = coverage_units_delivered / coverage_units_remaining_before
    if release_fraction >= 1:
        # Full release — cap to avoid over-releasing when the delivery
        # ratio drifted above 1 (typically the last period).
        return CsmRollForwardResult(
            closing=Decimal(0),
            released=after_experience,
            went_negative=False,
            loss_component_recognised=Decimal(0),
        )

    released = after_experience * release_fraction
    closing = after_experience - released
    return CsmRollForwardResult(
        closing=closing,
        released=released,
        went_negative=False,
        loss_component_recognised=Decimal(0),
    )


__all__ = [
    "CsmRollForwardResult",
    "roll_forward",
]
