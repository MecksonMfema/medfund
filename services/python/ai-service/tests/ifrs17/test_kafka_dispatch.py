"""Dispatch-branch coverage for the IFRS 17 wire-up (Phase 15 §11).

The unit compute is exercised by ``test_paa.py`` / ``test_gmm.py`` /
``test_vfa.py``. This suite covers the runner-side glue introduced in
``app.report.kafka``:

* PAA / GMM / VFA events → real chunk_result envelope on the completed
  topic.
* Missing/unknown ``measurement_model`` → failed envelope.
* ``payload_ref``-only event downloads the input from MinIO before
  dispatching.
* Oversize result triggers a MinIO upload; the outer envelope carries the
  ref instead of the result dict.

Runner infrastructure (fake consumer/producer) is copied over from
``tests/actuarial/test_kafka.py`` — the actuarial suite proves the loop
mechanics; here we only need enough to observe the IFRS 17 branch.
"""

from __future__ import annotations

import asyncio
import json
from decimal import Decimal
from typing import Any
from unittest.mock import patch

import pytest

from app.ifrs17 import gmm as gmm_module
from app.ifrs17 import vfa as vfa_module
from app.report.events import TOPIC_COMPLETED, ReportJobRequestedEvent
from app.report.kafka import (
    MODEL_VERSION,
    REJECT_KB,
    ReportJobRunner,
    _ifrs17_dispatch,
)


# ── fakes ────────────────────────────────────────────────────────────


class FakeMessage:
    def __init__(self, value: Any) -> None:
        self.value = value


class FakeConsumer:
    def __init__(self, messages: list[Any]) -> None:
        self._messages = list(messages)
        self.commits = 0

    async def start(self) -> None: ...
    async def stop(self) -> None: ...

    async def commit(self) -> None:
        self.commits += 1

    def __aiter__(self):
        return self

    async def __anext__(self) -> FakeMessage:
        if not self._messages:
            raise StopAsyncIteration
        return FakeMessage(self._messages.pop(0))


class FakeProducer:
    def __init__(self) -> None:
        self.sent: list[tuple[str, dict[str, Any]]] = []

    async def start(self) -> None: ...
    async def stop(self) -> None: ...

    async def send_and_wait(self, topic: str, value: dict[str, Any]) -> None:
        # Round-trip the envelope through json.dumps so a Decimal that
        # escaped mode='json' would fail this test — the aiokafka producer
        # does the same encode on the way to the broker.
        json.dumps(value)
        self.sent.append((topic, value))


# ── payload helpers ──────────────────────────────────────────────────


def _paa_input(**overrides) -> dict[str, Any]:
    base = {
        "measurement_model": "PAA",
        "job_id": "chunk-1",
        "tenant_id": "tenant-a",
        "portfolio_id": "pf-1",
        "cohort_id": "ch-1",
        "currency": "USD",
        "reporting_period_start": "2024-01-01",
        "reporting_period_end": "2024-12-31",
        "earliest_coverage_start": "2024-01-01",
        "latest_coverage_end": "2024-12-31",
        "opening_lrc": "100",
        "opening_lic": "5",
        "earned_in_period": "70",
        "claims_incurred": "12",
        "claims_paid": "9",
    }
    base.update(overrides)
    return base


def _requested(report_key: str = "IFRS17_LRC_LIC_RECONCILIATION",
               ifrs17_json: dict | None = None,
               payload_ref: str | None = None,
               parent_job_id: str | None = "parent-1") -> dict[str, Any]:
    return {
        "schema_version": 1,
        "job_id": "chunk-1",
        "tenant_id": "tenant-a",
        "parent_job_id": parent_job_id,
        "report_key": report_key,
        "params": {},
        "ifrs17_json": ifrs17_json or _paa_input(),
        "payload_ref": payload_ref,
        "requested_by": "u",
        "requested_by_email": "u@x.co",
    }


def _make_runner(messages: list[Any]) -> tuple[ReportJobRunner, FakeConsumer, FakeProducer]:
    consumer = FakeConsumer(messages)
    producer = FakeProducer()
    runner = ReportJobRunner(
        bootstrap_servers="unused",
        consumer_factory=lambda: consumer,
        producer_factory=lambda: producer,
    )
    return runner, consumer, producer


async def _drive(runner: ReportJobRunner) -> None:
    await runner.start()
    try:
        assert runner._task is not None
        await asyncio.wait_for(runner._task, timeout=2.0)
    finally:
        await runner.stop()


