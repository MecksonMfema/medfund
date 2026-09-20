"""Phase 5 — bulk enrollment at t=T0.

For each tenant:
    1. Create groups (3/8/15 per tier).
    2. Create members with enrollment_date snapped to the 1st of the month
       `months*30 days` before today.
    3. Cascade 0-4 dependants per member (Poisson lambda=1.5).
    4. LIFE tenant only — create a `LifePolicy` for 75% of members.

Idempotency:
    - Skip whole tenant if `GET /members?size=1` totalCount already >= 90% of
      the tier target (avoids doubling on re-runs; small tolerance so partial
      runs can top up).
    - Groups: fetch existing names first; only POST names that aren't already
      present.

Currency mix:
    - HEALTH: 60% USD schemes / 40% ZWL
    - LIFE:   50% USD / 50% ZAR

Age distribution: 25% <18, 50% 18-45, 20% 46-64, 5% 65+.
Grouped ratio: HEALTH 60% grouped, LIFE 40% grouped.
"""

from __future__ import annotations

import asyncio
import math
import random
from datetime import date, timedelta

import structlog
from faker import Faker

from ..client import SeederClient
from ..config import TierProfile

log = structlog.get_logger()


# ── Per-line configuration ───────────────────────────────────────────────────

CURRENCY_WEIGHTS: dict[str, dict[str, float]] = {
    "HEALTH": {"USD": 0.60, "ZWL": 0.40},
    "LIFE":   {"USD": 0.50, "ZAR": 0.50},
}

GROUPED_RATIO: dict[str, float] = {"HEALTH": 0.60, "LIFE": 0.40}

# LIFE occupation-hazard mix.
OCCUPATION_MIX: list[tuple[str, float]] = [
    ("SEDENTARY", 0.55),
    ("MANUAL",    0.30),
    ("HAZARDOUS", 0.12),
    ("VERY_HAZARDOUS", 0.03),
]


async def run_enrollment(
    client: SeederClient,
    user_url: str,
    contributions_url: str,
    tenants: dict[str, str],
    profile: TierProfile,
    rng: random.Random,
    faker: Faker,
) -> dict[str, dict[str, int]]:
    """Enroll groups / members / dependants / LifePolicy per tenant. Returns per-tenant counts."""
    timeline_start = _snap_to_first_of_month(
        date.today() - timedelta(days=profile.timeline_months * 30)
    )
    log.info("seeder.enrollment.start", timeline_start=timeline_start.isoformat())

    results: dict[str, dict[str, int]] = {}
    for line, tenant_id in tenants.items():
        target_members = (
            profile.health_members if line == "HEALTH" else profile.life_members
        )
        current_members = await _count_members(client, user_url, tenant_id)

        # 1. Fetch schemes and split by currency.
        schemes = await _fetch_schemes(client, contributions_url, tenant_id)
        if not schemes:
            log.error("seeder.enrollment.no-schemes", line=line, tenant_id=tenant_id)
            continue

        # 2. Groups (idempotent by name).
        group_ids = await _seed_groups(
            client, user_url, tenant_id, profile.groups_per_tenant, rng, faker
        )
        log.info("seeder.enrollment.groups", line=line, count=len(group_ids))

        # 3. Members + dependants — only if below target. Idempotent: dependants
        # cascade off newly-created members; existing members keep their existing
        # dependants (no attempt to top up per-member).
        to_create = max(0, target_members - current_members)
        if to_create == 0:
            log.info(
                "seeder.enrollment.members-already-full",
                line=line,
                current=current_members,
                target=target_members,
            )
        members_created, dependants_created = await _seed_members_and_dependants(
            client,
            user_url,
            tenant_id,
            line,
            to_create,
            timeline_start,
            schemes,
            group_ids,
            profile.concurrent_enrolls,
            rng,
            faker,
        )

        # 4. LifePolicy for 75% of LIFE members.
        life_policies_created = 0
        if line == "LIFE":
            life_policies_created = await _seed_life_policies(
                client,
                user_url,
                tenant_id,
                target=int(target_members * 0.75),
                schemes=schemes,
                rng=rng,
            )

        results[line] = {
            "groups": len(group_ids),
            "members": members_created,
            "dependants": dependants_created,
            "life_policies": life_policies_created,
        }
        log.info("seeder.enrollment.line-done", line=line, **results[line])

    return results


