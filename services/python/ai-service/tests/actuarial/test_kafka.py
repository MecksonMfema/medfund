"""Unit tests for the actuarial Kafka runner.

Uses in-memory fake consumer + producer objects (no live broker) so the
consume-compute-publish loop can be exercised deterministically. The
happy-path assertion pins the plan's shape:

* IBNR/LOSS jobs → chain-ladder compute → completed envelope with
  ``status='completed'`` published to ``medfund.report.job-completed``
  (canonical only after Phase 15 §22 cutover).
* Failed compute → completed envelope with ``status='failed'`` +
  ``error_message`` published, offset committed anyway.
* Unknown ``report_key`` → failure envelope.
* Payload exceeding 900KB → PayloadTooLargeError (surfaces as a failed
  envelope through the outer try/except).
"""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest

from app.actuarial.chain_ladder import ChainLadderResult, TriangleInput
from app.report.events import TOPIC_COMPLETED
from app.report.kafka import (
    MODEL_VERSION,
    REJECT_KB,
    ReportJobRunner,
    PayloadTooLargeError,
    _guard_size,
)


# ── in-memory kafka fakes ────────────────────────────────────────────


class FakeMessage:
    def __init__(self, value: Any) -> None:
        self.value = value


class FakeConsumer:
    """Minimal AIOKafkaConsumer stand-in.

    ``messages`` is drained by ``__aiter__``; ``commits`` records every
    ``commit()`` call so tests can assert the ack cadence directly.
    """

    def __init__(self, messages: list[Any]) -> None:
        self._messages = list(messages)
        self.commits = 0
        self.started = False
        self.stopped = False

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    async def commit(self) -> None:
        self.commits += 1

    def __aiter__(self) -> "FakeConsumer":
        return self

    async def __anext__(self) -> FakeMessage:
        if not self._messages:
            raise StopAsyncIteration
        return FakeMessage(self._messages.pop(0))


class FakeProducer:
    def __init__(self, fail_on_send: bool = False) -> None:
        self.sent: list[tuple[str, dict[str, Any]]] = []
        self.started = False
        self.stopped = False
        self._fail = fail_on_send

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True

    async def send_and_wait(self, topic: str, value: dict[str, Any]) -> None:
        if self._fail:
            raise RuntimeError("broker down")
        self.sent.append((topic, value))


# ── fixtures ────────────────────────────────────────────────────────


def _triangle() -> TriangleInput:
    return TriangleInput(
        accident_periods=["2020Q1", "2020Q2"],
        development_periods=["1", "2"],
        cells=[[100.0, 150.0], [110.0, None]],
        grain="quarter",
        reporting_currency="USD",
        insurance_line="HEALTH",
    )


def _requested(**overrides: Any) -> dict[str, Any]:
    base = {
        "schema_version": 1,
        "job_id": "job-1",
        "tenant_id": "tenant-a",
        "report_key": "IBNR_TRIANGLE",
        "params": {"ldfMethod": "volume"},
        "triangle": _triangle().model_dump(),
        "requested_by": "u",
        "requested_by_email": "u@x.co",
    }
    base.update(overrides)
    return base


def _canned_result() -> ChainLadderResult:
    return ChainLadderResult(
        ldfs=[1.5, 1.2],
        cdf=[1.8, 1.2],
        ibnr_total=42.0,
        ultimate_total=210.0,
        mack_standard_error=1.1,
        per_cohort_ultimate=[110.0, 100.0],
    )


def _make_runner(
    messages: list[Any],
    *,
    compute_fn=None,
    fail_producer: bool = False,
) -> tuple[ReportJobRunner, FakeConsumer, FakeProducer]:
    consumer = FakeConsumer(messages)
    producer = FakeProducer(fail_on_send=fail_producer)
    runner = ReportJobRunner(
        bootstrap_servers="unused",
        consumer_factory=lambda: consumer,
        producer_factory=lambda: producer,
        compute_fn=compute_fn or (lambda payload, method: _canned_result()),
    )
    return runner, consumer, producer


