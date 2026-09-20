---
date: 2026-09-20T15:12:00+02:00
researcher: methuseli
git_commit: 109cdc9b93c57a5b58dbb7afe09879a534a20f4c
branch: rename-adjustments-to-notes
repository: medfund
topic: "Retire the tenant.providers shadow: platform-level provider registry with per-tenant membership + line tagging + provider self-service"
tags: [research, providers, multi-tenancy, keycloak, portals, drools-facts, junction-tables, migration]
status: complete
last_updated: 2026-09-20
last_updated_by: methuseli
---

# Research: Platform-scoped providers with tenant membership, line tagging, and provider self-service

**Date**: 2026-09-20T15:12:00+02:00 · **Researcher**: methuseli · **Commit**: 109cdc9b · **Branch**: rename-adjustments-to-notes

## Research Question

We want providers to live at the platform level (not fanned-out shadows in every tenant schema), tagged with insurance line(s), and related to many tenants via a membership relationship. Later, providers should log in and manage their own profiles. What has to change across the InsureFlow monorepo to make that real, and where does existing code / spec / infrastructure already support it?

## Summary

**The platform-level design the user is proposing is already the documented intent in `public/V105__providers.sql:1-3` and is already implemented at the gateway + Java web-filter + connection-factory layers.** What is broken is the *tenant-local* half: `tenant/V001__baseline.sql:62-78` still creates a `providers` table in every tenant schema, 11 tenant tables carry a `provider_id` column (3 with hard FKs, 8 soft), and 18+ query sites in claims-service and finance-service read from the tenant-local table. The `ProviderOnboardedConsumer` + `ProviderBackfillRunner` we just shipped (uncommitted) exist purely to keep the obsolete tenant table populated — the redesign supersedes and removes them.

**The user's proposal (platform table + line tag + tenant membership join) is a third path that prior research did not consider.** The earlier Option A / Option B analysis in `thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md` rejected Option B (drop the shadow, retarget FKs) on Critical Rule 2 grounds. Adding a `public.provider_tenants` membership join reintroduces tenant scoping at the query level (`WHERE EXISTS ... provider_tenants pt WHERE pt.tenant_id = :tenantId`), which satisfies Rule 2's *intent* (each tenant sees only providers it has a relationship with) without per-tenant shadow rows. This also becomes the natural home for the per-tenant provider attributes (`network_tier`, `in_network`, contract terms) that `.claude/adjudication.md`'s `ProviderFact` already expects but `ClaimFactBuilder.fetchProvider` never populates.

**Provider self-service is entirely unbuilt but partially spec'd.** `.claude/portals.md:201-240` fully specifies a provider portal (`/providers/profile`, `/providers/staff`, tenant switcher, submit-claim, my-claims, pre-auth, payments). Zero Angular routes, zero Flutter screens, zero `provider`/`provider_admin` role wiring exists. The `Provider.keycloakUserId` column (`services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:51-52`) and `ProviderService.onboard` (`ProviderService.java:113-124`, creates a Keycloak user with role `"provider"` in realm `medfund-platform`) are stubs waiting for consumers. The gateway allowlist and Java `TenantWebFilter` already treat `/api/v1/providers` as tenant-less, so a provider login lands in a working "no tenant context" pipeline today.

**The architecture docs are in three-way contradiction with each other and with the code.** This is the single most important thing to fix — the plan must rewrite `.claude/multi-tenancy.md:242` and reconcile `.claude/architecture.md:588` vs `:599`, or a future contributor will re-introduce the drift.

## Findings

### Java data model — the target already exists, alongside the drift

**Platform-level Provider entity** (`services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:18`):
- `@Table(schema="public", value="providers")` — writes go to `public.providers` unconditionally.
- Columns: `id, name, provider_type, registration_number, specialty, email, phone, city, address, banking_details, keycloak_user_id, status, network_tier, created_at, updated_at, created_by, updated_by`.
- Every mutation calls `publishAudit` (`ProviderService.java:240`) tagged `tenant="platform"` (the `PLATFORM_TENANT` sentinel at `ProviderService.java:37`).
- Controller is documented "platform-wide registry" (`ProviderController.java:29`).

