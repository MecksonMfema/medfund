"""Phase 3 — 6 schemes per tenant + their benefits and age groups.

HEALTH tenant: Basic/Standard/Premium in USD and ZWL (6 schemes total).
LIFE tenant:   Term10/Term20/Endowment in USD and ZAR (6 schemes total).

Benefit shape:
    HEALTH — 12 benefits per scheme (GP, Specialist, Hospital, Maternity,
             Dental, Optical, Pharmacy, Preventive, Chronic, Emergency,
             Ambulance, Diagnostics).
    LIFE   — 3 benefits per scheme (Death, Disability rider, Terminal
             Illness rider).

Every benefit requires at least one tariff-category-id (V063 hard constraint
on `CreateSchemeBenefitRequest`). Fetches the tenant's tariff-categories
catalogue once from claims-service and reuses the ids.

Age groups: 7 bands per scheme (0-17, 18-24, 25-34, 35-44, 45-54, 55-64, 65+)
with age-graded contribution amounts scaled to the scheme tier.
"""

from __future__ import annotations

import asyncio
from datetime import date, timedelta
from decimal import Decimal

import structlog

from ..client import SeederClient
from ..config import TierProfile

log = structlog.get_logger()


HEALTH_TIER_BASE_PREMIUM: dict[str, Decimal] = {
    "Basic":    Decimal("25.00"),
    "Standard": Decimal("55.00"),
    "Premium":  Decimal("120.00"),
}

# HEALTH scheme names → (tier, currency). Order matters for stable-output logs.
HEALTH_SCHEMES: list[tuple[str, str, str]] = [
    ("Basic-USD",    "Basic",    "USD"),
    ("Standard-USD", "Standard", "USD"),
    ("Premium-USD",  "Premium",  "USD"),
    ("Basic-ZWL",    "Basic",    "ZWL"),
    ("Standard-ZWL", "Standard", "ZWL"),
    ("Premium-ZWL",  "Premium",  "ZWL"),
]

# ZWL premium is denominated in local currency (rough parity with the USD number
# times the initial FX seed of 500 ZWL/USD; the exact number is demo data).
ZWL_FX_APPROX = Decimal("500")


HEALTH_BENEFITS: list[tuple[str, str, Decimal, Decimal]] = [
    # (benefit_name, benefit_type, annual_limit_usd, waiting_period_days baseline)
    ("GP Consultations",       "OUTPATIENT",  Decimal("500"),   0),
    ("Specialist Consultations","OUTPATIENT", Decimal("800"),   0),
    ("Hospital Inpatient",     "INPATIENT",   Decimal("15000"), 90),
    ("Maternity",              "MATERNITY",   Decimal("3500"),  300),
    ("Dental",                 "DENTAL",      Decimal("600"),   0),
    ("Optical",                "OPTICAL",     Decimal("400"),   0),
    ("Pharmacy",               "PHARMACY",    Decimal("1200"),  0),
    ("Preventive Care",        "PREVENTIVE",  Decimal("300"),   0),
    ("Chronic Medication",     "CHRONIC",     Decimal("2400"),  0),
    ("Emergency",              "EMERGENCY",   Decimal("5000"),  0),
    ("Ambulance",              "EMERGENCY",   Decimal("800"),   0),
    ("Diagnostics",            "OUTPATIENT",  Decimal("1500"),  0),
]

# Tier multiplier applied to annual limits.
TIER_LIMIT_MULTIPLIER: dict[str, Decimal] = {
    "Basic": Decimal("0.6"),
    "Standard": Decimal("1.0"),
    "Premium": Decimal("2.0"),
}

LIFE_TIER_BASE_PREMIUM: dict[str, Decimal] = {
    "Term10":    Decimal("18.00"),
    "Term20":    Decimal("32.00"),
    "Endowment": Decimal("70.00"),
}

# LIFE scheme names → (tier, currency, tenant-default-cover-in-thousands).
LIFE_SCHEMES: list[tuple[str, str, str, int]] = [
    ("Term10-USD",    "Term10",    "USD", 25),
    ("Term20-USD",    "Term20",    "USD", 50),
    ("Endowment-USD", "Endowment", "USD", 100),
    ("Term10-ZAR",    "Term10",    "ZAR", 25),
    ("Term20-ZAR",    "Term20",    "ZAR", 50),
    ("Endowment-ZAR", "Endowment", "ZAR", 100),
]

