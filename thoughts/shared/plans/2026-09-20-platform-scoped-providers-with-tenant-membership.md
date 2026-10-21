---
date: 2026-09-20
git_commit: 109cdc9b93c57a5b58dbb7afe09879a534a20f4c
branch: rename-adjustments-to-notes
research:
  - thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md
  - thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md
supersedes:
  - thoughts/shared/plans/2026-09-15-provider-fanout-and-age-group-effective-from.md
steer: "supersedes the Option A fan-out plan; delete the uncommitted consumer + backfill runner + publisher extension as part of Phase 1"
services_touched: [tenancy-service, user-service, claims-service, finance-service, notification-service, rules-engine, angular, scripts, docs]
status: draft
---

# Platform-scoped providers with tenant membership and line tagging

## Overview

Retire the `tenant_<uuid>.providers` shadow table across every tenant schema. Providers become truly platform-scoped: one row in `public.providers`, related to N tenants through a new `public.provider_tenants` junction table (composite PK, carrying per-tenant contract metadata), and tagged with insurance lines through a new `public.provider_insurance_lines` junction (composite PK, `CHECK` against the 8 `InsuranceLine` values). Every one of the 44 SQL sites that today reads a `providers` table across four services gets repointed to `public.providers` joined through `public.provider_tenants`, and the tenant-local `providers` table is dropped in the same PR series.

The Option A fan-out plan that this supersedes ([thoughts/shared/plans/2026-09-15-provider-fanout-and-age-group-effective-from.md](2026-09-15-provider-fanout-and-age-group-effective-from.md)) shipped a claims-service Kafka consumer and boot-time backfill runner whose job was to keep the tenant shadow populated. That code is deleted in Phase 1. Its sibling change (age-group `effectiveFrom` on `CreateAgeGroupRequest` + `SchemeService.insertCurrentPrice`) is orthogonal to the provider work, already implemented in the working tree, and is not touched by this plan.

Provider self-service login (the `/providers/*` Angular portal, Flutter provider mode, `provider_admin` role) is **out of scope**. Backend link/unlink and line-tag endpoints ship as super-admin-only for the admin console.

## Current State Analysis

### The three-way contradiction between docs and code

Docs and migrations describe three different models for provider ownership, none matching each other:

| Source | Model |
|---|---|
| `.claude/multi-tenancy.md:225,242` | Full per-tenant replication (*"profile is replicated across tenant schemas"*) |
| `.claude/portals.md:207` | Single platform provider + accounts in every tenant realm |
| `.claude/architecture.md:588` | Platform-level provider registry (under public schema tree) |
| `.claude/architecture.md:599` | `providers` also listed under tenant schema tree, 11 lines later (self-contradictory) |
| `services/java/tenancy-service/src/main/resources/db/migration/public/V105__providers.sql:1-3` | *"Platform-wide provider registry. Providers are not tenant-scoped."* |
| Actual code | Writes to `public.providers`; reads from tenant-local `providers` at 38 unqualified sites via `search_path` |

### The Option A implementation that this supersedes

Uncommitted in the working tree at HEAD (`109cdc9b`):

- `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderOnboardedConsumer.java` (new file, 5845 bytes)
- `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderBackfillRunner.java` (new file, 4530 bytes)
- `services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderOnboardedConsumerTest.java` (new file, 8829 bytes)
- `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java:168-176` (extended `publishProviderOnboarded` to 4 args carrying `status` + `networkTier`)
- `services/java/user-service/src/main/java/com/medfund/user/service/ProviderService.java:128-132` (updated call site to pass the two new fields)
- `services/java/user-service/src/test/java/com/medfund/user/service/UserEventPublisherTest.java` (updated for the 4-arg payload)

All of it deleted or reverted in Phase 1.

### FK / soft-reference blast radius (11 tenant columns)

Hard `REFERENCES providers(id)` (retargeted to `public.providers` in Phase 2):

1. `claims.provider_id` at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:129`
2. `payments.provider_id` at `V001__baseline.sql:217` (nullable)
3. `quotations.provider_id` at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V003__quotations.sql:6`

Soft (bare UUID, app-enforced; stay bare):

4. `pre_authorizations.provider_id` at `V014__claims_schema.sql:32`
5. `payment_run_items.provider_id` at `V016__finance_schema.sql:35`
6. `provider_balances.provider_id` at `V016:52`
7. `notes.provider_id` at `V016:70`
8. `payment_advices.provider_id` at `V016:119`
9. `advance_payments.provider_id` at `V016:138`
10. `provider_balance_snapshot.provider_id` at `V080__balance_snapshots.sql:21`
11. `suspicious_transaction_alert.provider_id` at `V267:25`

Live row count for every one of those 11 columns across every tenant schema on 2026-09-20: **zero**. Retarget is referentially free.

### SQL read sites (44 in total across 4 services)

| Service | Sites | Files (grouped) |
|---|---|---|
| claims-service | 14 | `ClaimsReportQueryRepository.java` (8), `ClaimQueryRepository.java` (2), `PreAuthorizationQueryRepository.java` (2), `ClaimFactBuilder.java:206` (already `public.`), `ProviderOnboardedConsumer.java:109` + `ProviderBackfillRunner.java:57,86` (deleted in Phase 1) |
| finance-service | 16 | `PaymentQueryRepository.java` (2), `NoteQueryRepository.java` (2), `CreditorQueryRepository.java` (2), `ProviderBalanceQueryRepository.java` (2), `AdvancePaymentQueryRepository.java` (2), `PaymentAdviceQueryRepository.java` (3), `PaymentRunWorkbookQueryRepository.java` (1), `BalanceHistoryService.java:82`, `PaymentAdviceService.java:244`, `PaymentRunFactBuilder.java:81` (already `public.`) |
| user-service | 13 | `ProviderRepository.java` (11 `@Query` strings, entity already `@Table(schema="public")`), `TenantStatsController.java:895,933` (dynamic `"<schema>".providers` interpolation) |
| contributions-service | 0 | (none) |

### Existing precedents to copy verbatim

- **Junction table (composite PK + `_ck` CHECK)**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V085__treaty_applicable_line.sql:9-17`.
- **Hand-rolled `DatabaseClient` repository** for composite-PK tables: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/repository/TreatyParticipantRepository.java` (INSERT ... RETURNING, static `mapRow(Row, RowMetadata)`).
- **Junction-table entity** (no `@Id`, plain `@Getter @Setter @Table("...")`): `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/TreatyParticipant.java`.
- **Cross-schema FK**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V268__report_job_schedule_columns.sql:33-41` (`ALTER TABLE ... ADD CONSTRAINT ... REFERENCES public.<t>(id)` inside `DO $$ IF NOT EXISTS $$`).
- **`public/` migration style**: `CREATE TABLE IF NOT EXISTS public.<name>` + `CREATE INDEX IF NOT EXISTS` throughout.
- **`provision_tenant_role` grant machinery**: `services/java/tenancy-service/src/main/resources/db/migration/public/V182__grant_report_config_tables_to_tenant_roles.sql:25-95` (extend `v_readable_tables` array, backfill loop over `public.tenants`).
- **Controller Swagger house style**: `services/java/user-service/src/main/java/com/medfund/user/controller/ProviderController.java:27-31` (`@RestController`, `@Tag`, `@SecurityRequirement`, `@Operation(summary, description)` per endpoint, `@ApiResponses` when non-200 outcomes are worth documenting).
- **Reactor-Kafka consumer template**: `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/SchemeChangedConsumer.java:55-71` (`.doOnSuccess` for ack; never `.doOnTerminate`).
- **Boot-time per-tenant iteration**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantMigrationRunner.java:32-76`.

## Desired End State

After the six phases land and every touched service redeploys:

- Every provider is a single row in `public.providers`, with zero tenant-schema mirror. `SELECT count(*) FROM tenant_<slug>.providers` errors with "relation does not exist" in every tenant.
- `public.provider_tenants` carries the membership graph, one row per (provider, tenant) pair the tenant is contracted with. The pilot health-first tenant has 60 rows here (matching its old shadow count); life-first has its own subset.
- `public.provider_insurance_lines` carries the line tag(s) per provider. Every existing provider has at least one tag (`HEALTH` by default; `LIFE` or `FUNERAL` where `provider_type` indicates so).
- Every one of the 44 SQL sites reads `public.providers` filtered by `public.provider_tenants` for the current tenant. Every INNER-JOIN on providers in a report has an IT assertion covering the join.
- `POST /api/v1/claims` returns 422 with an actionable message when the referenced provider has no `provider_tenants` row for the current tenant, or the claim's scheme line is missing from the provider's `provider_insurance_lines`.
- `ProviderFact.inNetwork` and `ProviderFact.networkTier` are populated from `provider_tenants` in `ClaimFactBuilder.fetchProvider`. Any Drools rule keying on them starts firing.
- The 4 architecture docs (`CLAUDE.md`, `multi-tenancy.md`, `architecture.md`, `portals.md`) read as one coherent story about platform-scoped providers with tenant membership.
- Super-admin admin console can link and unlink a provider to a tenant, and add or remove line tags, from the existing `/platform/providers` page.
- Demo-seeder Phase 2 (providers) posts membership and line tags after onboarding each provider; Phase 6 (claims) no longer 422s on missing membership.

Provider self-service portal, Flutter provider mode, and `provider_admin` role wiring remain unbuilt after this plan. `Provider.keycloakUserId` is still populated on onboard (existing behaviour, unchanged), but nothing reads it in v1.

### Verification

- `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM public.provider_tenants;"` returns a non-zero number.
- `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM public.provider_insurance_lines;"` returns >= `SELECT count(*) FROM public.providers` (every provider has at least one tag).
- `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM tenant_health_first.providers;"` errors: `relation "tenant_health_first.providers" does not exist`.
- `curl -X POST http://localhost:8083/api/v1/claims -H 'X-Tenant-ID: <health>' -H 'Authorization: Bearer $T' -d '{... providerId: <unlinked-provider> ...}'` returns 422 with body `{"error": "provider is not contracted with this tenant"}`.
- Same curl with the wrong line (LIFE claim submitted to a HEALTH-tagged provider) returns 422 with `{"error": "provider does not serve LIFE claims"}`.
- `make test-java && make test-integration` green after each phase.
- `make seed-demo-reset && make seed-demo-micro && make seed-demo-verify TIER=micro` reports `claims_submitted > 0` and no membership 422s.
- Angular `/platform/providers` at `http://localhost:5100/platform/providers`: per-row "Tenants" and "Lines" pills render, click-to-edit modal saves cleanly, `verify` skill reports zero console errors.

### Key Discoveries

- **Migration numbers**: next public = **V185**, next tenant = **V275**. Verified 2026-09-20 via `ls services/java/tenancy-service/src/main/resources/db/migration/{public,tenant} | sort -V | tail -5`.
- **Existing provider row counts on live db** (2026-09-20, `POSTGRES_URL=postgres://medfund:medfund@localhost:5433/medfund`): `public.providers=60`, `tenant_health_first.providers=60`, `tenant_life_first.providers=60`, `tenant_first_medfund.providers=65` (5 orphans that predate the fan-out consumer). Every one of the 11 `provider_id` columns across every tenant has zero populated rows.
- **The 5 orphan rows in `tenant_first_medfund.providers`** all match the demo-seeder's Faker naming pattern (`f"{faker.company()} {provider_type.title().replace('_', ' ')}"` from `scripts/demo-seeder/src/demo_seeder/phases/providers.py:63-69`) and all have `keycloak_user_id IS NULL`. Safe to drop with a guard; a guard against operator-created rows (would have `keycloak_user_id` set) is included in Phase 5.
- **`ProviderRepository` split-brain**: entity is `@Table(schema="public", value="providers")` at `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:18`, but all 11 `@Query` strings under `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderRepository.java:13-57` are unqualified. Derived methods hit `public.providers`; `@Query` methods rely on `search_path`. Fine today because the caller path (`/api/v1/providers`) is platform-less; would silently read `tenant_x.providers` under any tenant context. Phase 5 schema-qualifies them.
- **`CreditorQueryRepository` has TWO `practice_number` sites**, not one: line 120 (WHERE `q` filter LIKE) and line 124 (SELECT projection). Verified 2026-09-20. `public.providers` renamed that column to `registration_number` in `public/V106__provider_registration_number.sql:5`. Fixing this is a Phase 4 sub-task with zero existing test coverage.
- **`TenantStatsController` at :895 and :933** builds SQL by string-interpolating a schema name (`"\"" + schema + "\".providers"`). A blanket `providers → public.providers` rewrite misses these because the token `providers` is preceded by `"."`. Phase 5 rewrites both to drive from `public.providers` gated by `provider_tenants`. Currently zero test coverage on these two queries.
- **`public.providers` is already tenant-readable**: `provision_tenant_role.v_readable_tables` at `public/V182:32` includes `'providers'` at position 2. Phase 2's V185 needs to add `'provider_tenants'` and `'provider_insurance_lines'` to the same array and re-provision existing tenants (`FOR t IN SELECT schema_name FROM public.tenants LOOP PERFORM public.provision_tenant_role(t.schema_name); END LOOP`).
- **`ClaimFactBuilder.fetchProvider` at line 206** already reads `public.providers`. It selects `status, provider_type` but throws both away and only sets `providerId` on the `ProviderFact`. Phase 3 fixes this by adding the `provider_tenants` join and populating `inNetwork` + `networkTier` from it.
- **The `PROVIDER_MODE_BY_LINE` policy** at `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java:71-90` (HEALTH REQUIRED, LIFE FORBIDDEN, FUNERAL OPTIONAL, etc.) is claim-side only today. Phase 6 adds the provider-side cross-check: the claim's scheme line must appear in `public.provider_insurance_lines` for the referenced provider.
- **Auto-memory guardrails apply**: [[feedback_never_edit_applied_migrations]] (V185 is a new file, never edit V105 or V001), [[feedback_no_em_dashes]] (this plan uses colons and parentheses throughout), [[feedback_audit_actor_email]] (every new mutation endpoint uses `AuditActor.id(jwt)` + `AuditActor.email(jwt)`), [[feedback_audit_entity_name]] (audit `entityName` is the provider name, not the composite key UUID), [[infra_testcontainers_pitfalls]] (existing IT harnesses already have the BOM override, flyway-database-postgresql, ReactiveJwtDecoder stub), [[bug_public_prefix_silent_rollback]] (`public.` prefix only for genuinely-public tables, not tenant tables), [[bug_r2dbc_pre_populated_id_update_mode]] (new entities leave `@Id` null on INSERT so Postgres `DEFAULT gen_random_uuid()` fires).

## What We're NOT Doing

- **Not** building the provider self-service portal. No Angular routes under `/providers/*` (dashboard, tenant-selector, claims, pre-auth, payments, profile, staff, documents, tariffs, verify-member, insights). No Flutter provider mode. No `provider_admin` role. No `/api/v1/providers/me` or `/api/v1/providers/me/tenants` endpoints. Deferred to a follow-up plan gated on the schema this plan lands.
- **Not** promoting the 8 soft `provider_id` references (`pre_authorizations`, `payment_run_items`, `provider_balances`, `notes`, `payment_advices`, `advance_payments`, `provider_balance_snapshot`, `suspicious_transaction_alert`) to cross-schema FKs. They stay bare UUID, matching the existing house convention. A separate plan can promote them if referential drift becomes an observed problem.
- **Not** wiring the extra `provider_tenants` contract fields (`contract_effective_from`, `contract_effective_to`, `credit_limit`, `credit_limit_currency`, `tariff_agreement_id`) to any consumer in v1. The columns exist in the schema for a future `providers:manage_contracts` UI (`services/java/shared/src/main/java/com/medfund/shared/rbac/Permissions.java:162`). The admin UI in Phase 6 only exposes `status`, `network_tier`, `in_network`.
- **Not** publishing `PROVIDER_UPDATED`, `PROVIDER_SUSPENDED`, or `PROVIDER_VERIFIED` Kafka events. `ProviderService.update / suspend / activate / verifyAhfoz / updateNetworkTier` at `ProviderService.java:150-234` continue to publish audit events only, no business events. Adding those events belongs in a follow-up.
- **Not** re-writing the age-group `effectiveFrom` work from the superseded Option A plan. That change (`SchemeService.insertCurrentPrice`, `CreateAgeGroupRequest`, Angular `UpsertAgeGroupPayload`, seeder `schemes.py`) is orthogonal, already applied in the working tree, and stays committed on its own.
- **Not** aligning the remaining `public.providers` column drift (`ahfoz_number`, `practice_number` still exist alongside `registration_number` on `public.providers`). Column renames are their own risk; this plan takes only the `practice_number → registration_number` fix that `CreditorQueryRepository` forces.
- **Not** amending `.claude/adjudication.md` beyond the two-line note that `ProviderFact.inNetwork` and `networkTier` now populate. That doc gets a full pass in a rules-engine plan when a Drools rule actually keys on those fields.

## Implementation Approach

Six phases. Docs first so the schema PRs land against a coherent north-star; schema before code so the two-hop join is queryable when Phase 3 starts reading through it; drop-table strictly last so no read site depends on a dead table. Each phase is independently reviewable; each has at least one automated verification and one manual step.

Kafka contracts: the `medfund.users.provider-onboarded` event stays alive with its original two-field payload (`event`, `providerId`, `name`); the 4-field extension from Option A is reverted. Two new events, `PROVIDER_TENANT_LINKED` and `PROVIDER_TENANT_UNLINKED`, ship in Phase 6 but have no consumer today; they exist for future cache-invalidation subscribers.

