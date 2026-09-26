"""DISABILITY code suggester — benefit-period codes by benefit_type + waiting_period."""
from __future__ import annotations

from typing import Any

from app.data.code_hints import CodeSuggestion, register
from app.schemas.insurance_line import InsuranceLine

_HINTS: dict[str, list[CodeSuggestion]] = {
    "TEMPORARY": [
        CodeSuggestion("DIS-TMP-STD", "Temporary disability monthly benefit",
                       "Standard monthly income replacement", 0.85),
        CodeSuggestion("DIS-TMP-REHAB", "Rehabilitation support allowance", "", 0.55),
    ],
    "PARTIAL": [
        CodeSuggestion("DIS-PAR-STD", "Partial disability graded benefit", "", 0.80),
        CodeSuggestion("DIS-PAR-REHAB", "Vocational rehab support", "", 0.55),
    ],
    "PERMANENT": [
        CodeSuggestion("DIS-PTD-LUMP", "Permanent total disability lump sum", "", 0.85),
        CodeSuggestion("DIS-PTD-INC", "Permanent monthly income benefit", "", 0.75),
        CodeSuggestion("DIS-PTD-WAIV", "Premium waiver rider", "", 0.60),
    ],
}


_WAITING_PERIOD_HINTS: dict[int, CodeSuggestion] = {
    7:   CodeSuggestion("DIS-WAIT-7",   "7-day waiting period benefit",   "", 0.60),
    14:  CodeSuggestion("DIS-WAIT-14",  "14-day waiting period benefit",  "", 0.60),
    30:  CodeSuggestion("DIS-WAIT-30",  "30-day waiting period benefit",  "", 0.65),
    60:  CodeSuggestion("DIS-WAIT-60",  "60-day waiting period benefit",  "", 0.60),
    90:  CodeSuggestion("DIS-WAIT-90",  "90-day waiting period benefit",  "", 0.55),
    180: CodeSuggestion("DIS-WAIT-180", "180-day waiting period benefit", "", 0.50),
}


@register(InsuranceLine.DISABILITY)
def suggest(context: dict[str, Any]) -> list[CodeSuggestion]:
    benefit = context.get("benefit_type")
    waiting = context.get("waiting_period_days")
    seen: dict[str, CodeSuggestion] = {}
    if isinstance(benefit, str):
        for hint in _HINTS.get(benefit.upper(), []):
            seen[hint.code] = hint
    if isinstance(waiting, int):
        wp = _WAITING_PERIOD_HINTS.get(waiting)
        if wp is not None:
            seen[wp.code] = wp
    return sorted(seen.values(), key=lambda s: s.confidence, reverse=True)
