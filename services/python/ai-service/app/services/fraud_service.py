"""Fraud detection service using ML models.

Two entry points:

- ``check_fraud_request`` is the endpoint path: takes a line-aware
  ``FraudCheckRequest``, dispatches through the per-line extractor
  registry, scores the canonical 5-tuple with the shared IsolationForest,
  and returns the endpoint response payload plus the raw ``line_features``
  bag for audit persistence.

- ``detect_fraud`` is the legacy Kafka-consumer path: takes a dict from
  a CLAIM_SUBMITTED envelope and returns an ``AIPrediction`` for the
  Phase 19 §A fraud producer. It synthesises a canonical vector directly
  from the dict since claim-submitted events don't yet carry the
  line-specific detail bag.
"""
from __future__ import annotations

import logging
from typing import Any

from app.models.prediction import AIPrediction
from app.schemas.fraud import FraudCheckRequest
from app.services.feature_extractors import (
    ExtractorRegistry,
    FraudContext,
)
from app.services.ml_models import FraudMLModel

logger = logging.getLogger(__name__)

# Global model instance — trained once on import
_fraud_model = FraudMLModel()
_extractor_registry = ExtractorRegistry()


class FraudService:
    """Fraud detection using IsolationForest on the canonical 5-tuple."""

    def __init__(
        self,
        model: FraudMLModel | None = None,
        extractors: ExtractorRegistry | None = None,
    ):
        self.model = model or _fraud_model
        self.extractors = extractors or _extractor_registry
        self.model_version = self.model.model_version

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
        # FraudContext is stubbed with defaults; Tranche 1 wires the
        # historical lookups (days-since-start, frequency, prior flags).
        ctx = FraudContext()
        canonical, normalized, rule_indicators = await extractor.extract(
            request, tenant_id, ctx,
        )

        ml_out = self.model.predict_canonical(canonical)

        indicators = list(ml_out.get("indicators", [])) + rule_indicators
        return {
            "risk_score": ml_out["risk_score"],
            "risk_level": ml_out["risk_level"],
            "indicators": indicators,
            "model_version": ml_out["model_version"],
            "insurance_line": request.insurance_line.value,
            "line_features": normalized,
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
        result = self.model.predict_canonical(canonical)

        return AIPrediction(
            tenant_id=tenant_id,
            entity_type="claim",
            entity_id=claim_data.get("claim_id", "unknown"),
            prediction_type="fraud_detection",
            model_version=self.model_version,
            input_features=claim_data,
            output=result,
            confidence=1.0 - result["risk_score"],
        )