Backwards compatibility during rollout: Phase 2 leaves `tenant.providers` in place, so any service still on the old code keeps reading its shadow. Phases 3, 4, 5 each repoint one service; a service running the old code between phases sees stale reads (rows it can no longer see because they were never in `provider_tenants`) but does not error. Phase 5's DROP TABLE is the strict cutover point: after it, no service on the pre-repoint code compiles against the schema.

---

## Deviations

### 2026-09-20: `./gradlew :<svc>:build` replaced with `:<svc>:test` as the per-phase compile gate

Every phase's Automated Verification lists `./gradlew :<service>:build`. That task runs
`jacocoTestCoverageVerification`, which fails on this repo independently of any change in
this plan: `:claims-service:build` reports `lines covered ratio is 0.56, but expected
minimum is 0.70` with a working tree that has zero claims-service modifications. The gate
is unreachable from unit tests alone because the `*IT` suite (which `make test-integration`
runs as a separate pass) contributes the rest of the coverage, and the repo's own
`make test-java` target runs `./gradlew test`, not `build`, precisely to sidestep this.

Substituted everywhere in this plan: `./gradlew :<service>:test` for compile + unit-test
verification, with `make test-integration` covering the IT pass. Compilation is still
fully verified; only the coverage ratchet is dropped, and it was never green to begin with.

### 2026-09-20: Phase 1 was already applied in the working tree

