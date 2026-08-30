"""Kafka runner for the async report job pipeline (Phase 15 §1).

Renamed from ``app/actuarial/kafka.py``. Consumes from the canonical
``medfund.report.job-requested`` topic (Phase 15 §22 cutover dropped the
legacy ``medfund.actuarial.job-requested`` dual-subscribe — §23 deletes
the topic), dispatches on ``report_key`` (actuarial keys route to Phase-14
compute; IFRS 17 keys land in ``_ifrs17_dispatch``), and publishes the
terminal envelope to the canonical ``medfund.report.job-completed`` topic.

Ack discipline follows the parent-plan invariant ("ack only on success") —
adapted for aiokafka's manual-commit surface:

* On success — publish completed event with ``status='completed'``, commit.
* On error — publish completed event with ``status='failed'`` +
  ``error_message``, then commit. Committing on the failed-publish path
  avoids the redelivery loop that would otherwise fire the same broken
  compute forever; the failed envelope is the durable record on the Java
  side.

A best-effort size guard rejects payloads over the Kafka 1 MB default (warn
at 800 KB, hard-fail at 900 KB per Grill note 7). §10 introduces a MinIO
fallback so the hard-fail path becomes a stream-to-MinIO for real payloads.
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Any, Callable

from app.actuarial.chain_ladder import ChainLadderResult, TriangleInput, compute
from app.actuarial.lapse import compute_from_dict as compute_lapse
from app.actuarial.morbidity import compute_from_dict as compute_morbidity
from app.actuarial.mortality import compute_from_dict as compute_mortality
from app.actuarial.persistency import compute_from_dict as compute_persistency
from app.ifrs17 import gmm, paa, vfa
from app.kafka import minio_payload
from app.report.events import (
    CONSUMER_GROUP,
    TOPIC_COMPLETED,
    TOPIC_REQUESTED,
    ReportJobCompletedEvent,
    ReportJobRequestedEvent,
)

log = logging.getLogger(__name__)

MODEL_VERSION = "chainladder-python:0.8.19"

WARN_KB = 800
REJECT_KB = 900

_TRIANGLE_KEYS = {"IBNR_TRIANGLE", "LOSS_TRIANGLE"}
_COHORT_KEYS = {"PERSISTENCY_STUDY", "LAPSE_STUDY"}
_EXPOSURE_KEYS = {"MORTALITY_STUDY", "MORBIDITY_STUDY"}
_IFRS17_KEYS = {"IFRS17_LRC_LIC_RECONCILIATION", "IFRS17_INSURANCE_REVENUE_SERVICE_RESULT"}


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


class ReportJobRunner:
    """Owns the consume-compute-publish loop for async report jobs.

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
        log.info("Report job runner: STARTED (group=%s)", CONSUMER_GROUP)

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
        log.info("Report job runner: STOPPED")

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
            log.exception("Report runner loop crashed")
            raise

    async def _process_one(self, raw: Any) -> None:
        """Consume + compute + publish for a single record, then commit."""
        job_id = "unknown"
        report_key = "unknown"
        method = "volume"
        tenant_id = "unknown"
        try:
            event = ReportJobRequestedEvent(**raw) if isinstance(raw, dict) else raw
            job_id = event.job_id
            tenant_id = event.tenant_id
            report_key = event.report_key
            method = str(event.params.get("ldfMethod", "volume"))
            completed = await self._dispatch(event)
            await self._publish(completed)
        except Exception as exc:  # noqa: BLE001
            log.exception("Report job %s failed: %s", job_id, exc)
            failed = ReportJobCompletedEvent(
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

    async def _dispatch(self, event: ReportJobRequestedEvent) -> ReportJobCompletedEvent:
        method = str(event.params.get("ldfMethod", "volume"))
        if event.report_key in _TRIANGLE_KEYS:
            if event.triangle is None:
                raise ValueError(
                    f"report_key={event.report_key} requires a triangle payload"
                )
            result = self._compute_fn(event.triangle, method)
            return ReportJobCompletedEvent(
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
            if event.report_key == "LAPSE_STUDY":
                cohort_result = compute_lapse(event.cohort)
            else:
                cohort_result = compute_persistency(event.cohort)
            return ReportJobCompletedEvent(
                job_id=event.job_id,
                tenant_id=event.tenant_id,
                report_key=event.report_key,
                status="completed",
                result_json=cohort_result.model_dump(),
                model_version=MODEL_VERSION,
                method=method,
                computed_at=_now_iso(),
            )
        if event.report_key in _EXPOSURE_KEYS:
            if event.exposure is None:
                raise ValueError(
                    f"report_key={event.report_key} requires an exposure payload"
                )
            if event.report_key == "MORBIDITY_STUDY":
                exposure_result = compute_morbidity(event.exposure)
            else:
                exposure_result = compute_mortality(event.exposure)
            return ReportJobCompletedEvent(
                job_id=event.job_id,
                tenant_id=event.tenant_id,
                report_key=event.report_key,
                status="completed",
                result_json=exposure_result.model_dump(),
                model_version=MODEL_VERSION,
                method=method,
                computed_at=_now_iso(),
            )
        if event.report_key in _IFRS17_KEYS:
            return _ifrs17_dispatch(event)
        raise NotImplementedError(
            f"report_key={event.report_key} not yet wired"
        )

    async def _publish(self, event: ReportJobCompletedEvent) -> None:
        # Phase 15 §22 cutover: publish to the canonical topic only.
        # Legacy medfund.actuarial.job-completed dual-write dropped;
        # §23 deletes the legacy topic.
        assert self.producer is not None
        payload = event.model_dump()
        _guard_size(payload)
        await self.producer.send_and_wait(TOPIC_COMPLETED, payload)


def _ifrs17_dispatch(event: ReportJobRequestedEvent) -> ReportJobCompletedEvent:
    """Route an IFRS 17 chunk event to the compute path chosen by the
    ``IFRS17_MODEL`` rule (§9). PAA lands in §11; GMM (§12+) and VFA (§16)
    return an explicit failed envelope until their compute modules land.

    Input dict resolution: prefer ``event.ifrs17_json`` when populated;
    fall back to a MinIO download when ``event.payload_ref`` is set (§10
    oversize path). ``measurement_model`` is a top-level discriminator in
    the dict — the Java shaping service (§17) inserts it before publish.
    """
    if event.payload_ref:
        input_bytes = minio_payload.download(event.payload_ref)
        input_dict = json.loads(input_bytes)
    else:
        input_dict = event.ifrs17_json or {}

    model = input_dict.get("measurement_model")
    if model == "PAA":
        chunk = paa.compute_from_dict(input_dict)
        method = "PAA"
        model_version = paa.MODEL_VERSION
    elif model == "GMM":
        chunk = gmm.compute_from_dict(input_dict)
        method = "GMM"
        model_version = gmm.MODEL_VERSION
    elif model == "VFA":
        chunk = vfa.compute_from_dict(input_dict)
        method = "VFA"
        model_version = vfa.MODEL_VERSION
    else:
        return _ifrs17_failed(
            event, f"Unknown or missing measurement_model: {model!r}"
        )

    # Serialize through mode='json' so nested Decimals render as strings;
    # otherwise the aiokafka producer's json.dumps blows up on the outer
    # envelope. Same pattern the LrcMovement/LicMovement tests pin.
    result_dict = chunk.model_dump(mode="json")
    result_bytes = json.dumps(result_dict).encode("utf-8")
    payload_ref: str | None = None
    if len(result_bytes) > REJECT_KB * 1024:
        # I25 fallback — offload oversize result to MinIO; envelope carries
        # the ref instead. The Java aggregator (§18) resolves it back on
        # arrival. parent_job_id/job_id combo produces the ref key that §10
        # documents; parent falls back to job_id if the event predates §17.
        parent_ref = event.parent_job_id or event.job_id
        payload_ref = minio_payload.maybe_upload_result(
            parent_ref, event.job_id, result_bytes
        )
        result_dict = None

    return ReportJobCompletedEvent(
        job_id=event.job_id,
        tenant_id=event.tenant_id,
        report_key=event.report_key,
        status="completed",
        result_json=result_dict,
        payload_ref=payload_ref,
        model_version=model_version,
        method=method,
        computed_at=_now_iso(),
    )


def _ifrs17_failed(
    event: ReportJobRequestedEvent, error_message: str
) -> ReportJobCompletedEvent:
    return ReportJobCompletedEvent(
        job_id=event.job_id,
        tenant_id=event.tenant_id,
        report_key=event.report_key,
        status="failed",
        error_message=error_message,
        model_version=MODEL_VERSION,
        method="IFRS17",
        computed_at=_now_iso(),
    )


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
    "ReportJobRunner",
    "MODEL_VERSION",
    "PayloadTooLargeError",
    "REJECT_KB",
    "WARN_KB",
]
