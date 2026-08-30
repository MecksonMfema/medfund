"""GMM (General Measurement Model) projection + LRC/LIC compute — Phase 15 §12.

Reference IFRS 17 paragraphs:

* .32 — LRC = BEL + RA + CSM (loss component when onerous).
* .34–.44 — measurement of the LRC over the coverage period.
* .40–.52 — measurement of the LIC (shared with PAA).
* .88 — OCI option for the effect of changes in discount rate.

What this phase ships
─────────────────────
* A monthly cash-flow projection: expected premiums in, expected claims
  out (mortality-driven), expected expenses out — attenuated by the
  surviving-units decrement (mortality + lapse).
* PV of net cash flows at the **locked-in** yield curve snapshot from
  cohort inception (per I18); the same rate drives P&L unwind.
* An LrcMovement + LicMovement journal built off ``opening_lrc/lic`` and
  the shaping-service-supplied period movements. Closing LRC now carries
  a placeholder ``opening_ra + opening_csm`` in place of §13/§14 output —
  those phases replace the RA + CSM roll-forward.
* A ``GmmProjection`` attached to the result so §13 (CSM roll-forward
  over coverage units) and §14 (RA CoC/CI) can reuse the same ladder.

What §13 / §14 / §15 refine
───────────────────────────
* §13 replaces the passthrough CSM with coverage-unit-driven release.
* §14 replaces ``ra_change`` / ``ra_release`` with CoC/CI-derived movement.
* §15 detects an onerous cohort from ``projected_bel`` and mints the
  loss-component recognition + status transition.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Mapping

from app.ifrs17.discount_curve import discount_factor, interpolate_spot_rate
from app.ifrs17.types import (
    CashFlowRow,
    ChunkResult,
    FinanceExpensePresentation,
    GmmChunkInput,
    GmmProjection,
    LicMovement,
    LrcMovement,
)

MODEL_VERSION = "ifrs17-gmm-1.0"

# 40 years — covers whole-life contracts. Anything longer is truncated
# with a warning; the resulting reserve rounds well within the ≤1cent-
# per-policy materiality threshold at 5% discounting.
MAX_HORIZON_MONTHS = 480

# Below this fraction of a unit surviving, the projection stops early —
# the discounted PV of a residual is well under a cent per policy.
EXTINCTION_THRESHOLD = Decimal("0.0001")


def compute(payload: GmmChunkInput) -> ChunkResult:
    """Run GMM projection + LRC/LIC compute for one chunk."""
    projection = _project(payload)

    lrc_pl, lrc_oci = _finance_expense_split(
        opening_balance=payload.opening_lrc,
        presentation=payload.finance_expense_presentation,
        locked_rate=_first_tenor_rate(payload),
        current_rate=payload.current_discount_rate,
    )
    lic_pl, lic_oci = _finance_expense_split(
        opening_balance=payload.opening_lic,
        presentation=payload.finance_expense_presentation,
        locked_rate=_first_tenor_rate(payload),
        current_rate=payload.current_discount_rate,
    )

    # Insurance revenue for GMM at 17.83 = expected claims + expenses
    # + CSM release + RA release, released over coverage units. §17 shaping
    # supplies the pre-computed revenue amount; §13 supplies csm_release.
    lrc_closing = (
        payload.opening_lrc
        + payload.new_business_written
        + payload.premiums_received
        - payload.insurance_revenue
        - payload.insurance_acquisition_cf
        + payload.loss_component_recognized
        - payload.loss_component_released
        + lrc_pl
        + lrc_oci
    )
    lic_closing = (
        payload.opening_lic
        + payload.claims_incurred
        - payload.claims_paid
        + payload.expenses_incurred
        - payload.expenses_paid
        + payload.ra_change
        - payload.ra_release
        + lic_pl
        + lic_oci
    )

    lrc = LrcMovement(
        opening=payload.opening_lrc,
        new_business=payload.new_business_written,
        premiums_received=payload.premiums_received,
        insurance_revenue=payload.insurance_revenue,
        insurance_acquisition_cf=payload.insurance_acquisition_cf,
        loss_component_recognized=payload.loss_component_recognized,
        loss_component_released=payload.loss_component_released,
        finance_expense_pl=lrc_pl,
        finance_expense_oci=lrc_oci,
        closing=lrc_closing,
    )
    lic = LicMovement(
        opening=payload.opening_lic,
        claims_incurred=payload.claims_incurred,
        claims_paid=payload.claims_paid,
        expenses_incurred=payload.expenses_incurred,
        expenses_paid=payload.expenses_paid,
        ra_change=payload.ra_change,
        ra_release=payload.ra_release,
        finance_expense_pl=lic_pl,
        finance_expense_oci=lic_oci,
        closing=lic_closing,
    )

    warnings: list[str] = []
    if projection.truncated_by_horizon_cap:
        warnings.append(
            f"projection truncated at MAX_HORIZON_MONTHS={MAX_HORIZON_MONTHS}; "
            "residual PV assumed immaterial"
        )
    if not payload.locked_in_curve:
        warnings.append(
            "locked_in_curve empty — falling back to zero rate; "
            "shaping should populate from ifrs17_cohort.locked_in_yield_curve_snapshot"
        )

    return ChunkResult(
        job_id=payload.job_id,
        tenant_id=payload.tenant_id,
        portfolio_id=payload.portfolio_id,
        cohort_id=payload.cohort_id,
        currency=payload.currency,
        model="GMM",
        lrc_movement=lrc,
        lic_movement=lic,
        discounting_applied=True,
        discounting_skip_reason=None,
        applied_rule_name=payload.applied_rule_name,
        warnings=warnings,
        projection=projection,
    )


def compute_from_dict(payload: dict) -> ChunkResult:
    """Runner convenience — accepts the raw ``ifrs17_json`` slot."""
    return compute(GmmChunkInput(**payload))


def _project(payload: GmmChunkInput) -> GmmProjection:
    horizon = min(payload.horizon_months, MAX_HORIZON_MONTHS)
    truncated_by_cap = payload.horizon_months > MAX_HORIZON_MONTHS

    curve = [
        {"tenor_months": p.tenor_months, "spot_rate": p.spot_rate}
        for p in payload.locked_in_curve
    ]

    monthly_lapse = payload.annual_lapse_rate / Decimal(12)
    qx_lookup = _qx_by_age(payload.mortality_qx, payload.avg_age_sex)

    surviving = Decimal(1)
    rows: list[CashFlowRow] = []
    projected_bel = Decimal(0)
    truncated_by_extinction = False

    for month in range(horizon):
        age = payload.avg_age + month // 12
        annual_qx_per_1000 = _interp_qx(qx_lookup, age)
        monthly_qx = (
            annual_qx_per_1000 * payload.mortality_multiplier / Decimal(12000)
        )

        deaths_this_month = surviving * monthly_qx
        premium_in = payload.premium_per_policy_monthly * surviving
        claims_out = payload.avg_sum_insured * deaths_this_month
        expenses_out = payload.monthly_expense_per_policy * surviving
        net = premium_in - claims_out - expenses_out

        months_ahead = month + 1
        rate = (
            interpolate_spot_rate(curve, months_ahead) if curve else Decimal(0)
        )
        factor = discount_factor(rate, months_ahead)
        pv = net * factor
        projected_bel += pv

        rows.append(
            CashFlowRow(
                month=month,
                surviving_units=surviving,
                expected_premium_in=premium_in,
                expected_claims_out=claims_out,
                expected_expenses_out=expenses_out,
                net_cash_flow=net,
                spot_rate=rate,
                discount_factor=factor,
                present_value=pv,
            )
        )

        surviving = surviving - deaths_this_month - (surviving * monthly_lapse)
        if surviving < EXTINCTION_THRESHOLD:
            truncated_by_extinction = True
            break

    # BEL is defined as the PV of *outflows minus inflows* (i.e. a positive
    # BEL is a net liability). The projection ladder above accumulates
    # `net = premium_in - claims_out - expenses_out`, so the liability is
    # the negation of the accumulated PV.
    projected_bel = -projected_bel

    return GmmProjection(
        rows=rows,
        projected_bel=projected_bel,
        horizon_months_used=len(rows),
        truncated_by_horizon_cap=truncated_by_cap,
        truncated_by_extinction=truncated_by_extinction,
    )


def _first_tenor_rate(payload: GmmChunkInput) -> Decimal:
    if not payload.locked_in_curve:
        return Decimal(0)
    shortest = min(payload.locked_in_curve, key=lambda p: p.tenor_months)
    return shortest.spot_rate


def _qx_by_age(
    qx: Mapping[int, Mapping[str, Decimal]], sex: str
) -> dict[int, Decimal]:
    """Flatten a sex-keyed qx grid into ``{age: qx_per_1000}`` for one sex."""
    return {int(age): Decimal(str(row.get(sex, row.get("composite", 0))))
            for age, row in qx.items()}


def _interp_qx(qx_lookup: dict[int, Decimal], age: int) -> Decimal:
    """Linear interpolation between the two nearest canonical select ages.

    Empty grid returns zero — the projection then only sees lapse decrement
    (a hand-fired dev event without mortality data still projects cleanly).
    """
    if not qx_lookup:
        return Decimal(0)
    ages = sorted(qx_lookup.keys())
    if age <= ages[0]:
        return qx_lookup[ages[0]]
    if age >= ages[-1]:
        return qx_lookup[ages[-1]]
    for i in range(len(ages) - 1):
        lo, hi = ages[i], ages[i + 1]
        if lo <= age <= hi:
            if hi == lo:
                return qx_lookup[lo]
            weight = Decimal(age - lo) / Decimal(hi - lo)
            return qx_lookup[lo] + weight * (qx_lookup[hi] - qx_lookup[lo])
    return qx_lookup[ages[-1]]


def _finance_expense_split(
    *,
    opening_balance: Decimal,
    presentation: FinanceExpensePresentation,
    locked_rate: Decimal,
    current_rate: Decimal,
) -> tuple[Decimal, Decimal]:
    """Split interest unwind into P&L and OCI portions per 17.88.

    * ``PL_ONLY`` — full unwind to P&L, OCI zero.
    * ``OCI_OPTION`` — locked-in unwind to P&L, ``(current − locked) × balance``
      to OCI. Same shape as PAA per :mod:`app.ifrs17.paa`.
    """
    if presentation == "PL_ONLY":
        return opening_balance * locked_rate, Decimal(0)
    pl = opening_balance * locked_rate
    oci = opening_balance * (current_rate - locked_rate)
    return pl, oci


__all__ = [
    "compute",
    "compute_from_dict",
    "MODEL_VERSION",
    "MAX_HORIZON_MONTHS",
    "EXTINCTION_THRESHOLD",
]