The three Option A files were already deleted, `UserEventPublisher.publishProviderOnboarded`
was already back to 2-arg, `ProviderService.onboard` already called the 2-arg form, and all
four `.claude/*.md` doc rewrites (items 5 through 8) were already present as uncommitted
changes at the time implementation started. Phase 1 was therefore verified rather than
re-applied; only the `.claude/adjudication.md` note (listed under References, absent from
Phase 1's Changes Required list) was newly written.

### 2026-09-20: Pre-existing unrelated red test carried into Phase 1

`PersistencyCohortReportServiceTest.generate_neverRefreshed_addsWarning` fails on this
branch. It is a pure Mockito unit test over `PolicyLifecycleReportQueryRepository` +
`ReportEnvelopeBuilder`, both untouched by this plan, and both the test file and its service
are byte-identical to HEAD. The test asserts a `"not yet been refreshed"` warning, while
`PersistencyCohortReportService.freshnessWarning` carries a javadoc stating the opposite is
deliberate: *"No matview row yet ... is deliberately treated as no-warning rather than a
stale warning"*. Resolving it means choosing between the test and a documented design
decision that belongs to another workstream, so it is left red and flagged rather than
fixed here. Not a blocker for any phase of this plan.

### 2026-09-20: V185 backfill hardened against live data the plan's SQL would have rejected

Three changes to the V185 SQL as written:

1. **Status vocab.** The plan's backfill copies `COALESCE(tp.status, 'active')` from the
   tenant shadow. Every live shadow row carries `status = 'pending_verification'` (the
   `public.providers` default, fanned out by the retired Option A consumer), which is not
   in `provider_tenants_status_ck`. The literal SQL fails the migration on first run. Both
   `status` and `network_tier` now go through a `CASE ... WHEN IN (<vocab>) THEN ... ELSE
   <default> END`, so anything outside the CHECK vocab lands as `active` / `STANDARD`.
   `active` (not `pending`) is deliberate: a `pending` membership would make Phase 6's
   `isMember` reject every claim these 180 rows exist to permit.
2. **Missing-table guard.** The loop `EXECUTE format(... FROM %I.providers ...)` errors on
   any tenant schema that has no `providers` table (a schema provisioned after tenant V276,
   or mid-provisioning). Added `CONTINUE WHEN NOT EXISTS (SELECT 1 FROM
   information_schema.tables WHERE table_schema = t.schema_name AND table_name =
   'providers')`.
3. **Catch-all line tag.** The plan's three `INSERT ... SELECT` statements leave the UI's
   `AUTOMOTIVE`, `LEGAL`, `FUNERAL` and `OTHER` provider types untagged, contradicting the
   migration's own stated invariant ("No provider ends up untagged"). `FUNERAL` joins the
   FUNERAL insert and a fourth statement tags anything still untagged as `HEALTH`.

`provision_tenant_role` also keeps V182's `SECURITY DEFINER`, which the plan's copy of the
body dropped. Live result: `provider_tenants = 180`, `provider_insurance_lines = 60`
(50 HEALTH + 7 LIFE + 3 FUNERAL), zero untagged providers.

### 2026-09-20: V275 drops the old FKs by catalogue lookup, not by hard-coded name

The plan drops `claims_provider_id_fkey` / `payments_provider_id_fkey` /
`quotations_provider_id_fkey` by name, and notes in the following paragraph that "actual
constraint names in a specific tenant may differ", deferring the reconciliation to a psql
step at verification time. A name-based DROP that misses leaves the old FK pointing at
`tenant.providers` and the table becomes undroppable in Phase 5, silently. The DROP side is
now a `FOR r IN SELECT ... FROM pg_constraint ...` loop over every FK on those three tables
whose referenced relation is the tenant-local `providers`, so drift cannot slip through. The
ADD side keeps the plan's three named constraints and its `IF NOT EXISTS` guards.

### 2026-09-20: The two Phase 2 repository ITs share a new dedicated container base

`ProviderTenantRepository` and `ProviderInsuranceLineRepository` schema-qualify `public.` on
every statement, so their fixture has to own `public.tenants` and `public.providers`. The
shared `AbstractPostgresIntegrationTest` container in user-service is already claimed by
`GroupNumberServiceIT`, whose fixture creates a differently-shaped `tenants` with a bare
`CREATE TABLE` — whichever ran second failed. Added
`user-service/src/test/java/com/medfund/user/repository/AbstractProviderMembershipIT.java`:
a dedicated container plus the shared `@SpringBootTest` / Flyway location / `ReactiveJwtDecoder`
stub, extended by both ITs. Same reasoning as `AbstractDedicatedPostgresIntegrationTest`'s
"one class per dedicated base" note, applied to a pair that shares one Flyway location.

### 2026-09-20: `ProviderTenantRepository.findByTenantId` not implemented

The plan's repository listing covers `findByProviderId`, `findByProviderIdAndTenantId`,
`insert` and `delete` under "only the methods v1 actually needs". A `findByTenantId` was
written during implementation and then removed: nothing in Phases 3 through 6 reads
memberships tenant-first (the admin modal is per-provider), so it would have shipped as
dead production code covered only by its own test.

### 2026-09-20: tenancy-service test suite is broadly red at HEAD

`:tenancy-service:test` reports 78 failures at pristine HEAD, across `PlatformAnalyticsIT`,
`PlatformSettingsIT`, `FlagBroadcastIT`, `KeycloakRealmSyncIT`, `TenantMigrationFlywayIT`
and the seven `Tenant*ConfigIT` / `Tenant*BasisIT` classes. Two distinct pre-existing causes,
neither touched by this plan:

- Context-load failures: `relation "platform_feature_flags" does not exist` — the
  `db/test-migration` fixtures were never extended for public V184 (`109cdc9b`).
- `TenantMigrationFlywayIT.v102_backfillsLegacyPolicies_…` and `v111_to_v113_backfill_…`
  call `Flyway.target("101")` / `target("110")` against `classpath:db/migration/tenant`,
  which has no V101 or V110 (tenant numbering runs V001 to V274 with a gap there).

Verified by running the suite with V185, V275 and the `TenantMigrationFlywayIT` edit all
stashed: same 78 failures. The new `v185AndV275_…` test passes; the count and the failing
set are unchanged by this phase. Left red and flagged rather than fixed here, same as the
`PersistencyCohortReportServiceTest` note above.

### 2026-09-20: Phase 3's `aggregateProvider` IT drives the repository, not an endpoint

The plan's `ClaimsReportProviderJoinIT` sketch calls "the aggregate endpoint" for all
three INNER-JOIN sites. Two of them have one: `perProviderSummary` is
`/api/v1/reports/claims/providers`, and `aggregateMonthlyProvider` is
`/api/v1/reports/aggregate/claims/monthly?dimension=PROVIDER`. The third does not.
`ClaimsAggregateController.aggregateClaims` hardcodes `claimsReportService.aggregate("SCHEME",
...)` and exposes no `dimension` parameter, so `ClaimsReportQueryRepository.aggregate`'s
PROVIDER branch is unreachable over HTTP. That test autowires the repository and calls
`aggregate("PROVIDER", ...)` with `.contextWrite(TenantTestContext.put())` instead. The other
two stay at the HTTP layer as written.

### 2026-09-20: Provider junction mirrors ship as test-migration V007, and the IT schema is literally `public`

The plan adds the two junction tables to `V001__claims_report_it.sql` and explains that the
mirror creates "a same-named local table" that `search_path` resolves in place of
`public.provider_tenants`. No resolution is involved: V001's own header states the IT schema
IS `public` (it seeds a `tenants` row whose `schema_name` is `public`), so the production
SQL's `public.provider_tenants` hits the mirror directly. The DDL went into a new
`V007__provider_tenant_membership.sql` rather than into V001, matching how V002 through V006
each added their own file, and carries the explicit `GRANT ... TO public_role` that V006's
header flags as necessary for tables created after V001.

### 2026-09-20: finance-service carries a pre-existing IT red baseline too

The deviation above records tenancy-service as broadly red at HEAD. `make test-integration`
shows the same for finance-service: 29 failures across `CommissionCalcIT`,
`CommissionClawbackIT`, `CommissionAggregateIT`, `ExecutiveKpiControllerIT`,
`ActuarialJobFullPathIT`, `ReportJobScheduleDedupIT` and `ReportJobRetentionJobIT`, all
surfacing as the opaque `R2DBC commit; The database returned ROLLBACK` shape from
[[bug_public_prefix_silent_rollback]]. Confirmed pre-existing by stashing every Phase 3
edit and re-running `CommissionCalcIT`: same failure. Nothing in this plan touches
finance-service until Phase 4, so it is flagged rather than fixed here.

### 2026-09-20: Phase 4's membership guard masks the provider name, it does not drop the row

The plan's `ProviderBalanceReportProviderJoinIT` sketch says to "assert
membership-exclusion (a provider in `public.providers` without a `provider_tenants` row
does not appear in this tenant's balance list)". Every one of the 16 finance sites is a
`LEFT JOIN`, and Phase 4's own overview says "same rewrite pattern as Phase 3", which kept
each join's existing INNER/LEFT shape. On a LEFT JOIN the guard withholds the *name*, not
the row: the driving row (a balance, a note, an advance, an advice) is tenant-local and
stays on the ledger with a blank payee. Dropping it would hide a real financial obligation
from the tenant that owns it, which is a worse failure than a blank name. All five new ITs
therefore assert name-masking plus the matching consequence that a `q` filter on the
provider name cannot reach the unlinked provider.

### 2026-09-20: Phase 4's new ITs drive the repositories, not `/api/v1/reports/creditors`

That route does not exist: the creditors surface is `GET /api/v1/creditors/page`
(`CreditorController:52,66`). The same is true of the other four repos, whose list
endpoints add envelope and permission machinery already covered elsewhere. All five
`*ProviderJoinIT` classes autowire their query repository and call it under
`.contextWrite(TenantTestContext.put())`, the same shape Phase 3 used for
`ClaimsReportQueryRepository.aggregate("PROVIDER", ...)`. The Phase 4 curl criterion is
likewise run against `/api/v1/creditors/page?subjectType=PROVIDER&q=REG`.

### 2026-09-20: finance test mirrors ship as test-migration V024, carrying four driving tables

The plan puts the `provider_tenants` mirror in `V005__balance_history_read.sql`. It went
into a new `V024__provider_tenant_membership.sql` instead, matching Phase 3's V007 and the
way V002 through V023 each added their own file. V024 carries more than the plan lists,
because the five new ITs have nothing to read from otherwise: the finance test-migration
set had no `provider_balances`, `notes`, `advance_payments` or `payment_advices` at all,
and V005's `providers` mirror is `(id, name, created_at)` with neither
`registration_number` nor `email`, the two columns the creditors branch projects. Shapes
are trimmed to the columns the repositories select, per the existing mirror convention.

### 2026-09-20: a second pre-existing red in finance-service, this one a unit test

The deviation above records finance-service's 29-failure *IT* baseline. Phase 4's
verification turned up one more red outside it: `AmlSummaryReportShaperTest.
shape_usTenant_yieldsUsd` fails with `IllegalStateException: No regulator currency for
AML_STR country US`, thrown from `RegulatoryReportCurrency.resolveOrThrow` because the
country-native map has no `US` entry. The test, the shaper and the shared currency
resolver are all byte-identical to HEAD, and nothing in the provider work reaches that
call chain. Left red and flagged, same as the other pre-existing reds. Total for
`:finance-service:test` is therefore 30 failures, all pre-existing: 29 IT + this one.

### 2026-09-20: `PaymentAdviceServiceTest` needed a UUID tenant

The five `contextWrite(ctx -> ctx.put("TENANT_ID", "test-tenant"))` calls in that Mockito
test were fine while nothing parsed the value. `loadPayeeName` now binds
`TenantContext.requireUuid(ctx)`, which deliberately throws on a non-UUID rather than
quietly binding null (see the javadoc added in Phase 3), so the test now supplies a UUID
the way a real request does. No production behaviour changed. `PaymentRunFactBuilderTest`
needed no change: `PaymentRunFactBuilder` already read `public.providers` and Phase 4 left
it alone, so its literal-SQL assertion still holds.

### 2026-09-20: the read-site inventory missed a Go consumer, fixed in Phase 5

Current State Analysis counts "44 SQL read sites across 4 services" and lists only Java
modules (contributions-service explicitly scored 0). The survey never covered Go.
`services/go/notification-service/internal/recipient/resolver.go:159` reads
`SELECT email, name FROM %s.providers WHERE id = $1` off the tenant schema, and it is the
payment-advice pipeline's PROVIDER-payee recipient lookup. Phase 5's V276 drops that table,
so shipping the phase as written would have left every provider payment-advice email
failing at runtime with `relation "tenant_x.providers" does not exist`, in a service no
phase of this plan redeploys.

Rewritten in Phase 5 to the same shape the Java sites use: drive from `public.providers`,
INNER JOIN `public.provider_tenants` on the advice's tenant. The `lookupSchema` call is
dropped from that function (the query no longer names a tenant schema); the membership join
is what keeps the read tenant-scoped. `dispatcher_test.go:43`'s fake-DB stub moves from
`FROM tenant_first_medfund.providers` to `FROM public.providers`. `go test ./...` green
across notification-service.

The inventory is a *written* claim this contradicts, hence a deviation rather than an
implementation detail. Nothing else outside Java reads a provider table: Elixir, Python and
the remaining Go services were swept and are clean.

### 2026-09-20: `TenantStatsController` binds `:tenantId` rather than interpolating it

Phase 5 step 2 says both rewrites "need the tenant UUID interpolated into the SQL alongside
the schema name", justified as "this controller path already interpolates the schema name
... so this pattern is not new". The controller in fact already *binds* `:tenantId` in six
places (`:38,74,454,480,500,638`), so a bind is the established pattern here and
interpolation would be the new one. Both rewrites bind. Same end result, one less
string-built predicate, and it matches `ProviderJoins`' `:tenantId` contract in
finance-service.

### 2026-09-20: Phase 5 adds coverage the plan left implicit

Two gaps the plan names but does not schedule work for:

1. Key Discoveries flags "zero test coverage" on the two `TenantStatsController` provider
   queries. Two SQL-pinning tests were added to the existing
   `TenantStatsControllerSqlTest` (the house pattern for this controller): each asserts the
   `public.providers` + `public.provider_tenants` + `:tenantId` triple is present, and that
   no captured SQL still contains `".providers` (the schema-interpolated shadow read).
2. Phase 5 step 4 says to "add a new assertion that V275 successfully retargeted
   `claims.provider_id`". That assertion already existed from Phase 2's
   `v185AndV275_…` test, so the new coverage went to V276 instead:
   `v276_dropsShadowProviders_andLeavesRetargetedFksIntact` (the `DROP TABLE ... CASCADE`
   must not take the retargeted FKs with it) and
   `v276_orphanGuard_refusesToDropWhenKeycloakBackedProviderIsUnmirrored` (stages at V275,
   seeds the exact row the guard exists for, asserts the migration raises and the table
   survives intact). A shared `assertTableAbsent` helper replaces the six removed
   `tenant.providers` assertions.

### 2026-09-20: V276 as written would have dropped `public.providers`

Caught during the pre-flight for the destructive step, before anything ran against live
data. The plan's V276 opens with a table-exists check on `current_schema()` and then
`DROP TABLE providers CASCADE`, unqualified. That is correct only if the migration never
executes with `current_schema() = 'public'`, which is exactly what local dev does:
tenancy-service's `application.yml:26-33` points one Flyway instance at **both**
`db/migration/public` and `db/migration/tenant` with `schemas: public`. Confirmed on the
live db, where `public.flyway_schema_history` carries a row for `V275__provider_fk_retarget.sql`
and the `public` schema holds tenant-shaped `claims` / `members` / `payments` / `quotations`
tables alongside the registry.

Under that layout the unqualified `providers` resolves to `public.providers`: the 60-row
platform registry that `provider_tenants` and `provider_insurance_lines` both reference, and
that this entire plan exists to migrate *towards*. `CASCADE` would have taken their FK
constraints with it. The orphan guard offers no protection, because its
`NOT EXISTS (SELECT 1 FROM public.providers pp WHERE pp.id = providers.id)` would be
comparing the registry to itself and always find zero orphans.

Fixed with an explicit `IF current_schema() = 'public' THEN RAISE NOTICE ... RETURN`, the
same skip shape tenant V259 already uses. This preserves the plan's intent exactly (drop the
tenant shadow, never the registry) rather than changing the design, so it is recorded here
rather than raised as a design mismatch. `v276_isNoOpInPublicSchema_soThePlatformRegistrySurvives`
pins it: it reproduces the dev layout (both locations, one shared history, `schemas: public`)
on its own database, because the shared IT container's `public` schema has already had
`db/migration/public` applied by `@BeforeAll` and replaying the dev shape on top of it dies at
tenant V001 with "providers already exists". The test was confirmed to fail with the guard
removed, so it genuinely catches the defect rather than passing either way.

### 2026-09-20: V276 applied out-of-band because `bootRun` is unavailable in this session

Tenant migrations normally reach live tenant schemas when tenancy-service boots
(`TenantMigrationRunner`). `make tenancy` could not be run here, so V276 was applied with a
throwaway `Flyway.configure().locations("classpath:db/migration/tenant").schemas(<schema>)`
runner over the tenancy-service test runtime classpath, once per live tenant. That is the
same API and the same per-schema history `TenantMigrationRunner` uses, so each schema
recorded its own V276 row with a Flyway-computed checksum and nothing was hand-inserted:
a later real boot validates clean. Result: `applied=1` for each of
`tenant_health_first`, `tenant_life_first`, `tenant_first_medfund`.

### 2026-09-20: a pre-existing red in `:shared:test`, surfaced for the first time

`make test-java` runs every module, including `:shared:test`, which earlier phases never
invoked (they ran `:<service>:test` per module). It turns up one failure:
`CrossServiceCallHelperTest.guarded_returnsFallbackAndWarnsWhenCallFails` asserts the
fallback message contains the cause text `"peer down"`, while the helper returns the bare
`"receipts-aggregate unavailable"`. Both `CrossServiceCallHelper` and its test are
byte-identical to HEAD, neither references `TenantContext` (the only `shared` file this
plan touched, in Phase 3), and nothing in the provider work reaches a cross-service HTTP
fallback. Left red and flagged, same as the other pre-existing reds.

### 2026-09-20: Phase 6 hydrates the provider list server-side instead of fetching pills on modal open

Phase 6 step 6 says to add `tenants?: string[]` and `insuranceLines?: string[]` to the
Angular `Provider` interface, "populated by a follow-up `include=` query param or fetched
on modal open; for v1 fetch on modal open to avoid re-shaping the list endpoint". Taken
literally, every row's two pill columns render empty until an operator opens that row's
modal, which contradicts the Desired End State ("per-row Tenants and Lines pills render")
and the phase's own verification criterion ("both pills reflect the change").

The alternative the plan names (a request per row on page load) is 40 calls for a page of
20. Instead `GET /api/v1/providers` now carries both lists per row, batched:
`ProviderService.searchPage` collects the page, then issues exactly two extra queries
(`ProviderTenantRepository.findTenantIdsByProviderIds`,
`ProviderInsuranceLineRepository.findByProviderIds`) keyed on the page's provider ids and
folds the results onto each `ProviderResponse`. `ProviderResponse.from(Provider)` keeps its
old arity and leaves both null, so the single-provider reads are unchanged.
`ProviderMembershipServiceIT.searchPage_carriesEachRowsMembershipsAndLineTags` pins it,
including that an untagged provider renders an empty list rather than null.

The modal still re-reads both junctions on open: it is where the operator acts on them, so
a stale page there is worse than one extra pair of requests.

### 2026-09-20: Phase 6's line-CRUD normalises through `InsuranceLine.from`, not `isKnown`

The plan's `addLine` rejects on `!InsuranceLine.isKnown(line)` and stores the caller's
upper-cased string. `InsuranceLine.from` accepts the Angular UI alias `MOTOR` as a synonym
for `VEHICLE` (its javadoc says so, and `isKnown` delegates to it), so `isKnown("MOTOR")`
is true while `'MOTOR'` is not in `provider_insurance_lines_ck`: the plan's version accepts
the alias and then dies on the CHECK constraint as a 500. Both `addLine` and `removeLine`
now resolve through `InsuranceLine.from` and store `resolved.code()`, so the alias round
trips as `VEHICLE` and an unknown code is a 400 before the database sees it.

### 2026-09-20: Phase 6's link/unlink resolves the tenant, and 409 comes from `DuplicateKeyException`

Two gaps in the plan's `ProviderMembershipService` sketch, both visible only against the
declared Swagger contract:

1. It documents `404 Provider or tenant not found` but only ever looks up the provider. An
   unknown tenant id would surface as the `public.tenants` FK violation, which the
   `GlobalExceptionHandler` catch-all renders as a 500. Added `PlatformTenantRepository`
   (one `SELECT name FROM public.tenants`), which both produces the 404 and supplies the
   tenant's display name, so `AuditEvent.entityName` reads `"Sunrise Clinic @ Health First
   Medical"` rather than embedding a UUID (per `feedback_audit_entity_name`).
2. It documents `409 Membership already exists` with nothing mapping the composite-PK
   violation to one. The duplicate INSERT is now mapped with
   `.onErrorMap(DuplicateKeyException.class, ...)` to `IllegalStateException`, which the
   existing handler already renders as 409; `DuplicateKeyException` was also added to that
   handler's list so a duplicate raised anywhere else in this service is a conflict rather
   than a 500. `TenantNotFoundException` joins the 404 list.

### 2026-09-20: Phase 6's claim validation defers the claim-number generator

`validateProviderMembership(...).then(generateClaimNumber())` evaluates
`generateClaimNumber()` at assembly time, before the validation has run. In production that
only assembles a Mono (the DB query still waits for a subscribe), but it broke the two new
`ClaimServiceTest` rejection cases outright: the mocked `ClaimRepository.existsByClaimNumber`
returns null at assembly and NPEs before the 422 can be raised. Changed to
`.then(Mono.defer(this::generateClaimNumber))`.

`ClaimServiceTest`'s 16 `ctx.put("TENANT_ID", "test-tenant")` calls also became real UUIDs,
for the same reason `PaymentAdviceServiceTest` did in Phase 4: `validateProviderMembership`
binds `TenantContext.requireUuid(ctx)`, which throws on a non-UUID by design.

### 2026-09-20: Phase 6's ITs drive the service and the reader, not the two endpoints

The Testing Strategy names `ProviderMembershipControllerIT` ("full round-trip via
WebTestClient: onboard, link, submit claim (201), unlink, submit claim (422)") and
`ClaimSubmitMembershipValidationIT`. Both were written one layer down, for different
reasons per service:

- **claims-service.** A round trip through `POST /api/v1/claims` needs the submit-path
  shape of the `claims` table, and the `db/test-migration` mirror carries only the report
  columns: no `payee_type`, `dependant_id`, `benefit_id`, `claim_type`, `batch_number`,
  `attachments_json`, `verified_at`, `notes`, `diagnosis_codes`, `procedure_codes`,
  `created_by`/`updated_by`. Widening the mirror to make the test run would mostly be
  testing the fixture. `ProviderMembershipReaderIT` (8 tests) instead drives the two SQL
  reads directly against the V007 mirror, covering what only a database can answer: the
  active-status filter, the per-tenant scoping, case normalisation on the line, and the
  absent-row cases. The 422 messages and their ordering against the per-line MODE rule are
  covered by three new `ClaimServiceTest` cases.
- **user-service.** `ProviderMembershipServiceIT` (16 tests) rides the Phase 2
  `AbstractProviderMembershipIT` container, so the composite PKs and the V185 CHECK
  constraints are the real ones. HTTP would add only path binding plus the
  `GlobalExceptionHandler` mapping; what is worth pinning is that each exception type the
  handler keys on is the one actually raised (404 / 409 / 400), which the service-level test
  asserts directly. `AuditPublisher` and `UserEventPublisher` moved onto the shared base as
  `@MockBean`s so all three subclasses keep one identical context definition, and therefore
  one container and one boot.

### 2026-09-20: Phase 6's seeder reconciles every provider, not only the ones it just created

The plan calls `_link_and_tag` "from `_one(index)` immediately after the successful
`POST /api/v1/providers`". That only links rows this invocation created, so a re-run against
an already-populated registry (the common case: `seed_providers` tops up to a target and
returns early when the count is already met) leaves every earlier provider unlinked, and
every claim against one of them 422s. That directly contradicts the phase's own criterion,
"`seed-demo-verify` reports `claims_submitted > 0` and no membership 422s".

`_reconcile_memberships` instead sweeps the full provider list (paged) after the top-up, and
runs on the early-return path too. Both endpoints are idempotent (409 means "already
there"), so repeating the sweep is free. `_line_for_type` mirrors V185's
`provider_type -> line` mapping rather than the plan's three-entry dict, which omitted
`FINANCIAL` and `FUNERAL`.

### 2026-09-20: Phase 6's admin UI uses toggle chips, and a new `textList` column type

Two small departures from step 6's sketch:

- The plan specifies the shared `SelectComponent` multi-select for both sections. A
  multi-select implies a pending selection committed on save, but every toggle here is its
  own POST or DELETE against a junction: there is no batch to commit. The modal uses toggle
  chips instead, where the chip IS the state, and it flips only after the server confirms
  (a rejected link leaves the chip off, covered by both the unit spec and the E2E spec).
  `feedback_no_raw_id_inputs` is still honoured: the payload carries the tenant UUID, the
  chip and the row pill both show the tenant's name.
- The row's Lines column reuses the existing `lineList` data-table column type. Tenants had
  no equivalent, so `textList` was added alongside it: a neutral pill list with an optional
  `labelFor` mapper, which is what resolves each tenant UUID to its name. Kept generic
  rather than provider-specific, matching how `lineList` and `holderType` are shared.

### 2026-09-20: the two claim rejections ship as 422 through a dedicated exception

Phase 6 step 4 says "`IllegalArgumentException` is already mapped to 422 by
`GlobalExceptionHandler`". In claims-service it is mapped to **400**
(`GlobalExceptionHandler:56-58`); only `InvalidClaimStateException` gets 422 there. The
implementation followed the plan's sketch literally, so both rejections came back as 400,
contradicting the Desired End State, its Verification block and Phase 6's own criteria,
which name 422 in five places between them.

Added `claims-service/.../exception/ProviderNotEligibleException` (plain `RuntimeException`)
plus a handler mapping it to `422 / provider-not-eligible`, and `validateProviderMembership`
now raises it instead of `IllegalArgumentException`. The two `ClaimServiceTest` rejection
cases assert the new type. Verified live against `tenant_health_first`: both rejections
return 422 with the actionable message, and the happy path (linked + tagged) still returns
201.

The older submit-path validations (the per-line MODE rule, the tariff-line requirement)
still return 400 for the same class of business-rule refusal. Aligning those is a
behaviour change to endpoints this plan does not otherwise touch, so it is left alone.

### 2026-09-20: `make test-angular` and `make test-e2e` both carry pre-existing red baselines

Phase 6 is the first phase whose criteria reach the Angular targets, and neither is green on
this branch independently of the provider work:

- **`make test-angular`**: 799 pass, 3 fail. `KpiTileComponent` × 2 (spec expects the export
  button to read `Export XLSX`, the template renders `Excel`) and `insurance-lines parsers >
  providerModeForLine` (spec expects `OPTIONAL` for HEALTH/GROUP/TRAVEL/VEHICLE/PROPERTY,
  `PROVIDER_MODE_BY_LINE` declares `REQUIRED`). All four files are unmodified on this branch.
  The target also fails a repo-wide karma coverage gate (41% statements against a 70%
  threshold) that no change in this plan could move, the same shape as the jacoco ratchet
  recorded at the top of this section.
- **`make test-e2e`**: 74 pass, 46 fail. The new `providers-membership.spec.ts` is 4/4 green.
  Because this plan edits the shared `data-table` component, the baseline was confirmed
  rather than assumed: with the three `data-table.component.*` edits stashed, a re-run of
  `finance-notes`, `kpi-dashboard` and `reinsurance-crud` reproduced exactly the same 6
  failures. The failures are unrelated surfaces (notes approve/reverse, KPI export, treaty
  lifecycle, IFRS 17, reinsurance, scheduled reports).

### 2026-09-20: V186 makes `provider_tenants.tenant_id` cascade; V185's RESTRICT made tenants undeletable

V185 as written (and as applied) gives `provider_tenants` two `ON DELETE RESTRICT` foreign
keys. On the provider side that is right. On the tenant side it means a tenant cannot be
deleted once a single provider is linked to it, and the backfill links every provider to
every tenant, so it took effect immediately. Caught while preparing Phase 6's seeder
criterion: `make seed-demo-reset` runs `DELETE FROM public.tenants WHERE slug=...` under
`set -euo pipefail` and aborts with
`violates foreign key constraint "provider_tenants_tenant_id_fkey"`. Reproduced first
inside a rolled-back transaction, so nothing was destroyed to find it.

This is not a seeder-only problem: it blocks tenant offboarding generally. The catalogue
settles the convention. Of the 28 foreign keys referencing `public.tenants`, 26 cascade,
`staff_users` sets null, and `provider_tenants` was the only RESTRICT. A membership has no
meaning without its tenant, so it now cascades too.

Fixed forward in `public/V186__provider_tenants_cascade_on_tenant_delete.sql` rather than by
editing V185, which is already applied ([[feedback_never_edit_applied_migrations]]). The
migration is a guarded `DROP CONSTRAINT` / `ADD CONSTRAINT` that no-ops when the FK is
already CASCADE or absent. The provider side stays RESTRICT.
`TenantMigrationFlywayIT.v186_tenantDeleteCascadesMemberships_whileProviderDeleteStaysRestricted`
pins both delete actions and drives the end-to-end case (tenant with a linked provider
deletes; the membership goes, the registry row stays). Applied live through a normal
tenancy-service boot, and `make seed-demo-reset` then completed, taking
`public.provider_tenants` from 180 to 60 (the one surviving tenant) exactly as the cascade
intends.

### 2026-09-20: the micro seed is blocked in phase 1 by an exchange-rates grant, before it reaches providers

With V186 in place `make seed-demo-reset` completes and `make seed-demo-micro` gets as far as
creating both demo tenants, then fails hard on the FX phase: every
`POST /api/v1/exchange-rates` returns 500, with Postgres logging
`permission denied for table exchange_rates`. The seeder raises on the third retry, so the
run never reaches phase 2 (providers) and `_reconcile_memberships` is never exercised.

The cause is the grant model, not this plan. `/api/v1/exchange-rates` is tenant-scoped: it
rejects a request with no `X-Tenant-ID` (400 `missing-tenant`), and with one the connection
runs as `tenant_<slug>_role`. `provision_tenant_role` lists `exchange_rates` under
`v_readable_tables`, so that role holds SELECT and nothing else. Confirmed in the catalogue:
all three tenant roles have SELECT only, including `tenant_first_medfund_role`, which
predates this branch. V185's copy of the function is a faithful superset of V182's for both
arrays, so nothing here revoked a privilege that used to exist; the seeder's own comment
("the stored row is platform-wide") suggests the write was never meant to run under a
tenant role at all.

Two ways out, both owned outside this plan: add `exchange_rates` to `v_writable_tables` in a
new provision migration (which gives every tenant role INSERT on a platform table, a Critical
Rule 2 decision), or take the write off the tenant-role connection. Left for that owner.

### 2026-09-20: `POST /api/v1/providers` 500s on this dev database, for a reason outside this plan

The Phase 6 curl script was meant to onboard a throwaway provider. It returns 500:
`column "banking_details" is of type jsonb but expression is of type character varying`.
Live `public.providers.banking_details` is `jsonb`, while `Provider.bankingDetails` is a
`String`. The column is jsonb because tenant `V001__baseline.sql` declares it `JSONB` and,
under the dev layout where one Flyway instance applies both `db/migration/public` and
`db/migration/tenant` with `schemas: public`, that DDL shaped the public table; public V105
(which declares it `TEXT`) was already recorded as applied and never re-ran.

Neither `Provider.java` nor `ProviderService.onboard`'s `r2dbcTemplate.insert(provider)` is
touched by this branch, and no migration in this plan mentions `banking_details`, so HEAD
fails identically against this database. Flagged rather than fixed: the fix is either a
`Json`-typed field or a public migration aligning the column, both of which belong to
whoever owns provider onboarding. Verification worked around it by driving an existing
registry provider and restoring its junction rows afterwards.

---

---

## Phase 1: Retire Option A + reconcile architecture docs

### Overview

Delete the three uncommitted files from the Option A consumer + backfill implementation. Revert `UserEventPublisher.publishProviderOnboarded` to its original 2-arg signature and `ProviderService.onboard`'s call site to match. Rewrite the four architecture docs (`CLAUDE.md` §Critical Rule 2, `multi-tenancy.md`, `architecture.md`, `portals.md`) so they read as one coherent story about platform-scoped providers with tenant membership. No schema, no runtime code beyond the reverts.

### Changes Required

#### 1. Delete uncommitted Option A files

**Files**: three deletions in the working tree.

```bash
rm services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderOnboardedConsumer.java
rm services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderBackfillRunner.java
rm services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderOnboardedConsumerTest.java
rmdir services/java/claims-service/src/main/java/com/medfund/claims/consumer 2>/dev/null || true
rmdir services/java/claims-service/src/test/java/com/medfund/claims/consumer 2>/dev/null || true
```

Directories `rmdir`ed only if empty; if a future consumer lands here first, the directory stays.

#### 2. Revert `UserEventPublisher.publishProviderOnboarded` to 2-arg

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java`

Restore the 2-arg method signature. Payload stays `{event, providerId, name}` via `Map.of(...)`.

```java
public Mono<Void> publishProviderOnboarded(String providerId, String name) {
    return publishEvent("medfund.users.provider-onboarded", providerId, Map.of(
        "event",      "PROVIDER_ONBOARDED",
        "providerId", providerId,
        "name",       name));
}
```

#### 3. Revert `ProviderService.onboard` call site

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/ProviderService.java`

Line 128-132 goes back to the 2-arg call:

```java
.then(eventPublisher.publishProviderOnboarded(
        saved.getId().toString(),
        saved.getName()))
```

#### 4. Revert `UserEventPublisherTest`

**File**: `services/java/user-service/src/test/java/com/medfund/user/service/UserEventPublisherTest.java`

Update the `publishProviderOnboarded` test back to the 2-arg call + 3-key payload assertion. Remove any `status` / `networkTier` assertions the Option A plan added.

#### 5. Amend `.claude/CLAUDE.md` §Critical Rule 2

**File**: `.claude/CLAUDE.md`

Add a paragraph after the existing Rule 2 text. Wording:

```markdown
**Exception: platform tables with tenant membership.** A small set of tables in the
`public` schema are shared by design (`public.providers`, `public.plans`,
`public.currencies`, `public.staff_users`). When such a table has per-tenant
relevance, every tenant-scoped read must include a membership check via the
corresponding `public.<entity>_tenants` join table (e.g. `public.provider_tenants`,
which carries the per-tenant contract row). Reads that omit the membership check
leak platform data across tenants and violate the rule's intent even though they
technically hit a `public.` table.
```

#### 6. Rewrite `.claude/multi-tenancy.md` provider section

**File**: `.claude/multi-tenancy.md`

Replace the two blocks at lines 225 and 242 that describe per-schema provider replication. The new §Provider Portal note reads:

```markdown
**Provider Multi-Tenancy Note**: A provider is a single row in `public.providers`,
shared across every tenant it serves. The membership relationship lives in
`public.provider_tenants` (composite PK `(provider_id, tenant_id)`, carries
per-tenant `network_tier`, `in_network`, `status`, and future contract fields).
The line(s) a provider serves live in `public.provider_insurance_lines`
(composite PK, CHECK-constrained to `InsuranceLine` values). When a provider
logs in (deferred to a follow-up plan), they authenticate against the
`medfund-platform` Keycloak realm and see a tenant switcher built from their
`public.provider_tenants` list.
```

#### 7. Reconcile `.claude/architecture.md`

**File**: `.claude/architecture.md`

At line 588 the `public` schema tree keeps its `providers` entry, plus two new lines:

```
│   ├── providers                  — Platform-level provider registry
│   ├── provider_tenants           — (provider, tenant) membership + per-tenant contract fields
│   ├── provider_insurance_lines   — (provider, line) tag; CHECK-constrained to InsuranceLine
```

At line 599 (inside the `tenant_{uuid}` tree), remove `providers` from the enumerated list. The line reads today:

```
│   ├── members, dependants, providers, groups, group_liaisons
```

It becomes:

```
│   ├── members, dependants, groups, group_liaisons
```

#### 8. Amend `.claude/portals.md:207`

**File**: `.claude/portals.md`

Replace *"Each provider has accounts in every tenant realm they serve. A provider user can belong to multiple tenant realms."* with:

```markdown
**Keycloak Realm**: A provider has a single identity in the `medfund-platform`
Keycloak realm. The Angular app fetches their `public.provider_tenants` list
after login and shows a tenant switcher in the header. When switching tenants,
the same session is scoped by an `X-Tenant-ID` header on every subsequent
request. (Provider self-service portal + Flutter provider mode are unbuilt as of
2026-09-20; see `thoughts/shared/plans/` for status.)
```

### Success Criteria

#### Automated Verification

- [x] `git status` shows the three Option A files removed, `UserEventPublisher.java` + `ProviderService.java` reverted, and the four `.claude/*.md` files modified
- [x] Java compiles clean: `cd services/java && ./gradlew :user-service:test :claims-service:test` (see Deviations: `build` substituted with `test`)
- [x] User-service unit tests pass: `cd services/java && ./gradlew :user-service:test` — 1 pre-existing unrelated failure (`PersistencyCohortReportServiceTest`, see Deviations); everything else green
- [x] Claims-service unit tests pass (no consumer test to break): `cd services/java && ./gradlew :claims-service:test`
- [x] `grep -RIn "publishProviderOnboarded" services/java/user-service/src/main services/java/user-service/src/test` shows only the 2-arg signature
- [x] `grep -RIn "ProviderOnboardedConsumer\|ProviderBackfillRunner" services/java/` returns empty
- [x] `grep -n "profile is replicated" .claude/multi-tenancy.md` returns empty
- [x] `grep -n "accounts in every tenant realm" .claude/portals.md` returns empty
- [x] `grep -c "providers" .claude/architecture.md` matches the expected count after the tenant-tree line edit (drops by one): returns 10

#### Manual Verification

- [ ] Read the four `.claude/*.md` files back-to-back; the story reads as one coherent design (platform table + membership join + platform-realm identity + deferred portal)
- [ ] Boot user-service and claims-service natively (`make user && make claims`); services come up green, `curl http://localhost:8082/actuator/health` and `:8083/actuator/health` return 200

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm the doc reads before starting Phase 2.

---

## Phase 2: Platform schema (V185) + tenant FK retarget (V275) + backfill

### Overview

New `public/V185__platform_provider_membership.sql`: create `public.provider_tenants` (composite PK, full contract-set columns), `public.provider_insurance_lines` (composite PK, `CHECK` on `InsuranceLine`), extend `provision_tenant_role.v_readable_tables` to include both, re-provision every existing tenant. Then backfill both tables from the current tenant `providers` shadow rows so nothing 422s at Phase 3. New `tenant/V275__provider_fk_retarget.sql`: retarget the 3 hard FKs (`claims.provider_id`, `payments.provider_id`, `quotations.provider_id`) from `tenant_x.providers` to `public.providers`. `tenant.providers` stays alive; it drops in Phase 5.

### Changes Required

#### 1. New public migration: V185

**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V185__platform_provider_membership.sql`

```sql
-- ============================================================
-- V185: Platform provider membership + insurance-line tagging
-- ============================================================
-- Providers are platform-scoped (public.providers) but their relationship
-- to each tenant is a first-class entity: contract terms, network tier,
-- in-network status. This migration adds both membership junctions and
-- backfills them from every tenant's providers shadow table (which still
-- exists at this point; V275 tenant-side migration drops it later in the
-- same PR series, after every service has repointed reads).

-- ── public.provider_tenants ─────────────────────────────────
CREATE TABLE IF NOT EXISTS public.provider_tenants (
    provider_id              UUID          NOT NULL REFERENCES public.providers(id) ON DELETE RESTRICT,
    tenant_id                UUID          NOT NULL REFERENCES public.tenants(id)   ON DELETE RESTRICT,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'active',
    network_tier             VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    in_network               BOOLEAN       NOT NULL DEFAULT TRUE,
    contract_effective_from  DATE          NOT NULL DEFAULT CURRENT_DATE,
    contract_effective_to    DATE          NULL,
    credit_limit             NUMERIC(15,2) NULL,
    credit_limit_currency    CHAR(3)       NULL REFERENCES public.currencies(code),
    tariff_agreement_id      UUID          NULL,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by               UUID          NULL,
    updated_by               UUID          NULL,
    PRIMARY KEY (provider_id, tenant_id),
    CONSTRAINT provider_tenants_status_ck CHECK (status IN ('active','pending','terminated','suspended')),
    CONSTRAINT provider_tenants_tier_ck   CHECK (network_tier IN ('STANDARD','TIER_1','TIER_2','TIER_3')),
    CONSTRAINT provider_tenants_dates_ck  CHECK (contract_effective_to IS NULL OR contract_effective_to >= contract_effective_from)
);

CREATE INDEX IF NOT EXISTS ix_provider_tenants_tenant  ON public.provider_tenants (tenant_id);
CREATE INDEX IF NOT EXISTS ix_provider_tenants_status  ON public.provider_tenants (status);

-- ── public.provider_insurance_lines ─────────────────────────
CREATE TABLE IF NOT EXISTS public.provider_insurance_lines (
    provider_id     UUID        NOT NULL REFERENCES public.providers(id) ON DELETE CASCADE,
    insurance_line  VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (provider_id, insurance_line),
    CONSTRAINT provider_insurance_lines_ck CHECK (insurance_line IN
        ('HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'))
);

CREATE INDEX IF NOT EXISTS ix_provider_insurance_lines_line ON public.provider_insurance_lines (insurance_line);

-- ── Extend provision_tenant_role ────────────────────────────
CREATE OR REPLACE FUNCTION public.provision_tenant_role(p_schema_name text) RETURNS void AS $$
DECLARE
    v_role text := p_schema_name || '_role';
    v_table text;
    v_readable_tables text[] := ARRAY[
        'tenants',
        'providers',
        'currencies',
        'exchange_rates',
        'tenant_currency_config',
        'tenant_rules',
        'tenant_email_templates',
        'branding_config',
        'payment_methods',
        'transaction_types',
        'benefit_types',
        'notification_templates',
        'plans',
        'staff_users',
        'tenant_report_config',
        'tenant_high_cost_claimant_config',
        -- Added V185: provider membership + line tagging. Tenant-scoped
        -- reads join public.providers through provider_tenants.
        'provider_tenants',
        'provider_insurance_lines'
    ];
    v_writable_tables text[] := ARRAY[
        'scheduled_job_configs',
        'scheduled_job_runs',
        'notifications'
    ];
BEGIN
    -- (body identical to V182, only v_readable_tables changed above)
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
        EXECUTE format('CREATE ROLE %I NOLOGIN NOINHERIT', v_role);
    END IF;
    EXECUTE format('GRANT %I TO %I', v_role, current_user);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('GRANT ALL ON ALL TABLES    IN SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('GRANT ALL ON ALL SEQUENCES IN SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON TABLES    TO %I', p_schema_name, v_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON SEQUENCES TO %I', p_schema_name, v_role);
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', v_role);
    FOREACH v_table IN ARRAY v_readable_tables LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema='public' AND table_name=v_table) THEN
            EXECUTE format('GRANT SELECT ON public.%I TO %I', v_table, v_role);
        END IF;
    END LOOP;
    FOREACH v_table IN ARRAY v_writable_tables LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema='public' AND table_name=v_table) THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO %I', v_table, v_role);
        END IF;
    END LOOP;
