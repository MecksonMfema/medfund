"""Schema tests for actuarial Kafka payloads.

Pins the wire shape of ``ActuarialJobRequestedEvent`` +
``ActuarialJobCompletedEvent`` so that any future field addition stays
additive (schema_version defaults to 1) and that the requested envelope
still round-trips the triangle branch cleanly.
"""

from __future__ import annotations

import json

import pytest

from app.actuarial.chain_ladder import TriangleInput
from app.actuarial.events import (
    CONSUMER_GROUP,
    SCHEMA_VERSION,
    TOPIC_COMPLETED,
    TOPIC_REQUESTED,
    ActuarialJobCompletedEvent,
    ActuarialJobRequestedEvent,
)


def _sample_triangle() -> TriangleInput:
    return TriangleInput(
        accident_periods=["2020Q1", "2020Q2"],
        development_periods=["1", "2"],
        cells=[[100.0, 150.0], [110.0, None]],
        grain="quarter",
        reporting_currency="USD",
        insurance_line="HEALTH",
    )


def test_topic_constants_match_plan():
    assert TOPIC_REQUESTED == "medfund.actuarial.job-requested"
    assert TOPIC_COMPLETED == "medfund.actuarial.job-completed"
    assert CONSUMER_GROUP == "ai-service-actuarial"
    assert SCHEMA_VERSION == 1


def test_requested_defaults_schema_version_to_one():
    event = ActuarialJobRequestedEvent(
        job_id="job-1",
        tenant_id="tenant-a",
        report_key="IBNR_TRIANGLE",
        triangle=_sample_triangle(),
        requested_by="user-1",
        requested_by_email="a@b.co",
    )
    assert event.schema_version == 1
    assert event.params == {}
    assert event.exposure is None
    assert event.cohort is None


def test_requested_roundtrips_through_json():
    event = ActuarialJobRequestedEvent(
        job_id="job-2",
        tenant_id="tenant-a",
        report_key="IBNR_TRIANGLE",
        params={"ldfMethod": "5yr"},
        triangle=_sample_triangle(),
        requested_by="user-1",
        requested_by_email="a@b.co",
    )
    payload = json.loads(event.model_dump_json())
    restored = ActuarialJobRequestedEvent(**payload)
    assert restored.triangle is not None
    assert restored.triangle.accident_periods == ["2020Q1", "2020Q2"]
    assert restored.params["ldfMethod"] == "5yr"


def test_completed_requires_model_version_and_method():
    with pytest.raises(Exception):
        ActuarialJobCompletedEvent(
            job_id="job-1",
            tenant_id="tenant-a",
            report_key="IBNR_TRIANGLE",
            status="completed",
            computed_at="2026-08-26T00:00:00+00:00",
        )  # type: ignore[call-arg]


def test_completed_success_shape():
    event = ActuarialJobCompletedEvent(
        job_id="job-1",
        tenant_id="tenant-a",
        report_key="IBNR_TRIANGLE",
        status="completed",
        result_json={"ldfs": [1.2]},
        model_version="chainladder-python:0.8.19",
        method="volume",
        computed_at="2026-08-26T00:00:00+00:00",
    )
    payload = event.model_dump()
    assert payload["status"] == "completed"
    assert payload["result_json"] == {"ldfs": [1.2]}
    assert payload["error_message"] is None
    assert payload["schema_version"] == 1


def test_completed_failed_shape():
    event = ActuarialJobCompletedEvent(
        job_id="job-1",
        tenant_id="tenant-a",
        report_key="IBNR_TRIANGLE",
        status="failed",
        error_message="ZeroDivisionError: cohort_size was 0",
        model_version="chainladder-python:0.8.19",
        method="volume",
        computed_at="2026-08-26T00:00:00+00:00",
    )
    payload = event.model_dump()
    assert payload["result_json"] is None
    assert payload["error_message"].startswith("ZeroDivisionError")
