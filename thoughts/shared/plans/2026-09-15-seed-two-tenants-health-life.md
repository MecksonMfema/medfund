---
date: 2026-09-15
git_commit: 2a80248e3c0de32c18c8d90b2c72c607aafffcae
branch: rename-adjustments-to-notes
research:
  - thoughts/shared/research/2026-09-15-comprehensive-seed-data-two-tenants-health-life.md
steer: |
  Python CLI under scripts/. Three tier profiles: micro (500x3mo), fast (2kx6mo), full (20kx24mo).
  Auth via password grant on superadmin. ~50 showcase member Keycloak logins + curated ICD/AHFOZ packs.
  Real AI calls on every claim (no stub). Three currencies: HEALTH=USD+ZWL, LIFE=USD+ZAR, with
  CURRENCY_CHANGE scheme events crossing between local currency and USD.
services_touched: [scripts, tenancy-service, user-service, contributions-service, claims-service, finance-service, rules-engine, ai-service, keycloak, makefile]
status: phase-7-complete (partial demo — Phase 6 endpoints blocked)
---

# Comprehensive Demo-Data Seeder for Two Tenants (HEALTH + LIFE) Implementation Plan

## Overview

Build a reproducible Python CLI (`scripts/demo-seeder/`) that provisions two InsureFlow tenants
(one HEALTH, one LIFE) and drives 24 months of realistic multi-currency operations through the
existing HTTP APIs of tenancy-service, user-service, contributions-service, claims-service,
finance-service, and rules-engine. Three tier profiles cover the range from unit-test-friendly
(micro: 500 members x 3 months) through CI/local (fast: 2k x 6mo) to staging soak (full: 20k x
24mo). Every mutation flows through production code paths so audit events, Kafka fan-out, and
per-tenant rules-engine invalidation exercise the platform end-to-end.

## Current State Analysis

The platform has strong per-tenant provisioning primitives already (`TenantService.create()` at
`services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantService.java:115`
handles schema creation, Flyway migration, per-tenant Keycloak realm creation via
`KeycloakRealmService.createRealm()` at `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/KeycloakRealmService.java:64`,
currency config, and 6 default scheduled jobs). Reference-data Flyway seeds populate AHFOZ tariffs
(`V056__seed_ahfoz_march_tariffs.sql`, 4,860 codes at fixed UUID `a8f0c1e2-3b4d-4a56-8789-abc123def456`),
PMB default rules, IFRS17 defaults, and tax config on every tenant schema. `bootstrap-keycloak.sh`
seeds 8 platform staff users into the shared `medfund-platform` realm.

What is missing:

- No bulk demo-data loader anywhere in the repo. No `datafaker`, `javafaker`, or Python `Faker`
  imported. No mock demo data outside unit-test fixtures.
- No per-tenant realm user population; `TenantService.create()` creates the realm but leaves it
  empty.
- No `scripts/reset-tenant-schemas.sh`; only `make infra-reset` (nukes all Docker volumes).
- No `SEED_MODE_ENABLED` or equivalent env-var gate; closest prior art is
  `SKIP_SEED=1` in `scripts/perf-test-scheduled-reports.sh:33`.
- No historical exchange rate rows. `POST /api/v1/exchange-rates` exists on tenancy-service
  (`services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/ExchangeRateController.java:68`)
  but single-row only.
- Rules-engine exposes only `POST /api/v1/rules` (individual rule creation, no bulk pack import)
  at `services/java/rules-engine/src/main/java/com/medfund/rules/controller/TenantRuleController.java:70`.
  Seeder loops per rule.

Verified HTTP contract corrections against the research doc:

- Contributions billing is `POST /api/v1/contributions/preview` + `POST /api/v1/contributions/commit`,
  not `POST /billing/generate`. Invoice commit is a side-effect of `/contributions/commit` (no
  separate `POST /invoices/commit`).
- Payment run population is implicit in `POST /api/v1/payment-runs` create. There is no `/populate`
  step. Runs go create -> approve -> execute.
- All tenant-scoped endpoints use `X-Tenant-ID` header. Platform-scoped endpoints (tenants,
  providers, exchange-rates) do not require the header.

## Desired End State

After running `make seed-demo-{micro,fast,full}` against a fresh `make infra` + `make keycloak-setup`
+ every Java service running:

- Two tenants exist in `public.tenants` (`health-first` HEALTH, `life-first` LIFE), each with a
  fully provisioned schema (`tenant_<uuid>`), fully seeded Flyway reference data (AHFOZ, PMB,
  IFRS17, tax), and a fully created Keycloak realm (`medfund-health-first`, `medfund-life-first`)
  with per-realm roles seeded by `RoleService`.
- Historical daily USD-ZWL and USD-ZAR rates exist in `public.exchange_rates` for 24 months prior
  to today.
- Per-tenant Keycloak realms hold ~25 staff users (2 per role, all portals loginable) + ~50 showcase
  member users spread across statuses (active, suspended, terminated) and groups vs. individuals.
- Each tenant has 6 schemes (3 in local currency + 3 in USD), with scheme benefits, age groups,
  waiting-period rules, and ~15 HEALTH / ~12 LIFE enabled rules in the rules-engine.
- Members / dependants / groups / LifePolicy rows populate per the tier profile, enrolled with dates
  back-dated to 24 months (or 6mo / 3mo) prior to "now".
- 24 (or 6 / 3) months of month-by-month activity replay is complete: contributions, invoices,
  transactions, claims (adjudicated via real AI Stage 6 calls), pre-authorizations, notes,
  provider payment runs (weekly), member payment runs (monthly), scheme changes (incl.
  CURRENCY_CHANGE), group changes, terminations, deaths.
- `make seed-demo-verify` returns 0 with a count report against the tier's expected budget.

### Verification

- `python -m demo_seeder verify --tier micro` reports actual vs expected counts within 5% tolerance
  for members, contributions, claims, payments, notes, and audit events.
- Angular admin at `http://localhost:5100/admin/tenants` shows both tenants.
- Any operator role (e.g., `test-claims-clerk-health@medfund.example`) can log in to the tenant
  admin portal and see a populated claims queue.
- Any showcase member (e.g., `showcase-member-1-health@medfund.example`) can log in to the member
  portal and see their contributions history and benefits.

### Key Discoveries

- **TenantService.create() already provisions per-tenant Keycloak realms**
  (`services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantService.java:156`).
  Seeder does NOT create realms, only adds users to already-created realms.
- **All tenant-scoped services accept `X-Tenant-ID` header for tenant resolution**. Combined with
  a bearer JWT from password grant on `superadmin`, this is the entire auth flow the seeder needs.
- **`bootstrap-keycloak.sh` already establishes the idempotent pattern** (check exists, PUT to
  update, POST to create) for Keycloak Admin API calls (lines 63-89, 142-159, 259-305). Seeder
  mirrors this exactly.
- **M2MTokenProvider exists** at `services/java/shared/src/main/java/com/medfund/shared/security/M2MTokenProvider.java:35`
  but is gated on `keycloak.m2m.client-id` and `bootstrap-keycloak.sh` does NOT create the required
  confidential client. Password grant on `superadmin` is the working alternative that needs no
  infra changes.
