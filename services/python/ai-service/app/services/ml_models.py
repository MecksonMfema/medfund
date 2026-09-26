"""ML models for fraud detection and risk scoring.

Two operating modes:

- **Synthetic (fallback)** — no artifact provided. Fits an IsolationForest
  on 1000 randomly-generated samples with a fixed seed. Runs deterministic
  and always boots green; used when no trained artifact exists in MinIO
  for a line, or when the runtime fails to load one.

- **Trained** — Phase 3 load path. Callers hand in an ``artifact`` dict
  produced by ``scripts/train_fraud.py``: a scaler + a base RandomForest
  classifier + a CalibratedClassifierCV wrapping it + the feature names +
  a canonical-features schema version. The calibrated probability is the
  risk score.

Both modes speak the same ``predict_canonical(canonical: list[float])``
contract so ``FraudService`` doesn't care which one it's holding.
"""
from __future__ import annotations

import logging
from typing import Any

import numpy as np
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import IsolationForest, RandomForestClassifier
from sklearn.preprocessing import StandardScaler

from app.schemas.insurance_line import InsuranceLine

logger = logging.getLogger(__name__)


CANONICAL_FALLBACK_VERSION = "fraud-isolation-forest-v1-canonical"


class FraudMLModel:
    """Fraud scorer over the canonical line-neutral 5-tuple:

        [amount, days_since_start, claim_frequency_30d,
         provider_flag_count, subject_flag_count]

    See ``app/services/feature_extractors.py`` for extraction. Line-
    specific rule indicators live in the extractor, not here.
    """

    def __init__(
        self,
        seed: int = 42,
        *,
        line: InsuranceLine | None = None,
        artifact: dict[str, Any] | None = None,
    ):
        self.line = line
        self._artifact_scaler: StandardScaler | None = None
        self._calibrator: CalibratedClassifierCV | None = None
        self._classifier: RandomForestClassifier | None = None
        self._feature_names: list[str] | None = None
        self.canonical_features_schema: str | None = None

        self._isolation_forest: IsolationForest | None = None
        self._scaler: StandardScaler | None = None
        self._trained = False

        if artifact is not None:
            self.model_version = str(artifact["model_version"])
            self._artifact_scaler = artifact["scaler"]
            self._calibrator = artifact["calibrator"]
            self._classifier = artifact.get("model")
            self._feature_names = list(artifact.get("feature_names") or [])
            self.canonical_features_schema = artifact.get("canonical_features_schema")
            self._trained = True
        else:
            self.model_version = CANONICAL_FALLBACK_VERSION
            self._train(seed)

    def _train(self, seed: int) -> None:
        rng = np.random.RandomState(seed)

        n_normal = 950
        n_fraud = 50

        normal_data = np.column_stack([
            rng.lognormal(6, 1, n_normal),
            rng.uniform(90, 3650, n_normal),
            rng.poisson(1.0, n_normal),
            rng.poisson(0.1, n_normal),
            rng.poisson(0.1, n_normal),
        ])
        fraud_data = np.column_stack([
            rng.lognormal(9, 0.5, n_fraud),
            rng.uniform(10, 180, n_fraud),
            rng.poisson(5.0, n_fraud),
            rng.poisson(1.5, n_fraud),
            rng.poisson(1.5, n_fraud),
        ])
        X = np.vstack([normal_data, fraud_data])

        self._scaler = StandardScaler()
        X_scaled = self._scaler.fit_transform(X)
        self._isolation_forest = IsolationForest(
            contamination=0.05, random_state=seed, n_estimators=100
        )
        self._isolation_forest.fit(X_scaled)
        self._trained = True
        logger.info(
            "FraudMLModel trained on %d canonical samples (fallback)",
            n_normal + n_fraud,
        )

    @property
    def is_trained_artifact(self) -> bool:
        """True when this instance was hydrated from a MinIO artifact."""
        return self._calibrator is not None

    def predict_canonical(self, canonical: list[float]) -> dict:
        """Score a canonical 5-tuple. Deterministic — same input, same score."""
        indicators = self._rule_indicators(canonical)

        if self.is_trained_artifact:
            X = np.array([canonical], dtype=float)
            X_scaled = self._artifact_scaler.transform(X)  # type: ignore[union-attr]
            risk_score = float(self._calibrator.predict_proba(X_scaled)[0, 1])  # type: ignore[union-attr]
            return {
                "risk_score":    round(risk_score, 4),
                "risk_level":    _bucket(risk_score),
                "indicators":    indicators,
                "model_version": self.model_version,
            }

        if not self._trained or self._scaler is None or self._isolation_forest is None:
            return {
                "risk_score":    0.1,
                "risk_level":    "LOW",
                "indicators":    indicators,
                "model_version": self.model_version,
            }

        X = np.array([canonical], dtype=float)
        X_scaled = self._scaler.transform(X)
        anomaly_score = self._isolation_forest.decision_function(X_scaled)[0]
        risk_score = max(0.0, min(1.0, 0.5 - anomaly_score * 0.5))
        return {
            "risk_score":    round(float(risk_score), 4),
            "risk_level":    _bucket(risk_score),
            "indicators":    indicators,
            "model_version": self.model_version,
        }

    @staticmethod
    def _rule_indicators(canonical: list[float]) -> list[str]:
        indicators: list[str] = []
        amount, days_since_start, freq_30d, provider_flags, subject_flags = canonical
        if days_since_start < 90:
            indicators.append("new_subject")
        if freq_30d >= 5:
            indicators.append("high_frequency")
        if provider_flags >= 1:
            indicators.append("provider_flagged")
        if subject_flags >= 1:
            indicators.append("subject_flagged")
        return indicators


def _bucket(risk_score: float) -> str:
    if risk_score > 0.6:
        return "HIGH"
    if risk_score > 0.3:
        return "MEDIUM"
    return "LOW"
