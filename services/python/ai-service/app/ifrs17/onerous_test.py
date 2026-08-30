"""Onerous contract test per IFRS 17.16-19 — Phase 15 §15.

IFRS 17.16 requires that a group of contracts is onerous at initial
recognition when the fulfilment cash flows plus previously recognised
acquisition cash flows exceed the premiums received. IFRS 17.19 requires
subsequent reassessment: reclassify to onerous when fulfilment cash flows
exceed the carrying amount of the CSM, and — mirror image — reclassify
out of onerous when the fulfilment cash flows fall back below that
threshold.

This module implements the reassessment. The Java §17 shaping service
supplies pre-aggregated ``projected_fcf`` + ``premium_received`` +
``remaining_csm`` per (portfolio, cohort, currency); the Python compute
paths (PAA / GMM / VFA) call :func:`test_onerous` after their
per-chunk compute lands and post an ``auto-transition`` call back to
user-service when the state flips.

The transition_reason strings pin the four possible outcomes so the
audit log distinguishes ``AUTO_TEST_FAILED`` (fresh onerous transition)
from ``AUTO_TEST_RECOVERED`` (loss component reversal) from
``NO_CHANGE`` (steady state — no HTTP callback fires). A caller
inspecting only ``is_now_onerous`` would lose that context and could
never tell why the row landed.

Loss component semantics
────────────────────────

The failed-test branch returns ``loss_component_amount`` = ``projected_fcf
- (premium_received + remaining_csm)`` — the positive gap that becomes
the INITIAL_RECOGNITION amount posted into
``cohort_loss_component_history``. The recovered branch returns ``None``
for the amount: the reclassification-to-non-onerous movement in §5's
service catalog is intentionally amount-agnostic (the matview drops the
balance to zero on the reclassification, no per-movement amount is
required). Steady-state ``NO_CHANGE`` returns ``None`` for the same
reason — no ledger movement fires.
"""

from __future__ import annotations

from decimal import Decimal
from typing import NamedTuple, Optional

# Public transition reason strings — the auto-transition endpoint audit
# log discriminator. Kept as constants so callers don't string-fumble.
REASON_FAILED = "AUTO_TEST_FAILED"
REASON_RECOVERED = "AUTO_TEST_RECOVERED"
REASON_NO_CHANGE = "NO_CHANGE"

# Cohort type discriminator — matches the CHECK constraint on
# ``ifrs17_cohort.cohort_type`` (ONEROUS | NON_ONEROUS | UNCERTAIN).
COHORT_TYPE_ONEROUS = "ONEROUS"


class OnerousTestResult(NamedTuple):
    """Return shape for :func:`test_onerous`.

    ``is_now_onerous``: whether the *result* state is onerous (not
    whether a transition fired).
    ``transition_reason``: one of REASON_FAILED / REASON_RECOVERED /
    REASON_NO_CHANGE — the audit log carries this verbatim.
    ``loss_component_amount``: positive Decimal on FAILED; ``None`` on
    RECOVERED + NO_CHANGE (see module docstring).
    """

    is_now_onerous: bool
    transition_reason: str
    loss_component_amount: Optional[Decimal]


def test_onerous(
    projected_fcf: Decimal,
    premium_received: Decimal,
    remaining_csm: Decimal,
    current_cohort_type: str,
) -> OnerousTestResult:
    """Reassess a group of contracts against IFRS 17.19.

    Per 17.19: reassess whether the group is onerous by determining if
    the fulfilment cash flows exceed the sum of premium received and the
    carrying amount of CSM.

    Parameters
    ──────────
    projected_fcf:
        Present value of fulfilment cash flows for the remaining
        coverage of the group (as at the reporting date, computed by
        GMM / VFA projection).
    premium_received:
        Cumulative premium received net of acquisition cash flows.
    remaining_csm:
        Post-accretion, pre-release CSM carrying amount as at the
        reporting date (from :mod:`~app.ifrs17.csm` roll-forward).
    current_cohort_type:
        Current value of ``ifrs17_cohort.cohort_type`` — ``ONEROUS`` or
        the industry default ``NON_ONEROUS`` / ``UNCERTAIN``. Anything
        other than ``ONEROUS`` is treated as non-onerous for the
        purpose of transition direction.

    Returns
    ───────
    :class:`OnerousTestResult` — the caller uses ``transition_reason``
    to decide whether to POST to
    ``/underwriting/cohorts/{id}/status-history/auto-transition``.
    """
    fcf_exceeds_available = projected_fcf > (premium_received + remaining_csm)
    was_onerous = current_cohort_type == COHORT_TYPE_ONEROUS

    if fcf_exceeds_available and not was_onerous:
        # Fresh failure — post INITIAL_RECOGNITION for the gap.
        loss_component = projected_fcf - (premium_received + remaining_csm)
        return OnerousTestResult(
            is_now_onerous=True,
            transition_reason=REASON_FAILED,
            loss_component_amount=loss_component,
        )

    if not fcf_exceeds_available and was_onerous:
        # Recovery — reclassify to non-onerous. No amount needed; the
        # matview handles the balance drop via the movement-type sign.
        return OnerousTestResult(
            is_now_onerous=False,
            transition_reason=REASON_RECOVERED,
            loss_component_amount=None,
        )

    # Steady state — either still onerous or still non-onerous; no HTTP
    # callback fires. The caller inspects ``transition_reason`` to skip.
    return OnerousTestResult(
        is_now_onerous=was_onerous,
        transition_reason=REASON_NO_CHANGE,
        loss_component_amount=None,
    )


# The ``test_`` prefix trips pytest's default collection when this
# module (or the function itself) is imported into a tests file. Pin
# ``__test__ = False`` so pytest does not treat the compute function
# as a test — the plan spec fixes the name.
test_onerous.__test__ = False


__all__ = [
    "OnerousTestResult",
    "test_onerous",
    "REASON_FAILED",
    "REASON_RECOVERED",
    "REASON_NO_CHANGE",
    "COHORT_TYPE_ONEROUS",
]
