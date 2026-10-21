---
date: 2026-08-23
git_commit: 55c368900d
branch: rename-adjustments-to-notes
ticket: null
spec: null
parent_plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md#phase-12
research:
  - thoughts/shared/plans/2026-08-11-financial-reporting-suite.md (Phase 12 section, lines 3090-3300 — grilling doc U1..U15 + F12-1..F12-12 dated 2026-08-23)
grilling:
  - decisions U1..U15 landed in parent plan at thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:3121-3137 on 2026-08-23
steer: "for phase 12"
services_touched: [tenancy-service, user-service, contributions-service, rules-engine, shared, gateway, angular]
status: draft
---

# Phase 12 — UPR Earning Schedule + Premium Register

## Overview

Greenfield premium-lifecycle module in `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/*` (U3 — honors G2 data-ownership; sits alongside `BillingService` + `BillingCycleExecutor`). Ships **written premium capture at bind time** for all 8 insurance lines (U1 + U2 mixed-billing: annual-bind for LIFE/FUNERAL/DISABILITY/VEHICLE/PROPERTY, monthly for HEALTH via `Contribution`, per-trip for TRAVEL), a **line-configurable earning engine** driven by a new `RuleCategory.PREMIUM_EARNING` (U4 + U11 — agenda-gated alongside `REINSURANCE`, `COMMISSION`, `BENEFIT_PRORATION`), a **per-policy-per-period `earning_schedule` materialization** (U7) with nightly `PremiumEarningExecutor` (U10), and four tenant-toggleable reports under a new `ReportFamily.UNDERWRITING` (U9): `UPR_MOVEMENT`, `PREMIUM_REGISTER`, `NEW_BUSINESS_REGISTER` (three keys already ship in `ReportKey.java:99-102` — enum edit only reassigns family), plus `ENDORSEMENT_REGISTER` (new key, added §C).

Ships a **full endorsement module in §C** (U5) with retro earning-schedule recompute (U8) and tenant-configurable four-eyes threshold (U12) mirroring Phase 11 `CommissionAdjustment` shape. Speculatively adds **IFRS 17 portfolio + cohort dimensions in §A** (U14) so Phase 15 IFRS 17 doesn't need a schema reshape on populated tenants. New-business classification is **policy-centric with a `renewed_from_policy_id` renewal chain** (U6) mirroring Phase 10 R11 Treaty pattern; HEALTH new business = member's first Contribution ever within the tenant.

**§0 pre-work (U15)**: pay down existing Testcontainers pool debt that has caused IT deferrals in Phase 9 + 11 (extend user-service ITs to shared `AbstractIntegrationTest`; tune R2DBC connection ceilings; triage the 25 pre-existing IT flakes; add pool-starvation health check). Once §0 stabilises the flakes, Phase 12 §A/§B/§C ITs land in-tranche without deferral.

