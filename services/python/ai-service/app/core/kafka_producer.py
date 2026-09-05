"""Outbound Kafka producer for AI decisions. Phase 19 §A Phase 3.

Wires the FRAUD_FLAG_EMITTED event on medfund.claims.fraud-flagged per
Rule 3 (AI decisions must be auditable). Pattern mirrors the existing
report-job producer at app/report/kafka.py:85-94.
"""
from __future__ import annotations

import json
import logging
from typing import Any

from aiokafka import AIOKafkaProducer

logger = logging.getLogger(__name__)


class ClaimsEventProducer:
    """Thin async wrapper over AIOKafkaProducer for claims-domain events."""

    TOPIC_FRAUD_FLAGGED = "medfund.claims.fraud-flagged"

    def __init__(self, bootstrap_servers: str) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._producer: AIOKafkaProducer | None = None

    async def start(self) -> None:
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            enable_idempotence=True,
            acks="all",
        )
        await self._producer.start()
        logger.info("ClaimsEventProducer started bootstrap=%s", self._bootstrap_servers)

    async def stop(self) -> None:
        if self._producer is not None:
            await self._producer.stop()
            self._producer = None
            logger.info("ClaimsEventProducer stopped")

    async def publish_fraud_flagged(self, event: dict[str, Any]) -> None:
        """Publish a FRAUD_FLAG_EMITTED event. Tenant id is used as the
        partition key to preserve per-tenant ordering (Phase 0 Kafka
        convention)."""
        if self._producer is None:
            raise RuntimeError("ClaimsEventProducer.start() has not been called")
        tenant_id = event.get("tenantId") or ""
        await self._producer.send_and_wait(
            self.TOPIC_FRAUD_FLAGGED,
            value=event,
            key=tenant_id.encode("utf-8"),
        )
