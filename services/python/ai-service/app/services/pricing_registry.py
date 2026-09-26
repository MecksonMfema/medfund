"""Runtime pricing-model registry per InsuranceLine (Phase 4 / G3 / G6).

G3 defers pricing training to Tranche 2. Today the resolver ALWAYS
returns a ``RulePricingModel`` — the rule-based scorer wrapped in an
adapter that speaks the same ``.score(req) -> ScoreResponse`` contract
a future trained model will speak. Layout in MinIO is the same as
fraud (``pricing-{line}-v{n}.joblib`` + manifest.json entry
``pricing/{LINE}``) so that when Tranche 2's ``train_pricing.py``
starts writing artifacts, the resolver picks them up on the next poll
without a code change.

Structure mirrors ``fraud_registry`` deliberately so a future
refactor can collapse the two behind a shared abstraction.
"""
from __future__ import annotations

import asyncio
import io
import logging
from dataclasses import dataclass
from typing import Protocol

import joblib

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine
from scripts._registry import (
    download_artifact,
    load_manifest,
    manifest_key,
)

logger = logging.getLogger(__name__)


_FALLBACK_SENTINEL = "__fallback__"
_RULE_MODEL_VERSION = "rule-v1"


class PricingModel(Protocol):
    """Uniform contract for rule-based and future trained pricing models."""

    model_version: str

    def score(self, req) -> object:  # ScoreResponse — avoid circular import
        ...


class RulePricingModel:
    """Adapter over the rule-based ``score()`` function.

    Import of the rule function is lazy — ``pricing_registry`` boots
    before ``app.api.pricing`` in some paths (e.g. tests), and taking
    the import at module load creates a cycle.
    """

    model_version = _RULE_MODEL_VERSION

    def score(self, req):
        from app.api.pricing import score as _rule_score
        return _rule_score(req)


class MinioTrainedPricingModel:
    """Placeholder for Tranche 2. Loads a joblib produced by a future
    ``train_pricing.py`` and exposes ``.score(req)`` speaking the same
    contract. No artifact writes it in Tranche 1 — this class exists
    so the resolver's load path is honest about what it would do.
    """

    def __init__(self, artifact: dict) -> None:
        self.model_version = str(artifact["model_version"])
        self._regressor = artifact["model"]
        self._scaler = artifact["scaler"]
        self._feature_names = list(artifact.get("feature_names") or [])
        self._canonical_features_schema = artifact.get("canonical_features_schema")

    def score(self, req):
        # Deliberately not implemented — Tranche 2 fills this in with
        # the real inference path once loss-ratio labels + a training
        # script exist. The resolver never lands on this path in
        # Tranche 1 because no artifact is ever promoted.
        raise NotImplementedError(
            "Trained pricing inference lands in Tranche 2 alongside "
            "the loss-ratio training corpus."
        )


@dataclass
class _LoadedEntry:
    model: PricingModel
    version: str


_CACHE: dict[InsuranceLine, _LoadedEntry] = {}
_RULE_SINGLETON: RulePricingModel | None = None
_SCHEMA_MISMATCHES: dict[tuple[str, InsuranceLine], str] = {}

_poll_task: asyncio.Task | None = None


def _rule_model() -> RulePricingModel:
    global _RULE_SINGLETON
    if _RULE_SINGLETON is None:
        _RULE_SINGLETON = RulePricingModel()
    return _RULE_SINGLETON


def _fallback_for(line: InsuranceLine) -> RulePricingModel:
    model = _rule_model()
    _CACHE[line] = _LoadedEntry(model=model, version=_FALLBACK_SENTINEL)
    return model


