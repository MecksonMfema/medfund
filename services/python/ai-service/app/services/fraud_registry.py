"""Runtime fraud-model registry per InsuranceLine (Phase 3 / G6 / G7).

Contract:

- ``resolve_fraud_model(line)`` returns the currently-active FraudMLModel
  for that line. Process-lifetime cache, invalidated by the poll loop
  when the MinIO manifest changes.
- ``refresh_from_manifest()`` reads ``manifest.json`` from the artifact
  bucket and eagerly re-loads changed entries. Called every
  ``settings.model_poll_interval_seconds`` by the background task
  ``poll_manifest_forever``.
- ``schema_mismatch_status(model_type, line)`` returns a short string
  when the artifact on disk carries a ``canonical_features_schema`` that
  doesn't match the runtime extractor's — the Phase 5 admin surface
  uses this to render the red ``SCHEMA_MISMATCH`` badge (G7).

Failure posture: every MinIO / joblib error falls back to the canonical
synthetic model. The service never hard-fails on artifact reads — a
missing / corrupt / mismatched artifact is a degraded operating state,
not a boot-time crash.
"""
from __future__ import annotations

import asyncio
import io
import logging
from dataclasses import dataclass
from typing import Optional

import joblib

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine
from app.services.ml_models import FraudMLModel

from scripts._registry import (
    download_artifact,
    load_manifest,
    manifest_key,
)


logger = logging.getLogger(__name__)


_FALLBACK_SENTINEL = "__fallback__"


@dataclass
class _LoadedEntry:
    model: FraudMLModel
    version: str  # concrete version ("v2") or _FALLBACK_SENTINEL


_CACHE: dict[InsuranceLine, _LoadedEntry] = {}
_FALLBACK: Optional[FraudMLModel] = None
_SCHEMA_MISMATCHES: dict[tuple[str, InsuranceLine], str] = {}

_poll_task: Optional[asyncio.Task] = None


def _get_fallback() -> FraudMLModel:
    global _FALLBACK
    if _FALLBACK is None:
        _FALLBACK = FraudMLModel(seed=42)
        logger.info("Fraud fallback: canonical synthetic model ready")
    return _FALLBACK


def _load_from_bucket(line: InsuranceLine, version: str) -> FraudMLModel:
    """Download the artifact, validate schema, and hydrate a FraudMLModel.

    Raises on any download / schema error — caller is responsible for
    falling back to the canonical model.
    """
    payload = download_artifact("fraud", line, version)
    artifact = joblib.load(io.BytesIO(payload))
    schema = artifact.get("canonical_features_schema")
    expected = settings.canonical_features_schema_version
    if schema != expected:
        detail = f"artifact_schema={schema!r} runtime_schema={expected!r}"
        _SCHEMA_MISMATCHES[("fraud", line)] = detail
        raise ValueError(f"SCHEMA_MISMATCH — {detail}")
    _SCHEMA_MISMATCHES.pop(("fraud", line), None)
    return FraudMLModel(line=line, artifact=artifact)


def _put(line: InsuranceLine, model: FraudMLModel, version: str) -> None:
    _CACHE[line] = _LoadedEntry(model=model, version=version)


def _fallback_for(line: InsuranceLine) -> FraudMLModel:
    fallback = _get_fallback()
    _put(line, fallback, _FALLBACK_SENTINEL)
    return fallback


def resolve_fraud_model(line: InsuranceLine) -> FraudMLModel:
    """Return the FraudMLModel currently serving ``line``.

    First call for a line pays the joblib.load cost; the poll loop
    keeps the cache in sync with the MinIO manifest, so a promotion
    surfaces on subsequent calls within ``model_poll_interval_seconds``.
    """
    cached = _CACHE.get(line)
    if cached is not None:
        return cached.model

    # GROUP dispatches to the underlying line at the extractor level
    # (see feature_extractors.GroupExtractor); the model itself stays
    # the canonical fallback so the resolver stays line-agnostic.
    if line == InsuranceLine.GROUP:
        return _fallback_for(line)

    if not settings.model_artifacts_enabled:
        return _fallback_for(line)

    try:
        manifest = load_manifest()
    except Exception as e:
        # MinIO unavailable / credentials missing → treat as no artifact.
        logger.warning("Fraud registry: manifest read failed (%s) — falling back", e)
        return _fallback_for(line)

    wanted = manifest.get(manifest_key("fraud", line))
    if not wanted:
        return _fallback_for(line)

    try:
        model = _load_from_bucket(line, wanted)
    except Exception as e:
        logger.warning(
            "Fraud registry: load fraud/%s/%s failed (%s) — falling back",
            line.value, wanted, e,
        )
        return _fallback_for(line)

    logger.info(
        "Fraud registry: loaded %s (%s) for line %s",
        model.model_version, wanted, line.value,
    )
    _put(line, model, wanted)
    return model


