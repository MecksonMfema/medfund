"""Kafka runner for the actuarial async job pipeline.

Consumes ``medfund.actuarial.job-requested``, dispatches to the phase-specific
compute (Phase 8 wires IBNR + LOSS_TRIANGLE via chain-ladder; Phases 11-14
plug into the same runner via ``report_key`` branches), and publishes the
outcome envelope to ``medfund.actuarial.job-completed``.

Ack discipline follows the parent-plan invariant ("ack only on success") —
adapted for aiokafka's manual-commit surface, that means:

* On success — publish completed event with ``status='completed'``, commit.
* On error — publish completed event with ``status='failed'`` + ``error_message``,
  then commit. Committing on the failed-publish path avoids the redelivery
  loop that would otherwise fire the same broken compute forever; the failed
  envelope is the durable record on the Java side.

A best-effort size guard rejects payloads over the Kafka 1 MB default (warn
at 800 KB, hard-fail at 900 KB per Grill note 7) so a runaway result never
poisons the topic.
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable

from app.actuarial.chain_ladder import ChainLadderResult, TriangleInput, compute
from app.actuarial.events import (
    CONSUMER_GROUP,
    TOPIC_COMPLETED,
    TOPIC_REQUESTED,
    ActuarialJobCompletedEvent,
    ActuarialJobRequestedEvent,
)
from app.actuarial.persistency import compute_from_dict as compute_persistency

log = logging.getLogger(__name__)

MODEL_VERSION = "chainladder-python:0.8.19"

WARN_KB = 800
REJECT_KB = 900

_TRIANGLE_KEYS = {"IBNR_TRIANGLE", "LOSS_TRIANGLE"}
_COHORT_KEYS = {"PERSISTENCY_STUDY"}


class PayloadTooLargeError(ValueError):
    """Raised when a completed-event payload would exceed the Kafka size ceiling."""


ConsumerFactory = Callable[[], Any]
ProducerFactory = Callable[[], Any]


def _default_consumer_factory(bootstrap_servers: str) -> ConsumerFactory:
    def make() -> Any:
        from aiokafka import AIOKafkaConsumer

        return AIOKafkaConsumer(
            TOPIC_REQUESTED,
            bootstrap_servers=bootstrap_servers,
            group_id=CONSUMER_GROUP,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
            value_deserializer=lambda v: json.loads(v.decode("utf-8")),
        )

    return make


def _default_producer_factory(bootstrap_servers: str) -> ProducerFactory:
    def make() -> Any:
        from aiokafka import AIOKafkaProducer

        return AIOKafkaProducer(
            bootstrap_servers=bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
        )

    return make


class ActuarialJobRunner:
    """Owns the consume-compute-publish loop for actuarial async jobs.

    Constructor takes explicit consumer + producer factories so unit tests
    can inject fakes without patching aiokafka at import time.
    """

    def __init__(
        self,
        bootstrap_servers: str,
        *,
        consumer_factory: ConsumerFactory | None = None,
        producer_factory: ProducerFactory | None = None,
        compute_fn: Callable[[TriangleInput, str], ChainLadderResult] = None,  # type: ignore[assignment]
    ) -> None:
        self.bootstrap_servers = bootstrap_servers
        self._consumer_factory = consumer_factory or _default_consumer_factory(bootstrap_servers)
        self._producer_factory = producer_factory or _default_producer_factory(bootstrap_servers)
        self._compute_fn = compute_fn or (lambda payload, method: compute(payload, method))  # type: ignore[arg-type]
        self.consumer: Any | None = None
        self.producer: Any | None = None
        self._task: asyncio.Task[None] | None = None
        self._running = False

    async def start(self) -> None:
        if self.consumer is None:
            self.consumer = self._consumer_factory()
        if self.producer is None:
            self.producer = self._producer_factory()
        await self.consumer.start()
        await self.producer.start()
        self._running = True
        self._task = asyncio.create_task(self._run_loop())
        log.info("Actuarial job runner: STARTED (group=%s)", CONSUMER_GROUP)

    async def stop(self) -> None:
        self._running = False
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):  # noqa: BLE001
                pass
            self._task = None
        if self.consumer is not None:
            await self.consumer.stop()
        if self.producer is not None:
            await self.producer.stop()
        log.info("Actuarial job runner: STOPPED")

    async def _run_loop(self) -> None:
        assert self.consumer is not None
        try:
            async for msg in self.consumer:
                if not self._running:
                    break
                await self._process_one(msg.value)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            # Log then exit — lifespan will restart the runner on next boot; a
            # broken outer loop must not silently swallow every future message.
            log.exception("Actuarial runner loop crashed")
            raise

    async def _process_one(self, raw: Any) -> None:
        """Consume + compute + publish for a single record, then commit."""
        job_id = "unknown"
        report_key = "unknown"
        method = "volume"
        tenant_id = "unknown"
        try:
            event = ActuarialJobRequestedEvent(**raw) if isinstance(raw, dict) else raw
            job_id = event.job_id
            tenant_id = event.tenant_id
            report_key = event.report_key
            method = str(event.params.get("ldfMethod", "volume"))
            completed = await self._dispatch(event)
            await self._publish(completed)
        except Exception as exc:  # noqa: BLE001
            log.exception("Actuarial job %s failed: %s", job_id, exc)
            failed = ActuarialJobCompletedEvent(
                job_id=job_id,
                tenant_id=tenant_id,
                report_key=report_key,
                status="failed",
                error_message=str(exc)[:1000],
                model_version=MODEL_VERSION,
                method=method,
                computed_at=_now_iso(),
            )
            try:
                await self._publish(failed)
            except Exception:  # noqa: BLE001
                # Publishing the failure envelope itself blew up. Log and fall
                # through to the commit — the redelivery would just replay the
                # same broken record, which is worse than a silently-dropped
                # failure envelope (redelivery does not fix a Kafka producer
                # outage).
                log.exception(
                    "Failed to publish failed-event for job %s; committing offset anyway", job_id
                )
        finally:
            assert self.consumer is not None
            await self.consumer.commit()

    async def _dispatch(self, event: ActuarialJobRequestedEvent) -> ActuarialJobCompletedEvent:
        method = str(event.params.get("ldfMethod", "volume"))
        if event.report_key in _TRIANGLE_KEYS:
            if event.triangle is None:
                raise ValueError(
                    f"report_key={event.report_key} requires a triangle payload"
                )
            result = self._compute_fn(event.triangle, method)
            return ActuarialJobCompletedEvent(
                job_id=event.job_id,
                tenant_id=event.tenant_id,
                report_key=event.report_key,
                status="completed",
                result_json=result.model_dump(),
                model_version=MODEL_VERSION,
                method=method,
                computed_at=_now_iso(),
            )
        if event.report_key in _COHORT_KEYS:
            if event.cohort is None:
                raise ValueError(
                    f"report_key={event.report_key} requires a cohort payload"
                )
            persistency_result = compute_persistency(event.cohort)
            return ActuarialJobCompletedEvent(
                job_id=event.job_id,
                tenant_id=event.tenant_id,
                report_key=event.report_key,
                status="completed",
                result_json=persistency_result.model_dump(),
                model_version=MODEL_VERSION,
                method=method,
                computed_at=_now_iso(),
            )
        raise NotImplementedError(
            f"report_key={event.report_key} not yet wired (see Phases 11-14)"
        )

    async def _publish(self, event: ActuarialJobCompletedEvent) -> None:
        assert self.producer is not None
        payload = event.model_dump()
        _guard_size(payload)
        await self.producer.send_and_wait(TOPIC_COMPLETED, payload)


def _guard_size(payload: dict[str, Any]) -> None:
    size_kb = len(json.dumps(payload)) / 1024
    if size_kb >= REJECT_KB:
        raise PayloadTooLargeError(
            f"Result payload too large ({size_kb:.0f}KB); coarsen grain or narrow period"
        )
    if size_kb >= WARN_KB:
        log.warning("Result payload size %.0fKB approaching Kafka limit", size_kb)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


__all__ = [
    "ActuarialJobRunner",
    "MODEL_VERSION",
    "PayloadTooLargeError",
    "REJECT_KB",
    "WARN_KB",
]