# ── Group creation ───────────────────────────────────────────────────────────


async def _seed_groups(
    client: SeederClient,
    user_url: str,
    tenant_id: str,
    target: int,
    rng: random.Random,
    faker: Faker,
) -> list[str]:
    """Return every group id (existing + newly-created) for the tenant."""
    existing = await _fetch_groups(client, user_url, tenant_id)
    if len(existing) >= target:
        return [g["id"] for g in existing[:target]]

    ids: list[str] = [g["id"] for g in existing]
    to_create = target - len(ids)
    used_names = {g["name"] for g in existing}

    for _ in range(to_create):
        for _attempt in range(10):
            name = f"{faker.company()[:180]} Group"
            if name not in used_names:
                used_names.add(name)
                break
        payload = {
            "name": name,
            "registrationNumber": f"GRP-{rng.randint(10_000, 999_999)}",
            "address": faker.address().replace("\n", ", "),
            "email": faker.company_email(),
        }
        resp = await client.post(
            f"{user_url}/api/v1/groups", json=payload, tenant_id=tenant_id
        )
        if resp.status_code in (201, 200):
            ids.append(resp.json()["id"])
            continue
        log.warning(
            "seeder.enrollment.group-non-201",
            status=resp.status_code,
            body=resp.text[:300],
        )
    return ids


async def _fetch_groups(client: SeederClient, user_url: str, tenant_id: str) -> list[dict]:
    resp = await client.get(
        f"{user_url}/api/v1/groups", params={"page": 1, "size": 100}, tenant_id=tenant_id
    )
    if resp.status_code != 200:
        return []
    body = resp.json()
    # GroupController may return either a Page shape or a raw list; handle both.
    if isinstance(body, list):
        return body
    return body.get("content") or []


# ── Scheme lookup ────────────────────────────────────────────────────────────


async def _fetch_schemes(
    client: SeederClient, contributions_url: str, tenant_id: str
) -> dict[str, list[dict]]:
    """Return `{currency: [scheme, ...]}` filtered to `active` schemes."""
    resp = await client.get(
        f"{contributions_url}/api/v1/schemes", tenant_id=tenant_id
    )
    if resp.status_code != 200:
        log.warning(
            "seeder.enrollment.schemes-non-200",
            status=resp.status_code,
            body=resp.text[:200],
        )
        return {}
    grouped: dict[str, list[dict]] = {}
    for scheme in resp.json() or []:
        if scheme.get("status") != "active":
            continue
        ccy = scheme.get("currencyCode") or "USD"
        grouped.setdefault(ccy, []).append(scheme)
    return grouped


# ── Member counting (idempotency probe) ──────────────────────────────────────


async def _count_members(client: SeederClient, user_url: str, tenant_id: str) -> int:
    """Members endpoint is cursor-paginated (no totalCount); use tenant-stats instead."""
    resp = await client.get(
        f"{user_url}/api/v1/tenant-stats",
        tenant_id=tenant_id,
    )
    if resp.status_code != 200:
        return 0
    body = resp.json() or {}
    return int(body.get("totalMembers") or 0)


# ── Member + dependant creation ──────────────────────────────────────────────


