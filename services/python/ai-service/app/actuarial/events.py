"""Kafka event payloads for the actuarial async job pipeline.

Phase 8 introduces two topics — ``medfund.actuarial.job-requested`` (Java
finance-service → Python ai-service) and ``medfund.actuarial.job-completed``
(Python ai-service → Java finance-service). Both carry a ``schema_version``
so downstream consumers can gate on breaking-change bumps; today both are on
version 1 and any subsequent change must be additive per the plan's
"Kafka contracts" invariant.

The requested envelope is a discriminated union in shape only — the
``triangle`` / ``exposure`` / ``cohort`` slots are report-key specific and
mutually exclusive. Phase 8 populates ``triangle`` for IBNR/LOSS jobs;
Phases 11-14 fill in the other two branches.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.actuarial.chain_ladder import TriangleInput

SCHEMA_VERSION = 1

TOPIC_REQUESTED = "medfund.actuarial.job-requested"
TOPIC_COMPLETED = "medfund.actuarial.job-completed"

CONSUMER_GROUP = "ai-service-actuarial"


class ActuarialJobRequestedEvent(BaseModel):
    """Payload consumed from ``medfund.actuarial.job-requested``.

    Exactly one of ``triangle`` / ``exposure`` / ``cohort`` is populated per
    ``report_key``; the runner routes on ``report_key`` and expects the
    matching branch to be present.
    """

    schema_version: int = Field(default=SCHEMA_VERSION)
    job_id: str
    tenant_id: str
    report_key: str
    params: dict[str, Any] = Field(default_factory=dict)
    triangle: TriangleInput | None = None
    exposure: dict[str, Any] | None = None
    cohort: dict[str, Any] | None = None
    requested_by: str
    requested_by_email: str


class ActuarialJobCompletedEvent(BaseModel):
    """Payload published to ``medfund.actuarial.job-completed``.

    ``status`` is ``completed`` on success and ``failed`` on error. ``model_version``
    stamps the compute-side library version so Rule 3 audit trails record what
    produced the numbers.
    """

    schema_version: int = Field(default=SCHEMA_VERSION)
    job_id: str
    tenant_id: str
    report_key: str
    status: str
    result_json: dict[str, Any] | None = None
    error_message: str | None = None
    model_version: str
    method: str
    basis: dict[str, Any] | None = None
    computed_at: str