LIFE_BENEFITS: list[tuple[str, str, Decimal, int]] = [
    ("Death Benefit",             "DEATH",         Decimal("1.0"),  0),
    ("Disability Rider",          "DISABILITY",    Decimal("0.5"),  180),
    ("Terminal Illness Rider",    "TERMINAL_ILL",  Decimal("0.5"),  180),
]

# 7 age bands per scheme (min, max, contribution_multiplier). Multiplier scales
# the scheme's base premium; young dependants pay ~half, seniors pay ~2x.
AGE_BANDS: list[tuple[str, int, int, Decimal]] = [
    ("0-17",  0,   17,  Decimal("0.50")),
    ("18-24", 18,  24,  Decimal("0.70")),
    ("25-34", 25,  34,  Decimal("1.00")),
    ("35-44", 35,  44,  Decimal("1.10")),
    ("45-54", 45,  54,  Decimal("1.30")),
    ("55-64", 55,  64,  Decimal("1.65")),
    ("65+",   65, 120,  Decimal("2.20")),
]


async def seed_schemes(
    client: SeederClient,
    contributions_url: str,
    claims_url: str,
    tenants: dict[str, str],
    profile: TierProfile,
) -> dict[str, int]:
    """Create/refresh all schemes + benefits + age groups. Returns per-tenant scheme count.

    Anchors each age-group's price-history row at the tier's timeline_start
    (today - months, snapped to 1st-of-month), matching the same anchor
    that Phase 5 enrollment uses. Previews / billing runs for back-dated
    periods then find a matching age_group_prices row in the LATERAL
    join in HealthCandidateResolver — without this, back-dated previews
    fall through to NULL and render as amount=0.
    """
    timeline_start = _snap_to_first_of_month(
        date.today() - timedelta(days=profile.timeline_months * 30)
    )
    log.info("seeder.schemes.timeline-anchor", effective_from=timeline_start.isoformat())
    counts: dict[str, int] = {}
    for line, tenant_id in tenants.items():
        # Every benefit needs at least one tariff-category-id. Fetch once per tenant.
        category_ids = await _fetch_category_ids(client, claims_url, tenant_id)
        if not category_ids:
            log.error(
                "seeder.schemes.no-tariff-categories",
                line=line,
                tenant_id=tenant_id,
                hint="V063 seed should have populated ~7 default categories per tenant",
            )
            counts[line] = 0
            continue

        if line == "HEALTH":
            scheme_specs = [(name, tier, ccy, HEALTH_TIER_BASE_PREMIUM[tier]) for name, tier, ccy in HEALTH_SCHEMES]
            benefit_specs = HEALTH_BENEFITS
        elif line == "LIFE":
            scheme_specs = [(name, tier, ccy, LIFE_TIER_BASE_PREMIUM[tier]) for name, tier, ccy, _ in LIFE_SCHEMES]
            benefit_specs = None  # handled per-scheme with sum-assured multiplier below
        else:
            log.warning("seeder.schemes.unknown-line", line=line)
            counts[line] = 0
            continue

        existing_schemes = await _fetch_existing_schemes(client, contributions_url, tenant_id)
        created = 0
        for name, tier, currency, base_premium in scheme_specs:
            scheme_id = existing_schemes.get(name)
            if scheme_id is None:
                scheme_id = await _create_scheme(
                    client, contributions_url, tenant_id, name, tier, currency, line
                )
                if scheme_id is None:
                    continue
                created += 1

            # Benefits + age groups are idempotent: check existence first.
            existing_benefit_names = await _fetch_benefit_names(
                client, contributions_url, tenant_id, scheme_id
            )
            if line == "HEALTH":
                await _seed_health_benefits(
                    client, contributions_url, tenant_id, scheme_id, tier, currency,
                    existing_benefit_names, category_ids,
                )
            else:
                # LIFE: sum-assured scales the annual limit; benefits are DEATH+riders.
                life_sum_assured = _life_sum_assured(name)
                await _seed_life_benefits(
                    client, contributions_url, tenant_id, scheme_id, currency,
                    life_sum_assured, existing_benefit_names, category_ids,
                )

            existing_ag_names = await _fetch_age_group_names(
                client, contributions_url, tenant_id, scheme_id
            )
            await _seed_age_groups(
                client, contributions_url, tenant_id, scheme_id, currency,
                base_premium if currency in ("USD", "ZAR") else base_premium * ZWL_FX_APPROX,
                existing_ag_names,
                timeline_start,
            )

        counts[line] = created
        log.info("seeder.schemes.done", line=line, created=created)
    return counts


