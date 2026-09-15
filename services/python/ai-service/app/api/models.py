"""AI models registry — admin endpoints for Phase 5.

Two surfaces:

- ``GET /api/v1/ai/models`` — snapshot of the currently-active model
  version per (model_type, line), plus eval metrics from the metadata
  sidecar and the current ``schema_status`` (G7). 16 rows total.

- ``PUT /api/v1/ai/models/{model_type}/{line}/promote`` — flip the
  MinIO manifest to point at ``version`` and emit an audit event.
  Guarded by the ``ai:models:promote`` permission (G5). The caller's
  permissions arrive via ``X-User-Permissions`` (comma-separated,
  populated by the gateway).

Every write path emits an audit event (Rule 8) with actor + email +
before/after version + friendly entity name (per
``feedback_audit_entity_name``, never the UUID).
"""
from __future__ import annotations

import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Optional

from fastapi import APIRouter, HTTPException, Header, status
from pydantic import BaseModel, Field

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine
from app.services.fraud_registry import (
    refresh_from_manifest as fraud_refresh,
    schema_mismatch_status as fraud_schema_status,
)
from app.services.pricing_registry import (
    refresh_from_manifest as pricing_refresh,
    schema_mismatch_status as pricing_schema_status,
)

from scripts._registry import (
    active_version,
    artifact_exists,
    download_metadata,
    promote_manifest_entry,
)


logger = logging.getLogger(__name__)


router = APIRouter(prefix="/api/v1/ai/models", tags=["AI Models"])


MODEL_TYPES = ("fraud", "pricing")
AI_MODELS_PROMOTE = "ai:models:promote"


CANONICAL_FALLBACK_VERSION = "fraud-isolation-forest-v1-canonical"
RULE_PRICING_VERSION = "rule-v1"


class ActiveModel(BaseModel):
    model_type: str
    line: str
    active_version: Optional[str] = Field(
        None,
        description="MinIO version string when a trained artifact is live; "
                    "None for canonical / rule fallback.",
    )
    model_version: str = Field(
        ...,
        description="The version identifier the runtime records on each "
                    "prediction row (e.g. 'fraud-health-v2' or "
                    "'fraud-isolation-forest-v1-canonical').",
    )
    is_fallback: bool
    trained_at: Optional[str] = None
    train_samples: int = 0
    metrics: dict[str, Any] = Field(default_factory=dict)
    schema_status: str = Field(
        default="OK",
        description="OK | SCHEMA_MISMATCH — flags when a promoted artifact's "
                    "canonical_features_schema doesn't match the runtime "
                    "extractor's; runtime silently falls back to canonical.",
    )
    schema_status_detail: Optional[str] = None


class PromoteRequest(BaseModel):
    version: str = Field(..., min_length=1)


class PromoteResponse(BaseModel):
    model_type: str
    line: str
    before: Optional[str]
    after: str
    audit_event_id: str


def _parse_permissions(header_value: Optional[str]) -> set[str]:
    if not header_value:
        return set()
    return {p.strip() for p in header_value.split(",") if p.strip()}


def _require_promote_permission(x_user_permissions: Optional[str]) -> None:
    perms = _parse_permissions(x_user_permissions)
    if AI_MODELS_PROMOTE not in perms:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="ai:models:promote required",
        )


def _model_type_valid(model_type: str) -> None:
    if model_type not in MODEL_TYPES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"unknown model_type '{model_type}' — expected one of {MODEL_TYPES}",
        )


def _line_valid(line: str) -> InsuranceLine:
    try:
        return InsuranceLine(line.upper())
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"unknown insurance_line '{line}'",
        )


def _fallback_version_for(model_type: str) -> str:
    if model_type == "fraud":
        return CANONICAL_FALLBACK_VERSION
    return RULE_PRICING_VERSION


def _schema_status_for(model_type: str, line: InsuranceLine) -> tuple[str, Optional[str]]:
    if model_type == "fraud":
        detail = fraud_schema_status(model_type, line)
    else:
        detail = pricing_schema_status(model_type, line)
    return ("SCHEMA_MISMATCH" if detail else "OK"), detail


def _row_for(model_type: str, line: InsuranceLine) -> ActiveModel:
    version = active_version(model_type, line)
    if not version:
        schema_state, detail = _schema_status_for(model_type, line)
        return ActiveModel(
            model_type=model_type,
            line=line.value,
            active_version=None,
            model_version=_fallback_version_for(model_type),
            is_fallback=True,
            schema_status=schema_state,
            schema_status_detail=detail,
        )

    metadata: dict[str, Any] = {}
    try:
        metadata = download_metadata(model_type, line, version)
    except Exception as e:
        logger.warning(
            "Could not read metadata for %s/%s/%s: %s",
            model_type, line.value, version, e,
        )

    schema_state, detail = _schema_status_for(model_type, line)
    return ActiveModel(
        model_type=model_type,
        line=line.value,
        active_version=version,
        model_version=str(
            metadata.get("model_version")
            or f"{model_type}-{line.value.lower()}-{version}"
        ),
        is_fallback=schema_state == "SCHEMA_MISMATCH",
        trained_at=metadata.get("trained_at"),
        train_samples=int(metadata.get("train_samples") or 0),
        metrics=dict(metadata.get("val_metrics") or {}),
        schema_status=schema_state,
        schema_status_detail=detail,
    )


