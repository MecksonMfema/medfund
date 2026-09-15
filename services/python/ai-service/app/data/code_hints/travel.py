"""TRAVEL code suggester — coverage-category codes."""
from __future__ import annotations

from typing import Any

from app.data.code_hints import CodeSuggestion, register
from app.schemas.insurance_line import InsuranceLine


_HINTS: dict[str, list[CodeSuggestion]] = {
    "MEDICAL": [
        CodeSuggestion("TRV-MED-EMR", "Emergency medical treatment abroad", "", 0.85),
        CodeSuggestion("TRV-MED-EVAC", "Medical evacuation / repatriation", "", 0.75),
        CodeSuggestion("TRV-MED-DENT", "Emergency dental treatment", "", 0.55),
        CodeSuggestion("TRV-MED-HOSP", "Hospital day rate abroad", "", 0.70),
    ],
    "CANCELLATION": [
        CodeSuggestion("TRV-CAN-TRP", "Trip cancellation refund", "", 0.85),
        CodeSuggestion("TRV-CAN-CURT", "Trip curtailment refund", "", 0.70),
    ],
    "DELAY": [
        CodeSuggestion("TRV-DLY-COMP", "Departure-delay compensation", "", 0.80),
        CodeSuggestion("TRV-DLY-MISS", "Missed connection compensation", "", 0.65),
    ],
    "BAGGAGE": [
        CodeSuggestion("TRV-BAG-LOST", "Lost baggage compensation", "", 0.80),
        CodeSuggestion("TRV-BAG-DLY", "Baggage-delay compensation", "", 0.65),
        CodeSuggestion("TRV-BAG-DAM", "Baggage damage compensation", "", 0.55),
    ],
    "LIABILITY": [
        CodeSuggestion("TRV-LIA-PERS", "Personal liability payout", "", 0.75),
        CodeSuggestion("TRV-LIA-LEGAL", "Legal expenses abroad", "", 0.55),
    ],
    "PERSONAL_ACCIDENT": [
        CodeSuggestion("TRV-PA-STD", "Personal accident abroad", "", 0.75),
    ],
    "RENTAL_EXCESS": [
        CodeSuggestion("TRV-RENT-EXC", "Rental-car excess waiver", "", 0.70),
    ],
}


@register(InsuranceLine.TRAVEL)
def suggest(context: dict[str, Any]) -> list[CodeSuggestion]:
    category = context.get("coverage_category")
    if not isinstance(category, str):
        return []
    hints = _HINTS.get(category.upper(), [])
    return sorted(hints, key=lambda s: s.confidence, reverse=True)
