"""Anonymize AI feature vectors before persistence per Critical Rule #4.

The endpoint hands us a dict that came off the wire (member_id,
provider_id, claim details). This helper removes direct identifiers but
keeps everything needed to reproduce a prediction or debug drift.
"""
from __future__ import annotations

from typing import Any


# Direct identifiers stripped — they don't help model debugging, and
# their presence in a compliance query would leak PII across the audit
# boundary.
_STRIPPED_KEYS = frozenset({
    "member_id",
    "subject_id",       # asset id for VEHICLE/PROPERTY, member id otherwise
    "provider_id",
    "claim_id",
    "correlation_id",
    "user_id",
    "conversation_id",
    "member_name",
    "provider_name",
    "member_email",
    "provider_email",
    "asset_id",
    "vehicle_id",
    "property_id",
})


def anonymize_features(features: Any) -> dict[str, Any]:
    """Return a copy of features with direct identifiers removed.

    Nested dicts are recursed (member_context, claim_data, etc). Lists
    of dicts are recursed element-by-element. Non-dict input returns
    an empty dict — callers should always pass a dict.
    """
    if not isinstance(features, dict):
        return {}
    out: dict[str, Any] = {}
    for k, v in features.items():
        if k in _STRIPPED_KEYS:
            continue
        if isinstance(v, dict):
            out[k] = anonymize_features(v)
        elif isinstance(v, list):
            out[k] = [
                anonymize_features(item) if isinstance(item, dict) else item
                for item in v
            ]
        else:
            out[k] = v
    return out