async def _drive(runner: ReportJobRunner) -> None:
    """Run runner start/loop/stop with a timeout guard for pytest safety."""
    await runner.start()
    try:
        # runner._task is the loop; wait for it to drain the message queue.
        assert runner._task is not None
        await asyncio.wait_for(runner._task, timeout=2.0)
    except asyncio.TimeoutError:
        pytest.fail("runner loop did not drain in 2s")
    finally:
        await runner.stop()


# ── happy path ──────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_ibnr_happy_path_publishes_completed_and_commits():
    runner, consumer, producer = _make_runner([_requested()])
    await _drive(runner)

    assert consumer.started and consumer.stopped
    assert producer.started and producer.stopped
    assert consumer.commits == 1
    # Phase 15 §22 cutover: publish to canonical topic only; legacy
    # medfund.actuarial.job-completed dual-write dropped.
    assert len(producer.sent) == 1
    topic, payload = producer.sent[0]
    assert topic == TOPIC_COMPLETED
    assert payload["status"] == "completed"
    assert payload["job_id"] == "job-1"
    assert payload["tenant_id"] == "tenant-a"
    assert payload["report_key"] == "IBNR_TRIANGLE"
    assert payload["method"] == "volume"
    assert payload["result_json"]["ldfs"] == [1.5, 1.2]
    assert payload["model_version"] == MODEL_VERSION
    assert payload["computed_at"].endswith("+00:00")


@pytest.mark.asyncio
async def test_loss_triangle_dispatches_via_same_branch():
    runner, _consumer, producer = _make_runner(
        [_requested(report_key="LOSS_TRIANGLE", job_id="job-loss")]
    )
    await _drive(runner)
    assert producer.sent[0][1]["report_key"] == "LOSS_TRIANGLE"
    assert producer.sent[0][1]["status"] == "completed"


@pytest.mark.asyncio
async def test_persistency_study_dispatches_via_cohort_branch():
    body = _requested(
        report_key="PERSISTENCY_STUDY",
        job_id="job-persist",
        triangle=None,
    )
    body["cohort"] = {
        "cohorts": [{
            "cohort_month": "2024-01", "insurance_line": "HEALTH",
            "cohort_size": 100,
            "checkpoints": [{"months": 3, "retained_count": 90}],
        }],
        "expected_basis": {
            "HEALTH": [{"cohort_months": 3, "expected_retention_pct": 0.90}],
        },
    }
    runner, consumer, producer = _make_runner([body])
    await _drive(runner)
    assert consumer.commits == 1
    payload = producer.sent[0][1]
    assert payload["status"] == "completed"
    assert payload["report_key"] == "PERSISTENCY_STUDY"
    per_line = payload["result_json"]["per_line"]
    assert "HEALTH" in per_line and len(per_line["HEALTH"]) == 1
    assert per_line["HEALTH"][0]["ae_ratio"] == pytest.approx(1.0, rel=1e-6)


@pytest.mark.asyncio
async def test_lapse_study_dispatches_via_cohort_branch():
    body = _requested(
        report_key="LAPSE_STUDY",
        job_id="job-lapse",
        triangle=None,
    )
    body["cohort"] = {
        "cohorts": [{
            "cohort_month": "2024-01", "insurance_line": "HEALTH",
            "cohort_size": 100,
            "checkpoints": [{"months": 3, "still_active": 90}],
        }],
        "expected_basis": {
            "HEALTH": [{"cohort_months": 3, "expected_retention_pct": 0.90}],
        },
    }
    runner, consumer, producer = _make_runner([body])
    await _drive(runner)
    assert consumer.commits == 1
    payload = producer.sent[0][1]
    assert payload["status"] == "completed"
    assert payload["report_key"] == "LAPSE_STUDY"
    per_line = payload["result_json"]["per_line"]
    assert "HEALTH" in per_line and len(per_line["HEALTH"]) == 1
    # 10/100 actual lapse vs 0.10 expected lapse → A/E = 1.0.
    assert per_line["HEALTH"][0]["ae_ratio"] == pytest.approx(1.0, rel=1e-6)


