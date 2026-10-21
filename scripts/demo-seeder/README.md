# InsureFlow demo-data seeder

Provisions two tenants (`health-first` HEALTH, `life-first` LIFE) and drives
3, 6, or 24 months of realistic activity through the platform's HTTP APIs.
Runs against a local infra + native service stack; produces demo data for
UX, demo, or soak use.

## Prerequisites

1. `make infra` — postgres, redis, kafka, keycloak, minio up
2. `make keycloak-setup` — `medfund-platform` realm + `superadmin` user seeded
3. Every Java service running natively:
   `make tenancy && make user && make claims && make contributions && make finance && make rules`
4. AI service running: `make ai` (needs `ANTHROPIC_API_KEY` in its env). AI
   Stage-6 adjudication is currently stubbed in the seeder (see [Known gaps](#known-gaps));
   the service is still probed on preflight for future re-wiring.
5. `SEED_MODE_ENABLED=true` must be exported — the seeder aborts otherwise.
   The Makefile targets export it for you.

## Tiers

| Tier  | Members (H/L)     | Months | Wall-clock | Anthropic cost (rough) |
|-------|-------------------|--------|------------|------------------------|
| micro | 500 / 375         | 3      | ~15 min    | $5-$15 (once AI wired) |
| fast  | 2,000 / 1,500     | 6      | ~2-3 hours | $100-$400              |
| full  | 20,000 / 15,000   | 24     | days       | $6,000-$25,000         |

**Cost warning.** Once AI Stage-6 is re-wired, every claim will invoke
ai-service, which calls Claude. The `full` tier is aspirational under the
"real AI everywhere" policy; confirm budget before running.

## Usage

```bash
# First time: install Python deps.
make seed-demo-setup

# Probe every service; exits non-zero if anything critical is down.
make seed-demo-preflight

# Reset any prior seed data (drops schemas + realms for the two seed tenants).
make seed-demo-reset

# Seed. Runs preflight, all six phases, and a verification report.
make seed-demo-micro     # or seed-demo-fast / seed-demo-full

# Post-run row-count verification (idempotent — safe to re-run).
make seed-demo-verify TIER=micro
```

## Commands

| Command                    | What it does                                          |
|----------------------------|-------------------------------------------------------|
| `demo-seeder ping`         | Fetch a Keycloak token + `GET /api/v1/tenants`        |
| `demo-seeder preflight`    | Probe every service, exit non-zero if any is down     |
| `demo-seeder seed --tier`  | Run all six seed phases + verification                |
| `demo-seeder verify --tier`| Row-count report against the tier's expected budget   |

Exit codes: `0` success, `1` verify failed, `2` `SEED_MODE_ENABLED` not set,
`3` preflight failed (critical service unreachable).

## Environment variables

| Var                       | Default                     | Purpose                                       |
|---------------------------|-----------------------------|-----------------------------------------------|
| `SEED_MODE_ENABLED`       | (unset)                     | **Required.** Set to `true` to run.           |
| `SEED_AI_RPS`             | `10`                        | Max requests-per-second to ai-service         |
| `SEED_KEYCLOAK_URL`       | `http://localhost:9080`     | Keycloak base URL                             |
| `SEED_TENANCY_URL`        | `http://localhost:8081`     | tenancy-service base URL                      |
| `SEED_USER_URL`           | `http://localhost:8082`     | user-service base URL                         |
| `SEED_CLAIMS_URL`         | `http://localhost:8083`     | claims-service base URL                       |
| `SEED_CONTRIBUTIONS_URL`  | `http://localhost:8084`     | contributions-service base URL                |
| `SEED_FINANCE_URL`        | `http://localhost:8085`     | finance-service base URL                      |
| `SEED_RULES_URL`          | `http://localhost:8086`     | rules-engine base URL                         |
| `SEED_AI_SERVICE_URL`     | `http://localhost:8000`     | ai-service base URL (preflight only)          |

## Verification

`make seed-demo-verify TIER=<tier>` prints a colored table:

```
Line   Metric                 Expected     Actual    Tol   Result
------------------------------------------------------------------
HEALTH members                     500        500    2.0%    PASS
HEALTH groups                        3          3    0.0%    PASS
HEALTH schemes_active                6          6    0.0%    PASS
...
```

Tolerance varies by metric (deterministic metrics like `groups` are exact;
stochastic metrics like `dependants` allow ±30%). Metrics blocked by known
upstream 500s (see [Known gaps](#known-gaps)) currently report `0`; they'll
turn green when those endpoints are fixed.

## Known gaps

- **AI Stage-6 stubbed.** Claims land in `submitted` status; no ai-service
  calls. Rewire once ai-service is production-ready.
- **Three server-side 500s** on `POST /contributions/commit`, `POST /claims`,
  and `POST /payment-runs`. See the plan's Phase 6 Deviations for root-cause
  notes; a follow-up plan is required.
- **Weekly provider payment runs → monthly** (one run per currency per month
  instead of weekly, to keep row counts sane once endpoints are fixed).
- **Timeline sub-phases deferred:** new-member growth (2%/mo), scheme
  changes, group changes, terminations, deaths, pre-authorizations, CTC
  member payment runs.
- **Showcase-member Keycloak sync silently fails** (upstream bug —
  MemberService writes to realm `tenant-<uuid>` while KeycloakRealmService
  creates realms as `medfund-<slug>`).

## Troubleshooting

- **`ERROR: SEED_MODE_ENABLED=true must be set`** — export the env var.
  The Makefile targets do this for you.
- **Preflight fails on tenancy-service / user-service / …** — check
  `make <service>` is running and printed `Started …Application in Ns`.
- **Keycloak token expiry** — the seeder refreshes 30s before expiry. If
  you see `401` mid-run, restart the seeder; token cache resets.
- **ai-service rate-limited by Anthropic** — lower `SEED_AI_RPS`:
  `SEED_AI_RPS=3 make seed-demo-micro`.
- **Tenant already exists** — the seeder is idempotent by slug
  (`health-first`, `life-first`). Re-runs skip creation. For a truly clean
  run: `make seed-demo-reset` first.

## Reset

`make seed-demo-reset` drops the `tenant_<uuid>` schemas for the two seed
slugs and deletes the corresponding Keycloak realms +
`public.exchange_rates` rows tagged `source='seeder'`. Non-seed tenants are
untouched.
