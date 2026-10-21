"""LIFE code suggester — benefit-type codes keyed by benefit_type + cause."""
from __future__ import annotations

from typing import Any

from app.data.code_hints import CodeSuggestion, register
from app.schemas.insurance_line import InsuranceLine

_HINTS: dict[str, list[CodeSuggestion]] = {
    "NATURAL": [
        CodeSuggestion("LIFE-NAT-STD", "Natural cause — standard death benefit",
                       "Standard payout for illness-related death", 0.85),
        CodeSuggestion("LIFE-FUN-CONTRIB", "Funeral contribution rider", "", 0.55),
    ],
    "ACCIDENTAL": [
        CodeSuggestion("LIFE-ACC-STD", "Accidental death benefit", "", 0.85),
        CodeSuggestion("LIFE-ACC-DBL", "Accidental death double indemnity",
                       "Where rider is present", 0.60),
    ],
    "TERMINAL_ILLNESS": [
        CodeSuggestion("LIFE-TI-ADV", "Terminal-illness advance payment",
                       "Accelerated benefit for terminal diagnosis", 0.85),
    ],
    "PERMANENT_DISABILITY": [
        CodeSuggestion("LIFE-PTD-LUMP", "Permanent total disability lump sum", "", 0.80),
        CodeSuggestion("LIFE-PTD-WAIV", "Premium waiver on total disability", "", 0.70),
    ],
    "CRITICAL_ILLNESS": [
        CodeSuggestion("LIFE-CI-STD", "Critical-illness lump sum", "", 0.80),
    ],
}


_CAUSE_HINTS: dict[str, list[CodeSuggestion]] = {
    "myocardial_infarction": [
        CodeSuggestion("LIFE-CI-STD", "Critical-illness lump sum — heart attack",
                       "Common critical-illness trigger", 0.80),
    ],
    "stroke": [
        CodeSuggestion("LIFE-CI-STD", "Critical-illness lump sum — stroke", "", 0.80),
    ],
    "cancer": [
        CodeSuggestion("LIFE-CI-STD", "Critical-illness lump sum — cancer", "", 0.80),
    ],
    "motor_vehicle_accident": [
        CodeSuggestion("LIFE-ACC-STD", "Accidental death benefit", "", 0.85),
    ],
}


@register(InsuranceLine.LIFE)
def suggest(context: dict[str, Any]) -> list[CodeSuggestion]:
    benefit = context.get("benefit_type")
    cause = context.get("cause")
    seen: dict[str, CodeSuggestion] = {}
    if isinstance(benefit, str):
        for hint in _HINTS.get(benefit.upper(), []):
            seen[hint.code] = hint
    if isinstance(cause, str):
        for hint in _CAUSE_HINTS.get(cause.lower(), []):
            prev = seen.get(hint.code)
            if prev is None or hint.confidence > prev.confidence:
                seen[hint.code] = hint
    return sorted(seen.values(), key=lambda s: s.confidence, reverse=True)
