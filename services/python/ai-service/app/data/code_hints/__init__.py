"""Per-line code-suggestion registry.

Each `<line>.py` module registers a suggester with `@register(line)` and
exports a `_HINTS` dict of seed data. The registry dispatches
`suggest_for_line(line, context)` to the correct module.

The registry is populated at import time — importing this package
triggers `_load_all()` which pulls every per-line module.
"""
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from app.schemas.insurance_line import InsuranceLine


@dataclass(frozen=True)
class CodeSuggestion:
    code: str
    label: str
    rationale: str
    confidence: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "label": self.label,
            "rationale": self.rationale,
            "confidence": self.confidence,
        }


SuggesterFn = Callable[[dict[str, Any]], list[CodeSuggestion]]


_SUGGESTERS: dict[InsuranceLine, SuggesterFn] = {}


def register(line: InsuranceLine):
    def _wrap(fn: SuggesterFn) -> SuggesterFn:
        _SUGGESTERS[line] = fn
        return fn

    return _wrap


def suggest_for_line(
    line: InsuranceLine, context: dict[str, Any]
) -> list[CodeSuggestion]:
    fn = _SUGGESTERS.get(line)
    if fn is None:
        return []
    return fn(context or {})


def registered_lines() -> set[InsuranceLine]:
    return set(_SUGGESTERS.keys())


def _load_all() -> None:
    # Import each module for its @register side-effect.
    from app.data.code_hints import (  # noqa: F401
        disability,
        funeral,
        group,
        health,
        life,
        property,
        travel,
        vehicle,
    )


_load_all()