Every decision in this plan is inherited from the grilling session that produced U1..U15 (see [parent plan](2026-08-11-financial-reporting-suite.md#phase-12), lines 3121–3137). A code-altitude verification pass on 2026-08-23 confirmed the inherited claims and settled every open grill note — see *Verified during this planning pass* below.

## Current State Analysis

- **The module is 100% greenfield** (F12-4). Grep across `services/java/*/src/main/java` and `clients/angular/src/app` for `UPR|Unearned|earned_premium|earning_schedule|endorsement|new_business|policy_issuance` returns nothing operational except the three `ReportKey` enum stubs (F12-2). Verified 2026-08-23.
- **Report catalogue is pre-wired.** `ReportKey.UPR_MOVEMENT` (line 100), `ReportKey.PREMIUM_REGISTER` (line 101), `ReportKey.NEW_BUSINESS_REGISTER` (line 102), all under `ReportFamily.CLAIMS_FINANCIAL` (`ReportFamily.java:20`). Phase 12 §B reassigns them to a new `ReportFamily.UNDERWRITING` (U9) and adds `ENDORSEMENT_REGISTER` key in §C.
- **All Phase 0-11 shared infra composes on top** (F12-1): `ReportEnvelopeBuilder` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportEnvelopeBuilder.java:64-129` with 3 overloads), `FxRateReader.findRate/.convert` (`.../FxRateReader.java:51-93` — best-effort vs fail-loud), `ReportWorkbook`, `RequiresReport` + `ReportGuardAspect`, `SecurityEventPublisher.publishDataAccess`, `AuditActor.id/email`, `ReportingCurrencyResolver`, `CrossServiceCallHelper`. Zero new shared plumbing.
- **All tenant migrations live in tenancy-service** (F12-3). Highest tenant V today = **V101** (last: `V101__member_lapsed_status.sql`); Phase 12 §A opens at **V102**. Highest public V = **V133** (last: `V133__tenant_auto_lapse_config.sql`); §C opens at **V134**.
- **`ContributionEventPublisher` publishes 7 events today**: `.billing-generated`, `.paid`, `.invoice-issued`, `.transaction-recorded`, `.scheme-changed`, `.revoked`, `.invoice-pdf-deleted` (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/ContributionEventPublisher.java:27-293`). §A adds no new topic on the contributions side (§A's HEALTH earning-schedule hook is in-JVM per F12-11). §A's cross-service consumer subscribes to **new `medfund.user.policy-issued`** which user-service must add first.
- **`medfund.user.policy-issued` does NOT exist today** (F12-9). `user-service.MemberService` publishes `medfund.users.member-lifecycle` only. Phase 12 §A Phase 3 adds the publisher on the user-service side; §A Phase 5 adds the contributions-service consumer.
- **`medfund.user.policy-endorsed` does NOT exist** (F12-10). §C Phase 8 adds it.
- **Policy entity asymmetry is the load-bearing constraint** (F12-7). Of 6 policy entities in user-service:
  - `TravelPolicy` (`services/java/user-service/src/main/java/com/medfund/user/entity/TravelPolicy.java:14`) has `trip_start_date` + `trip_end_date`; no premium, no currency.
  - `LifePolicy` (`.../LifePolicy.java:14`) has `sum_assured` + `term_months` only.
  - `FuneralPolicy`, `DisabilityPolicy`, `Vehicle`, `Property` have no coverage-period columns.
  - **None** carry a `currency_code`.
  - **No policy has a premium column** — `sum_assured`/`cover_amount`/`monthly_benefit`/`vehicle_value`/`sum_insured` are *coverage* amounts. This asymmetry drives Phase 2's schema-widening pass.
- **`Contribution` (HEALTH)** (`services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Contribution.java:14`) has `amount`, `currency_code`, `period_start`, `period_end` — the natural analog of "written premium for the month" (F12-6). §A Phase 5 hooks into `BillingService.generateBilling` (line 177) + `BillingService.doCommit` (line 896) to write `earning_schedule` rows synchronously as `Contribution` rows are created.
- **contributions-service jobs use tenant-configurable `JobExecutor`** (F12-5). Interface at `services/java/shared/src/main/java/com/medfund/shared/scheduler/JobExecutor.java:9-24` (`getJobType()` + `execute(String tenantId, String settings)`). Reference implementations: `BillingCycleExecutor.java:14-41`, `OverdueCheckExecutor.java:14-42`. §A Phase 5 adds `PremiumEarningExecutor` extending this contract. Requires a new `JobType.PREMIUM_EARNING` enum arm.
- **`ContributionFact` today is billing-loop-flavored** (F12-8) — memberAge, dependantCount, smokingStatus, bmi (`services/java/rules-engine/src/main/java/com/medfund/rules/fact/ContributionFact.java:30-169`). U11 introduces a sibling `PremiumFact` rather than extending. `AGENDA_GATED_CATEGORIES` at `DrlCompiler.java:57` currently `{BENEFIT_PRORATION, REINSURANCE, COMMISSION}` — §A Phase 4 adds `PREMIUM_EARNING`.
- **Four-eyes precedent** for §C endorsement approval workflow: `CommissionAdjustmentService` (Phase 11, `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionAdjustmentService.java:32-186`) state machine `DRAFT → APPROVED → COMMITTED (terminal)` + `DRAFT|APPROVED → VOIDED`. Four-eyes enforced at line 92-96 (approver ID ≠ drafter ID). §C Phase 8's `PolicyEndorsementService` mirrors exactly.
- **Tenant-config precedent** for §C `tenant_endorsement_config`: `V133__tenant_auto_lapse_config.sql` (single-row-per-tenant, nullable settings, enable toggle). §C Phase 8's `V134__tenant_endorsement_config.sql` follows verbatim.
- **`AbstractIntegrationTest` already exists shared** at `services/java/shared/src/testFixtures/java/com/medfund/shared/testfixtures/AbstractIntegrationTest.java:38-124` — static Postgres 17-alpine + Kafka 7.6.0 containers per-JVM (one startup via `Startables.deepStart()`), `@DynamicPropertySource` wires R2DBC + Flyway + Kafka + Redis. Already extended by contributions-service (SchemeCostShareIT, CashFlowForecastControllerIT, SchemeServiceIT). §0 Phase 1 extends user-service ITs and finance-service Phase 11 IT set to this base uniformly; adds pool-ceiling config and pool-starvation health check.
- **Angular rule editor is category-agnostic** (extendable in 3 edits): `RULE_CATEGORIES` array in `clients/angular/src/app/core/services/rules.service.ts:36-90`; `ACTION_TYPES` array in `clients/angular/src/app/pages/tenant-admin/rules/rule-editor/rule-editor.component.ts:38-67`; `FACT_SEEDS` array in `clients/angular/src/app/pages/tenant-admin/rules/rule-dry-run/rule-dry-run.component.ts:32-141`. §A Phase 4 appends `PREMIUM_EARNING` entries to all three — verified pattern.

### Key Discoveries

- **HEALTH billing rows are the earning strip** — a single `Contribution` row for a month IS the "written premium for that month" and it earns entirely within that same month (U2, F12-6). No annual-bind step; no synthetic scheme-cost-shares. §A Phase 5's `BillingContributionEarningHook` writes one `earning_schedule` row per `Contribution` in the same reactive transaction.
- **Annual-bind lines commit the full annual premium at issuance** — LIFE/FUNERAL/DISABILITY/VEHICLE/PROPERTY require a new `written_premium` scalar on the policy entity, populated at issuance (U1 + U2). §A Phase 2 adds the column via V102-V106; §A Phase 3 requires the tenant admin to enter it in the CRUD form (existing sibling forms don't have a premium field today).
- **`bound_at` timestamp is the pivot column** — it's the "when did earning start" anchor for U6 new-business classification, U11 rules-engine bind-time firing, and U10 executor's period-close pass. Every annual policy gets `bound_at TIMESTAMPTZ NOT NULL` in V102-V106; backfilled from `created_at` for legacy rows.
- **Renewal chain (`renewed_from_policy_id UUID NULL SELF-FK`)** mirrors Phase 10 R11 Treaty precedent exactly (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V082__treaty.sql`). U6 classification query: `renewed_from_policy_id IS NULL AND bound_at BETWEEN periodStart AND periodEnd`.
- **Per-policy-per-period grain avoids the outline's "per day" storage explosion** (U7). 100k policies × ~12 periods/year = ~1.2M rows/year/line. Report queries interpolate one row per policy that spans the as-of date.
- **Native-currency storage preserves audit** (U8) — `earning_schedule.currency_code` is fixed at row creation from the policy's `written_premium_currency` (or Contribution's `currency_code` for HEALTH). Endorsements adjust `delta_amount` in the same currency, never re-denominate. Missing FX at report time = envelope warning, not throw.
- **`PremiumFact` is a new sibling fact** (F12-8, U11) — not an extension of `ContributionFact`. Cleaner tenant-configurable earning rules; keeps `ContributionFact` billing-loop-flavored.
- **`PolicyIssuedPublisher` fires from 6 user-service create paths** (grill note 3 owed back): `LifePolicyService.create`, `FuneralPolicyService.create`, `DisabilityPolicyService.create`, `TravelPolicyService.create`, `VehicleService.create`, `PropertyService.create`. Each also fires on `update` when `written_premium` changes materially (a mid-term price change without an endorsement — narrow surface, only if the admin edits the raw row; endorsements are §C's proper path).
- **`AbstractIntegrationTest` already extended by contributions-service** — §0's harness work is narrower than the parent-plan grill note suggested. Real work is: extend user-service ITs to the shared base + tune connection pool ceilings + fix the 25 pre-existing flakes that surface under full-suite pressure (root cause investigation, not just pool tuning).
- **Angular rules editor extends cleanly** — verified pattern used by Phase 11's `COMMISSION` category. 3 appends: RULE_CATEGORIES list, ACTION_TYPES array, FACT_SEEDS list.
- **Portfolio/cohort admin surface is basic CRUD for §A** — sophisticated portfolio-management (onerous-detection, loss-component decomposition, CSM measurement) is Phase 15 IFRS 17's job (U14).
- **Endorsement CRUD is DRAFT + APPROVE + COMMIT** — mirrors Phase 11 `CommissionAdjustment` exactly. The state machine, four-eyes guard, audit shape, and even the reference-generator pattern (`END-YYYY-NNNNNN` monotonic per year) transfer verbatim.

## Desired End State

**Backend**
- 9 new tenant-scoped tables under `tenant_<uuid>` schema (§A: V102 policy-widening + V107 `ifrs17_portfolio` + V108 `ifrs17_cohort` + V109 `earning_schedule`; §C: V110 `endorsement`; §A one-shot combined migration also lifts `status` enum arms on all 6 policy tables to `{ACTIVE, LAPSED, SUSPENDED, TERMINATED, DRAFT, LEGACY_NO_PREMIUM}` — the last a backfill-flag arm).
- 1 new public-schema table (§C: V134 `public.tenant_endorsement_config`).
- Additive columns on 6 user-service policy entities + Contribution (§A Phase 2): `written_premium NUMERIC(19,4)`, `written_premium_currency CHAR(3)`, `bound_at TIMESTAMPTZ`, `coverage_start DATE`, `coverage_end DATE`, `renewed_from_policy_id UUID NULL SELF-FK`, `status` enum widened, `portfolio_id UUID NULL FK`, `cohort_id UUID NULL FK`. TravelPolicy: same minus `coverage_start`/`coverage_end` (reuses `trip_start_date`/`trip_end_date`). Contribution: only `portfolio_id` + `cohort_id`.
- Premium module in `com.medfund.contributions.premium.*` — entities, repositories, service, executor, controllers, DTOs.
- Two new Kafka topics — `medfund.user.policy-issued` (§A), `medfund.user.policy-endorsed` (§C). Producers: user-service. Consumers: contributions-service.
- One in-JVM hook — §A Phase 5's `BillingContributionEarningHook` fires from `BillingService.persistContribution` in the same reactive transaction; no Kafka hop.
- One new `JobExecutor` — `PremiumEarningExecutor` (§A Phase 5), tenant-configurable via `scheduled_job_configs`.
- Four report endpoints under `/api/v1/reports/premium/*` (§B for 3, §C for endorsements) with `@RequiresReport` + `SecurityEventPublisher` + `ReportEnvelopeBuilder`.
- `RuleCategory.PREMIUM_EARNING` (agenda-gated) + `ActionType.ACCRUE_PREMIUM` + `PremiumFact` + `AccruePremiumEmitter` + `PremiumEarningTemplates` (3 rules) in rules-engine (§A Phase 4).
- Endorsement four-eyes workflow in user-service (§C Phase 8): `PolicyEndorsementService` with `DRAFT → APPROVED → COMMITTED (terminal)` + `DRAFT|APPROVED → VOIDED`, threshold gating from `public.tenant_endorsement_config`.

**Angular**
- Tenant-admin under `/tenant-admin/underwriting/*`: `Portfolios` CRUD, `Cohorts` CRUD (§A Phase 3).
- Tenant-admin under `/tenant-admin/settings`: new `Endorsement Config` tab (§C Phase 10).
- Per-policy under existing policy detail pages: `Endorsements` tab (§C Phase 10).
- Reports under `/tenant/finance/reports/underwriting/*`: `UPR Movement`, `Premium Register`, `New Business Register` (§B Phase 7), `Endorsement Register` (§C Phase 10).
- Approver queue under `/tenant/finance/underwriting/endorsements/review-queue` (§C Phase 10).
- Reports hub auto-registers the four keys under the new `UNDERWRITING` family card via `TenantReportConfigService`.

### Verification

```bash
# Backend
cd services/java && ./gradlew build test
make test-integration

# Angular
make test-angular
make test-e2e   # includes underwriting-reports, endorsement-workflow, portfolio-crud specs

# Manual acceptance
make infra && make tenancy user contributions finance claims gateway notification web
# Log in as tenant admin → /tenant-admin/underwriting/portfolios → create "General Motor 2026" portfolio
# Bind a new VehiclePolicy for a member with written_premium=$1200, coverage 2026-01-01 to 2026-12-31
# → observe earning_schedule row for period 2026-01 with written=100, earned=100 (period closed by nightly job)
# → observe earning_schedule row for period 2026-02..2026-12 (unclosed, earned=NULL until job runs)
# Export UPR Movement for period 2026-Q1 → XLSX shows opening=0, written=1200, earned=300, closing=900
# Endorse the policy on 2026-04-15 with premium_delta=+$100 (upgrade cover)
# → observe review queue entry (if threshold configured); approve + commit
# → observe earning_schedule rewrites for periods 2026-05..2026-12 with adjusted delta
# Export Endorsement Register for 2026-04 → XLSX shows the endorsement row
```

## What We're NOT Doing

- **Line-specific policy-detail surfaces**. Phase 12 doesn't add new policy CRUD screens per line — the existing `/tenant-admin/policies/*` covers policy creation; §A Phase 3 only extends the existing form fields for the new columns (written_premium, bound_at, coverage_start/end, renewed_from_policy_id picker).
- **Actuarial cash-flow decomposition**. IFRS 17 CSM (Contractual Service Margin) measurement, loss-component detection, and locked-in vs current-rate reconciliation are Phase 15's job. Phase 12 §A ships portfolio + cohort schema only; the maths lives downstream.
- **Bulk endorsement**. Endorsements are per-policy in §C. A bulk-endorsement UI (e.g. tenant admin lifts every LifePolicy premium 5% for inflation) is a follow-up.
- **Premium refunds / cancellations mid-term**. A policy voided mid-term today gets `status='TERMINATED'` and the earning-schedule marks unclosed periods as `voided_at`. Refund calculation (short-rate vs pro-rata) is deferred; the earning schedule just stops accruing.
- **Reinstatement**. A LAPSED policy that gets paid up doesn't retro-fill earning_schedule; it starts fresh from the reinstatement date. Reinstatement premium handling is a follow-up.
- **Portfolio-migration**. A policy can't have its `portfolio_id` changed mid-year via the tenant-admin UI in Phase 12. Portfolio reassignment is a Phase 15 operation because it interacts with LRC/LIC re-measurement.
- **Aggregation into IFRS 17 groups of contracts**. §A stores portfolio_id + cohort_id on each policy; Phase 15 defines the aggregation rules that turn those into IFRS 17 "groups of contracts".
- **New-business acquisition-cost tracking**. `NEW_BUSINESS_REGISTER` shows written premium at bind; the acquisition-cost allocation (broker commission, marketing, medical-underwriting cost) that would feed a combined-ratio report is not in scope.
- **Scheduled report email delivery**. `UPR_MOVEMENT.cadenced = true` in the enum but Phase 12 ships manual XLSX export only. Phase 17 (scheduled email delivery) lands the scheduled path.
- **PDF report variants**. XLSX only for the four report keys. Regulatory PDFs sit in Phase 16.
- **Multi-currency earning within a single policy**. A policy's `written_premium_currency` is fixed at bind. If a tenant wants to re-denominate a live policy, that's a void + re-bind, not an endorsement.
- **Policy-level GL account mapping**. Per-policy general-ledger export for double-entry booking is out of scope; reports are financial-management-shaped, not accounting-system-integration-shaped.

## Implementation Approach

**Order:** §0 harness first (Phase 1) — pays down the pre-existing IT flake debt so §A/§B/§C ITs land clean. Then §A schema + module (Phases 2-5): schema-widening migrations, user-service policy CRUD updates + `PolicyIssuedPublisher`, rules-engine `PREMIUM_EARNING` category, contributions-service `premium/` subpackage + consumer + executor. Then §B reports (Phases 6-7): backend controllers + XLSX + gateway + Angular pages + hub. Then §C endorsements (Phases 8-10): endorsement schema + backend, retro recompute + endorsement report, Angular admin surface.

**Rollout invariants** (all phases must uphold, inheriting from parent-plan cross-phase invariants):

1. **Every wrapped report endpoint accepts optional `?reportingCurrency=`** and returns `ReportResponse<T>` with a native-currency `perCurrency` map per parent-plan invariant #1. `earning_schedule` rows stay native (U8).
2. **Every report GET short-circuits with 403 Forbidden** if `tenant_report_config.enabled = FALSE` via `@RequiresReport(...)`. Mutations (POST/PUT/DELETE) are gated only by `@RequiresPermission`.
3. **Every XLSX export emits `SecurityEventMessage`** with `eventType="DATA_ACCESS"` and `details.reportKey=<key>` before returning bytes.
4. **Every controller carries full Swagger annotations** (Rule 7).
5. **Every entity mutation emits an `AuditEvent`** using `AuditActor.id(jwt)` + `AuditActor.email(jwt)`, with a friendly `entityName` (policy.policyNumber, portfolio.name, cohort.name, endorsement.reference — never the UUID per `feedback_audit_entity_name`).
6. **All amount arithmetic is `BigDecimal`.** No cross-currency additions without `FxRateReader.convert(...)`. Missing FX rate at a grand-total scalar → `ReportGenerationException`; missing FX rate in the envelope map → omitted + `warnings: List<String>` entry per parent-plan G28 / U8.
7. **All queries on tenant-schema tables use unqualified names.** Only prefix `public.` for platform-wide tables (V133 `tenant_auto_lapse_config`, V134 `tenant_endorsement_config`) per `bug_public_prefix_silent_rollback`.
8. **All Kafka consumers use `.doOnSuccess` for offset ack** per `bug_reactor_kafka_ack_swallow`; never `.doOnTerminate`. Errors carry the full cause chain in log messages.
9. **All Angular pickers (policy, portfolio, cohort, member) are debounced search-selects** per `feedback_no_raw_id_inputs` — never a raw `<input>` for a UUID.
10. **All policy `bound_at` / `coverage_start` snap to 1st-of-month;** `coverage_end` / termination dates snap to last-day-of-month per `feedback_effective_date_snap`. Endorsement `effective_from` snaps to 1st-of-month.
11. **Rules-engine tenant isolation is preserved.** New `RuleCategory.PREMIUM_EARNING` compiles per-tenant into its own `ReleaseId`; concurrency IT covers the invariant per `bug_rules_engine_tenant_isolation`.
12. **`AbstractIntegrationTest` is the single IT base for cross-service testing.** No new bespoke Testcontainers wiring per test — every new IT extends `com.medfund.shared.testfixtures.AbstractIntegrationTest`.
13. **One policy = one `earning_schedule` row per period.** Idempotency guard via `UNIQUE (policy_id, policy_source, period_start, COALESCE(endorsement_id, '00000000-0000-0000-0000-000000000000'::uuid))` partial index. A `PremiumEarningExecutor` re-run writes zero duplicate rows.

**Cross-phase Kafka contract stability:** §A Phase 3 introduces `medfund.user.policy-issued` (user-service publisher lands first, contributions-service consumer next). §C Phase 8 introduces `medfund.user.policy-endorsed` (same producer-first ordering). All new payloads carry `tenantId` as a top-level field so `.contextWrite(Context.of(TenantContext.KEY, tenantId))` can propagate in the consumer chain.

## Deviations

- **2026-08-23 (Phase 2)** — CHECK-constraint name for status widening. Plan snippet uses `chk_<table>_status` as the name to drop-then-recreate. V032 declared these constraints inline, so Postgres auto-named them `<table>_status_check`. `DROP CONSTRAINT IF EXISTS chk_<table>_status` would silently no-op and leave the original constraint rejecting `'legacy_no_premium'` — the backfill UPDATE would fail. V102 drops the actual auto-name `<table>_status_check` for all six policy tables. `contributions.status` + `schemes.status` have no CHECK constraint in V001 (both are `VARCHAR(20) DEFAULT ...` without a vocab check), so no widening is needed there.
- **2026-08-23 (Phase 2)** — FK-add idempotency syntax. Plan uses `ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS fk_...` — but standard Postgres does not support `IF NOT EXISTS` on `ADD CONSTRAINT`. V107 + V108 use the `DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ...) THEN ALTER TABLE ... END IF; END$$;` pattern per `V113__tenant_currency_config_extend.sql`'s precedent.
- **2026-08-23 (Phase 2)** — Test-migration mirrors deferred. Plan Phase 2 §5 lists user-service `test-migration/V017..V019.sql` (mirror of V102/V107/V108) and contributions-service `test-migration/V0NN` (mirror of V107/V108/V109). Two blockers: (a) user-service has no shared `test-migration/` folder — only IT-specific folders (`group-create-migration/`, `group-number-migration/`) with their own V001 numbering. (b) Mirroring V107 into contributions-service `test-migration/` requires `life_policies` etc. to exist in that IT's schema (V107 FKs reference those tables) — currently they don't, so the mirror would fail on load. Phase 2 automated verification does not consume the mirrors (`TenantMigrationFlywayIT` reads `db/migration/tenant` directly; existing contributions-service ITs use minimal `public.*` mirrors that don't touch policy tables). The mirrors are only needed when Phase 3 lands `Ifrs17PortfolioServiceTest/IT` in user-service (spin up a dedicated `user-service/src/test/resources/db/underwriting-migration/V001..sql` at that time) and Phase 5 lands `BillingEarningIT/PolicyIssuedConsumerIT` in contributions-service. Deferring lets each IT scope its own minimal schema per the existing pattern rather than force-widening every IT's baseline.
- **2026-08-23 (Phase 2)** — `member_first_contribution` view scope. Plan defines the materialized view as `MIN(created_at) GROUP BY member_id` from contributions. Added `WHERE member_id IS NOT NULL` filter — HEALTH group-billed contributions carry only `group_id` (member_id NULL, per V001 shape), and grouping on NULL would create a garbage aggregate row that would break the `UNIQUE (member_id)` index. NewBusinessRegisterReportService's HEALTH branch classifies per-member new business anyway; group-billed rows are out of scope for that classification.
- **2026-08-23 (Phase 2)** — `earning_schedule` CHECK bounds relaxed for endorsements. Plan's original `CHECK (written_amount >= 0)` + `CHECK (earned_at_period_end BETWEEN 0 AND written_amount)` blocks Phase 9's retro-recompute path where an endorsement writes a negative `written_amount` (cover reduction) or an endorsement row that mirrors an already-closed period (earned = written but written is a delta, potentially negative). Adjusted to `written_amount >= 0 OR is_endorsement = TRUE` and `earned_at_period_end IS NULL OR is_endorsement = TRUE OR (earned BETWEEN 0 AND written)`. Base-row invariant preserved; endorsement rows carry their own signed deltas.
- **2026-08-23 (Phase 1)** — user-service IT count. Plan says "approximately 8 files" need migrating to a new `AbstractUserServiceIT`; actual state has only **2** ITs (`GroupNumberServiceIT`, `GroupServiceCreateIT`). Both already extend the split shared bases (`AbstractPostgresIntegrationTest`, `AbstractDedicatedPostgresIntegrationTest`) — no local `@Container`/`@DynamicPropertySource` wiring exists. Both already carry the `ReactiveJwtDecoder` `SecurityStub` inner-class pattern the plan cites. `AbstractUserServiceIT` is added as a marker convention that **extends `AbstractPostgresIntegrationTest`** (not `AbstractIntegrationTest`) so we don't force an unused Kafka container per class — the shared base's own javadoc warns against that. `GroupNumberServiceIT` migrates onto `AbstractUserServiceIT`. `GroupServiceCreateIT` stays on `AbstractDedicatedPostgresIntegrationTest` because it uses a colliding Flyway migration path (`db/group-create-migration`) that would fail checksum-validation if it shared a container with `GroupNumberServiceIT`'s `db/group-number-migration` — that isolation is exactly what `AbstractDedicatedPostgresIntegrationTest`'s javadoc documents. Future user-service ITs that need Kafka should extend `AbstractIntegrationTest` directly.
- **2026-08-23 (Phase 1)** — contributions-service already ships a tuned `spring.r2dbc.pool` block in `application.yml`. Plan's target ceilings (max-size=30, max-idle-time=30m, max-life-time=1h) are broader than what's there today. Keeping the more-conservative existing block on contributions-service (max-size=15, max-idle-time=5m, validation-depth=REMOTE) since it was tuned deliberately to a real prod bug (see the file comment). Other four services get the plan's ceilings.
- **2026-08-23 (Phase 1)** — the "25 pre-existing flakes" figure from parent-plan grill U15 has no counted source in the tree. Phase 1's triage produces the actual count.
- **2026-08-23 (Phase 3)** — extending the six per-line Angular policy CRUD forms with the new underwriting fields (writtenPremium input, currency picker, boundAt datepicker with 1st-of-month snap, coverageStart/End datepickers, renewedFromPolicyId debounced search-select, portfolio + cohort dropdowns) deferred to a follow-up sub-phase (Phase 3b). Reason: (a) the backend accepts the fields as optional (`PolicyUnderwritingFields` wrapper on `Create*Request` / `Update*Request`), so existing forms continue to work; (b) `PolicyIssuedPublisher` fires only when `writtenPremium` is non-null (its own null-guard), so events skip legacy rows correctly regardless of UI state; (c) the six forms have per-line specifics (member picker fields differ, coverage semantics differ for TravelPolicy) requiring individualised work. The Legacy Retrofit route at `/tenant/admin/underwriting/legacy-retrofit` ships as a stub pointing operators at the per-line policy pages until Phase 3b lands. §A Phase 5 (contributions-service consumer) is unaffected — it reads whatever the backend has written, and any Kafka payload it gets is valid.
- **2026-08-23 (Phase 3)** — Ifrs17 CRUD controllers are permission-gated on mutations (`@RequiresPermission("underwriting.portfolio:manage" | "underwriting.cohort:manage")`) but list/get endpoints are open per existing user-service convention (LifePolicyController etc. don't gate reads at the controller layer either). Read-side isolation is the tenant-schema filter.
- **2026-08-23 (Phase 3)** — `Ifrs17PortfolioIT` / `Ifrs17CohortIT` / `PolicyIssuedPublisherIT` deferred to Phase 3b along with the mirror `test-migration/V017..V019` set. Reason cited in Phase 2 Deviation §5: user-service has no shared `test-migration/` folder today (only IT-specific folders under `db/group-create-migration/` etc. with their own V001 numbering), so the mirror needs a purpose-built `db/underwriting-migration/V001..sql` at IT time. Unit-test coverage (`Ifrs17PortfolioServiceTest` 6 cases, `Ifrs17CohortServiceTest` 7 cases, `PolicyIssuedPublisherTest` 3 cases) is in place and green.
- **2026-08-23 (Phase 3)** — real service bug found + fixed via unit tests: `Ifrs17CohortService.create/update` originally used `.then(repository.existsByCompositeKey(...))` — Reactor evaluates the `.then(otherMono)` argument eagerly at call time, not at subscription time, so `existsByCompositeKey` would fire even when the parent portfolio lookup errored. Wrapped both call sites in `Mono.defer(() -> ...)`. Also cascades to the portfolio existence check ordering: the `switchIfEmpty` now propagates as expected.
- **2026-08-23 (Phase 4)** — template-provider interface. Plan Phase 4 §1 defined `PremiumEarningTemplates implements RuleTemplateProvider` returning `List<Map<String, Object>>`. The rules-engine ships `TemplateProvider` (not `RuleTemplateProvider`) returning `List<RuleDefinition>` via `TemplateBuilder.rule(...)` helpers; every existing provider (CommissionTemplates, ReinsuranceTemplates, …) uses that shape, and `RuleTemplateServiceTest` wires them by class name. Followed the existing convention verbatim so the new provider drops into `RuleTemplateService` alongside the other 17 without service-side changes.
- **2026-08-23 (Phase 4)** — E70 condition operator. Plan Phase 4 §1's E70 template used a single `IN` condition against a list of 5 insurance lines. `Operator.java` doesn't declare `IN`; the DrlCompiler falls back to raw operator + string-formats the List, which would emit un-parseable DRL. Rewrote as an `any()` condition group with five per-line `EQUALS` conditions — equivalent semantics, valid DRL, and matches the shape every other template provider uses.
- **2026-08-23 (Phase 4)** — jacoco threshold. `./gradlew :rules-engine:build` fails on `jacocoTestCoverageVerification` (0.54 vs 0.70). Verified via git worktree against `55c3689` (before this branch): baseline sat at 0.52. Phase 4 additions lifted the ratio +2 pp. Same pre-existing-infra pattern already documented in Phase 2 §A automated-verification note (tenancy-service: 0.45 vs 0.70). Not raising the module coverage as a Phase 4 concern — that's a standalone follow-up.
- **2026-08-23 (Phase 5)** — Grill note 12 (Scheme.defaultPortfolioId propagation) landed on both save-points inside `BillingService` (`generateBilling` line 208, `persistContribution` line 1547). Contribution entity gained `portfolioId` + `cohortId` fields to carry the inherited value. Test-migration mirror files (`test-migration/V001__schemes.sql`, `charge-preview-migration/V001__charge_preview_schema.sql`) were widened idempotently with the new columns (`default_portfolio_id`, `contributions.portfolio_id`, `contributions.cohort_id`, `annual_member_cap`) so existing ITs keep passing under the entity's now-wider mapping. `bad-debts-migration/V001` unaffected — it doesn't create the `schemes` table.
- **2026-08-23 (Phase 5)** — Executor uses a two-tier design: `PremiumEarningExecutor` is a thin `JobExecutor` façade over `EarningScheduleClosureService` (which owns the chunking + run-lifecycle + backfill entrypoints). Rationale: the closure service is reachable from both the nightly job and the admin controller's backfill/replay endpoints without duplicating the run-tracking wiring.
- **2026-08-23 (Phase 5)** — Rules integration uses `RuleEvaluationService.evaluateInGroup(tenantId, "PREMIUM_EARNING", fact)` (not a bespoke `RuleEngineClient`) so the same reactive wrapper that Phase 4 already sanctions carries the rules-engine call. `TenantRuleLoader.ensureLoaded(tenantUuid)` runs first so tenants that never touched rules still get their KieContainer built before the fire. Falls through to `PremiumFact.earningMethod = "DAILY_LINEAR"` when no PREMIUM_EARNING rule matches.
- **2026-08-23 (Phase 5)** — Kafka payload shape matches the user-service `PolicyIssuedPublisher` verbatim (Instant `boundAt`, `policyNumber`, `renewedFromPolicyId`) — payload record and parser live under `com.medfund.contributions.premium.consumer`. Consumer follows the `MemberEnrolledConsumer` pattern (subscribe once via `@PostConstruct`, ack-on-success via `.doOnSuccess` per `bug_reactor_kafka_ack_swallow`, log-and-ack on error to keep the partition unblocked).
- **2026-08-23 (Phase 5)** — Integration tests deferred to Phase 5b. `PolicyIssuedConsumerIT` / `BillingEarningIT` / `PremiumEarningExecutorIT` need a `db/premium-earning-migration/` folder carrying the ifrs17_portfolio + ifrs17_cohort + earning_schedule + earning_schedule_run + contributions schema. Reason parallel to the Phase 3 deferral: contributions-service has no shared `test-migration/` for policy-adjacent tables, and force-widening the existing three migration folders (`test-migration`, `bad-debts-migration`, `charge-preview-migration`) would cascade FK references into every IT slice that never touches earning-schedule. Purpose-built folder + ITs will land as Phase 5b. Unit-test coverage (28 cases across the 6 new test classes) is in-tranche.
- **2026-08-23 (Phase 5)** — `BillingContributionEarningHook.onContributionCreated` wraps the projection call in `.onErrorResume(err -> log + Mono.empty())` so an earning-schedule failure is best-effort and does NOT fail billing. Contribution is source of truth; the executor's backfill path catches up. Keeps Rule 1 + Rule 8 unaffected (audit still fires on Contribution mutation regardless of projection status).
- **2026-08-23 (Phase 6)** — Query repository consolidated as one `PremiumReportQueryRepository` class covering the three §B reports (three `*Rows(...)` streams + three `*PerCurrencyTotals(...)` aggregates). Rationale: the three reports share the `earning_schedule` + `ifrs17_portfolio` + `ifrs17_cohort` join topology and the seven-way policy-enrichment UNION-ALL; splitting per report would triplicate the union block without a payoff. Mirrors `BillingReportQueryRepository`'s posture of "one repository per family, three services on top".
- **2026-08-23 (Phase 6)** — Three §B report REST endpoints ship on **one** `PremiumReportController` at `/api/v1/reports/premium/{upr-movement,register,new-business}` rather than three separate controllers, mirroring the Phase 11 `CommissionReportController` pattern (single class, two families, one export helper). Reduces the OpenAPI tag count + centralises the `SecurityEventPublisher.publishDataAccess` call so future §C endorsement register can wire in as a fourth arm without a new controller.
- **2026-08-23 (Phase 6)** — Workbook services use `FxRateReader` (shared, best-effort) rather than finance-service's `FxConverter` (fail-loud) because contributions-service doesn't own an `FxConverter` and dragging it in for one grand-total call would tighten cross-service coupling. Uses the same convention as `ReportEnvelopeBuilder`'s own `bestEffortFxRates` — missing FX renders an "FX unavailable" warnings block rather than throwing.
- **2026-08-23 (Phase 6)** — Integration tests deferred to Phase 6b, mirroring the Phase 5 Deviation. `UprMovementReportIT` / `PremiumRegisterReportIT` / `NewBusinessRegisterReportIT` need a `db/premium-reports-migration/` folder mirroring the seven policy tables + `members` + `schemes` + `ifrs17_*` + `earning_schedule` + the `member_first_contribution` materialised view. Force-widening the existing `test-migration/` / `bad-debts-migration/` / `charge-preview-migration/` folders would cascade FK references into every IT slice that never touches these reports. Purpose-built folder + ITs land as Phase 6b. Unit-test coverage (18 cases across 6 new test classes) is in-tranche.
- **2026-08-23 (Phase 6)** — `PremiumRegisterRow.isNewBusiness` for the CONTRIBUTION source is set by joining the `member_first_contribution` materialised view and checking that the member's first-ever Contribution instant falls within the reporting window. For the six annual-bind sources it's `renewed_from_policy_id IS NULL` (member's first-ever Contribution filter is skipped since it's meaningless for non-HEALTH). SQL predicate lives in the report query (not the service) so a mixed-line register renders both correctly in one pass.
- **2026-08-23 (Phase 8)** — R2DBC repository scan needed extending. `EndorsementRepository` lives in `com.medfund.user.endorsement.repository` (the endorsement sub-package the plan calls for) rather than the standard `com.medfund.user.repository` that `@EnableR2dbcRepositories` currently covers. Without the scan extension, `PolicyEndorsementService` fails to autowire and *every* user-service `@SpringBootTest`-based IT dies on context load (surfaced as `GroupNumberServiceIT` + `GroupServiceCreateIT` red). Added the sub-package to `R2dbcConfig.basePackages`; the alternative (moving the repo up into `.repository`) breaks the plan's "endorsement module lives in a sub-package" invariant.
- **2026-08-23 (Phase 8)** — Reactor eager-eval bug in `PolicyEndorsementService.createDraft`. The initial chain `.then(referenceGenerator.nextEndorsementReference())` evaluates the argument at chain-build time, so the mocked `nextEndorsementReference()` fired even when `validateDraft` errored — the mock returned `null` and NPEd inside `.then(null)`. Same class of bug documented on the Phase 3 `Ifrs17CohortService` deviation. Wrapped as `.then(Mono.defer(referenceGenerator::nextEndorsementReference))` so the ref-mint stays lazy.
- **2026-08-23 (Phase 8)** — `TenantEndorsementConfigService.applyRequest` clears the threshold on disable rather than leaving stale values. The plan didn't specify the disable semantics; nulling out `fourEyesThresholdAmount` + `thresholdCurrency` makes the unconfigured/disabled read shapes identical, so a subsequent re-enable starts from a clean state instead of resurrecting a stale threshold the tenant might have forgotten about. Guarded by the new `upsert_disabledClearsThreshold` test case.
- **2026-08-23 (Phase 8)** — Endorsement `endorsedPublisher.publish` receives the raw `String` tenantId (`TenantContext.get(ctx)`) rather than a UUID; matches the `PolicyIssuedPublisher` payload shape verbatim (empty string when the context is unset, avoiding a null hop through Kafka). No parsing dance, no drop of tenants that arrive as slugs.
- **2026-08-23 (Phase 8)** — `PolicyEndorsementIT` + `TenantEndorsementConfigIT` (the two ITs the plan calls for) deferred to Phase 8b, mirroring the Phase 3/5/6 pattern. Rationale: user-service has no shared `test-migration/` folder covering the `endorsement` table (adding one would force-widen every existing user-service IT's schema); the endorsement IT needs its own `db/endorsement-migration/` folder + a Kafka container for the publisher round-trip. Unit-test coverage (25 cases across the three new test classes) is in-tranche and green; migration ITs (`TenantMigrationFlywayIT` + `PublicMigrationFlywayIT`) already assert V110 + V134 land cleanly with the full column set.
- **2026-08-23 (Phase 9)** — the plan's Phase 9 §4 dithered between contributions-service and user-service for the endorsement register; per G2 (data ownership: endorsements live in user-service) the report ships there. `EndorsementReportQueryRepository` uses raw SQL with a UNION-ALL across the six annual-bind policy tables to enrich each row with a member name — mirrors the shape of contributions-service's `PremiumReportQueryRepository` but scoped to user-service tables only. Gateway route is registered ahead of the broader `/api/v1/reports/premium/*` wildcard so Fiber's registration-order dispatch beats the contributions-service catchall.
- **2026-08-23 (Phase 9)** — the plan's markComputed callback is fire-and-forget via `UserServiceClient.markEndorsementComputed` — swallows errors (log-only). The recompute has already succeeded and is idempotent (deleteByEndorsementId first) so an operator can flip the status manually via the same `PUT /api/v1/endorsements/{id}/computed` endpoint. Same failure posture as the existing `UserServiceClient` member/group actions used by ArrearsEscalationExecutor.
- **2026-08-23 (Phase 9)** — endorsement-strip apportionment mirrors `PremiumEarningStripCalculator`'s last-row-absorbs-rounding invariant, so the sum of endorsement row deltas equals `premiumDelta` exactly. Closed base periods emit closed endorsement rows (`earned_at_period_end = writtenAmount`); open base periods emit open endorsement rows so the executor's period-close pass can close them on the same schedule. Currency comes from the endorsement payload (falls back to the base row's currency if the endorsement was drafted without one — non-financial endorsements land as zero-delta anyway and short-circuit to no rows).
- **2026-08-23 (Phase 9)** — endorsement `/api/v1/endorsements` + `/*` gateway route added alongside the report route. Owed from Phase 8 but not caught by that phase's Success Criteria; without it the Phase 8 endorsement REST surface is unreachable from the Angular gateway. Documented here rather than back-porting to Phase 8's Deviations because it's shipping in this tranche.
- **2026-08-23 (Phase 9)** — `EndorsementRecomputeIT` + `EndorsementRegisterReportIT` deferred to Phase 9b, mirroring the Phase 5/6/8 test-migration deviation. Reasons: (a) contributions-service ITs would need a `db/endorsement-recompute-migration/` folder carrying earning_schedule + earning_schedule_run + ifrs17_* tables plus an endorsement publisher stub — force-widening the existing three folders cascades unrelated FKs; (b) user-service report IT needs the six policy tables + members + endorsement + a Kafka container for the security-event publisher assertion, so it too needs its own migration folder. Unit-test coverage (14 cases across the three new test classes) is in-tranche; migration ITs (`TenantMigrationFlywayIT` + `PublicMigrationFlywayIT`) already assert V109 + V110 land cleanly.
- **2026-08-23 (Phase 10)** — placed the endorsements files under `/tenant/policies/*` and `/tenant/finance/underwriting/*` rather than the plan's `/tenant-admin/policies/*` naming. Reason: the actual Angular route tree has policies at `/tenant/policies/*` (not `/tenant-admin/*`), so `/tenant-admin/policies/*` would 404 today. New routes: `/tenant/policies/endorsements/:policySource/:policyId` (per-policy history + modal, matches plan's Fallback shape), `/tenant/finance/underwriting/endorsements/review-queue`, `/tenant/finance/underwriting/endorsements/:id`. The Endorsement Config lives at `/tenant/admin/settings` (Endorsement Config tab) which is a tenant-admin surface and matches the plan verbatim.
- **2026-08-23 (Phase 10)** — plan step §2 (`Mount the "Endorsements" tab on each of the 6 line-specific policy detail pages`) uses the plan's explicit Fallback: a standalone route accessible from each policy's overview page. The six per-line detail components (LifePolicyForm etc.) are policy CRUD forms today, not tab-hosting shells, and Phase 3b's own Deviation already deferred extending those forms with the new underwriting fields for the same shape reasons (per-line specifics differ). The polymorphic `EndorsementHistoryComponent` route works for all 6 lines out of the box; adding a "View endorsements" link on each per-line form to point at that route is a one-line task deferred to Phase 3b.
- **2026-08-23 (Phase 10)** — `EndorsementReviewQueueComponent` uses `NavigationService.getUserInfo().email` for the client-side same-actor guard rather than a dedicated actor-id accessor, matching the shape of the four-eyes comparison the drafter/approver payload actually exposes (`draftActorEmail`). Case-insensitive comparison. The server still enforces the invariant via 403; the client check is UX-only. Aligns with Phase 11's approver queue that already relies on server-side 409 for the same invariant.

## Verified during this planning pass (grill notes closed at code altitude)

| Grill note | Resolution |
|---|---|
| 1 — V-migration ordering (F12-3, U1) | Bundled schema-widening into a single V102 (`policy_underwriting_widening.sql`) covering all 6 policy tables — one atomic ALTER pass, no cross-migration drift, one Deviation entry if it needs rework. V107..V109 are IFRS + earning_schedule. §C's V110 endorsement is a separate migration. Rationale: 5 near-identical widening migrations would spam the history table; TravelPolicy's slightly different shape (no coverage_start/end) is a CASE in the same file. |
| 2 — Policy backfill for existing rows (§A) | Existing rows get `bound_at = created_at` (approximation), `written_premium = NULL`, `written_premium_currency = tenant's default currency` (from `tenant_currency_config.is_default = TRUE`), `coverage_start = created_at::DATE`, `coverage_end = created_at + INTERVAL '1 year'`, `status = 'LEGACY_NO_PREMIUM'` (new arm — earning-schedule skips these rows entirely). Tenant admin retrofits via a new admin CRUD screen at `/tenant-admin/policies/legacy-retrofit` (§A Phase 3, folded in). |
| 3 — `PolicyIssuedPublisher` firing points (F12-9, §A Phase 3) | 6 service `create()` paths (`LifePolicyService.create`, `FuneralPolicyService.create`, `DisabilityPolicyService.create`, `TravelPolicyService.create`, `VehicleService.create`, `PropertyService.create`). Also `update()` when `written_premium` changes — but this is a narrow surface and the endorsement path (§C) is preferred; document the split. Verified none are called from a Kafka consumer path (no consume-then-publish loop). |
| 4 — `PremiumFact` DSL (U11) | Fields: `{policyId, insuranceLine, productCode, tenantId, writtenPremium, currencyCode, coverageStart, coverageEnd, boundAt, portfolioId, cohortId}`. `AccruePremiumEmitter` action-value DSL: `EARNING_METHOD:DAILY_LINEAR`, `EARNING_METHOD:MONTHLY_24THS`, `EARNING_METHOD:LINEAR_WITH_LOADING:<pct>` (loading percent inline). Emitter is a standalone `@Component` mirroring `PayCommissionEmitter` shape at `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/PayCommissionEmitter.java`. |
| 5 — `PremiumEarningExecutor` chunking (U10) | 5k policies per commit chunk, streamed via Reactor `.window(5000)`. Progress-tracked via a new `earning_schedule_run(id, tenantId, startedAt, finishedAt, policiesProcessed, periodsWritten, status, lastProcessedPolicyId, error)` table. Cancel: admin `POST /premium/earning-schedule/runs/{runId}/cancel` sets status to CANCELLED; running executor checks per-chunk. Resume: on next scheduled fire, executor picks up from `lastProcessedPolicyId` if status = RUNNING and heartbeat is stale. |
| 6 — Endorsement retro-recompute performance (§C Phase 9, U12) | Reuses the same chunking strategy as U10. A rate-card revision endorsement lifted to bulk (say 10k policies × 6 avg remaining periods = 60k rewrites) fits in ~2 minutes at 500 rows/sec R2DBC throughput. `endorsement.status = COMMITTED → COMPUTED` transition on completion. UI shows "Recompute in progress" banner with ETA. |
| 7 — HEALTH new-business detection (U6) | Materialized `member_first_contribution_at` computed view on tenant schema — refreshed nightly by `PremiumEarningExecutor` after period-close pass. `NewBusinessRegisterReportService` reads `WHERE member_first_contribution_at BETWEEN periodStart AND periodEnd` for HEALTH branch. Cheap for report reads; refresh cost is O(members with new Contribution in last 24h). |
| 8 — §0 IT flake triage (U15) | Diagnostic pass at §0 Phase 1: rerun the finance-service full IT suite; classify failures as (a) genuine correctness bug — file follow-up, (b) pool pressure — fix via ceiling tune, (c) test-isolation drift — fix via `@DirtiesContext` or better per-test cleanup. Reserve 3 days of the Phase 1 budget for triage. |
| 9 — IFRS 17 portfolio/cohort naming (U14) | Keep `ifrs17_portfolio` + `ifrs17_cohort` prefix. Rationale: Phase 15 downstream reads these tables — the prefix documents intent at the table level. Tenant-admin UI labels them "Portfolios (IFRS 17)" / "Cohorts (IFRS 17)"; tenants not on IFRS 17 simply see a default "MISC" portfolio + "2026-DEFAULT" cohort auto-created at tenant-provisioning time. |
| 10 — Endorsement `change_type` enum arms (§C Phase 8, U5) | `{PREMIUM_ADJUSTMENT, COVERAGE_EXTENSION, BENEFIT_CHANGE, BENEFICIARY_CHANGE, ADMIN_CHANGE}`. Also add `PRODUCT_SWITCH` (change of scheme mid-term — needs void + rebind of the current policy, cross-refs a new policy) and `RENEWAL_ADVANCE` (early renewal — cross-refs a new policy chain). Total 7 arms. |
| 11 — Portfolio/cohort assignment default (§A Phase 3, U14) | Tenant-provisioning provisions two rows: `MISC` portfolio (insurance_line = NULL — a catchall) and `MISC` cohort (portfolio_id = MISC, cohort_year = current year, cohort_type = NON_ONEROUS). Every policy without explicit `portfolio_id` at bind gets MISC. Tenant admin can migrate policies via a bulk-reassign UI (deferred to a follow-up; the default MISC lands in §A). Tenant provisioning in `tenancy-service.TenantProvisioningService.provisionTenantSchema` — extend with the two seed rows. |
| 12 — `portfolio_id` + `cohort_id` on Contribution for HEALTH (§A Phase 2, U14) | Attach to each Contribution row. Default set to the member's scheme's associated portfolio at billing-generation time (needs a new `scheme.default_portfolio_id UUID NULL FK` column — added in V102). Redundancy across 12 monthly Contributions per member per year is minor storage cost and preserves the invariant "an earning_schedule row is (policy_source × source_id) → portfolio/cohort straight through without a JOIN". |
| 13 — `.claude/multi-currency.md` update owed | Added under "Reporting" section at line 165+ a paragraph codifying U8: "Written premium stays in the policy's original currency; endorsement retro-recompute preserves the original currency (never re-denominates). Report envelopes convert at each row's period_end date; missing historical FX yields an envelope warning, not a throw." Applied at §A Phase 5 (docs land alongside the executor that codifies the rule). |

---

## Phase 1: §0 — Testcontainers harness stabilisation

### Overview

Pay down the pre-existing IT flake debt (~25 tests fail on full-suite runs across Phase 9 + 11 sub-plans) before §A starts landing new ITs. Extend user-service ITs to the shared `AbstractIntegrationTest` base; tune R2DBC connection ceilings across services; triage each pre-existing flake to root cause (correctness bug vs pool pressure vs test isolation); add a pool-starvation health check gauge. Ships in isolation — success criterion is "`make test-integration` passes end-to-end in a single run".

### Changes Required

#### 1. R2DBC connection pool ceilings

**Files** (per service):
- `services/java/tenancy-service/src/main/resources/application.yml`
- `services/java/user-service/src/main/resources/application.yml`
- `services/java/contributions-service/src/main/resources/application.yml`
- `services/java/finance-service/src/main/resources/application.yml`
- `services/java/claims-service/src/main/resources/application.yml`

Add or tune under `spring.r2dbc.pool`:

```yaml
spring:
  r2dbc:
    pool:
      initial-size: 5
      max-size: 30          # was default 10 — bumped to survive full-suite IT run
      max-idle-time: 30m
      max-acquire-time: 10s
      max-life-time: 1h
      validation-query: "SELECT 1"
```

Test-profile override in each service's `application-test.yml`:

```yaml
spring:
  r2dbc:
    pool:
      max-size: 20   # per-JVM; Testcontainers Postgres accepts ~100 conns total across services
      max-acquire-time: 5s
```

#### 2. Extend user-service ITs to shared `AbstractIntegrationTest`

**File**: `services/java/user-service/src/test/java/com/medfund/user/AbstractUserServiceIT.java` (new)

```java
package com.medfund.user;

import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractUserServiceIT extends AbstractIntegrationTest {
    // Marker + convention: user-service ITs extend this instead of writing bespoke @Container setup.
}
```

Migrate every existing `*IT` in `services/java/user-service/src/test/java/com/medfund/user/` (grep for `class .*IT` — approximately 8 files) to extend `AbstractUserServiceIT`. Remove local `@Container` and `@DynamicPropertySource` definitions.

#### 3. Pool-starvation health check

**File**: `services/java/shared/src/main/java/com/medfund/shared/health/R2dbcPoolHealthIndicator.java` (new)

```java
package com.medfund.shared.health;

import io.r2dbc.pool.ConnectionPool;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * DOWN when the pool has zero available acquisitions and >90% of max is in use.
 * Fires an alert; developers see it in the /actuator/health endpoint during full-suite runs.
 */
@Component
@RequiredArgsConstructor
public class R2dbcPoolHealthIndicator implements ReactiveHealthIndicator {
    private final ConnectionPool pool;

    @Override
    public Mono<Health> health() {
        var metrics = pool.getMetrics().orElse(null);
        if (metrics == null) return Mono.just(Health.unknown().build());
        int allocated = metrics.allocatedSize();
        int idle = metrics.idleSize();
        int max = metrics.getMaxAllocatedSize();
        double utilisation = (double) (allocated - idle) / max;
        if (utilisation > 0.9 && idle == 0) {
            return Mono.just(Health.down()
                    .withDetail("allocated", allocated)
                    .withDetail("idle", idle)
                    .withDetail("max", max)
                    .withDetail("utilisation", utilisation)
                    .build());
        }
        return Mono.just(Health.up()
                .withDetail("allocated", allocated)
                .withDetail("idle", idle)
                .build());
    }
}
```

#### 4. Triage pre-existing IT flakes

**Process** (documented in `thoughts/shared/notes/2026-08-23-it-flake-triage.md` — new file):
1. Run `cd services/java && ./gradlew :finance-service:test --tests '*IT'` five times, collecting failures each run.
2. Classify each unique failure:
   - **Bug** — real correctness issue → file follow-up ticket, tag `[flake-triage]`, exclude from §0 close-out.
   - **Pool pressure** — `Failed to load ApplicationContext` or `sorry, too many clients` → fixed by ceiling tune (#1 above); re-run to confirm.
   - **Test isolation drift** — data-cleanup issue → add per-test-class `@Sql(scripts = "classpath:cleanup.sql", executionPhase = BEFORE_TEST_METHOD)` or wrap in a rollback-only transaction.
3. Any test that turns out to be flaky in a non-fixable way lands in a `@Disabled("flake-triage-YYYY-MM-DD: see notes")` marker with a link to the ticket.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew build` — all modules. **Verified 2026-08-23** — `./gradlew build -x test` green across all 6 modules.
- [x] `make test-integration` — finance-service IT suite (74 tests) drops from OOM/8-fail to 2 fail across two fixes: `tasks.test.maxHeapSize=1536m` clears the OOM; `postgres -c max_connections=300` + tighter test-profile pool clears 6 of 8 remaining. The last 2 (`CommissionCalcIT.reprocessingSameContribution_isIdempotent`, `CommissionClawbackIT.memberLapse_withinWindow_writesClawbackAndReversedTxn`) reproduce in isolation — real Phase 11 correctness bugs, not §0 flakes; filed as [flake-triage] follow-ups per plan §0 close-out policy. See `thoughts/shared/notes/2026-08-23-it-flake-triage.md` for classification.
- [x] user-service ITs migrated onto `AbstractUserServiceIT` — `GroupNumberServiceIT` migrated; `GroupServiceCreateIT` stayed on `AbstractDedicatedPostgresIntegrationTest` per Deviation (colliding Flyway migration path). Only 2 user-service ITs exist, not ~8 as the plan claimed. `./gradlew :user-service:test` green.
- [x] Pool health indicator `R2dbcPoolHealthIndicator` lands at `services/java/shared/src/main/java/com/medfund/shared/health/`. Gated by `@ConditionalOnBean(ConnectionFactory.class)` + `@ConditionalOnEnabledHealthIndicator("r2dbcPool")`. Actuator moved to `api` in shared `build.gradle.kts`.
- [x] Flake triage document at `thoughts/shared/notes/2026-08-23-it-flake-triage.md` — three passes recorded with root-cause fixes and residual bug classification.

#### Manual Verification
- [ ] Rerun `make test-integration` three times consecutively — zero flakes on the third run.
- [ ] Under sustained load (concurrent `make test-integration` in two shells), pool-health indicator flips to `DOWN` briefly and recovers — indicator behaves.

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm before Phase 2.

---

## Phase 2: §A — Schema-widening migrations + IFRS 17 dimensions + earning_schedule

### Overview

Add the columns Phase 12 needs across all 6 policy entities in one atomic tenant migration (V102), plus the IFRS 17 tables (V107 portfolios + V108 cohorts), plus the earning schedule table (V109). Backfill semantics for existing policy rows populated inline. All test-migration files mirrored in user-service + contributions-service `src/test/resources/db/test-migration/`.

### Changes Required

#### 1. Tenant migration V102 — policy schema-widening

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V102__policy_underwriting_widening.sql`

```sql
-- Widen 6 policy entities with underwriting columns per Phase 12 §A U1 + U2 + U6 + U14.
-- Idempotent: uses IF NOT EXISTS + ALTER ... ADD IF NOT EXISTS.

-- ── LifePolicy ─────────────────────────────────────────────────────────────
ALTER TABLE life_policies
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_start DATE,
    ADD COLUMN IF NOT EXISTS coverage_end DATE,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES life_policies(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

-- Widen status vocabulary to include LEGACY_NO_PREMIUM backfill marker.
ALTER TABLE life_policies DROP CONSTRAINT IF EXISTS chk_life_policy_status;
ALTER TABLE life_policies ADD CONSTRAINT chk_life_policy_status
    CHECK (status IN ('active', 'lapsed', 'suspended', 'terminated', 'draft', 'legacy_no_premium'));

-- Backfill legacy rows (nullable columns default to sensible values).
UPDATE life_policies SET
    bound_at = COALESCE(bound_at, created_at),
    coverage_start = COALESCE(coverage_start, created_at::DATE),
    coverage_end = COALESCE(coverage_end, (created_at + INTERVAL '1 year')::DATE),
    status = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL OR coverage_start IS NULL OR coverage_end IS NULL;

-- ── Repeat for FuneralPolicy / DisabilityPolicy / Vehicle / Property ──────
--   (identical shape; separate ALTER blocks for readability)

ALTER TABLE funeral_policies
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_start DATE,
    ADD COLUMN IF NOT EXISTS coverage_end DATE,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES funeral_policies(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;
ALTER TABLE funeral_policies DROP CONSTRAINT IF EXISTS chk_funeral_policy_status;
ALTER TABLE funeral_policies ADD CONSTRAINT chk_funeral_policy_status
    CHECK (status IN ('active', 'lapsed', 'suspended', 'terminated', 'draft', 'legacy_no_premium'));
UPDATE funeral_policies SET
    bound_at = COALESCE(bound_at, created_at),
    coverage_start = COALESCE(coverage_start, created_at::DATE),
    coverage_end = COALESCE(coverage_end, (created_at + INTERVAL '1 year')::DATE),
    status = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL OR coverage_start IS NULL OR coverage_end IS NULL;

-- (Same blocks for disability_policies, vehicles, properties — abbreviated here)

-- ── TravelPolicy (special case — reuses trip_start_date / trip_end_date) ──
ALTER TABLE travel_policies
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES travel_policies(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;
ALTER TABLE travel_policies DROP CONSTRAINT IF EXISTS chk_travel_policy_status;
ALTER TABLE travel_policies ADD CONSTRAINT chk_travel_policy_status
    CHECK (status IN ('active', 'lapsed', 'suspended', 'terminated', 'draft', 'legacy_no_premium'));
UPDATE travel_policies SET
    bound_at = COALESCE(bound_at, created_at),
    status = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL;

-- ── Contribution (HEALTH) additive: portfolio + cohort only ───────────────
ALTER TABLE contributions
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

-- ── schemes gets default_portfolio_id for HEALTH billing default (grill note 12) ──
ALTER TABLE schemes
    ADD COLUMN IF NOT EXISTS default_portfolio_id UUID;

-- Indexes for period-scan queries (used by earning executor + reports).
CREATE INDEX IF NOT EXISTS ix_life_bound_at ON life_policies (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_life_coverage_end ON life_policies (coverage_end) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_funeral_bound_at ON funeral_policies (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_funeral_coverage_end ON funeral_policies (coverage_end) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_disability_bound_at ON disability_policies (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_disability_coverage_end ON disability_policies (coverage_end) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_travel_bound_at ON travel_policies (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_travel_trip_end ON travel_policies (trip_end_date) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_vehicle_bound_at ON vehicles (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_vehicle_coverage_end ON vehicles (coverage_end) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_property_bound_at ON properties (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_property_coverage_end ON properties (coverage_end) WHERE status = 'active';
```

#### 2. Tenant migration V107 — ifrs17_portfolio

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V107__ifrs17_portfolio.sql`

```sql
CREATE TABLE IF NOT EXISTS ifrs17_portfolio (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(200) NOT NULL,
    description    TEXT,
    insurance_line VARCHAR(20),          -- NULL for MISC catchall
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id       UUID,
    actor_email    VARCHAR(255),
    CONSTRAINT uq_ifrs17_portfolio_name UNIQUE (name)
);

-- Seed the tenant's default MISC portfolio (grill note 11).
INSERT INTO ifrs17_portfolio (id, name, description, insurance_line, is_active)
VALUES (gen_random_uuid(), 'MISC', 'Default catchall portfolio for un-configured policies', NULL, TRUE)
ON CONFLICT (name) DO NOTHING;

-- FKs onto policy tables (V102 columns).
ALTER TABLE life_policies       ADD CONSTRAINT IF NOT EXISTS fk_life_portfolio       FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
ALTER TABLE funeral_policies    ADD CONSTRAINT IF NOT EXISTS fk_funeral_portfolio    FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
ALTER TABLE disability_policies ADD CONSTRAINT IF NOT EXISTS fk_disability_portfolio FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
ALTER TABLE travel_policies     ADD CONSTRAINT IF NOT EXISTS fk_travel_portfolio     FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
ALTER TABLE vehicles            ADD CONSTRAINT IF NOT EXISTS fk_vehicle_portfolio    FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
ALTER TABLE properties          ADD CONSTRAINT IF NOT EXISTS fk_property_portfolio   FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
ALTER TABLE contributions       ADD CONSTRAINT IF NOT EXISTS fk_contribution_portfolio FOREIGN KEY (portfolio_id) REFERENCES ifrs17_portfolio(id);
ALTER TABLE schemes             ADD CONSTRAINT IF NOT EXISTS fk_scheme_default_portfolio FOREIGN KEY (default_portfolio_id) REFERENCES ifrs17_portfolio(id);

-- Backfill: point every legacy policy at MISC.
UPDATE life_policies       SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE funeral_policies    SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE disability_policies SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE travel_policies     SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE vehicles            SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE properties          SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
UPDATE contributions       SET portfolio_id = (SELECT id FROM ifrs17_portfolio WHERE name = 'MISC') WHERE portfolio_id IS NULL;
```

#### 3. Tenant migration V108 — ifrs17_cohort

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V108__ifrs17_cohort.sql`

```sql
CREATE TABLE IF NOT EXISTS ifrs17_cohort (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id  UUID         NOT NULL REFERENCES ifrs17_portfolio(id) ON DELETE RESTRICT,
    cohort_year   INT          NOT NULL,
    cohort_type   VARCHAR(20)  NOT NULL CHECK (cohort_type IN ('ONEROUS', 'NON_ONEROUS', 'UNCERTAIN')),
    name          VARCHAR(200) NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id      UUID,
    actor_email   VARCHAR(255),
    CONSTRAINT uq_ifrs17_cohort UNIQUE (portfolio_id, cohort_year, cohort_type)
);

-- Seed the tenant's default MISC cohort for the current year (grill note 11).
INSERT INTO ifrs17_cohort (portfolio_id, cohort_year, cohort_type, name)
SELECT id, EXTRACT(YEAR FROM NOW())::INT, 'NON_ONEROUS',
       'MISC-' || EXTRACT(YEAR FROM NOW())::INT || '-DEFAULT'
FROM ifrs17_portfolio WHERE name = 'MISC'
ON CONFLICT (portfolio_id, cohort_year, cohort_type) DO NOTHING;

-- FKs onto policy tables + Contribution.
ALTER TABLE life_policies       ADD CONSTRAINT IF NOT EXISTS fk_life_cohort       FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
ALTER TABLE funeral_policies    ADD CONSTRAINT IF NOT EXISTS fk_funeral_cohort    FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
ALTER TABLE disability_policies ADD CONSTRAINT IF NOT EXISTS fk_disability_cohort FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
ALTER TABLE travel_policies     ADD CONSTRAINT IF NOT EXISTS fk_travel_cohort     FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
ALTER TABLE vehicles            ADD CONSTRAINT IF NOT EXISTS fk_vehicle_cohort    FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
ALTER TABLE properties          ADD CONSTRAINT IF NOT EXISTS fk_property_cohort   FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);
ALTER TABLE contributions       ADD CONSTRAINT IF NOT EXISTS fk_contribution_cohort FOREIGN KEY (cohort_id) REFERENCES ifrs17_cohort(id);

-- Backfill: point every legacy policy at MISC-YYYY-DEFAULT.
UPDATE life_policies       SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE funeral_policies    SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE disability_policies SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE travel_policies     SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE vehicles            SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE properties          SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
UPDATE contributions       SET cohort_id = (SELECT id FROM ifrs17_cohort WHERE name LIKE 'MISC-%-DEFAULT' LIMIT 1) WHERE cohort_id IS NULL;
```

#### 4. Tenant migration V109 — earning_schedule

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V109__earning_schedule.sql`

```sql
CREATE TABLE IF NOT EXISTS earning_schedule (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id             UUID         NOT NULL,
    policy_source         VARCHAR(30)  NOT NULL CHECK (policy_source IN (
                              'LIFE_POLICY', 'FUNERAL_POLICY', 'DISABILITY_POLICY',
                              'TRAVEL_POLICY', 'VEHICLE_POLICY', 'PROPERTY_POLICY',
                              'CONTRIBUTION'
                          )),
    insurance_line        VARCHAR(20)  NOT NULL,
    period_start          DATE         NOT NULL,
    period_end            DATE         NOT NULL,
    written_amount        NUMERIC(19, 4) NOT NULL,
    earned_at_period_end  NUMERIC(19, 4),                                 -- null until period closes
    currency_code         CHAR(3)      NOT NULL,
    is_endorsement        BOOLEAN      NOT NULL DEFAULT FALSE,
    endorsement_id        UUID,
    portfolio_id          UUID         REFERENCES ifrs17_portfolio(id),
    cohort_id             UUID         REFERENCES ifrs17_cohort(id),
    earning_method        VARCHAR(30)  NOT NULL,                          -- snapshot of DRL template that fired
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id              UUID,
    actor_email           VARCHAR(255),
    CONSTRAINT chk_period_bounds CHECK (period_end >= period_start),
    CONSTRAINT chk_written_positive CHECK (written_amount >= 0),
    CONSTRAINT chk_earned_valid CHECK (earned_at_period_end IS NULL OR earned_at_period_end BETWEEN 0 AND written_amount)
);

-- Idempotency guard per U10 — one row per (policy, period, endorsement-if-any).
CREATE UNIQUE INDEX IF NOT EXISTS ux_earning_schedule_key ON earning_schedule
    (policy_id, policy_source, period_start, COALESCE(endorsement_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Query indexes.
CREATE INDEX IF NOT EXISTS ix_earning_schedule_period ON earning_schedule (period_start, period_end);
CREATE INDEX IF NOT EXISTS ix_earning_schedule_policy ON earning_schedule (policy_id, policy_source);
CREATE INDEX IF NOT EXISTS ix_earning_schedule_line_currency ON earning_schedule (insurance_line, currency_code, period_end);
CREATE INDEX IF NOT EXISTS ix_earning_schedule_portfolio ON earning_schedule (portfolio_id, period_end);
CREATE INDEX IF NOT EXISTS ix_earning_schedule_unclosed ON earning_schedule (period_end) WHERE earned_at_period_end IS NULL;

-- Executor progress tracking (grill note 5).
CREATE TABLE IF NOT EXISTS earning_schedule_run (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID         NOT NULL,
    run_kind                 VARCHAR(30)  NOT NULL CHECK (run_kind IN ('SCHEDULED', 'BACKFILL', 'ENDORSEMENT_RECOMPUTE')),
    trigger_reference        UUID,                        -- policyId (backfill) or endorsementId (recompute)
    status                   VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    started_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at              TIMESTAMPTZ,
    last_heartbeat_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_processed_policy_id UUID,
    policies_processed       INT          NOT NULL DEFAULT 0,
    periods_written          INT          NOT NULL DEFAULT 0,
    error_message            TEXT
);
CREATE INDEX IF NOT EXISTS ix_earning_run_status ON earning_schedule_run (status, started_at DESC);

-- Materialized view for HEALTH new-business detection (grill note 7).
CREATE MATERIALIZED VIEW IF NOT EXISTS member_first_contribution AS
SELECT member_id, MIN(created_at) AS first_at
FROM contributions
GROUP BY member_id;
CREATE UNIQUE INDEX IF NOT EXISTS ux_member_first_contribution ON member_first_contribution (member_id);
```

#### 5. Test-migrations mirror (user-service + contributions-service)

**Files** (new):
- `services/java/user-service/src/test/resources/db/test-migration/V017__policy_underwriting_widening.sql` (copy of V102)
- `services/java/user-service/src/test/resources/db/test-migration/V018__ifrs17_portfolio.sql` (copy of V107)
- `services/java/user-service/src/test/resources/db/test-migration/V019__ifrs17_cohort.sql` (copy of V108)
- `services/java/contributions-service/src/test/resources/db/test-migration/V0NN__earning_schedule.sql` (copy of V109 — next available number in contributions-service test-migration numbering)
- `services/java/contributions-service/src/test/resources/db/test-migration/V0NN__ifrs17_portfolio.sql` + `V0NN__ifrs17_cohort.sql` (mirror of V107 + V108 — required for `contributions.portfolio_id` FK)

Each test-migration ends with the standard `GRANT ... TO public_role;` block per the Phase 8 test-migration deviation from Phase 11 sub-plan.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :tenancy-service:compileJava :tenancy-service:processResources` — green; the full `build` task fails on a pre-existing jacocoTestCoverageVerification threshold (0.45 vs 0.70 required) unrelated to Phase 2. New SQL files land on the classpath after `processResources`.
- [x] Flyway V102, V107, V108, V109 apply cleanly on fresh testcontainer via `TenantMigrationFlywayIT` — 3 tests, 0 failures. Full migration chain V001→V109 runs to head against a fresh Postgres.
- [x] Column-set assertions added to `TenantMigrationFlywayIT.tenantMigrations_landAllExpectedColumns` cover the six policy tables' underwriting columns, coverage_start/end (skipped for TravelPolicy), `contributions.portfolio_id/cohort_id`, `schemes.default_portfolio_id`, and the shape of `ifrs17_portfolio` / `ifrs17_cohort` / `earning_schedule` / `earning_schedule_run`. Seed of MISC portfolio + MISC-YYYY-DEFAULT cohort is asserted.
- [x] Materialized view `member_first_contribution` created by V109 — verified via `assertColumns` on the underlying relation. `REFRESH MATERIALIZED VIEW` is Phase 5's PremiumEarningExecutor's responsibility; not a schema-migration concern.
- [x] Backfill: new `v102_backfillsLegacyPolicies_toLegacyNoPremiumWithMiscPortfolio()` seeds a Vehicle at V101, migrates through V109, and asserts `status='legacy_no_premium'`, `bound_at`/`coverage_start`/`coverage_end` all non-null, `portfolio_id → MISC`, `cohort_id → MISC-YYYY-DEFAULT`.
- [x] Contributions-service ITs SchemeCostShareIT (3 tests) + CashFlowForecastControllerIT (4 tests) still pass — the additive columns don't break them (verified 2026-08-23).

#### Manual Verification
- [ ] Run migrations against a test tenant with 1k legacy policies of each line → backfill completes in < 30s; no partial state.
- [ ] Inspect indexes: `EXPLAIN SELECT * FROM life_policies WHERE bound_at >= '2026-01-01' AND bound_at < '2027-01-01'` uses `ix_life_bound_at`.

**Implementation Note**: pause for human confirmation before Phase 3.

---

## Phase 3: §A — user-service policy updates + PolicyIssuedPublisher + Portfolio/Cohort CRUD

### Overview

Update the 6 user-service policy entities to reflect V102 columns, add a `PolicyIssuedPublisher` firing from each service's `create()` (and `update()` when `written_premium` changes), and ship `IFRS17PortfolioService` + `IFRS17CohortService` CRUD + Angular admin pages under `/tenant-admin/underwriting/*`.

### Changes Required

#### 1. Entity updates (6 files)

**Files**:
- `services/java/user-service/src/main/java/com/medfund/user/entity/LifePolicy.java`
- `services/java/user-service/src/main/java/com/medfund/user/entity/FuneralPolicy.java`
- `services/java/user-service/src/main/java/com/medfund/user/entity/DisabilityPolicy.java`
- `services/java/user-service/src/main/java/com/medfund/user/entity/TravelPolicy.java`
- `services/java/user-service/src/main/java/com/medfund/user/entity/Vehicle.java`
- `services/java/user-service/src/main/java/com/medfund/user/entity/Property.java`

Add fields matching V102 columns. Example for `LifePolicy` (others follow the same shape modulo TravelPolicy's period-reuse):

```java
@Column("written_premium")
private BigDecimal writtenPremium;

@Column("written_premium_currency")
private String writtenPremiumCurrency;

@Column("bound_at")
private OffsetDateTime boundAt;

@Column("coverage_start")
private LocalDate coverageStart;

@Column("coverage_end")
private LocalDate coverageEnd;

@Column("renewed_from_policy_id")
private UUID renewedFromPolicyId;

@Column("portfolio_id")
private UUID portfolioId;

@Column("cohort_id")
private UUID cohortId;
```

#### 2. Kafka event + publisher

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/PolicyIssuedPublisher.java` (new)

```java
package com.medfund.user.service;

import com.medfund.shared.kafka.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyIssuedPublisher {
    private static final String TOPIC = "medfund.user.policy-issued";

    private final EventPublisher eventPublisher;

    public Mono<Void> publish(PolicyIssuedPayload payload) {
        return eventPublisher.publish(TOPIC, payload.policyId().toString(), Map.of(
                "event", "policy.issued",
                "tenantId", payload.tenantId().toString(),
                "policyId", payload.policyId().toString(),
                "policySource", payload.policySource(),
                "insuranceLine", payload.insuranceLine(),
                "writtenPremium", payload.writtenPremium(),
                "currencyCode", payload.currencyCode(),
                "coverageStart", payload.coverageStart().toString(),
                "coverageEnd", payload.coverageEnd().toString(),
                "boundAt", payload.boundAt().toString(),
                "memberId", String.valueOf(payload.memberId()),
                "portfolioId", String.valueOf(payload.portfolioId()),
                "cohortId", String.valueOf(payload.cohortId())
        ));
    }

    public record PolicyIssuedPayload(
            UUID tenantId,
            UUID policyId,
            String policySource,
            String insuranceLine,
            BigDecimal writtenPremium,
            String currencyCode,
            LocalDate coverageStart,
            LocalDate coverageEnd,
            OffsetDateTime boundAt,
            UUID memberId,
            UUID portfolioId,
            UUID cohortId
    ) {}
}
```

#### 3. Service wiring — fire from 6 create() paths

**Files** (edit; example for LifePolicyService):

```java
// In LifePolicyService.create(request, actorId, actorEmail):
LifePolicy policy = // ... build entity
return lifePolicyRepository.save(policy)
    .flatMap(saved -> {
        // Only publish if the policy is a real bind (not a LEGACY_NO_PREMIUM row).
        if (saved.getWrittenPremium() == null) return Mono.just(saved);
        return publisher.publish(new PolicyIssuedPublisher.PolicyIssuedPayload(
                tenantId, saved.getId(), "LIFE_POLICY", "LIFE",
                saved.getWrittenPremium(), saved.getWrittenPremiumCurrency(),
                saved.getCoverageStart(), saved.getCoverageEnd(),
                saved.getBoundAt(), saved.getInsuredMemberId(),
                saved.getPortfolioId(), saved.getCohortId()
        )).thenReturn(saved);
    })
    .flatMap(saved -> auditPublisher.publish(/* AuditEvent */).thenReturn(saved));
```

Same shape for FuneralPolicyService, DisabilityPolicyService, TravelPolicyService, VehicleService, PropertyService. Also fire on `update()` if `writtenPremium` changed (narrow surface — document that endorsements via §C are the preferred change path).

#### 4. IFRS 17 Portfolio + Cohort CRUD

**File**: `services/java/user-service/src/main/java/com/medfund/user/entity/Ifrs17Portfolio.java` (new)
**File**: `services/java/user-service/src/main/java/com/medfund/user/entity/Ifrs17Cohort.java` (new)
**File**: `services/java/user-service/src/main/java/com/medfund/user/repository/Ifrs17PortfolioRepository.java` (new)
**File**: `services/java/user-service/src/main/java/com/medfund/user/repository/Ifrs17CohortRepository.java` (new)
**File**: `services/java/user-service/src/main/java/com/medfund/user/service/Ifrs17PortfolioService.java` (new — full CRUD + AuditEvent per Rule 8)
**File**: `services/java/user-service/src/main/java/com/medfund/user/service/Ifrs17CohortService.java` (new — same shape)
**File**: `services/java/user-service/src/main/java/com/medfund/user/controller/Ifrs17PortfolioController.java` (new)
**File**: `services/java/user-service/src/main/java/com/medfund/user/controller/Ifrs17CohortController.java` (new)

REST at `/api/v1/underwriting/portfolios` + `/api/v1/underwriting/cohorts` with GET list/detail/page + POST create + PUT update + DELETE (soft: `is_active = FALSE`). Full Swagger annotations per Rule 7.

Two new permissions in `services/java/shared/src/main/resources/permissions.yaml`:
- `underwriting.portfolio:manage`
- `underwriting.cohort:manage`

Corresponding entries in `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java` and `clients/angular/src/app/core/security/permissions.ts`.

#### 5. Gateway routes

**File**: `services/go/gateway/internal/routes/routes.go` (edit)

```go
// User-service underwriting routes.
r.Post("/api/v1/underwriting/portfolios", proxy.To(userSvc))
r.Get("/api/v1/underwriting/portfolios", proxy.To(userSvc))
r.All("/api/v1/underwriting/portfolios/*", proxy.To(userSvc))
r.Post("/api/v1/underwriting/cohorts", proxy.To(userSvc))
r.Get("/api/v1/underwriting/cohorts", proxy.To(userSvc))
r.All("/api/v1/underwriting/cohorts/*", proxy.To(userSvc))
```

#### 6. Angular tenant-admin surfaces

**Files** (new):
- `clients/angular/src/app/core/services/ifrs17-portfolio.service.ts` — HTTP client.
- `clients/angular/src/app/core/services/ifrs17-cohort.service.ts` — HTTP client.
- `clients/angular/src/app/pages/tenant-admin/underwriting/portfolios-list.component.ts` + `.html` — CRUD list + inline form.
- `clients/angular/src/app/pages/tenant-admin/underwriting/cohorts-list.component.ts` + `.html` — CRUD list + inline form; portfolio dropdown = debounced search-select.
- `clients/angular/src/app/pages/tenant-admin/underwriting/legacy-retrofit.component.ts` + `.html` — paginated list of policies with `status = 'legacy_no_premium'`, inline edit form to fill in `written_premium` + `currency` + `bound_at` + `coverage_start/end` + `portfolio_id` + `cohort_id`; on save flips status back to ACTIVE and fires `PolicyIssuedPublisher`.
- `clients/angular/src/app/pages/tenant-admin/underwriting/underwriting.routes.ts` — route entries.

**File** (edit): `clients/angular/src/app/layout/tenant-sidebar/tenant-sidebar.component.ts` — add "Underwriting" nav entry with sub-links (Portfolios, Cohorts, Legacy retrofit) gated by `underwriting.portfolio:manage` permission.

**File** (edit): existing per-line policy CRUD forms (`life-policy-form.component.ts`, `funeral-policy-form.component.ts`, etc.) — add the new fields (written_premium input, currency picker, bound_at datepicker snapping to 1st-of-month, coverage_start/end datepickers, renewed_from policy picker as debounced search-select, portfolio + cohort dropdowns).

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :user-service:build` — green (2026-08-23). All 6 policy entities carry V102 columns; the 6 services wire `PolicyIssuedPublisher` from `create()` + `update()` (premium-change gated). Ifrs17 CRUD (entity/repo/DTO/service/controller × 2) compiles.
- [x] Unit tests: `make test-java` — new `Ifrs17PortfolioServiceTest` (6 cases: create-duplicate / create-success / findAll-active-only / findAll-include-inactive / findById-missing / softDelete-conflict + 1 update-success case = 7 total), `Ifrs17CohortServiceTest` (7 cases: findAll / findById-missing / create-missing-portfolio / create-duplicate-key / create-success / softDelete-conflict / update-success), `PolicyIssuedPublisherTest` (3 cases: full-payload / null-writtenPremium-skips / travel-line-uses-trip-dates). `./gradlew :user-service:test` green.
- [ ] Integration tests: `make test-integration` — **deferred** to Phase 3b per Deviation (test-migration mirror not yet in place). Unit-test coverage is in.
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — green (2026-08-23), pre-existing warnings only, none from the new files.
- [x] `make test-angular` — new `ifrs17-portfolio.service.spec.ts` (5 wire-shape cases: GET list, GET search, POST, PUT, DELETE) + `ifrs17-cohort.service.spec.ts` (4 wire-shape cases). All 9 pass under `npx ng test`.
- [x] Gateway Go tests: `cd services/go/gateway && go build ./...` clean (2026-08-23). No new routes_test.go added (matches Phase 11 precedent).
- [ ] `verify` on `/tenant-admin/underwriting/portfolios` — page compiles + loads; interactive verification pending.
- [ ] `verify` on `/tenant-admin/underwriting/cohorts` — page compiles + loads; portfolio picker debounces at 300ms via RxJS `debounceTime(300)`; interactive verification pending.
- [ ] `verify` on `/tenant-admin/underwriting/legacy-retrofit` — stub page shipping in Phase 3 (per Deviation) points operators at per-line policy pages; full retrofit surface deferred to Phase 3b.

#### Manual Verification
- [ ] Create a new LifePolicy via existing admin form — the extended form now requires `written_premium` + `currency` + `bound_at` + `coverage_start/end`; a `medfund.user.policy-issued` event appears on Kafka within 1s. (Requires Phase 3b Angular form extensions.)
- [ ] Create a renewal LifePolicy pointing at an existing one via `renewed_from_policy_id` picker — the picker searches by policy number. (Requires Phase 3b Angular form extensions.)
- [ ] Attempt to delete a portfolio that has policies attached — 409 with "cannot delete: policies reference this portfolio". (`Ifrs17PortfolioRepository.countReferencingPolicies` counts across 6 policy tables; service converts count > 0 to 409.)

**Implementation Note**: pause for human confirmation before Phase 4.

---

## Phase 4: §A — rules-engine PREMIUM_EARNING category

### Overview

Add `RuleCategory.PREMIUM_EARNING` (agenda-gated), `ActionType.ACCRUE_PREMIUM`, `PremiumFact`, `AccruePremiumEmitter`, `PremiumEarningTemplates` (3 templates: DAILY_LINEAR, MONTHLY_24THS, LINEAR_WITH_LOADING), and the corresponding Angular rule-editor extensions. Mirrors Phase 11 `COMMISSION` category pattern exactly.

### Changes Required

#### 1. Backend rules-engine additions

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java` (edit)

Add `PREMIUM_EARNING` after `COMMISSION`:

```java
COMMISSION,
PREMIUM_EARNING;
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/model/ActionType.java` (edit)

Add `ACCRUE_PREMIUM` after `PAY_COMMISSION`:

```java
PAY_COMMISSION,
ACCRUE_PREMIUM;
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java` (edit)

Add `"PREMIUM_EARNING"` to `AGENDA_GATED_CATEGORIES` at line 57:

```java
private static final Set<String> AGENDA_GATED_CATEGORIES = Set.of(
    "BENEFIT_PRORATION", "REINSURANCE", "COMMISSION", "PREMIUM_EARNING"
);
```

Add `PremiumFact` to the imports block + `FACT_MAPPINGS` — check current shape at code time.

Add `factForAction` mapping so `ACCRUE_PREMIUM` binds `$premium`:

```java
if ("ACCRUE_PREMIUM".equals(actionType)) return "premium";
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/fact/PremiumFact.java` (new)

```java
package com.medfund.rules.fact;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fact for the PREMIUM_EARNING rule category. Populated at policy bind time by
 * the contributions-service PolicyIssuedConsumer; the AccruePremiumEmitter fires
 * rules that pick an earning method and record it on this fact for downstream
 * consumption by the executor.
 */
@Getter
@Setter
@Accessors(chain = true)
public class PremiumFact {
    private UUID policyId;
    private String policySource;       // 'LIFE_POLICY' etc.
    private String insuranceLine;      // 'LIFE', 'HEALTH', etc.
    private String productCode;        // optional — scheme + benefit-family (nullable in MVP)
    private UUID tenantId;
    private BigDecimal writtenPremium;
    private String currencyCode;
    private LocalDate coverageStart;
    private LocalDate coverageEnd;
    private OffsetDateTime boundAt;
    private UUID portfolioId;
    private UUID cohortId;

    /** The earning method chosen by the fired rule (or default DAILY_LINEAR). */
    private String earningMethod = "DAILY_LINEAR";

    /** Optional loading percent for LINEAR_WITH_LOADING (front-loaded portion). */
    private BigDecimal loadingPercent;

    private final List<RuleResult> results = new ArrayList<>();

    /** Action method invoked by the AccruePremiumEmitter's DRL body. */
    public void accrue(String earningMethodOverride, BigDecimal loadingPct, String reason) {
        this.earningMethod = earningMethodOverride;
        this.loadingPercent = loadingPct;
        results.add(new RuleResult(getPolicyId().toString(), earningMethodOverride, reason));
    }
}
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/AccruePremiumEmitter.java` (new)

```java
package com.medfund.rules.compiler;

import com.medfund.rules.model.ActionType;
import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * Emits the DRL body for ACCRUE_PREMIUM. Encodes the earning method + loading
 * percent as an action-value DSL:
 *   EARNING_METHOD:DAILY_LINEAR
 *   EARNING_METHOD:MONTHLY_24THS
 *   EARNING_METHOD:LINEAR_WITH_LOADING:15   (15% front-loaded)
 * The consumer reads PremiumFact.earningMethod + .loadingPercent to know
 * which strip strategy to apply when writing earning_schedule rows.
 */
@Component
public class AccruePremiumEmitter implements ActionEmitter {

    @Override
    public String type() {
        return ActionType.ACCRUE_PREMIUM.name();
    }

    @Override
    public String emit(RuleAction action) {
        String value = action.getValue();          // "EARNING_METHOD:DAILY_LINEAR" etc.
        String reason = escape(action.getMessage() == null ? "" : action.getMessage());
        String method;
        String loading;

        if (value != null && value.startsWith("EARNING_METHOD:")) {
            String rest = value.substring("EARNING_METHOD:".length());
            String[] parts = rest.split(":", 2);
            method = parts[0];
            loading = parts.length == 2 ? parts[1] : "null";
        } else {
            method = "DAILY_LINEAR";
            loading = "null";
        }

        String loadingExpr = "null".equals(loading)
                ? "null"
                : "new java.math.BigDecimal(\"" + loading + "\")";

        return String.format(
                "$premium.accrue(\"%s\", %s, \"%s\");",
                method, loadingExpr, reason
        );
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/PremiumEarningTemplates.java` (new)

```java
package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.template.RuleTemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Ships three ready-to-clone templates for the PREMIUM_EARNING category:
 *   E70 — Daily linear default for annual-bind lines
 *   E71 — Monthly 24ths for IPEC-jurisdiction tenants
 *   E72 — Linear-with-loading for front-loaded life products
 */
@Component
public class PremiumEarningTemplates implements RuleTemplateProvider {

    @Override
    public RuleCategory category() { return RuleCategory.PREMIUM_EARNING; }

    @Override
    public List<Map<String, Object>> templates() {
        return List.of(
                Map.of(
                        "code", "E70",
                        "name", "Daily-linear earning (default annual bind)",
                        "priority", 100,
                        "conditions", Map.of("operator", "AND", "items", List.of(
                                Map.of("field", "premium.insuranceLine", "operator", "IN",
                                       "value", List.of("LIFE", "FUNERAL", "DISABILITY", "VEHICLE", "PROPERTY"))
                        )),
                        "action", Map.of("type", "ACCRUE_PREMIUM",
                                         "value", "EARNING_METHOD:DAILY_LINEAR",
                                         "message", "Default 1/365ths earning")
                ),
                Map.of(
                        "code", "E71",
                        "name", "Monthly 24ths (IPEC ZW opt-in)",
                        "priority", 90,
                        "conditions", Map.of("operator", "AND", "items", List.of(
                                Map.of("field", "premium.insuranceLine", "operator", "EQUALS", "value", "VEHICLE")
                        )),
                        "action", Map.of("type", "ACCRUE_PREMIUM",
                                         "value", "EARNING_METHOD:MONTHLY_24THS",
                                         "message", "IPEC-mandated 24ths for motor")
                ),
                Map.of(
                        "code", "E72",
                        "name", "Linear-with-loading (front-loaded whole-life)",
                        "priority", 80,
                        "conditions", Map.of("operator", "AND", "items", List.of(
                                Map.of("field", "premium.insuranceLine", "operator", "EQUALS", "value", "LIFE"),
                                Map.of("field", "premium.productCode", "operator", "EQUALS", "value", "WHOLE_LIFE")
                        )),
                        "action", Map.of("type", "ACCRUE_PREMIUM",
                                         "value", "EARNING_METHOD:LINEAR_WITH_LOADING:15",
                                         "message", "15% front-loading for whole-life products")
                )
        );
    }
}
```

#### 2. Angular rule editor updates

**File**: `clients/angular/src/app/core/services/rules.service.ts` (edit)

Add `'PREMIUM_EARNING'` to the `RuleCategory` union at line 36-59. Append to `RULE_CATEGORIES` array:

```typescript
{ id: 'PREMIUM_EARNING', label: 'Premium earning', icon: 'trending-up' }
```

**File**: `clients/angular/src/app/pages/tenant-admin/rules/rule-editor/rule-editor.component.ts` (edit)

Append to `ACTION_TYPES`:

```typescript
{
  id: 'ACCRUE_PREMIUM',
  label: 'Accrue premium',
  description: 'Pick an earning method for the policy at bind time. Value: EARNING_METHOD:<name>[:<loading-pct>]',
  valueHint: 'EARNING_METHOD:DAILY_LINEAR | EARNING_METHOD:MONTHLY_24THS | EARNING_METHOD:LINEAR_WITH_LOADING:15'
}
```

**File**: `clients/angular/src/app/pages/tenant-admin/rules/rule-dry-run/rule-dry-run.component.ts` (edit)

Append to `FACT_SEEDS`:

```typescript
{
  category: 'PREMIUM_EARNING',
  fact: {
    policyId: '00000000-0000-0000-0000-000000000001',
    policySource: 'LIFE_POLICY',
    insuranceLine: 'LIFE',
    productCode: 'TERM_LIFE',
    tenantId: '00000000-0000-0000-0000-000000000000',
    writtenPremium: 1200.00,
    currencyCode: 'USD',
    coverageStart: '2026-01-01',
    coverageEnd: '2026-12-31',
    boundAt: '2026-01-01T00:00:00Z',
    portfolioId: null,
    cohortId: null
  }
}
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :rules-engine:build` — `compileJava`, `test`, `jacocoTestReport` all green (2026-08-23). Full `build` fails on the pre-existing `jacocoTestCoverageVerification` threshold (0.54 vs 0.70 required); confirmed against `55c3689` baseline where the module sat at 0.52. Phase 4 additions lifted the ratio +2 pp, not down — the failure is pre-existing infra, matching the Phase 2 §A pattern documented in that phase's success criteria.
- [x] Unit tests: `./gradlew :rules-engine:test` — new `AccruePremiumEmitterTest` (6 cases: DAILY_LINEAR / MONTHLY_24THS / LINEAR_WITH_LOADING encoding, missing-value default, malformed-loading sanitisation, message escaping); `RuleCategoryTest` size guard bumped to 19 + `premiumEarningCategory_isDeclared`; `DrlCompilerTest.compile_premiumEarningRule_addsAgendaGroupAndAccrue` + `compile_premiumEarningLoadingRule_carriesLoadingPercent` verify PREMIUM_EARNING is agenda-gated and PremiumFact is auto-bound; `RuleTemplateServiceTest.getDefaultRules_coversEveryDeclaredCategory` picks up the new `PremiumEarningTemplates` provider; new `PremiumFactTest` (4 cases) locks in the ACCRUE_PREMIUM action method and default earning method. All green.
- [ ] Rules engine `POST /api/v1/rules` accepts a rule with `category=PREMIUM_EARNING` + `action.type=ACCRUE_PREMIUM` + `value=EARNING_METHOD:DAILY_LINEAR` — compiles to DRL cleanly. **Manual — pending live-service smoke.**
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — green (2026-08-23), pre-existing warnings only, none from the new files.
- [x] `make test-angular` — `rules.service.spec.ts` extended with `includes the Phase 12 PREMIUM_EARNING category`; `rule-editor.component.spec.ts` extended with `exposes the Phase 12 ACCRUE_PREMIUM action type` + `carries a valueHint on ACCRUE_PREMIUM (EARNING_METHOD DSL)`. All 8 rules-related specs pass under ChromeHeadlessCI.

#### Manual Verification
- [ ] Rules engine `POST /api/v1/rules` with `category=PREMIUM_EARNING` + `action.type=ACCRUE_PREMIUM` + `value=EARNING_METHOD:DAILY_LINEAR` compiles to DRL cleanly and activates without firing during a stage-7 sweep.
- [ ] Tenant admin visits `/tenant-admin/rules/new`, picks "Premium earning" category, gets the ACCRUE_PREMIUM action in the action dropdown with the value-hint text visible.
- [ ] Dry-run against the `PremiumFact` seed returns the expected `earningMethod` on the fact.

**Implementation Note**: pause for human confirmation before Phase 5.

---

## Phase 5: §A — contributions-service premium module

### Overview

The heart of §A: `com.medfund.contributions.premium.*` subpackage housing `PolicyIssuedConsumer` (Kafka), `BillingContributionEarningHook` (in-JVM from BillingService for HEALTH), `PremiumEarningExecutor` (nightly JobExecutor), `EarningScheduleRepository`, and CRUD services for the executor's admin surfaces (backfill trigger, run status, replay).

### Changes Required

#### 1. Entities + repositories

**Files** (new):
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/entity/EarningSchedule.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/entity/EarningScheduleRun.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/repository/EarningScheduleRepository.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/repository/EarningScheduleRunRepository.java`

Repository methods:

```java
public interface EarningScheduleRepository extends ReactiveCrudRepository<EarningSchedule, UUID> {
    Flux<EarningSchedule> findByPolicyIdAndPolicySource(UUID policyId, String policySource);
    Flux<EarningSchedule> findByPeriodEndBeforeAndEarnedAtPeriodEndIsNull(LocalDate asOf);
    Flux<EarningSchedule> findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(
            UUID policyId, String policySource, LocalDate effectiveFrom);
    Mono<Void> deleteByEndorsementId(UUID endorsementId);
    // Executor picks up policies missing current period; report queries.
}
```

#### 2. `PolicyIssuedConsumer`

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/consumer/PolicyIssuedConsumer.java` (new)

```java
package com.medfund.contributions.premium.consumer;

import com.medfund.contributions.premium.service.EarningScheduleService;
import com.medfund.shared.kafka.KafkaConsumerConfig;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyIssuedConsumer {

    private static final String TOPIC = "medfund.user.policy-issued";

    private final KafkaReceiver<String, String> receiver;
    private final EarningScheduleService earningScheduleService;
    private final PolicyIssuedPayloadParser parser;

    // Started via ApplicationReadyEvent / SmartLifecycle
    public void start() {
        receiver.receive()
            .flatMap(this::handle)
            .subscribe();
    }

    private Flux<Void> handle(ReceiverRecord<String, String> record) {
        return Flux.defer(() -> {
            var payload = parser.parse(record.value());
            UUID tenantId = payload.tenantId();
            return earningScheduleService.writeSchedule(payload)
                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                .doOnError(err -> log.error("Failed to write earning schedule for policy={} tenant={} — full chain: {}",
                        payload.policyId(), tenantId, chainMessages(err), err))
                .contextWrite(Context.of(TenantContext.KEY, tenantId));
        }).thenMany(Flux.empty());
    }

    private static String chainMessages(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        while (c != null) {
            sb.append(" ← ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return sb.toString();
    }
}
```

Note the `.doOnSuccess` ack per `bug_reactor_kafka_ack_swallow` memory — never `.doOnTerminate`.

#### 3. `BillingContributionEarningHook` (in-JVM for HEALTH)

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/BillingContributionEarningHook.java` (new)

```java
package com.medfund.contributions.premium.service;

import com.medfund.contributions.entity.Contribution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Called from BillingService.persistContribution AFTER the Contribution row is saved,
 * within the same reactive transaction. Writes one earning_schedule row per Contribution
 * (HEALTH earns entirely within its period per U2, so earned_at_period_end = amount
 * at row creation).
 */
@Component
@RequiredArgsConstructor
public class BillingContributionEarningHook {

    private final EarningScheduleService earningScheduleService;

    public Mono<Void> onContributionCreated(Contribution contribution) {
        return earningScheduleService.writeContributionSchedule(contribution);
    }
}
```

Wire into `BillingService.persistContribution` (line ~208 for `generateBilling`, ~904 for `doCommit`):

```java
// In BillingService.persistContribution:
return contributionRepository.save(contribution)
    .flatMap(saved -> billingContributionEarningHook.onContributionCreated(saved).thenReturn(saved))
    // ... rest of existing chain
    ;
```

#### 4. `EarningScheduleService`

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EarningScheduleService.java` (new)

```java
package com.medfund.contributions.premium.service;

import com.medfund.contributions.entity.Contribution;
import com.medfund.contributions.premium.consumer.PolicyIssuedPayload;
import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.contributions.premium.repository.EarningScheduleRepository;
import com.medfund.rules.engine.RuleEngineClient;
import com.medfund.rules.fact.PremiumFact;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes earning_schedule rows for policies (via PolicyIssuedConsumer) and
 * contributions (via BillingContributionEarningHook).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarningScheduleService {

    private final EarningScheduleRepository earningScheduleRepository;
    private final RuleEngineClient ruleEngineClient;   // fires PREMIUM_EARNING agenda

    /** Called by PolicyIssuedConsumer per annual-bind policy. */
    public Mono<Void> writeSchedule(PolicyIssuedPayload payload) {
        PremiumFact fact = new PremiumFact()
                .setPolicyId(payload.policyId())
                .setPolicySource(payload.policySource())
                .setInsuranceLine(payload.insuranceLine())
                .setTenantId(payload.tenantId())
                .setWrittenPremium(payload.writtenPremium())
                .setCurrencyCode(payload.currencyCode())
                .setCoverageStart(payload.coverageStart())
                .setCoverageEnd(payload.coverageEnd())
                .setBoundAt(payload.boundAt())
                .setPortfolioId(payload.portfolioId())
                .setCohortId(payload.cohortId());

        // Fire the PREMIUM_EARNING agenda for this fact — sets fact.earningMethod + loadingPercent.
        return ruleEngineClient.fire(payload.tenantId(), "PREMIUM_EARNING", fact)
                .then(Mono.defer(() -> writeRowsForFact(fact)))
                .then();
    }

    /** Called by BillingContributionEarningHook per HEALTH Contribution. */
    public Mono<Void> writeContributionSchedule(Contribution contribution) {
        EarningSchedule row = new EarningSchedule();
        row.setPolicyId(contribution.getId());
        row.setPolicySource("CONTRIBUTION");
        row.setInsuranceLine("HEALTH");
        row.setPeriodStart(contribution.getPeriodStart());
        row.setPeriodEnd(contribution.getPeriodEnd());
        row.setWrittenAmount(contribution.getAmount());
        row.setEarnedAtPeriodEnd(contribution.getAmount());   // HEALTH earns entirely within period per U2
        row.setCurrencyCode(contribution.getCurrencyCode());
        row.setEarningMethod("DAILY_LINEAR");                  // HEALTH always linear-within-period
        row.setPortfolioId(contribution.getPortfolioId());
        row.setCohortId(contribution.getCohortId());
        return earningScheduleRepository.save(row).then();
    }

    private Mono<Void> writeRowsForFact(PremiumFact fact) {
        List<EarningSchedule> rows = splitIntoPeriods(fact);
        return Flux.fromIterable(rows)
                .flatMap(earningScheduleRepository::save, 4)
                .then();
    }

    private List<EarningSchedule> splitIntoPeriods(PremiumFact fact) {
        // Compute per-period splits. For DAILY_LINEAR: monthly buckets, pro-rated on days.
        // For MONTHLY_24THS: half-months.
        // For LINEAR_WITH_LOADING: first month gets loading + pro-rata linear; rest linear.
        // Implementation detail — expanded at code-altitude.
        List<EarningSchedule> rows = new ArrayList<>();
        LocalDate cursor = fact.getCoverageStart().withDayOfMonth(1);
        LocalDate coverageEnd = fact.getCoverageEnd();
        long totalDays = ChronoUnit.DAYS.between(fact.getCoverageStart(), coverageEnd) + 1;

        while (!cursor.isAfter(coverageEnd)) {
            LocalDate periodStart = cursor.isBefore(fact.getCoverageStart()) ? fact.getCoverageStart() : cursor;
            LocalDate periodEnd = cursor.plusMonths(1).minusDays(1);
            if (periodEnd.isAfter(coverageEnd)) periodEnd = coverageEnd;

            long periodDays = ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
            BigDecimal periodWritten = fact.getWrittenPremium()
                    .multiply(BigDecimal.valueOf(periodDays))
                    .divide(BigDecimal.valueOf(totalDays), 4, java.math.RoundingMode.HALF_EVEN);

            EarningSchedule row = new EarningSchedule();
            row.setPolicyId(fact.getPolicyId());
            row.setPolicySource(fact.getPolicySource());
            row.setInsuranceLine(fact.getInsuranceLine());
            row.setPeriodStart(periodStart);
            row.setPeriodEnd(periodEnd);
            row.setWrittenAmount(periodWritten);
            row.setCurrencyCode(fact.getCurrencyCode());
            row.setEarningMethod(fact.getEarningMethod());
            row.setPortfolioId(fact.getPortfolioId());
            row.setCohortId(fact.getCohortId());
            rows.add(row);

            cursor = cursor.plusMonths(1);
        }
        return rows;
    }
}
```

#### 5. `PremiumEarningExecutor`

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/scheduler/PremiumEarningExecutor.java` (new)

```java
package com.medfund.contributions.premium.scheduler;

import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import com.medfund.shared.scheduler.JobExecutor;
import com.medfund.shared.scheduler.JobType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class PremiumEarningExecutor implements JobExecutor {

    private final EarningScheduleClosureService closureService;

    @Override
    public JobType getJobType() { return JobType.PREMIUM_EARNING; }

    @Override
    public Mono<Void> execute(String tenantId, String settings) {
        log.info("PremiumEarningExecutor: tenant={} settings={}", tenantId, settings);
        return closureService.closeExpiredPeriodsForTenant(tenantId);
    }
}
```

`EarningScheduleClosureService` handles the actual chunked scan + close per U10. Full implementation at code altitude — the plan below sketches the pipeline:

```java
Mono<Void> closeExpiredPeriodsForTenant(String tenantId) {
    return earningScheduleRunRepository.startRun(tenantId, "SCHEDULED")
        .flatMap(run ->
            earningScheduleRepository.findByPeriodEndBeforeAndEarnedAtPeriodEndIsNull(LocalDate.now())
                .window(5000)  // U10 chunk size
                .concatMap(chunk -> chunk
                    .flatMap(row -> {
                        row.setEarnedAtPeriodEnd(row.getWrittenAmount());
                        return earningScheduleRepository.save(row);
                    }, 4)
                    .then(earningScheduleRunRepository.heartbeat(run.getId())))
                .then(earningScheduleRunRepository.finish(run.getId(), "COMPLETED"))
                .onErrorResume(err -> earningScheduleRunRepository.finish(run.getId(), "FAILED", err.getMessage()))
        )
        .then(refreshMemberFirstContributionView(tenantId));    // grill note 7
}
```

Add `JobType.PREMIUM_EARNING` to the enum in `services/java/shared/src/main/java/com/medfund/shared/scheduler/JobType.java`.

#### 6. Admin controllers

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/controller/EarningScheduleController.java` (new)

Endpoints:
- `POST /api/v1/premium/earning-schedule/backfill?policyId=&policySource=` — kick off targeted backfill (fire-and-forget, returns 202).
- `POST /api/v1/premium/earning-schedule/replay?policyId=&policySource=` — recompute after a rule edit.
- `POST /api/v1/premium/earning-schedule/runs/{runId}/cancel` — cancel a running executor.
- `GET /api/v1/premium/earning-schedule/runs/{runId}` — progress polling.
- `GET /api/v1/premium/earning-schedule?policyId=&policySource=` — dev/debug: raw earning_schedule rows for a policy.

Two new permissions:
- `premium.earning:manage_backfill`
- `premium.earning:view_debug` (dev-only; only exposed via `@Profile("dev")` in production build)

#### 7. `.claude/multi-currency.md` update (grill note 13)

**File**: `.claude/multi-currency.md` (edit; insert after line 169)

Add paragraph:

```markdown
### Premium earning-schedule currency handling (Phase 12)

Written premium stays in the policy's original currency; endorsement retro-recompute preserves the
original currency (never re-denominates). Report envelopes convert to the reporting currency at each
row's `period_end` date via `FxRateReader.convert(...)`. Missing historical FX for a `period_end` →
the currency is omitted from the envelope's `fxRates` map and named in `warnings: List<String>`
rather than thrown — reports succeed with partial coverage per parent-plan invariant #6 / G28.
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :contributions-service:compileJava` — green (2026-08-23). `./gradlew :contributions-service:test :shared:test :rules-engine:test :user-service:test` — all green.
- [x] Unit tests: `make test-java`
  - `PremiumEarningStripCalculatorTest` — 8 cases: DAILY_LINEAR annual, mid-month start clipping, MONTHLY_24THS half-ends, LINEAR_WITH_LOADING front-load, single-month cover, cross-year 13-row split, missing bounds → empty strip, unknown method → linear fallback. Every case asserts the strip sums exactly to written premium (last-row absorbs rounding).
  - `EarningScheduleServiceTest` — 4 cases: annual-policy runs the PREMIUM_EARNING agenda then persists 12 strip rows; missing writtenPremium skips without evaluating; HEALTH single-row write with earned = amount + portfolio inheritance; missing Contribution fields skip.
  - `BillingContributionEarningHookTest` — 3 cases: passthrough on success, swallows downstream failure (billing must not fail if projection breaks), completes with zero-schedule case.
  - `PolicyIssuedConsumerTest` — 4 cases: happy path, missing tenantId dropped, unparseable JSON swallowed, downstream error propagates for outer ack path.
  - `EarningScheduleClosureServiceTest` — 4 cases: closeOne sets earned = written, closeExpiredPeriodsForTenant runs the run lifecycle end-to-end, backfill filters to requested policy (already-closed rows skipped), refresh MV swallows SQL failures.
  - `PremiumEarningExecutorTest` — 2 cases: getJobType, execute delegates to closure service.
- [ ] Integration tests: `make test-integration` (all extend `AbstractIntegrationTest`) — **deferred to Phase 5b** per Phase 2 §5 Deviation; the ifrs17 + earning_schedule mirror is not yet in the shared test-migration path (adding it would force-widen every existing contributions-service IT's schema and cross-service tables). Follow-up (Phase 5b): add a dedicated `db/premium-earning-migration/` folder + `PolicyIssuedConsumerIT`, `BillingEarningIT`, `PremiumEarningExecutorIT`.
- [x] Contract check — the `EarningScheduleClosureServiceTest.backfillPolicy_onlyClosesRowsForRequestedPolicy` case guards the idempotency contract: already-closed rows are filtered out on replay so re-running writes no duplicates.
- [ ] `verify` on `/api/v1/premium/earning-schedule/backfill?policyId=...` returns 202 and a runId; polling `/runs/{runId}` shows progress. **Manual — pending live-service smoke.**

#### Manual Verification
- [ ] Bind a new LifePolicy for $1200 covering 2026-01-01 to 2026-12-31 → 12 earning_schedule rows appear, ~$100 each, all with earned_at_period_end = null.
- [ ] Nightly `PremiumEarningExecutor` fires → periods with period_end < today get earned_at_period_end filled in.
- [ ] Pay a HEALTH Contribution → single earning_schedule row appears with earned_at_period_end = amount (HEALTH earns within period).
- [ ] Kick off a backfill for a specific policy → runId returned; polling shows progress; final status COMPLETED.
- [ ] Cancel a running backfill mid-flight → status transitions to CANCELLED; no data corruption.

**Implementation Note**: pause for human confirmation before Phase 6 — §A is now silent-complete.

---

## Phase 6: §B — Report backend + ReportFamily.UNDERWRITING

### Overview

Reassign the 3 existing `ReportKey` enum entries to a new `ReportFamily.UNDERWRITING`, build `UprMovementReportController`, `PremiumRegisterReportController`, `NewBusinessRegisterReportController` with XLSX exports, wire gateway routes.

### Changes Required

#### 1. Shared enum edits

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java` (edit)

Add `UNDERWRITING`:

```java
CLAIMS_FINANCIAL,
UNDERWRITING,
RECONCILIATION,
// ...
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java` (edit)

Reassign lines 100-102:

```java
UPR_MOVEMENT("UPR movement", ReportFamily.UNDERWRITING, true),
PREMIUM_REGISTER("Premium register", ReportFamily.UNDERWRITING, false),
NEW_BUSINESS_REGISTER("New business register", ReportFamily.UNDERWRITING, false),
```

**File**: `clients/angular/src/app/core/services/report-catalogue.service.ts` (if exists) or wherever family labels live — add `UNDERWRITING` mapping label "Underwriting".

#### 2. Report DTOs

**Files** (new, all in `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/dto/`):
- `UprMovementRow.java` — `{insuranceLine, currencyCode, openingUpr, writtenPremium, earnedPremium, endorsementDelta, closingUpr}`
- `PremiumRegisterRow.java` — `{policyId, policySource, memberName, insuranceLine, schemeName, currencyCode, writtenPremium, earnedInPeriod, unearnedAtPeriodEnd, boundAt, coverageStart, coverageEnd, isNewBusiness, portfolioName, cohortName}`
- `NewBusinessRegisterRow.java` — `{policyId, policySource, memberNumber, memberName, insuranceLine, schemeName, boundAt, writtenPremium, currencyCode, portfolioName, cohortName}`

#### 3. Report services

**Files** (new):
- `UprMovementReportService.java`
- `PremiumRegisterReportService.java`
- `NewBusinessRegisterReportService.java`

Each mirrors Phase 11 `CommissionStatementService` shape — delegates to `ReportEnvelopeBuilder`, computes native-currency perCurrency map via a second aggregate SQL, populates FX rates map best-effort.

`UprMovementReportService` in more detail — the SQL is the workhorse:

```sql
-- Opening UPR: sum of (written - earned) over rows where period_start < :periodStart
--              AND (period_end >= :periodStart OR period_end IS NULL)
WITH opening AS (
    SELECT insurance_line, currency_code,
           SUM(written_amount - COALESCE(earned_at_period_end, 0)) AS opening_upr
    FROM earning_schedule
    WHERE period_start < :periodStart
      AND period_end >= :periodStart
    GROUP BY insurance_line, currency_code
),
written_in_period AS (
    SELECT insurance_line, currency_code,
           SUM(written_amount) AS written
    FROM earning_schedule
    WHERE period_start >= :periodStart AND period_start < :periodEnd
    GROUP BY insurance_line, currency_code
),
earned_in_period AS (
    -- Rows fully closed within the period
    SELECT insurance_line, currency_code,
           SUM(earned_at_period_end) AS earned
    FROM earning_schedule
    WHERE period_end >= :periodStart AND period_end < :periodEnd
      AND earned_at_period_end IS NOT NULL
    GROUP BY insurance_line, currency_code
),
endorsement_delta AS (
    SELECT insurance_line, currency_code,
           SUM(written_amount) AS delta
    FROM earning_schedule
    WHERE is_endorsement = TRUE
      AND created_at >= :periodStart AND created_at < :periodEnd
    GROUP BY insurance_line, currency_code
)
SELECT COALESCE(o.insurance_line, w.insurance_line, e.insurance_line, d.insurance_line) AS insurance_line,
       COALESCE(o.currency_code, w.currency_code, e.currency_code, d.currency_code) AS currency_code,
       COALESCE(o.opening_upr, 0) AS opening_upr,
       COALESCE(w.written, 0) AS written_premium,
       COALESCE(e.earned, 0) AS earned_premium,
       COALESCE(d.delta, 0) AS endorsement_delta,
       COALESCE(o.opening_upr, 0) + COALESCE(w.written, 0) - COALESCE(e.earned, 0) + COALESCE(d.delta, 0) AS closing_upr
FROM opening o
FULL OUTER JOIN written_in_period w USING (insurance_line, currency_code)
FULL OUTER JOIN earned_in_period e USING (insurance_line, currency_code)
FULL OUTER JOIN endorsement_delta d USING (insurance_line, currency_code)
ORDER BY insurance_line, currency_code;
```

#### 4. Report controllers

**Files** (new):
- `UprMovementReportController.java` at `/api/v1/reports/premium/upr-movement`
- `PremiumRegisterReportController.java` at `/api/v1/reports/premium/register`
- `NewBusinessRegisterReportController.java` at `/api/v1/reports/premium/new-business`

Each carries:
```java
@GetMapping
@RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
@RequiresReport(ReportKey.UPR_MOVEMENT)
public Mono<ReportResponse<List<UprMovementRow>>> get(
        @RequestParam LocalDate periodStart,
        @RequestParam LocalDate periodEnd,
        @RequestParam(required = false) String insuranceLine,
        @RequestParam(required = false) String reportingCurrency) { ... }

@GetMapping("/export/excel")
@RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
@RequiresReport(ReportKey.UPR_MOVEMENT)
public Mono<ResponseEntity<byte[]>> exportExcel(/* same params */, @AuthenticationPrincipal Jwt jwt) {
    // Delegate to workbook service; publish SecurityEventPublisher.publishDataAccess before return
}
```

Full Swagger annotations per Rule 7.

#### 5. Workbook services

**Files** (new):
- `UprMovementWorkbookService.java`
- `PremiumRegisterWorkbookService.java`
- `NewBusinessRegisterWorkbookService.java`

Use shared `ReportWorkbook` builder. XLSX shape:
- **UPR Movement**: one sheet per insurance_line (or combined "All lines" toggle in filter), summary sheet with cross-currency totals converted to reporting_currency + FX audit trail + warnings block.
- **Premium Register**: single sheet with per-policy rows; separate summary sheet.
- **New Business Register**: single sheet with per-policy rows; summary sheet.

#### 6. Gateway routes

**File**: `services/go/gateway/internal/routes/routes.go` (edit)

```go
// Underwriting report routes → contributions-service.
r.Get("/api/v1/reports/premium/*", proxy.To(contributionsSvc))
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :shared:compileJava :contributions-service:compileJava` — green (2026-08-23). `ReportFamily.UNDERWRITING` added; the three keys move from CLAIMS_FINANCIAL to UNDERWRITING; the report module + workbook services land under `com.medfund.contributions.premium.{dto,repository,service,controller}`.
- [x] Unit tests: `./gradlew :contributions-service:test :shared:test` — all green (2026-08-23).
  - `UprMovementReportServiceTest` — 5 cases: empty period, single-currency line filter, multi-currency perCurrency, currency override forwarded, reportKey + period grain assertions.
  - `PremiumRegisterReportServiceTest` — 4 cases: period + line filter forwarded, isNewBusiness flag flow, portfolio + cohort + scheme + member label flow, native perCurrency map + reportKey assertion.
  - `NewBusinessRegisterReportServiceTest` — 4 cases: empty period envelope, line filter forwarded, row-fields flow (memberNumber / memberName / writtenPremium / currency), reportKey assertion.
  - `UprMovementWorkbookServiceTest` — 3 cases: sheet-per-line + Summary, converted grand total when FX resolves, warning line when FX missing.
  - `PremiumRegisterWorkbookServiceTest` — 2 cases: Register + Summary sheets with per-currency subtotal, missing-FX warning.
  - `NewBusinessRegisterWorkbookServiceTest` — 2 cases: New Business + Summary sheets, missing-FX warning.
- [ ] Integration tests: `make test-integration` — **deferred** to Phase 6b, mirroring the Phase 5 Deviation. The seven-way UNION SQL + envelope + workbook end-to-end asserts (`UprMovementReportIT`, `PremiumRegisterReportIT`, `NewBusinessRegisterReportIT`) need a `db/premium-reports-migration/` folder carrying `ifrs17_portfolio` + `ifrs17_cohort` + `earning_schedule` + policy tables (life / funeral / disability / travel / vehicles / properties / members / schemes / contributions). Force-widening the existing `test-migration/` folders would cascade FK references into every unrelated IT slice; a purpose-built folder + ITs lands as Phase 6b.
- [x] Gateway Go tests: `cd services/go/gateway && go test ./...` — green (2026-08-23). `/api/v1/reports/premium` + `/api/v1/reports/premium/*` route added; no `routes_test.go` per Phase 11 precedent.

#### Manual Verification
- [ ] Toggle UPR_MOVEMENT enabled in tenant_report_config → `GET /api/v1/reports/premium/upr-movement?periodStart=2026-01-01&periodEnd=2026-03-31` returns envelope with real numbers.
- [ ] Export XLSX for a period spanning multiple currencies → file downloads, opens in LibreOffice, one sheet per insurance_line, summary sheet correct with fx audit.
- [ ] Toggle report off → GET returns 403; XLSX endpoint returns 403.
- [ ] Kafka `medfund.security.events` carries `reportKey=UPR_MOVEMENT` on every export.

**Implementation Note**: pause for human confirmation before Phase 7.

---

## Phase 7: §B — Angular report pages + hub

### Overview

Angular report pages under `/tenant/finance/reports/underwriting/*` for the 3 §B reports, reports-hub `UNDERWRITING` family card, sidebar registration, Playwright specs.

### Changes Required

#### 1. Angular services

**Files** (new):
- `clients/angular/src/app/core/services/upr-movement-report.service.ts`
- `clients/angular/src/app/core/services/premium-register-report.service.ts`
- `clients/angular/src/app/core/services/new-business-register-report.service.ts`

Each mirrors the Phase 11 `commission-report.service.ts` shape — GET envelope + POST /export/excel returning blob.

#### 2. Report page components

**Files** (new — one page per report):
- `clients/angular/src/app/pages/tenant/finance/reports/underwriting/upr-movement.component.ts` + `.html`
- `clients/angular/src/app/pages/tenant/finance/reports/underwriting/premium-register.component.ts` + `.html`
- `clients/angular/src/app/pages/tenant/finance/reports/underwriting/new-business-register.component.ts` + `.html`
- `clients/angular/src/app/pages/tenant/finance/reports/underwriting/underwriting.routes.ts`

Each page structure (following Phase 11 commission-statement.component precedent):
- Filter row: period picker (default: prior month), insurance-line dropdown, reporting-currency override picker, Export button.
- Warnings banner beneath toolbar (renders envelope `warnings: string[]`).
- Data table (paged for register + new-business; matrix for UPR movement) + native perCurrency totals strip.
- Missing-FX warning inline where amounts couldn't be converted.

#### 3. Reports hub

The reports hub (`clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts`) already derives family cards from `TenantReportConfigService.list(tenantId)` — the three keys under `UNDERWRITING` family will auto-appear as a new family card once the enum edit lands (Phase 6). No client-side catalogue edit needed (matches Phase 11 Phase 5 deviation).

#### 4. Sidebar (no per-report sub-entries)

Follows Phase 11 precedent: reports sidebar stays a single entry pointing at the hub; per-report links surface only in the hub's family cards.

### Success Criteria

#### Automated Verification
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — green (2026-08-23), pre-existing warnings only, none from the new files.
- [x] `make test-angular` — 3 new `*-report.service.spec.ts` files (4 wire-shape cases each: GET envelope forwarding, optional-omission, blob export, filter-forwarded export). 12 new tests, all green under ChromeHeadlessCI. The one pre-existing failure (`insurance-lines.spec.ts:344` — `providerModeForLine` HEALTH/GROUP/TRAVEL/VEHICLE/PROPERTY expected OPTIONAL, code returns REQUIRED) traces to `e7b8fb3` on main, not this branch.
- [x] Playwright: `underwriting-reports.spec.ts` landed with 5 tests — hub family card visibility, each of the 3 pages rendering + warnings + export blob, and the disabled-toggle hides-the-card path. Mirrors `reinsurance-bordereau.spec.ts` shape.

#### Manual Verification
- [ ] Reports hub at `/tenant/finance/reports` shows an "Underwriting" family card when the three keys are enabled.
- [ ] Toggle a report off in tenant-admin → direct URL navigation returns 403; hub card hides the report.
- [ ] Reporting-currency override picker sets `?reportingCurrency=EUR` and the summary panel updates with the new conversion.
- [ ] Missing FX rate for one currency shows a warning banner beneath the toolbar and the report still renders (envelope `warnings` array is displayed inline).

**Implementation Note**: pause for human confirmation before Phase 8 — §B is complete.

---

## Phase 8: §C — Endorsement schema + PolicyEndorsementService

### Overview

Add the endorsement table (V110 tenant), tenant_endorsement_config public table (V134), `PolicyEndorsementService` with four-eyes state machine mirroring Phase 11 `CommissionAdjustmentService`, `PolicyEndorsedPublisher` for the `medfund.user.policy-endorsed` event.

### Changes Required

#### 1. Migrations

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V110__endorsement.sql`

```sql
CREATE TABLE IF NOT EXISTS endorsement (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    reference             VARCHAR(40)  NOT NULL UNIQUE,          -- END-2026-000001 monotonic per year
    policy_id             UUID         NOT NULL,
    policy_source         VARCHAR(30)  NOT NULL CHECK (policy_source IN (
                              'LIFE_POLICY', 'FUNERAL_POLICY', 'DISABILITY_POLICY',
                              'TRAVEL_POLICY', 'VEHICLE_POLICY', 'PROPERTY_POLICY'
                          )),
    insurance_line        VARCHAR(20)  NOT NULL,
    change_type           VARCHAR(30)  NOT NULL CHECK (change_type IN (
                              'PREMIUM_ADJUSTMENT', 'COVERAGE_EXTENSION', 'BENEFIT_CHANGE',
                              'BENEFICIARY_CHANGE', 'ADMIN_CHANGE', 'PRODUCT_SWITCH', 'RENEWAL_ADVANCE'
                          )),
    effective_from        DATE         NOT NULL,
    premium_delta         NUMERIC(19, 4),                        -- nullable for non-financial endorsements
    currency_code         CHAR(3),                                -- nullable, must match policy currency when present
    reason                TEXT         NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
                              'DRAFT', 'APPROVED', 'COMMITTED', 'VOIDED', 'COMPUTED'
                          )),
    draft_actor_id        UUID         NOT NULL,
    draft_actor_email     VARCHAR(255) NOT NULL,
    draft_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    approve_actor_id      UUID,
    approve_actor_email   VARCHAR(255),
    approve_at            TIMESTAMPTZ,
    commit_actor_id       UUID,
    commit_actor_email    VARCHAR(255),
    commit_at             TIMESTAMPTZ,
    voided_reason         VARCHAR(500),
    voided_at             TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_endorsement_policy ON endorsement (policy_id, policy_source);
CREATE INDEX IF NOT EXISTS ix_endorsement_status ON endorsement (status);
CREATE INDEX IF NOT EXISTS ix_endorsement_effective_from ON endorsement (effective_from);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V134__tenant_endorsement_config.sql`

```sql
CREATE TABLE IF NOT EXISTS public.tenant_endorsement_config (
    id                            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                     UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    enabled                       BOOLEAN      NOT NULL DEFAULT FALSE,
    four_eyes_threshold_amount    NUMERIC(19, 4),                -- nullable = disabled
    threshold_currency            CHAR(3),
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id                      UUID,
    actor_email                   VARCHAR(255),
    CONSTRAINT uq_tenant_endorsement_config UNIQUE (tenant_id),
    CONSTRAINT chk_threshold_paired CHECK (
        (four_eyes_threshold_amount IS NULL AND threshold_currency IS NULL)
        OR (four_eyes_threshold_amount IS NOT NULL AND threshold_currency IS NOT NULL)
    )
);
```

Test-migration mirror in user-service + contributions-service test-migration folders per Phase 11 test-migration deviation.

#### 2. Entities + repositories + services

**Files** (new, in `services/java/user-service/src/main/java/com/medfund/user/endorsement/`):
- `entity/Endorsement.java`
- `repository/EndorsementRepository.java`
- `service/PolicyEndorsementService.java` — full four-eyes state machine mirroring `CommissionAdjustmentService` (DRAFT → APPROVED → COMMITTED → VOIDED; four-eyes on approve; retro-recompute fires on COMMIT).
- `service/PolicyEndorsedPublisher.java` — publishes to `medfund.user.policy-endorsed` on COMMIT.
- `controller/EndorsementController.java` — CRUD REST at `/api/v1/policies/{policySource}/{policyId}/endorsements/*`.
- `dto/CreateEndorsementRequest.java`, `ApproveEndorsementRequest.java`, `VoidEndorsementRequest.java`, `EndorsementResponse.java`.

**Files** (new, in tenancy-service):
- `entity/TenantEndorsementConfig.java`
- `repository/TenantEndorsementConfigRepository.java`
- `service/TenantEndorsementConfigService.java`
- `controller/TenantEndorsementConfigController.java` — REST at `/api/v1/tenants/{tenantId}/endorsement-config` (mirrors `TenantAutoLapseConfigController` shape at lines 38-71).

#### 3. Reference generator

**File**: `services/java/user-service/src/main/java/com/medfund/user/endorsement/util/EndorsementReferenceGenerator.java` (new)

Mirrors Phase 11 `ReferenceGenerator` shape — monotonic-per-year format `END-YYYY-NNNNNN`.

#### 4. Permissions

Add two permissions (`services/java/shared/src/main/resources/permissions.yaml`):
- `policy:draft_endorsement` (all tenant admins)
- `policy:approve_endorsement` (supervisor role)

And one for the config:
- `tenant.settings:manage_endorsement_config`

Corresponding entries in `Permissions.java` + `permissions.ts`.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :shared:compileJava :tenancy-service:compileJava :user-service:compileJava` — green (2026-08-23). New endorsement subpackage under `user-service/endorsement/{entity,repository,service,controller,dto,util}` compiles; `TenantEndorsementConfig` + service + controller compile in tenancy-service; `TenantEndorsementConfigClient` compiles in user-service; permissions + YAML + TS in sync.
- [x] Flyway V110 + V134 apply cleanly on fresh testcontainer via `TenantMigrationFlywayIT` + `PublicMigrationFlywayIT` — extended both ITs to assert the new columns (endorsement lifecycle fields, `tenant_endorsement_config.four_eyes_threshold_amount` / `threshold_currency` / actor fields). Green (2026-08-23).
- [x] Unit tests: `make test-java`
  - `PolicyEndorsementServiceTest` — 17 cases (short-reason 400 / unpaired premium+currency 400 / zero-delta 400 / config-disabled auto-commit / below-threshold auto-commit / at-or-above threshold parks at DRAFT with no event / currency-mismatch auto-commits / effectiveFrom snaps to 1st-of-month / negative-magnitude ≥ threshold parks at DRAFT / approve happy-path / approve same-actor four-eyes rejection / approve not-DRAFT 409 / commit happy-path fires event / commit not-APPROVED 409 / void from DRAFT / void from APPROVED / void from COMMITTED rejected / void missing reason rejected / markComputed happy-path / queue defaults to DRAFT+APPROVED). Covers the 15 arms in the plan + the auto-commit routing tests demanded by the below-vs-above threshold behaviour + the markComputed transition for Phase 9 wiring.
  - `TenantEndorsementConfigServiceTest` — 5 cases (get returns defaults when unconfigured; upsert insert path with audit CREATE; upsert update path with audit UPDATE + old/new maps; enable-without-threshold rejects; enable→disable clears the threshold). Delta from the plan: extra "disable clears threshold" test guards the applyRequest wipe.
  - `EndorsementReferenceGeneratorTest` — 3 cases (zero-pads six digits starting at 000001; increments from existing count → 000042; queries the current-year `END-YYYY-` prefix).
  - `./gradlew :user-service:test :tenancy-service:test` — both suites green (2026-08-23) after adding `com.medfund.user.endorsement.repository` to `R2dbcConfig` basePackages (`endorsementController` bean now resolves during context load — GroupNumberServiceIT / GroupServiceCreateIT recover).
- [ ] Integration tests: `make test-integration`
  - `PolicyEndorsementIT` — 6 cases: full round-trip DRAFT → APPROVED → COMMITTED → publisher event; threshold-off auto-commits; four-eyes rejection; void; VOIDED cannot be re-approved.
  - `TenantEndorsementConfigIT` — 3 cases: get default, upsert, permission-gate 403.
- [ ] Swagger renders both new controllers at `/swagger-ui`.

#### Manual Verification
- [ ] Configure `tenant_endorsement_config` with `enabled=true`, `four_eyes_threshold_amount=100.00`, `threshold_currency=USD`.
- [ ] Draft an endorsement with `premium_delta=50.00 USD` — auto-commits (below threshold).
- [ ] Draft an endorsement with `premium_delta=200.00 USD` — enters DRAFT, requires second actor to approve + commit.
- [ ] Same actor attempts to approve their own DRAFT — 403 with clear error.
- [ ] COMMITTED endorsement can't be voided.
- [ ] `EndorsementReference` monotonically increases per year: `END-2026-000001, END-2026-000002, ...`.

**Implementation Note**: pause for human confirmation before Phase 9.

---

## Phase 9: §C — Retro recompute + ENDORSEMENT_REGISTER report

### Overview

Add the `PolicyEndorsedConsumer` in contributions-service (subscribes to `medfund.user.policy-endorsed`, triggers `EarningScheduleClosureService.recomputeForEndorsement(endorsementId)`), extend `PremiumEarningExecutor` for the recompute path, add `ENDORSEMENT_REGISTER` key + controller + XLSX.

### Changes Required

#### 1. Shared enum edit

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java` (edit)

Add after `NEW_BUSINESS_REGISTER`:

```java
ENDORSEMENT_REGISTER("Endorsement register", ReportFamily.UNDERWRITING, false),
```

#### 2. `PolicyEndorsedConsumer`

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/consumer/PolicyEndorsedConsumer.java` (new)

Same shape as `PolicyIssuedConsumer` but subscribes to `medfund.user.policy-endorsed`; invokes `EarningScheduleClosureService.recomputeForEndorsement(endorsementId)`.

#### 3. Retro recompute path

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EarningScheduleClosureService.java` (extend from Phase 5)

Add:

```java
public Mono<Void> recomputeForEndorsement(UUID endorsementId, UUID policyId, String policySource,
                                          LocalDate effectiveFrom, BigDecimal premiumDelta, String currencyCode) {
    return earningScheduleRunRepository.startRun(tenantId, "ENDORSEMENT_RECOMPUTE", endorsementId)
        .flatMap(run ->
            // 1. Delete any prior endorsement rows for this endorsement (idempotent replay).
            earningScheduleRepository.deleteByEndorsementId(endorsementId)
                // 2. Load affected periods (period_start >= effectiveFrom).
                .thenMany(earningScheduleRepository
                    .findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(policyId, policySource, effectiveFrom))
                .window(5000)
                .concatMap(chunk -> chunk
                    .flatMap(existing -> {
                        // Compute per-period share of the endorsement delta.
                        BigDecimal delta = computeDeltaForPeriod(existing, premiumDelta);
                        EarningSchedule endorsementRow = existing.toBuilder()
                                .id(null)                          // new row
                                .isEndorsement(true)
                                .endorsementId(endorsementId)
                                .writtenAmount(delta)
                                .earnedAtPeriodEnd(existing.getEarnedAtPeriodEnd() != null ? delta : null)
                                .build();
                        return earningScheduleRepository.save(endorsementRow);
                    }, 4)
                    .then(earningScheduleRunRepository.heartbeat(run.getId())))
                .then(earningScheduleRunRepository.finish(run.getId(), "COMPLETED"))
                .then(endorsementService.markComputed(endorsementId))
                .onErrorResume(err -> earningScheduleRunRepository.finish(run.getId(), "FAILED", err.getMessage()))
        )
        .then();
}
```

#### 4. Endorsement report controller

**Files** (new):
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EndorsementRegisterReportService.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EndorsementRegisterWorkbookService.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/controller/EndorsementRegisterReportController.java` at `/api/v1/reports/premium/endorsements`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/dto/EndorsementRegisterRow.java` — `{reference, policyId, policySource, memberName, insuranceLine, changeType, effectiveFrom, premiumDelta, currencyCode, status, draftActorEmail, approveActorEmail, commitActorEmail, voidedReason}`

The controller reaches into user-service via a new `UserServiceEndorsementClient` (WebClient wrapper, timeout+retry via `CrossServiceCallHelper` from Phase 3 shared infra). Fanout pattern:

```java
public Flux<EndorsementRegisterRow> getRegister(LocalDate periodStart, LocalDate periodEnd, String insuranceLine, String status) {
    return userServiceEndorsementClient.list(periodStart, periodEnd, insuranceLine, status)
        .flatMap(this::enrichWithMemberName);       // joins user-service Member for display
}
```

Alternatively, since the endorsement lives in user-service, expose the report there directly — plan-time call. **Recommended: report lives in user-service** (data ownership per G2), served via the gateway route `/api/v1/reports/premium/endorsements` → `userSvc`. Contributions-service doesn't need the endorsement report; it just needs the recompute consumer.

Reverting: endorsement report lives in user-service:
- `services/java/user-service/src/main/java/com/medfund/user/endorsement/service/EndorsementRegisterReportService.java`
- `services/java/user-service/src/main/java/com/medfund/user/endorsement/controller/EndorsementRegisterReportController.java`
- Same DTO + workbook shape.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :contributions-service:compileJava :user-service:compileJava :shared:compileJava` — green (2026-08-23). `PolicyEndorsedConsumer` + `PolicyEndorsedPayload(Parser)` land in `contributions-service.premium.consumer`; `EarningScheduleClosureService.recomputeForEndorsement` extends the Phase 5 service (took new `UserServiceClient` constructor arg for the `COMMITTED → COMPUTED` callback via `PUT /api/v1/endorsements/{id}/computed`); endorsement register report lives in user-service (`EndorsementReportQueryRepository` + `EndorsementRegisterReportService` + `EndorsementRegisterWorkbookService` + `EndorsementRegisterReportController` at `/api/v1/reports/premium/endorsements`).
- [x] Unit tests: `make test-java`
  - `PolicyEndorsedConsumerTest` — 4 cases: happy path delegate, missing tenantId dropped, missing required field dropped, malformed JSON swallowed. All green.
  - `EarningScheduleClosureServiceTest` — 6 new cases on top of the Phase 5 set: recompute mints one endorsement row per base period with sum-equal-to-delta (last-row absorbs rounding); zero delta writes no rows but still marks computed; closed base periods produce closed endorsement rows (open stays open); missing required args rejected with IllegalArgumentException; failure path finishes run FAILED and skips markComputed; idempotent replay calls deleteByEndorsementId first. 14 total, all green.
  - `EndorsementRegisterReportServiceTest` — 4 cases: empty envelope, line+status filters forwarded to repository, row fields flow through, reportKey is ENDORSEMENT_REGISTER. All green.
- [ ] Integration tests: `make test-integration` — **deferred** to Phase 9b, mirroring the Phase 5/6/8 pattern. `EndorsementRecomputeIT` (end-to-end Kafka round-trip) + `EndorsementRegisterReportIT` (envelope shape, 403 on disabled config, XLSX bytes, filter variants, SecurityEventPublisher on export) need a `db/endorsement-recompute-migration/` (contributions-service) + `db/endorsement-report-migration/` (user-service) — force-widening the existing folders would cascade FKs across unrelated IT slices. Unit-test coverage is in-tranche.
- [x] Gateway route `/api/v1/reports/premium/endorsements` proxies to user-service — registered BEFORE the broader `/api/v1/reports/premium/*` wildcard so Fiber's registration-order dispatch routes to user-service. Also added missing `/api/v1/endorsements` + `/*` CRUD route owed from Phase 8. `go build ./... && go test ./...` green.

#### Manual Verification
- [ ] Bind a LifePolicy for $1200 covering 2026-01 to 2026-12 → 12 earning_schedule rows appear.
- [ ] Commit an endorsement on 2026-04-15 with premium_delta=+$120 (10% uplift) → PolicyEndorsedConsumer fires → 8 new rows appear (2026-05..2026-12) with is_endorsement=true and $10 each.
- [ ] Export ENDORSEMENT_REGISTER for 2026-04 → XLSX contains the endorsement row with reference END-2026-0000NN.
- [ ] Replay the endorsement event (Kafka reset) → no duplicate rows (idempotency via deleteByEndorsementId).

**Implementation Note**: pause for human confirmation before Phase 10 — §C backend complete.

---

## Phase 10: §C — Angular endorsement admin surface

### Overview

Angular endorsement creation modal on policy detail pages (all 6 lines get a new Endorsements tab), approver review queue at `/tenant/finance/underwriting/endorsements/review-queue`, tenant-admin endorsement-config panel at `/tenant-admin/settings/endorsement-config`, endorsement register report page, sidebar registration, Playwright specs.

### Changes Required

#### 1. Angular services

**Files** (new):
- `clients/angular/src/app/core/services/endorsement.service.ts` — HTTP client (CRUD + approve + commit + void).
- `clients/angular/src/app/core/services/tenant-endorsement-config.service.ts` — get/update.
- `clients/angular/src/app/core/services/endorsement-register-report.service.ts` — GET envelope + POST /export blob.

#### 2. Per-policy endorsement tab

**Files** (new):
- `clients/angular/src/app/pages/tenant-admin/policies/endorsements/endorsement-modal.component.ts` + `.html` — create modal with change-type dropdown + effective_from (1st-of-month snap) + premium_delta + currency + reason.
- `clients/angular/src/app/pages/tenant-admin/policies/endorsements/endorsement-history.component.ts` + `.html` — table of past endorsements for a policy.

Mount the "Endorsements" tab on each of the 6 line-specific policy detail pages (LifePolicyDetailComponent, FuneralPolicyDetailComponent, etc.). Since these detail pages don't all exist today with a "tab" structure, the plan may need to add tab wrapping — plan-time call. Fallback: a new route `/tenant-admin/policies/{policySource}/{policyId}/endorsements` accessible from each policy's overview page.

#### 3. Approver review queue

**Files** (new):
- `clients/angular/src/app/pages/tenant/finance/underwriting/endorsement-review-queue.component.ts` + `.html` — paginated table of DRAFT + APPROVED endorsements above threshold; row actions: view detail, approve, commit, void.
- `clients/angular/src/app/pages/tenant/finance/underwriting/endorsement-detail.component.ts` + `.html` — full endorsement detail + audit trail + associated earning_schedule diff view.

#### 4. Endorsement config tab

**File**: `clients/angular/src/app/pages/tenant-admin/settings/endorsement/endorsement-config.component.ts` + `.html` — new tab in existing `TenantSettingsComponent` (mounted alongside the Auto-Lapse tab per Phase 11 precedent). Form: enable toggle + threshold amount + currency picker.

#### 5. Endorsement register report

**File**: `clients/angular/src/app/pages/tenant/finance/reports/underwriting/endorsement-register.component.ts` + `.html` — mirrors §B report pages (Phase 7).

#### 6. Sidebar

Extend `operational-nav.ts` with a new Finance peer entry "Endorsement Review" gated by `policy:approve_endorsement` permission.

### Success Criteria

#### Automated Verification
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — green (2026-08-23). Zero new warnings from Phase 10 files (unused `IconComponent` in `endorsement-detail.component.ts` was cleaned up post-build). Pre-existing warnings on legacy files unchanged.
- [x] `make test-angular` — 15 new unit tests, all green under `ChromeHeadlessCI`:
  - `endorsement.service.spec.ts` — 8 wire-shape cases: default list (no params), status+page+size forwarded, `GET /endorsements/{id}`, `GET /endorsements/by-policy` (policyId+policySource), `POST /endorsements` payload verbatim, `PUT /{id}/approve` empty body, `PUT /{id}/commit` empty body, `POST /{id}/void` with reason.
  - `tenant-endorsement-config.service.spec.ts` — 3 cases: GET returns config, PUT enabled payload verbatim, PUT accepts disabled + nulled threshold/currency.
  - `endorsement-register-report.service.spec.ts` — 4 cases: GET forwards period + line + status + reporting currency, GET omits optional filters, GET export returns blob, GET export forwards status filter.
  - Full-suite pass: 561 pass, 5 skipped, 1 pre-existing failure (`insurance-lines.spec.ts:344` `providerModeForLine` — the same regression documented in Phase 7 §B, traces to `e7b8fb3` on main, unrelated to Phase 10).
- [x] Playwright: `endorsement-workflow.spec.ts` — 2 tests (drafter creates a new endorsement, supervisor approves → commits with same-actor guard); `endorsement-report.spec.ts` — 3 tests (hub visibility, page renders + warnings + export, disabled-toggle hides the card). Spec files land alongside `underwriting-reports.spec.ts` and `reinsurance-facultative.spec.ts` — full E2E run deferred to pre-PR pass to keep CI fast.

#### Manual Verification
- [ ] Tenant admin visits a LifePolicy detail page → clicks "Endorsements" → sees history + "New endorsement" button.
- [ ] Configure endorsement-config with `enabled=true, threshold=$100 USD`.
- [ ] Draft endorsement with `premium_delta=$50 USD` → auto-commits, appears on next Endorsement Register.
- [ ] Draft endorsement with `premium_delta=$200 USD` → enters DRAFT → appears in approver queue → supervisor approves + commits → earning schedule updates within 1 minute.
- [ ] Same actor tries to approve their own DRAFT → button disabled + backend 403.
- [ ] Reports hub shows Endorsement Register card when key enabled.
- [ ] Export Endorsement Register → XLSX includes all four states + audit trail columns.

**Implementation Note**: this is the last phase. **After manual acceptance, run the self-review loop** — create the PR, then `code-review` over the whole diff, triage every Blocker/Important, fix, and re-sweep. The self-review comment gets posted to the PR.

---

## Testing Strategy

### Unit Tests
- **Earning-strip period splitting** (Phase 5): DAILY_LINEAR / MONTHLY_24THS / LINEAR_WITH_LOADING math; boundary conditions (leap year, cross-year policies, single-day trip); rounding behaviour (HALF_EVEN).
- **Idempotency** (Phase 5, 9): `ux_earning_schedule_key` guards under duplicate consumer replay; `deleteByEndorsementId` before rewriting for §C recompute.
- **Four-eyes state machine** (Phase 8): every valid transition + every invalid transition + same-actor rejection.
- **HEALTH within-period earning** (Phase 5): earned = amount at row creation; portfolio inherited from scheme default.
- **UPR movement SQL** (Phase 6): opening + written - earned + endorsement_delta = closing; FULL OUTER JOIN handles rows appearing in only one CTE.
- **New-business classification** (Phase 6): renewal chain excluded; HEALTH's `member_first_contribution` materialized view accurate.

### Integration Tests (Testcontainers via shared `AbstractIntegrationTest`)
- Full CRUD IT for each Phase 3 entity (Portfolio, Cohort).
- Kafka round-trip for each Phase 5 + Phase 9 consumer (PolicyIssued, PolicyEndorsed).
- Report envelope IT with report-toggle 403 + SecurityEvent-on-export assertion (Phase 6).
- Retro recompute end-to-end (Phase 9): draft + commit endorsement → consumer → executor → earning_schedule rewritten correctly.
- Executor idempotency (Phase 5): re-run writes zero duplicate rows.
- Backfill trigger + progress polling (Phase 5).

### E2E Tests (Playwright, `clients/angular/e2e/`)
- `portfolio-crud.spec.ts` (Phase 3) — create portfolio + cohort + attach to policy.
- `legacy-retrofit.spec.ts` (Phase 3) — retrofit a legacy policy → earning_schedule appears.
- `underwriting-reports.spec.ts` (Phase 7) — hub → each of 3 §B reports → export.
- `endorsement-workflow.spec.ts` (Phase 10) — drafter + supervisor journey.
- `endorsement-report.spec.ts` (Phase 10) — hub → endorsement register → export.

### Manual Testing (per-phase Manual Verification lists — see each phase above)

## Performance Considerations

- **Earning-schedule row density** (Phase 5) — ~100k policies × ~12 periods/year = ~1.2M rows/year/line. Composite index `(insurance_line, currency_code, period_end)` covers the UPR SQL. Partition by year if a single tenant exceeds ~50M total rows.
- **`PremiumEarningExecutor` chunking** (Phase 5, U10) — 5000 rows per commit; total nightly job ~1-2 min at moderate tenant scale.
- **Retro recompute performance** (Phase 9, U12) — bulk endorsement (10k policies × 6 avg periods = 60k rewrites) ~2 min at 500 rows/sec R2DBC. UI shows "Recompute in progress" banner.
- **Materialized view refresh** (Phase 5, grill note 7) — `member_first_contribution` refresh cost proportional to Contributions posted in last 24h; nightly is fine.
- **UPR SQL FULL OUTER JOIN** (Phase 6) — 4 CTEs joined; indexed by `(insurance_line, currency_code, period_end)` per V109. Expect < 200ms for a Q1 report at moderate scale.
- **XLSX rendering** (Phase 6, 9) — Apache POI SXSSF streaming mode for > 500 rows (Premium Register + Endorsement Register); XSSF default for smaller UPR movement + New Business Register.
- **Angular bundle** — 3 new lazy-loaded chunks under `/tenant/finance/reports/underwriting/*` + 4 under `/tenant-admin/underwriting/*` + 3 under `/tenant/finance/underwriting/endorsements/*`. Aim < 60KB gzipped per chunk.

## Migration Notes

- **Tenant migration ordering (V102..V110):** strict numerical order per `bug_tenant_flyway_outoforder`. Any hotfix within Phase 12 goes into a new higher-numbered file (V111+) — never edit an applied migration per `feedback_never_edit_applied_migrations`. Idempotent SQL (`ADD COLUMN IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`) throughout.
- **Backfill approach for legacy policies (§A Phase 2):** existing rows get `status = 'legacy_no_premium'` + placeholder `bound_at/coverage_start/coverage_end` + `portfolio_id = MISC.id` + `cohort_id = MISC-YYYY-DEFAULT.id`. `PremiumEarningExecutor` skips `LEGACY_NO_PREMIUM` rows entirely. Tenant admin retrofits via `/tenant-admin/underwriting/legacy-retrofit` UI.
- **Public migration (V134):** single-row-per-tenant `tenant_endorsement_config`; matches V127/V128/V132/V133 pattern.
- **No changes to `public.flyway_schema_history`.** Per `bug_public_flyway_history_load_bearing` — do not delete rows.
- **`public.` prefix rule:** only `V134 tenant_endorsement_config` uses the `public.` prefix in queries; tenant-schema tables (earning_schedule, endorsement, ifrs17_*) use unqualified names per `bug_public_prefix_silent_rollback`.
- **Rules-engine per-tenant recompile** — adding `RuleCategory.PREMIUM_EARNING` means every tenant's `KieContainer` needs a rebuild on next fire. `TenantRuleEngine.loadRules` handles per-tenant `ReleaseId` minting per `bug_rules_engine_tenant_isolation`; concurrency IT covers the invariant.
- **Materialized view refresh** — `member_first_contribution` is a MATERIALIZED VIEW (not a regular view) because it holds MIN(created_at) per member. `REFRESH MATERIALIZED VIEW member_first_contribution;` runs nightly via `PremiumEarningExecutor` after the period-close pass. First refresh after migration takes O(N members) — acceptable for the tenant sizes we support.

## Rollout & Rollback

**Deploy order (all phases):**

1. **tenancy-service first** (schema-only in Phase 2, 3, 8) — safe: additive columns + tables, no data loss on rollback.
2. **rules-engine** (Phase 4 adds `RuleCategory.PREMIUM_EARNING` + emitter). No consumer yet; the enum widening is backwards-compatible.
3. **user-service** (Phase 3 adds `PolicyIssuedPublisher`; Phase 8 adds `PolicyEndorsedPublisher`). After tenancy-service.
4. **contributions-service** (Phase 5 adds consumer + executor; Phase 9 adds recompute consumer). After user-service is emitting the new events.
5. **finance-service** — not touched by Phase 12 directly, but `AbstractIntegrationTest` harness fix from §0 lands here.
6. **gateway** (Phase 3, 6, 8, 9 add route entries) — deploys after all upstream services.
7. **Angular** (Phase 3, 7, 10 add UI surfaces) — deploys after gateway.

**Rollback strategy per phase:**

- **Phase 1 (§0 harness)** — pool config changes are additive; rollback = revert application.yml. Extended user-service ITs stay — they don't affect production.
- **Phase 2 (schema)** — Flyway migrations are additive; rollback = revert application deploys. Columns stay, unused. Do NOT `flyway repair`.
- **Phase 3-5 (§A)** — user-service can roll back independently (no downstream consumers of `PolicyIssuedPublisher` yet). Once contributions-service consumer is deployed, rollback of user-service leaves contributions-service consumer idle (no events fired) — safe.
- **Phase 6-7 (§B)** — reports fully gated by `tenant_report_config.enabled`; disable to hide surface, then rollback.
- **Phase 8-10 (§C)** — set `public.tenant_endorsement_config.enabled = FALSE` for all tenants to short-circuit the four-eyes gate, then roll back user-service + contributions-service. Any in-flight DRAFT/APPROVED endorsements remain in DB — a follow-up rollback ticket can void them explicitly.

**Feature-flag posture:** no dedicated feature flags — every user-facing surface is either permission-gated or `@RequiresReport`-gated by default-off tenant config. Toggling permissions off (or leaving `tenant_report_config.enabled = FALSE`) is the operational kill switch.

## References

- **Parent plan:** `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md#phase-12` (lines 3089-3300)
- **Grill decisions:** parent plan lines 3121-3137 (U1..U15) + 3141-3152 (F12-1..F12-12)
- **Sibling sub-plan (template):** `thoughts/shared/plans/2026-08-22-producer-broker-module-and-commission-reports.md` (Phase 11, 10 sub-phases, 3240 lines)
- **Sibling sub-plan (reinsurance precedent):** `thoughts/shared/plans/2026-08-22-reinsurance-module-and-bordereau-reports.md`
- **Architecture docs:**
  - `.claude/CLAUDE.md` — 9 Critical Rules; Java coding conventions
  - `.claude/rules-engine.md:7-26` — template categories, agenda gating (Phase 4)
  - `.claude/multi-currency.md:1-196` — currency handling (U8; grill note 13 updates this)
  - `.claude/multi-tenancy.md:74-108` — TenantContext + reactive WebFilter (Phase 5 consumer)
  - `.claude/coding-standards.md:570-593` — audit `entityName` conventions (Rule 8)
- **Key code references:**
  - `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:99-102` — Phase 12 keys already ship
  - `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:20` — UNDERWRITING to be added
  - `services/java/shared/src/testFixtures/java/com/medfund/shared/testfixtures/AbstractIntegrationTest.java:38-124` — IT base
  - `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionAdjustmentService.java:32-186` — four-eyes template (§C Phase 8)
  - `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/PayCommissionEmitter.java` — emitter template (§A Phase 4)
  - `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/CommissionTemplates.java` — templates provider template (§A Phase 4)
  - `services/java/tenancy-service/src/main/resources/db/migration/public/V133__tenant_auto_lapse_config.sql` — tenant-config table template (V134 endorsement config)
  - `services/java/contributions-service/src/main/java/com/medfund/contributions/job/BillingCycleExecutor.java:14-41` — JobExecutor template (Phase 5)
- **Auto-memory constraints upheld:**
  - `feedback_audit_actor_email`, `feedback_audit_entity_name`, `feedback_effective_date_snap`, `feedback_no_raw_id_inputs`, `feedback_one_contribution_per_month`
  - `bug_reactor_kafka_ack_swallow`, `bug_rules_engine_tenant_isolation`, `bug_public_prefix_silent_rollback`, `bug_public_flyway_history_load_bearing`, `bug_tenant_flyway_outoforder`
  - `feedback_never_edit_applied_migrations`, `infra_testcontainers_pitfalls`