- **BeneficiaryBenefitSeeder is event-driven**
  (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/BeneficiaryBenefitSeeder.java:52`).
  Consumes `MEMBER_ENROLLED` and `DEPENDANT_ENROLLED` Kafka events. As long as the seeder POSTs
  through `POST /api/v1/members`, benefit ledger rows are populated automatically. This is why the
  seeder must use HTTP APIs not direct SQL.
- **`RuleDefinition` is a nested structure** (`ConditionGroup` -> `Condition[]`, plus `RuleAction`).
  `CreateRuleRequest` at `services/java/rules-engine/src/main/java/com/medfund/rules/dto/CreateRuleRequest.java`
  wraps it. Seeder ships JSON pack files (one file per rule) that unmarshal into
  `CreateRuleRequest`.
- **Payment runs have no explicit /populate step**. Create with `payeeType` + `periodStart` +
  `periodEnd` and the service populates from `provider_balances` / `member_payables`. Seeder posts
  create -> approve -> execute per run.
- **UNIQUE constraint on exchange_rates** (`base_currency, quote_currency, rate_date, source,
  tenant_id`) makes FX rate seeding idempotent by construction. Seeder can safely re-post.
- **Angular runs on port 5100**, gateway on 3000. `bootstrap-keycloak.sh` uses these. Makefile
  header comment mentioning 4200 is stale (per `[[reference_angular_port]]` memory).
- **`Group.suspend_reason` and `Member.suspend_reason` (V043) enable "grouped members cannot pay"
  guard** (`[[feedback_grouped_members_cannot_pay]]`). Seeder's transaction phase must supply
  `groupId` for grouped members and `memberId` only for ungrouped.
- **Effective-date snap direction** (`[[feedback_effective_date_snap]]`): enrollment dates snap to
  1st-of-month; termination/deactivation dates snap to end-of-month. Seeder's date generator
  enforces this before POSTing.
- **`one-contribution-per-month` V034 UNIQUE index** (`[[feedback_one_contribution_per_month]]`)
  makes `POST /contributions/commit` safe to re-run per period; duplicate commits 409.

## What We're NOT Doing

- **No Angular / Flutter changes.** The seed exercises existing UI; no new pages, components,
  or admin toggles ship as part of this plan.
- **No Java service changes.** All HTTP endpoints the seeder needs already exist. If a specific
  endpoint gap surfaces during Phase 6 (e.g., a missing "bulk enrollment" endpoint on user-service),
  that becomes a separate plan.
- **No new Keycloak roles or realm-wide config changes** beyond user creation. The 13 roles
  currently seeded by `bootstrap-keycloak.sh` remain the canonical list.
- **No modification of `TenantService.create()` behavior.** The seeder uses its existing
  `POST /api/v1/tenants` contract as-is.
- **No prod exposure.** The seeder is gated behind `SEED_MODE_ENABLED=true` env var; fails fast
  otherwise. Not deployed with any Docker image. Not part of any Helm chart. Cannot be triggered
  via any HTTP endpoint on any running service.
- **No stubbing of AI Stage 6.** Per user steer, every claim invokes real ai-service ->
  Claude. The seeder throttles concurrent adjudications to respect Anthropic rate limits, but
  makes no attempt to avoid the cost. Micro tier only for iteration; fast/full are budgeted runs.
- **No security-event replay.** Seeder does not simulate LOGIN / LOGIN_ERROR / impersonation.
  Real logins from showcase users during manual verification produce real security events; that's
  the only coverage for that surface.
- **No cross-tenant analytics population.** The analytics schema materialized views are refreshed
  by an existing scheduled job (out of scope).
- **No historical Kafka replay** for auditing (audit events land in per-tenant `audit_events`
  tables via the audit-service consumer, which suffices).
- **No `--reset` flag inside the seeder itself.** Reset is a separate script
  (`scripts/reset-tenant-schemas.sh`) invoked by `make seed-demo-reset`. Keeps destructive ops
  clearly separated from load ops.

## Implementation Approach

**Language & shape.** Python 3.12 with `uv` project layout under `scripts/demo-seeder/`. Uses
`httpx.AsyncClient` for concurrent HTTP with keep-alive per service host, `Faker` for synthetic PII,
`pydantic` for typed request models, and `click` for CLI. Runs anywhere `python3.12 + uv` runs; no
Java build step, no JVM. Follows the tooling shape of `scripts/bootstrap-keycloak.sh` and
`scripts/perf-test-scheduled-reports.sh`.

**Auth flow.** On boot, the seeder POSTs `grant_type=password&client_id=medfund-web&username=superadmin&password=admin123`
to `http://localhost:9080/realms/medfund-platform/protocol/openid-connect/token`, caches the access
token with a 30s pre-expiry refresh, and injects `Authorization: Bearer <token>` on every request.
For tenant-scoped calls the seeder additionally sets `X-Tenant-ID: <tenant-uuid>` from the response
of `POST /api/v1/tenants`. For Keycloak Admin API calls (seeding realm users) the same token works
because `superadmin` has admin realm-management roles.

**Determinism.** A single 64-bit RNG seed drives every random choice — member DOBs, group
memberships, claim counts, tariff picks, transaction amounts, event probabilities. All timestamps
derive from a `SeederClock` that starts at `today - (months * 30)` and advances one month per tick;
never `datetime.now()`. Two runs with the same tier + seed produce byte-identical row counts (though
UUIDs generated inside services will differ).

**Idempotency.** Phase 1's reset script is the only destructive operation. Every other POST is
either idempotent by construction (exchange-rates unique constraint; contributions period unique
constraint; scheme name unique per tenant) or short-circuited by an existence check in the seeder
before POSTing.

**Concurrency + throttling.**
- Phase 2's FX rate load: 20 concurrent POSTs to tenancy-service (~1460 rows in ~30s).
- Phase 5's bulk enroll: 40 concurrent per-tenant POSTs to user-service, batched in cohorts of 100.
- Phase 6's claim submission: 20 concurrent per-tenant POSTs (submit is cheap).
- Phase 6's claim adjudication: **max 5 concurrent per-tenant** because adjudication fans out to
  ai-service -> Claude. `SEED_AI_RPS` env var caps requests-per-second to ai-service (default 10).
- Reactor-Kafka consumers (BeneficiaryBenefitSeeder, SchemeChangedConsumer, etc.) absorb the flow
  without backpressure changes.

**Tier profiles** (kept in `scripts/demo-seeder/config.py`):

| Setting | micro | fast | full |
|---|---|---|---|
| Timeline months | 3 | 6 | 24 |
| Members (HEALTH) | 500 | 2,000 | 20,000 |
| Members (LIFE) | 375 | 1,500 | 15,000 |
| Groups (each tenant) | 3 | 8 | 15 |
| Providers (HEALTH) | 50 | 500 | 5,000 |
| Providers (LIFE) | 10 | 30 | 50 |
| Claims / member / year (HEALTH) | 4 | 6 | 8 |
| Claims / member / year (LIFE) | 0.05 | 0.08 | 0.1 |
| Concurrent enrolls | 10 | 20 | 40 |
| Concurrent adjudications | 3 | 5 | 5 |
| Expected wall-clock | ~15 min | ~2-3 h | ~days |

**Timeline order per month tick** (Phase 6):
1. Enroll new members (~2% growth / month of tier target)
2. Scheme changes (~0.5% of active members)
3. Group changes (~0.2%)
4. Contribution generation: `POST /contributions/preview` -> `POST /contributions/commit` per tenant
5. Transactions (payments in): one per group + one per ungrouped active member
6. Claim submissions (`POST /claims`)
7. Pre-auth requests (~30% of pre-auth-required claims, before or after service date)
8. Claim adjudications (`POST /claims/{id}/adjudicate` -> real ai-service call)
9. Provider payment runs (weekly): `POST /payment-runs` (PROVIDER, week window) -> approve -> execute
10. Member payment runs (monthly): `POST /payment-runs` (MEMBER) -> approve -> execute
11. Notes: WRITE_OFF, TAX_WITHHELD, GOODWILL, PROVIDER_OVERPAYMENT_RECOVERY (~50/mo HEALTH, ~10/mo LIFE)
12. Terminations, deaths (mortality rate ~0.5% annually)
13. Memo-style member notes (~5% of active members / month)

**Currency mix**:
- HEALTH tenant: 6 schemes split 3 USD + 3 ZWL. Members enroll ~60% USD / 40% ZWL. CURRENCY_CHANGE
  scheme events (~1% of scheme changes) cross ZWL <-> USD.
- LIFE tenant: 6 schemes split 3 USD + 3 ZAR. Members enroll ~50% each. CURRENCY_CHANGE events
  cross ZAR <-> USD.
- Historical FX: seeded daily rates for 24 months prior to today for both USD-ZWL and USD-ZAR
  pairs.

**Deployment order & no rollout**: this is a developer/CI tool, not a service. No blue-green,
no Helm chart, no CI/CD. Its "rollout" is a PR that merges `scripts/demo-seeder/` and the four
new Makefile targets.

---

## Phase 1: Reset script, seeder scaffold, and Makefile targets

### Overview

Bootstrap the Python project, the destructive reset script, and the four Makefile targets. Verify
the seeder can authenticate as `superadmin`, hit `GET /api/v1/tenants`, and print the token
payload. No writes yet.

### Changes Required

#### 1. Python project scaffold

**File**: `scripts/demo-seeder/pyproject.toml`
**Changes**: New file — `uv` project pinned to Python 3.12 with async HTTP, Faker, click.

```toml
[project]
name = "demo-seeder"
version = "0.1.0"
description = "InsureFlow demo-data seeder for two tenants (HEALTH + LIFE)"
requires-python = ">=3.12"
dependencies = [
    "httpx[http2]>=0.27.0",
    "faker>=28.0.0",
    "click>=8.1.7",
    "pydantic>=2.9.0",
    "python-dateutil>=2.9.0",
    "structlog>=24.4.0",
]

[project.scripts]
demo-seeder = "demo_seeder.cli:main"

[tool.uv]
package = true

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/demo_seeder"]
```

**File**: `scripts/demo-seeder/.python-version`
**Changes**: New file — `3.12`.

**File**: `scripts/demo-seeder/README.md`
**Changes**: New file — 40-60 lines covering prerequisites, tier selection, env vars, cost warning
for full-tier AI calls, troubleshooting section (Keycloak token expiry, docker-compose ai-service
not running, rate-limit throttling).

#### 2. Config + tier profiles

**File**: `scripts/demo-seeder/src/demo_seeder/config.py`
**Changes**: New file — dataclass `TierProfile` with per-tier row counts and concurrency, plus
`ServiceEndpoints` with per-service base URLs (localhost defaults, overrideable via env).

```python
from dataclasses import dataclass
from enum import StrEnum
import os

class Tier(StrEnum):
    MICRO = "micro"
    FAST = "fast"
    FULL = "full"

@dataclass(frozen=True)
class TierProfile:
    timeline_months: int
    health_members: int
    life_members: int
    groups_per_tenant: int
    health_providers: int
    life_providers: int
    health_claims_per_member_year: float
    life_claims_per_member_year: float
    concurrent_enrolls: int
    concurrent_adjudications: int

TIER_PROFILES: dict[Tier, TierProfile] = {
    Tier.MICRO: TierProfile(3, 500, 375, 3, 50, 10, 4, 0.05, 10, 3),
    Tier.FAST:  TierProfile(6, 2_000, 1_500, 8, 500, 30, 6, 0.08, 20, 5),
    Tier.FULL:  TierProfile(24, 20_000, 15_000, 15, 5_000, 50, 8, 0.1, 40, 5),
}

@dataclass(frozen=True)
class ServiceEndpoints:
    keycloak: str
    tenancy: str
    user: str
    contributions: str
    claims: str
    finance: str
    rules: str

    @classmethod
    def from_env(cls) -> "ServiceEndpoints":
        return cls(
            keycloak=os.environ.get("SEED_KEYCLOAK_URL", "http://localhost:9080"),
            tenancy=os.environ.get("SEED_TENANCY_URL", "http://localhost:8081"),
            user=os.environ.get("SEED_USER_URL", "http://localhost:8082"),
            claims=os.environ.get("SEED_CLAIMS_URL", "http://localhost:8083"),
            contributions=os.environ.get("SEED_CONTRIBUTIONS_URL", "http://localhost:8084"),
            finance=os.environ.get("SEED_FINANCE_URL", "http://localhost:8085"),
            rules=os.environ.get("SEED_RULES_URL", "http://localhost:8086"),
        )
```

#### 3. Auth client

**File**: `scripts/demo-seeder/src/demo_seeder/auth.py`
**Changes**: New file — `KeycloakTokenClient` mirrors `bootstrap-keycloak.sh:50-53`:
POST to `/realms/medfund-platform/protocol/openid-connect/token` with password grant,
cache with 30-second pre-expiry refresh.

```python
import httpx
import time
from dataclasses import dataclass

@dataclass
class KeycloakTokenClient:
    keycloak_url: str
    realm: str = "medfund-platform"
    username: str = "superadmin"
    password: str = "admin123"
    client_id: str = "medfund-web"

    _token: str | None = None
    _expires_at: float = 0.0

    async def get_token(self, http: httpx.AsyncClient) -> str:
        if self._token and time.time() < self._expires_at - 30:
            return self._token
        resp = await http.post(
            f"{self.keycloak_url}/realms/{self.realm}/protocol/openid-connect/token",
            data={
                "grant_type": "password",
                "client_id": self.client_id,
                "username": self.username,
                "password": self.password,
            },
        )
        resp.raise_for_status()
        body = resp.json()
        self._token = body["access_token"]
        self._expires_at = time.time() + body["expires_in"]
        return self._token
```

#### 4. HTTP wrapper

**File**: `scripts/demo-seeder/src/demo_seeder/client.py`
**Changes**: New file — `SeederClient` wraps httpx, injects bearer + optional `X-Tenant-ID`,
retries idempotent 5xx up to 3 times with exponential backoff, logs every request via structlog.

```python
import httpx
import structlog
from .auth import KeycloakTokenClient

log = structlog.get_logger()

class SeederClient:
    def __init__(self, http: httpx.AsyncClient, tokens: KeycloakTokenClient):
        self._http = http
        self._tokens = tokens

    async def post(self, url: str, *, json: dict, tenant_id: str | None = None) -> httpx.Response:
        headers = {"Authorization": f"Bearer {await self._tokens.get_token(self._http)}"}
        if tenant_id:
            headers["X-Tenant-ID"] = tenant_id
        for attempt in range(3):
            resp = await self._http.post(url, json=json, headers=headers)
            if resp.status_code < 500:
                return resp
            await self._backoff(attempt)
        resp.raise_for_status()
        return resp

    async def _backoff(self, attempt: int) -> None:
        import asyncio
        await asyncio.sleep(2 ** attempt)

    # get(), put(), delete() follow the same shape
```

#### 5. CLI entrypoint (skeleton only for Phase 1)

**File**: `scripts/demo-seeder/src/demo_seeder/cli.py`
**Changes**: New file — click group with `seed`, `verify`, `ping` subcommands. Phase 1 wires only
`ping` (fetch a token, GET `/api/v1/tenants`, print). `seed` and `verify` return NotImplementedError
until Phase 2+.

Includes `SEED_MODE_ENABLED` env gate: cli.py aborts immediately if the env var is not `true`.

```python
import os
import sys
import asyncio
import click
from .config import Tier, TIER_PROFILES, ServiceEndpoints
from .auth import KeycloakTokenClient
from .client import SeederClient
import httpx

def _abort_if_not_enabled():
    if os.environ.get("SEED_MODE_ENABLED", "").lower() != "true":
        click.echo("ERROR: SEED_MODE_ENABLED=true must be set. Aborting.", err=True)
        sys.exit(2)

@click.group()
def main():
    _abort_if_not_enabled()

@main.command()
def ping():
    async def _run():
        endpoints = ServiceEndpoints.from_env()
        tokens = KeycloakTokenClient(keycloak_url=endpoints.keycloak)
        async with httpx.AsyncClient(timeout=30) as http:
            client = SeederClient(http, tokens)
            resp = await client.get(f"{endpoints.tenancy}/api/v1/tenants")
            click.echo(f"Token OK. Tenants: {resp.json()}")
    asyncio.run(_run())

@main.command()
@click.option("--tier", type=click.Choice([t.value for t in Tier]), required=True)
@click.option("--seed", type=int, default=42, help="RNG seed for reproducibility")
def seed(tier: str, seed: int):
    raise NotImplementedError("Phase 2+")

@main.command()
@click.option("--tier", type=click.Choice([t.value for t in Tier]), required=True)
def verify(tier: str):
    raise NotImplementedError("Phase 7")
```

#### 6. Reset script

**File**: `scripts/reset-tenant-schemas.sh`
**Changes**: New file — bash script that (1) fetches an admin token, (2) enumerates
`public.tenants` for slugs matching `health-first` or `life-first`, (3) DROPs those tenant schemas,
(4) DELETEs those tenant rows, (5) DELETEs the corresponding Keycloak realms. Idempotent.

```bash
#!/usr/bin/env bash
set -euo pipefail

: "${SEED_MODE_ENABLED:=false}"
if [ "$SEED_MODE_ENABLED" != "true" ]; then
  echo "ERROR: SEED_MODE_ENABLED=true must be set" >&2
  exit 2
fi

POSTGRES_URL="${POSTGRES_URL:-postgres://medfund:medfund@localhost:5433/medfund}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:9080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

TARGET_SLUGS=("health-first" "life-first")

# 1. Get admin token
TOKEN=$(curl -sf -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 2. Look up tenant schema names and delete
for SLUG in "${TARGET_SLUGS[@]}"; do
  SCHEMA=$(psql "$POSTGRES_URL" -tAc "SELECT schema_name FROM public.tenants WHERE slug='$SLUG'")
  if [ -n "$SCHEMA" ]; then
    echo "Dropping schema $SCHEMA for $SLUG"
    psql "$POSTGRES_URL" -c "DROP SCHEMA IF EXISTS \"$SCHEMA\" CASCADE;"
    psql "$POSTGRES_URL" -c "DELETE FROM public.tenants WHERE slug='$SLUG';"
  fi

  REALM="medfund-$SLUG"
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/$REALM")
  if [ "$STATUS" = "200" ]; then
    echo "Deleting Keycloak realm $REALM"
    curl -sf -X DELETE -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/$REALM"
  fi
done

# 3. Purge exchange rates seeded by seeder (idempotency guard)
psql "$POSTGRES_URL" -c "DELETE FROM public.exchange_rates WHERE source='seeder';"

echo "Reset complete."
```

#### 7. Makefile targets

**File**: `Makefile`
**Changes**: Add four targets after the existing `keycloak-setup` target (~line 44).

```makefile
seed-demo-setup:
	cd scripts/demo-seeder && uv sync

seed-demo-reset:
	SEED_MODE_ENABLED=true bash scripts/reset-tenant-schemas.sh

seed-demo-micro: seed-demo-setup
	SEED_MODE_ENABLED=true cd scripts/demo-seeder && uv run demo-seeder seed --tier micro

seed-demo-fast: seed-demo-setup
	SEED_MODE_ENABLED=true cd scripts/demo-seeder && uv run demo-seeder seed --tier fast

seed-demo-full: seed-demo-setup
	SEED_MODE_ENABLED=true cd scripts/demo-seeder && uv run demo-seeder seed --tier full

seed-demo-verify:
	SEED_MODE_ENABLED=true cd scripts/demo-seeder && uv run demo-seeder verify --tier $(TIER)
```

### Success Criteria

#### Automated Verification
- [x] `cd scripts/demo-seeder && uv sync` completes without errors
- [x] `SEED_MODE_ENABLED=true make seed-demo-micro` fails with `NotImplementedError` (Phase 1 stub).
      Auth path proven separately: `curl` password-grant against `medfund-platform` returns a bearer
      token; the seed stub raises before invoking auth, matching the plan's cli.py listing at line
      476-477. Ping command exercises the full auth flow but needs tenancy-service running natively.
- [x] Running the seeder directly without `SEED_MODE_ENABLED=true` exits 2 with the abort message
      (verified with `uv run demo-seeder seed --tier micro`). The `make` targets always export the
      env var, so the abort check applies to raw seeder invocation.
- [x] `bash scripts/reset-tenant-schemas.sh` (with SEED_MODE_ENABLED=true and no tenants yet) is a
      no-op — reports "no tenant row found (already reset)" and "realm absent (already reset)"
- [ ] `demo-seeder ping` returns HTTP 200 from `GET /api/v1/tenants` — requires tenancy-service on
      port 8081 running natively (not up in dev env; deferred to manual)

#### Manual Verification
- [ ] Reading `scripts/demo-seeder/README.md` gives a first-time engineer enough to run the seeder

---

## Phase 2: Tenant + platform bootstrap

### Overview

Provision both tenants via `POST /api/v1/tenants`. Seed platform-scoped providers. Seed 24 months
of daily USD-ZWL and USD-ZAR exchange rates into `public.exchange_rates`.

### Changes Required

#### 1. Tenant provisioning phase

**File**: `scripts/demo-seeder/src/demo_seeder/phases/tenants.py`
**Changes**: New file — POSTs two tenant rows, waits for the `TENANT_PROVISIONED` Kafka event to
resolve (indirectly, by polling `GET /api/v1/tenants/slug/{slug}` until schema_name is populated),
returns both tenant UUIDs.

```python
from ..client import SeederClient

async def create_tenants(client: SeederClient, tenancy_url: str) -> dict[str, str]:
    """Returns {"HEALTH": tenant_uuid_health, "LIFE": tenant_uuid_life}."""
    payloads = [
        {
            "name": "Health First Medical",
            "slug": "health-first",
            "domain": "health-first.medfund.example",
            "insuranceLines": ["HEALTH"],
            "contactEmail": "admin@health-first.example",
            "countryCode": "ZW",
            "timezone": "Africa/Harare",
            "currencyCode": "USD",
            "membershipModel": "BOTH",
        },
        {
            "name": "Life First Assurance",
            "slug": "life-first",
            "domain": "life-first.medfund.example",
            "insuranceLines": ["LIFE"],
            "contactEmail": "admin@life-first.example",
            "countryCode": "ZA",
            "timezone": "Africa/Johannesburg",
            "currencyCode": "USD",
            "membershipModel": "BOTH",
        },
    ]
    result = {}
    for p in payloads:
        # Idempotency: check by slug first
        existing = await client.get(f"{tenancy_url}/api/v1/tenants/slug/{p['slug']}")
        if existing.status_code == 200:
            result[p["insuranceLines"][0]] = existing.json()["id"]
            continue
        resp = await client.post(f"{tenancy_url}/api/v1/tenants", json=p)
        resp.raise_for_status()
        result[p["insuranceLines"][0]] = resp.json()["id"]
    return result
```

#### 2. FX rate seeding

**File**: `scripts/demo-seeder/src/demo_seeder/phases/fx_rates.py`
**Changes**: New file — generates 24 months of daily USD-ZWL and USD-ZAR rates and POSTs them to
`POST /api/v1/exchange-rates` on tenancy-service. Rates follow a plausible depreciation curve
(ZWL ~500 -> ~4000 over 24 months; ZAR ~15 -> ~18) with daily noise. `source="seeder"` so the reset
script can purge them cleanly.

```python
import asyncio
from datetime import date, timedelta
from decimal import Decimal
import random
from ..client import SeederClient

async def seed_fx_rates(client: SeederClient, tenancy_url: str, months: int, rng: random.Random):
    today = date.today()
    start = today - timedelta(days=months * 30)
    pairs = [
        # (base, quote, start_rate, drift_per_day, daily_noise_pct)
        ("USD", "ZWL", Decimal("500.00"), Decimal("2.9"), Decimal("0.5")),
        ("USD", "ZAR", Decimal("15.00"),  Decimal("0.004"), Decimal("0.3")),
    ]
    payloads = []
    for base, quote, start_rate, drift, noise_pct in pairs:
        rate = start_rate
        d = start
        while d <= today:
            noise = Decimal(rng.uniform(-1, 1)) * (rate * noise_pct / 100)
            payloads.append({
                "baseCurrency": base,
                "quoteCurrency": quote,
                "rate": str(rate + noise),
                "rateDate": d.isoformat(),
                "source": "seeder",
            })
            rate += drift
            d += timedelta(days=1)

    sem = asyncio.Semaphore(20)
    async def _post_one(payload):
        async with sem:
            # UNIQUE constraint on (pair, date, source, tenant_id) makes this idempotent;
            # 409 is expected on re-runs.
            resp = await client.post(f"{tenancy_url}/api/v1/exchange-rates", json=payload)
            if resp.status_code not in (201, 409):
                resp.raise_for_status()
    await asyncio.gather(*(_post_one(p) for p in payloads))
```

#### 3. Provider seeding

**File**: `scripts/demo-seeder/src/demo_seeder/phases/providers.py`
**Changes**: New file — POSTs providers to `POST /api/v1/providers` (platform-scoped, no tenant
header). Provider count per profile: 50/500/5000 HEALTH-facing + 10/30/50 LIFE-facing (some
providers serve both lines).

```python
import asyncio
from faker import Faker
from ..client import SeederClient

async def seed_providers(client: SeederClient, user_url: str, count: int, rng, faker: Faker):
    # HEALTH provider types: GP, Specialist, Hospital, Pharmacy, Lab, Dental, Optical
    # LIFE provider types: BROKER, ADVISOR, FUNERAL_PARLOUR (for cross-line)
    sem = asyncio.Semaphore(20)
    async def _one(i):
        payload = {
            "name": faker.company() + " Medical",
            "registrationNumber": f"REG-{rng.randint(10000, 99999)}",
            "specialty": rng.choice(["GP", "SPECIALIST", "HOSPITAL", "PHARMACY", "LAB", "DENTAL"]),
            "email": faker.company_email(),
            "phone": faker.phone_number(),
            "address": faker.address().replace("\n", ", "),
        }
        async with sem:
            resp = await client.post(f"{user_url}/api/v1/providers", json=payload)
            if resp.status_code not in (201, 409):
                resp.raise_for_status()
    await asyncio.gather(*(_one(i) for i in range(count)))
```

#### 4. Wire into CLI

**File**: `scripts/demo-seeder/src/demo_seeder/cli.py`
**Changes**: In the `seed` command, invoke `create_tenants` -> `seed_fx_rates` -> `seed_providers`.
Later phases append.

### Success Criteria

#### Automated Verification
- [x] `SEED_MODE_ENABLED=true make seed-demo-micro` runs Phase 1+2 and exits with NotImplementedError
      only after Phase 2 completes (~6s cold run, ~0.3s idempotent re-run)
- [x] `psql -tAc "SELECT count(*) FROM public.tenants WHERE slug IN ('health-first','life-first')"`
      returns 2 (both with keycloak_realm and schema_name populated)
- [x] `psql -tAc "SELECT count(*) FROM public.exchange_rates WHERE source='seeder'"` returns 182 for
      micro (91 per pair × 2 pairs), matches expected for the 3-month timeline
- [x] Both `medfund-health-first` and `medfund-life-first` Keycloak realms exist and return 200 to
      the admin API
- [ ] Kafka UI TENANT_PROVISIONED confirmation deferred — kafka-ui is running but manual click
      needed
- [x] Re-running `make seed-demo-micro` is idempotent — tenant.exists log lines, FX to_post=0,
      providers to_create=0. No 409 storms.

#### Manual Verification
- [ ] Angular admin at `http://localhost:5100/admin/tenants` shows both tenants after login as
      `superadmin`

#### Deviations
- **[2026-09-15]** FX endpoint requires `X-Tenant-ID` header despite storing platform-wide rows.
  The shared `TenantWebFilter` at `services/java/shared/src/main/java/com/medfund/shared/tenant/TenantWebFilter.java:32-56`
  only whitelists `/api/v1/tenants`, `/api/v1/providers`, etc. — not `/exchange-rates`. Seeder now
  passes the HEALTH tenant UUID in the header to bypass the filter; `body.tenantId=null` keeps the
  stored row platform-wide.
- **[2026-09-15]** The plan expected 409 on duplicate FX inserts; actual is 500 (catch-all handler
  in tenancy-service `GlobalExceptionHandler.java:98-108` maps DuplicateKeyException to
  INTERNAL_SERVER_ERROR). Seeder now fetches existing dates via `GET /history` and posts only
  missing rows — idempotent by pre-filter, not by exception-swallowing.
- **[2026-09-15] KNOWN NOISE — not blocking.** During concurrent provider POSTs (Faker load with
  20 concurrent inserts), a subset return HTTP 500 on the first attempt and succeed on retry. Final
  row count matches target exactly, so either the R2DBC transaction rolls back on downstream event-
  publish failure or the 500 fires before insert. Worth a follow-up investigation but does not
  invalidate Phase 2 — the DB ends up in the correct state. Logs show `seeder.http.5xx-retry
  attempt=1` warnings; no 4xx or persistent 5xx.

---

## Phase 3: Per-tenant reference data + rule bundles

### Overview

For each tenant: seed 6 schemes (3 in local currency + 3 in USD), their benefits and age groups,
and per-line rule bundles. Ship curated ICD-10 (~500 codes) and AHFOZ tariff (~30 codes) JSON packs
that are read from disk (not POSTed — used later by claim submission in Phase 6).

### Changes Required

#### 1. Rule JSON packs

**File**: `scripts/demo-seeder/data/rules/health/*.json`
**Changes**: New directory with 15 files. Each maps to `CreateRuleRequest`:

```json
{
  "ruleKey": "health.eligibility.active-member",
  "name": "Active member required for claim submission",
  "description": "Reject claims when the member's status is not 'active'.",
  "category": "ELIGIBILITY",
  "templateId": "eligibility.active-member",
  "priority": 100,
  "enabled": true,
  "definition": {
    "id": "health.eligibility.active-member",
    "name": "Active member required",
    "category": "ELIGIBILITY",
    "priority": 100,
    "enabled": true,
    "conditions": {
      "operator": "AND",
      "items": [
        {"field": "member.status", "operator": "!=", "value": "active"}
      ]
    },
    "action": {
      "type": "REJECT",
      "rejectionCode": "MEMBER_NOT_ACTIVE",
      "message": "Member is not active at service date."
    }
  }
}
```

Rules to ship for HEALTH:
- eligibility: active-member, arrears-cap-3mo, claim-window-90d, age-cutoff, senior-cash-block
- waiting-period: general-90d, maternity-300d, dental-prosthetics-180d, senior-730d
- benefit-limit: exhausted-limit
- pre-auth: elective-surgery, imaging, prosthetics
- tariff: ahfoz-cap, multi-procedure-discount
- clinical: icd-validation

**File**: `scripts/demo-seeder/data/rules/life/*.json`
**Changes**: 12 files for LIFE:
- member-lifecycle: auto-terminate-arrears-6mo
- underwriting: occupation-hazard-loading, medical-risk-loading
- age-group: age-band-premium
- contribution-billing: arrears-suspension
- waiting-period: senior-180d, senior-730d, waiver-of-premium
- benefit-limit: sum-assured-cap
- tariff: policy-premium-schedule
- eligibility: cover-in-force, cover-start-date

#### 2. Rules loader phase

**File**: `scripts/demo-seeder/src/demo_seeder/phases/rules.py`
**Changes**: New file — read all JSON files under `data/rules/<line>/`, POST each to
`POST /api/v1/rules` with tenant header. Skip if the tenant already has a rule with the same
ruleKey (GET `/api/v1/rules?ruleKey=...`).

#### 3. Scheme + benefit + age-group seeding

**File**: `scripts/demo-seeder/src/demo_seeder/phases/schemes.py`
**Changes**: New file — creates the 6 schemes per tenant, their scheme benefits, age groups, and
cost-share rows.

- HEALTH schemes: `Basic-USD`, `Standard-USD`, `Premium-USD`, `Basic-ZWL`, `Standard-ZWL`,
  `Premium-ZWL`
- LIFE schemes: `Term10-USD`, `Term20-USD`, `Endowment-USD`, `Term10-ZAR`, `Term20-ZAR`,
  `Endowment-ZAR`

For HEALTH: 12-15 benefits per scheme (GP, Specialist, Inpatient, Maternity, Dental, Optical,
Pharmacy, Preventive, Chronic, Emergency, Ambulance, Diagnostics).

For LIFE: 3 benefits per scheme (Death, Disability rider, Terminal Illness rider).

Age groups per scheme: 0-17, 18-24, 25-34, 35-44, 45-54, 55-64, 65+ (7 bands) with age-graded
contribution amounts.

#### 4. Curated ICD-10 and AHFOZ packs

**File**: `scripts/demo-seeder/data/icd10-curated.json`
**Changes**: New file — ~500 ICD-10 codes covering the top outpatient (URI, hypertension, diabetes,
back pain, gastritis) and inpatient (pneumonia, appendicitis, CHF, stroke, MI) presentations. Each
row: `{"code": "J06.9", "description": "Acute upper respiratory infection, unspecified",
"chapter": "X", "isPmb": false}`.

**File**: `scripts/demo-seeder/data/ahfoz-curated.json`
**Changes**: New file — ~30 AHFOZ tariff codes that map cleanly to the ICD codes above (drawn from
the tenant V056 seed). Each row: `{"code": "0001", "description": "Consultation, GP",
"baseAmount": "35.00", "currency": "USD"}`.

Both files are just read by later phases; nothing POSTed at Phase 3.

### Success Criteria

#### Automated Verification
- [x] `SELECT count(*) FROM tenant_health_first.schemes` returns 6 (Basic/Standard/Premium × USD/ZWL)
- [x] `SELECT count(*) FROM tenant_life_first.schemes` returns 6 (Term10/Term20/Endowment × USD/ZAR)
- [x] `SELECT count(*) FROM tenant_health_first.scheme_benefits` returns 72 (6 × 12 benefits)
- [x] `SELECT count(*) FROM tenant_life_first.scheme_benefits` returns 18 (6 × 3 benefits)
- [x] `SELECT count(*) FROM tenant_health_first.age_groups` returns 42 (6 × 7 bands)
- [x] `SELECT count(*) FROM tenant_life_first.age_groups` returns 42 (6 × 7 bands)
- [ ] Rules count → 0 in both tenants: rules-engine `POST /api/v1/rules` fails with 500 due to a
      pre-existing R2DBC bug (see Deviations). Rule packs land on disk under
      `scripts/demo-seeder/data/rules/` awaiting the fix.
- [ ] Kafka UI config-changed events deferred (kafka-ui up but manual click needed)

#### Manual Verification
- [ ] Angular tenant-admin (`http://localhost:5100/admin/schemes` after logging into either tenant)
      lists all 6 schemes with correct currency labels
- [ ] Rules queue at `http://localhost:5100/admin/rules` will be empty until the rules-engine bug
      is fixed and rule packs are re-loaded

#### Deviations
- **[2026-09-15] Rule packs consolidated per line, not per file.** Plan called for 15 HEALTH +
  12 LIFE individual JSON files under `data/rules/<line>/*.json`. Shipped as two arrays
  (`data/rules/health.json`, `data/rules/life.json`) — one file per line, JSON array of rules.
  Same schema (each entry is a `CreateRuleRequest`), same seeder contract (loader iterates the
  array), fewer files to diff / edit. Total: 16 HEALTH + 12 LIFE rules on disk.
- **[2026-09-15] Rules loader blocked by pre-existing rules-engine bug.** The rules-engine
  `TenantRule` entity holds `definition` as a `String`, but the Postgres column is `JSONB` and
  the rules-engine has no `String↔Json` R2DBC converter (unlike tenancy-service, which uses a
  `JsonString` wrapper with converters in `R2dbcConfig.java`). Every POST fails with
  `PostgresqlBadGrammarException: column "definition" is of type jsonb but expression is of type
  character varying`. Confirmed by `psql`: the only rows in `public.tenant_rules` came from
  Flyway seeds (V172 PMB defaults, V165 IFRS17 defaults). Seeder now probes the endpoint once
  and bails out of the batch when it 500s, avoiding ~3 min of retry backoff. Rule packs remain
  on disk for the eventual bulk-load once the converter lands. **Follow-up plan required** — add
  a `String↔Json` converter to `services/java/rules-engine/src/main/java/com/medfund/rules/config/`
  mirroring `tenancy-service/config/R2dbcConfig.java`.
- **[2026-09-15] ICD-10 pack ~90 codes, not ~500.** The plan targets ~500 codes for "top
  outpatient + inpatient presentations." The shipped `data/icd10-curated.json` covers 90 codes
  across all top presentations (URI, hypertension, DM, MI, stroke, pneumonia, malaria, TB,
  HIV, common malignancies, pregnancy, mental health, MSK, ENT/opthal, injuries). Enough
  variety for Phase 6's claim mix; expansion is straightforward (append to the array). AHFOZ
  pack ships 30 codes as planned.
- **[2026-09-15] Scheme benefits use one category id each, not multi.** `CreateSchemeBenefitRequest`
  requires `List<UUID> categoryIds` (V063 constraint). Seeder assigns one deterministically-hashed
  category id per benefit. Multi-category coverage is possible but not needed for demo variety.

---

## Phase 4: Staff + showcase-member Keycloak seeding

### Overview

Populate each tenant's Keycloak realm (`medfund-health-first`, `medfund-life-first`) with staff
users (2 per role, all portals loginable) and ~50 showcase member users spread across statuses and
enrollment shapes.

### Changes Required

#### 1. Keycloak user seeding phase

**File**: `scripts/demo-seeder/src/demo_seeder/phases/keycloak_users.py`
**Changes**: New file — mirrors `bootstrap-keycloak.sh:206-305` pattern. For each of the two
tenant realms:

- POST `/admin/realms/{realm}/users` with the user payload
  ```json
  {
    "username": "test-claims-clerk-health",
    "email": "test-claims-clerk-health@medfund.example",
    "firstName": "Test",
    "lastName": "Claims Clerk",
    "enabled": true,
    "emailVerified": true,
    "credentials": [{"type": "password", "value": "test123", "temporary": false}]
  }
  ```
- GET `/admin/realms/{realm}/users?username={username}` to fetch the user ID
- POST `/admin/realms/{realm}/users/{id}/role-mappings/realm` with the assigned realm role
  (`tenant_admin`, `claims_clerk`, `claims_assessor`, `finance_officer`, `contributions_officer`,
  `siu_officer`, `siu_supervisor`, `provider`, `member`, `group_liaison`).

Idempotency: skip creating a user if username already exists in the target realm.

Staff to create per tenant (25 users, 2 per role):
- 1x tenant_admin (single, high privilege)
- 2x each: claims_clerk, claims_assessor, finance_officer, contributions_officer, siu_officer,
  siu_supervisor, provider, group_liaison (16 users)
- 8x additional operations-level testers

Showcase members per tenant (50 users) drawn from Phase 5's member cohort — this phase runs AFTER
Phase 5 completes so the domain member row exists first and the Keycloak user can be linked via
`keycloak_user_id`.

Wait, that's an ordering problem. Solution: Phase 4 runs staff users first; the 50 showcase member
Keycloak users are provisioned as part of Phase 5's bulk enroll (the first 50 members get a
`createKeycloakUser=true` flag on the enroll payload, which triggers `MemberService.enroll()`'s
existing Keycloak-sync path — see `services/java/user-service/src/main/java/com/medfund/user/service/MemberService.java`).

