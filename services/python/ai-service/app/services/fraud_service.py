"""Fraud detection service using ML models.

Two entry points:

- ``check_fraud_request`` is the endpoint path: takes a line-aware
  ``FraudCheckRequest``, dispatches through the per-line extractor
  registry, resolves the trained-or-fallback model for the request's
  ``insurance_line`` via ``fraud_registry.resolve_fraud_model``, and
  returns the response payload plus the raw ``line_features`` bag for
  audit persistence.

- ``detect_fraud`` is the legacy Kafka-consumer path: takes a dict from
  a CLAIM_SUBMITTED envelope. When the envelope carries an
  ``insurance_line`` we route through the same per-line resolver;
  otherwise we fall back to the shared canonical model.
"""
from __future__ import annotations

import logging
from typing import Any

from app.models.prediction import AIPrediction
from app.schemas.fraud import FraudCheckRequest
from app.schemas.insurance_line import InsuranceLine
from app.services.feature_extractors import (
    ExtractorRegistry,
    FraudContext,
)
from app.services.fraud_registry import resolve_fraud_model
from app.services.ml_models import FraudMLModel

logger = logging.getLogger(__name__)

_extractor_registry = ExtractorRegistry()


class FraudService:
    """Fraud detection with per-line trained artifacts + canonical fallback."""

    def __init__(
        self,
        model: FraudMLModel | None = None,
        extractors: ExtractorRegistry | None = None,
    ):
        # Injected model overrides the per-line resolver (used by tests
        # to pin a specific model instance). Left None in production so
        # every request resolves the currently-active artifact.
        self._injected_model = model
        self.extractors = extractors or _extractor_registry

    def _model_for(self, line: InsuranceLine) -> FraudMLModel:
        return self._injected_model or resolve_fraud_model(line)

    # ── Endpoint path ─────────────────────────────────────────────────
    async def check_fraud_request(
        self, request: FraudCheckRequest, tenant_id: str
    ) -> dict[str, Any]:
        """Line-aware fraud scoring for the HTTP endpoint."""
        logger.info(
            "Fraud check for claim %s line=%s tenant=%s",
            request.claim_id, request.insurance_line.value, tenant_id,
        )
        extractor = self.extractors.for_line(request.insurance_line)
        ctx = FraudContext()
        canonical, normalized, rule_indicators = await extractor.extract(
            request, tenant_id, ctx,
        )

        model = self._model_for(request.insurance_line)
        ml_out = model.predict_canonical(canonical)

        indicators = list(ml_out.get("indicators", [])) + rule_indicators
        return {
            "risk_score":         ml_out["risk_score"],
            "risk_level":         ml_out["risk_level"],
            "indicators":         indicators,
            "model_version":      ml_out["model_version"],
            "insurance_line":     request.insurance_line.value,
            "line_features":      normalized,
            "canonical_features": canonical,
        }

    # ── Kafka-consumer path (legacy dict shape) ──────────────────────
    async def detect_fraud(self, claim_data: dict, tenant_id: str) -> AIPrediction:
        """Score a CLAIM_SUBMITTED envelope. Returns AIPrediction for
        Phase 19 §A fraud producer."""
        logger.info(
            "Fraud check (kafka) for claim %s tenant %s",
            claim_data.get("claim_id"), tenant_id,
        )
        canonical = [
            float(claim_data.get("claimed_amount", 0) or 0),
            float(claim_data.get("days_since_enrollment", 365) or 365),
            float(claim_data.get("claim_frequency_30d", 0) or 0),
            float(claim_data.get("provider_flag_count", 0) or 0),
            float(claim_data.get("subject_flag_count", 0) or 0),
        ]

        line = _coerce_line(claim_data.get("insurance_line"))
        model = (
            self._injected_model
            if self._injected_model is not None
            else (resolve_fraud_model(line) if line is not None else _canonical_model())
        )
        result = model.predict_canonical(canonical)

        return AIPrediction(
            tenant_id=tenant_id,
            entity_type="claim",
            entity_id=claim_data.get("claim_id", "unknown"),
            prediction_type="fraud_detection",
            model_version=result["model_version"],
            input_features=claim_data,
            output=result,
            confidence=1.0 - result["risk_score"],
        )


_canonical_singleton: FraudMLModel | None = None


def _canonical_model() -> FraudMLModel:
    """Kafka-path fallback when the envelope doesn't carry a line."""
    global _canonical_singleton
    if _canonical_singleton is None:
        _canonical_singleton = FraudMLModel(seed=42)
    return _canonical_singleton


def _coerce_line(value: Any) -> InsuranceLine | None:
    if not value:
        return None
    try:
        return InsuranceLine(value)
    except (ValueError, TypeError):
        return None