END; $$ LANGUAGE plpgsql;

-- Backfill: re-provision every existing tenant so already-provisioned roles
-- pick up the two new grants without waiting for the next re-provision.
DO $$
DECLARE t RECORD;
BEGIN
    FOR t IN SELECT schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        PERFORM public.provision_tenant_role(t.schema_name);
    END LOOP;
END $$;

-- ── Backfill provider_tenants from existing tenant shadows ──
-- Every tenant's providers table (which still exists at this point) contains
-- exactly the rows that tenant is contracted with. Copy them across, preserving
-- the tenant.providers.status and network_tier if set. Anything unmatched in
-- public.providers is skipped (the Phase 5 destructive migration handles orphans).
DO $$
DECLARE t RECORD;
BEGIN
    FOR t IN SELECT id AS tenant_id, schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        EXECUTE format($f$
            INSERT INTO public.provider_tenants (provider_id, tenant_id, status, network_tier, in_network)
            SELECT tp.id, %L::uuid,
                   COALESCE(tp.status, 'active'),
                   COALESCE(tp.network_tier, 'STANDARD'),
                   TRUE
              FROM %I.providers tp
             WHERE EXISTS (SELECT 1 FROM public.providers pp WHERE pp.id = tp.id)
            ON CONFLICT (provider_id, tenant_id) DO NOTHING
        $f$, t.tenant_id, t.schema_name);
    END LOOP;
END $$;

-- ── Backfill provider_insurance_lines ──────────────────────
-- Every existing provider gets a line tag inferred from public.providers.provider_type.
-- Default HEALTH; BROKER/ADVISOR/FINANCIAL → LIFE; FUNERAL_PARLOUR → FUNERAL.
-- No provider ends up untagged (Phase 6 validation would 422 every claim otherwise).
INSERT INTO public.provider_insurance_lines (provider_id, insurance_line)
SELECT id, 'HEALTH' FROM public.providers
 WHERE provider_type IS NULL OR provider_type IN ('HEALTH','HEALTHCARE','GP','SPECIALIST','HOSPITAL','PHARMACY','LAB','DENTAL','OPTICAL')
ON CONFLICT DO NOTHING;

INSERT INTO public.provider_insurance_lines (provider_id, insurance_line)
SELECT id, 'LIFE' FROM public.providers
 WHERE provider_type IN ('BROKER','ADVISOR','FINANCIAL')
ON CONFLICT DO NOTHING;

INSERT INTO public.provider_insurance_lines (provider_id, insurance_line)
SELECT id, 'FUNERAL' FROM public.providers
 WHERE provider_type = 'FUNERAL_PARLOUR'
ON CONFLICT DO NOTHING;
```

#### 2. New tenant migration: V275

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V275__provider_fk_retarget.sql`

```sql
-- ============================================================
-- V275: Retarget the 3 hard provider FKs to public.providers
-- ============================================================
-- claims.provider_id, payments.provider_id, quotations.provider_id
-- currently REFERENCE the tenant-local providers table. This migration
-- retargets them to public.providers. Same pattern as tenant/V268:33-41:
-- ALTER TABLE ADD CONSTRAINT inside a guarded DO $$ IF NOT EXISTS $$.
--
-- Preconditions:
--   - public.provider_tenants populated (V185 backfilled it).
--   - No orphaned provider_id values in claims / payments / quotations
--     (verified 2026-09-20: zero rows across all live tenants).
--
-- The tenant.providers table stays alive; Phase 5 drops it.

DO $$
BEGIN
    -- claims.provider_id
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'claims_provider_id_fkey') THEN
        ALTER TABLE claims DROP CONSTRAINT claims_provider_id_fkey;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'claims_provider_id_public_fkey') THEN
        ALTER TABLE claims
            ADD CONSTRAINT claims_provider_id_public_fkey
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE RESTRICT;
    END IF;

    -- payments.provider_id (nullable)
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payments_provider_id_fkey') THEN
        ALTER TABLE payments DROP CONSTRAINT payments_provider_id_fkey;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payments_provider_id_public_fkey') THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_provider_id_public_fkey
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE SET NULL;
    END IF;

    -- quotations.provider_id (NOT NULL)
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'quotations_provider_id_fkey') THEN
        ALTER TABLE quotations DROP CONSTRAINT quotations_provider_id_fkey;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'quotations_provider_id_public_fkey') THEN
        ALTER TABLE quotations
            ADD CONSTRAINT quotations_provider_id_public_fkey
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE RESTRICT;
    END IF;
END $$;
```

Actual constraint names in a specific tenant may differ. The Phase 2 test verification adds a psql check that emits the current constraint names before applying the migration and confirms every constraint that referenced tenant.providers is gone afterwards.

#### 3. Java entities for the two new tables

**File**: `services/java/user-service/src/main/java/com/medfund/user/entity/ProviderTenant.java` (new)

Pattern lifted from `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/TreatyParticipant.java`. No `@Id` (composite key), plain `@Getter @Setter @Table(...)`.

```java
package com.medfund.user.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * (provider, tenant) membership with per-tenant contract terms.
 * Composite key on (providerId, tenantId); repository hand-rolls SQL via
 * DatabaseClient because R2DBC's ReactiveCrudRepository does not support
 * composite keys out of the box (see TreatyParticipantRepository for the
 * canonical pattern).
 */
@Getter
@Setter
@Table(schema = "public", value = "provider_tenants")
public class ProviderTenant {
    @Column("provider_id")             private UUID providerId;
    @Column("tenant_id")               private UUID tenantId;
    @Column("status")                  private String status;
    @Column("network_tier")            private String networkTier;
    @Column("in_network")              private Boolean inNetwork;
    @Column("contract_effective_from") private LocalDate contractEffectiveFrom;
    @Column("contract_effective_to")   private LocalDate contractEffectiveTo;
    @Column("credit_limit")            private BigDecimal creditLimit;
    @Column("credit_limit_currency")   private String creditLimitCurrency;
    @Column("tariff_agreement_id")     private UUID tariffAgreementId;
    @Column("created_at")              private OffsetDateTime createdAt;
    @Column("updated_at")              private OffsetDateTime updatedAt;
    @Column("created_by")              private UUID createdBy;
    @Column("updated_by")              private UUID updatedBy;
}
```

**File**: `services/java/user-service/src/main/java/com/medfund/user/entity/ProviderInsuranceLine.java` (new)

```java
package com.medfund.user.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Table(schema = "public", value = "provider_insurance_lines")
public class ProviderInsuranceLine {
    @Column("provider_id")    private UUID providerId;
    @Column("insurance_line") private String insuranceLine;
    @Column("created_at")     private OffsetDateTime createdAt;
}
```

#### 4. Hand-rolled repositories

**File**: `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderTenantRepository.java` (new)

Pattern lifted from `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/repository/TreatyParticipantRepository.java` line-for-line. Only the methods v1 actually needs.