# ── PAA happy path ───────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_ifrs17_paa_event_publishes_completed_envelope():
    runner, _consumer, producer = _make_runner([_requested()])
    await _drive(runner)

    # Phase 15 §22 cutover: publish to canonical topic only.
    assert len(producer.sent) == 1
    topic, payload = producer.sent[0]
    assert topic == TOPIC_COMPLETED
    assert payload["status"] == "completed"
    assert payload["report_key"] == "IFRS17_LRC_LIC_RECONCILIATION"
    assert payload["method"] == "PAA"
    assert payload["model_version"] == "ifrs17-paa-1.0"
    result = payload["result_json"]
    assert result["model"] == "PAA"
    # Golden values from the ex3 fixture.
    assert result["lrc_movement"]["closing"] == "30"
    assert result["lic_movement"]["closing"] == "8"
    assert result["discounting_applied"] is False


@pytest.mark.asyncio
async def test_ifrs17_insurance_revenue_report_key_also_routes_to_paa():
    runner, _consumer, producer = _make_runner([
        _requested(report_key="IFRS17_INSURANCE_REVENUE_SERVICE_RESULT")
    ])
    await _drive(runner)
    payload = producer.sent[0][1]
    assert payload["status"] == "completed"
    assert payload["report_key"] == "IFRS17_INSURANCE_REVENUE_SERVICE_RESULT"


# ── model discriminator branches ─────────────────────────────────────


def _gmm_input(**overrides) -> dict[str, Any]:
    """Minimal GMM chunk payload — mirrors the §12 IASB Example 7 shape."""
    base = {
        "measurement_model": "GMM",
        "job_id": "chunk-1",
        "tenant_id": "tenant-a",
        "portfolio_id": "pf-1",
        "cohort_id": "ch-1",
        "currency": "USD",
        "insurance_line": "LIFE",
        "reporting_period_start": "2024-01-01",
        "reporting_period_end": "2024-12-31",
        "horizon_months": 12,
        "avg_age": 40,
        "avg_age_sex": "male",
        "mortality_qx": {40: {"male": "1.80", "female": "1.15"}},
        "premium_per_policy_monthly": "10",
        "avg_sum_insured": "10000",
        "monthly_expense_per_policy": "1",
        "locked_in_curve": [
            {"tenor_months": 1, "spot_rate": "0.05"},
            {"tenor_months": 60, "spot_rate": "0.05"},
        ],
        "opening_lrc": "0",
        "opening_lic": "0",
    }
    base.update(overrides)
    return base


@pytest.mark.asyncio
async def test_ifrs17_gmm_event_publishes_completed_envelope():
    """Phase 12 wires GMM into the dispatch — the runner now emits a real
    ChunkResult envelope instead of the §11 not-implemented stub."""
    runner, _c, producer = _make_runner([
        _requested(ifrs17_json=_gmm_input())
    ])
    await _drive(runner)

    payload = producer.sent[0][1]
    assert payload["status"] == "completed"
    assert payload["method"] == "GMM"
    assert payload["model_version"] == gmm_module.MODEL_VERSION
    result = payload["result_json"]
    assert result["model"] == "GMM"
    assert result["discounting_applied"] is True
    # Projection ladder threads through to the aggregator side.
    assert result["projection"]["horizon_months_used"] == 12
    assert len(result["projection"]["rows"]) == 12


@pytest.mark.asyncio
async def test_vfa_event_dispatches_to_vfa_compute():
    """VFA measurement_model now routes to :mod:`app.ifrs17.vfa` (§16).
    The stub 'not yet implemented' response no longer fires."""
    vfa_payload = {
        "measurement_model": "VFA",
        "job_id": "chunk-vfa",
        "tenant_id": "tenant-a",
        "portfolio_id": "pf-vfa",
        "cohort_id": "ch-vfa",
        "currency": "USD",
        "reporting_period_start": "2024-01-01",
        "reporting_period_end": "2024-12-31",
        "locked_in_curve": [
            {"tenor_months": 1, "spot_rate": "0"},
            {"tenor_months": 60, "spot_rate": "0"},
        ],
        "opening_underlying_fair_value": "1000",
        "closing_underlying_fair_value": "1100",
        "variable_fee_earned": "20",
        "variable_fee_pool_remaining": "100",
        "opening_lrc": "1100",
        "opening_csm": "100",
    }
    runner, _c, producer = _make_runner([_requested(ifrs17_json=vfa_payload)])
    await _drive(runner)
    payload = producer.sent[0][1]
    assert payload["status"] == "completed"
    assert payload["method"] == "VFA"
    assert payload["model_version"] == vfa_module.MODEL_VERSION
    result = payload["result_json"]
    assert result["model"] == "VFA"
    assert result["discounting_applied"] is True
    # LRC closing per fixture steady_growth period 1 hand-verification.
    assert Decimal(result["lrc_movement"]["closing"]) == Decimal("1196")


