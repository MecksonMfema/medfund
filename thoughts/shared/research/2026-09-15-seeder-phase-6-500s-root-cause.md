---
date: 2026-09-15T17:31:21+02:00
researcher: Methuseli
git_commit: 26906fa65318807e020e2abb4aad75bfc6710a75
branch: rename-adjustments-to-notes
repository: medfund
topic: "Root cause of Phase 6 seeder's three server-side 500s: contributions/commit, claims submit, payment-runs create"
tags: [research, codebase, seeder, contributions-service, claims-service, finance-service, tenant-schema, r2dbc, kafka]
status: complete
last_updated: 2026-09-15
last_updated_by: Methuseli
---

# Research: Root cause of Phase 6 seeder's three server-side 500s

**Date**: 2026-09-15T17:31:21+02:00 · **Researcher**: Methuseli · **Commit**: 26906fa6 · **Branch**: rename-adjustments-to-notes

## Research Question

The demo-seeder plan `thoughts/shared/plans/2026-09-15-seed-two-tenants-health-life.md` documents three server-side 500s from Phase 6 that blocked the timeline replay: `POST /api/v1/contributions/commit`, `POST /api/v1/claims`, and `POST /api/v1/payment-runs`. Each returns a generic `INTERNAL_SERVER_ERROR + correlationId` with no body detail. Given the seeder's payloads look well-formed and the services boot cleanly, what is the root cause of each 500?

## Summary

- **Claims submit 500 — root cause is a schema mismatch**, not an application bug. Providers are seeded via `POST /api/v1/providers` (user-service) which writes to `public.providers` (platform-scoped). But the tenant baseline migration also creates a per-tenant `providers` table, and `claims.provider_id` FKs into that tenant-local table, which is **never populated**. There is no `provider.onboarded` fan-out consumer that shadows platform providers into each tenant schema. Every claim submission fails on the FK constraint → the R2DBC `DataIntegrityViolationException` lands in `GlobalExceptionHandler` and surfaces as an opaque 500.
- **Contributions preview `amount=0` — the direct cause is stale `age_group_prices.effective_from`.** The seeder's `POST /api/v1/schemes/age-groups` endpoint hardcodes `effective_from = CURRENT_DATE`, but Phase 6 back-dates contributions to `timeline_start - 3mo`. The preview's LATERAL join filters `age_group_prices WHERE effective_from <= period_start`, and there are no rows valid for the historical period, so the amount collapses to zero.
- **Contributions commit 500 — most likely cascade of the amount-zero path plus a side-effect in the balance/earning hooks.** With `amount=0` rows persisting successfully (the DB accepts `DECIMAL(19,4) NOT NULL DEFAULT 0`), the follow-on chain (`balanceService.applyContributionDebit`, `earningHook.onContributionCreated`, or `invoiceSnapshotService.stampSnapshot` under the `null-holder + zero-total` boundary) is the most probable trigger. Confirming which requires re-running one commit with the Java service's stdout captured to a file — the deviation note says stdout went to a broken pipe.
- **Payment-run create 500 — the earlier agent's line-232 NPE hypothesis is wrong** (the code is null-guarded there). Cause is not conclusively identified from static reading; needs the same stdout capture as the commit path. Best static candidates: the `Map.of(...)` payload in `publishAudit`/`publishPaymentRunCreated` rejects null values, and if any Kafka publisher path returns null on downstream failure, the reactive chain 500s.

## Findings

### Claims submit 500 — cross-schema FK violation on `provider_id`

**Where the mismatch lives.**

- `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:18` — `@Table(schema = "public", value = "providers")`. Every `POST /api/v1/providers` writes to `public.providers`.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:62-78` — every tenant schema gets its own local `providers` table.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:129` — `claims.provider_id UUID NOT NULL REFERENCES providers(id)`. The FK resolves to the tenant-local table because R2DBC's `Claim` entity's `@Table("claims")` binding is unqualified and `TenantWebFilter` sets `search_path` to the tenant schema for tenant-scoped requests.