async def _seed_members_and_dependants(
    client: SeederClient,
    user_url: str,
    tenant_id: str,
    line: str,
    to_create: int,
    timeline_start: date,
    schemes_by_ccy: dict[str, list[dict]],
    group_ids: list[str],
    concurrency: int,
    rng: random.Random,
    faker: Faker,
) -> tuple[int, int]:
    """POST members concurrently, then cascade dependants. Returns (members, dependants)."""
    currencies = list(CURRENCY_WEIGHTS[line].keys())
    weights = list(CURRENCY_WEIGHTS[line].values())
    grouped_ratio = GROUPED_RATIO[line]

    sem = asyncio.Semaphore(concurrency)
    members_created = 0
    dependants_created = 0

    async def _one(index: int) -> None:
        nonlocal members_created, dependants_created

        currency = rng.choices(currencies, weights=weights, k=1)[0]
        candidate_schemes = schemes_by_ccy.get(currency)
        if not candidate_schemes:
            # Fall back to any currency; a scheme is required.
            all_schemes = [s for ss in schemes_by_ccy.values() for s in ss]
            if not all_schemes:
                return
            candidate_schemes = all_schemes
        scheme = rng.choice(candidate_schemes)
        group_id = rng.choice(group_ids) if group_ids and rng.random() < grouped_ratio else None

        dob = _weighted_dob(rng, timeline_start)
        gender = rng.choice(["male", "female"])
        national_id = f"NID-{index:06d}-{rng.randint(1000, 9999)}"

        first = faker.first_name_male() if gender == "male" else faker.first_name_female()
        last = faker.last_name()
        email = f"member-{tenant_id[:8]}-{index}@medfund.example"

        payload = {
            "firstName": first[:100],
            "lastName": last[:100],
            "dateOfBirth": dob.isoformat(),
            "gender": gender,
            "nationalId": national_id,
            "email": email,
            "phone": faker.phone_number()[:50],
            "address": faker.address().replace("\n", ", "),
            "schemeId": scheme["id"],
            "enrollmentDate": timeline_start.isoformat(),
        }
        if group_id is not None:
            payload["groupId"] = group_id

        async with sem:
            resp = await client.post(
                f"{user_url}/api/v1/members", json=payload, tenant_id=tenant_id
            )
        if resp.status_code not in (201, 200):
            log.warning(
                "seeder.enrollment.member-non-201",
                index=index,
                status=resp.status_code,
                body=resp.text[:200],
            )
            return
        member = resp.json()
        member_id = member.get("id")
        if member_id is None:
            return
        members_created += 1

        # Dependants: Poisson lambda=1.5 → mostly 0-4.
        n_dep = _poisson(1.5, rng)
        for j in range(n_dep):
            relationship, dep_dob = _dependant_shape(rng, dob)
            dep_payload = {
                "memberId": member_id,
                "firstName": (faker.first_name_male() if rng.random() < 0.5 else faker.first_name_female())[:100],
                "lastName": last[:100],
                "dateOfBirth": dep_dob.isoformat(),
                "gender": rng.choice(["male", "female"]),
                "relationship": relationship,
                "nationalId": f"NID-{index:06d}-D{j}-{rng.randint(1000, 9999)}",
                "enrollmentDate": timeline_start.isoformat(),
            }
            async with sem:
                dr = await client.post(
                    f"{user_url}/api/v1/dependants",
                    json=dep_payload,
                    tenant_id=tenant_id,
                )
            if dr.status_code in (201, 200):
                dependants_created += 1
            else:
                log.warning(
                    "seeder.enrollment.dep-non-201",
                    status=dr.status_code,
                    body=dr.text[:200],
                )

    await asyncio.gather(*(_one(i) for i in range(to_create)))
    return members_created, dependants_created


# ── LifePolicy creation (LIFE tenant only) ──────────────────────────────────


async def _seed_life_policies(
    client: SeederClient,
    user_url: str,
    tenant_id: str,
    target: int,
    schemes: dict[str, list[dict]],
    rng: random.Random,
) -> int:
    """POST LifePolicy for a random 75% of the tenant's members."""
    # Fetch members in bulk.
    members = await _fetch_all_members(client, user_url, tenant_id)
    if not members:
        return 0
    chosen = rng.sample(members, min(target, len(members)))
    all_schemes = [s for ss in schemes.values() for s in ss]

    hazard_pool = [oc for oc, _ in OCCUPATION_MIX]
    hazard_weights = [w for _, w in OCCUPATION_MIX]

    # Skip members that already have a policy — supports top-up idempotency.
    existing_ids = await _fetch_existing_life_policy_member_ids(client, user_url, tenant_id)

    created = 0
    failure_logged = 0
    for idx, m in enumerate(chosen):
        member_id = m.get("id")
        if member_id is None or member_id in existing_ids:
            continue
        scheme = rng.choice(all_schemes)
        sum_assured = rng.choice([25_000, 50_000, 100_000, 250_000, 500_000])
        payload = {
            "policyNumber": f"LP-{tenant_id[:6].upper()}-{member_id[:8].upper()}-{idx:05d}",
            "sumAssured": str(sum_assured),
            "occupationHazardClass": rng.choices(hazard_pool, weights=hazard_weights, k=1)[0],
            "termMonths": rng.choice([120, 240, 360]),
            "schemeId": scheme["id"],
            "insuredMemberId": member_id,
        }
        try:
            resp = await client.post(
                f"{user_url}/api/v1/life-policies", json=payload, tenant_id=tenant_id
            )
        except Exception as exc:
            if failure_logged < 3:
                log.warning(
                    "seeder.enrollment.life-policy-raised",
                    error=str(exc)[:300],
                    payload=payload,
                )
                failure_logged += 1
            continue
        if resp.status_code in (201, 200):
            created += 1
            continue
        if failure_logged < 3:
            log.warning(
                "seeder.enrollment.life-policy-non-201",
                status=resp.status_code,
                body=resp.text[:300],
                payload=payload,
            )
            failure_logged += 1
    return created