**Legacy tenant-local `providers` table** (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:62-78`):
- Predates V105 (public.providers created 2026-Q1); never removed after V105 became the source of truth.
- Different columns: has `ahfoz_number` (no public equivalent), keeps `practice_number` (public renamed to `registration_number` via V106), `banking_details` is `JSONB` vs public's `TEXT`, lacks `provider_type` and `city`. `network_tier` was added independently via V213 (tenant) and V135 (public) — parallel migrations.
- Header of `public/V105__providers.sql:1-3` states intent verbatim: *"Platform-wide provider registry. Providers are not tenant-scoped — they are onboarded once and shared across all tenants."*

**FK / soft-reference blast radius (11 tenant tables)**:

Hard `REFERENCES providers(id)`:
1. `claims.provider_id` — `tenant/V001__baseline.sql:129` (NOT NULL, relaxed by V055)
2. `payments.provider_id` — `tenant/V001__baseline.sql:217` (nullable)
3. `quotations.provider_id` — `tenant/V003__quotations.sql:6` (NOT NULL)

Soft (`UUID` column, no FK constraint, app-enforced):
4. `pre_authorizations.provider_id` — `V014__claims_schema.sql:32`
5. `payment_run_items.provider_id` — `V016__finance_schema.sql:35`
6. `provider_balances.provider_id` — `V016:52` (unique on `provider_id + currency`)
7. `notes.provider_id` — `V016:70` (renamed from `adjustments` in V074; CHECK enforces `provider_id OR member_id`)
8. `payment_advices.provider_id` — `V016:119`
9. `advance_payments.provider_id` — `V016:138`
10. `provider_balance_snapshot.provider_id` — `V080__balance_snapshots.sql:21`
11. `suspicious_transaction_alert.provider_id` — `V267:25` (nullable)

**Live FK usage today**: zero. Every tenant's `claims.provider_id` and `payments.provider_id` count is 0 (verified against the running database). Retargeting the FK now is referentially free.

**Read-site inventory (18+ queries)**:
- claims-service: `ClaimFactBuilder.java:206` (already reads `public.providers` — precedent), `ClaimsReportQueryRepository.java:145,336,397,513,599,660,809,927` (7 unqualified JOINs), `ClaimQueryRepository.java:73,95`, `PreAuthorizationQueryRepository.java:65,87`, `ProviderOnboardedConsumer.java:107-121`, `ProviderBackfillRunner.java:84-98`.
- finance-service: `PaymentQueryRepository.java:53,70`, `NoteQueryRepository.java:64,115`, `CreditorQueryRepository.java:131`, `ProviderBalanceQueryRepository.java:48,64`, `AdvancePaymentQueryRepository.java:44,61`, `PaymentRunWorkbookQueryRepository.java:49`, `PaymentAdviceQueryRepository.java:53,84,105`, `PaymentAdviceService.java:244`, `BalanceHistoryService.java:82`, `PaymentRunFactBuilder.java:81` (also reads `public.providers` — second precedent).
- user-service: `TenantStatsController.java:895,933` (dynamic cross-schema SQL `"\"" + schema + "\".providers"` — reaches directly into a specific tenant's providers table for stats).

**Existing cross-schema inconsistency** (unrelated to this redesign but must be reconciled by it): `ClaimFactBuilder.java:206` and `PaymentRunFactBuilder.java:81` already query `public.providers`; every other read path uses the unqualified (tenant-local) name. The redesign converges all reads onto `public.providers` (+ `provider_tenants` filter).

### Insurance-line semantics live in claims-service only

`services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java:71-90`:
```java
private static final Map<String, ProviderMode> PROVIDER_MODE_BY_LINE = Map.of(
        "HEALTH",     REQUIRED,   "GROUP",      REQUIRED,
        "TRAVEL",     REQUIRED,   "VEHICLE",    REQUIRED,
        "PROPERTY",   REQUIRED,   "FUNERAL",    OPTIONAL,
        "LIFE",       FORBIDDEN,  "DISABILITY", FORBIDDEN
);
```

`validateProviderPolicy` (`ClaimService.java:365-381`) throws when a FORBIDDEN-line claim carries a provider or a REQUIRED-line claim lacks one. **No line tag exists on the Provider entity today** — the check is claim-side only. Adding `insurance_line` (or `insurance_lines TEXT[]`) to `public.providers` is net-new schema. When present, `validateProviderPolicy` can add a cross-check: the claim's scheme line must be in the provider's declared lines.

### Provider identity — stubs exist, no consumers

- `Provider.keycloakUserId` is a single `VARCHAR(255)` column (`Provider.java:51-52`, `public/V105__providers.sql:20`).
- `ProviderService.onboard` (`ProviderService.java:113-124`) creates a Keycloak user in realm **`medfund-platform`** with role `"provider"`.
- `ProviderRepository.findByKeycloakUserId` exists (`ProviderRepository.java:19-20`) but has **no caller in non-test code** — reserved for a `/me` endpoint that was never built.
- No `ProviderInvite` class exists (grep confirmed empty) — providers don't have an invite flow like `Member`/`GroupLiaison` do.
- The Java `TenantWebFilter` already whitelists `/api/v1/providers` as a platform path (`services/java/shared/src/main/java/com/medfund/shared/tenant/TenantWebFilter.java:58`).
- `TenantAwareConnectionFactory.java:73-95` correctly routes no-tenant-context requests to `search_path=public` with no `SET ROLE` restriction — the "provider logs in without a tenant" pipeline works today.

### Per-tenant metadata that is faked today by full-row duplication

The tenant-local `providers` table has been quietly serving as per-tenant provider metadata:
- `network_tier` (V213) is technically a global attribute on `public.providers` (V135) but the tenant column allows per-tenant override that no code exercises today.
- `ProviderFact.inNetwork`, `ProviderFact.networkTier` (in `rules-engine/src/main/java/com/medfund/rules/facts/ProviderFact.java:12-16`) are declared with comments saying they represent "contracted with the fund" state — but `ClaimFactBuilder.fetchProvider` (`ClaimFactBuilder.java:202-213`) only ever sets `providerId`, leaving both fields perpetually null. **A `public.provider_tenants(provider_id, tenant_id, network_tier, in_network, ...)` join table is where these unfulfilled fields would finally get their data.**
- Stub permission `providers:manage_contracts` at `Permissions.java:162` (catalogued `PermissionCatalogue.java:136` "Configure tariff agreements and payment terms with providers") has **zero consumer code** — the join table is where this feature would land.

### Kafka events

- Producer: `UserEventPublisher.publishProviderOnboarded` (`services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java:168-176`) — payload `{event, providerId, name, status, networkTier}` (the last two are additions from the uncommitted Phase 1 work).
- Topic: `medfund.users.provider-onboarded`.
- Only consumer: `ProviderOnboardedConsumer` (uncommitted, in claims-service) — will be deleted by the redesign.
- `ProviderService.update / suspend / activate / verifyAhfoz / updateNetworkTier` (`ProviderService.java:150-234`) publish audit events only, **no Kafka business events**. The redesign needs `PROVIDER_UPDATED`, `PROVIDER_SUSPENDED`, `PROVIDER_TENANT_LINKED`, `PROVIDER_TENANT_UNLINKED` if any consumer (report caches, notification service) needs to react.

### Angular / Flutter provider surfaces

Angular: **only admin-facing.** One route `/platform/providers` → `ProvidersComponent` (`clients/angular/src/app/app.routes.ts:44-48`), guarded by `roleGuard(['super_admin'])` (`app.routes.ts:11`). Verify/Suspend/Activate actions (`providers.component.ts:63-80`). Legacy redirect `providers → /platform/providers` (`app.routes.ts:147`). Zero routes under `/providers/*` for a logged-in provider.

- `PROVIDER_PORTAL` appears as an unused feature-flag literal at `feature-flag.service.ts:15` — reserved but never gated on.
- No `PROVIDER` role constant in `auth.guard.ts` or `keycloak.init.ts`; roles are free-form strings checked against `realm_access.roles`.
- `rootRedirectGuard` (`auth.guard.ts:57-81`) only special-cases `super_admin`; a `provider` role would fall through to `/tenant/dashboard`, which is wrong.

Flutter: **unbuilt for providers.** `clients/flutter/lib/screens/` contains only member screens (benefits, chat, claims, dashboard, home, login, payments, profile). Zero provider files. The "provider companion" claim in root `CLAUDE.md` is aspirational.

### Gateway routing

`services/go/gateway/internal/routes/routes.go:70-71` proxies `/api/v1/providers` and `/api/v1/providers/*` to user-service unconditionally.

`services/go/gateway/internal/middleware/tenant.go:15-34` lists `/api/v1/providers` in `platformPaths` — skipped by `TenantResolver` (`tenant.go:49-53`) with explicit comment: *"Providers live in a single platform-wide registry — the same doctor/clinic can serve members of multiple tenants — so they are never tenant-scoped."* **The gateway is already the model the redesign wants.**

### Tenant provisioning

`TenantService.create()` (`services/java/tenancy-service/.../service/TenantService.java:114-170`) → `SchemaProvisioningService.provisionSchema` runs Flyway `tenant/` migrations including `V001__baseline.sql:62-78`. Removing tenant-local providers is a new tenant migration that drops the table and repoints `claims.provider_id` (and the 10 soft references) to `public.providers` via cross-schema FK. No per-tenant provider data is seeded at provisioning time today, so the provisioning path itself is untouched.

### Junction-table pattern to model on

`treaty_participant` (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V084__treaty_participant.sql`) — composite PK `(treaty_id, reinsurer_id)`, `ON DELETE CASCADE / RESTRICT` per side. Java entity `services/java/finance-service/.../reinsurance/entity/TreatyParticipant.java` — plain `@Getter @Setter @Table`, no `@Id`. `TreatyParticipantRepository.java` — hand-rolled `DatabaseClient` with raw SQL, explicit `mapRow(Row, RowMetadata)` mapper, **not** a `ReactiveCrudRepository`. Class javadoc: *"R2DBC's `ReactiveCrudRepository` does not support composite keys out of the box."* Follow this shape exactly for `public.provider_tenants`.

### Live data on the running database

Verified against `tenant_first_medfund`, `tenant_health_first`, `tenant_life_first`:
- `public.providers` count: **60**.
- All 60 present in all three tenant schemas via the just-shipped backfill runner.
- `tenant_first_medfund.providers` has **5 orphan rows** with no `public.providers` counterpart: `Porter, Wilkerson and Day Funeral Parlour`, `Miller-Carter Broker`, `Martinez-Walker Broker`, `Byrd-Orr Medical`, `Nolan and Sons Advisor`. These must be handled by the migration (promote to `public.providers` with a `first_medfund` membership, or drop after operator confirmation).
- Live FK references: **zero**. No `claims.provider_id` or `payments.provider_id` is populated in any tenant. Retargeting the FK carries no referential-data risk.

## Cross-service flow (target design)

```
1. Admin POST /api/v1/providers  →  Gateway (skips tenant middleware, tenant.go:15-34)
                                 →  user-service ProviderController
                                 →  INSERT public.providers  (with insurance_lines[])
                                 →  Kafka medfund.users.provider-onboarded
                                     └→ (optional) notification-service, report caches
                                 →  AuditEvent (tenant=platform)

2. Admin POST /api/v1/providers/{id}/tenants/{tenantId}  (new endpoint)
                                 →  user-service (or new provider-service)
                                 →  INSERT public.provider_tenants (provider_id, tenant_id,
                                       network_tier, in_network, contract_effective_from, ...)
                                 →  Kafka medfund.users.provider-tenant-linked
                                 →  AuditEvent

3. Tenant claim submit  (X-Tenant-ID header)
                                 →  Gateway resolves tenant → user JWT
                                 →  claims-service ClaimService.submit
                                 →  validateProviderPolicy:
                                     - line REQUIRED ↔ providerId present
                                     - EXISTS in public.provider_tenants
                                       (provider_id=req.providerId AND tenant_id=CURRENT_TENANT)
                                     - insurance_line in provider.insurance_lines
                                 →  INSERT claims (provider_id FK → public.providers)

4. Provider login  (no tenant header, no X-Tenant-ID)
                                 →  Gateway (platform path, no tenant resolution)
                                 →  Keycloak medfund-platform realm, role=provider
                                 →  user-service /api/v1/providers/me  (new)
                                     └→ SELECT public.providers WHERE keycloak_user_id=:sub
                                     └→ SELECT public.provider_tenants WHERE provider_id=:id
                                        (returns tenant switcher list)
                                 →  Angular /providers/dashboard  (new)
```

## Architecture doc vs. code — a three-way contradiction

The docs describe **three different models** for provider ownership, none matching each other and none matching the code:

| Source | Model | Location |
|---|---|---|
| `.claude/multi-tenancy.md:225,242` | *"A provider exists in multiple tenant schemas. The provider's profile is replicated across tenant schemas."* — full per-tenant replication | Multi-tenancy doc §Provider Portal |
| `.claude/portals.md:207` | *"Each provider has accounts in every tenant realm they serve. A provider user can belong to multiple tenant realms."* — one platform provider + multiple Keycloak realm accounts | Portals doc §Provider |
| `.claude/architecture.md:588` | *"Platform-level provider registry"* under public schema tree | Architecture doc |
| `.claude/architecture.md:599` | `providers` listed under `tenant_{uuid}` schema tree, 11 lines after :588 | Same file, contradicts itself |
| `public/V105__providers.sql:1-3` | *"Platform-wide provider registry. Providers are not tenant-scoped."* | Migration header |
| Actual code | Writes to `public.providers`; reads from tenant-local `providers` (via unqualified name + tenant search_path) | 18+ query sites |

**The redesign must rewrite `multi-tenancy.md` and reconcile `architecture.md` in the same PR that lands the schema change.** Leaving stale doc guidance is what got us into this drift — three months later, a subsequent contributor reintroducing per-tenant provider replication because `multi-tenancy.md:242` still says to.

Prior research (`thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md:163`) already flagged this doc gap: *"The doc needs a section on 'shadow tables' or the code needs to converge to one of the two shapes."* Never resolved.

## Design forks the plan must resolve

Each is a genuine fork with tradeoffs — a plan needs a decision on each before code.

1. **Line tagging shape.** `insurance_line VARCHAR(20)` (one line per provider) vs. `insurance_lines TEXT[]` or a `provider_insurance_lines` join (many). Real-world providers are almost always single-line (a hospital is HEALTH, a workshop is VEHICLE, a funeral parlour is FUNERAL), but mixed practices exist (a hospital that also does occupational-health assessments). Multi is safer, single is simpler; check schema conventions elsewhere (do we use TEXT[] or junction tables for other "multiple codes" attributes?).

2. **Membership table location.** `public.provider_tenants(provider_id UUID, tenant_id UUID, ...)` in `public` schema (both FKs to platform tables), composite PK, following the `treaty_participant` pattern. Not tenant-scoped — a tenant reading its own membership is `WHERE tenant_id = :ctx.tenantId`.

3. **Per-tenant metadata on the join.** Which columns land on `provider_tenants`?
   - `network_tier` (per-tenant override of `public.providers.network_tier`, which becomes the default)
   - `in_network BOOLEAN` (the currently-null field in `ProviderFact`)
   - `contract_status VARCHAR(20)` (`active`, `pending`, `terminated`)
   - `contract_effective_from DATE`, `contract_effective_to DATE`
   - `credit_limit NUMERIC(15,2)`, `credit_limit_currency CHAR(3)`
   - `tariff_agreement_id UUID` (reference to a per-tenant tariff row, future)
   - Audit fields: `created_at`, `created_by`, `updated_at`, `updated_by`.
   Question: which are v1 vs. deferred? The `providers:manage_contracts` permission (`Permissions.java:162`) suggests all of them eventually — but v1 could ship with just `network_tier + in_network + status` and defer contract fields to a `provider_contracts` sub-table.

4. **Provider identity model.** Two sub-forks, resolvable together:
   - **Option A: single Keycloak identity in `medfund-platform` realm, per-tenant authorization claims.** Matches the existing schema (one `keycloak_user_id` column), matches the gateway/webfilter platform-path handling, matches how the code already works. `portals.md:207` disagrees ("accounts in every tenant realm") but is a spec, not enforced anywhere yet.
   - **Option B: per-tenant-realm identities, one `public.providers` row.** Matches `portals.md:207`; requires either dropping the `keycloak_user_id` column (identities become tenant-realm-scoped) or turning it into `provider_keycloak_identities(provider_id, tenant_realm, keycloak_user_id)`. Substantially more moving parts.
   - **Recommend A.** The schema already supports it, the gateway is already tenant-less on `/api/v1/providers`, `portals.md` can be amended to match.

5. **FK strategy for tenant tables.**
   - **Option A: cross-schema FK** — `ALTER TABLE tenant_x.claims ADD FOREIGN KEY (provider_id) REFERENCES public.providers(id)`. Postgres supports this within one database. Enforces referential integrity at the DB layer for the 3 hard FKs.
   - **Option B: bare UUID column, app-enforced against `public.providers`** — matches the 8 soft references we already have. Cheaper migration (no FK statements), consistent with the 8 soft columns, but relies on application layer for referential safety.
   - **Recommend A for the 3 hard FKs, leave the 8 soft as-is.** Least surprise; the hard FKs are hard for a reason.

6. **What to do with the 5 orphan tenant-local rows** in `tenant_first_medfund` (funeral parlour, brokers, medical, advisor). Options:
   - Promote to `public.providers`, add `provider_tenants(provider_id, tenant_id=first_medfund)` — preserves the data.
   - Drop after operator confirmation — they may be test fixtures.
   - Fail the migration if any orphan exists, require operator to `INSERT INTO public.providers ...` first.
   The migration script must handle this; the plan should recommend promote-with-membership as the default.

7. **Migration destructive step.** Drop `tenant.providers` entirely vs. leave as a deprecated view (`CREATE VIEW providers AS SELECT p.* FROM public.providers p JOIN public.provider_tenants pt ON pt.provider_id = p.id WHERE pt.tenant_id = current_setting('app.tenant_id')::uuid`). A view means the 18+ unqualified-`providers` query sites don't need code changes in v1; they just start reading a view. Cleaner mid-term to fix the queries and drop the view.

8. **Amendment to Critical Rule 2 in `.claude/multi-tenancy.md`.** The rule needs an explicit "platform tables with tenant membership" section. Wording: *"Platform tables (`public.providers`, `public.plans`, `public.currencies`) are shared by design. When a platform table has per-tenant relevance, every tenant-scoped read must include a membership check via the corresponding `public.<entity>_tenants` join table."* Without this amendment, prior-research-style objections will re-emerge and the redesign will be re-litigated.

## Gap between spec and code

Where `.claude/portals.md` §Provider Portal (`portals.md:201-240`) promises features the code doesn't have:

| Spec (portals.md line) | Reality |
|---|---|
| `:207` "Each provider has accounts in every tenant realm they serve" | `Provider.keycloakUserId` is a single column (`Provider.java:51-52`); one Keycloak identity per provider row, period |
| `:211-227` `/providers/dashboard`, `/providers/tenant-switcher`, `/providers/claims/*`, `/providers/pre-auth/*`, `/providers/payments/*`, `/providers/profile`, `/providers/staff`, `/providers/documents`, `/providers/tariff-lookup`, `/providers/verify-member` | Zero Angular routes exist under `/providers/*`; only `/platform/providers` admin console |
| `:229-239` Flutter "provider mode": quick claim submission w/ OCR, member QR, payment push, claim status | Zero provider files in `clients/flutter/lib/` |
| `:459-463` Roles `provider`, `provider_admin` with permissions submit-claim, request-pre-auth, view-payments, manage-staff | No `provider`/`provider_admin` role wiring in Angular guards or Keycloak fixtures; `Permissions.java:162` `providers:manage_contracts` has zero consumer code |
| `:220` `/providers/profile` (banking, tax clearance, practice info self-service) | No `/me` endpoint on `ProviderController.java`; `findByKeycloakUserId` exists but is never called |

Where `.claude/adjudication.md` promises facts the code doesn't populate:
| Spec | Reality |
|---|---|
| `ProviderFact.inNetwork`, `ProviderFact.networkTier` declared with contract-status semantics (`ProviderFact.java:12-16`) | `ClaimFactBuilder.fetchProvider` (`ClaimFactBuilder.java:202-213`) only sets `providerId`; both fields perpetually null → any Drools rule keying on `inNetwork` is dead |

## Code References

- `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:18-77` — platform-scoped entity, `keycloak_user_id` at :51, `network_tier` at :56
- `services/java/user-service/src/main/java/com/medfund/user/service/ProviderService.java:37` — `PLATFORM_TENANT` sentinel; :89-135 `onboard`; :113-124 Keycloak user create in `medfund-platform`; :128 publisher call; :150-234 update/suspend/activate/verifyAhfoz (audit-only, no Kafka)
- `services/java/user-service/src/main/java/com/medfund/user/repository/ProviderRepository.java:13-57` — R2DBC repository, all unqualified `providers`, `findByKeycloakUserId:19-20` (no non-test caller)
- `services/java/user-service/src/main/java/com/medfund/user/controller/ProviderController.java:27-139` — admin CRUD/search/verify/suspend/activate; no `/me`
- `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java:168-176` — `publishProviderOnboarded` (uncommitted extension to 4-arg)
- `services/java/tenancy-service/src/main/resources/db/migration/public/V105__providers.sql:1-36` — target-state migration with intent-declaring header
- `services/java/tenancy-service/src/main/resources/db/migration/public/V106__provider_registration_number.sql:5` — rename `practice_number → registration_number` (tenant never did)
- `services/java/tenancy-service/src/main/resources/db/migration/public/V135__public_provider_network_tier.sql:8-13` — public network_tier
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:62-78` — legacy tenant providers table (to be dropped)
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:129` — `claims.provider_id REFERENCES providers(id)` (to be retargeted to `public.providers`)
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V213__provider_network_tier.sql:7-13` — tenant network_tier (paired with V135)
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V084__treaty_participant.sql` — junction-table precedent
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/TreatyParticipant.java` — composite-PK entity pattern
- `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java:71-90` — `PROVIDER_MODE_BY_LINE`; :365-381 `validateProviderPolicy`
- `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimFactBuilder.java:202-213` — reads `public.providers` (precedent); `ProviderFact` fields left null
- `services/java/finance-service/src/main/java/com/medfund/finance/factbuilder/PaymentRunFactBuilder.java:81` — also reads `public.providers`
- `services/java/rules-engine/src/main/java/com/medfund/rules/facts/ProviderFact.java:12-16` — `inNetwork`, `networkTier` declared, semantics documented, never populated
- `services/java/shared/src/main/java/com/medfund/shared/tenant/TenantWebFilter.java:58` — `PLATFORM_PATHS` includes `/api/v1/providers`
- `services/java/shared/src/main/java/com/medfund/shared/tenant/TenantAwareConnectionFactory.java:73-95` — no-tenant → `search_path=public` (line 85)
- `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderOnboardedConsumer.java` (uncommitted) — to be deleted
- `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderBackfillRunner.java` (uncommitted) — to be deleted
- `services/java/user-service/src/main/java/com/medfund/user/controller/TenantStatsController.java:895,933` — dynamic cross-schema SQL to be reconsidered
- `services/java/shared/src/main/java/com/medfund/shared/rbac/Permissions.java:162` — `providers:manage_contracts` stub
- `services/java/shared/src/main/java/com/medfund/shared/rbac/PermissionCatalogue.java:136` — catalogue entry
- `services/go/gateway/internal/routes/routes.go:70-71` — proxy config
- `services/go/gateway/internal/middleware/tenant.go:15-34,49-53` — `platformPaths` allowlist with intent comment
- `clients/angular/src/app/app.routes.ts:11,44-48,147` — admin route + legacy redirect
- `clients/angular/src/app/pages/providers/providers.component.ts:63-80` — admin actions
- `clients/angular/src/app/core/services/feature-flag.service.ts:15` — `PROVIDER_PORTAL` unused literal
- `clients/angular/src/app/auth/auth.guard.ts:57-81` — `rootRedirectGuard` needs a `provider` branch
- `.claude/multi-tenancy.md:225,242` — replication-model doc (contradicts target)
- `.claude/portals.md:201-240` — provider portal specification
- `.claude/portals.md:207` — multi-realm identity doc (contradicts target)
- `.claude/portals.md:459-463` — provider/provider_admin role definitions
- `.claude/architecture.md:588,599` — self-contradictory schema tree
- `.claude/adjudication.md:41-42,193-194,333-343` — provider participation in claims pipeline
- `.claude/CLAUDE.md` §Critical Rule 2 — tenant-scoping rule, no platform-table exception documented

## Architecture Insights

- **Critical Rule 2 needs an explicit "platform table with tenant membership" exception documented in `.claude/multi-tenancy.md`.** The redesign satisfies the rule's *intent* (each tenant only sees providers it has a relationship with, via `provider_tenants` join) but violates its *letter* (reads from `public.providers` are not tenant-scoped). The prior research (2026-09-15-seeder-phase-6-500s) already flagged this doc gap; the redesign is the trigger to close it.
- **Critical Rule 6 (Kafka for side effects)** will need `PROVIDER_TENANT_LINKED`, `PROVIDER_TENANT_UNLINKED`, `PROVIDER_UPDATED`, `PROVIDER_SUSPENDED` events if any consumer needs to react. The current fan-out consumer is the only Kafka subscriber; removing it means any tenant-side cache invalidation needs another consumer.
- **Critical Rule 7 (Swagger)** — new endpoints (`/api/v1/providers/{id}/tenants`, `/api/v1/providers/me`, `/api/v1/providers/me/tenants`, provider self-service profile/staff) all need OpenAPI 3.1. Existing provider endpoints need a Swagger completeness audit.
- **Critical Rule 8 (audit every mutation)** — the `provider_tenants` link/unlink is a mutation and must emit `AuditEvent` with `actorEmail` (per `feedback_audit_actor_email`) and friendly `entityName` (per `feedback_audit_entity_name`) — probably the provider name, not the composite key.
- **Critical Rule 9 (security events)** — provider login becomes a first-class security event source. Every login/logout/failed-auth/MFA/permission-denial for a provider identity must land in `security_events` alongside members and staff.
- **The uncommitted work on this branch is what the redesign supersedes.** The `ProviderOnboardedConsumer`, `ProviderBackfillRunner`, the `UserEventPublisher.publishProviderOnboarded` extension to 4 args, and the accompanying test all get removed. The publisher can revert to its 2-arg signature. The backfill runs (60 shadow rows per tenant, verified live) will be undone by the tenant-`providers`-drop migration.
- **Junction-table convention is hand-rolled `DatabaseClient`, not Spring Data.** `ReactiveCrudRepository` doesn't handle composite keys. `TreatyParticipantRepository` is the canonical pattern; `ProviderTenantRepository` should mirror it line-for-line (raw SQL for CRUD, static `mapRow(Row, RowMetadata)` mapper, no interface).
- **Doc drift is the highest-priority follow-up.** Three-way contradiction across `.claude/multi-tenancy.md`, `.claude/portals.md`, `.claude/architecture.md` — plus the migration header at `V105__providers.sql:1-3` that already declares the right answer. Fix all four in one PR alongside the schema change.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md` — introduced the Option A / Option B framing; recommended and rejected respectively. **The user's proposal is Option C** (platform table + tenant membership join + line tag + provider self-service), a third path not previously considered.
- `thoughts/shared/plans/2026-09-15-provider-fanout-and-age-group-effective-from.md` — the plan that implemented Option A (currently uncommitted in the working tree). "What We're NOT Doing" lists retirement of the shadow explicitly. The current plan and this new redesign are logical successors; the new plan supersedes and removes the Option A code.
- `thoughts/shared/research/2026-09-15-tenant-provisioning-rbac-model.md` — describes provider identity as *"a Keycloak user in EACH tenant realm they serve"* (line 127). This matches `portals.md:207` but conflicts with the actual single-`keycloak_user_id` schema. Design fork #4 above resolves this.
- Other provider-touching docs found (not read in full — flag if the plan crosses these areas): `2026-08-10-creditors-workflow-unify-providers-and-members.md`, `2026-08-22-phase11-producer-commission.md`, `2026-08-09-payment-run-vs-payments.md`, `2026-09-15-comprehensive-seed-data-two-tenants-health-life.md`.

## Related Research

- `thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md` — Option A/B analysis
- `thoughts/shared/research/2026-09-15-tenant-provisioning-rbac-model.md` — provider identity discussion
- `thoughts/shared/plans/2026-09-15-provider-fanout-and-age-group-effective-from.md` — the Option A implementation this redesign supersedes

## Open Questions

- **Line tag cardinality**: single `insurance_line` or `insurance_lines TEXT[]`? Data question: does any real-world provider in the seed data or expected pilot deployments serve multiple lines?
- **Contract fields v1 vs. deferred**: which `provider_tenants` columns land in v1 (probably `network_tier`, `in_network`, `status`) and which wait for a `provider_contracts` sub-table?
- **Migration destructiveness**: drop `tenant.providers` outright, or keep as a compatibility view for one release? A view lets the 18+ unqualified-`providers` query sites keep working while they're being repointed; a drop forces the repointing to happen in the same PR.
- **Provider Keycloak realm**: stay with `medfund-platform` or introduce a `medfund-providers` realm? The gateway and web-filter already handle tenant-less requests; the isolation argument for a separate realm is defence-in-depth against a provider role escalation. Product/security call.
- **Orphan tenant rows**: promote-with-membership vs. drop? Needs an operator conversation, not just a code decision.
- **Provider-tenant linking authorization**: who can link a provider to a tenant? Super-admin only, or can tenant-admin invite-a-provider? The invite flow doesn't exist today (`Provider` has no `ProviderInvite`); the plan needs to decide the shape.
- **Cross-service Kafka events for `provider_tenants` mutations**: does any consumer actually need them today? None found; punt to when a consumer materialises.

---

**Hand-off for the next step of the RPI loop.**

Research written to `thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md`.

Clear your context, then run:

```
create-plan thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md \
            thoughts/shared/plans/2026-09-15-provider-fanout-and-age-group-effective-from.md \
            "supersedes the Option A fan-out plan; delete the uncommitted consumer + backfill runner + publisher extension as part of Phase 1"
```

Passing the existing Phase 1 plan as a second input lets `create-plan` see exactly what it's superseding. The steer tells it to include the cleanup in Phase 1 rather than treat the deletions as an afterthought.
