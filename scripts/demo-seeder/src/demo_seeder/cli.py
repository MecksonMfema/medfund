"""Demo-seeder CLI."""

from __future__ import annotations

import asyncio
import os
import random
import sys

import click
import httpx
import structlog
from faker import Faker

from .auth import KeycloakTokenClient
from .client import SeederClient
from .config import TIER_PROFILES, ServiceEndpoints, Tier
from .phases.enrollment import run_enrollment
from .phases.fx_rates import seed_fx_rates
from .phases.keycloak_users import seed_realm_users
from .phases.providers import seed_providers
from .phases.rules import seed_rules
from .phases.schemes import seed_schemes
from .phases.tenants import create_tenants
from .phases.timeline import run_timeline
from .phases.verify import echo_report, verify_tier
from .preflight import echo_probes, run_preflight

log = structlog.get_logger()


def _slug_to_tenant_id(tenants_by_line: dict[str, str]) -> dict[str, str]:
    """Map tenant slug (`health-first`, `life-first`) → tenant UUID."""
    line_to_slug = {"HEALTH": "health-first", "LIFE": "life-first"}
    return {line_to_slug[line]: tid for line, tid in tenants_by_line.items() if line in line_to_slug}


def _abort_if_not_enabled() -> None:
    if os.environ.get("SEED_MODE_ENABLED", "").lower() != "true":
        click.echo(
            "ERROR: SEED_MODE_ENABLED=true must be set. Aborting.",
            err=True,
        )
        sys.exit(2)


@click.group()
def main() -> None:
    """InsureFlow demo-data seeder."""
    _abort_if_not_enabled()


@main.command()
def ping() -> None:
    """Fetch a token and GET /api/v1/tenants to confirm auth + service reachability."""

    async def _run() -> None:
        endpoints = ServiceEndpoints.from_env()
        tokens = KeycloakTokenClient(keycloak_url=endpoints.keycloak)
        async with httpx.AsyncClient(timeout=30) as http:
            client = SeederClient(http, tokens)
            resp = await client.get(f"{endpoints.tenancy}/api/v1/tenants")
            resp.raise_for_status()
            body = resp.json()
            log.info("seeder.ping.ok", status=resp.status_code, count=len(body) if isinstance(body, list) else "n/a")
            click.echo(f"Token OK. GET /api/v1/tenants -> {resp.status_code}. Body: {body}")

    asyncio.run(_run())