@router.get("", response_model=list[ActiveModel])
async def list_active_models() -> list[ActiveModel]:
    """Return the currently-active model version per (model_type, line).

    Ordered by (model_type, line.name) so the admin table sorts predictably.
    """
    rows: list[ActiveModel] = []
    for model_type in MODEL_TYPES:
        for line in InsuranceLine:
            rows.append(_row_for(model_type, line))
    return rows


@router.put(
    "/{model_type}/{line}/promote",
    response_model=PromoteResponse,
    status_code=status.HTTP_200_OK,
)
async def promote_model(
    model_type: str,
    line: str,
    request: PromoteRequest,
    x_actor_id: Optional[str] = Header(None, alias="X-Actor-ID"),
    x_actor_email: Optional[str] = Header(None, alias="X-Actor-Email"),
    x_tenant_id: Optional[str] = Header(None, alias="X-Tenant-ID"),
    x_user_permissions: Optional[str] = Header(None, alias="X-User-Permissions"),
) -> PromoteResponse:
    """Flip the MinIO manifest for (model_type, line) to point at ``version``.

    Requires ``ai:models:promote`` in ``X-User-Permissions`` and the
    caller's actor id + email (Rule 8 — audit event carries actorEmail;
    see ``feedback_audit_actor_email``).
    """
    _require_promote_permission(x_user_permissions)
    _model_type_valid(model_type)
    resolved_line = _line_valid(line)

    if not x_actor_id or not x_actor_email:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="X-Actor-ID and X-Actor-Email headers required",
        )

    if not artifact_exists(model_type, resolved_line, request.version):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                f"artifact not found: {model_type}/{resolved_line.value}/"
                f"{request.version}"
            ),
        )

    before, after = promote_manifest_entry(
        model_type, resolved_line, request.version,
    )

    # Refresh the per-model resolver caches so the promotion takes
    # effect immediately on this pod. Other pods pick up on their
    # next 60s poll (per G6).
    try:
        if model_type == "fraud":
            fraud_refresh()
        else:
            pricing_refresh()
    except Exception as e:  # noqa: BLE001
        logger.warning("Local resolver refresh failed after promote: %s", e)

    event = _build_audit_event(
        actor_id=x_actor_id,
        actor_email=x_actor_email,
        tenant_id=x_tenant_id or "platform",
        model_type=model_type,
        line=resolved_line,
        before=before,
        after=after,
    )
    logger.info(
        "AI_MODEL promotion: %s → %s (%s/%s) by %s",
        before, after, model_type, resolved_line.value, x_actor_email,
    )
    # Kafka publish is best-effort here — the manifest is already
    # updated, so the promotion is durable. If Kafka is down, log the
    # event locally so the audit trail can be reconciled offline.
    try:
        await _publish_audit_event(event)
    except Exception as e:  # noqa: BLE001
        logger.error(
            "Audit publish failed for AI_MODEL promote (%s/%s → %s): %s\n%s",
            model_type, resolved_line.value, after, e, json.dumps(event),
        )

    return PromoteResponse(
        model_type=model_type,
        line=resolved_line.value,
        before=before,
        after=after,
        audit_event_id=event["id"],
    )


def _build_audit_event(
    *,
    actor_id: str, actor_email: str, tenant_id: str,
    model_type: str, line: InsuranceLine,
    before: Optional[str], after: str,
) -> dict[str, Any]:
    return {
        "id":            str(uuid.uuid4()),
        "tenantId":      tenant_id,
        "entityType":    "AI_MODEL",
        "entityId":      f"{model_type}/{line.value}",
        "entityName":    f"{model_type} model for {line.value}",
        "action":        "PROMOTE",
        "actorId":       actor_id,
        "actorEmail":    actor_email,
        "oldValue":      {"active_version": before} if before else {},
        "newValue":      {"active_version": after},
        "changedFields": ["active_version"],
        "correlationId": str(uuid.uuid4()),
        "timestamp":     datetime.now(timezone.utc).isoformat(),
    }


async def _publish_audit_event(event: dict[str, Any]) -> None:
    """Fire-and-forget publish to ``medfund.audit.events``.

    Wrapped in a helper so tests can monkey-patch it without spinning
    up an in-process Kafka.
    """
    from aiokafka import AIOKafkaProducer

    producer = AIOKafkaProducer(
        bootstrap_servers=settings.kafka_bootstrap_servers,
        value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        enable_idempotence=True,
        acks="all",
    )
    await producer.start()
    try:
        await producer.send_and_wait(
            "medfund.audit.events",
            value=event,
            key=(event.get("tenantId") or "").encode("utf-8"),
        )
    finally:
        await producer.stop()
