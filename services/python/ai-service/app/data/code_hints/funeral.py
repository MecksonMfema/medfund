"""FUNERAL code suggester — service-tier codes keyed by benefit_tier."""
from __future__ import annotations

from typing import Any

from app.data.code_hints import CodeSuggestion, register
from app.schemas.insurance_line import InsuranceLine


_HINTS: dict[str, list[CodeSuggestion]] = {
    "ECONOMY": [
        CodeSuggestion("FUN-ECO-CASKET", "Economy casket", "", 0.80),
        CodeSuggestion("FUN-ECO-HEARSE", "Hearse and single transport", "", 0.75),
        CodeSuggestion("FUN-ECO-BURIAL", "Standard burial service", "", 0.70),
    ],
    "STANDARD": [
        CodeSuggestion("FUN-STD-CASKET", "Standard casket", "", 0.85),
        CodeSuggestion("FUN-STD-HEARSE", "Hearse and family transport", "", 0.75),
        CodeSuggestion("FUN-STD-MORT", "Mortuary preparation", "", 0.70),
        CodeSuggestion("FUN-STD-CATER", "Catering allowance", "", 0.60),
    ],
    "PREMIUM": [
        CodeSuggestion("FUN-PRM-CASKET", "Premium casket", "", 0.85),
        CodeSuggestion("FUN-PRM-HEARSE", "Premium hearse convoy", "", 0.75),
        CodeSuggestion("FUN-PRM-VENUE", "Venue hire allowance", "", 0.65),
        CodeSuggestion("FUN-PRM-CATER", "Premium catering", "", 0.65),
        CodeSuggestion("FUN-PRM-FLORAL", "Floral tribute allowance", "", 0.55),
    ],
    "EXTENDED_FAMILY": [
        CodeSuggestion("FUN-EXT-DEP-CASKET",
                       "Casket for extended family dependant", "", 0.80),
        CodeSuggestion("FUN-EXT-DEP-BURIAL",
                       "Burial service for extended family dependant", "", 0.70),
    ],
}


@register(InsuranceLine.FUNERAL)
def suggest(context: dict[str, Any]) -> list[CodeSuggestion]:
    tier = context.get("benefit_tier")
    if not isinstance(tier, str):
        return []
    hints = _HINTS.get(tier.upper(), [])
    return sorted(hints, key=lambda s: s.confidence, reverse=True)
