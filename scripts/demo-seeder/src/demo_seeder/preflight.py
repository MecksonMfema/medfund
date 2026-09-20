"""Service reachability + prerequisites pre-flight for the seeder.

GETs `/actuator/health` on every Java service the seeder will call and
`/health` on the AI service; probes Keycloak's `medfund-platform` realm.
Prints a fixed-width table with pass / fail per row. Returns True when
every critical dependency is reachable.

Called from the CLI at the start of `seed` (fail fast) and available as
`demo-seeder preflight` for standalone inspection.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

import click
import httpx
import structlog

from .config import ServiceEndpoints

log = structlog.get_logger()


AI_SERVICE_URL_ENV = "SEED_AI_SERVICE_URL"
AI_SERVICE_URL_DEFAULT = "http://localhost:8000"


@dataclass
class Probe:
    name: str
    url: str
    ok: bool
    detail: str
    critical: bool = True


async def _probe(
    http: httpx.AsyncClient, name: str, url: str, *, critical: bool = True
) -> Probe:
    try:
        resp = await http.get(url, timeout=5.0)
    except httpx.HTTPError as e:
        return Probe(name=name, url=url, ok=False, detail=str(e)[:80], critical=critical)
    if resp.status_code >= 400:
        return Probe(
            name=name,
            url=url,
            ok=False,
            detail=f"HTTP {resp.status_code}",
            critical=critical,
        )
    # Spring Boot actuator returns {"status": "UP"} — check it explicitly so
    # a service booting under DOWN state doesn't slip through.
    body = None
    try:
        body = resp.json()
    except Exception:  # noqa: BLE001 — non-JSON /health is fine for FastAPI
        pass
    if isinstance(body, dict):
        status = body.get("status")
        if isinstance(status, str) and status.upper() not in ("UP", "OK", "HEALTHY"):
            return Probe(
                name=name,
                url=url,
                ok=False,
                detail=f"status={status}",
                critical=critical,
            )
    return Probe(name=name, url=url, ok=True, detail="UP", critical=critical)


async def run_preflight(endpoints: ServiceEndpoints) -> list[Probe]:
    """Concurrent probe of every service the seeder needs."""
    ai_service_url = os.environ.get(AI_SERVICE_URL_ENV, AI_SERVICE_URL_DEFAULT)
    java_probes: list[tuple[str, str]] = [
        ("tenancy-service", f"{endpoints.tenancy}/actuator/health"),
        ("user-service", f"{endpoints.user}/actuator/health"),
        ("contributions-service", f"{endpoints.contributions}/actuator/health"),
        ("claims-service", f"{endpoints.claims}/actuator/health"),
        ("finance-service", f"{endpoints.finance}/actuator/health"),
        ("rules-engine", f"{endpoints.rules}/actuator/health"),
    ]
    keycloak_url = (
        f"{endpoints.keycloak}/realms/medfund-platform/.well-known/openid-configuration"
    )

    async with httpx.AsyncClient() as http:
        probes: list[Probe] = []
        # Keycloak platform realm is a hard prerequisite; without it the token
        # fetch fails and nothing else works.
        probes.append(await _probe(http, "keycloak (medfund-platform)", keycloak_url))
        for name, url in java_probes:
            probes.append(await _probe(http, name, url))
        # AI service is non-critical when Phase 6 has AI stubbed; still probed
        # so operators know its status. Mark as advisory.
        probes.append(
            await _probe(
                http,
                "ai-service",
                f"{ai_service_url}/health",
                critical=False,
            )
        )
    return probes


def echo_probes(probes: list[Probe]) -> bool:
    """Print a colored table; return True iff every critical probe is UP."""
    header = f"{'Service':<28} {'Status':<8} {'Detail'}"
    click.echo(header)
    click.echo("-" * 78)
    critical_failed = False
    for p in probes:
        status = "UP" if p.ok else "DOWN"
        color = "green" if p.ok else ("red" if p.critical else "yellow")
        prefix = "  " if p.critical else "  "
        suffix = "" if p.critical else "  (non-critical)"
        click.secho(
            f"{prefix}{p.name:<26} {status:<8} {p.detail}{suffix}",
            fg=color,
        )
        if not p.ok and p.critical:
            critical_failed = True
    click.echo()
    if critical_failed:
        click.secho("→ Preflight FAILED — critical service unreachable.", fg="red", bold=True)
        return False
    click.secho("→ Preflight OK.", fg="green", bold=True)
    return True
