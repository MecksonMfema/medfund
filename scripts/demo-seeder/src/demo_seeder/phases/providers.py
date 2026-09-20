"""Phase 2 — platform-scoped provider onboarding.

Providers are platform-wide (not tenant-scoped). One target count covers both
tenants. Idempotency: count existing rows via `GET /api/v1/providers?size=1`
and top up to the target.

Creating the row is only half the job. A provider is invisible to a tenant
until it has a `public.provider_tenants` membership row, and claims-service
refuses a claim whose provider is not tagged for the claim's insurance line.
So after topping up, every provider (new or pre-existing) is reconciled
against both junctions via the six membership endpoints.
"""

from __future__ import annotations

import asyncio
import random
from collections.abc import Sequence

import structlog
from faker import Faker

from ..client import SeederClient

log = structlog.get_logger()


HEALTH_SPECIALTIES = (
    "GP",
    "SPECIALIST",
    "HOSPITAL",
    "PHARMACY",
    "LAB",
    "DENTAL",
    "OPTICAL",
)

# HEALTH covers by default; a small cross-line subset uses LIFE-oriented types.
LIFE_TYPES = ("BROKER", "ADVISOR", "FUNERAL_PARLOUR")

# Which insurance line a provider of each type is tagged to serve. Mirrors the
# provider_type -> line mapping in tenancy-service public/V185's backfill.
_LINE_BY_TYPE = {
    "BROKER": "LIFE",
    "ADVISOR": "LIFE",
    "FINANCIAL": "LIFE",
    "FUNERAL_PARLOUR": "FUNERAL",
    "FUNERAL": "FUNERAL",
}

_PAGE_SIZE = 100

_CONCURRENCY = 20


async def seed_providers(
    client: SeederClient,
    user_url: str,
    health_count: int,
    life_count: int,
    rng: random.Random,
    faker: Faker,
    tenant_ids: Sequence[str] = (),
) -> int:
    """Create providers to reach `health_count + life_count` platform-wide. Returns rows inserted."""
    target = health_count + life_count
    existing = await _count_existing(client, user_url)
    to_create = max(0, target - existing)
    log.info("seeder.providers.plan", existing=existing, target=target, to_create=to_create)
    if to_create == 0:
        await _reconcile_memberships(client, user_url, tenant_ids)
        return 0

    # First `life_count` new rows use LIFE-oriented types; rest are HEALTH.
    life_slots = max(0, life_count - _life_existing(existing, health_count, life_count))
    life_slots = min(life_slots, to_create)

    sem = asyncio.Semaphore(_CONCURRENCY)

    async def _one(index: int) -> bool:
        async with sem:
            is_life = index < life_slots
            if is_life:
                provider_type = rng.choice(LIFE_TYPES)
                specialty = provider_type
                name = f"{faker.company()} {provider_type.title().replace('_', ' ')}"
            else:
                provider_type = "HEALTH"
                specialty = rng.choice(HEALTH_SPECIALTIES)
                name = f"{faker.company()} Medical"
            payload = {
                "name": name[:200],
                "providerType": provider_type,
                "registrationNumber": f"REG-{rng.randint(100_000, 999_999_999)}",
                "specialty": specialty,
                "email": faker.company_email(),
                "phone": faker.phone_number()[:50],
                "city": faker.city()[:100],
                "address": faker.address().replace("\n", ", "),
            }
            resp = await client.post(f"{user_url}/api/v1/providers", json=payload)
            if resp.status_code == 201:
                return True
            log.warning(
                "seeder.providers.non-201",
                status=resp.status_code,
                body=resp.text[:200],
            )
            return False

    results = await asyncio.gather(*(_one(i) for i in range(to_create)))
    await _reconcile_memberships(client, user_url, tenant_ids)
    return sum(1 for ok in results if ok)


async def _reconcile_memberships(
    client: SeederClient, user_url: str, tenant_ids: Sequence[str]
) -> None:
    """Link every platform provider to every seeded tenant and tag its line.

    Runs over the full provider list rather than only the rows this invocation
    created: a re-run against an already-populated registry would otherwise
    leave earlier providers unlinked, and every claim against one of them would
    422 at submit. Both endpoints are idempotent (409 = already there), so the
    sweep is safe to repeat.
    """
    if not tenant_ids:
        log.warning("seeder.providers.link-skipped", reason="no tenant ids supplied")
        return

    providers = await _list_all(client, user_url)
    if not providers:
        return

    sem = asyncio.Semaphore(_CONCURRENCY)

    async def _link_and_tag(provider: dict) -> None:
        async with sem:
            pid = provider.get("id")
            if not pid:
                return
            for tid in tenant_ids:
                resp = await client.post(f"{user_url}/api/v1/providers/{pid}/tenants/{tid}")
                if resp.status_code not in (201, 409):
                    log.warning(
                        "seeder.providers.link-non-201",
                        pid=pid,
                        tid=tid,
                        status=resp.status_code,
                        body=resp.text[:200],
                    )
            line = _line_for_type(provider.get("providerType"))
            resp = await client.post(
                f"{user_url}/api/v1/providers/{pid}/insurance-lines/{line}"
            )
            if resp.status_code not in (201, 409):
                log.warning(
                    "seeder.providers.line-non-201",
                    pid=pid,
                    line=line,
                    status=resp.status_code,
                    body=resp.text[:200],
                )

    await asyncio.gather(*(_link_and_tag(p) for p in providers))
    log.info(
        "seeder.providers.linked",
        providers=len(providers),
        tenants=len(tenant_ids),
    )


async def _list_all(client: SeederClient, user_url: str) -> list[dict]:
    """Every provider in the platform registry, paged."""
    out: list[dict] = []
    page = 1
    while True:
        resp = await client.get(
            f"{user_url}/api/v1/providers", params={"page": page, "size": _PAGE_SIZE}
        )
        if resp.status_code != 200:
            log.warning(
                "seeder.providers.list-non-200", status=resp.status_code, body=resp.text[:200]
            )
            return out
        body = resp.json() or {}
        content = body.get("content") or []
        out.extend(content)
        if len(content) < _PAGE_SIZE or page >= int(body.get("totalPages") or 1):
            return out
        page += 1


def _line_for_type(provider_type: str | None) -> str:
    """HEALTH unless the provider type says otherwise (mirrors V185's backfill)."""
    return _LINE_BY_TYPE.get((provider_type or "").upper(), "HEALTH")


async def _count_existing(client: SeederClient, user_url: str) -> int:
    resp = await client.get(f"{user_url}/api/v1/providers", params={"page": 1, "size": 1})
    if resp.status_code != 200:
        log.warning("seeder.providers.count-non-200", status=resp.status_code, body=resp.text[:200])
        return 0
    body = resp.json() or {}
    return int(body.get("totalCount") or 0)


def _life_existing(total_existing: int, health_target: int, life_target: int) -> int:
    """Assume prior runs filled LIFE-typed rows first; used to keep the mix stable."""
    # Cheap heuristic; the split is a demo shape, not a correctness constraint.
    return min(total_existing, life_target)
