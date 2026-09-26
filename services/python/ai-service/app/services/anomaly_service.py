"""Anomaly detection in financial transactions."""
import logging

import numpy as np
from sklearn.ensemble import IsolationForest

logger = logging.getLogger(__name__)


class AnomalyService:
    """Detects anomalous transactions using IsolationForest."""

    def detect_anomalies(self, transactions: list[dict], contamination: float = 0.05) -> dict:
        """Flag anomalous transactions from a list."""
        if len(transactions) < 10:
            return {"error": "Need at least 10 transactions", "anomalies": []}

        amounts = np.array([float(t.get("amount", 0)) for t in transactions]).reshape(-1, 1)

        model = IsolationForest(contamination=contamination, random_state=42)
        predictions = model.fit_predict(amounts)
        scores = model.decision_function(amounts)

        anomalies = []
        for i, (pred, score) in enumerate(zip(predictions, scores)):
            if pred == -1:
                anomalies.append({
                    "index": i,
                    "transaction": transactions[i],
                    "anomaly_score": round(float(-score), 4),
                })

        return {
            "total_transactions": len(transactions),
            "anomalies_found": len(anomalies),
            "anomalies": sorted(anomalies, key=lambda a: a["anomaly_score"], reverse=True),
        }
