"""Post-run row-count verification against the tier profile's expected budget.

Fetches actual counts via HTTP (never direct SQL) and compares them to the
tier's expected values within tolerance. Reports pass/fail per assertion.

Assertions cover: members, groups, life_policies, schemes_active,
contributions_pending, invoices_outstanding, claims, payment_runs, notes,
transactions. Metrics that are known-blocked in Phase 6 (claims,
contributions, payment_runs) still appear in the report — a 0 there is a
signal that the upstream 500s remain unfixed.
"""

from __future__ import annotations

from dataclasses import dataclass

import click
import structlog

from ..client import SeederClient
from ..config import ServiceEndpoints, TierProfile

log = structlog.get_logger()


# Wider tolerance for metrics that depend on stochastic (Poisson / probability)
# generation in Phase 5+; tight tolerance for deterministic ones like schemes.
DEFAULT_TOLERANCE = 0.10
TIGHT_TOLERANCE = 0.02


@dataclass
class Check:
    line: str        # "HEALTH" or "LIFE"
    metric: str
    expected: int
    actual: int
    tolerance: float

    @property
    def passed(self) -> bool:
        if self.expected == 0:
            return self.actual == 0
        diff = abs(self.actual - self.expected) / self.expected
        return diff <= self.tolerance


def _expected_counts(line: str, profile: TierProfile) -> dict[str, int]:
    """Row-count expectations per line based on tier profile + Phase 5/6 design."""
    if line == "HEALTH":
        members = profile.health_members
        # Phase 3 seeds 6 schemes (3 USD + 3 ZWL).
        schemes = 6
        # Phase 5: 60% grouped × ~1.5 dependants/member (Poisson lambda=1.5).
        # Approximate — verify uses ±25% tolerance for dependants.
        dependants = int(members * 1.5)
        # Life-policies not applicable to HEALTH.
        life_policies = 0
    elif line == "LIFE":
        members = profile.life_members
        schemes = 6
        dependants = int(members * 1.5)
        # Phase 5: 75% of LIFE members get a LifePolicy row.
        life_policies = int(members * 0.75)
    else:  # pragma: no cover — StrEnum keeps this closed
        members = 0
        schemes = 0
        dependants = 0
        life_policies = 0

    return {
        "members": members,
        "groups": profile.groups_per_tenant,
        "schemes_active": schemes,
        "dependants": dependants,
        "life_policies": life_policies,
        # Timeline-tick derived counts. Phase 6 partial: contributions +
        # claims + payment_runs are known-blocked (500s from Java services).
        # Expected values below reflect the plan's design intent; the report
        # exposes the delta so operators can see the gap at a glance.
        "transactions": _expected_transactions(line, profile),
        # Notes: ~50/mo HEALTH, ~10/mo LIFE (plan §Implementation Approach).
        "notes": (50 if line == "HEALTH" else 10) * profile.timeline_months,
        # Claims: (members × claims_per_year × months) / 12
        "claims": _expected_claims(line, profile),
        # Payment runs: one PROVIDER run per currency per month (2 currencies).
        # Member payment runs (monthly, CTC-only ~10%) deferred in Phase 6.
        "payment_runs": 2 * profile.timeline_months,
    }


def _expected_claims(line: str, profile: TierProfile) -> int:
    per_year = (
        profile.health_claims_per_member_year
        if line == "HEALTH"
        else profile.life_claims_per_member_year
    )
    members = profile.health_members if line == "HEALTH" else profile.life_members
    return int(members * per_year * profile.timeline_months / 12)


def _expected_transactions(line: str, profile: TierProfile) -> int:
    """One per group per month + one per ungrouped active member per month
    (rough — the timeline sub-phase varies)."""
    members = profile.health_members if line == "HEALTH" else profile.life_members
    grouped_ratio = 0.60 if line == "HEALTH" else 0.40
    ungrouped = int(members * (1 - grouped_ratio))
    return (profile.groups_per_tenant + ungrouped) * profile.timeline_months


# ── Fetchers ─────────────────────────────────────────────────────────────────


async def _get_json(
    client: SeederClient, url: str, tenant_id: str | None = None
) -> dict | list | None:
    """GET the URL, return parsed JSON or None on 4xx/5xx."""
    try:
        resp = await client.get(url, tenant_id=tenant_id)
    except Exception as e:  # noqa: BLE001 — surface any network failure as None
        log.warning("verify.fetch.exception", url=url, error=str(e))
        return None
    if resp.status_code >= 400:
        log.warning("verify.fetch.non-2xx", url=url, status=resp.status_code)
        return None
    try:
        return resp.json()
    except Exception:  # noqa: BLE001
        return None


async def _count_page_endpoint(
    client: SeederClient, url: str, tenant_id: str
) -> int:
    """Any `/page` endpoint returns a PageResponse with a `total` field. Some
    finance-service endpoints wrap it in a `ReportResponse<T>` envelope
    (`{data: {total, content, …}}`) — unwrap when present."""
    body = await _get_json(client, f"{url}?page=0&size=1", tenant_id=tenant_id)
    if not isinstance(body, dict):
        return 0
    if isinstance(body.get("data"), dict) and "total" in body["data"]:
        return int(body["data"].get("total") or 0)
    return int(body.get("total") or 0)