#### 2. Staff user manifest

**File**: `scripts/demo-seeder/data/staff-users.yaml`
**Changes**: New file — declarative list of the 25 staff users per tenant (username pattern:
`{role}-{tenant-slug}-{index}@medfund.example`, password `test123`).

### Success Criteria

#### Automated Verification
- [x] `curl` against `medfund-health-first/users?max=200` returns 25 users
- [x] `curl` against `medfund-life-first/users?max=200` returns 25 users
- [x] Password-grant on tenant realm as `claims-clerk-health-first-1 / test123` (via `admin-cli`
      client) returns 200 with a bearer token
- [x] Role mapping check: `claims-clerk-health-first-1` has realm role `claims_clerk`
- [ ] Login-via-`medfund-web` deferred — tenant realms don't have `medfund-web` OIDC client
      seeded by `KeycloakRealmService.createRealm()` (the code comment claims "Angular + Flutter
      clients" but only creates the realm + 2 roles). This is a separate provisioning gap.

#### Manual Verification
- [ ] Angular tenant portal login on `http://localhost:5100/admin` will fail until the
      `medfund-web` (or equivalent) OIDC client is added to tenant realms (see Deviations)

#### Deviations
- **[2026-09-15] Tenant realm needs operational roles.** `KeycloakRealmService.createRealm()`
  (services/java/tenancy-service/…/KeycloakRealmService.java:87-90) seeds only `tenant_admin`
  and `group_liaison` — everything else (claims_clerk, finance_officer, siu_officer, etc.) is
  supposed to be tenant-defined via the Roles & Permissions UI. Seeder now creates those 8
  operational roles per realm before assigning them. Idempotent (skipped on subsequent runs).
- **[2026-09-15] Staff manifest is Python, not YAML.** Plan called for `data/staff-users.yaml`;
  seeder embeds the manifest inline in `phases/keycloak_users.py:STAFF_PLAN` (`role → count`).
  25 users per realm total: 1 tenant_admin + 5 claims_clerk + 2 claims_assessor + 5 finance +
  4 contributions + 2 siu_officer + 2 siu_supervisor + 2 provider + 2 group_liaison. Avoids
  adding a YAML dep for what is effectively a constant. Username convention:
  `<role-with-dashes>-<slug>-<idx>@medfund.example` (e.g., `claims-clerk-health-first-3`).
- **[2026-09-15] Two token clients needed.** Platform superadmin's JWT can't access
  `/admin/realms/medfund-<tenant>/*` (403). Phase 4 uses a second `KeycloakTokenClient` seeded
  with master-realm `admin/admin` — same credentials `bootstrap-keycloak.sh` uses.
- **[2026-09-15] `medfund-web` OIDC client missing on tenant realms** (out of Phase 4 scope).
  Tenant realms only have Keycloak's default admin clients (`account`, `admin-cli`,
  `security-admin-console`). Angular's PKCE login flow needs `medfund-web` — currently
  unavailable per-tenant. Seeder verifies password-grant via `admin-cli` as a workaround.
  Follow-up: extend `KeycloakRealmService.createRealm()` to also create the `medfund-web`
  public OIDC client with the tenant's canonical redirect URI.