@pytest.mark.asyncio
async def test_persistency_missing_cohort_publishes_failed():
    body = _requested(report_key="PERSISTENCY_STUDY", triangle=None)
    body["cohort"] = None
    runner, consumer, producer = _make_runner([body])
    await _drive(runner)
    assert consumer.commits == 1
    envelope = producer.sent[0][1]
    assert envelope["status"] == "failed"
    assert "cohort" in envelope["error_message"]


@pytest.mark.asyncio
async def test_method_from_params_reaches_completed_envelope():
    seen_methods: list[str] = []

    def _spy(_payload, method):
        seen_methods.append(method)
        return _canned_result()

    runner, _c, producer = _make_runner(
        [_requested(params={"ldfMethod": "5yr"})],
        compute_fn=_spy,
    )
    await _drive(runner)
    assert seen_methods == ["5yr"]
    assert producer.sent[0][1]["method"] == "5yr"


# ── failure paths ───────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_compute_error_publishes_failed_and_commits():
    def _boom(_payload, _method):
        raise ValueError("bad cells")

    runner, consumer, producer = _make_runner([_requested()], compute_fn=_boom)
    await _drive(runner)

    assert consumer.commits == 1
    # Phase 15 §22 cutover: failed envelope publishes to canonical only.
    assert len(producer.sent) == 1
    payload = producer.sent[0][1]
    assert payload["status"] == "failed"
    assert payload["error_message"] == "bad cells"
    assert payload["result_json"] is None


@pytest.mark.asyncio
async def test_unknown_report_key_publishes_failed():
    # All six actuarial ReportKeys land through Phases 7-14. This test uses
    # a fabricated unknown key to pin the runner's NotImplementedError
    # branch — it must reject anything outside the wired set with a durable
    # failed envelope, not silently accept.
    runner, consumer, producer = _make_runner(
        [_requested(report_key="NOT_A_REAL_KEY")]
    )
    await _drive(runner)
    assert consumer.commits == 1
    payload = producer.sent[0][1]
    assert payload["status"] == "failed"
    assert "not yet wired" in payload["error_message"]


@pytest.mark.asyncio
async def test_mortality_study_dispatches_via_exposure_branch():
    body = _requested(
        report_key="MORTALITY_STUDY",
        job_id="job-mortality",
        triangle=None,
    )
    body["exposure"] = {
        "cohorts": [{
            "insurance_line": "LIFE",
            "basis_name": "A1949_52",
            "multiplier": 1.0,
            "bands": [
                {"age_band": "30-34", "sex": "male",
                 "exposure_years": 100_000.0, "deaths": 234},
            ],
        }],
    }
    runner, consumer, producer = _make_runner([body])
    await _drive(runner)
    assert consumer.commits == 1
    payload = producer.sent[0][1]
    assert payload["status"] == "completed"
    assert payload["report_key"] == "MORTALITY_STUDY"
    per_line = payload["result_json"]["per_line"]
    assert "LIFE" in per_line
    row = per_line["LIFE"]["rows"][0]
    assert row["ae_ratio"] == pytest.approx(1.0, rel=1e-6)


@pytest.mark.asyncio
async def test_mortality_missing_exposure_publishes_failed():
    body = _requested(report_key="MORTALITY_STUDY", triangle=None)
    body["exposure"] = None
    runner, consumer, producer = _make_runner([body])
    await _drive(runner)
    assert consumer.commits == 1
    envelope = producer.sent[0][1]
    assert envelope["status"] == "failed"
    assert "exposure" in envelope["error_message"]


