"""AI Predictions review endpoints — the human-review half of Critical Rule #3.

Tenant admins browse every AI prediction persisted by the AI service for their
tenant, filter by line / entity type / decision status, and record an
accept / override decision that writes back to ``ai_predictions_store``.
"""
from __future__ import annotations

import logging
import random
from datetime import datetime
from typing import Optional
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.database import get_optional_session
from app.models.db_models import AIPredictionDB
from app.schemas.insurance_line import InsuranceLine

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/ai/predictions", tags=["AI Predictions"])


class PredictionRow(BaseModel):
    id: str
    tenant_id: str
    insurance_line: Optional[str] = None
    entity_type: str
    entity_id: str
    prediction_type: str
    model_version: str
    confidence: Optional[float] = None
    accepted: Optional[bool] = None
    reviewed_by: Optional[str] = None
    reviewed_by_email: Optional[str] = None
    reviewed_at: Optional[datetime] = None
    created_at: Optional[datetime] = None


class PredictionDetail(PredictionRow):
    input_features: dict = Field(default_factory=dict)
    output: dict = Field(default_factory=dict)


class PredictionPage(BaseModel):
    items: list[PredictionRow]
    total: int
    page: int
    size: int


class DecideRequest(BaseModel):
    accepted: bool
    feedback: str | None = None


def _to_row(p: AIPredictionDB) -> PredictionRow:
    return PredictionRow(
        id=str(p.id),
        tenant_id=str(p.tenant_id),
        insurance_line=p.insurance_line,
        entity_type=p.entity_type,
        entity_id=p.entity_id,
        prediction_type=p.prediction_type,
        model_version=p.model_version or "",
        confidence=p.confidence,
        accepted=p.accepted,
        reviewed_by=p.reviewed_by,
        reviewed_by_email=p.reviewed_by_email,
        reviewed_at=p.reviewed_at,
        created_at=p.created_at,
    )


def _to_detail(p: AIPredictionDB) -> PredictionDetail:
    row = _to_row(p)
    return PredictionDetail(
        **row.model_dump(),
        input_features=p.input_features or {},
        output=p.output or {},
    )


def _require_session(session: AsyncSession | None) -> AsyncSession:
    if session is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="AI predictions persistence not available",
        )
    return session


@router.get("", response_model=PredictionPage)
async def list_predictions(
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    insurance_line: Optional[InsuranceLine] = Query(None),
    entity_type: Optional[str] = Query(None),
    prediction_type: Optional[str] = Query(None),
    model_version: Optional[str] = Query(None),
    accepted: Optional[bool] = Query(None),
    page: int = Query(0, ge=0),
    size: int = Query(50, ge=1, le=500),
    session: AsyncSession | None = Depends(get_optional_session),
) -> PredictionPage:
    """Tenant-scoped list of AI predictions with optional filters."""
    db = _require_session(session)
    conditions = [AIPredictionDB.tenant_id == x_tenant_id]
    if insurance_line is not None:
        conditions.append(AIPredictionDB.insurance_line == insurance_line.value)
    if entity_type:
        conditions.append(AIPredictionDB.entity_type == entity_type)
    if prediction_type:
        conditions.append(AIPredictionDB.prediction_type == prediction_type)
    if model_version:
        conditions.append(AIPredictionDB.model_version == model_version)
    if accepted is not None:
        conditions.append(AIPredictionDB.accepted == accepted)
    where = and_(*conditions)

    total = (
        await db.execute(select(func.count()).select_from(AIPredictionDB).where(where))
    ).scalar_one()

    rows = (
        await db.execute(
            select(AIPredictionDB)
            .where(where)
            .order_by(AIPredictionDB.created_at.desc())
            .offset(page * size)
            .limit(size)
        )
    ).scalars().all()

    return PredictionPage(
        items=[_to_row(r) for r in rows],
        total=int(total or 0),
        page=page,
        size=size,
    )


class ReviewQueueBatch(BaseModel):
    """Stratified sample of unreviewed predictions for the review queue.

    The corpus consumed by Tranche 1 training scripts skews HIGH when
    reviewers only ever inspect what the model flagged. This endpoint
    returns a 50/50 HIGH/LOW random-sample batch so overrides accumulate
    across both buckets, giving downstream training real true-negative
    signal.
    """

    items: list[PredictionDetail]
    total_unreviewed: int
    high_count: int
    low_count: int
    other_count: int