def _load_from_bucket(line: InsuranceLine, version: str) -> MinioTrainedPricingModel:
    payload = download_artifact("pricing", line, version)
    artifact = joblib.load(io.BytesIO(payload))
    schema = artifact.get("canonical_features_schema")
    expected = settings.canonical_features_schema_version
    if schema != expected:
        detail = f"artifact_schema={schema!r} runtime_schema={expected!r}"
        _SCHEMA_MISMATCHES[("pricing", line)] = detail
        raise ValueError(f"SCHEMA_MISMATCH — {detail}")
    _SCHEMA_MISMATCHES.pop(("pricing", line), None)
    return MinioTrainedPricingModel(artifact)


def resolve_pricing_model(line: InsuranceLine) -> PricingModel:
    """Return the pricing model currently serving ``line``. Rule adapter
    for every line in Tranche 1 (G3); the manifest-driven load path is
    plumbed so Tranche 2's trained artifacts drop in without changes."""
    cached = _CACHE.get(line)
    if cached is not None:
        return cached.model

    if not settings.model_artifacts_enabled:
        return _fallback_for(line)

    try:
        manifest = load_manifest()
    except Exception as e:
        logger.warning("Pricing registry: manifest read failed (%s) — falling back", e)
        return _fallback_for(line)

    wanted = manifest.get(manifest_key("pricing", line))
    if not wanted:
        return _fallback_for(line)

    try:
        model = _load_from_bucket(line, wanted)
    except Exception as e:
        logger.warning(
            "Pricing registry: load pricing/%s/%s failed (%s) — falling back",
            line.value, wanted, e,
        )
        return _fallback_for(line)

    logger.info(
        "Pricing registry: loaded %s (%s) for line %s",
        model.model_version, wanted, line.value,
    )
    _CACHE[line] = _LoadedEntry(model=model, version=wanted)
    return model


def refresh_from_manifest() -> None:
    if not settings.model_artifacts_enabled:
        return
    try:
        manifest = load_manifest()
    except Exception as e:
        logger.warning("Pricing registry poll: manifest read failed (%s)", e)
        return

    for line in InsuranceLine:
        wanted = manifest.get(manifest_key("pricing", line)) or _FALLBACK_SENTINEL
        cached = _CACHE.get(line)
        current = cached.version if cached else None
        if current == wanted:
            continue
        logger.info(
            "Pricing registry poll: line=%s cached=%s manifest=%s — reloading",
            line.value, current, wanted,
        )
        _CACHE.pop(line, None)
        try:
            if wanted == _FALLBACK_SENTINEL:
                _fallback_for(line)
            else:
                model = _load_from_bucket(line, wanted)
                _CACHE[line] = _LoadedEntry(model=model, version=wanted)
        except Exception as e:
            logger.warning(
                "Pricing registry poll: reload pricing/%s/%s failed (%s) — fallback",
                line.value, wanted, e,
            )
            _fallback_for(line)


async def poll_manifest_forever() -> None:
    interval = max(1, int(settings.model_poll_interval_seconds))
    logger.info("Pricing registry poll loop starting (every %ss)", interval)
    while True:
        try:
            await asyncio.sleep(interval)
            refresh_from_manifest()
        except asyncio.CancelledError:
            logger.info("Pricing registry poll loop cancelled")
            raise
        except Exception as e:  # noqa: BLE001
            logger.warning("Pricing registry poll loop error (continuing): %s", e)


def start_polling() -> asyncio.Task | None:
    global _poll_task
    if _poll_task is not None and not _poll_task.done():
        return _poll_task
    if not settings.model_artifacts_enabled:
        return None
    _poll_task = asyncio.create_task(poll_manifest_forever())
    return _poll_task


async def stop_polling() -> None:
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
) -> str | None:
    return _SCHEMA_MISMATCHES.get((model_type, line))


def cached_version(line: InsuranceLine) -> str | None:
    entry = _CACHE.get(line)
    if entry is None or entry.version == _FALLBACK_SENTINEL:
        return None
    return entry.version


def reset_for_tests() -> None:
    global _RULE_SINGLETON, _poll_task
    _CACHE.clear()
    _SCHEMA_MISMATCHES.clear()
    _RULE_SINGLETON = None
    _poll_task = None