```java
package com.medfund.user.repository;

import com.medfund.user.entity.ProviderTenant;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProviderTenantRepository {
    private final DatabaseClient db;

    public Flux<ProviderTenant> findByProviderId(UUID providerId) {
        return db.sql("""
                SELECT provider_id, tenant_id, status, network_tier, in_network,
                       contract_effective_from, contract_effective_to,
                       credit_limit, credit_limit_currency, tariff_agreement_id,
                       created_at, updated_at, created_by, updated_by
                  FROM public.provider_tenants
                 WHERE provider_id = :providerId
                 ORDER BY tenant_id
                """)
                .bind("providerId", providerId)
                .map(ProviderTenantRepository::mapRow)
                .all();
    }

    public Mono<ProviderTenant> findByProviderIdAndTenantId(UUID providerId, UUID tenantId) {
        return db.sql("""
                SELECT provider_id, tenant_id, status, network_tier, in_network,
                       contract_effective_from, contract_effective_to,
                       credit_limit, credit_limit_currency, tariff_agreement_id,
                       created_at, updated_at, created_by, updated_by
                  FROM public.provider_tenants
                 WHERE provider_id = :providerId AND tenant_id = :tenantId
                """)
                .bind("providerId", providerId)
                .bind("tenantId",   tenantId)
                .map(ProviderTenantRepository::mapRow)
                .one();
    }

    public Mono<ProviderTenant> insert(ProviderTenant p) {
        return db.sql("""
                INSERT INTO public.provider_tenants
                    (provider_id, tenant_id, status, network_tier, in_network,
                     contract_effective_from, contract_effective_to,
                     credit_limit, credit_limit_currency, tariff_agreement_id, created_by)
                VALUES (:providerId, :tenantId, :status, :networkTier, :inNetwork,
                        COALESCE(:effectiveFrom, CURRENT_DATE), :effectiveTo,
                        :creditLimit, :creditLimitCurrency, :tariffAgreementId, :createdBy)
                RETURNING provider_id, tenant_id, status, network_tier, in_network,
                          contract_effective_from, contract_effective_to,
                          credit_limit, credit_limit_currency, tariff_agreement_id,
                          created_at, updated_at, created_by, updated_by
                """)
                .bind("providerId",          p.getProviderId())
                .bind("tenantId",            p.getTenantId())
                .bind("status",              p.getStatus() != null ? p.getStatus() : "active")
                .bind("networkTier",         p.getNetworkTier() != null ? p.getNetworkTier() : "STANDARD")
                .bind("inNetwork",           p.getInNetwork() != null ? p.getInNetwork() : Boolean.TRUE)
                .bind("effectiveFrom",       p.getContractEffectiveFrom())
                .bind("effectiveTo",         p.getContractEffectiveTo())
                .bind("creditLimit",         p.getCreditLimit())
                .bind("creditLimitCurrency", p.getCreditLimitCurrency())
                .bind("tariffAgreementId",   p.getTariffAgreementId())
                .bind("createdBy",           p.getCreatedBy())
                .map(ProviderTenantRepository::mapRow)
                .one();
    }

    public Mono<Long> delete(UUID providerId, UUID tenantId) {
        return db.sql("DELETE FROM public.provider_tenants WHERE provider_id = :providerId AND tenant_id = :tenantId")
                .bind("providerId", providerId)
                .bind("tenantId",   tenantId)
                .fetch().rowsUpdated();
    }

    private static ProviderTenant mapRow(Row row, RowMetadata meta) {
        ProviderTenant p = new ProviderTenant();
        p.setProviderId(row.get("provider_id", UUID.class));
        p.setTenantId(row.get("tenant_id", UUID.class));
        p.setStatus(row.get("status", String.class));
        p.setNetworkTier(row.get("network_tier", String.class));
        p.setInNetwork(row.get("in_network", Boolean.class));
        p.setContractEffectiveFrom(row.get("contract_effective_from", java.time.LocalDate.class));
        p.setContractEffectiveTo(row.get("contract_effective_to", java.time.LocalDate.class));
        p.setCreditLimit(row.get("credit_limit", java.math.BigDecimal.class));
        p.setCreditLimitCurrency(row.get("credit_limit_currency", String.class));
        p.setTariffAgreementId(row.get("tariff_agreement_id", UUID.class));
        p.setCreatedAt(row.get("created_at", java.time.OffsetDateTime.class));
        p.setUpdatedAt(row.get("updated_at", java.time.OffsetDateTime.class));
        p.setCreatedBy(row.get("created_by", UUID.class));
        p.setUpdatedBy(row.get("updated_by", UUID.class));
        return p;
    }
}
```

**File**: `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderInsuranceLineRepository.java` (new)

Same shape, shorter. Methods: `findByProviderId(UUID) : Flux<ProviderInsuranceLine>`, `insert(UUID providerId, String line) : Mono<ProviderInsuranceLine>`, `delete(UUID providerId, String line) : Mono<Long>`, `existsByProviderIdAndLine(UUID, String) : Mono<Boolean>` (used by Phase 6's `ClaimService.validateProviderPolicy`).

#### 5. Integration test scaffolding

**File**: `services/java/user-service/src/test/java/com/medfund/user/repository/ProviderTenantRepositoryIT.java` (new)

Testcontainers Postgres. Provision `public` migrations up to V185; seed a tenant row + a provider row; call `insert / findByProviderId / findByProviderIdAndTenantId / delete`; assert row shape. Cover the composite-PK uniqueness (INSERT twice returns error).

**File**: `services/java/user-service/src/test/java/com/medfund/user/repository/ProviderInsuranceLineRepositoryIT.java` (new)

Same shape. Cover the `CHECK` constraint (inserting `insurance_line = 'WRONG'` errors).

**File**: `services/java/tenancy-service/src/test/java/com/medfund/tenancy/integration/TenantMigrationFlywayIT.java` (edit)

Add assertions that V185 creates `public.provider_tenants` and `public.provider_insurance_lines`; that `provision_tenant_role` grants SELECT on both to the tenant role. Do not touch the existing tenant.providers assertions at lines 159/356/361/368/542/655 yet; those go in Phase 5.

### Success Criteria

#### Automated Verification

- [x] Java compiles clean: `cd services/java && ./gradlew :tenancy-service:test :user-service:test` (see Deviations: `build` substituted with `test`)
- [x] Tenancy-service unit tests pass: `cd services/java && ./gradlew :tenancy-service:test` — 78 pre-existing failures at pristine HEAD, unchanged by this phase (see Deviations); the new `v185AndV275_…` test passes
- [x] User-service unit tests pass (new ITs included): `cd services/java && ./gradlew :user-service:test` — 448 tests, 1 failure, the pre-existing `PersistencyCohortReportServiceTest`
- [x] Integration tests pass: `ProviderTenantRepositoryIT` (8) + `ProviderInsuranceLineRepositoryIT` (6) green; `TenantMigrationFlywayIT` green apart from the two pre-existing `Flyway.target(...)` failures
- [x] `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM public.provider_tenants;"` returns 180 (60 × 3 tenants; the 5 `tenant_first_medfund` orphans absent from `public.providers` are correctly skipped)
- [x] `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM public.provider_insurance_lines;"` returns 60 (50 HEALTH + 7 LIFE + 3 FUNERAL; zero untagged providers)
- [x] `psql "$POSTGRES_URL" -tAc "SELECT conname FROM pg_constraint WHERE conrelid = 'tenant_health_first.claims'::regclass AND contype = 'f' AND conname LIKE '%provider%';"` returns `claims_provider_id_public_fkey` (retargeted) and no `claims_provider_id_fkey`. Catalogue-wide check: zero FKs in any schema still reference a tenant-local `providers`
- [x] `psql "$POSTGRES_URL" -tAc "\d public.provider_tenants"` shows the composite PK + 3 CHECK constraints + 2 indexes as designed
- [x] Tenant roles can read the new tables: `has_table_privilege('tenant_<x>_role', 'public.provider_tenants', 'SELECT')` is true for all 3 live tenants, same for `provider_insurance_lines`
- [x] Existing curl paths still work: `curl http://localhost:8082/api/v1/providers` returns 200 with `totalCount: 60`, unchanged
- [x] Existing claim submission with an existing provider still returns 201 (no policy check yet: Phase 6 adds membership + line validation). Verified through the retargeted FK against `tenant_health_first`; the verification claim was deleted afterwards

#### Manual Verification

- [ ] Boot every Java service natively (`make tenancy && make user && make claims && make contributions && make finance`); every service comes up green
- [ ] Onboard a new provider via `POST /api/v1/providers`; verify a row lands in `public.providers` and (once Phase 6 lands) a companion row in `public.provider_tenants` for any linked tenants. In Phase 2 alone, no `provider_tenants` row is created for a new provider until an operator links them manually via psql or the admin UI (Phase 6)
- [ ] The Angular admin `/platform/providers` page continues to work unchanged

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm the schema against live data before starting Phase 3.

---

## Phase 3: Repoint claims-service reads + populate ProviderFact

### Overview

14 SQL sites in `ClaimsReportQueryRepository.java`, `ClaimQueryRepository.java`, `PreAuthorizationQueryRepository.java`. Convert every `JOIN providers p ON p.id = c.provider_id` to `JOIN public.providers p ON p.id = c.provider_id AND EXISTS (SELECT 1 FROM public.provider_tenants pt WHERE pt.provider_id = p.id AND pt.tenant_id = :tenantId)`, with `:tenantId` bound from `TenantContext`. Extend `ClaimFactBuilder.fetchProvider` at line 202-213 to populate `ProviderFact.inNetwork` + `ProviderFact.networkTier` from the same join (the currently-null fields declared at `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ProviderFact.java:12-16`). Update `AbstractClaimsReportIT` + test-migration V001 + every subclass so their seeded fixtures include `provider_tenants` rows.

### Changes Required

#### 1. Rewrite the 14 claims-service SQL sites

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/repository/ClaimsReportQueryRepository.java`

For each of the 8 sites (lines 145, 336, 397, 513, 599, 660, 809, 927), swap the unqualified `providers` for `public.providers` and add the membership guard. Two representative examples:

Line 145 (`perProviderSummary`, INNER JOIN):

```java
// Before
SELECT c.provider_id AS dimension_id, p.name AS dimension_name, ...
  FROM claims c
  JOIN providers p ON p.id = c.provider_id
 GROUP BY c.provider_id, p.name, c.currency_code
 ORDER BY p.name

// After
SELECT c.provider_id AS dimension_id, p.name AS dimension_name, ...
  FROM claims c
  JOIN public.providers p ON p.id = c.provider_id
   AND EXISTS (SELECT 1 FROM public.provider_tenants pt
                WHERE pt.provider_id = p.id AND pt.tenant_id = :tenantId)
 GROUP BY c.provider_id, p.name, c.currency_code
 ORDER BY p.name
```

Line 336 (`dimensionName`):

```java
// Before
SELECT name AS dimension_name FROM providers WHERE id = :id

// After
SELECT p.name AS dimension_name
  FROM public.providers p
 WHERE p.id = :id
   AND EXISTS (SELECT 1 FROM public.provider_tenants pt
                WHERE pt.provider_id = p.id AND pt.tenant_id = :tenantId)
```

Every rewrite binds `:tenantId` from `TenantContext`. Helper: add a private static `String withTenantFilter(String baseJoin)` if the SQL text becomes repetitive across the 8 methods; otherwise inline for clarity.

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/repository/ClaimQueryRepository.java`

Two sites (`count` at line 73, `baseFrom` at line 95). Same pattern.

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/repository/PreAuthorizationQueryRepository.java`

Two sites (`count` at line 65, `baseFrom` at line 87). Same pattern.

Every rewrite adds a `.bind("tenantId", ...)` to the query builder. Source: `Mono.deferContextual(ctx -> {...})` to reach into `TenantContext.KEY` from the reactive context. Precedent for that pull-from-context pattern: `services/java/shared/src/main/java/com/medfund/shared/tenant/TenantAwareConnectionFactory.java:74-90`.

#### 2. Populate ProviderFact.inNetwork + networkTier

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimFactBuilder.java`

Rewrite `fetchProvider` at lines 202-213 to select from the join:

```java
private Mono<ProviderFact> fetchProvider(String providerId) {
    return Mono.deferContextual(ctx -> {
        String tenantIdStr = ctx.get(com.medfund.shared.tenant.TenantContext.KEY);
        return db.sql("""
                SELECT p.id, p.status, p.provider_type,
                       pt.in_network, pt.network_tier
                  FROM public.providers p
             LEFT JOIN public.provider_tenants pt
                    ON pt.provider_id = p.id AND pt.tenant_id = :tenantId
                 WHERE p.id = :id
                """)
                .bind("id", java.util.UUID.fromString(providerId))
                .bind("tenantId", java.util.UUID.fromString(tenantIdStr))
                .fetch().one()
                .map(row -> {
                    ProviderFact p = new ProviderFact();
                    p.setProviderId(providerId);
                    p.setRegistrationStatus((String) row.get("status"));
                    p.setInNetwork((Boolean) row.get("in_network"));
                    p.setNetworkTier((String) row.get("network_tier"));
                    return p;
                })
                .defaultIfEmpty(emptyProvider(providerId))
                .onErrorResume(err -> {
                    log.debug("[fact-builder] provider lookup failed for {}: {}", providerId, err.getMessage());
                    return Mono.just(emptyProvider(providerId));
                });
    });
}
```

The `LEFT JOIN` is deliberate: a provider not (yet) linked to the current tenant still returns a ProviderFact with `providerId` + `status` set; `inNetwork` and `networkTier` come back null in that case, matching the field's `Boolean` (nullable) type. Phase 6's `validateProviderPolicy` is where the missing membership becomes a 422; the fact builder itself does not reject.

#### 3. Update abstract IT fixture + test-migration mirror

**File**: `services/java/claims-service/src/test/resources/db/test-migration/V001__claims_report_it.sql`

Add the two junction tables to the mirror (the test-migration file already mirrors the tables the ITs use). Add columns to the mirror's `providers` table if the rewritten SQL needs `p.network_tier` etc; today the test-migration only has `(id, name, created_at)` at lines 122-126. Since the rewrites join through `provider_tenants`, the test-migration DDL adds:

```sql
CREATE TABLE IF NOT EXISTS provider_tenants (
    provider_id     UUID NOT NULL,
    tenant_id       UUID NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    network_tier    VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    in_network      BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (provider_id, tenant_id)
);
CREATE TABLE IF NOT EXISTS provider_insurance_lines (
    provider_id     UUID NOT NULL,
    insurance_line  VARCHAR(20) NOT NULL,
    PRIMARY KEY (provider_id, insurance_line)
);
```

The IT fixture puts these tables in the *tenant* schema for test purposes (not `public.`), because the IT harness runs everything under a single test schema. That's why the rewrites use `public.provider_tenants` in production but the test-migration mirror creates a same-named local table; the search-path resolves to the local test table. Cross-check: this matches how `providers` itself is mirrored today (production is `public.providers`, test is `<schema>.providers`).

**File**: `services/java/claims-service/src/test/java/com/medfund/claims/integration/AbstractClaimsReportIT.java`

Extend `seedProvider(String name)` at line 151 to also insert a `provider_tenants` row keyed to the same tenant the IT is running under (`TenantTestContext.get()`). New signature: `seedProvider(String name, String... lines)` where lines default to `HEALTH`; every existing caller keeps compiling because Java varargs.

```java
protected UUID seedProvider(String name, String... lines) {
    UUID id = UUID.randomUUID();
    UUID tenantId = TenantTestContext.get();
    db.sql("INSERT INTO providers (id, name) VALUES (:id, :name)")
            .bind("id", id).bind("name", name).then().block();
    db.sql("""
            INSERT INTO provider_tenants (provider_id, tenant_id, status, network_tier, in_network)
            VALUES (:pid, :tid, 'active', 'STANDARD', TRUE)
            """)
            .bind("pid", id).bind("tid", tenantId).then().block();
    for (String line : lines.length == 0 ? new String[]{"HEALTH"} : lines) {
        db.sql("INSERT INTO provider_insurance_lines (provider_id, insurance_line) VALUES (:pid, :line)")
                .bind("pid", id).bind("line", line).then().block();
    }
    return id;
}
```

Extend the `TRUNCATE` at line 88 to include `provider_tenants` and `provider_insurance_lines`.

#### 4. Add ITs for the 3 INNER-JOIN provider report queries

**File**: `services/java/claims-service/src/test/java/com/medfund/claims/integration/ClaimsReportProviderJoinIT.java` (new)

Assertions the existing ITs skip: `perProviderSummary`, `aggregateProvider`, `aggregateMonthlyProvider` (INNER JOINs at lines 145, 513, 599) must return the provider's `dimension_name`. Seed 3 providers; seed claims for each; call the aggregate endpoint; assert every row has a `dimensionName` matching the seeded name. Also seed a fourth provider *without* a `provider_tenants` row and assert its claims are excluded from the aggregate (the point of the membership guard).

### Success Criteria

#### Automated Verification

- [x] Java compiles clean: `cd services/java && ./gradlew :claims-service:test` (see Deviations: `build` substituted with `test`); `./gradlew classes testClasses` green across every module after the shared `TenantContext` addition
- [x] Unit tests pass: `cd services/java && ./gradlew :claims-service:test` — 370 tests, 0 failures
- [x] Integration tests pass: `make test-integration` — claims-service 66 ITs green; tenancy-service (71) and finance-service (29) carry their pre-existing red baselines, both reproduced on a stashed tree (see Deviations)
- [x] `ClaimsReportProviderJoinIT` covers all 3 INNER JOIN sites with a dimension_name assertion + a membership-exclusion assertion
- [x] `grep -RIn 'JOIN providers ' services/java/claims-service/src/main/java` returns empty (every JOIN is now qualified)
- [x] `grep -RIn 'FROM providers ' services/java/claims-service/src/main/java` returns empty
- [x] Curl-level: `/api/v1/reports/claims/providers` returns 200 for health-first (empty data both before and after: that tenant has zero claims on live data, and the only live claim anywhere has a null `provider_id`). Regression covered instead by running the rewritten join verbatim under `SET ROLE tenant_health_first_role`: parses, and 60 providers are visible through `provider_tenants`, matching the pre-Phase-3 shadow count. `/api/v1/claims/page` and `/api/v1/pre-authorizations/page` (the two rewritten list repositories) both return 200 with joined names on live data

#### Manual Verification

- [ ] Angular `/admin/reports/claims/providers`: the report page renders with the same providers and same numbers as before Phase 3 (regression check)
- [ ] Submit a claim; then `SELECT in_network, network_tier FROM public.provider_tenants pt JOIN claims c ON c.provider_id = pt.provider_id WHERE c.id = '<claim id>' AND pt.tenant_id = '<current tenant>'` returns the expected row. Fact builder log line at `ClaimFactBuilder.java:206` shows the enriched ProviderFact
- [ ] `verify` skill on `http://localhost:5100/admin/reports/claims/providers`: no console errors, table renders with data, provider filter fires

**Implementation Note**: pause for the human to eyeball the report before starting Phase 4.

---

## Phase 4: Repoint finance-service reads + practice_number fix

### Overview

16 SQL sites across 7 query repositories + `BalanceHistoryService.loadProviderName` + `PaymentAdviceService`. Same rewrite pattern as Phase 3. Also fix `CreditorQueryRepository.java:120,124` where `pr.practice_number` is projected AND used in a WHERE filter (the column no longer exists on `public.providers`; renamed to `registration_number` in V106). Update the 2 finance ITs + test-migration V005 + the literal SQL-string assertion in `PaymentRunFactBuilderTest.java:89`. Add ITs for the 5 finance query repos that today have zero provider-join coverage.

### Changes Required

#### 1. Rewrite the 16 finance-service SQL sites

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/PaymentQueryRepository.java` (sites at lines 53, 70). Add `public.` prefix + `EXISTS (public.provider_tenants ...)` guard on the LEFT JOIN.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/NoteQueryRepository.java` — sites at 64, 115. Same pattern.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/CreditorQueryRepository.java` — sites at 120 (WHERE filter `LOWER(COALESCE(pr.practice_number, ''))`), 124 (SELECT `pr.practice_number AS subject_code`), 131 (LEFT JOIN). Two edits per line:

```java
// Before (line 120)
+ " OR LOWER(COALESCE(pr.practice_number, '')) LIKE :qLower) ");
// After
+ " OR LOWER(COALESCE(pr.registration_number, '')) LIKE :qLower) ");