# ── Helpers ──────────────────────────────────────────────────────────────────


async def _fetch_category_ids(
    client: SeederClient, claims_url: str, tenant_id: str
) -> list[str]:
    resp = await client.get(
        f"{claims_url}/api/v1/tariff-categories",
        params={"activeOnly": "true"},
        tenant_id=tenant_id,
    )
    if resp.status_code != 200:
        log.warning(
            "seeder.schemes.categories-non-200",
            tenant_id=tenant_id,
            status=resp.status_code,
            body=resp.text[:200],
        )
        return []
    return [row["id"] for row in (resp.json() or []) if row.get("id")]


async def _fetch_existing_schemes(
    client: SeederClient, contributions_url: str, tenant_id: str
) -> dict[str, str]:
    resp = await client.get(f"{contributions_url}/api/v1/schemes", tenant_id=tenant_id)
    if resp.status_code != 200:
        return {}
    return {row["name"]: row["id"] for row in (resp.json() or []) if row.get("name")}


async def _create_scheme(
    client: SeederClient,
    contributions_url: str,
    tenant_id: str,
    name: str,
    tier: str,
    currency: str,
    line: str,
) -> str | None:
    payload = {
        "name": name,
        "description": f"{tier} tier scheme ({currency}) for demo-seeder.",
        "schemeType": "medical_aid" if line == "HEALTH" else "life",
        "insuranceLine": line,
        "effectiveDate": date.today().replace(day=1).isoformat(),
        "currencyCode": currency,
        "tracksMemberBalances": True,
    }
    resp = await client.post(
        f"{contributions_url}/api/v1/schemes", json=payload, tenant_id=tenant_id
    )
    if resp.status_code in (201, 200):
        body = resp.json()
        return body.get("id")
    if resp.status_code == 409:
        # Race with a parallel run. Refetch by name.
        again = await _fetch_existing_schemes(client, contributions_url, tenant_id)
        return again.get(name)
    log.warning(
        "seeder.schemes.create-non-201",
        name=name,
        status=resp.status_code,
        body=resp.text[:300],
    )
    return None


async def _fetch_benefit_names(
    client: SeederClient, contributions_url: str, tenant_id: str, scheme_id: str
) -> set[str]:
    resp = await client.get(
        f"{contributions_url}/api/v1/schemes/{scheme_id}/benefits", tenant_id=tenant_id
    )
    if resp.status_code != 200:
        return set()
    return {row["name"] for row in (resp.json() or []) if row.get("name")}


async def _seed_health_benefits(
    client: SeederClient,
    contributions_url: str,
    tenant_id: str,
    scheme_id: str,
    tier: str,
    currency: str,
    existing_names: set[str],
    category_ids: list[str],
) -> None:
    multiplier = TIER_LIMIT_MULTIPLIER[tier]
    fx_scale = Decimal("1") if currency == "USD" else ZWL_FX_APPROX
    sem = asyncio.Semaphore(5)

    async def _one(spec: tuple[str, str, Decimal, int]) -> None:
        name, benefit_type, annual_limit_usd, waiting = spec
        if name in existing_names:
            return
        annual_limit = (annual_limit_usd * multiplier * fx_scale).quantize(Decimal("0.01"))
        payload = {
            "schemeId": scheme_id,
            "name": name,
            "benefitType": benefit_type,
            "annualLimit": str(annual_limit),
            "currencyCode": currency,
            "waitingPeriodDays": waiting,
            "usageMode": "RUNNING_BALANCE",
            # One category id per benefit; the plan's demo shape does not need
            # multi-category benefits.
            "categoryIds": [category_ids[hash(name) % len(category_ids)]],
        }
        async with sem:
            resp = await client.post(
                f"{contributions_url}/api/v1/schemes/benefits",
                json=payload,
                tenant_id=tenant_id,
            )
            if resp.status_code not in (201, 200):
                log.warning(
                    "seeder.schemes.benefit-non-201",
                    scheme_id=scheme_id,
                    name=name,
                    status=resp.status_code,
                    body=resp.text[:300],
                )

    await asyncio.gather(*(_one(s) for s in HEALTH_BENEFITS))


