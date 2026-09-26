"""Financial forecasting endpoints."""
import logging

from fastapi import APIRouter, Header
from pydantic import BaseModel

from app.services.forecasting_service import ForecastingService

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/ai/analytics", tags=["Analytics"])

_forecasting = ForecastingService()


class ForecastRequest(BaseModel):
    historical_data: list[dict]
    periods: int = 6


@router.post("/forecast")
async def forecast(
    request: ForecastRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
):
    """Forecast future values from historical monthly data."""
    return _forecasting.forecast(request.historical_data, request.periods)