// Before (line 124)
+ "       pr.practice_number AS subject_code,"
// After
+ "       pr.registration_number AS subject_code,"

// Before (line 131)
+ "  LEFT JOIN providers pr ON pr.id = b.provider_id"
// After
+ "  LEFT JOIN public.providers pr ON pr.id = b.provider_id"
+ "   AND EXISTS (SELECT 1 FROM public.provider_tenants pt WHERE pt.provider_id = pr.id AND pt.tenant_id = :tenantId)"
```

Add `.bind("tenantId", ...)` to every `providerBranch` call site.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/ProviderBalanceQueryRepository.java` — 48, 64. Same.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/AdvancePaymentQueryRepository.java` — 44, 61. Same.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/PaymentAdviceQueryRepository.java` — 53 (`BASE_SELECT`), 84, 105. Same.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/PaymentRunWorkbookQueryRepository.java` — 49. Same.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/BalanceHistoryService.java:82` — `SELECT name FROM providers WHERE id = :id` → `SELECT p.name FROM public.providers p WHERE p.id = :id AND EXISTS (SELECT 1 FROM public.provider_tenants pt WHERE pt.provider_id = p.id AND pt.tenant_id = :tenantId)`.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentAdviceService.java:244` — same shape.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunFactBuilder.java:81` — already `public.providers`, unchanged.

#### 2. Update the two finance ITs

**File**: `services/java/finance-service/src/test/resources/db/test-migration/V005__balance_history_read.sql`

Add `provider_tenants` mirror. Same shape as the claims-service test-migration mirror.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/BalanceHistoryControllerIT.java`

At the seed block (lines 80-85), add `INSERT INTO provider_tenants (provider_id, tenant_id, status, network_tier, in_network) VALUES (:pid, :tid, 'active', 'STANDARD', TRUE)` after each provider insert. Extend the TRUNCATE at line 80 to include the new table.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/PaymentRunWorkbookControllerIT.java`

Same treatment at the seed block (lines 92-95).

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/service/PaymentRunFactBuilderTest.java:89`

The assertion `.anyMatch(sql -> sql.contains("FROM public.providers"))` continues to hold (Phase 3's rewrite kept `public.providers` there). No change needed unless the rewrite added a `provider_tenants` join to this specific fact builder query, in which case update the assertion to `.anyMatch(sql -> sql.contains("FROM public.providers") && sql.contains("provider_tenants"))`.

#### 3. Add ITs for the 5 untested finance query repos

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/CreditorReportProviderJoinIT.java` (new)

Cover `CreditorQueryRepository.providerBranch`: seed a provider + provider_balance + provider_tenants row; call `/api/v1/reports/creditors`; assert the `subject_code` is `registration_number` (not `practice_number`). The one test that catches Phase 4's most important behavior fix.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/PaymentAdviceReportProviderJoinIT.java` (new)

Cover `PaymentAdviceQueryRepository.BASE_SELECT` + `count` + `perCurrencyTotals`. Assert `payee_name` COALESCE resolves to the provider name for PROVIDER-typed advices.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/ProviderBalanceReportProviderJoinIT.java` (new)

Cover `ProviderBalanceQueryRepository.search` + `count`. Assert membership-exclusion (a provider in `public.providers` without a `provider_tenants` row does not appear in this tenant's balance list).

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/NoteReportProviderJoinIT.java` (new)

Cover `NoteQueryRepository.search` + `count`. Same shape.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/AdvancePaymentReportProviderJoinIT.java` (new)

Cover `AdvancePaymentQueryRepository.search` + `count`. Same shape.

### Success Criteria

#### Automated Verification

- [x] Java compiles clean: `cd services/java && ./gradlew :finance-service:test` (see Deviations: `build` substituted with `test`)
- [x] Unit tests pass: `cd services/java && ./gradlew :finance-service:test` - 1061 tests, 30 failures, every one pre-existing: the 29-failure finance IT baseline recorded below, plus `AmlSummaryReportShaperTest` (see Deviations)
- [x] Integration tests pass: `make test-integration` - claims-service (66), user-service (92) and contributions-service (51) all green; finance-service 131 ITs with the recorded 29-failure baseline; tenancy-service with its recorded 71
- [x] `grep -RIn 'practice_number' services/java/finance-service/src/main` returns empty
- [x] `grep -RIn 'JOIN providers \|FROM providers ' services/java/finance-service/src/main/java` returns empty
- [x] The five new `*ProviderJoinIT.java` files run green (16 tests: Creditor 4, ProviderBalance 3, Note 3, AdvancePayment 3, PaymentAdvice 3)
- [x] Curl-level: `curl '/api/v1/creditors/page?subjectType=PROVIDER&q=REG'` against live `tenant_health_first` returns 200 with `subjectCode: "REG-582277668"` (see Deviations: the route is `/api/v1/creditors/page`, not `/api/v1/reports/creditors`). Membership guard checked directly against live data too: deleting the `provider_tenants` row inside a rolled-back transaction masks `subjectName` and `subjectCode` to null while the 600.0000 balance row stays on the ledger. Every other rewritten surface smoke-checked 200 on live data: `creditors/page` for PROVIDER, MEMBER and BOTH (the MEMBER-only case pins the conditional `:tenantId` bind), `payments/page`, `notes/page`, `advance-payments/page`, `payment-advices/page`, `payment-runs/page`, and `reports/balance-history/provider/{id}` which resolves `payeeName: "Brown LLC Medical"` through the guarded lookup

#### Manual Verification

- [ ] Angular `/admin/reports/creditors`: page renders with data, provider filter fires. Search by "REG" narrows the list
- [ ] Angular `/admin/reports/payments`: same
- [ ] Angular `/admin/reports/provider-balances`: same
- [ ] `verify` skill on the three above URLs: no console errors, tables render

**Implementation Note**: pause for the human to compare finance report totals against Phase 3's state (should match exactly — Phase 4 does not change what's summed, only how the join reaches the provider name).

---

## Phase 5: Repoint user-service + drop tenant.providers

### Overview

Schema-qualify all 11 `ProviderRepository` `@Query` strings so the derived + query methods agree. Rewrite the two `TenantStatsController` dynamic queries to drive from `public.providers` gated by `provider_tenants`. Then the destructive step: `tenant/V276__drop_providers.sql` (bumped one past V275 to keep migration ordering monotonic) drops the shadow `providers` table from every tenant schema after a guard-check for operator-created rows (rows with `keycloak_user_id` set that aren't in `public.providers`). Remove the six `TenantMigrationFlywayIT` assertions on `tenant.providers`. Update `UserEventPublisherTest` for the reverted 2-arg event.

### Changes Required

#### 1. Schema-qualify all `ProviderRepository` `@Query` strings

**File**: `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderRepository.java`

Every `@Query("SELECT ... FROM providers ...")` becomes `@Query("SELECT ... FROM public.providers ...")` at lines 13, 16, 19, 22, 25, 28, 31, 34, 38, 49, 57. Ten single-token edits, verified afterwards with `grep -c 'FROM providers' services/java/user-service/src/main/java/com/medfund/user/repository/ProviderRepository.java` returning 0.

These queries are called from a `/api/v1/providers` context that today has no tenant header. Schema-qualifying is a correctness cleanup, not a behavior change under the current caller. Under a future call from a tenant-scoped path, it prevents a silent read of `tenant_x.providers` (which will be dropped in step 3 anyway).

#### 2. Rewrite `TenantStatsController` :895 and :933

**File**: `services/java/user-service/src/main/java/com/medfund/user/controller/TenantStatsController.java`

**Line 895** (`getRecentPayments`): the query drives from `<schema>.payments`, LEFT JOINs `<schema>.providers pr`. Rewrite the LEFT JOIN to `LEFT JOIN public.providers pr ON pr.id = p.provider_id AND EXISTS (SELECT 1 FROM public.provider_tenants pt WHERE pt.provider_id = pr.id AND pt.tenant_id = '%s'::uuid)`, interpolating the tenant UUID into the string alongside the schema name.

**Line 933** (`getTopPayees`): the query drives from `<schema>.providers pr` and JOINs `<schema>.payments p`. Rewrite to drive from `public.providers pr JOIN public.provider_tenants pt ON pt.provider_id = pr.id AND pt.tenant_id = '<tenant-uuid>'::uuid`, then JOIN `<schema>.payments p ON p.provider_id = pr.id`. The GROUP BY stays.

Both rewrites need the tenant UUID interpolated into the SQL alongside the schema name (this controller path already interpolates the schema name for tenant-schema-name resolution reasons, so this pattern is not new).

#### 3. New destructive tenant migration

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V276__drop_providers.sql`

```sql
-- ============================================================
-- V276: Drop the tenant-local providers table
-- ============================================================
-- Providers are now platform-scoped (public.providers) with per-tenant
-- membership in public.provider_tenants. The shadow table in this schema
-- was populated by an Option A fan-out that has since been retired. All
-- code has repointed reads to public.providers as of Phase 4.
--
-- Preconditions:
--   1. V185 (public) landed and backfilled public.provider_tenants +
--      public.provider_insurance_lines.
--   2. V275 (tenant) retargeted the 3 hard FKs (claims, payments,
--      quotations) to public.providers.
--   3. Every service has been redeployed with the read-repointing.
--
-- Safety guard: fail the migration if any orphan row in this tenant's
-- providers table has a keycloak_user_id set. That would indicate real
-- operator-created data that was never mirrored to public.providers,
-- which the drop would destroy silently.

DO $$
DECLARE
    orphan_count integer;
    orphan_names text;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema = current_schema() AND table_name = 'providers') THEN
        RETURN;
    END IF;

    SELECT count(*), string_agg(name, ', ')
      INTO orphan_count, orphan_names
      FROM providers
     WHERE keycloak_user_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM public.providers pp WHERE pp.id = providers.id);

    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'Cannot drop % providers: % row(s) with keycloak_user_id set are missing from public.providers. Names: %. Resolve manually (either INSERT them into public.providers or NULL their keycloak_user_id) before retrying.',
            current_schema(), orphan_count, orphan_names;
    END IF;

    DROP TABLE providers CASCADE;
END $$;
```

CASCADE is safe because the 3 hard FKs were retargeted in V275 and the 8 soft columns have no constraint.

#### 4. Update `TenantMigrationFlywayIT`

**File**: `services/java/tenancy-service/src/test/java/com/medfund/tenancy/integration/TenantMigrationFlywayIT.java`

Remove or invert the six assertions:

- Line 159: `assertColumns(conn, "tenant_it", "providers", List.of("network_tier"))` → assert the table does *not* exist (`assertTableAbsent(conn, "tenant_it", "providers")`)
- Line 356, 361, 368: same pattern
- Line 542, 655: remove the seed / read of `tenant.providers`; replace with the equivalent against `public.providers` if the surrounding assertion still needs a provider row

Add a new assertion that V275 successfully retargeted `claims.provider_id` FK to `public.providers` (checks `pg_constraint`).

#### 5. Update `UserEventPublisherTest`

**File**: `services/java/user-service/src/test/java/com/medfund/user/service/UserEventPublisherTest.java`

Already touched by Phase 1's revert. In Phase 5, ensure the test asserts the payload is `{event, providerId, name}` and nothing more.

#### 6. Repoint the notification-service provider lookup (added: see Deviations)

**File**: `services/go/notification-service/internal/recipient/resolver.go`

`Resolver.ForProvider` (the payment-advice PROVIDER-payee recipient lookup) reads
`SELECT email, name FROM <schema>.providers WHERE id = $1`. V276 drops that table, so this
must repoint before the drop or every provider advice email fails at runtime. Rewrite to
drive from `public.providers` INNER JOINed to `public.provider_tenants` on the advice's
tenant, and drop the now-unneeded `lookupSchema` call from that function. Update the
fake-DB stub in `services/go/notification-service/internal/advice/dispatcher_test.go` from
`FROM tenant_first_medfund.providers` to `FROM public.providers`.

### Success Criteria

#### Automated Verification

- [x] Java compiles clean: `cd services/java && ./gradlew :user-service:test :tenancy-service:test` (see Deviations: `build` substituted with `test`)
- [x] Unit tests pass across the board: `make test-java` — pre-existing reds only, unchanged by this phase: user-service 450 tests / 1 failure (`PersistencyCohortReportServiceTest`), finance-service 1061 / 30, tenancy-service 242 / 71, shared 271 / 1 (`CrossServiceCallHelperTest`, newly surfaced pre-existing, see Deviations); claims-service (370), contributions-service (449) and rules-engine (154) fully green
- [x] Integration tests pass: `make test-integration` — claims-service (66), user-service (92) and contributions-service (51) green; tenancy-service 96 tests / 78 failures and finance-service 131 / 29, both exactly the recorded pre-existing baselines. `TenantMigrationFlywayIT` goes 94 → 96 tests with the two new V276 tests and stays at its 2 pre-existing `Flyway.target(...)` failures
- [x] Go tests pass for the repointed `notification-service` (added, see Deviations): `go test ./...` green in every `services/go/*` module. `make test-go` itself is broken at HEAD (`pattern ./...: directory prefix . does not contain modules listed in go.work`) and the target is untouched by this branch
- [x] `grep -RIn "FROM providers" services/java/user-service/src/main/java` returns empty. Repo-wide sweep for a tenant-schema provider read (`%s.providers`, `"<schema>".providers`) across Java and Go also returns empty
- [x] `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM tenant_health_first.providers;"` errors with `relation "tenant_health_first.providers" does not exist`
- [x] Same for `tenant_life_first` and `tenant_first_medfund`. The platform side survived the `CASCADE` intact: `public.providers` 60 rows, `public.provider_tenants` 180, `public.provider_insurance_lines` 60, and all 9 `*_provider_id_public_fkey` constraints (3 tables x 3 tenants) still present. The V276 orphan guard passed with 0 orphans in every tenant, so no operator-created row was destroyed
- [x] `curl 'http://localhost:8082/api/v1/tenant-stats/recent-payments' -H 'X-Tenant-ID: <id>'` returns the same rows as before Phase 5 (regression). Note the route is `/api/v1/tenant-stats/...`, not `/api/v1/tenants/<id>/...`. Live data has zero `payments` rows in every tenant, so the endpoint returns `[]` both before and after and proves nothing on its own; run against a seeded fixture (one payment to a contracted provider, one to a deliberately unlinked provider, removed afterwards) it returns **both** rows with only the unlinked provider's `providerName` blank, which is the LEFT-JOIN intent from the Phase 4 deviation
- [x] `curl 'http://localhost:8082/api/v1/tenant-stats/top-payees' -H 'X-Tenant-ID: <id>'` returns rows only for providers with a `provider_tenants` row for that tenant. Same fixture: the INNER-JOIN gate returns the contracted provider at 500.0 and excludes the unlinked one entirely
- [x] `curl 'http://localhost:8082/api/v1/providers?size=2'` returns 200 post-drop, confirming the 11 schema-qualified `@Query` strings resolve against `public.providers` now that no shadow exists to fall back on
- [x] The notification-service provider lookup resolves post-drop: its rewritten SQL returns the contracted provider's email for a linked provider and zero rows for an unlinked one

#### Manual Verification