@router.get("/review-queue", response_model=ReviewQueueBatch)
async def review_queue(
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    size: int = Query(20, ge=1, le=200),
    model_type: str = Query("fraud", description="Prediction type to sample from"),
    insurance_line: Optional[InsuranceLine] = Query(None),
    seed: Optional[int] = Query(None, description="Deterministic sampling for tests"),
    session: AsyncSession | None = Depends(get_optional_session),
) -> ReviewQueueBatch:
    """Stratified 50/50 HIGH/LOW random sample of unreviewed predictions.

    Bucketing keys on ``output.risk_level``. When one bucket is thin,
    the shortfall is filled from the other so callers still get ``size``
    rows when volume allows. Everything is tenant-scoped by header.
    """
    db = _require_session(session)
    conditions = [
        AIPredictionDB.tenant_id == x_tenant_id,
        AIPredictionDB.prediction_type == model_type,
        AIPredictionDB.accepted.is_(None),
    ]
    if insurance_line is not None:
        conditions.append(AIPredictionDB.insurance_line == insurance_line.value)

    rows = (
        await db.execute(select(AIPredictionDB).where(and_(*conditions)))
    ).scalars().all()

    high: list[AIPredictionDB] = []
    low: list[AIPredictionDB] = []
    other: list[AIPredictionDB] = []
    for r in rows:
        level = (r.output or {}).get("risk_level")
        if level == "HIGH":
            high.append(r)
        elif level == "LOW":
            low.append(r)
        else:
            other.append(r)

    rng = random.Random(seed) if seed is not None else random.Random()
    rng.shuffle(high)
    rng.shuffle(low)
    rng.shuffle(other)

    target_high = size // 2
    target_low = size - target_high
    picked_high = high[:target_high]
    picked_low = low[:target_low]

    # Backfill when one bucket is thin.
    shortfall = size - len(picked_high) - len(picked_low)
    if shortfall > 0:
        pool = high[len(picked_high):] + low[len(picked_low):] + other
        rng.shuffle(pool)
        picked_high.extend(pool[:shortfall])

    picked = picked_high + picked_low
    rng.shuffle(picked)

    return ReviewQueueBatch(
        items=[_to_detail(r) for r in picked],
        total_unreviewed=len(rows),
        high_count=len(high),
        low_count=len(low),
        other_count=len(other),
    )


@router.get("/{prediction_id}", response_model=PredictionDetail)
async def get_prediction(
    prediction_id: UUID,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    session: AsyncSession | None = Depends(get_optional_session),
) -> PredictionDetail:
    """Fetch one prediction — tenant-scoped, 404 on cross-tenant access."""
    db = _require_session(session)
    row = (
        await db.execute(
            select(AIPredictionDB).where(AIPredictionDB.id == str(prediction_id))
        )
    ).scalar_one_or_none()
    if row is None or row.tenant_id != x_tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
    return _to_detail(row)


@router.put("/{prediction_id}/decision", response_model=PredictionRow)
async def decide_prediction(
    prediction_id: UUID,
    decision: DecideRequest,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    x_actor_id: str | None = Header(None, alias="X-Actor-ID"),
    x_actor_email: str | None = Header(None, alias="X-Actor-Email"),
    session: AsyncSession | None = Depends(get_optional_session),
) -> PredictionRow:
    """Record a human accept / override decision on an AI prediction.

    The gateway forwards the caller's actor id + email as ``X-Actor-*`` headers;
    the AI-service does not decode Keycloak JWTs itself. Both must be present so
    the audit trail carries the actor's email (Rule 8 / ``feedback_audit_actor_email``).
    """
    db = _require_session(session)
    row = (
        await db.execute(
            select(AIPredictionDB).where(AIPredictionDB.id == str(prediction_id))
        )
    ).scalar_one_or_none()
    if row is None or row.tenant_id != x_tenant_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
    if not x_actor_id or not x_actor_email:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="X-Actor-ID and X-Actor-Email headers required",
        )

    row.accepted = decision.accepted
    row.reviewed_by = x_actor_id
    row.reviewed_by_email = x_actor_email
    row.reviewed_at = datetime.utcnow()
    if decision.feedback:
        merged = dict(row.output or {})
        merged["_review_feedback"] = decision.feedback
        row.output = merged
    await db.commit()
    await db.refresh(row)
    return _to_row(row)
