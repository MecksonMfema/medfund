"""Line-aware fraud check request/response schemas."""
from __future__ import annotations

from datetime import date
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field

from app.schemas.insurance_line import InsuranceLine


class FraudCheckRequest(BaseModel):
    """Line-aware fraud check payload.

    `subject_id` is the member id for person-centric lines and the asset id
    (vehicle_id / property_id) for asset-centric lines. Feature extraction
    dispatches on `insurance_line` to pick the correct extractor.

    `line_features` is a free-form bag whose expected keys depend on the
    line. See app/services/feature_extractors.py for the per-line shape.
    """

    claim_id: UUID
    insurance_line: InsuranceLine
    subject_id: UUID
    provider_id: UUID | None = None
    claimed_amount: float
    currency_code: str
    service_date: date
    line_features: dict[str, Any] = Field(default_factory=dict)


class FraudCheckResponse(BaseModel):
    claim_id: UUID
    insurance_line: InsuranceLine
    risk_score: float
    risk_level: str
    indicators: list[str]
    model_version: str
