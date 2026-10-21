"""Pydantic envelopes for IFRS 17 chunk compute (Phase 15 §11).

Every field carrying money is ``Decimal`` per Rule 1 (never floating point).
Callers should pass strings or numeric literals; Pydantic coerces both. The
Kafka runner serializes via ``model_dump(mode='json')`` so nested Decimals
render as strings before ``json.dumps`` sees the outer envelope.

``PaaChunkInput`` covers the Phase 11 PAA compute. ``GmmChunkInput`` covers
Phase 12 GMM projection. ``VfaChunkInput`` lands in §16 for direct-
participation (unit-linked) contracts.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, Field

MeasurementModel = Literal["PAA", "GMM", "VFA"]
FinanceExpensePresentation = Literal["PL_ONLY", "OCI_OPTION"]


class LrcMovement(BaseModel):
    """Liability for Remaining Coverage movement journal for one chunk period.

    Opening → closing arithmetic (per IFRS 17.55-59 for PAA; 17.40-.44 for GMM):

        closing = opening
                + new_business + premiums_received
                - insurance_revenue - insurance_acquisition_cf
                + loss_component_recognized - loss_component_released
                + finance_expense_pl + finance_expense_oci

    All amounts are in the chunk's ``currency``; envelope rollups apply FX
    per I8 (historical for movements, closing for balances) at §17/§18.
    """

    opening: Decimal
    new_business: Decimal = Decimal(0)
    premiums_received: Decimal = Decimal(0)
    insurance_revenue: Decimal = Decimal(0)
    insurance_acquisition_cf: Decimal = Decimal(0)
    loss_component_recognized: Decimal = Decimal(0)
    loss_component_released: Decimal = Decimal(0)
    finance_expense_pl: Decimal = Decimal(0)
    finance_expense_oci: Decimal = Decimal(0)
    closing: Decimal


class LicMovement(BaseModel):
    """Liability for Incurred Claims movement journal for one chunk period.

        closing = opening
                + claims_incurred + expenses_incurred
                - claims_paid - expenses_paid
                + ra_change - ra_release
                + finance_expense_pl + finance_expense_oci
    """

    opening: Decimal
    claims_incurred: Decimal = Decimal(0)
    claims_paid: Decimal = Decimal(0)
    expenses_incurred: Decimal = Decimal(0)
    expenses_paid: Decimal = Decimal(0)
    ra_change: Decimal = Decimal(0)
    ra_release: Decimal = Decimal(0)
    finance_expense_pl: Decimal = Decimal(0)
    finance_expense_oci: Decimal = Decimal(0)
    closing: Decimal


class PaaChunkInput(BaseModel):
    """Per portfolio × cohort × currency chunk payload for PAA compute.

    The Java shaping service (§17) builds one of these per chunk and Kafka-
    delivers it inside ``ReportJobRequestedEvent.ifrs17_json``. All balance
    fields are pre-aggregated so this module does arithmetic only — no
    database reads, no cross-service calls.

    Discounting decision drivers per IFRS 17.59:

    * ``coverage_duration_months`` from ``earliest_coverage_start`` →
      ``latest_coverage_end`` — if ≤12mo, LRC finance expense is skipped.
    * ``median_settlement_days`` — if ≤365 days (≤12mo), LIC finance
      expense is skipped.

    Both must fall under the threshold for ``discounting_applied=False``.
    """

    job_id: str
    tenant_id: str
    portfolio_id: str
    cohort_id: str
    currency: str
    reporting_period_start: date
    reporting_period_end: date
    earliest_coverage_start: date
    latest_coverage_end: date
    finance_expense_presentation: FinanceExpensePresentation = "PL_ONLY"
    applied_rule_name: str | None = None

    opening_lrc: Decimal = Decimal(0)
    opening_lic: Decimal = Decimal(0)
    new_business_written: Decimal = Decimal(0)
    premiums_received: Decimal = Decimal(0)
    earned_in_period: Decimal = Decimal(0)
    insurance_acquisition_cf: Decimal = Decimal(0)

    claims_incurred: Decimal = Decimal(0)
    claims_paid: Decimal = Decimal(0)
    expenses_incurred: Decimal = Decimal(0)
    expenses_paid: Decimal = Decimal(0)

    loss_component_recognized: Decimal = Decimal(0)
    loss_component_released: Decimal = Decimal(0)
    ra_change: Decimal = Decimal(0)
    ra_release: Decimal = Decimal(0)

    # Discounting inputs (used only when the 17.59 test says to discount).
    # Per-period rates: caller (§17 shaping) converts annual → period at the
    # reporting cadence. Zero is the sentinel for "not populated".
    locked_in_discount_rate: Decimal = Decimal(0)
    current_discount_rate: Decimal = Decimal(0)
    median_settlement_days: int = 0


class YieldCurvePoint(BaseModel):
    """Single tenor/rate pair on a locked-in yield curve snapshot.

    Matches the JSONB shape written to
    ``ifrs17_cohort.locked_in_yield_curve_snapshot`` in Phase 6 (V145) and
    threaded through the request event's ``ifrs17_json`` slot per I18.
    """

    tenor_months: int
    spot_rate: Decimal


class CashFlowRow(BaseModel):
    """One month of the projected fulfilment cash flow ladder.

    Emitted alongside the movement journals so §13 (CSM roll-forward) and
    §14 (RA) can consume the same projection without re-running it.
    """

    month: int
    surviving_units: Decimal
    expected_premium_in: Decimal = Decimal(0)
    expected_claims_out: Decimal = Decimal(0)
    expected_expenses_out: Decimal = Decimal(0)
    net_cash_flow: Decimal
    spot_rate: Decimal
    discount_factor: Decimal
    present_value: Decimal


class GmmChunkInput(BaseModel):
    """Per portfolio × cohort × currency chunk payload for GMM projection.

    The Java shaping service (§17) builds one of these per chunk and Kafka-
    delivers it inside ``ReportJobRequestedEvent.ifrs17_json``. All balance
    fields are pre-aggregated so this module does arithmetic + projection
    only — no database reads, no cross-service calls.

    Basis-driven projection inputs
    ──────────────────────────────
    * ``mortality_qx`` — the ``qx`` grid parsed from a Phase-14 basis
      YAML (e.g. ``CSO_2017``). Keys are canonical select ages; values
      are per-thousand annual mortality per sex. The shaping service
      calls ``basis_loader.load_basis_table`` and inlines the grid here
      so the compute stays a pure function.
    * ``mortality_multiplier`` — the tenant's per-line
      ``tenant_mortality_basis.mortality_multiplier`` scaling factor.
    * ``annual_lapse_rate`` — the tenant's per-line lapse assumption
      resolved from ``tenant_persistency_basis`` at shape time.
    * ``avg_age`` / ``avg_age_sex`` — cohort characteristic used to look
      up the qx grid entry (linearly interpolated across select ages).

    The Phase-14 loader lives in Java on the persistency/mortality path
    already; this module reads the resolved grid from the payload rather
    than re-hitting the DB, matching the pattern in :mod:`app.actuarial`.
    """

    job_id: str
    tenant_id: str
    portfolio_id: str
    cohort_id: str
    currency: str
    insurance_line: str
    reporting_period_start: date
    reporting_period_end: date
    finance_expense_presentation: FinanceExpensePresentation = "PL_ONLY"
    applied_rule_name: str | None = None

    # Projection driver inputs.
    horizon_months: int = Field(..., gt=0)
    avg_age: int = Field(..., ge=0)
    avg_age_sex: Literal["male", "female"] = "male"
    mortality_qx: dict[int, dict[str, Decimal]] = Field(default_factory=dict)
    mortality_multiplier: Decimal = Decimal(1)
    annual_lapse_rate: Decimal = Decimal(0)
    premium_per_policy_monthly: Decimal = Decimal(0)
    avg_sum_insured: Decimal = Decimal(0)
    monthly_expense_per_policy: Decimal = Decimal(0)

    # Locked-in yield curve snapshot per I18.
    locked_in_curve: list[YieldCurvePoint] = Field(default_factory=list)

    # Opening balances (RA + CSM refined in §13/§14, threaded through here).
    opening_lrc: Decimal = Decimal(0)
    opening_lic: Decimal = Decimal(0)
    opening_ra: Decimal = Decimal(0)
    opening_csm: Decimal = Decimal(0)

    # Period movements (§17 shaping supplies from ledger + coverage-unit release).
    new_business_written: Decimal = Decimal(0)
    premiums_received: Decimal = Decimal(0)
    insurance_revenue: Decimal = Decimal(0)
    insurance_acquisition_cf: Decimal = Decimal(0)
    loss_component_recognized: Decimal = Decimal(0)
    loss_component_released: Decimal = Decimal(0)
    claims_incurred: Decimal = Decimal(0)
    claims_paid: Decimal = Decimal(0)
    expenses_incurred: Decimal = Decimal(0)
    expenses_paid: Decimal = Decimal(0)
    ra_change: Decimal = Decimal(0)
    ra_release: Decimal = Decimal(0)
    csm_release: Decimal = Decimal(0)

    # Current curve for OCI split (17.88); locked-in rate for P&L.
    current_discount_rate: Decimal = Decimal(0)


class GmmProjection(BaseModel):
    """Projected cash-flow ladder + summary aggregates for one chunk.

    Attached to :class:`ChunkResult` when ``model == 'GMM'`` so §13/§14
    can roll CSM + RA over the same ladder without re-projecting.
    """

    rows: list[CashFlowRow] = Field(default_factory=list)
    projected_bel: Decimal
    horizon_months_used: int
    truncated_by_horizon_cap: bool = False
    truncated_by_extinction: bool = False


class VfaChunkInput(BaseModel):
    """Per portfolio × cohort × currency chunk payload for VFA compute (§16).

    VFA (Variable Fee Approach — IFRS 17.71-B119) governs direct-participation
    contracts (typically unit-linked life). Balance-sheet obligation is the
    share of the fair value of the underlying items (the fund the policyholder
    is invested in), less the entity's *variable fee* — the fee % applied to
    the underlying items' returns that accrues to the entity as CSM.

    The Java shaping service (§17) pre-aggregates every input field by
    walking Phase-8's ``fund_nav_history`` + ``policy_unit_ledger`` +
    ``variable_fee_schedule`` tables for the chunk's period, so this compute
    stays a pure function — no database reads, no cross-service calls.

    Pre-aggregation choices (why the compute doesn't walk the ledger itself)
    ────────────────────────────────────────────────────────────────────────
    * ``opening_underlying_fair_value`` / ``closing_underlying_fair_value``:
      Σ over policies (units_at_date × NAV_at_date × share_pct) evaluated at
      period boundaries. The compute treats these as opaque balances rather
      than re-walking the NAV grid — matches the ``opening_lrc`` / ``opening_lic``
      convention that PAA + GMM already use.
    * ``variable_fee_earned``: shaping computes ``fee_percentage × underlying_growth``
      from the ``variable_fee_schedule`` effective for the period. This value
      drives BOTH the CSM ``experience_adjustment`` (entity's accrual of the
      fee) AND the ``coverage_units_delivered`` (the entity's economic activity
      is proportional to the fee it earned). §16 §Deviation 1 documents this
      dual role.
    * ``variable_fee_pool_remaining``: expected total variable fee across the
      remainder of the coverage period. Used as the CSM release denominator
      (release_fraction = variable_fee_earned / variable_fee_pool_remaining)
      — mirrors how GMM uses coverage units.
    """

    job_id: str
    tenant_id: str
    portfolio_id: str
    cohort_id: str
    currency: str
    reporting_period_start: date
    reporting_period_end: date
    finance_expense_presentation: FinanceExpensePresentation = "PL_ONLY"
    applied_rule_name: str | None = None

    # Underlying items fair value (entity's share of the policyholder pool).
    # Pre-aggregated by shaping from fund_nav_history × policy_unit_ledger
    # at period boundaries. See docstring for the walk.
    opening_underlying_fair_value: Decimal = Decimal(0)
    closing_underlying_fair_value: Decimal = Decimal(0)

    # Variable fee earned this period + remaining pool over coverage tail.
    # Shaping computes from variable_fee_schedule.fee_percentage × underlying
    # growth. Dual role: CSM experience_adjustment AND release driver.
    variable_fee_earned: Decimal = Decimal(0)
    variable_fee_pool_remaining: Decimal = Decimal(0)

    # Opening balances (from prior period's closing). Balance-sheet
    # identity: opening_lrc == opening_underlying_fair_value + opening_csm
    # + opening_loss_component. Caller is responsible for feeding all four
    # consistently — the compute uses each independently.
    opening_lrc: Decimal = Decimal(0)
    opening_lic: Decimal = Decimal(0)
    opening_csm: Decimal = Decimal(0)
    opening_loss_component: Decimal = Decimal(0)

    # Period movements (§17 shaping supplies from ledger + coverage-unit release).
    new_business_written: Decimal = Decimal(0)
    premiums_received: Decimal = Decimal(0)
    insurance_acquisition_cf: Decimal = Decimal(0)
    claims_incurred: Decimal = Decimal(0)
    claims_paid: Decimal = Decimal(0)
    expenses_incurred: Decimal = Decimal(0)
    expenses_paid: Decimal = Decimal(0)
    loss_component_recognized: Decimal = Decimal(0)
    loss_component_released: Decimal = Decimal(0)
    ra_change: Decimal = Decimal(0)
    ra_release: Decimal = Decimal(0)

    # Locked-in yield curve snapshot per I18 — accretes the CSM.
    # Current curve for OCI split at 17.88 (VFA has an OCI option too).
    locked_in_curve: list[YieldCurvePoint] = Field(default_factory=list)
    current_discount_rate: Decimal = Decimal(0)


class ChunkResult(BaseModel):
    """Compute output for one portfolio × cohort × currency chunk.

    Aggregator (§18) folds one of these per chunk into the parent job's
    envelope shape (I13). ``model`` names which measurement approach fired
    — must match the ``IFRS17_MODEL`` rule that selected it, and drives
    the aggregator's per-portfolio grouping.
    """

    job_id: str
    tenant_id: str
    portfolio_id: str
    cohort_id: str
    currency: str
    model: MeasurementModel
    lrc_movement: LrcMovement
    lic_movement: LicMovement
    discounting_applied: bool
    discounting_skip_reason: str | None = None
    applied_rule_name: str | None = None
    warnings: list[str] = Field(default_factory=list)
    projection: GmmProjection | None = None


__all__ = [
    "MeasurementModel",
    "FinanceExpensePresentation",
    "LrcMovement",
    "LicMovement",
    "PaaChunkInput",
    "GmmChunkInput",
    "GmmProjection",
    "CashFlowRow",
    "YieldCurvePoint",
    "VfaChunkInput",
    "ChunkResult",
]
