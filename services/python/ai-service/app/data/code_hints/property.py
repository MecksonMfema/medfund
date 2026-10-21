"""PROPERTY code suggester — damage-category codes keyed by peril + damage_type."""
from __future__ import annotations

from typing import Any

from app.data.code_hints import CodeSuggestion, register
from app.schemas.insurance_line import InsuranceLine

_HINTS: dict[str, list[CodeSuggestion]] = {
    "fire": [
        CodeSuggestion("STRUCT-FIRE", "Structural fire repair", "", 0.80),
        CodeSuggestion("CONTENT-FIRE", "Contents replacement — fire", "", 0.75),
        CodeSuggestion("SMOKE-CLN", "Smoke damage cleaning", "", 0.65),
        CodeSuggestion("ELECTRICAL-REW", "Electrical rewiring — fire", "", 0.60),
    ],
    "flood": [
        CodeSuggestion("FLOOD-STR", "Flood structural repair", "", 0.80),
        CodeSuggestion("FLOOD-CONT", "Flood contents replacement", "", 0.75),
        CodeSuggestion("FLOOR-REPL", "Floor replacement (waterlogged)", "", 0.60),
        CodeSuggestion("DEHUMID-01", "Structural dehumidification", "", 0.55),
    ],
    "burglary": [
        CodeSuggestion("BURG-CONT", "Contents replacement — theft", "", 0.80),
        CodeSuggestion("BURG-REPAIR", "Forced-entry repair", "", 0.65),
        CodeSuggestion("LOCK-REPL", "Lock replacement", "", 0.70),
        CodeSuggestion("WINDOW-REPL", "Window pane replacement", "", 0.55),
    ],
    "storm": [
        CodeSuggestion("STORM-ROOF", "Roof storm repair", "", 0.75),
        CodeSuggestion("STORM-WIND", "Wind damage — cladding / gutter", "", 0.65),
        CodeSuggestion("TREE-REM", "Fallen tree removal", "", 0.55),
    ],
    "lightning": [
        CodeSuggestion("ELEC-REPL", "Electrical equipment replacement", "", 0.75),
        CodeSuggestion("ROOF-LIGHT", "Roof damage from strike", "", 0.60),
    ],
    "subsidence": [
        CodeSuggestion("SUB-FOUND", "Foundation subsidence repair", "", 0.80),
        CodeSuggestion("SUB-WALL", "Wall crack rectification", "", 0.65),
    ],
    "malicious-damage": [
        CodeSuggestion("VAN-PAINT", "Repaint (vandalism)", "", 0.65),
        CodeSuggestion("WINDOW-REPL", "Window pane replacement", "", 0.60),
    ],
    "impact": [
        CodeSuggestion("IMPACT-WALL", "Wall repair — vehicle impact", "", 0.70),
        CodeSuggestion("FENCE-REPL", "Fence replacement", "", 0.60),
    ],
    "escape-of-water": [
        CodeSuggestion("PLUMB-REP", "Plumbing repair", "", 0.75),
        CodeSuggestion("WATER-DRY", "Water damage drying and clean", "", 0.65),
        CodeSuggestion("PAINT-REP", "Interior repainting after leak", "", 0.55),
    ],
}


@register(InsuranceLine.PROPERTY)
def suggest(context: dict[str, Any]) -> list[CodeSuggestion]:
    peril = context.get("peril")
    damage = context.get("damage_type")
    keys: list[str] = []
    for candidate in (peril, damage):
        if isinstance(candidate, str):
            keys.append(candidate.lower())
    seen: dict[str, CodeSuggestion] = {}
    for key in keys:
        for hint in _HINTS.get(key, []):
            prev = seen.get(hint.code)
            if prev is None or hint.confidence > prev.confidence:
                seen[hint.code] = hint
    return sorted(seen.values(), key=lambda s: s.confidence, reverse=True)
