"""VEHICLE code suggester — repair-part and labor codes keyed by damage_type.

Line-specific seed covers common motor damage: collisions, theft, hail,
fire, glass, vandalism, animal, water, mechanical, and total-loss
settlements. Falls back on `cause_of_loss` when `damage_type` is absent.
"""
from __future__ import annotations

from typing import Any

from app.data.code_hints import CodeSuggestion, register
from app.schemas.insurance_line import InsuranceLine

_HINTS: dict[str, list[CodeSuggestion]] = {
    "collision": [
        CodeSuggestion("PANEL-FR", "Front panel replacement",
                       "Common in front-end collisions", 0.75),
        CodeSuggestion("BUMPER-FR", "Front bumper replacement", "", 0.75),
        CodeSuggestion("HEADLAMP-L", "Left headlamp assembly", "", 0.55),
        CodeSuggestion("HEADLAMP-R", "Right headlamp assembly", "", 0.55),
        CodeSuggestion("GRILLE", "Front grille replacement", "", 0.50),
        CodeSuggestion("RAD-01", "Radiator replacement",
                       "Impact damage — often affects cooling system", 0.55),
        CodeSuggestion("CHASSIS-STR", "Chassis straightening — jig time", "", 0.50),
        CodeSuggestion("PAINT-FR", "Front-end respray", "", 0.65),
        CodeSuggestion("LABOR-BODY", "Body shop labor per hour", "", 0.90),
    ],
    "rear-collision": [
        CodeSuggestion("PANEL-RR", "Rear panel replacement", "", 0.75),
        CodeSuggestion("BUMPER-RR", "Rear bumper replacement", "", 0.75),
        CodeSuggestion("BOOTLID", "Boot lid replacement", "", 0.55),
        CodeSuggestion("TAILLAMP-L", "Left taillamp assembly", "", 0.55),
        CodeSuggestion("TAILLAMP-R", "Right taillamp assembly", "", 0.55),
        CodeSuggestion("PAINT-RR", "Rear-end respray", "", 0.65),
    ],
    "side-collision": [
        CodeSuggestion("DOOR-L", "Left door skin / assembly", "", 0.65),
        CodeSuggestion("DOOR-R", "Right door skin / assembly", "", 0.65),
        CodeSuggestion("MIRROR-L", "Left wing mirror", "", 0.55),
        CodeSuggestion("MIRROR-R", "Right wing mirror", "", 0.55),
        CodeSuggestion("SILL-01", "Sill panel repair", "", 0.55),
        CodeSuggestion("PAINT-SIDE", "Side respray", "", 0.65),
    ],
    "theft": [
        CodeSuggestion("TOTAL-LOSS", "Total-loss settlement",
                       "Vehicle unrecovered after 30 days", 0.90),
        CodeSuggestion("IGN-LOCK", "Ignition lock replacement",
                       "Common when vehicle recovered post-theft", 0.65),
    ],
    "hail": [
        CodeSuggestion("PDR-01", "Paintless dent removal (multi-panel)",
                       "Standard hail damage response", 0.80),
        CodeSuggestion("PAINT-ROOF", "Roof / bonnet respray after dent pull", "", 0.55),
    ],
    "fire": [
        CodeSuggestion("TOTAL-LOSS", "Total-loss settlement",
                       "Fire damage typically writes off the vehicle", 0.90),
        CodeSuggestion("INTERIOR-STR", "Interior trim strip and clean", "", 0.50),
    ],
    "glass": [
        CodeSuggestion("WSD-01", "Windscreen replacement", "", 0.90),
        CodeSuggestion("SIDE-GLASS", "Side window glass replacement", "", 0.75),
        CodeSuggestion("REAR-GLASS", "Rear window glass replacement", "", 0.70),
    ],
    "vandalism": [
        CodeSuggestion("PAINT-VAN", "Vandalism respray", "", 0.65),
        CodeSuggestion("PDR-02", "Localised dent removal", "", 0.55),
        CodeSuggestion("BODY-KEY-01", "Key-scratch repair labor", "", 0.55),
    ],
    "animal": [
        CodeSuggestion("PANEL-FR", "Front panel repair", "", 0.65),
        CodeSuggestion("GRILLE", "Front grille replacement", "", 0.60),
        CodeSuggestion("HEADLAMP-L", "Headlamp assembly", "", 0.50),
    ],
    "flood": [
        CodeSuggestion("ELEC-HARN", "Electrical harness replacement",
                       "Standard after water ingress", 0.75),
        CodeSuggestion("INTERIOR-STR", "Interior trim strip and dry", "", 0.65),
        CodeSuggestion("TOTAL-LOSS", "Total-loss settlement",
                       "Full submersion typically writes off vehicle", 0.60),
    ],
    "mechanical": [
        CodeSuggestion("ENG-OH", "Engine overhaul labor", "", 0.60),
        CodeSuggestion("TRANS-OH", "Transmission overhaul labor", "", 0.55),
    ],
    "total-loss": [
        CodeSuggestion("TOTAL-LOSS", "Total-loss settlement", "", 0.95),
    ],
}


_CAUSE_HINTS: dict[str, list[CodeSuggestion]] = {
    "accident":  _HINTS["collision"],
    "theft":     _HINTS["theft"],
    "hail":      _HINTS["hail"],
    "fire":      _HINTS["fire"],
    "vandalism": _HINTS["vandalism"],
    "flood":     _HINTS["flood"],
}


@register(InsuranceLine.VEHICLE)
def suggest(context: dict[str, Any]) -> list[CodeSuggestion]:
    key = context.get("damage_type")
    hints: list[CodeSuggestion] = []
    if isinstance(key, str):
        hints = list(_HINTS.get(key.lower(), []))
    if not hints:
        cause = context.get("cause_of_loss")
        if isinstance(cause, str):
            hints = list(_CAUSE_HINTS.get(cause.lower(), []))
    return sorted(hints, key=lambda s: s.confidence, reverse=True)