def refresh_from_manifest() -> None:
    """Sync ``_CACHE`` with the current MinIO manifest.

    For each line, if the manifest names a version that differs from
    what's cached, invalidate the cache entry and eagerly re-load. If
    the manifest removes a line's entry entirely, revert the cache to
    the canonical fallback.

    All errors are logged and swallowed — a bad poll must not crash
    the poll task.
    """
    if not settings.model_artifacts_enabled:
        return
    try:
        manifest = load_manifest()
    except Exception as e:
        logger.warning("Fraud registry poll: manifest read failed (%s)", e)
        return

    for line in InsuranceLine:
        if line == InsuranceLine.GROUP:
            continue
        wanted = manifest.get(manifest_key("fraud", line)) or _FALLBACK_SENTINEL
        cached = _CACHE.get(line)
        current = cached.version if cached else None
        if current == wanted:
            continue

        logger.info(
            "Fraud registry poll: line=%s cached=%s manifest=%s — reloading",
            line.value, current, wanted,
        )
        _CACHE.pop(line, None)
        try:
            if wanted == _FALLBACK_SENTINEL:
                _fallback_for(line)
            else:
                model = _load_from_bucket(line, wanted)
                _put(line, model, wanted)
        except Exception as e:
            logger.warning(
                "Fraud registry poll: reload fraud/%s/%s failed (%s) — fallback",
                line.value, wanted, e,
            )
            _fallback_for(line)


async def poll_manifest_forever() -> None:
    """Background poll loop — cancel by cancelling the wrapping task."""
    interval = max(1, int(settings.model_poll_interval_seconds))
    logger.info("Fraud registry poll loop starting (every %ss)", interval)
    while True:
        try:
            await asyncio.sleep(interval)
            refresh_from_manifest()
        except asyncio.CancelledError:
            logger.info("Fraud registry poll loop cancelled")
            raise
        except Exception as e:  # noqa: BLE001 — never let the loop die
            logger.warning("Fraud registry poll loop error (continuing): %s", e)


def start_polling() -> Optional[asyncio.Task]:
    """Spawn the background poll task on the current event loop."""
    global _poll_task
    if _poll_task is not None and not _poll_task.done():
        return _poll_task
    if not settings.model_artifacts_enabled:
        return None
    _poll_task = asyncio.create_task(poll_manifest_forever())
    return _poll_task


async def stop_polling() -> None:
    """Cancel + await the poll task, if any."""
    global _poll_task
    if _poll_task is None:
        return
    _poll_task.cancel()
    try:
        await _poll_task
    except asyncio.CancelledError:
        pass
    _poll_task = None


def schema_mismatch_status(
    model_type: str, line: InsuranceLine,
) -> Optional[str]:
    """Return a short reason string if the last load for this (type, line)
    hit a SCHEMA_MISMATCH; otherwise None. Consumed by Phase 5."""
    return _SCHEMA_MISMATCHES.get((model_type, line))


def cached_version(line: InsuranceLine) -> Optional[str]:
    """The concrete version string currently serving ``line`` (or None
    when the fallback is active / the line hasn't been resolved yet).
    Used by Phase 5 admin surface."""
    entry = _CACHE.get(line)
    if entry is None or entry.version == _FALLBACK_SENTINEL:
        return None
    return entry.version


def reset_for_tests() -> None:
    """Clear cache + fallback + mismatch state. Test-only hook."""
    global _FALLBACK, _poll_task
    _CACHE.clear()
    _SCHEMA_MISMATCHES.clear()
    _FALLBACK = None
    _poll_task = None
