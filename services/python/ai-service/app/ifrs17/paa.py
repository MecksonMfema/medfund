"""PAA (Premium Allocation Approach) LRC/LIC compute — Phase 15 §11.

Reference IFRS 17 paragraphs:

* .53–.59 — PAA measurement of the LRC.
* .40–.52 — LIC measurement (shared with GMM).
* .59(a)/(b) — the "simplified" opt-outs: if the coverage period is
  ≤12 months, an insurer *may* skip discounting on LRC; if claims
  are expected to settle within 12 months, the LIC does not need
  discounting either. This module applies both opt-outs when the
  respective duration is ≤12 months, matching the industry default.

Everything else — new-business acquisition, loss component splits,
finance-expense P&L/OCI allocation — follows the LrcMovement /
LicMovement journal shape defined in :mod:`app.ifrs17.types`. This
module does arithmetic only; the Java shaping service (§17) supplies
pre-aggregated balances so the compute is deterministic and side-
effect free.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from app.ifrs17.types import (
    ChunkResult,
    FinanceExpensePresentation,
    LicMovement,
    LrcMovement,
    PaaChunkInput,
)

DISCOUNT_SKIP_THRESHOLD_MONTHS = 12
DISCOUNT_SKIP_THRESHOLD_DAYS = 365

MODEL_VERSION = "ifrs17-paa-1.0"


def compute(payload: PaaChunkInput) -> ChunkResult:
    """Run PAA LRC + LIC compute for one chunk."""
    coverage_months = _months_between(
        payload.earliest_coverage_start, payload.latest_coverage_end
    )
    lrc_discount = coverage_months > DISCOUNT_SKIP_THRESHOLD_MONTHS
    lic_discount = payload.median_settlement_days > DISCOUNT_SKIP_THRESHOLD_DAYS
    applied = lrc_discount or lic_discount
    skip_reason = (
        None
        if applied
        else "IFRS_17_59_coverage_and_settlement_le_12mo"
    )

    lrc_pl, lrc_oci = _finance_expense_split(
        opening_balance=payload.opening_lrc,
        apply=lrc_discount,
        presentation=payload.finance_expense_presentation,
        locked_rate=payload.locked_in_discount_rate,
        current_rate=payload.current_discount_rate,
    )
    lic_pl, lic_oci = _finance_expense_split(
        opening_balance=payload.opening_lic,
        apply=lic_discount,
        presentation=payload.finance_expense_presentation,
        locked_rate=payload.locked_in_discount_rate,
        current_rate=payload.current_discount_rate,
    )

    lrc_closing = (
        payload.opening_lrc
        + payload.new_business_written
        + payload.premiums_received
        - payload.earned_in_period
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
        insurance_revenue=payload.earned_in_period,
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

    return ChunkResult(
        job_id=payload.job_id,
        tenant_id=payload.tenant_id,
        portfolio_id=payload.portfolio_id,
        cohort_id=payload.cohort_id,
        currency=payload.currency,
        model="PAA",
        lrc_movement=lrc,
        lic_movement=lic,
        discounting_applied=applied,
        discounting_skip_reason=skip_reason,
        applied_rule_name=payload.applied_rule_name,
        warnings=[],
    )


def compute_from_dict(payload: dict) -> ChunkResult:
    """Runner convenience — accepts the raw ``ifrs17_json`` slot."""
    return compute(PaaChunkInput(**payload))


def _months_between(start: date, end: date) -> int:
    """Whole-month gap between two dates, floored at zero.

    IFRS 17 counts coverage-period length inclusively at the day level, but
    the ≤12mo test at 17.59 is coarse enough that a month approximation is
    the standard practical implementation. Callers concerned about
    boundary cases (e.g. a contract that runs 12mo + 1d) should compute
    days externally and set ``median_settlement_days`` accordingly.
    """
    if end < start:
        return 0
    return (end.year - start.year) * 12 + (end.month - start.month)


def _finance_expense_split(
    *,
    opening_balance: Decimal,
    apply: bool,
    presentation: FinanceExpensePresentation,
    locked_rate: Decimal,
    current_rate: Decimal,
) -> tuple[Decimal, Decimal]:
    """Split interest unwind into P&L and OCI portions per presentation choice.

    * ``apply=False`` — zero on both sides (17.59 opt-out).
    * ``presentation='PL_ONLY'`` — full unwind to P&L, OCI zero.
    * ``presentation='OCI_OPTION'`` — locked-in unwind to P&L, delta
      ``(current - locked_in) * balance`` absorbed by OCI. Matches the
      systematic-allocation option at 17.88.
    """
    if not apply:
        return Decimal(0), Decimal(0)
    if presentation == "PL_ONLY":
        return opening_balance * locked_rate, Decimal(0)
    pl = opening_balance * locked_rate
    oci = opening_balance * (current_rate - locked_rate)
    return pl, oci


__all__ = [
    "compute",
    "compute_from_dict",
    "MODEL_VERSION",
    "DISCOUNT_SKIP_THRESHOLD_MONTHS",
    "DISCOUNT_SKIP_THRESHOLD_DAYS",
]
