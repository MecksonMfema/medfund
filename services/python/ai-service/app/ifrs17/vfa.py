"""VFA (Variable Fee Approach) compute — Phase 15 §16.

Reference IFRS 17 paragraphs:

* .71 — VFA scope: contracts with direct-participation features that
  entitle the policyholder to a share of the fair value of a clearly
  identified pool of underlying items.
* .45 — VFA measurement model: obligation = share of the fair value of
  underlying items minus a variable fee (the entity's share).
* .B96–.B119 — variable-fee mechanics: fee = fee % × underlying-items
  returns; entity's share of the change in underlying items is captured
  in CSM (not P&L) as an experience adjustment.
* .87A — VFA-specific OCI option: entity may present entity's share of
  the change in fair value of underlying items in OCI instead of P&L.
* .49 — loss component recognition when after-experience CSM would be
  driven below zero.

Balance-sheet identity is the ground truth
──────────────────────────────────────────
Under VFA, the LRC always resolves to:

    LRC = (policyholder's fair value share of underlying items)
        + (CSM, non-negative)
        + (loss component, non-negative)

This module drives compute off that identity. The CSM roll-forward
(:mod:`app.ifrs17.csm`) supplies the closing CSM + new loss component
(when after-experience goes negative). The policyholder-side fair value
is pre-aggregated by shaping (§17). Closing LRC = sum of those three.

The movement-journal ``finance_expense_pl`` + ``finance_expense_oci``
fields then absorb whatever residual is needed to reconcile the standard
LrcMovement template (opening + inflows − outflows + adjustments +
finance = closing) with the identity-derived closing. This keeps the
schema stable across PAA/GMM/VFA while letting VFA's fair-value-change
+ variable-fee-absorption flow through a single line item.

Why not compute finance_expense from first principles
─────────────────────────────────────────────────────
A first-principles finance-expense split for VFA would be:

    finance_expense = fair_value_growth
                    + csm_delta
                    + insurance_revenue        (added back because it's
                                               already subtracted as a
                                               separate flow)

That works when the CSM stays positive (steady state). But under an
onerous transition the CSM clamps at zero and part of the negative
variable-fee shortfall becomes loss component — the algebra to
disentangle csm_delta into "the part absorbed by CSM" vs "the part that
overflowed into loss component" is where readers trip up. The identity
approach sidesteps that by anchoring on the balance-sheet total; the
finance-expense residual carries whatever the identity says.

CSM dual role of ``variable_fee_earned``
────────────────────────────────────────
The variable fee earned this period is BOTH:

* the CSM ``experience_adjustment`` — entity's economic gain flows into
  CSM per 17.45(b)(ii) (may be negative in an adverse period), AND
* the CSM ``coverage_units_delivered`` — release proportional to the
  fee earned (a proxy for entity's economic activity).

An adverse period → negative variable_fee_earned. The CSM roll-forward
accepts negative experience_adjustment (the onerous-test path); but
coverage_units_delivered is clamped at ``max(Decimal(0), variable_fee)``
because "delivered −20 units of coverage" is not a defined state — the
entity earned nothing this period, so it released nothing. When the
adverse experience tips after-experience CSM below zero, the roll-forward
returns went_negative=True with the shortfall as ``loss_component_recognised``.
"""

from __future__ import annotations

from decimal import Decimal

from app.ifrs17 import csm
from app.ifrs17.types import (
    ChunkResult,
    LicMovement,
    LrcMovement,
    VfaChunkInput,
)

MODEL_VERSION = "ifrs17-vfa-1.0"


