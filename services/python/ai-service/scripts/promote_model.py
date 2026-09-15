"""Promote a candidate model artifact to ``active`` in the MinIO manifest.

Usage:
    uv run python scripts/promote_model.py \\
        --model-type fraud --line HEALTH --version v2 \\
        --actor-id user-42 --actor-email admin@example.com \\
        --reason "eval regression cleared"

Effects:
    1. Verify the artifact exists in the bucket.
    2. Update ``manifest.json`` to map ``fraud/HEALTH`` → ``v2``.
    3. Publish an audit event to ``medfund.audit.events`` with actor,
       before/after version, entity type ``AI_MODEL``, entity name
       "fraud model for HEALTH" (friendly text — never the UUID; see
       ``feedback_audit_entity_name``).

The CLI mirrors the Phase 5 UI promote flow so an operator can hand-drive
a rollback in a pinch. AuditEvent shape matches the Go
``audit-service/internal/audit/event.go`` struct.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Optional

from aiokafka import AIOKafkaProducer

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine

from scripts._registry import (
    active_version,
    artifact_exists,
    promote_manifest_entry,
)


TOPIC_AUDIT_EVENTS = "medfund.audit.events"
ENTITY_TYPE = "AI_MODEL"
EXIT_MISSING_ARTIFACT = 4

logger = logging.getLogger(__name__)


@dataclass
class PromoteArgs:
    model_type: str
    line: InsuranceLine
    version: str
    actor_id: str
    actor_email: str
    reason: Optional[str]
    tenant_id: str
    correlation_id: str


def _entity_name(model_type: str, line: InsuranceLine) -> str:
    """Friendly text for the audit trail — never the UUID."""
    return f"{model_type} model for {line.value}"


def _audit_event(args: PromoteArgs, *, before: Optional[str], after: str) -> dict[str, Any]:
    return {
        "id":            str(uuid.uuid4()),
        "tenantId":      args.tenant_id,
        "entityType":    ENTITY_TYPE,
        "entityId":      f"{args.model_type}/{args.line.value}",
        "entityName":    _entity_name(args.model_type, args.line),
        "action":        "PROMOTE",
        "actorId":       args.actor_id,
        "actorEmail":    args.actor_email,
        "oldValue":      {"active_version": before} if before else {},
        "newValue":      {"active_version": after, "reason": args.reason or ""},
        "changedFields": ["active_version"],
        "correlationId": args.correlation_id,
        "timestamp":     datetime.now(timezone.utc).isoformat(),
    }


async def _publish(event: dict[str, Any]) -> None:
    producer = AIOKafkaProducer(
        bootstrap_servers=settings.kafka_bootstrap_servers,
        value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        enable_idempotence=True,
        acks="all",
    )
    await producer.start()
    try:
        await producer.send_and_wait(
            TOPIC_AUDIT_EVENTS,
            value=event,
            key=(event.get("tenantId") or "").encode("utf-8"),
        )
    finally:
        await producer.stop()


def promote(args: PromoteArgs, *, publish: bool = True) -> tuple[Optional[str], str, dict[str, Any]]:
    """Verify → update manifest → publish audit event.

    Returns ``(before, after, event)`` — callers (tests) can inspect the
    event without hitting Kafka when ``publish=False``.
    """
    if not artifact_exists(args.model_type, args.line, args.version):
        raise FileNotFoundError(
            f"artifact not found in bucket: {args.model_type}/{args.line.value}/{args.version}"
        )

    before = active_version(args.model_type, args.line)
    _, after = promote_manifest_entry(args.model_type, args.line, args.version)
    event = _audit_event(args, before=before, after=after)
    if publish:
        asyncio.run(_publish(event))
    return before, after, event


def _parse_args(argv: list[str] | None = None) -> PromoteArgs:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model-type", required=True,
                        choices=["fraud", "pricing"])
    parser.add_argument("--line", required=True,
                        choices=[l.value for l in InsuranceLine])
    parser.add_argument("--version", required=True, help="e.g. 'v2'")
    parser.add_argument("--actor-id", required=True)
    parser.add_argument("--actor-email", required=True,
                        help="Reviewer email — never null "
                             "(see feedback_audit_actor_email).")
    parser.add_argument("--reason", default=None,
                        help="Free text — surfaced in the audit trail.")
    parser.add_argument("--tenant-id", default="platform",
                        help="Which tenant this promotion applies to. "
                             "Defaults to 'platform' since model artifacts "
                             "are cross-tenant per G4.")
    parser.add_argument("--correlation-id", default=None)
    parsed = parser.parse_args(argv)
    if not parsed.actor_email or "@" not in parsed.actor_email:
        parser.error("--actor-email must be a real email address")
    return PromoteArgs(
        model_type=parsed.model_type,
        line=InsuranceLine(parsed.line),
        version=parsed.version,
        actor_id=parsed.actor_id,
        actor_email=parsed.actor_email,
        reason=parsed.reason,
        tenant_id=parsed.tenant_id,
        correlation_id=parsed.correlation_id or str(uuid.uuid4()),
    )


def main(argv: list[str] | None = None) -> None:
    args = _parse_args(argv)
    try:
        before, after, event = promote(args)
    except FileNotFoundError as e:
        sys.stderr.write(f"promote_model: {e}\n")
        sys.exit(EXIT_MISSING_ARTIFACT)

    print(json.dumps({
        "model_type":     args.model_type,
        "line":           args.line.value,
        "before":         before,
        "after":          after,
        "audit_event_id": event["id"],
    }, indent=2))


if __name__ == "__main__":
    main()
