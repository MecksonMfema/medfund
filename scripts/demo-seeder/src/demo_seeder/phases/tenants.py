"""Phase 2 — provision the two demo tenants (HEALTH + LIFE).

POSTs to tenancy-service's `POST /api/v1/tenants`; the service creates the
tenant DB schema, runs Flyway migrations, and creates the per-tenant Keycloak
realm via `KeycloakRealmService.createRealm()`. Idempotent by slug.
"""

from __future__ import annotations

import json

import structlog

from ..client import SeederClient

log = structlog.get_logger()


# HEALTH: country ZW, local currency ZWL. LIFE: country ZA, local currency ZAR.
# Both tenants use USD as the platform default currency so scheme-level currency
# switching still works within a single tenant.
TENANT_PAYLOADS: list[dict] = [
    {
        "name": "Health First Medical",
        "slug": "health-first",
        "domain": "health-first.medfund.example",
        "contactEmail": "admin@health-first.example",
        "countryCode": "ZW",
        "timezone": "Africa/Harare",
        "defaultCurrencyCode": "USD",
        "membershipModel": "BOTH",
        "settings": json.dumps({"insuranceLines": ["HEALTH"]}),
    },
    {
        "name": "Life First Assurance",
        "slug": "life-first",
        "domain": "life-first.medfund.example",
        "contactEmail": "admin@life-first.example",
        "countryCode": "ZA",
        "timezone": "Africa/Johannesburg",
        "defaultCurrencyCode": "USD",
        "membershipModel": "BOTH",
        "settings": json.dumps({"insuranceLines": ["LIFE"]}),
    },
]


async def create_tenants(client: SeederClient, tenancy_url: str) -> dict[str, str]:
    """Return `{"HEALTH": tenant_uuid, "LIFE": tenant_uuid}` — creating rows as needed."""
    tenants: dict[str, str] = {}
    for payload in TENANT_PAYLOADS:
        line = _insurance_line(payload)
        slug = payload["slug"]
        existing = await client.get(f"{tenancy_url}/api/v1/tenants/slug/{slug}")
        if existing.status_code == 200:
            tenants[line] = existing.json()["id"]
            log.info("seeder.tenant.exists", slug=slug, id=tenants[line])
            continue
        resp = await client.post(f"{tenancy_url}/api/v1/tenants", json=payload)
        if resp.status_code == 409:
            # Rare race — another seeder run created it between our GET and POST.
            retry = await client.get(f"{tenancy_url}/api/v1/tenants/slug/{slug}")
            retry.raise_for_status()
            tenants[line] = retry.json()["id"]
            log.info("seeder.tenant.raced", slug=slug, id=tenants[line])
            continue
        resp.raise_for_status()
        body = resp.json()
        tenants[line] = body["id"]
        log.info(
            "seeder.tenant.created",
            slug=slug,
            id=body["id"],
            schema=body.get("schemaName"),
            realm=body.get("keycloakRealm"),
        )
    return tenants


def _insurance_line(payload: dict) -> str:
    settings = json.loads(payload["settings"])
    lines = settings.get("insuranceLines", [])
    if not lines:
        raise ValueError(f"Tenant payload {payload['slug']} has no insuranceLines")
    return lines[0]