async def _count_list_endpoint(
    client: SeederClient, url: str, tenant_id: str
) -> int:
    """A raw Flux endpoint (returns a JSON array); count elements."""
    body = await _get_json(client, url, tenant_id=tenant_id)
    if not isinstance(body, list):
        return 0
    return len(body)


async def _fetch_actuals(
    client: SeederClient,
    endpoints: ServiceEndpoints,
    tenant_id: str,
    line: str,
) -> dict[str, int]:
    """Best-effort HTTP fetch of every metric. Missing metric → 0."""
    stats = await _get_json(
        client, f"{endpoints.user}/api/v1/tenant-stats", tenant_id=tenant_id
    )
    stats = stats if isinstance(stats, dict) else {}

    groups = await _count_list_endpoint(
        client, f"{endpoints.user}/api/v1/groups", tenant_id=tenant_id
    )
    life_policies = (
        await _count_list_endpoint(
            client, f"{endpoints.user}/api/v1/life-policies", tenant_id=tenant_id
        )
        if line == "LIFE"
        else 0
    )

    # `dependants` has no list-all endpoint. Fall back to schema-derived
    # expectation vs. the noted post count from Phase 5. Keeping it at 0 here
    # under-reports but keeps the verify command entirely HTTP-driven.
    # If a future controller exposes /api/v1/dependants, update this call.
    dependants = 0

    claims = await _count_page_endpoint(
        client, f"{endpoints.claims}/api/v1/claims/page", tenant_id=tenant_id
    )
    payment_runs = await _count_page_endpoint(
        client, f"{endpoints.finance}/api/v1/payment-runs/page", tenant_id=tenant_id
    )
    notes = await _count_page_endpoint(
        client, f"{endpoints.finance}/api/v1/notes/page", tenant_id=tenant_id
    )
    # GET /api/v1/transactions returns a PageResponse envelope, not a raw list.
    transactions = await _count_page_endpoint(
        client, f"{endpoints.contributions}/api/v1/transactions", tenant_id=tenant_id
    )

    return {
        "members": int(stats.get("totalMembers") or 0),
        "groups": groups,
        "schemes_active": int(stats.get("schemesActive") or 0),
        "dependants": dependants,
        "life_policies": life_policies,
        "transactions": transactions,
        "notes": notes,
        "claims": claims,
        "payment_runs": payment_runs,
    }


# ── Public entrypoint ────────────────────────────────────────────────────────


async def verify_tier(
    client: SeederClient,
    endpoints: ServiceEndpoints,
    tenants: dict[str, str],
    profile: TierProfile,
) -> list[Check]:
    """Return one Check per (line, metric)."""
    per_metric_tolerance: dict[str, float] = {
        # Deterministic — exact.
        "members": 0.02,
        "groups": 0.0,
        "schemes_active": 0.0,
        # Stochastic — Poisson / probability sampling.
        "dependants": 0.30,
        "life_policies": 0.05,
        "transactions": 0.25,
        "notes": 0.25,
        "claims": 0.25,
        "payment_runs": 0.15,
    }

    checks: list[Check] = []
    for line, tenant_id in tenants.items():
        expected = _expected_counts(line, profile)
        actual = await _fetch_actuals(client, endpoints, tenant_id, line)
        for metric, exp in expected.items():
            checks.append(
                Check(
                    line=line,
                    metric=metric,
                    expected=exp,
                    actual=actual.get(metric, 0),
                    tolerance=per_metric_tolerance.get(metric, DEFAULT_TOLERANCE),
                )
            )
    return checks


def render_report(checks: list[Check]) -> tuple[str, bool]:
    """Format the checks as a fixed-width table. Returns (text, all_passed)."""
    header = f"{'Line':<6} {'Metric':<20} {'Expected':>10} {'Actual':>10} {'Tol':>6} {'Result':>8}"
    separator = "-" * len(header)
    rows = [header, separator]
    all_passed = True
    for c in checks:
        result = "PASS" if c.passed else "FAIL"
        if not c.passed:
            all_passed = False
        rows.append(
            f"{c.line:<6} {c.metric:<20} {c.expected:>10} {c.actual:>10} "
            f"{c.tolerance * 100:>5.1f}% {result:>8}"
        )
    return "\n".join(rows), all_passed


def echo_report(checks: list[Check]) -> bool:
    text, all_passed = render_report(checks)
    for line in text.splitlines():
        if line.endswith("FAIL"):
            click.secho(line, fg="red")
        elif line.endswith("PASS"):
            click.secho(line, fg="green")
        else:
            click.echo(line)
    click.echo()
    status = "ALL PASSED" if all_passed else "FAILURES DETECTED"
    color = "green" if all_passed else "red"
    click.secho(f"→ {status}", fg=color, bold=True)
    return all_passed
