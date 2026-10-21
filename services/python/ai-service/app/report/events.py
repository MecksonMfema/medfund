"""Kafka event payloads for the async report job pipeline (Phase 15 §1).

Renamed from ``app/actuarial/events.py`` — the shape is the superset of the
Phase-14 actuarial envelopes plus three additive fields for Phase 15:

* ``parent_job_id`` — links a chunk row to its parent report job (IFRS 17
  aggregator pattern, §18 + IBNR sub-job orchestrator §24).
* ``ifrs17_json`` — IFRS 17 chunk payload slot (§11+ compute reads this).
* ``payload_ref`` — MinIO fallback ref for oversize payloads (§10). Either
  the map fields OR ``payload_ref`` is populated, never both.

Additive-only change; ``schema_version`` stays at 1. Pydantic's default
``extra='ignore'`` means old-shape payloads decode cleanly into the new class
and vice-versa.

The requested envelope is a discriminated union in shape only — the
``triangle`` / ``exposure`` / ``cohort`` / ``ifrs17_json`` slots are
report-key specific and mutually exclusive. Phase 14 populated the first
three; Phases 15 §11+ populate ``ifrs17_json``.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.actuarial.chain_ladder import TriangleInput

SCHEMA_VERSION = 1

TOPIC_REQUESTED = "medfund.report.job-requested"
TOPIC_COMPLETED = "medfund.report.job-completed"

# Consumer group stays on the pre-rename identifier so the running rollup
# doesn't reset to offset-0 on deploy — Kafka treats a rename as a new
# group otherwise, and would replay the whole topic.
CONSUMER_GROUP = "ai-service-actuarial"


class ReportJobRequestedEvent(BaseModel):
    """Payload consumed from the canonical job-requested topic.

    Exactly one of ``triangle`` / ``exposure`` / ``cohort`` / ``ifrs17_json``
    is populated per ``report_key``; the runner routes on ``report_key`` and
    expects the matching branch to be present.
    """

    schema_version: int = Field(default=SCHEMA_VERSION)
    job_id: str
    tenant_id: str
    parent_job_id: str | None = None
    report_key: str
    params: dict[str, Any] = Field(default_factory=dict)
    triangle: TriangleInput | None = None
    exposure: dict[str, Any] | None = None
    cohort: dict[str, Any] | None = None
    ifrs17_json: dict[str, Any] | None = None
    payload_ref: str | None = None
    requested_by: str
    requested_by_email: str


class ReportJobCompletedEvent(BaseModel):
    """Payload published to the canonical job-completed topic.

    ``status`` is ``completed`` on success and ``failed`` on error.
    ``model_version`` stamps the compute-side library version so Rule 3
    audit trails record what produced the numbers. ``payload_ref`` is
    populated when the result is offloaded to MinIO (§10) instead of
    riding the Kafka payload.
    """

    schema_version: int = Field(default=SCHEMA_VERSION)
    job_id: str
    tenant_id: str
    report_key: str
    status: str
    result_json: dict[str, Any] | None = None
    payload_ref: str | None = None
    error_message: str | None = None
    model_version: str
    method: str
    basis: dict[str, Any] | None = None
    computed_at: str