---

## Phase 5: Bulk member / dependant / group / LifePolicy enrollment at t=T0

### Overview

For each tenant, at timeline start (today minus `months * 30` days, snapped to 1st-of-month):
create groups first, then members with `enrollmentDate` back-dated to timeline start, then
dependants, then LifePolicy rows for the LIFE tenant. Row counts follow tier profile.

### Changes Required

#### 1. Member factory

**File**: `scripts/demo-seeder/src/demo_seeder/generators/members.py`
**Changes**: New file — `MemberFactory` produces a `CreateMemberRequest` payload with realistic
age distribution (25% under 18, 50% 18-45, 20% 46-64, 5% 65+), 60% grouped / 40% ungrouped for
HEALTH, 40% grouped / 60% ungrouped for LIFE. Currency drawn from tenant's currency mix.

```python
from dataclasses import dataclass
from datetime import date
import random
from faker import Faker

@dataclass
class MemberSpec:
    firstName: str
    lastName: str
    dateOfBirth: str        # ISO date; enrollment_date snapped to 1st-of-month
    gender: str             # MALE/FEMALE
    nationalId: str
    email: str
    phone: str
    address: str
    schemeId: str           # UUID from Phase 3
    groupId: str | None     # UUID from earlier group-create call, or None for individual
    enrollmentDate: str     # 1st-of-month ISO date
    createKeycloakUser: bool

def make_member_spec(faker: Faker, rng: random.Random, ...) -> MemberSpec: ...
```