**Where the sync should have been but isn't.**

- Grep across `services/java/**` for `class .*ProviderConsumer`, `@KafkaListener.*provider`, and `topic.*provider.*onboard` returns **only** the `UserEventPublisher` producer + its test — no consumer. No service subscribes to a `provider.onboarded` event to upsert a shadow row into `tenant_<schema>.providers`.

**What happens in the seeder run.**

1. Phase 2 posts N providers → `public.providers` fills with N rows (~50 HEALTH / ~10 LIFE per the micro tier).
2. Phase 6 timeline picks a random provider from `GET /api/v1/providers` (`scripts/demo-seeder/src/demo_seeder/phases/timeline.py:252-259`), returns platform-scoped rows.
3. Phase 6 posts a claim with `providerId = <UUID from public.providers>` via `POST /api/v1/claims`.
4. `ClaimService.submit` at `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java:258` calls `claimRepository.save(claim)`. Postgres enforces the FK against **tenant.providers** (empty) → `foreign_key_violation` (SQLSTATE 23503).
5. `GlobalExceptionHandler` maps unhandled exceptions to a generic 500. No response body detail.

**Confidence: high.** This is a schema-level guarantee. If the tenant providers table has 0 rows, every claim POST 500s.

**Note on the earlier NPE hypothesis** (line 266 `savedClaim.getClaimedAmount().toString()`): this is possible in principle but unlikely — the field is set from a `@NotNull @DecimalMin("0.01")` DTO one line above the save, and R2DBC's `save()` returns the same entity instance with only the auto-generated ID populated. The FK violation happens *at line 258*, before the audit block ever runs.

### Contributions preview `amount=0` — historical period misses `age_group_prices.effective_from`

**How pricing gets seeded.**

- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/SchemeService.java:91-102` — `insertCurrentPrice` inserts into `age_group_prices` with `effective_from = CURRENT_DATE, effective_to = NULL`. Called from `createAgeGroup` (line 481).
- Seeder Phase 3 hits `POST /api/v1/schemes/age-groups` on the day the seeder runs (today).

**How preview reads pricing.**

- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/candidate/HealthCandidateResolver.java:112-120` — LATERAL join on `age_group_prices WHERE age_group_id = ag.id AND effective_from <= :period_start AND (effective_to IS NULL OR effective_to >= :period_start)`.
- When `period_start` (e.g., 2026-06-01) is *before* `effective_from` (2026-09-15, today), the LATERAL join yields no rows and `contribution_amount` falls through to NULL → `COALESCE(..., 0)` → `amount = 0`.

**Why the deviation note says `age_groups.contribution_amount` is populated in psql (30250 ZWL) but preview returns 0.**

`age_groups.contribution_amount` is a **denormalized cache** — the current price at row-write time. The pricing table of record is `age_group_prices`, gated by `effective_from`/`effective_to`. Preview reads the versioned table.

**Fix outline (no code changes made):**

- Option A: `SchemeService.insertCurrentPrice` accepts a caller-supplied `effectiveFrom`; `CreateAgeGroupRequest` grows an optional `effectiveFrom` field; the seeder passes `timeline_start`.
- Option B: seeder writes shadow `age_group_prices` rows via a SQL script post-Phase-3 (breaks the "HTTP APIs only" constraint).
- Option C: preview falls back to `age_groups.contribution_amount` when no `age_group_prices` row is active for the period (semantic change; changes what the price-history feature guarantees).

**Confidence: high** on the mechanism; the earlier agent's "NULL effective_age_group_id" hypothesis was a plausible-but-incorrect alternative — dates, not age-band gaps, are what break the LATERAL join in the seeder scenario.

### Contributions commit 500 — probable failure in the balance/earning/snapshot hooks under `amount=0`

**The commit path**, `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingService.java:909-968`:

1. `resolveCandidatesForTenant` → yields candidates (member × age-group).
2. `applyPricing` → returns `PricedCandidate(amount=0, currencyCode=…)` for every row (see previous section).
3. `persistContribution` at line 917 → `contributionRepository.save(c)` at line 1568 succeeds (schema allows amount=0).
4. **`balanceService.applyContributionDebit(saved).thenReturn(saved)` at line 1570** — plausible trigger. Debit-history schema fields may have implicit non-null assumptions that a zero-amount debit doesn't satisfy.
5. **`earningHook::onContributionCreated` at line 1572** — Phase 12 §A earning-schedule projection. Second plausible trigger.
6. `updateLastCommittedAt` + `publishBillingGenerated` Kafka + `publishAudit` — Map.of() calls that reject nulls; if audit metadata resolves to null anywhere, NPE.
7. `generateInvoicesFor(...)` → per-invoice: `invoiceSnapshotService.stampSnapshot` at line 1401. `stampSnapshot` itself is defensively coded (`nz()` helpers, `defaultIfEmpty(Prior.EMPTY)`), but the `.flatMap(invoiceRepository::save)` immediately after can 500 if `invoices.total_amount` NOT NULL is violated by a null derived from an empty saved-contributions list.

**Which one 500s.** Static reading cannot resolve this — the deviation note captured stdout to `/dev/null`, so no stack trace is on disk. To disambiguate, re-run contributions-service with stdout to a file (`make contributions 2>&1 | tee /tmp/contributions.log`) and re-post one commit via curl. The stack trace will pin the line in under a minute.

**Confidence: moderate.** The three candidates above are the highest-likelihood; runtime capture is needed to pick one.

### Payment-run create 500 — cause not conclusively identified from static reading

**The create path**, `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:155-235`:

1. Bank account lookup at line 184-192 → succeeds (Phase 6 verified: bank accounts UP).
2. `paymentRunRepository.save(run)` at line 209 → INSERT succeeds.
3. `paymentRunGenerator.populate(saved, finalStart, finalEnd)` at line 213 →
   - For PROVIDER: `populateProviderItems(runId, currency).count().map(Long::intValue)` at `PaymentRunGenerator.java:89`. Empty flux → count=0 → `Mono.just(0)`.
4. `paymentRunRepository.save(saved)` at line 217 → UPDATE (ID populated). Should succeed.
5. `publishAudit(...)` at line 222-227 → `AuditEvent.create(...)` at line 728-740. Any null in the fields (`tenantId`, `entityName`, `actorId`, `actorEmail`, action, correlationId) fails a Map assertion or an audit constraint (memory `feedback_audit_actor_email` requires `actorEmail` non-null; `feedback_audit_entity_name` requires friendly text, not UUID — here `saved.getRunNumber()` is friendly text, good).
6. `eventPublisher.publishPaymentRunCreated(...)` at line 228-233 — the earlier agent claimed line 232 NPEs on `saved.getTotalAmount().toPlainString()`. **Wrong**: the ternary `saved.getTotalAmount() != null ? saved.getTotalAmount().toPlainString() : "0"` is guarded. Same for `saved.getPaymentCount()` at line 233.

**Best remaining static candidates for the 500**:

- `Map.of("runNumber", …, "status", saved.getStatus(), "paymentCount", …)` at line 225-227 — `saved.getStatus()` should be `"draft"` (set at line 197 before save), but if R2DBC's UPDATE-mode save at line 217 doesn't re-hydrate the entity from the DB (which it doesn't in the default flow), then `saved.getStatus()` is whatever the local field held. It should still be `"draft"`. Low probability.
- The `Map.of(...)` in `publishPaymentRunCreated` at `FinanceEventPublisher.java:58-65` — same null-hostile pattern. `currencyCode` and `runNumber` should be non-null by then, so also low.
- Reactor-Kafka `.doOnTerminate` vs `.doOnSuccess` on the offset ack (memory `bug_reactor_kafka_ack_swallow`) — this pattern eats errors, so if a Kafka publish 500s, the error surfaces here without a useful body. If the seeder's `AuditPublisher` uses this pattern, that's the trigger.

**Confidence: low** without runtime capture. The path is short and null-guarded; static reading can't rule the remaining candidates in or out.

## Cross-service flow

Only the claims 500 spans services: the seeder posts to user-service (`POST /api/v1/providers`), then to claims-service (`POST /api/v1/claims`), and claims-service in turn calls contributions-service via `SchemeClient` (`services/java/claims-service/src/main/java/com/medfund/claims/client/SchemeClient.java:46-60`) before its FK-violating `save()`. The scheme lookup succeeds; the failure is inside claims-service on `claimRepository.save`.

Contributions commit and payment-run create are single-service failures; both emit Kafka events (`medfund.billing.generated`, `medfund.finance.invoice.issued`, `medfund.payments.run.created`) whose downstream consumers never receive the message because the producer never emits a successful record.

## Architecture doc vs. code

- `.claude/multi-tenancy.md` (schema-per-tenant design) says platform-scoped tables live under `public.` and tenant-scoped tables live under `tenant_<uuid>.`. The `providers` table is a **hybrid** — a platform row-of-record (`public.providers`) *and* a tenant-scoped local table (`tenant.providers`). The doc doesn't spell out which is which, and the code has both. This is the drift that produces the claims 500. Follow-up: pick one of record and either back-fill via a fan-out consumer or delete the tenant-local table.
- `.claude/adjudication.md` treats providers as first-class facts on `ClaimFact`. If providers are meant to be platform-wide, `claims.provider_id` should FK into `public.providers`, not tenant.providers. That FK change is a schema decision.

## Code References

- `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:18` — providers live in `public.providers`
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:62-78` — tenant.providers table (shadow)
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:124-153` — claims table + FK to (tenant.)providers
- `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java:258` — save that trips the FK
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/SchemeService.java:91-102` — `insertCurrentPrice` hard-codes `effective_from = CURRENT_DATE`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/candidate/HealthCandidateResolver.java:112-120` — LATERAL join gated on `effective_from`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingService.java:909-968` — `doCommit` orchestrator
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingService.java:1543-1573` — `persistContribution` including balance + earning hooks
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingService.java:1373-1443` — `persistInvoiceFor` + `stampSnapshot` call
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/InvoiceSnapshotService.java:62-99` — `stampSnapshot` (defensively coded)
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:155-235` — payment-run create path (already null-guarded at line 232)
- `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java:56-66` — `publishPaymentRunCreated` (uses null-hostile `Map.of`)
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V029__age_group_price_history.sql:23-46` — `age_group_prices` schema
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V075__tenant_bank_accounts.sql:53-95` — `payment_runs.source_bank_account_id NOT NULL`
- `scripts/demo-seeder/src/demo_seeder/phases/timeline.py:252-259` — seeder fetches providers from platform endpoint
- `scripts/demo-seeder/src/demo_seeder/phases/schemes.py:372-411` — seeder posts age-groups with today's implicit `effective_from`

## What this ticket needs decided

- **Provider-of-record for tenant-scoped claims.** Two forks:
  - *A. Keep the tenant.providers shadow, add a fan-out consumer.* `POST /api/v1/providers` (user-service) publishes `medfund.providers.onboarded`; every tenant's claims-service (or a dedicated consumer app) subscribes and upserts a shadow row keyed by the platform UUID. Preserves the schema-per-tenant guarantee for reads. Small new consumer to write.
  - *B. Retire tenant.providers; change `claims.provider_id → public.providers`.* Simpler schema, but breaks the "every DB query is tenant-scoped" rule (#2 in `.claude/CLAUDE.md`) unless the FK is decorated with a tenant filter at query time. Requires a Flyway migration to drop the tenant table and re-point the FK.
  - Recommend A — matches the greenfield multi-tenancy story.

- **Historical `age_group_prices` for seed runs.** Two forks:
  - *A. Extend `CreateAgeGroupRequest` with optional `effectiveFrom`.* Seeder passes `timeline_start`. Real operators keep today. Minimal API surface change.
  - *B. Seeder writes `age_group_prices` shadow rows via psql after age-group creation.* Breaks the "HTTP APIs only" seeder constraint but requires no service change.
  - Recommend A — the seeder scenario is legitimate (any historical replay tool would want it) and the extension is backward-compatible.

- **Which contribution-commit hook is trapping.** Deferred to next-step verification: re-run with stdout captured. The three candidates (`applyContributionDebit`, `onContributionCreated`, `stampSnapshot`) all sit inside `doCommit`; the fix scope depends on which fires.

- **Payment-run 500 — is it independent or a cascade of the claims 500?** Deferred to next-step verification. If empty `provider_balances` causes the crash, the fix is defensive nulling in `PaymentRunService.create`; if the trigger is somewhere else entirely (Kafka publisher, audit publisher), the fix is different.

## Gaps between spec and code

- `.claude/multi-tenancy.md` — treats platform vs tenant scope as a clean binary. The `providers` table is a hybrid with no fan-out. The doc needs a section on "shadow tables" or the code needs to converge to one of the two shapes.
- `.claude/adjudication.md` (§ Facts) — implies providers are visible from claims-service without spelling out how the visibility happens. In practice claims-service only reads `public.schemes` via the SchemeClient; there is no `ProviderClient` and no per-tenant provider fan-out.
- No architecture doc covers `age_group_prices` price-history vs `age_groups.contribution_amount` cache duality. A short entry in `.claude/multi-currency.md` or a new "pricing history" section would prevent this class of misuse.

## Architecture Insights

- Critical Rule 2 (tenant scoping) is being enforced in the read path (TenantWebFilter sets search_path) but not verified at the seeder's compose time. A seeder that fetches from a platform-scoped endpoint and reuses those IDs in tenant-scoped writes bypasses the tenant boundary silently.
- Critical Rule 8 (every entity mutation audit-logged) — audit events use `Map.of(...)`, which rejects nulls. Every path that publishes audit must resolve every field to a non-null value first, or use `Map.ofEntries(...)` / a linked map that accepts nulls. This is a recurring 500 trap; the memory `feedback_audit_actor_email` mentions it in another context.
- `.claude/coding-standards.md` — `GlobalExceptionHandler` catch-all mapping unknown exceptions to 500 with a generic body is by design (correlationId-based). This works for real operators (they read the correlation ID and grep the log) but is opaque to programmatic clients (the seeder). Consider adding an `X-Correlation-Id` header on the 500 response so the seeder can quote the ID in its logs and cross-reference — cheap DX win.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/plans/2026-09-15-seed-two-tenants-health-life.md` — the plan whose Phase 6 Deviations block this research
- `thoughts/shared/research/2026-08-10-tenant-bank-accounts-and-stubbed-gateway.md` — priors on payment-runs / bank_accounts / payment-gateway stubbing (per plan References)
- `thoughts/shared/research/2026-08-09-ctc-payments.md` — member payment-runs are CTC-opt-in; the seeder scope excludes them and this research does too

## Related Research

- (none — this is the first Phase-6 500 diagnosis)

## Open Questions

- Which specific hook inside `doCommit` throws the commit 500? (`applyContributionDebit` vs `earningHook.onContributionCreated` vs `stampSnapshot` vs an audit/Kafka publisher). Requires running one commit with stdout captured.
- Is payment-run create's 500 truly independent of the claims 500, or does empty `provider_balances` propagate? Same runtime capture will disambiguate.
- Does `.claude/multi-tenancy.md` need a formal "shadow tables" doctrine, or should the tenant.providers table be removed?
- Should the AuditPublisher standardize on a null-tolerant map builder to prevent future 500s of this shape across services?

## Handoff

Research written to `thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md`.

Clear your context, then run:

```
create-plan thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md \
            thoughts/shared/plans/2026-09-15-seed-two-tenants-health-life.md \
            "start with the claims FK fix — that's the highest-confidence and unblocks Phase 6 verify"
```

Or, if you want to scope down to just the schema fix and defer the two verify-needed causes:

```
create-plan thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md \
            "provider fan-out consumer only; contributions and payment-run fixes deferred until stdout captured"
```