@pytest.mark.asyncio
async def test_unknown_measurement_model_fails():
    runner, _c, producer = _make_runner([
        _requested(ifrs17_json={**_paa_input(), "measurement_model": "BOGUS"})
    ])
    await _drive(runner)
    payload = producer.sent[0][1]
    assert payload["status"] == "failed"
    assert "Unknown or missing measurement_model" in payload["error_message"]


@pytest.mark.asyncio
async def test_missing_measurement_model_fails():
    payload_dict = _paa_input()
    payload_dict.pop("measurement_model")
    runner, _c, producer = _make_runner([_requested(ifrs17_json=payload_dict)])
    await _drive(runner)
    envelope = producer.sent[0][1]
    assert envelope["status"] == "failed"
    assert "Unknown or missing measurement_model" in envelope["error_message"]


# ── MinIO fallback paths ─────────────────────────────────────────────


@pytest.mark.asyncio
async def test_payload_ref_downloads_input_from_minio():
    input_dict = _paa_input()
    encoded = json.dumps(input_dict).encode("utf-8")

    with patch("app.report.kafka.minio_payload.download", return_value=encoded) as dl:
        runner, _c, producer = _make_runner([
            _requested(
                ifrs17_json=None,
                payload_ref="s3://medfund-report-payloads/parent-1-chunk-1-input.json",
            )
        ])
        await _drive(runner)

    dl.assert_called_once_with("s3://medfund-report-payloads/parent-1-chunk-1-input.json")
    payload = producer.sent[0][1]
    assert payload["status"] == "completed"


@pytest.mark.asyncio
async def test_oversize_result_offloads_to_minio():
    # Force the size guard to trip on any envelope so we exercise the
    # upload branch without fabricating a genuinely huge result.
    oversize_bytes = REJECT_KB * 1024 + 10
    fake_ref = "s3://medfund-report-payloads/parent-1-chunk-1-result.json"

    with patch(
        "app.report.kafka.minio_payload.maybe_upload_result",
        return_value=fake_ref,
    ) as up, patch(
        "app.report.kafka.json.dumps",
        wraps=lambda obj, *a, **kw: "x" * oversize_bytes,
    ):
        event = ReportJobRequestedEvent(**_requested())
        result_event = _ifrs17_dispatch(event)

    up.assert_called_once()
    args = up.call_args.args
    assert args[0] == "parent-1"      # parent_job_id used as the outer ref
    assert args[1] == "chunk-1"       # chunk id as the inner ref
    assert result_event.status == "completed"
    assert result_event.payload_ref == fake_ref
    assert result_event.result_json is None


@pytest.mark.asyncio
async def test_payload_ref_falls_back_to_job_id_when_no_parent():
    # A one-off IFRS 17 event with no parent (defensive path — §17 always
    # sets parent, but a hand-fired dev event might not) still produces a
    # valid MinIO ref keyed on job_id alone.
    oversize_bytes = REJECT_KB * 1024 + 10
    with patch(
        "app.report.kafka.minio_payload.maybe_upload_result",
        return_value="s3://medfund-report-payloads/chunk-1-chunk-1-result.json",
    ) as up, patch(
        "app.report.kafka.json.dumps",
        wraps=lambda obj, *a, **kw: "x" * oversize_bytes,
    ):
        event = ReportJobRequestedEvent(**_requested(parent_job_id=None))
        _ifrs17_dispatch(event)
    args = up.call_args.args
    assert args[0] == "chunk-1"


# ── result serialization ─────────────────────────────────────────────


def test_ifrs17_dispatch_result_is_json_safe():
    """The runner sends result_json through json.dumps; Decimal leakage
    would blow up mid-publish. This dispatch-level guard catches a
    regression in the mode='json' serialization step."""
    event = ReportJobRequestedEvent(**_requested())
    completed = _ifrs17_dispatch(event)
    # If any Decimal leaked, this raises TypeError.
    json.dumps(completed.model_dump())
    assert completed.result_json["lrc_movement"]["closing"] == "30"


# ── malformed payload ────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_malformed_ifrs17_payload_publishes_failed():
    """Malformed input (missing required PaaChunkInput field) surfaces as
    the outer runner catch — the runner publishes a generic 'failed'
    envelope with the pydantic validation message; the commit still
    fires so the record does not redeliver."""
    bad_input = _paa_input()
    bad_input.pop("job_id")
    runner, consumer, producer = _make_runner([_requested(ifrs17_json=bad_input)])
    await _drive(runner)
    assert consumer.commits == 1
    envelope = producer.sent[0][1]
    assert envelope["status"] == "failed"
    assert envelope["model_version"] == MODEL_VERSION