- [ ] Angular tenant-admin dashboard finance widgets (`/admin/tenants/<id>/dashboard` "Recent payments" and "Top payees" cards) render correctly
- [ ] `verify` on `/admin/tenants/<id>/dashboard`: no console errors, cards populate
- [ ] Full round-trip: onboard a provider via `POST /api/v1/providers` (no membership yet), then manually `INSERT INTO public.provider_tenants (provider_id, tenant_id, status) VALUES (<pid>, <tid>, 'active')`, then submit a claim — 201; then delete the membership and submit again — Phase 6 will 422, but Phase 5 will still 201 because validation lands in Phase 6

**Implementation Note**: this is the destructive-migration phase. Pause for the human to backup + verify orphan-guard result before the migration runs against prod-like data.

---

## Phase 6: Membership + line CRUD APIs + validation + seeder + admin UI

### Overview

Ship the last-mile pieces that make the schema queryable and consistent with the plan's design goal: a super-admin endpoint set for linking providers to tenants and tagging providers with insurance lines, the `ClaimService.validateProviderPolicy` extension that 422s claims for un-linked providers or wrong-line providers, two new Kafka business events for future consumers, the demo-seeder wiring so `make seed-demo-micro` still finishes cleanly, and a modest admin-UI extension on `/platform/providers` for operator-driven link management.

### Changes Required

#### 1. New service: `ProviderMembershipService`

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/ProviderMembershipService.java` (new)

```java
package com.medfund.user.service;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.user.entity.ProviderTenant;
import com.medfund.user.entity.ProviderInsuranceLine;
import com.medfund.user.repository.ProviderRepository;
import com.medfund.user.repository.ProviderTenantRepository;
import com.medfund.user.repository.ProviderInsuranceLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderMembershipService {
    private static final String PLATFORM_TENANT = "platform";

    private final ProviderRepository providerRepository;
    private final ProviderTenantRepository membershipRepository;
    private final ProviderInsuranceLineRepository lineRepository;
    private final AuditPublisher auditPublisher;
    private final UserEventPublisher eventPublisher;

    // ── Tenant membership ─────────────────────────────────────
    public Flux<ProviderTenant> listMemberships(UUID providerId) {
        return membershipRepository.findByProviderId(providerId);
    }

    @Transactional
    public Mono<ProviderTenant> link(UUID providerId, UUID tenantId, String actorId, String actorEmail) {
        var pt = new ProviderTenant();
        pt.setProviderId(providerId);
        pt.setTenantId(tenantId);
        pt.setCreatedBy(actorId != null && !actorId.equals("system") ? UUID.fromString(actorId) : null);
        return providerRepository.findById(providerId)
            .switchIfEmpty(Mono.error(new com.medfund.user.exception.ProviderNotFoundException(providerId)))
            .flatMap(provider -> membershipRepository.insert(pt)
                .flatMap(saved -> publishLinkAudit(provider.getName(), providerId, tenantId, actorId, actorEmail, "LINK")
                    .then(eventPublisher.publishProviderTenantLinked(providerId.toString(), tenantId.toString()))
                    .thenReturn(saved)));
    }

    @Transactional
    public Mono<Void> unlink(UUID providerId, UUID tenantId, String actorId, String actorEmail) {
        return providerRepository.findById(providerId)
            .switchIfEmpty(Mono.error(new com.medfund.user.exception.ProviderNotFoundException(providerId)))
            .flatMap(provider -> membershipRepository.delete(providerId, tenantId)
                .flatMap(rows -> {
                    if (rows == 0) return Mono.empty();
                    return publishLinkAudit(provider.getName(), providerId, tenantId, actorId, actorEmail, "UNLINK")
                        .then(eventPublisher.publishProviderTenantUnlinked(providerId.toString(), tenantId.toString()));
                }));
    }

    // ── Insurance-line tags ───────────────────────────────────
    public Flux<ProviderInsuranceLine> listLines(UUID providerId) {
        return lineRepository.findByProviderId(providerId);
    }

    @Transactional
    public Mono<ProviderInsuranceLine> addLine(UUID providerId, String line, String actorId, String actorEmail) {
        if (!com.medfund.shared.insurance.InsuranceLine.isKnown(line)) {
            return Mono.error(new IllegalArgumentException("Unknown insurance line: " + line));
        }
        return providerRepository.findById(providerId)
            .switchIfEmpty(Mono.error(new com.medfund.user.exception.ProviderNotFoundException(providerId)))
            .flatMap(provider -> lineRepository.insert(providerId, line)
                .flatMap(saved -> publishLineAudit(provider.getName(), providerId, line, actorId, actorEmail, "ADD")
                    .thenReturn(saved)));
    }

    @Transactional
    public Mono<Void> removeLine(UUID providerId, String line, String actorId, String actorEmail) {
        return providerRepository.findById(providerId)
            .switchIfEmpty(Mono.error(new com.medfund.user.exception.ProviderNotFoundException(providerId)))
            .flatMap(provider -> lineRepository.delete(providerId, line)
                .flatMap(rows -> rows == 0 ? Mono.empty()
                    : publishLineAudit(provider.getName(), providerId, line, actorId, actorEmail, "REMOVE")));
    }

    // ── Audit helpers ─────────────────────────────────────────
    private Mono<Void> publishLinkAudit(String providerName, UUID providerId, UUID tenantId,
                                         String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
            PLATFORM_TENANT, "PROVIDER_TENANT",
            providerId + "::" + tenantId,
            providerName + " @ " + tenantId,
            action, actorId, actorEmail,
            null,
            Map.of("providerId", providerId.toString(), "tenantId", tenantId.toString()),
            new String[]{"providerId", "tenantId"},
            UUID.randomUUID().toString());
        return auditPublisher.publish(event);
    }

    private Mono<Void> publishLineAudit(String providerName, UUID providerId, String line,
                                         String actorId, String actorEmail, String action) {
        var event = AuditEvent.create(
            PLATFORM_TENANT, "PROVIDER_INSURANCE_LINE",
            providerId + "::" + line,
            providerName + " (" + line + ")",
            action, actorId, actorEmail,
            null,
            Map.of("providerId", providerId.toString(), "insuranceLine", line),
            new String[]{"providerId", "insuranceLine"},
            UUID.randomUUID().toString());
        return auditPublisher.publish(event);
    }
}
```

#### 2. Extend `UserEventPublisher` with the two new events

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java`

```java
public Mono<Void> publishProviderTenantLinked(String providerId, String tenantId) {
    return publishEvent("medfund.users.provider-tenant-linked", providerId, Map.of(
        "event",      "PROVIDER_TENANT_LINKED",
        "providerId", providerId,
        "tenantId",   tenantId));
}

public Mono<Void> publishProviderTenantUnlinked(String providerId, String tenantId) {
    return publishEvent("medfund.users.provider-tenant-unlinked", providerId, Map.of(
        "event",      "PROVIDER_TENANT_UNLINKED",
        "providerId", providerId,
        "tenantId",   tenantId));
}
```

No consumer today; the events exist for future cache invalidation.

#### 3. Extend `ProviderController` with the new endpoints

**File**: `services/java/user-service/src/main/java/com/medfund/user/controller/ProviderController.java`

Inject `ProviderMembershipService`. New endpoints follow the house Swagger style established in the same file (`@Operation(summary, description)`, `@ApiResponses` only when non-200 outcomes are worth documenting, `@AuthenticationPrincipal Jwt jwt` as the last param on mutating methods):

```java
@GetMapping("/{id}/tenants")
@Operation(summary = "List the tenants a provider is contracted with")
public Flux<ProviderTenantResponse> listMemberships(@PathVariable UUID id) {
    return membershipService.listMemberships(id).map(ProviderTenantResponse::from);
}

@PostMapping("/{id}/tenants/{tenantId}")
@ResponseStatus(HttpStatus.CREATED)
@Operation(summary = "Link a provider to a tenant",
    description = "Creates a public.provider_tenants row with default status/tier. Contract fields default to CURRENT_DATE / null; edit via a follow-up PATCH endpoint (not built in v1).")
@ApiResponses({
    @ApiResponse(responseCode = "201", description = "Membership created"),
    @ApiResponse(responseCode = "404", description = "Provider or tenant not found"),
    @ApiResponse(responseCode = "409", description = "Membership already exists")
})
public Mono<ProviderTenantResponse> link(@PathVariable UUID id, @PathVariable UUID tenantId,
                                          @AuthenticationPrincipal Jwt jwt) {
    return membershipService.link(id, tenantId, AuditActor.id(jwt), AuditActor.email(jwt))
                             .map(ProviderTenantResponse::from);
}

@DeleteMapping("/{id}/tenants/{tenantId}")
@ResponseStatus(HttpStatus.NO_CONTENT)
@Operation(summary = "Unlink a provider from a tenant")
public Mono<Void> unlink(@PathVariable UUID id, @PathVariable UUID tenantId,
                          @AuthenticationPrincipal Jwt jwt) {
    return membershipService.unlink(id, tenantId, AuditActor.id(jwt), AuditActor.email(jwt));
}

@GetMapping("/{id}/insurance-lines")
@Operation(summary = "List the insurance lines a provider is tagged for")
public Flux<String> listLines(@PathVariable UUID id) {
    return membershipService.listLines(id).map(ProviderInsuranceLine::getInsuranceLine);
}

@PostMapping("/{id}/insurance-lines/{line}")
@ResponseStatus(HttpStatus.CREATED)
@Operation(summary = "Tag a provider with an insurance line")
@ApiResponses({
    @ApiResponse(responseCode = "201", description = "Tag added"),
    @ApiResponse(responseCode = "400", description = "Unknown insurance line"),
    @ApiResponse(responseCode = "404", description = "Provider not found"),
    @ApiResponse(responseCode = "409", description = "Tag already exists")
})
public Mono<Void> addLine(@PathVariable UUID id, @PathVariable String line,
                           @AuthenticationPrincipal Jwt jwt) {
    return membershipService.addLine(id, line.toUpperCase(), AuditActor.id(jwt), AuditActor.email(jwt)).then();
}

@DeleteMapping("/{id}/insurance-lines/{line}")
@ResponseStatus(HttpStatus.NO_CONTENT)
@Operation(summary = "Remove an insurance-line tag from a provider")
public Mono<Void> removeLine(@PathVariable UUID id, @PathVariable String line,
                              @AuthenticationPrincipal Jwt jwt) {
    return membershipService.removeLine(id, line.toUpperCase(), AuditActor.id(jwt), AuditActor.email(jwt));
}
```

Two new DTOs: `ProviderTenantResponse` (mirrors entity, `LocalDate` fields become ISO strings), `AddLineRequest` (not used; the line is a path variable). Both are Java records.

#### 4. Extend `ClaimService.validateProviderPolicy`

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java`

The current `validateProviderPolicy` at lines 365-381 checks provider MODE against line only. Extend it to also cross-check the provider's membership + line tags. New checks fire AFTER the existing MODE checks (so a REQUIRED-line claim with no provider fails first with the existing message; a REQUIRED-line claim with a provider that doesn't serve the line fails with the new message).

The two new checks read `public.provider_tenants` and `public.provider_insurance_lines` via new methods on a lightweight `ProviderMembershipReader` in claims-service (avoids reaching cross-service into user-service; the SQL is trivial and colocated with the caller). New file:

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/repository/ProviderMembershipReader.java` (new)

```java
package com.medfund.claims.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProviderMembershipReader {
    private final DatabaseClient db;

    public Mono<Boolean> isMember(UUID providerId, UUID tenantId) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM public.provider_tenants
                                WHERE provider_id = :pid AND tenant_id = :tid
                                  AND status = 'active') AS present
                """)
                .bind("pid", providerId).bind("tid", tenantId)
                .map((row, meta) -> row.get("present", Boolean.class))
                .one()
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> servesLine(UUID providerId, String line) {
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM public.provider_insurance_lines
                                WHERE provider_id = :pid AND insurance_line = :line) AS present
                """)
                .bind("pid", providerId).bind("line", line.toUpperCase())
                .map((row, meta) -> row.get("present", Boolean.class))
                .one()
                .defaultIfEmpty(false);
    }
}
```