@main.command()
@click.option(
    "--tier",
    type=click.Choice([t.value for t in Tier]),
    required=True,
    help="Row-count / months profile.",
)
@click.option("--seed", type=int, default=42, help="RNG seed for reproducibility.")
def seed(tier: str, seed: int) -> None:
    """Seed the platform with demo data for the two tenants."""
    profile = TIER_PROFILES[Tier(tier)]
    rng = random.Random(seed)
    faker = Faker()
    Faker.seed(seed)

    async def _run() -> None:
        endpoints = ServiceEndpoints.from_env()

        # Preflight — fail fast when a Java service or Keycloak is down; the
        # seeder assumes all six services + the AI service are natively running.
        click.echo("── Preflight ─────────────────────────────────────────────")
        probes = await run_preflight(endpoints)
        if not echo_probes(probes):
            click.echo()
            click.secho(
                "Aborting seed. Start missing services and retry.",
                fg="red",
                bold=True,
            )
            sys.exit(3)
        click.echo()

        tokens = KeycloakTokenClient(keycloak_url=endpoints.keycloak)
        async with httpx.AsyncClient(timeout=60) as http:
            client = SeederClient(http, tokens)

            log.info("seeder.phase2.start", tier=tier, months=profile.timeline_months)

            tenants = await create_tenants(client, endpoints.tenancy)
            log.info("seeder.phase2.tenants", tenants=tenants)

            fx_posted = await seed_fx_rates(
                client,
                endpoints.tenancy,
                profile.timeline_months,
                rng,
                # TenantWebFilter requires an X-Tenant-ID on /exchange-rates even
                # though the stored row is platform-wide (body.tenantId=null).
                header_tenant_id=tenants["HEALTH"],
            )
            log.info("seeder.phase2.fx", posted=fx_posted)

            providers_posted = await seed_providers(
                client,
                endpoints.user,
                profile.health_providers,
                profile.life_providers,
                rng,
                faker,
                # Providers are platform-scoped; these are the tenants each one
                # gets a public.provider_tenants membership row for.
                tenant_ids=list(tenants.values()),
            )
            log.info("seeder.phase2.providers", posted=providers_posted)

            log.info("seeder.phase2.done", tenants=tenants)

            log.info("seeder.phase3.start")
            rules_posted = await seed_rules(client, endpoints.rules, tenants)
            log.info("seeder.phase3.rules", posted=rules_posted)

            schemes_created = await seed_schemes(
                client, endpoints.contributions, endpoints.claims, tenants, profile
            )
            log.info("seeder.phase3.schemes", created=schemes_created)
            log.info("seeder.phase3.done")

            log.info("seeder.phase4.start")
            slug_to_id = _slug_to_tenant_id(tenants)
            # Tenant-realm admin ops require the master realm's admin token —
            # platform superadmin only sees medfund-platform.
            master_tokens = KeycloakTokenClient(
                keycloak_url=endpoints.keycloak,
                realm="master",
                client_id="admin-cli",
                username="admin",
                password="admin",
            )
            kc_created = await seed_realm_users(
                http, endpoints.keycloak, master_tokens, slug_to_id
            )
            log.info("seeder.phase4.done", staff_created=kc_created)

            log.info("seeder.phase5.start")
            enrollment_summary = await run_enrollment(
                client,
                endpoints.user,
                endpoints.contributions,
                tenants,
                profile,
                rng,
                faker,
            )
            log.info("seeder.phase5.done", summary=enrollment_summary)

            log.info("seeder.phase6.start")
            timeline_summary = await run_timeline(
                client, endpoints, tenants, profile, rng, faker
            )
            log.info("seeder.phase6.done", summary=timeline_summary)

            # Phase 7 — post-seed row-count verification. Prints a report but
            # never fails the seed run; blocked-endpoint metrics are expected
            # to trip until the upstream 500s are fixed.
            click.echo()
            click.echo("── Verification ──────────────────────────────────────────")
            checks = await verify_tier(client, endpoints, tenants, profile)
            echo_report(checks)

    asyncio.run(_run())


@main.command()
@click.option(
    "--tier",
    type=click.Choice([t.value for t in Tier]),
    required=True,
    help="Tier the run was seeded at.",
)
def verify(tier: str) -> None:
    """Post-run row-count verification against the tier's expected budget.

    Exits 0 when every assertion passes within tolerance; 1 otherwise.
    """
    profile = TIER_PROFILES[Tier(tier)]

    async def _run() -> None:
        endpoints = ServiceEndpoints.from_env()
        tokens = KeycloakTokenClient(keycloak_url=endpoints.keycloak)
        async with httpx.AsyncClient(timeout=30) as http:
            client = SeederClient(http, tokens)
            # `create_tenants` is idempotent by slug — reuse it to resolve
            # existing tenant UUIDs without a separate lookup helper.
            tenants = await create_tenants(client, endpoints.tenancy)
            checks = await verify_tier(client, endpoints, tenants, profile)
            all_passed = echo_report(checks)
            sys.exit(0 if all_passed else 1)

    asyncio.run(_run())


@main.command()
def preflight() -> None:
    """Probe every Java service, Keycloak, and the AI service. Exit non-zero on failure."""

    async def _run() -> None:
        endpoints = ServiceEndpoints.from_env()
        probes = await run_preflight(endpoints)
        ok = echo_probes(probes)
        sys.exit(0 if ok else 3)

    asyncio.run(_run())


if __name__ == "__main__":
    main()
