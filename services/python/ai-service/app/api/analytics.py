"""Analytics endpoints — anomaly detection, provider intelligence."""
import logging

from fastapi import APIRouter, Header
from pydantic import BaseModel

from app.services.anomaly_service import AnomalyService

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/ai/analytics", tags=["Analytics"])

_anomaly = AnomalyService()


class AnomalyRequest(BaseModel):
    transactions: list[dict]
    contamination: float = 0.05


class ProviderStatsRequest(BaseModel):
    provider_id: str
    claims: list[dict]


@router.post("/anomalies")
async def detect_anomalies(
    request: AnomalyRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
):
    """Detect anomalous transactions using ML."""
    return _anomaly.detect_anomalies(request.transactions, request.contamination)


@router.post("/provider-stats")
async def provider_stats(
    request: ProviderStatsRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
):
    """Compute provider performance statistics."""
    claims = request.claims
    total = len(claims)
    if total == 0:
        return {"provider_id": request.provider_id, "total_claims": 0}

    approved = sum(1 for c in claims if c.get("status") == "ADJUDICATED")
    rejected = sum(1 for c in claims if c.get("status") == "REJECTED")
    total_amount = sum(float(c.get("claimed_amount", 0)) for c in claims)

    return {
        "provider_id": request.provider_id,
        "total_claims": total,
        "approved_count": approved,
        "rejected_count": rejected,
        "approval_rate": round(approved / total, 4) if total else 0,
        "rejection_rate": round(rejected / total, 4) if total else 0,
        "total_claimed_amount": round(total_amount, 2),
        "avg_claim_amount": round(total_amount / total, 2) if total else 0,
    }
