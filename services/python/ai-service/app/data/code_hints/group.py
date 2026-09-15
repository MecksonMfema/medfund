"""GROUP code suggester — dispatches to the underlying line's module."""
from __future__ import annotations

from typing import Any

from app.data.code_hints import CodeSuggestion, register, suggest_for_line
from app.schemas.insurance_line import InsuranceLine


@register(InsuranceLine.GROUP)
def suggest(context: dict[str, Any]) -> list[CodeSuggestion]:
    underlying = context.get("underlying_line")
    if not isinstance(underlying, str):
        return []
    try:
        line = InsuranceLine(underlying)
    except ValueError:
        return []
    if line == InsuranceLine.GROUP:
        return []
    return suggest_for_line(line, context)