@pytest.mark.asyncio
async def test_morbidity_study_dispatches_via_exposure_branch():
    body = _requested(
        report_key="MORBIDITY_STUDY",
        job_id="job-morbidity",
        triangle=None,
    )
    body["exposure"] = {
        "cohorts": [{
            "insurance_line": "HEALTH",
            "basis_name": "CIDA",
            "multiplier": 1.0,
            "bands": [
                {"age_band": "30-34", "sex": "male",
                 "exposure_years": 100_000.0, "incidents": 214},
            ],
        }],
    }
    runner, consumer, producer = _make_runner([body])
    await _drive(runner)
    assert consumer.commits == 1
    payload = producer.sent[0][1]
    assert payload["status"] == "completed"
    assert payload["report_key"] == "MORBIDITY_STUDY"
    per_line = payload["result_json"]["per_line"]
    assert "HEALTH" in per_line
    row = per_line["HEALTH"]["rows"][0]
    assert row["ae_ratio"] == pytest.approx(1.0, rel=1e-6)


@pytest.mark.asyncio
async def test_morbidity_missing_exposure_publishes_failed():
    body = _requested(report_key="MORBIDITY_STUDY", triangle=None)
    body["exposure"] = None
    runner, consumer, producer = _make_runner([body])
    await _drive(runner)
    assert consumer.commits == 1
    envelope = producer.sent[0][1]
    assert envelope["status"] == "failed"
    assert "exposure" in envelope["error_message"]


@pytest.mark.asyncio
async def test_missing_triangle_for_triangle_key_fails():
    body = _requested()
    body["triangle"] = None
    runner, consumer, producer = _make_runner([body])
    await _drive(runner)
    assert consumer.commits == 1
    assert producer.sent[0][1]["status"] == "failed"
    assert "triangle" in producer.sent[0][1]["error_message"]


@pytest.mark.asyncio
async def test_malformed_event_publishes_failed_with_unknown_metadata():
    # Missing required fields — pydantic raises; runner catches, publishes
    # failed envelope with placeholder metadata, still commits.
    runner, consumer, producer = _make_runner(
        [{"schema_version": 1, "not_the_right_shape": True}]
    )
    await _drive(runner)
    assert consumer.commits == 1
    envelope = producer.sent[0][1]
    assert envelope["status"] == "failed"
    assert envelope["job_id"] == "unknown"
    assert envelope["tenant_id"] == "unknown"
    assert envelope["report_key"] == "unknown"


@pytest.mark.asyncio
async def test_producer_error_on_failed_publish_still_commits():
    def _boom(_payload, _method):
        raise ValueError("compute broke")

    runner, consumer, producer = _make_runner(
        [_requested()], compute_fn=_boom, fail_producer=True
    )
    await _drive(runner)

    # Compute failed; failure-publish also failed; runner must still commit
    # to prevent an infinite redelivery loop on a permanently-broken record.
    assert consumer.commits == 1
    assert producer.sent == []


# ── size guard ──────────────────────────────────────────────────────


def test_size_guard_rejects_over_ceiling():
    huge = {"blob": "x" * (REJECT_KB * 1024)}
    with pytest.raises(PayloadTooLargeError):
        _guard_size(huge)


def test_size_guard_warns_between_800_and_900_kb(caplog):
    payload = {"blob": "x" * (850 * 1024)}
    with caplog.at_level("WARNING"):
        _guard_size(payload)
    assert any("approaching Kafka limit" in r.message for r in caplog.records)


def test_size_guard_silent_under_800_kb(caplog):
    payload = {"blob": "x" * (100 * 1024)}
    with caplog.at_level("WARNING"):
        _guard_size(payload)
    assert not any("approaching Kafka limit" in r.message for r in caplog.records)


# ── lifecycle ───────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_stop_before_start_is_noop():
    runner = ReportJobRunner(
        bootstrap_servers="unused",
        consumer_factory=lambda: FakeConsumer([]),
        producer_factory=lambda: FakeProducer(),
    )
    # Stop with no prior start — must not raise; nothing to close.
    await runner.stop()