Wire it into `ClaimService` and call from `submit` after the existing MODE validation. Because `validateProviderPolicy` today is synchronous (throws), refactor to a `Mono<Void>` (or add a sibling `Mono<Void> validateProviderMembership(...)` that's chained in `.flatMap` before the claim insert). The membership check requires `TenantContext.get()` for the tenant UUID.

```java
private Mono<Void> validateProviderMembership(SubmitClaimRequest req, String line) {
    if (req.providerId() == null) return Mono.empty(); // MODE check already handled
    return Mono.deferContextual(ctx -> {
        UUID providerId = UUID.fromString(req.providerId());
        UUID tenantId   = UUID.fromString(ctx.get(TenantContext.KEY));
        return membershipReader.isMember(providerId, tenantId)
            .flatMap(isMember -> isMember
                ? Mono.empty()
                : Mono.error(new IllegalArgumentException(
                    "Provider " + providerId + " is not contracted with this tenant. "
                    + "Ask a super-admin to link the provider via POST /api/v1/providers/{id}/tenants/{tenantId}.")))
            .then(membershipReader.servesLine(providerId, line))
            .flatMap(servesLine -> servesLine
                ? Mono.empty()
                : Mono.error(new IllegalArgumentException(
                    "Provider " + providerId + " is not tagged to serve " + line + " claims. "
                    + "Ask a super-admin to add the line via POST /api/v1/providers/{id}/insurance-lines/" + line + ".")));
    });
}
```

Wire into `submit` after the existing `validateProviderPolicy` throw-based call.

`IllegalArgumentException` is already mapped to 422 by `GlobalExceptionHandler`.

#### 5. Demo-seeder: link providers + tag lines

**File**: `scripts/demo-seeder/src/demo_seeder/phases/providers.py`

After each `POST /api/v1/providers` returns 201, extract the created provider ID from the response and:

1. For each of the two seeded tenants (health-first + life-first), POST `/{id}/tenants/{tenantId}`.
2. Based on `provider_type`, POST `/{id}/insurance-lines/{HEALTH|LIFE|FUNERAL}`.

```python
async def _link_and_tag(client, user_url, provider_id, provider_type, tenant_ids):
    for tid in tenant_ids:
        r = await client.post(f"{user_url}/api/v1/providers/{provider_id}/tenants/{tid}")
        if r.status_code not in (201, 409):
            log.warning("seeder.providers.link-non-201", pid=provider_id, tid=tid, status=r.status_code)
    line = _line_for_type(provider_type)
    r = await client.post(f"{user_url}/api/v1/providers/{provider_id}/insurance-lines/{line}")
    if r.status_code not in (201, 409):
        log.warning("seeder.providers.line-non-201", pid=provider_id, line=line, status=r.status_code)

def _line_for_type(pt: str) -> str:
    return {"BROKER": "LIFE", "ADVISOR": "LIFE", "FUNERAL_PARLOUR": "FUNERAL"}.get(pt, "HEALTH")
```

Call from `_one(index)` immediately after the successful `POST /api/v1/providers` at line 80. Pass in the two seeded tenant UUIDs (fetched once in `seed_providers` via `GET /api/v1/tenants?slug=health-first,life-first`).

#### 6. Angular admin UI: "Tenants & lines" editor on `/platform/providers`

**File**: `clients/angular/src/app/pages/providers/providers.component.html`

Add two new column definitions in `providers.component.ts` (`tenants` pill list + `lines` pill list) and a per-row action `Manage tenants & lines` that opens a modal. The modal has two sections:

- **Tenants**: a multi-select (using the shared `SelectComponent`) of all tenants; checked = linked. On toggle, calls POST or DELETE `/api/v1/providers/{id}/tenants/{tid}`.
- **Lines**: a multi-select of the 8 `InsuranceLine` values; checked = tagged. On toggle, calls POST or DELETE `/api/v1/providers/{id}/insurance-lines/{line}`.

Both selects use the shared debounced pattern from `clients/angular/src/app/shared/components/select` (per [[feedback_no_raw_id_inputs]] — never raw `<input>` for IDs; the payload holds the UUID, the UI shows the name).

**File**: `clients/angular/src/app/core/services/providers.service.ts`

Add methods `listMemberships(providerId): Observable<ProviderTenant[]>`, `link(providerId, tenantId): Observable<void>`, `unlink(providerId, tenantId): Observable<void>`, and analogous for lines. Hits the six new endpoints.

**File**: `clients/angular/src/app/core/models/provider.ts` (edit)

Add `tenants?: string[]` and `insuranceLines?: string[]` optional fields on the `Provider` interface (populated by a follow-up `include=` query param or fetched on modal open; for v1 fetch on modal open to avoid re-shaping the list endpoint).

#### 7. Angular unit test + Playwright E2E

**File**: `clients/angular/src/app/pages/providers/providers.component.spec.ts` (edit)

Add cases: opening the "Manage tenants & lines" modal renders the tenant + line lists; clicking a line toggle fires the corresponding service call; the modal closes on save.

**File**: `clients/angular/e2e/providers-membership.spec.ts` (new)

Playwright: log in as super_admin, navigate to `/platform/providers`, open the modal on the first row, toggle a line, save, reopen the modal, assert the toggle is persisted. Same for a tenant link.

### Success Criteria

#### Automated Verification

- [x] Java compiles clean: `cd services/java && ./gradlew :user-service:test :claims-service:test` (see Deviations: `build` substituted with `test`); re-run green on claims-service after the 422 change
- [x] Unit tests pass: `make test-java` — pre-existing reds only, every baseline unchanged by this phase: user-service 477 / 1 (`PersistencyCohortReportServiceTest`), finance-service 1061 / 30, tenancy-service 244 / 71 (243 before V186's test), shared 271 / 1 (`CrossServiceCallHelperTest`); claims-service (381), contributions-service (449) and rules-engine (154) fully green
- [x] Integration tests pass: `make test-integration` — user-service 108 / 0 (92 + the 16 new `ProviderMembershipServiceIT` cases) and claims-service 74 / 0 (66 + the 8 new `ProviderMembershipReaderIT` cases) both green; contributions-service 51 / 0; tenancy-service 97 / 78 and finance-service 131 / 29 at their recorded pre-existing baselines
- [x] Angular unit tests pass: `make test-angular` — 799 pass / 3 fail, all three pre-existing in files untouched by this branch, plus a pre-existing repo-wide coverage gate (see Deviations)
- [x] Playwright E2E green including the new `providers-membership.spec.ts`: `make test-e2e` — the new spec is 4/4 green; 46 other failures are the branch's pre-existing baseline, confirmed by reproducing 6 of them with the shared `data-table` edits stashed (see Deviations)
- [x] Swagger renders the 6 new endpoints at `http://localhost:8082/swagger-ui` — all six present in `/v3/api-docs` with their summaries
- [x] Curl-level: `POST /api/v1/providers/{id}/tenants/{tid}` returns 201; a second call returns 409 (`Provider Miller-Carter Broker is already linked to Health First Medical`). An unknown tenant id returns 404, pinning the `PlatformTenantRepository` lookup added in Deviations
- [x] Curl-level: `POST /api/v1/providers/{id}/insurance-lines/WRONG` returns 400 with "Unknown insurance line: WRONG". The `MOTOR` alias round-trips: POST returns 201 and the tag reads back as `VEHICLE`
- [x] Curl-level: submitting a HEALTH claim for a LIFE-only provider returns 422 with the actionable line-tag error message (see Deviations: needed a dedicated exception; `IllegalArgumentException` maps to 400 in claims-service, not 422)
- [x] Curl-level: submitting a claim to a provider with no `provider_tenants` row returns 422 with the actionable link error message. The happy path (linked + HEALTH-tagged) returns 201; the verification claim and the temporary tag were removed afterwards and the junctions are back at 180 / 60
- [ ] `make seed-demo-reset && make seed-demo-micro && make seed-demo-verify TIER=micro` reports `claims_submitted > 0` and no membership 422s in the seeder log — **blocked before the provider phase**. `seed-demo-reset` now completes (it could not before V186, see Deviations). `seed-demo-micro` creates both tenants and then dies in phase 1 on `POST /api/v1/exchange-rates` returning 500 / `permission denied for table exchange_rates`, which is unrelated to provider membership and needs a call from whoever owns that surface (see Deviations)
- [ ] `verify` skill on `/platform/providers` after opening a "Manage tenants & lines" modal: no console errors, toggles persist, both pills reflect the change — **not run**: the Chrome extension is not connected in this session. The server half is verified (`GET /api/v1/providers` carries `tenantIds` + `insuranceLines` per row against live data) and the modal round-trip is covered by the 4 green `providers-membership.spec.ts` cases

#### Manual Verification

- [ ] Log in as super_admin at `http://localhost:5100`, navigate to `/platform/providers`, open the modal, toggle a tenant + a line, save, reopen; state persists across reload
- [ ] Real end-to-end: seed micro; log in as tenant-admin for `health-first`; submit a claim through `/admin/claims/submit` for a provider that IS tagged HEALTH and IS linked to `health-first`; claim goes 201 and lands in the claims queue
- [ ] Try the same for a provider NOT linked to `health-first`; UI shows a friendly 422 message from `ClaimService.validateProviderMembership`

**Implementation Note**: this is the final phase. After it passes, the plan's End State is met.

---

## Testing Strategy

### Unit tests (per phase, `services/java/**/src/test/java/`)

- Phase 1: `UserEventPublisherTest` reverts to 2-arg payload assertion.
- Phase 2: `ProviderTenantRepositoryIT` and `ProviderInsuranceLineRepositoryIT` cover CRUD + `CHECK` constraint + composite-PK uniqueness.
- Phase 3: `ClaimFactBuilderTest` gains `inNetwork` + `networkTier` assertions.
- Phase 4: `PaymentRunFactBuilderTest` string-literal assertion updated if the rewrite added `provider_tenants` to that specific SQL.
- Phase 5: `UserEventPublisherTest` re-verified against reverted 2-arg payload; `ProviderRepositoryTest` if any is broken by the schema-qualification.
- Phase 6: `ProviderMembershipServiceTest` with mocked repos + `AuditPublisher` — cover link + unlink + line add/remove + unknown-line rejection + provider-not-found. `ClaimServiceTest` gains `validateProviderMembership` cases (not-a-member 422, wrong-line 422, member + right line 201).

### Integration tests (Testcontainers, `services/java/**/src/test/java/**/*IT.java`)

- Phase 2: `ProviderTenantRepositoryIT`, `ProviderInsuranceLineRepositoryIT`, plus new assertions in `TenantMigrationFlywayIT` that V185's tables + grants land.
- Phase 3: `AbstractClaimsReportIT` fixture updated; new `ClaimsReportProviderJoinIT` covering the 3 INNER-JOIN report queries + a membership-exclusion assertion.
- Phase 4: 5 new `*ProviderJoinIT.java` files under `finance-service/src/test/java/com/medfund/finance/integration/` covering the previously untested query repos.
- Phase 5: `TenantMigrationFlywayIT` V276 assertions (table absent + FK retargeted).
- Phase 6: `ProviderMembershipControllerIT` — full round-trip via WebTestClient: onboard, link, submit claim (201), unlink, submit claim (422). `ClaimSubmitMembershipValidationIT` — direct claim-service IT for the 422 paths.
- Reference the auto-memory pitfalls: [[infra_testcontainers_pitfalls]] (Testcontainers 1.21.4 BOM override, `flyway-database-postgresql`, `ReactiveJwtDecoder` stub).

### E2E tests (Playwright, `clients/angular/e2e/`)

- Phase 6: new `providers-membership.spec.ts` covering the admin modal round-trip.

### Manual test flow

1. `make infra && make keycloak-setup`
2. `make tenancy && make user && make claims && make contributions && make finance` (never `./gradlew --stop`, per [[feedback_never_gradle_stop]])
3. `SEED_MODE_ENABLED=true make seed-demo-reset && SEED_MODE_ENABLED=true make seed-demo-micro`
4. Verify `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM public.provider_tenants;"` returns a nonzero number (~60 × 2 = 120 for micro).
5. Log in as super_admin at `http://localhost:5100`; navigate to `/platform/providers`; open the "Tenants & lines" modal on any row; verify pills.
6. Log in as tenant-admin for `health-first`; navigate to `/admin/claims/submit`; pick a HEALTH-tagged provider; submit; verify 201.
7. Repeat with a LIFE-only provider; verify 422 with the actionable message.
8. Verify `make seed-demo-verify TIER=micro` reports `claims_submitted > 0`.

## Performance Considerations

- **Membership `EXISTS` subquery**: every rewritten SQL adds one nested `EXISTS (SELECT 1 FROM public.provider_tenants ...)`. Postgres's planner routinely converts these to semi-joins; the `ix_provider_tenants_tenant` index at V185 ensures the tenant-filter side is index-scan. Expected overhead: ~1-2ms per query on the 60-provider fixture; negligible on 10k+ providers.
- **`ClaimFactBuilder.fetchProvider`**: adds a `LEFT JOIN` to `public.provider_tenants` on the primary path. Called once per claim adjudication; negligible.
- **`ClaimService.validateProviderMembership`**: two `EXISTS` round-trips per claim submit (membership + line). Both hit indexed PK columns. Add up to ~5ms per submit at seeder scale; not on any hot path.
- **`ProviderRepository` schema qualification**: no semantic change; Postgres planner treats `public.providers` and unqualified `providers` (search_path public) identically.
- **Backfill migration cost (V185)**: `INSERT ... SELECT ... FROM public.tenants LOOP` iterates 3 tenants × 60 providers = 180 rows. Runs once during deploy; ~50ms.
- **V276 destructive migration**: DROP TABLE on a 60-row table is instant. Guard SELECT reads at most 5 rows.
- **Angular admin modal**: two GET calls when opened (tenants + lines), one POST/DELETE per toggle. No new bundle code beyond the modal component; O(10KB) gzipped.

## Migration Notes

- **V185 is a new public migration** (new file, never edit V105 or V182). Per [[feedback_never_edit_applied_migrations]], corrections require a higher-numbered file. V185 is designed idempotent (`CREATE TABLE IF NOT EXISTS`, `INSERT ... ON CONFLICT DO NOTHING`, `CREATE INDEX IF NOT EXISTS`) so a re-run against an already-applied database is safe.
- **V275 + V276 are new tenant migrations**. Each runs once per tenant schema through `SchemaProvisioningService`. Both use `DO $$ IF NOT EXISTS $$` guards so they're safe to re-run.
- **Migration ordering**: V185 (public) must land before V275 (tenant) because V275's FK retarget needs `public.providers` to be readable by the tenant role. `public.providers` was already in the readable list; V185 adds `public.provider_tenants` and `public.provider_insurance_lines`. V275 doesn't reference the two new tables — it only alters existing tenant tables — so ordering with V185 is a soft requirement only.
- **Ordering within one deploy**: `SchemaProvisioningService` applies migrations in classpath order via Flyway, which orders by version number. V185 < V275 < V276 by number, so ordering is enforced.
- **Backfill idempotency**: V185's `DO $$` backfill loop uses `ON CONFLICT (provider_id, tenant_id) DO NOTHING`, so re-running against a partially-migrated environment is safe.
- **Auto-memory guardrails apply**: [[bug_tenant_flyway_outoforder]] (if V275 is applied before V185 by mistake, `flyway repair` then `migrate -outOfOrder=true`; but this is unlikely because the runner reads both dirs in one pass). [[bug_public_flyway_history_load_bearing]] (do not manually delete V185's row from `public.flyway_schema_history`).
- **Rollback strategy**: see the Rollout & Rollback section.

## Rollout & Rollback

### Rollout order

1. **Phase 1 PR** (doc-only + Option A revert). Merge and deploy user-service + claims-service. Zero runtime risk.
2. **Phase 2 PR** (schema + backfill). Migration lands and runs. Deploy tenancy-service to get the migrations applied at boot. Verify `public.provider_tenants` populated. No service behavior changes yet.
3. **Phase 3 PR** (claims-service reads). Deploy claims-service. All existing reads work; new INNER-JOIN filter drops any provider that doesn't have a membership row (backfill ensured every existing provider has one, so no rows drop unexpectedly).
4. **Phase 4 PR** (finance-service reads). Deploy finance-service. Same shape.
5. **Phase 5 PR** (user-service + destructive drop). Deploy tenancy-service to run V276; deploy user-service with the schema-qualified queries. **This is the point of no return** — after V276, no service on pre-Phase-3 code compiles against the schema.
6. **Phase 6 PR** (endpoints + validation + seeder + admin UI). Deploy user-service + claims-service + Angular. Seeder updated in the same PR.

### Rollback

- **Phase 1**: revert the PR. Docs return to their stale-drift state; Option A files re-emerge in the working tree (but not deployed).
- **Phase 2**: revert the migration is destructive (`DROP TABLE public.provider_tenants`) which loses the manually-linked memberships. Prefer rolling forward. Emergency rollback: manually drop the two new tables via psql and revert the migration file entry from `public.flyway_schema_history`. Per [[bug_public_flyway_history_load_bearing]], this is the one row that's genuinely safe to delete because it references a table that no longer exists.
- **Phase 3, 4**: revert code. Reads go back to `tenant.providers` (which still exists at this point). No data loss.
- **Phase 5**: this is the point of no return. Once V276 drops `tenant.providers`, rolling back requires re-provisioning every tenant schema from V001, which loses all tenant data. Do not roll back Phase 5. Instead, roll forward: fix the bug in a Phase 5b patch.
- **Phase 6**: revert code. Endpoints disappear; validation reverts to MODE-only. Seeder update stays (it just adds POSTs that now 404 on rollback; not fatal). Admin UI extension disappears; the underlying data (membership rows created via the admin UI) stays.

### Deployment ordering constraints

- Kafka producer (user-service Phase 6) before any consumer (there are none in this plan; deferred).
- Migration runner (tenancy-service) before every reader (claims-service, finance-service, user-service).

## References

### Research

- [thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md](../research/2026-09-20-platform-scoped-providers-with-tenant-membership.md) — primary source
- [thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md](../research/2026-09-15-seeder-phase-6-500s-root-cause.md) — Option A/B framing this supersedes
- [thoughts/shared/research/2026-09-15-tenant-provisioning-rbac-model.md](../research/2026-09-15-tenant-provisioning-rbac-model.md) — provider identity discussion

### Superseded

- [thoughts/shared/plans/2026-09-15-provider-fanout-and-age-group-effective-from.md](2026-09-15-provider-fanout-and-age-group-effective-from.md) — Option A fan-out implementation, Phase 1 deleted by this plan; age-group Phase 2 is orthogonal and stays

### Architecture docs (all edited by Phase 1)

- `.claude/CLAUDE.md` §Critical Rule 2
- `.claude/multi-tenancy.md` §Provider Portal
- `.claude/architecture.md` schema tree
- `.claude/portals.md` §Provider Portal
- `.claude/adjudication.md` (light touch: note that `ProviderFact.inNetwork` + `networkTier` now populate)

### Similar implementations to model after

- Junction table (composite PK, `CHECK` on enum): `services/java/tenancy-service/src/main/resources/db/migration/tenant/V085__treaty_applicable_line.sql`
- Junction-table entity + hand-rolled repo: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/TreatyParticipant.java` + `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/repository/TreatyParticipantRepository.java`
- Cross-schema FK: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V268__report_job_schedule_columns.sql:33-41`
- `provision_tenant_role` extension: `services/java/tenancy-service/src/main/resources/db/migration/public/V182__grant_report_config_tables_to_tenant_roles.sql:25-95`
- Controller Swagger house style: `services/java/user-service/src/main/java/com/medfund/user/controller/ProviderController.java`

### Load-bearing files edited by this plan

- `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java` (Phase 1 revert + Phase 6 add 2 events)
- `services/java/user-service/src/main/java/com/medfund/user/service/ProviderService.java:128-132` (Phase 1 revert)
- `services/java/user-service/src/main/java/com/medfund/user/controller/ProviderController.java` (Phase 6 add 6 endpoints)
- `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderRepository.java` (Phase 5 schema-qualify 11 @Query strings)
- `services/java/user-service/src/main/java/com/medfund/user/controller/TenantStatsController.java:895,933` (Phase 5 rewrite)
- `services/java/claims-service/src/main/java/com/medfund/claims/repository/ClaimsReportQueryRepository.java` (Phase 3, 8 sites)
- `services/java/claims-service/src/main/java/com/medfund/claims/repository/ClaimQueryRepository.java` (Phase 3, 2 sites)
- `services/java/claims-service/src/main/java/com/medfund/claims/repository/PreAuthorizationQueryRepository.java` (Phase 3, 2 sites)
- `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimFactBuilder.java:202-213` (Phase 3, populate ProviderFact)
- `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java` (Phase 6 add membership + line validation)
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/CreditorQueryRepository.java:120,124,131` (Phase 4 rewrite + practice_number fix)
- Every other finance-service `*QueryRepository.java` per the inventory above
- `.claude/CLAUDE.md`, `.claude/multi-tenancy.md`, `.claude/architecture.md`, `.claude/portals.md` (Phase 1)
- `clients/angular/src/app/pages/providers/providers.component.{html,ts,scss}` (Phase 6)
- `clients/angular/src/app/core/services/providers.service.ts` (Phase 6)
- `scripts/demo-seeder/src/demo_seeder/phases/providers.py` (Phase 6)

### Load-bearing files created by this plan

- `services/java/tenancy-service/src/main/resources/db/migration/public/V185__platform_provider_membership.sql`
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V275__provider_fk_retarget.sql`
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V276__drop_providers.sql`
- `services/java/user-service/src/main/java/com/medfund/user/entity/ProviderTenant.java`
- `services/java/user-service/src/main/java/com/medfund/user/entity/ProviderInsuranceLine.java`
- `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderTenantRepository.java`
- `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderInsuranceLineRepository.java`
- `services/java/user-service/src/main/java/com/medfund/user/service/ProviderMembershipService.java`
- `services/java/user-service/src/main/java/com/medfund/user/dto/ProviderTenantResponse.java`
- `services/java/claims-service/src/main/java/com/medfund/claims/repository/ProviderMembershipReader.java`
- `services/java/user-service/src/test/java/com/medfund/user/repository/ProviderTenantRepositoryIT.java`
- `services/java/user-service/src/test/java/com/medfund/user/repository/ProviderInsuranceLineRepositoryIT.java`
- `services/java/claims-service/src/test/java/com/medfund/claims/integration/ClaimsReportProviderJoinIT.java`
- `services/java/claims-service/src/test/java/com/medfund/claims/integration/ClaimSubmitMembershipValidationIT.java`
- `services/java/finance-service/src/test/java/com/medfund/finance/integration/{Creditor,PaymentAdvice,ProviderBalance,Note,AdvancePayment}ReportProviderJoinIT.java` (5 files)
- `services/java/user-service/src/test/java/com/medfund/user/controller/ProviderMembershipControllerIT.java`
- `clients/angular/e2e/providers-membership.spec.ts`

### Load-bearing files deleted by this plan

- `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderOnboardedConsumer.java`
- `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderBackfillRunner.java`
- `services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderOnboardedConsumerTest.java`