def compute(payload: VfaChunkInput) -> ChunkResult:
    """Run VFA CSM roll-forward + LRC/LIC compute for one chunk."""
    curve_dicts = [
        {"tenor_months": p.tenor_months, "spot_rate": p.spot_rate}
        for p in payload.locked_in_curve
    ]

    csm_result = csm.roll_forward(
        opening_csm=payload.opening_csm,
        locked_in_curve=curve_dicts,
        experience_adjustment=payload.variable_fee_earned,
        coverage_units_delivered=max(Decimal(0), payload.variable_fee_earned),
        coverage_units_remaining_before=payload.variable_fee_pool_remaining,
    )

    # VFA insurance revenue = variable fee released from CSM this period.
    insurance_revenue = csm_result.released

    # Loss component recognised this period = shortfall from onerous
    # transition (from CSM roll-forward) + any external recognition
    # (from an §15 auto-transition happening in the same reporting run).
    loss_component_recognized_this_period = (
        csm_result.loss_component_recognised + payload.loss_component_recognized
    )

    # LIC follows the same movement journal shape as PAA / GMM — VFA-specific
    # underlying-items flow only affects the LRC.
    lic_pl, lic_oci = _lic_finance_expense_split(payload)
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

    # LRC closing via balance-sheet identity — see module docstring.
    # Opening loss component carries forward from prior period; the new
    # recognition (from CSM going onerous OR external §15 injection) adds
    # to it; explicit releases (recovery, per IFRS 17.52) subtract.
    closing_loss_component_held = (
        payload.opening_loss_component
        + loss_component_recognized_this_period
        - payload.loss_component_released
    )
    lrc_closing = (
        payload.closing_underlying_fair_value
        + csm_result.closing
        + closing_loss_component_held
    )

    # Reconcile the movement journal by solving for the finance-expense
    # residual. This is the sum of PL + OCI (the split-by-presentation is
    # done afterwards). See docstring for why this approach beats a
    # first-principles finance-expense derivation.
    non_finance_flows = (
        payload.new_business_written
        + payload.premiums_received
        - insurance_revenue
        - payload.insurance_acquisition_cf
        + loss_component_recognized_this_period
        - payload.loss_component_released
    )
    finance_total = lrc_closing - payload.opening_lrc - non_finance_flows

    lrc_pl, lrc_oci = _split_finance_by_presentation(
        finance_total=finance_total,
        payload=payload,
    )

    lrc = LrcMovement(
        opening=payload.opening_lrc,
        new_business=payload.new_business_written,
        premiums_received=payload.premiums_received,
        insurance_revenue=insurance_revenue,
        insurance_acquisition_cf=payload.insurance_acquisition_cf,
        loss_component_recognized=loss_component_recognized_this_period,
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
    if csm_result.went_negative:
        warnings.append(
            "VFA cohort went onerous — after-experience CSM < 0; "
            f"loss component recognised={csm_result.loss_component_recognised}"
        )

    return ChunkResult(
        job_id=payload.job_id,
        tenant_id=payload.tenant_id,
        portfolio_id=payload.portfolio_id,
        cohort_id=payload.cohort_id,
        currency=payload.currency,
        model="VFA",
        lrc_movement=lrc,
        lic_movement=lic,
        discounting_applied=True,
        discounting_skip_reason=None,
        applied_rule_name=payload.applied_rule_name,
        warnings=warnings,
    )


def compute_from_dict(payload: dict) -> ChunkResult:
    """Runner convenience — accepts the raw ``ifrs17_json`` slot."""
    return compute(VfaChunkInput(**payload))


def _first_tenor_rate(payload: VfaChunkInput) -> Decimal:
    """Shortest-tenor locked-in rate for LRC/LIC finance-expense unwind.

    Returns zero on an empty curve — CSM accretion inside
    :func:`csm.roll_forward` raises on the empty case (accretion is
    invariant), but the LIC unwind is independently safe to zero. Mirrors
    the GMM convention (``_first_tenor_rate`` in :mod:`app.ifrs17.gmm`).
    """
    if not payload.locked_in_curve:
        return Decimal(0)
    shortest = min(payload.locked_in_curve, key=lambda p: p.tenor_months)
    return shortest.spot_rate


def _lic_finance_expense_split(payload: VfaChunkInput) -> tuple[Decimal, Decimal]:
    """LIC interest-unwind split — identical to PAA/GMM (17.88).

    LIC is not affected by the underlying items — it holds incurred claims
    only. The same locked-vs-current split for OCI option applies as with
    the other measurement models.
    """
    locked_rate = _first_tenor_rate(payload)
    if payload.finance_expense_presentation == "PL_ONLY":
        return payload.opening_lic * locked_rate, Decimal(0)
    pl = payload.opening_lic * locked_rate
    oci = payload.opening_lic * (payload.current_discount_rate - locked_rate)
    return pl, oci


def _split_finance_by_presentation(
    *,
    finance_total: Decimal,
    payload: VfaChunkInput,
) -> tuple[Decimal, Decimal]:
    """Split the LRC finance-expense residual into P&L + OCI.

    * ``PL_ONLY`` — full residual to P&L, zero OCI (17.88 default).
    * ``OCI_OPTION`` — locked-in interest unwind on opening LRC goes to
      P&L; the rest (fair-value change + entity's share difference at
      current vs locked rate) goes to OCI per 17.87A. Approximation:
      P&L = locked_rate × opening_lrc, OCI = finance_total − P&L.
    """
    if payload.finance_expense_presentation == "PL_ONLY":
        return finance_total, Decimal(0)
    locked_rate = _first_tenor_rate(payload)
    interest_unwind = payload.opening_lrc * locked_rate
    return interest_unwind, finance_total - interest_unwind


__all__ = [
    "compute",
    "compute_from_dict",
    "MODEL_VERSION",
]