async def _seed_life_benefits(
    client: SeederClient,
    contributions_url: str,
    tenant_id: str,
    scheme_id: str,
    currency: str,
    sum_assured: Decimal,
    existing_names: set[str],
    category_ids: list[str],
) -> None:
    sem = asyncio.Semaphore(5)

    async def _one(spec: tuple[str, str, Decimal, int]) -> None:
        name, benefit_type, multiplier, waiting = spec
        if name in existing_names:
            return
        annual_limit = (sum_assured * multiplier).quantize(Decimal("0.01"))
        payload = {
            "schemeId": scheme_id,
            "name": name,
            "benefitType": benefit_type,
            "annualLimit": str(annual_limit),
            "currencyCode": currency,
            "waitingPeriodDays": waiting,
            "usageMode": "ONE_TIME_PER_BENEFICIARY",
            "categoryIds": [category_ids[hash(name) % len(category_ids)]],
        }
        async with sem:
            resp = await client.post(
                f"{contributions_url}/api/v1/schemes/benefits",
                json=payload,
                tenant_id=tenant_id,
            )
            if resp.status_code not in (201, 200):
                log.warning(
                    "seeder.schemes.benefit-non-201",
                    scheme_id=scheme_id,
                    name=name,
                    status=resp.status_code,
                    body=resp.text[:300],
                )

    await asyncio.gather(*(_one(s) for s in LIFE_BENEFITS))


async def _fetch_age_group_names(
    client: SeederClient, contributions_url: str, tenant_id: str, scheme_id: str
) -> set[str]:
    resp = await client.get(
        f"{contributions_url}/api/v1/schemes/{scheme_id}/age-groups", tenant_id=tenant_id
    )
    if resp.status_code != 200:
        return set()
    return {row["name"] for row in (resp.json() or []) if row.get("name")}


async def _seed_age_groups(
    client: SeederClient,
    contributions_url: str,
    tenant_id: str,
    scheme_id: str,
    currency: str,
    base_premium: Decimal,
    existing_names: set[str],
    effective_from: date,
) -> None:
    sem = asyncio.Semaphore(5)

    async def _one(band: tuple[str, int, int, Decimal]) -> None:
        name, min_age, max_age, multiplier = band
        if name in existing_names:
            return
        amount = (base_premium * multiplier).quantize(Decimal("0.01"))
        payload = {
            "schemeId": scheme_id,
            "name": name,
            "minAge": min_age,
            "maxAge": max_age,
            "contributionAmount": str(amount),
            "currencyCode": currency,
            "effectiveFrom": effective_from.isoformat(),
        }
        async with sem:
            resp = await client.post(
                f"{contributions_url}/api/v1/schemes/age-groups",
                json=payload,
                tenant_id=tenant_id,
            )
            if resp.status_code not in (201, 200):
                log.warning(
                    "seeder.schemes.age-group-non-201",
                    scheme_id=scheme_id,
                    name=name,
                    status=resp.status_code,
                    body=resp.text[:300],
                )

    await asyncio.gather(*(_one(b) for b in AGE_BANDS))


def _life_sum_assured(scheme_name: str) -> Decimal:
    """Look up the LIFE scheme's default sum-assured for benefit-limit scaling."""
    for name, _, _, cover_thousands in LIFE_SCHEMES:
        if name == scheme_name:
            return Decimal(str(cover_thousands * 1000))
    return Decimal("25000")


def _snap_to_first_of_month(d: date) -> date:
    return d.replace(day=1)