async def _fetch_existing_life_policy_member_ids(
    client: SeederClient, user_url: str, tenant_id: str
) -> set[str]:
    """Return the set of member ids that already have at least one LifePolicy.

    `GET /api/v1/life-policies` is unpaginated (Flux) — returns the full array
    on every call. Loop-with-page-size hangs forever here (285 rows always
    exceed any page size, `hasMore` doesn't exist on the endpoint). One call
    is enough.
    """
    resp = await client.get(f"{user_url}/api/v1/life-policies", tenant_id=tenant_id)
    if resp.status_code != 200:
        return set()
    rows = resp.json() or []
    if not isinstance(rows, list):
        rows = rows.get("content") or []
    return {row["insuredMemberId"] for row in rows if row.get("insuredMemberId")}


async def _fetch_all_members(client: SeederClient, user_url: str, tenant_id: str) -> list[dict]:
    """Cursor-page through all members. Members endpoint returns
    `{content, nextCursor, hasMore, limit}` — walk `nextCursor` until exhausted."""
    all_rows: list[dict] = []
    cursor: str | None = None
    limit = 100
    while True:
        params: dict[str, str | int] = {"limit": limit}
        if cursor:
            params["cursor"] = cursor
        resp = await client.get(
            f"{user_url}/api/v1/members", params=params, tenant_id=tenant_id
        )
        if resp.status_code != 200:
            break
        body = resp.json() or {}
        rows = body.get("content") or []
        all_rows.extend(rows)
        cursor = body.get("nextCursor")
        if not body.get("hasMore") or not cursor:
            break
    return all_rows


# ── RNG helpers ──────────────────────────────────────────────────────────────


def _weighted_dob(rng: random.Random, reference: date) -> date:
    """25% <18, 50% 18-45, 20% 46-64, 5% 65+."""
    bucket = rng.random()
    if bucket < 0.25:
        years_ago = rng.randint(1, 17)
    elif bucket < 0.75:
        years_ago = rng.randint(18, 45)
    elif bucket < 0.95:
        years_ago = rng.randint(46, 64)
    else:
        years_ago = rng.randint(65, 85)
    days = years_ago * 365 + rng.randint(0, 364)
    return reference - timedelta(days=days)


def _dependant_shape(rng: random.Random, principal_dob: date) -> tuple[str, date]:
    """Draw a relationship + a plausible DOB relative to the principal."""
    r = rng.random()
    today = date.today()
    if r < 0.35:
        # SPOUSE aged 18-65
        years = rng.randint(18, 65)
        return "SPOUSE", today - timedelta(days=years * 365 + rng.randint(0, 364))
    if r < 0.90:
        # CHILD < 25, born after principal
        years = rng.randint(0, 24)
        return "CHILD", today - timedelta(days=years * 365 + rng.randint(0, 364))
    # PARENT > 40
    years = rng.randint(40, 80)
    return "PARENT", today - timedelta(days=years * 365 + rng.randint(0, 364))


def _poisson(lmbda: float, rng: random.Random) -> int:
    """Simple Knuth Poisson sampler — fine for our small lambda."""
    L = math.exp(-lmbda)
    k = 0
    p = 1.0
    while True:
        k += 1
        p *= rng.random()
        if p <= L:
            return k - 1


def _snap_to_first_of_month(d: date) -> date:
    return d.replace(day=1)