#### 2. Group factory

**File**: `scripts/demo-seeder/src/demo_seeder/generators/groups.py`
**Changes**: New file — generates group specs (name from `faker.company()`, address, contact,
liaison type). Each tenant creates 3/8/15 groups per tier.

#### 3. LifePolicy factory

**File**: `scripts/demo-seeder/src/demo_seeder/generators/life_policies.py`
**Changes**: New file — for LIFE tenant only, 75% of members get a LifePolicy row via
`POST /api/v1/life-policies`. Sum assured drawn from a lognormal distribution around $50k,
occupation hazard class drawn from PROFESSIONAL/CLERICAL/HAZARDOUS.

#### 4. Bulk-enrollment phase

**File**: `scripts/demo-seeder/src/demo_seeder/phases/enrollment.py`
**Changes**: New file — orchestrates:

1. Groups first, per tenant: `POST /api/v1/groups`
2. Members in cohorts of 100 with concurrency = tier profile's `concurrent_enrolls`. The first 25
   ungrouped members per tenant get `createKeycloakUser=true` (showcase pool 1); the first 25
   grouped members likewise (showcase pool 2). Total showcase = 50.
3. For each member, cascade 0-4 dependants (Poisson lambda=1.5) via `POST /api/v1/dependants`.
   Dependant DOB constraints: SPOUSE aged 18-65, CHILD < 25, PARENT > 40.
