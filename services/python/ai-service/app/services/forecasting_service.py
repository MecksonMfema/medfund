"""Financial forecasting using linear regression."""
import logging

import numpy as np
from sklearn.linear_model import LinearRegression

logger = logging.getLogger(__name__)


class ForecastingService:
    """Simple time-series forecasting for claims and cashflow."""

    def forecast(self, historical: list[dict], periods: int = 6) -> dict:
        """Forecast future values from historical monthly data.

        historical: list of {"period": int, "value": float}
        """
        if len(historical) < 2:
            return {"error": "Need at least 2 data points", "forecasts": []}

        X = np.array([[d["period"]] for d in historical])
        y = np.array([d["value"] for d in historical])

        model = LinearRegression()
        model.fit(X, y)

        last_period = int(X[-1][0])
        residuals = y - model.predict(X)
        std = float(np.std(residuals)) if len(residuals) > 1 else 0

        forecasts = []
        for i in range(1, periods + 1):
            period = last_period + i
            predicted = float(model.predict([[period]])[0])
            forecasts.append({
                "period": period,
                "predicted_value": round(predicted, 2),
                "lower_bound": round(predicted - std, 2),
                "upper_bound": round(predicted + std, 2),
            })

        return {
            "model": "linear_regression",
            "r_squared": round(float(model.score(X, y)), 4),
            "trend_slope": round(float(model.coef_[0]), 2),
            "forecasts": forecasts,
        }
