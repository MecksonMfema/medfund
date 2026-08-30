"""Coverage-unit computation for CSM release — Phase 15 §13.

The ``IFRS17_MODEL`` rule (Phase 15 §9) writes a ``coverage_unit_pattern``
onto the ``IfrsPortfolioFact``; the shaping service (§17) copies that
pattern into the chunk payload; §13 uses it here to compute the units of
coverage delivered in the reporting period versus the units remaining
before that release. The ratio between those two figures drives the CSM
release fraction in :mod:`app.ifrs17.csm`.

Per IFRS 17.B119, the coverage-unit pattern is a portfolio-level
"reflection of the quantity of the benefits provided under a contract"
— time-based for most non-life, sum-insured × time for level term life,
sum-at-risk × time for decreasing term life / unit-linked, and expected
claim frequency × time for indemnity health.

Registry vs switch statement
────────────────────────────
The four patterns are registered by decorator so adding a fifth (per
tenant demand — e.g. a jurisdiction-specific pattern for Zimbabwean funeral
lines) is a single ``@register`` decorator without touching a dispatcher
switch. New patterns must also land in the ``IFRS17_MODEL`` rule template
options (:file:`services/java/rules-engine/.../Ifrs17ModelTemplates.java`)
and the ``coverage_unit_pattern`` allow-list — the checklist for adding
one lives under ``reference_new_rule_category_checklist``.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Callable

from pydantic import BaseModel, Field


class PolicyInScope(BaseModel):
    """One in-force policy contributing coverage units this period.

    All ``months_in_period`` are the policy's active months within the
    reporting window — the shaping service pro-rates a mid-period issue
    or termination down to whole months (per :ref:`feedback_effective_date_snap`).
    """

    policy_id: str
    months_in_period: int = Field(..., ge=0)
    sum_insured: Decimal = Decimal(0)
    reserve_balance: Decimal = Decimal(0)
    expected_claim_frequency: Decimal = Decimal(0)


class CoverageUnitInput(BaseModel):
    """Per portfolio × cohort × currency payload for one period's units.

    ``periods_in_scope`` is used only by ``TIME``; the other patterns
    walk ``policies_in_scope`` because a mid-year cancellation must reduce
    the exposure denominator without a period going missing.
    """

    periods_in_scope: int = Field(0, ge=0)
    policies_in_scope: list[PolicyInScope] = Field(default_factory=list)


CoverageUnitHandler = Callable[[CoverageUnitInput], Decimal]

_PATTERN_HANDLERS: dict[str, CoverageUnitHandler] = {}


def register(pattern: str) -> Callable[[CoverageUnitHandler], CoverageUnitHandler]:
    """Register a coverage-unit handler for ``pattern``.

    Handlers must return a non-negative Decimal — a zero result is a
    legitimate no-exposure period, not an error, and lets §13 release
    zero CSM cleanly rather than emitting a divide-by-zero.
    """

    def decorate(fn: CoverageUnitHandler) -> CoverageUnitHandler:
        _PATTERN_HANDLERS[pattern] = fn
        return fn

    return decorate


@register("TIME")
def _time(payload: CoverageUnitInput) -> Decimal:
    return Decimal(payload.periods_in_scope)


@register("SUM_INSURED_TIME")
def _sum_insured_time(payload: CoverageUnitInput) -> Decimal:
    return sum(
        (p.sum_insured * Decimal(p.months_in_period) for p in payload.policies_in_scope),
        Decimal(0),
    )


@register("SUM_AT_RISK_TIME")
def _sum_at_risk_time(payload: CoverageUnitInput) -> Decimal:
    total = Decimal(0)
    for p in payload.policies_in_scope:
        # Sum-at-risk floors at zero — a fully-reserved policy contributes
        # nothing this period (per IFRS 17.B119(a) worked example).
        sar = p.sum_insured - p.reserve_balance
        if sar < 0:
            sar = Decimal(0)
        total += sar * Decimal(p.months_in_period)
    return total


@register("CLAIM_FREQUENCY_TIME")
def _claim_frequency_time(payload: CoverageUnitInput) -> Decimal:
    return sum(
        (
            p.expected_claim_frequency * Decimal(p.months_in_period)
            for p in payload.policies_in_scope
        ),
        Decimal(0),
    )


def compute(pattern: str, payload: CoverageUnitInput) -> Decimal:
    """Dispatch to the registered handler for ``pattern``.

    Raises ``ValueError`` when the pattern isn't registered. The
    ``IFRS17_MODEL`` rule template's ``coverage_unit_pattern`` SELECT
    input constrains authored rules to the known set, but a hand-crafted
    ``ifrs17_json`` payload from a dev harness could still slip a typo
    through — this fails loudly rather than defaulting silently.
    """
    handler = _PATTERN_HANDLERS.get(pattern)
    if handler is None:
        raise ValueError(
            f"Unknown coverage_unit_pattern: {pattern!r}; registered patterns are "
            f"{sorted(_PATTERN_HANDLERS)}"
        )
    return handler(payload)


def registered_patterns() -> list[str]:
    """Return the sorted list of registered patterns — used by tests + docs."""
    return sorted(_PATTERN_HANDLERS)


__all__ = [
    "CoverageUnitInput",
    "PolicyInScope",
    "compute",
    "register",
    "registered_patterns",
]