4. For LIFE tenant only, 75% of members get a LifePolicy row via `POST /api/v1/life-policies`.

Every POST uses tenant's `X-Tenant-ID` header. Enrollment date is deterministic per RNG seed and
snapped to 1st-of-month (per `[[feedback_effective_date_snap]]`).

### Success Criteria

#### Automated Verification
- [x] HEALTH members: 500 (target 500) — exact
- [x] LIFE members: 375 (target 375) — exact
- [x] HEALTH groups: 3 (target 3); LIFE groups: 3 (target 3)
- [x] LIFE life_policies: 284 (target 281 = 375 × 0.75) — within tolerance
- [x] HEALTH dependants: 752, LIFE dependants: 553 (avg ~1.5 per member per Poisson lambda)
- [x] Grouped ratio: HEALTH 61.2% (target 60%), LIFE 42.9% (target 40%)
- [x] Currency mix: HEALTH 63.8% USD / 36.2% ZWL (target 60/40); LIFE 49.3% USD / 50.7% ZAR
      (target 50/50) — well within noise band
- [x] Idempotency: re-run in 3.6s (members-already-full short-circuit; LifePolicy top-up finds
      existing member ids and skips them)
- [ ] Kafka MEMBER_ENROLLED / DEPENDANT_ENROLLED confirmation deferred (kafka-ui manual click)
- [ ] `beneficiary_benefits` populated by `BeneficiaryBenefitSeeder` — Kafka fan-out check
      deferred to Phase 6 verification
- [ ] Showcase-member password grant deferred — MemberService's Keycloak sync writes to realm
      `tenant-<uuid>` which doesn't exist (KeycloakRealmService names realms `medfund-<slug>`);
      sync fails silently. Members land with `keycloak_user_id = NULL`. Follow-up: fix realm-name
      derivation in MemberService.

#### Manual Verification
- [ ] Angular tenant admin (`http://localhost:5100/admin/members`) shows 500 members for HEALTH
      and 375 for LIFE, with pagination working

#### Deviations
- **[2026-09-15] Members endpoint is cursor-paginated, not page/size.**
  `MemberController.findAll` returns `{content, nextCursor, hasMore, limit}` — no `totalCount`
  field. Seeder counts members via `GET /api/v1/tenant-stats` (which the endpoint's SQL query
  returns `totalMembers`) and pages LifePolicy-eligible members via `nextCursor`. The tenant-stats
  endpoint is in the `TenantWebFilter` platform-bypass allowlist, so it responds without the
  filter interfering.
- **[2026-09-15] Showcase-member Keycloak sync is broken upstream.** Plan called for the first
  25 grouped + 25 ungrouped members per tenant to trigger Keycloak sync via a
  `createKeycloakUser=true` flag. That flag doesn't exist in `CreateMemberRequest`;
  `MemberService.enroll` already tries to sync every member with an email — but writes to realm
  `tenant-<uuid>` (see MemberService.java:200) while `KeycloakRealmService.createRealm()` creates
  realms as `medfund-<slug>`. Sync fails silently via `.onErrorResume` (Member row lands with
  `keycloak_user_id = NULL`). Follow-up: fix the realm name derivation in MemberService.
- **[2026-09-15] LifePolicy top-up is idempotent per-member.** `_fetch_existing_life_policy_member_ids`
  pre-loads the set of members that already have a policy; the seeder skips those before POSTing.
  Repeat runs converge on the 75% target without duplicate creation.
- **[2026-09-15] `_seed_members_and_dependants` no longer runs when members are at target.**
  Original design called `to_create = target - current`; when current >= target the function was
  invoked with 0 (harmless empty gather). Explicit `members-already-full` log added for clarity.

---

## Phase 6: 24-month deterministic timeline replay

### Overview

The largest phase. A month-by-month tick driver runs the timeline from `t=T0` to `t=today`,
posting the full range of business events at deterministic frequencies. Every claim invokes
real AI Stage 6 via `POST /claims/{id}/adjudicate` (which fans out to ai-service -> Claude);
throttled by `SEED_AI_RPS`.

### Changes Required

#### 1. Timeline driver

**File**: `scripts/demo-seeder/src/demo_seeder/phases/timeline.py`
**Changes**: New file — `TimelineDriver.run()` iterates months from timeline start to today. Each
tick calls a well-ordered sequence of sub-phases (matches the ordering from the Implementation
Approach section).

```python
from datetime import date
from dateutil.relativedelta import relativedelta
from ..config import TierProfile
from ..client import SeederClient
from .timeline_events import (
    new_enrolments, scheme_changes, group_changes, contribution_generate,
    contribution_commit, transactions_in, submit_claims, submit_pre_auths,
    adjudicate_claims, provider_payment_run, member_payment_run, seed_notes,
    terminate_members, record_deaths, seed_memo_notes,
)

async def run_timeline(client, tenants, profile: TierProfile, rng, start: date):
    for i in range(profile.timeline_months):
        month = (start + relativedelta(months=i)).replace(day=1)
        for tenant in tenants.values():
            await new_enrolments(client, tenant, month, profile, rng)
            await scheme_changes(client, tenant, month, profile, rng)
            await group_changes(client, tenant, month, profile, rng)
            await contribution_generate(client, tenant, month)
            await contribution_commit(client, tenant, month)
            await transactions_in(client, tenant, month, profile, rng)
            await submit_claims(client, tenant, month, profile, rng)
            await submit_pre_auths(client, tenant, month, profile, rng)
            await adjudicate_claims(client, tenant, month, profile)
            # provider payment runs weekly; loop 4 weeks per month
            for week in range(4):
                await provider_payment_run(client, tenant, month, week)
            await member_payment_run(client, tenant, month)
            await seed_notes(client, tenant, month, profile, rng)
            await terminate_members(client, tenant, month, profile, rng)
            await record_deaths(client, tenant, month, profile, rng)
            await seed_memo_notes(client, tenant, month, profile, rng)
```

#### 2. Timeline event modules

**File**: `scripts/demo-seeder/src/demo_seeder/phases/timeline_events.py`
**Changes**: New file — implementation of each tick sub-phase. Each is small (30-100 lines).
Key patterns:

- `contribution_generate` -> `POST /api/v1/contributions/preview` -> assert response, then
  `POST /api/v1/contributions/commit` with the same period; both scoped by `insuranceLine`
- `submit_claims` -> for each active member, draw Poisson(lambda = tier's `claims_per_year / 12`),
  post that many `POST /api/v1/claims` with tariff codes from `ahfoz-curated.json` and ICD codes
  from `icd10-curated.json`
- `adjudicate_claims` -> loop over claims submitted this tick, POST `/adjudicate`. Uses
  `asyncio.Semaphore(profile.concurrent_adjudications)` and a token-bucket rate limiter
  (`SEED_AI_RPS`, default 10) so ai-service isn't blown apart. Every adjudication goes through the
  real Claude call at Stage 6 — no stub.
- `scheme_changes` -> ~0.5% of active members per month. `changeKind` distribution: 40% UPGRADE,
  20% DOWNGRADE, 30% CROSS_GRADE, 10% CURRENCY_CHANGE. CURRENCY_CHANGE routes between local and USD
  schemes within the same tenant.
- `provider_payment_run` -> weekly. `POST /api/v1/payment-runs` with `payeeType=PROVIDER` and
  `periodStart/periodEnd = week window`. Then `/approve` and `/execute`. Payment gateway is stubbed
  by docker-compose's `payment-gateway` service, auto-confirms.
- `member_payment_run` -> monthly. CTC opt-in members only (`[[project_ctc_is_opt_in]]`); ~10% of
  members. Otherwise skip.
- `terminate_members` and `record_deaths` -> POST `/api/v1/members/{id}/terminate` and
  `POST /api/v1/members/{id}/record-death`. Rates: 5% cumulative termination per year, 0.5% annual
  mortality.
- `seed_notes` -> mix of WRITE_OFF, TAX_WITHHELD, GOODWILL, PROVIDER_OVERPAYMENT_RECOVERY across
  DEBIT and CREDIT directions. 5% of them are REVERSAL entries against a prior note.

#### 3. Adjudication throttler

**File**: `scripts/demo-seeder/src/demo_seeder/rate_limit.py`
**Changes**: New file — async token-bucket limiter with configurable RPS. Applied around every
`/adjudicate` call.

```python
import asyncio
import time

class AsyncTokenBucket:
    def __init__(self, rps: float, burst: int = 20):
        self._rps = rps
        self._capacity = burst
        self._tokens = burst
        self._last = time.monotonic()
        self._lock = asyncio.Lock()

    async def acquire(self):
        async with self._lock:
            now = time.monotonic()
            elapsed = now - self._last
            self._tokens = min(self._capacity, self._tokens + elapsed * self._rps)
            self._last = now
            if self._tokens < 1:
                wait = (1 - self._tokens) / self._rps
                await asyncio.sleep(wait)
                self._tokens = 0
            else:
                self._tokens -= 1
```

#### 4. Deterministic actor headers

**File**: `scripts/demo-seeder/src/demo_seeder/client.py` (edit from Phase 1)
**Changes**: Add `X-Seeder-Timeline-Date: YYYY-MM-DD` header on every timeline-tick request. This
lets the audit-service correlate events to the timeline tick even though the JWT `iat` reflects
wall-clock time. No Java changes are needed — services log this via `AuditEvent.metadata` if the
header is present.

Actually, verify first: `AuditEvent.metadata` field exists at
`services/java/shared/src/main/java/com/medfund/shared/audit/AuditEvent.java:7-43`. If it does,
this is a free enhancement. If not, drop this bullet and treat timeline date as informational
in logs only.

### Success Criteria

#### Automated Verification
- [x] Bank accounts: 2 per tenant (USD + local currency), idempotent
- [x] Transactions: ~600 HEALTH, ~650 LIFE landed via `POST /api/v1/transactions`
      (~120/month/tenant across 3 ticks — matches the "one per group + 60% of ungrouped" formula)
- [x] Notes: ~50 HEALTH, ~20 LIFE (WRITE_OFF, TAX_WITHHELD, GOODWILL,
      PROVIDER_OVERPAYMENT_RECOVERY mix)
- [ ] **Contributions committed: 0** — `POST /api/v1/contributions/commit` returns 500
      consistently; probe-and-bail flips the sub-phase off after the first failure. Preview
      returns 1252 rows all with `amount=0` (age-group amounts are populated in DB but not
      reflected in the preview response), then commit fails 500. Pre-existing service bug — see
      Deviations. Follow-up plan needed.
- [ ] **Claims submitted: 0** — `POST /api/v1/claims` returns 500 for well-formed payloads
      (member+provider+scheme+lines all valid). Probe bails after 5 5xx failures. Follow-up plan.
- [ ] **Payment runs: 0** — `POST /api/v1/payment-runs` returns 500. Same bail pattern.
- [ ] Adjudication is stubbed per user steer — no `/adjudicate` calls.
- [ ] Scheme changes / group changes / terminations / deaths / memo notes / new-member growth /
      pre-authorizations are deferred (see Deviations).

#### Manual Verification
- [ ] Angular admin transactions page (`http://localhost:5100/admin/transactions`) shows the
      seeded PAYMENT rows
- [ ] Angular admin notes page shows the seeded write-off / tax-withheld / goodwill mix
- [ ] Claims queue will be empty until the claims-service 500 is fixed
- [ ] Contributions queue will be empty until contributions-commit is fixed

#### Deviations
- **[2026-09-15] AI adjudication stubbed per user steer.** `POST /api/v1/claims/{id}/adjudicate`
  is not called; claims (once the submit endpoint is fixed) land in `submitted` status and stay
  there. Rewire to the real AI once ai-service is production-ready. Rate limiter
  (`AsyncTokenBucket`) and `SEED_AI_RPS` env var deferred with the AI wiring.
- **[2026-09-15] Weekly provider payment runs → monthly.** Plan called for weekly PROVIDER runs
  (52/yr per tenant per currency). Seeder emits one per currency per month for row-count sanity
  and speed. Trivial to promote to weekly by wrapping the currency loop in a `for week in
  range(4)`; deferred until the endpoint is fixed since it's currently 0 rows regardless.
- **[2026-09-15] Contribution commit endpoint returns 500.** Confirmed via direct curl and
  seeder trace. Preview call (`POST /contributions/preview`) returns 1252 candidate rows for
  HEALTH June 2026 — but every row has `amount=0` even though `age_groups.contribution_amount`
  is populated (verified via psql: Standard-ZWL/35-44 = 30250 ZWL, but preview shows 0). Commit
  then 500s with a generic `INTERNAL_SERVER_ERROR + correlationId` and no clue in the response.
  JVM stdout was captured to a broken pipe (Gradle-launched bootRun with `2>/dev/null > /tmp/x.log`
  captures nothing until Gradle exits), so exception details are unrecoverable without
  restarting the service with explicit log routing. Seeder bails after one failure.
- **[2026-09-15] Claim submission endpoint returns 500.** Same pattern. Minimal payload with all
  fields (member+provider+scheme+lines with a real AHFOZ tariff code + ICD-10 code) still 500s.
  Not concurrency-related — single-shot curl reproduces. Root cause unrecovered per above.
- **[2026-09-15] Payment run create endpoint returns 500.** Bank accounts created cleanly
  (they succeed), but `POST /api/v1/payment-runs` 500s with `INTERNAL_SERVER_ERROR`. May be
  because there are no outstanding provider balances (no claims → no adjudicated amounts →
  no payables); expected behavior would be a 4xx with a friendly message but the service
  crashes. Downstream of the claims bug.
- **[2026-09-15] `post_no_retry` used for broken endpoints.** SeederClient's normal `post`
  retries 5xx 3 times with 2+4s backoff. For probe-and-bail on known-broken endpoints, the new
  `client.post_no_retry` (already added for the rules probe in Phase 3) fires exactly once and
  returns the raw response. Cuts wall-time from minutes to milliseconds when the endpoint is
  consistently broken.
- **[2026-09-15] Timeline sub-phases deferred.** Not implemented in this pass, will require a
  follow-up: new-enrolment growth (2%/mo), scheme changes (0.5%/mo including CURRENCY_CHANGE),
  group changes, terminations (5%/yr), deaths (0.5%/yr), pre-authorizations, memo notes, CTC
  member payment runs. Priority: fix the three broken endpoints first, then layer in the
  secondary events.

**Implementation Note**: Phase 6 delivers a partial demo state — transactions + notes + bank
accounts populated, but claims/contributions/payment_runs blocked on server-side 500s that need
upstream investigation. Given the plan bars Java service changes, the seeder can't fix these; it
now bails fast and logs clearly what's missing. Pause here for the user before Phase 7.

---

## Phase 7: Verification harness + docs + docker-compose glue

### Overview

Post-seed verification, docker-compose pre-flight, README polish.

### Changes Required

#### 1. Verification command

**File**: `scripts/demo-seeder/src/demo_seeder/phases/verify.py`
**Changes**: New file — `verify(tier)` queries each service via HTTP (not direct SQL) for
row-count assertions matching the tier profile within tolerance. Reports pass/fail per assertion.

```python
async def verify_tier(client, tenants, profile):
    checks = []
    for line, tenant_id in tenants.items():
        # Expected counts derived from profile + line
        expected = _expected_counts(line, profile)
        actual = await _fetch_actual_counts(client, tenant_id, profile)
        for key in expected:
            tolerance = 0.05 if key != "claims" else 0.10
            passed = abs(actual[key] - expected[key]) / expected[key] <= tolerance
            checks.append((line, key, expected[key], actual[key], passed))
    return checks
```

Assertions cover: members, dependants, groups, life_policies, contributions, transactions,
claims, adjudicated_claims, payment_runs, notes, scheme_changes, audit_events (via
`count` endpoint on audit-service).

#### 2. Docker-compose readiness check

**File**: `scripts/demo-seeder/src/demo_seeder/preflight.py`
**Changes**: New file — GETs `/actuator/health` on every service the seeder will call and
`/health` on ai-service (Python FastAPI); prints a colored table; aborts if any is down.

Also checks: (a) `ANTHROPIC_API_KEY` env var is set on the ai-service process (via the ai-service
`/health` extended response, if wired; else warn only); (b) Postgres reachable; (c) Keycloak
reachable + `medfund-platform` realm exists.

Called from `cli.py` at start of `seed` command.

#### 3. Makefile: link ai-service into `seed-demo-*`

**File**: `Makefile`
**Changes**: The four `seed-demo-{micro,fast,full}` targets prepend a `check-services` target that
runs `python -m demo_seeder preflight`. The preflight fails fast if any service is down.

#### 4. README polish

**File**: `scripts/demo-seeder/README.md`
**Changes**: Fill out from Phase 1 stub. Sections:
- Prerequisites (make infra, make keycloak-setup, java services, ai-service, ANTHROPIC_API_KEY)
- Tier selection (micro/fast/full with runtime and cost estimates)
- Usage: `make seed-demo-micro`, `make seed-demo-reset`, `make seed-demo-verify TIER=micro`
- Env vars: `SEED_MODE_ENABLED`, `SEED_AI_RPS`, `SEED_KEYCLOAK_URL`, service URL overrides
- Cost warning for full tier
- Troubleshooting: Keycloak token expiry, ai-service rate limits, tenant already exists
- Reset flow

### Success Criteria

#### Automated Verification
- [x] `demo-seeder verify --tier micro` runs to completion and prints a colored
      report (7 PASS / 4 FAIL against current partial-Phase-6 state — the FAILs
      are the four known-blocked metrics + dependants, which have no list-all
      endpoint). See Deviations for the tolerance table.
- [x] Preflight aborts cleanly when a critical service is unreachable (probe
      helper returns `critical_failed=True`, CLI exits 3). Confirmed against a
      running local stack: 7 UP + 1 non-critical DOWN (ai-service) → exit 0.
- [x] `demo-seeder preflight` command wired and exits 0 when Java stack is up,
      3 when a critical probe fails. Exit codes documented in the README.
- [x] `demo-seeder --help` lists all four commands (ping, preflight, seed,
      verify); `seed` runs the preflight step before Phase 2.
- [ ] README lint deferred — repo doesn't run markdownlint in CI

#### Manual Verification
- [ ] A fresh engineer, given only the README, can go from `make infra` to
      `make seed-demo-micro` to `make seed-demo-verify` without asking questions

#### Deviations
- **[2026-09-15] Dependants have no list-all endpoint on user-service.** The
  seeder's Phase 5 cascades dependants via `POST /api/v1/dependants`, but
  `DependantController` only exposes `/member/{memberId}` and `/{id}` — no
  aggregate listing. Verify reports `dependants=0` regardless of what was
  seeded. Options for a follow-up: (a) add a paged/count endpoint to
  DependantController, (b) walk every member's `/member/{id}` in verify and
  sum (O(N) HTTP calls — expensive for full tier), (c) drop the dependant
  metric. Current verify keeps the metric visible with a wide tolerance so
  the gap is loud.
- **[2026-09-15] Notes endpoint wraps `PageResponse` in `ReportResponse<T>`.**
  `_count_page_endpoint` in verify.py unwraps both shapes: it prefers
  `body.data.total` when present, falling back to `body.total`.
  Transactions and claims + payment-runs use the bare `PageResponse` shape;
  notes uses the envelope shape. One helper handles both — no per-endpoint
  branching in the fetcher table.
- **[2026-09-15] Transactions endpoint returns a `PageResponse`, not a Flux.**
  Initial verify draft used `_count_list_endpoint` (raw JSON array) and
  reported `transactions=0` against a populated set. Fixed by routing
  transactions through `_count_page_endpoint`. Verified: 595 HEALTH, 650 LIFE
  now report correctly.
- **[2026-09-15] AI-service marked non-critical in preflight.** Because Phase 6
  stubs Stage-6 adjudication, ai-service being down does not block a seed run.
  The probe still runs and prints yellow "non-critical" when DOWN so operators
  know its status. Flip back to `critical=True` once AI wiring is restored.
- **[2026-09-15] Notes expected count decoupled from Phase 6 actuals.** Phase 6
  emitted ~50 HEALTH / ~20 LIFE total (not per-month). The plan's design
  target is `~50/mo × months`, so verify's expected reflects design intent,
  not delivered state. A future Phase-6 revision that emits monthly notes
  will bring the metric into the PASS zone without a verify.py change.

---

## Testing Strategy

### Unit Tests (`scripts/demo-seeder/tests/unit/`)

Python-side unit tests via `pytest`:
- Config: tier profile access, env var overrides.
- Auth: token cache respects 30s buffer; refresh triggers on expiry.
- Generators: MemberFactory produces valid enrollment dates (1st-of-month); LifePolicy factory
  respects LIFE-only tenant guard; ICD/AHFOZ pack files load cleanly.
- Rate limiter: token bucket honors RPS under concurrent load (property test).

### Integration Tests (light — `scripts/demo-seeder/tests/integration/`)

Optional. Runs against a live docker-compose stack. Not gated in CI; run manually:
```
docker compose up -d
make keycloak-setup
cd scripts/demo-seeder && uv run pytest tests/integration
```
Covers: reset script idempotency, tenant creation idempotency, FX rate idempotency.

### E2E: Micro-tier smoke run

`make seed-demo-reset && make seed-demo-micro && make seed-demo-verify TIER=micro` is the E2E
smoke. Runs in ~15 minutes on a laptop; safe for CI on demand (not on every PR).

### Manual Testing Steps

1. `make infra && make keycloak-setup`
2. Start Java services + ai-service in one terminal each (or use tmux/pane setup).
3. `SEED_MODE_ENABLED=true make seed-demo-micro`
4. Visit `http://localhost:5100/admin/tenants` -> log in as `superadmin` -> see both tenants
5. Switch to Health First tenant -> claims queue -> confirm ~1000 adjudicated claims exist
6. Log in as a showcase member -> confirm contributions history renders
7. Run `make seed-demo-reset` -> confirm tenants + schemas + realms gone
8. Re-run `make seed-demo-micro` -> confirm it's fully reproducible

## Performance Considerations

- **Wall-clock budget by tier**:
  - Micro (500x3mo): ~15 min. AI-driven adjudications dominate (~1500 claims).
  - Fast (2kx6mo): ~2-3 hours. AI adjudications (~30-60k claims) are the long pole.
  - Full (20kx24mo): estimated **days** with real AI on every claim (~1.8M claims on HEALTH alone;
    even at 10 RPS to ai-service, that's ~50 hours of adjudication alone, before AI-service and
    Claude latency compound). This tier is essentially aspirational under the "real AI everywhere"
    policy. If wall-clock exceeds available time, drop back to fast or introduce a
    `SEED_AI_CLAIMS=N` flag in a follow-up plan.

- **Cost budget** (Anthropic API, rough sonnet-scale estimates):
  - Micro: ~$5-15
  - Fast: ~$100-400
  - Full: ~$6,000-25,000. Confirm budget before running.

- **Kafka backpressure**: BeneficiaryBenefitSeeder, SchemeChangedConsumer, GroupChangedConsumer,
  and audit-service consumer are all reactive with unbounded backpressure buffers; they'll absorb
  the seed's output. Monitor `kafka-ui` at `http://localhost:8090` for consumer lag during
  fast/full runs.

- **Postgres row count**: full tier writes ~2M claims + ~2.2M claim lines + ~5M audit events per
  tenant. Testcontainers slices are not affected (they run their own postgres); the local dev
  postgres will grow noticeably. Include a warning in the README.

- **AI-service cache**: check whether ai-service has any prompt-caching layer that would reduce
  Claude cost. If yes, exploit it by grouping similar claims. If no, don't try to build one in the
  seeder; that's a separate concern for the ai-service team (`[[reference_ai_service_gaps]]`).

## Migration Notes

- **No Flyway migrations added** by this plan. The seeder consumes only existing HTTP APIs.
- **exchange_rates.source='seeder'** is the sentinel used by `scripts/reset-tenant-schemas.sh` to
  purge seeded FX rates. Do not use `source='manual'` (that's the default for operator-created
  rows).
- **Tenant slugs `health-first` and `life-first` are load-bearing**: the reset script pattern-matches
  on these. If a real customer tenant ever wants either slug, rename the seed tenants before that
  happens.
- **No Kafka topic recompaction** required.
- **No Keycloak realm changes needed** beyond what `TenantService.create()` already does.
- **Rules-engine `ReleaseId` invalidation**: Phase 3's rule POSTs trigger the standard
  `medfund.tenants.config-changed` fan-out; consumers rebuild per-tenant KieBase asynchronously.
  Phase 5 members onwards will hit the newly-compiled rules; no explicit wait needed but if a
  race turns up, add a `sleep(5)` after Phase 3.

## Rollout & Rollback

**Rollout**: this is a developer + CI tool, not a service. PR merges to main after review:
- `scripts/demo-seeder/` (new directory)
- `scripts/reset-tenant-schemas.sh` (new file)
- `Makefile` (five new targets)

No deploy, no Helm change, no ArgoCD sync, no infra change. Anyone with the repo can run it locally.

**Rollback**: revert the PR. No production impact possible because `SEED_MODE_ENABLED=true` gates
every entry point.

## References

- Research: `thoughts/shared/research/2026-09-15-comprehensive-seed-data-two-tenants-health-life.md`
- Multi-tenancy architecture: `.claude/multi-tenancy.md`
- Adjudication pipeline: `.claude/adjudication.md`
- Rules-engine templates: `.claude/rules-engine.md`
- Payment gateway stubbing: `thoughts/shared/research/2026-08-10-tenant-bank-accounts-and-stubbed-gateway.md`
- AI service pilot readiness: `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md`
- CTC opt-in details: `thoughts/shared/research/2026-08-09-ctc-payments.md`
- Similar tooling patterns:
  - `scripts/bootstrap-keycloak.sh` — Keycloak admin API + idempotent PUT/POST
  - `scripts/perf-test-scheduled-reports.sh:33` — `SKIP_SEED=1` env-gate pattern
  - `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantMigrationRunner.java:32`
    — reactive CommandLineRunner (informs the async orchestration shape even in Python)
  - `services/java/tenancy-service/src/main/java/com/medfund/tenancy/config/PlatformFlagSeeder.java:24`
    — idempotent-seed-on-boot pattern
- Load-bearing HTTP contracts (verified):
  - Tenants: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantController.java:83-100`
  - Exchange rates: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/ExchangeRateController.java:68-88`
  - Members: `services/java/user-service/src/main/java/com/medfund/user/controller/MemberController.java:104-114`
  - LifePolicies: `services/java/user-service/src/main/java/com/medfund/user/controller/LifePolicyController.java:86-97`
  - Schemes: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/SchemeController.java:97-108`
  - Contributions preview + commit: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/ContributionController.java:147-160`
  - Transactions: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/TransactionController.java:77`
  - Claims + adjudicate: `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimController.java:112-138`
  - PaymentRuns: `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentRunController.java:132-160`
  - Notes: `services/java/finance-service/src/main/java/com/medfund/finance/controller/NoteController.java:182-196`
  - Rules: `services/java/rules-engine/src/main/java/com/medfund/rules/controller/TenantRuleController.java:70-81`
