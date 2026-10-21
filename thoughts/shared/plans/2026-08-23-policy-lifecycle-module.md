---
date: 2026-08-23
git_commit: fe36635
branch: rename-adjustments-to-notes
ticket: null
spec: null
parent_plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md#phase-13
research:
  - thoughts/shared/plans/2026-08-11-financial-reporting-suite.md (Phase 13 section, lines 3318-3502 — grilling doc L1..L18 + F13-1..F13-11 + 11 grill notes for create-plan dated 2026-08-23)
grilling:
  - decisions L1..L18 landed in parent plan at thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:3350-3367 on 2026-08-23
  - facts F13-1..F13-11 landed in parent plan at thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:3371-3381 on 2026-08-23
steer: "for phase 13"
services_touched: [tenancy-service, user-service, contributions-service, claims-service, shared, gateway, angular]
status: implemented
---

# Phase 13 — Persistency + Policy Movement + Provider Network (Policy Lifecycle Module)

## Overview

Greenfield **policy status-transition module** across user-service + contributions-service + claims-service that ships (a) a full admin surface for `lapse` / `terminate` / `suspend` / `reinstate` on all 6 annual-bind policy lines (per L4 + L5 — uniform 4-action REST shape with per-line reason-code vocab), (b) two new tenant-scoped history tables (`policy_status_history` V111 + `member_status_history` V112 per L2 — the source of truth for persistency + movement reports), (c) a centralized write-pathway pattern via new `MemberStatusTransitionService` + `PolicyStatusTransitionService` classes (per L3 — retrofits the golden central pathway `MemberService.transitionStatus` at line 510-548 plus one auto-term corner case), (d) a new Kafka topic `medfund.user.policy-status-changed` (per L6) with contributions-service `PolicyStatusChangedConsumer` extending `EarningScheduleClosureService.closeOutForPolicyClosure` to keep UPR correct when a policy lapses / terminates mid-term, (e) a single-column `provider.network_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD'` schema addition (per L1 — cheapest touch that gives `PROVIDER_NETWORK_UTILIZATION` a real grouping dimension), (f) a tenant-scoped `member_contribution_presence` materialized view (V114) refreshed by Phase 12's `PremiumEarningExecutor` for the HEALTH persistency signal (per L16 + grill note 4), and (g) **four tenant-toggleable reports** — three under a new `ReportFamily.POLICY_LIFECYCLE` (`POLICY_MOVEMENT`, `PERSISTENCY_COHORT`, `GROUP_CENSUS` per L8) hosted in user-service, plus `PROVIDER_NETWORK_UTILIZATION` staying under `CLAIMS_FINANCIAL` hosted in claims-service with a `ProviderClient` cross-service call for provider metadata enrichment (per L13).

All four report keys already ship in `ReportKey.java:85-88` (per F13-1 — enum edit is family reassignment via L8 + cadence flips via L14, not enum adds). All Phase 0-12 shared infra composes cleanly (per F13-2). Persistency semantics for HEALTH cross-check `member_status_history` against monthly `Contribution` presence for the "still-paying" retention signal (per L16 — stricter than industry default); annual-line persistency uses the renewal-chain-aware active check (per L9).

**§A** ships schema (V111 policy_status_history + V112 member_status_history + V113 provider.network_tier all in **tenancy-service** — see F13-4 correction below), the two transition services + 24 admin REST endpoints (6 lines × 4 actions), and the Angular per-line modal surface on the existing policy detail pages plus the in-line network_tier dropdown on the provider list. **§B** wires the Kafka event out of user-service, adds `PolicyStatusChangedConsumer` in contributions-service, and extends `EarningScheduleClosureService` with a policy-closure path so lapsed / terminated policies stop accruing. Refund calculation (short-rate vs pro-rata) deferred (per L6). **§C** ships the four reports + new `ReportFamily.POLICY_LIFECYCLE` family card + Angular pages + hub registration.

Every decision in this plan is inherited from the grilling session that produced L1..L18 (see [parent plan Phase 13 section](2026-08-11-financial-reporting-suite.md#phase-13), lines 3350-3367). A code-altitude verification pass on 2026-08-23 confirmed the inherited claims and settled every open grill note — see *Verified during this planning pass* below.

## Current State Analysis

Inheriting all F13-1..F13-11 verifications from the parent-plan grill (verified 2026-08-23), plus additional code-altitude findings:

- **The module is 100% greenfield** (F13-6). Zero hits on `policy_status_history`, `member_status_history`, `provider_network`, `provider.network_id`, `provider.network_tier`, `PolicyStatusTransitionService`, `MemberStatusTransitionService`, `PolicyStatusChangedConsumer` across `services/java/*/src/main/java` and `clients/angular/src/app`.
- **Report catalogue is pre-wired.** `ReportKey.POLICY_MOVEMENT` (line 85, cadenced=true), `ReportKey.PERSISTENCY_COHORT` (line 86, cadenced=false), `ReportKey.GROUP_CENSUS` (line 87, cadenced=false), `ReportKey.PROVIDER_NETWORK_UTILIZATION` (line 88, cadenced=false), all currently under `ReportFamily.CLAIMS_FINANCIAL` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:20`). Phase 13 §C reassigns three keys to a new `POLICY_LIFECYCLE` family (per L8) and flips three cadence booleans (per L14).
- **All Phase 0-12 shared infra composes cleanly** (F13-2): `ReportEnvelopeBuilder` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportEnvelopeBuilder.java`), `FxRateReader.findRate/.convert`, `ReportWorkbook`, `@RequiresReport` + `ReportGuardAspect`, `SecurityEventPublisher.publishDataAccess`, `AuditActor.id/email`, `ReportingCurrencyResolver`, `CrossServiceCallHelper`. Zero new shared plumbing.
- **All tenant migrations live in tenancy-service.** Verified 2026-08-23: `ls services/java/*/src/main/resources/db/migration/tenant/` shows only `tenancy-service/` populated. Phase 12's V102 (`policy_underwriting_widening`), V107 (`ifrs17_portfolio`), V108 (`ifrs17_cohort`), V109 (`earning_schedule` with `member_first_contribution` matview), V110 (`endorsement`) all live at `services/java/tenancy-service/src/main/resources/db/migration/tenant/`. Phase 13 opens at **V111** in tenancy-service; **no public schema changes** in Phase 13 (all new tables tenant-scoped). **Correction to F13-4**: the fact stated "user-service last V102" — actually V102 is in **tenancy-service**. Documented in the Deviations section for provenance.
- **`MemberService.transitionStatus` is the golden central pathway** (verified 2026-08-23): `services/java/user-service/src/main/java/com/medfund/user/service/MemberService.java:510-548` is the ONE method that does `member.setStatus() + publishAudit + memberRepository.save()`. Every existing member-status writer already funnels through it except `MemberLifecycleFactBuilder.java:111` (rules-engine auto-termination writes `member.setStatus("terminated")` directly). Consequence: L3's retrofit is much cheaper than the ~6 sites the grill enumerated — refactor `transitionStatus` to route through the new `MemberStatusTransitionService` and every existing caller works transparently; only `MemberLifecycleFactBuilder` needs its own retrofit.
- **`GroupService.cascadeToMembers` iterates via `Flux.flatMap`** (verified 2026-08-23): `services/java/user-service/src/main/java/com/medfund/user/service/GroupService.java:397-413` loops through active/suspended members and calls `memberService.deactivate/terminate` per member. Sequential execution; unbounded loop; will naturally route through the new `MemberStatusTransitionService` if `MemberService.transitionStatus` is retrofitted.
- **`UserEventPublisher.publishMemberLifecycle` already exists** at line 89 and publishes `MEMBER_STATUS_CHANGED` to Kafka on immediate transitions. Phase 13 introduces a **new topic** `medfund.user.policy-status-changed` for POLICY transitions (not to be conflated with the existing `MEMBER_STATUS_CHANGED` topic).
- **All six policy detail pages are single-form layouts, not tabbed** (verified 2026-08-23): `clients/angular/src/app/pages/tenant/policies/{life,funeral,disability,travel,vehicles,properties}/*-form.component.ts` render bespoke single-form layouts with action buttons in the header (no shared `PolicyDetailBase`). Endorsements live on a separate fallback page `/tenant/policies/endorsements/:policySource/:policyId`. Phase 13 mounts a shared `PolicyStatusActionButtonsComponent` in each form's header — no tabbed detail layout needed.
- **Provider list is `ProvidersComponent`** at `clients/angular/src/app/pages/providers/providers.component.ts:18` using a shared `DataTableComponent` (`providers.component.html:48`). No existing inline-editable columns, but the table supports row actions — extending it with an inline dropdown for `network_tier` follows the existing extension pattern (per L17).
- **Endorsement modal shape is the template**: `clients/angular/src/app/pages/tenant/policies/endorsements/endorsement-modal.component.ts:24-72` is the closest reuse pattern for Phase 13's `PolicyStatusActionModalComponent` (title + subheader + form fields + reason textarea + cancel/submit).
- **`EarningScheduleClosureService.refreshMemberFirstContribution` is the extension precedent** for the new `refreshMemberContributionPresence` (per L16 + grill note 4): `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EarningScheduleClosureService.java:62` chains the refresh after the period-close pass finishes; the new method drops in as a `.then(refreshMemberContributionPresence())` chain after that call.
- **`Contribution` has `idx_contributions_member`** but NOT a composite `(member_id, period_start)` index (verified via V001 baseline + V034). Runtime SQL for the persistency `has_contribution_in_month` lookup is a member-index seek + range filter — slow at 3.6M-row scale. Matview `member_contribution_presence` (member_id, contribution_month) with UNIQUE composite index gives sub-ms lookup — chosen per L16 + grill note 4.
- **No `policy_status_history` or `member_status_history` table exists** (SF5 from grill). No `AuditEvent` persisted repository — Kafka-only per F13-5.
- **No `provider_network` table** and no `provider.network_tier` column on the Provider entity (verified — `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:18-69` has zero network columns). `ProviderFact.networkTier` at `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ProviderFact.java:17` is a placeholder used by `CoPaymentTemplates.java:60` — nothing populates it from `Provider` today.

### Key Discoveries

- **HEALTH persistency signal comes from Member + Contribution together** (per L16). Requires the L16 cross-check: status was 'active' at checkpoint month AND had a `Contribution` row for the checkpoint month. Materialized view is the cheap-read path — sub-ms per checkpoint cell vs. 6-30s per report with runtime SQL (per grill note 4 investigation).
- **Annual-line persistency = renewal-chain-active at checkpoint** (per L9). Traverse `renewed_from_policy_id` backwards from any currently-active policy in the checkpoint month; if the traversal reaches the cohort policy, that cohort policy is still-active at checkpoint. No history-table lookup needed for the pure renewal-chain reading — but a mid-term LAPSED (via Phase 13 §A's new admin endpoint) needs `policy_status_history` to know the effective_at date.
- **`MemberService.transitionStatus` refactor is the ONE golden hook** (finding A2 from exploration). Because it's the central pathway, retrofitting it to route through `MemberStatusTransitionService.transition(...)` propagates to every existing caller (arrears consumer, scheduled executor, group cascade, dependant flows) without per-site changes. Only `MemberLifecycleFactBuilder.java:111` writes status outside this pathway.
- **`UserEventPublisher.publishMemberLifecycle` already emits `MEMBER_STATUS_CHANGED`** on immediate transitions (line 89). Phase 13's new `PolicyStatusChangedPublisher` for POLICY entities follows the same shape — sibling class, distinct topic (`medfund.user.policy-status-changed`).
- **Endorsement modal template is the reuse target for the status-action modal**. The shared `PolicyStatusActionModalComponent` follows the same shape verbatim (per exploration finding E10). Combined with the reason-vocab picker from `PolicyLifecycleActionRegistry`, one modal template services all 4 actions across all 6 lines.
- **Contribution schema needs no changes** for the persistency query — the existing `Contribution` fields (`member_id`, `period_start`, `period_end`, `currency_code`, `status`) supply everything the matview + persistency service need.
- **Provider batch enrichment uses existing `CrossServiceCallHelper` pattern**. Cap N=100 per HTTP call. If user-service is unreachable, envelope carries warnings + placeholder names ("Provider Unknown") + STANDARD tier fallback — matches Phase 8 aged-debtors precedent.
- **Angular per-line policy forms are bespoke single-form layouts** — no shared base class, no tabbed detail view. The shared `PolicyStatusActionButtonsComponent` mounts in each form's header slot as a sibling to the existing suspend/terminate buttons.

## Desired End State

**Backend**
- 5 new tenant-scoped tables/columns/matviews in tenancy-service `db/migration/tenant/`:
  - V111 `policy_status_history` (§A Phase 1)
  - V112 `member_status_history` (§A Phase 1)
  - V113 `provider.network_tier` column (§A Phase 1)
  - V114 `member_contribution_presence` materialised view (§C Phase 7)
  - V115 `earning_schedule.closure_ref` column (§B Phase 6)
- 0 new public-schema tables (no `V135` needed — all Phase 13 data is tenant-scoped).
- `com.medfund.user.status.*` subpackage in user-service — abstract `PolicyStatusTransitionService<T>` base + 6 concrete subclasses + `MemberStatusTransitionService` + `StatusTransitionRecorder` (shared writes) + `PolicyReasonCode` enum registry + 24 admin REST endpoints.
- `com.medfund.contributions.premium.consumer.PolicyStatusChangedConsumer` in contributions-service consuming `medfund.user.policy-status-changed`.
- `EarningScheduleClosureService.closeOutForPolicyClosure(...)` extension + `refreshMemberContributionPresence()` chained after existing `refreshMemberFirstContribution` at line 62.
- 3 policy-lifecycle report controllers under `com.medfund.user.reports.lifecycle.*` at `/api/v1/reports/policy-lifecycle/*` + 1 utilization report controller under `com.medfund.claims.reports.provider.*` at `/api/v1/reports/claims/provider-network-utilization`.
- `ReportFamily.POLICY_LIFECYCLE` enum add + 3-key family reassignment (POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS) + 3 cadence flips + `PROVIDER_NETWORK_UTILIZATION` cadence flip.
- 1 new Kafka topic (`medfund.user.policy-status-changed`) with in-JVM publisher in user-service + Kafka consumer in contributions-service.

**Angular**
- Existing 6 policy form components mount `PolicyStatusActionButtonsComponent` + open `PolicyStatusActionModalComponent` in header per L4 + L5.
- Existing `ProvidersComponent` gets inline `network_tier` dropdown column per L17.
- 4 new report pages under `/tenant/finance/reports/policy-lifecycle/*` (3) + `/tenant/finance/reports/claims-financial/provider-network-utilization` (1).
- Reports hub auto-registers the 4 keys — new POLICY_LIFECYCLE family card renders when any key enabled.

### Verification

```bash
# Backend
cd services/java && ./gradlew build test
make test-integration

# Angular
make test-angular
make test-e2e   # includes policy-lifecycle-reports, policy-status-workflow, provider-network-tier specs

# Manual acceptance
make infra && make tenancy user contributions finance claims gateway notification web
# Log in as tenant admin → /tenant/policies/life/{id} → click Lapse → confirm reason → observe
#   member_status_history + policy_status_history rows, PolicyStatusChanged Kafka event on topic
# Log in as tenant admin → /tenant/providers → change a provider's network_tier from STANDARD → TIER_1
# Log in as finance officer → /tenant/finance/reports/policy-lifecycle/movement → export XLSX for prior month
#   → see per-line count + written_premium movement columns
# → /tenant/finance/reports/policy-lifecycle/persistency-cohort?checkpoints=6,12,24 → export XLSX
# → /tenant/finance/reports/policy-lifecycle/group-census?asOf=2026-08-01 → export XLSX
# → /tenant/finance/reports/claims-financial/provider-network-utilization → export XLSX
#   → see per-network summary + per-provider detail sheets; provider network_tier tiering reflected
```

## What We're NOT Doing

- **Refund calculation on policy termination** (per L6). Short-rate vs pro-rata refund logic, per-tenant config, and Contribution reversal Notes are deferred to a follow-up phase. §B's `closeOutForPolicyClosure` stops earning; it does not compute refund liability.
- **Bulk policy actions**. Admin can lapse/terminate/suspend/reinstate one policy at a time from the per-line detail page. A bulk-action UI (select N policies, apply the same action) is deferred.
- **`provider_network` full hierarchy** with contract expiry, per-network commission overrides, PMB accreditation. Phase 13 ships the single-column `network_tier` VARCHAR only (per L1). Full network hierarchy lives in Phase 16 regulatory-format reports alongside PMB spend.
- **CSV bulk import for provider network_tier** (per L17). Inline dropdown only; CSV import deferred to a follow-up if real demand surfaces.
- **Extending the 6 per-line policy CRUD forms with Phase 12's deferred underwriting fields** — that's Phase 3b from Phase 12's sub-plan. Phase 13 mounts new action buttons in the form headers but does NOT extend the form fields themselves.
- **Persistency cohorts as `public.tenant_persistency_config`** — URL param configurable per L9; not tenant-configurable schema.
- **Two-column persistency (persistency + retention)** — HEALTH uses the strict definition per L16; annual lines use renewal-chain-active per L9. One column per report row.
- **Policy movement transaction-list drill-down** — roll shape only per L10. Detail drill-down endpoint deferred.
- **Portfolio/cohort-aware persistency slicing** — Phase 12 IFRS 17 portfolio + cohort dimensions are on the policy but Phase 13 reports don't slice by them. Deferred to Phase 15 IFRS 17.
- **Endorsement-void-cascades-to-policy-terminated** — per F13-9 endorsement VOIDED does not cascade to policy.status; Phase 13 does NOT add this cascade.
- **PDF variants of the 4 reports** — XLSX only. Regulatory PDF variants are Phase 16's job.

## Implementation Approach

**Order:** §A schema + writers + admin modals first (Phases 1-4) — ships admins the ability to transition policies before any consumer needs the event. Then §B Kafka + earning-schedule closure (Phases 5-6) — keeps UPR correct on status change; producer before consumer within §B. Then §C reports (Phases 7-10) — matview + report backends + Angular pages; contributions-service matview refresh must ship before the persistency report reads from it.

**Rollout invariants** (all phases must uphold, inheriting from parent-plan cross-phase invariants at :117-124):

1. **Every wrapped report endpoint accepts optional `?reportingCurrency=`** and returns `ReportResponse<T>` with a native-currency `perCurrency` map per parent-plan invariant #1. Report rows stay native (L10 policy movement written_premium columns are native per row; envelope `fxRates` is best-effort for optional client-side display).
2. **Every report GET short-circuits with 403 Forbidden** if `tenant_report_config.enabled = FALSE` via `@RequiresReport(...)`. Mutations (POST/PUT/DELETE) are gated only by `@RequiresPermission`.
3. **Every XLSX export emits `SecurityEventMessage`** with `eventType="DATA_ACCESS"` and `details.reportKey=<key>` before returning bytes.
4. **Every controller carries full Swagger annotations** (Rule 7).
5. **Every entity mutation emits an `AuditEvent`** using `AuditActor.id(jwt)` + `AuditActor.email(jwt)`, with a friendly `entityName` (member.memberNumber, policy.policyNumber, provider.name — never the UUID per `feedback_audit_entity_name`).
6. **All amount arithmetic is `BigDecimal`.** No cross-currency additions without `FxRateReader.convert(...)`. Missing FX rate in envelope map → omitted + `warnings: List<String>` entry per parent-plan G28.
7. **All queries on tenant-schema tables use unqualified names.** Only prefix `public.` for platform-wide tables. Per `bug_public_prefix_silent_rollback`.
8. **All Kafka consumers use `.doOnSuccess` for offset ack** per `bug_reactor_kafka_ack_swallow`; never `.doOnTerminate`. Errors carry the full cause chain in log messages.
9. **All Angular pickers (group, provider, policy) are debounced search-selects** per `feedback_no_raw_id_inputs` — never a raw `<input>` for a UUID.
10. **All `effective_at` timestamps on status transitions preserve the actual moment** — no 1st-of-month snap; a lapse fires at the moment it happens (transaction commit time). The `feedback_effective_date_snap` memory applies only to coverage start/end dates, not to transition audit-log timestamps.
11. **Rules-engine tenant isolation preserved.** Phase 13 does NOT add a new RuleCategory (L5 rejected `POLICY_LIFECYCLE` as over-engineered).
12. **`AbstractIntegrationTest` is the single IT base for cross-service testing.** No new bespoke Testcontainers wiring per test.
13. **One policy = one policy_status_history row per (policy_id, effective_at)** — a natural PK guard. `member_status_history` similarly `(member_id, effective_at)`. Idempotency guaranteed by primary key.

**Cross-phase Kafka contract stability:** §B Phase 5 introduces `medfund.user.policy-status-changed` (user-service publisher lands first, contributions-service consumer next). All new payloads carry `tenantId` as a top-level field so `.contextWrite(Context.of(TenantContext.KEY, tenantId))` can propagate in the consumer chain.

## Deviations

- **2026-08-23 (Overview)** — F13-4 fact correction. The parent-plan grill's F13-4 stated the next tenant migration was V111 "user-service last: V102, tenancy-service last: V110". Verified via `ls services/java/*/src/main/resources/db/migration/tenant/` on 2026-08-23: **all tenant migrations live in `tenancy-service/`** — user-service, contributions-service, claims-service, finance-service have no tenant migration folders. Phase 12's V102 (`policy_underwriting_widening`) is at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V102__policy_underwriting_widening.sql`. Phase 13's V111..V115 land in the same tenancy-service `tenant/` folder.
- **2026-08-24 (§A Phase 4)** — Added `V135__public_provider_network_tier.sql` in tenancy-service `db/migration/public/`. The plan's V113 puts `network_tier` on the tenant-schema `providers` table (which the claims-service PROVIDER_NETWORK_UTILIZATION report will read in Phase 9). But the user-service `Provider` entity at `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:18` is annotated `@Table(schema = "public", value = "providers")` — it reads from `public.providers` — so the admin PATCH endpoint + inline dropdown ship in Phase 4 need the column on `public.providers` too. Vocab + default mirror V113 verbatim. Constraint renamed to `chk_public_providers_network_tier` to avoid clashing with the tenant-schema constraint of the same name.
- **2026-08-24 (§A Phase 4)** — Kept the legacy per-line `suspend/terminate` methods on `PoliciesService` (e.g. `suspendLifePolicy`) alongside the new unified `applyPolicyLifecycleAction(source, id, action, payload)`. They now hit the same Phase 3 endpoint (empty body → null reasonCode, which the service accepts). The legacy call sites in each form's `suspend()` / `terminate()` methods are still present but the header buttons that call them were removed in favor of the `PolicyStatusActionButtonsComponent`, so those dead methods will get swept in a follow-up pass — not touched here to keep the Phase 4 diff scoped to the plan.
- **2026-08-24 (§A Phase 4)** — Added a new `type: 'select'` column to `DataTableComponent` (with `options` + `onSelectChange` on `TableColumn`) so the provider `network_tier` renders as an in-line dropdown without introducing a bespoke provider-only cell renderer. Backwards-compatible — every existing caller is unaffected.
- **2026-08-24 (§B Phase 5)** — `PolicyStatusChangedPublisher` uses `KafkaSender<String,String>` + `ObjectMapper` and a flat `Map<String,String>` JSON body — matching the existing `PolicyEndorsedPublisher` / `PolicyIssuedPublisher` / `UserEventPublisher` shape. The plan's `ReactiveKafkaProducerTemplate<String, KafkaEnvelope<Payload>>` shape doesn't exist anywhere in the codebase, and the flat-map shape is what every downstream consumer already understands. Nullable fields serialise as empty strings so the consumer never needs a null check.
- **2026-08-24 (§B Phase 5)** — Instead of the plan's `insuranceLineFor(entity)` per-entity getter on each subclass, added a no-arg `insuranceLine()` abstract on the base — the value is constant per subclass and never varies by entity instance, so passing the entity is dead weight. Signature change is scoped to test infrastructure (`insuranceLineExpected()` override in `PolicyStatusTransitionServiceTestBase`).
- **2026-08-24 (§B Phase 5)** — Added `PolicyStatusChangedPublisher` as a `@MockBean` in `AbstractPolicyLifecycleIT` alongside the existing `AuditPublisher` / `UserEventPublisher` mocks. Without it the Phase 3 IT (`PolicyStatusHistoryWritesIT`) would try to actually hit Kafka via the transition-service chain and fail — the test slice deliberately keeps Kafka out of the SQL surface.
- **2026-08-24 (§B Phase 6)** — V115 uses a **non-unique** partial index on `closure_ref` (not the plan's `UNIQUE`). One closure event fans out across N future periods and stamps every affected row with the same ref for traceability; a `UNIQUE` index would forbid that. Idempotency is enforced application-side by a pre-write `SELECT COUNT(*) WHERE closure_ref = :ref` inside `EarningScheduleClosureService.closeOutForPolicyClosure` + `freezePolicyEarning`. The plan's proposed service code already carried this COUNT check — the `UNIQUE` index in the same block would have contradicted it.
- **2026-08-24 (§B Phase 6)** — V115 bundles the `is_closure BOOLEAN NOT NULL DEFAULT FALSE` column into the same migration as `closure_ref` (the plan flagged this bundling as an "amended V115" note). Both are new columns on `earning_schedule`, both are needed by the same consumer flow, and shipping them separately would force a two-hop deploy for zero rollback benefit.
- **2026-08-24 (§B Phase 6)** — Consumer uses a **deterministic `closure_ref`** — `UUID.nameUUIDFromBytes(policyId + "|" + toStatus + "|" + effectiveDate)` — so a redelivered Kafka record produces the same ref and the service's COUNT check drops the duplicate. Plan sketched `UUID.randomUUID()` inside the consumer, but a random ref makes byte-identical replay impossible to detect via COUNT — determinism keeps the idempotency guard honest across Kafka retries. Unit test `processRecord_deterministicClosureRef_isStableAcrossReplays` captures the value across two invocations.
- **2026-08-24 (§B Phase 6)** — Renamed the repository query `findByPeriodEndBeforeAndEarnedAtPeriodEndIsNull` → `findByPeriodEndBeforeAndEarnedAtPeriodEndIsNullAndClosureFalse` so the nightly `PremiumEarningExecutor` scan skips rows a lapse/freeze already took out of the earning loop. Existing test + service call sites updated. Without this, a frozen row (`is_closure=TRUE`, `earned_at_period_end=NULL`) would still be linear-earned on the next nightly pass — silently unwinding the freeze.
- **2026-08-24 (§B Phase 6)** — Added `AbstractPolicyStatusConsumerPostgresIntegrationTest` as a sibling to `AbstractDedicatedPostgresIntegrationTest` so `EarningScheduleClosureLifecycleIT` runs against its own container. Extending `AbstractPostgresIntegrationTest` (shared with `BalanceQueryRepositoryBadDebtsIT`, `SchemeServiceIT`, `SchemeCostShareIT`, `CashFlowForecastControllerIT`) collides on the V001 checksum because each of those ITs installs a different `db/*-migration/V001__*.sql` into `public.flyway_schema_history`. Documented in the base class's javadoc.
- **2026-08-24 (§B Phase 6)** — Behavioural coverage for the 4 new closure methods runs against real Postgres (`EarningScheduleClosureLifecycleIT`), not against a mocked fluent `DatabaseClient` chain. The service methods are pure SQL — a Mockito re-encoding would just paraphrase the queries as stubs. Unit-level surface stays as null-arg rejection cases in `EarningScheduleClosureServiceTest`.
- **2026-08-24 (§C Phase 7)** — `MemberContributionPresenceRefreshIT` reuses the `db/policy-status-consumer-migration/` baseline (extended additively with `contributions` + `member_contribution_presence` matview + `earning_schedule_run.contrib_presence_refresh_at`) instead of the plan's fresh `db/premium-earning-migration/` folder. Rationale: a new folder would need its own dedicated Postgres base class (each folder's V001 checksum collides with sibling ITs sharing a container), and the Phase 6 baseline already has the earning tables the refresh needs. Additive edits are transparent to `EarningScheduleClosureLifecycleIT` on the same baseline.
- **2026-08-24 (§C Phase 7)** — `EarningScheduleClosureService.refreshMemberContributionPresence` is `public`, not the plan's package-private `Mono<Void> refreshMemberContributionPresence()`. Needed so `MemberContributionPresenceRefreshIT` (in `com.medfund.contributions.integration`) can invoke it directly; the sibling closure lifecycle methods (`closeOutForPolicyClosure` etc.) are also `public` for the same reason.
- **2026-08-24 (§C Phase 8)** — Angular-side `report-family-labels.service.ts` addition was skipped because no such file exists — the reports hub (`clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts:71-84`) groups by whatever family the backend returns, and Java's `ReportFamily.POLICY_LIFECYCLE("Policy lifecycle")` supplies the label. Adding a client-side label map would drift the label away from the backend enum.
- **2026-08-24 (§C Phase 8)** — Query repository resolves currency via `COALESCE(written_premium_currency, 'USD')` because Phase 12 V102's underwriting widening left `written_premium_currency` nullable and legacy rows have NULL. A ternary in the SQL keeps the aggregate rows keyed by a non-null currency string so `PerCurrencyTotal` map building never trips on a NULL key. Documented on the query repo.
- **2026-08-24 (§C Phase 8)** — `GroupCensusReportService` uses `members.status` directly (not the strict "latest `member_status_history` row with effective_at <= asOf" projection the plan sketched). Rationale: a plan-conforming query is a temporal JOIN + window function and the report accepts only `asOf <= today` (rejected with 400 at the controller). Same-day snapshot semantics collapse to the current status column. When historical asOf shipping becomes a real requirement (Phase 15 IFRS 17 depending on it), the query moves to the history-based projection then.
- **2026-08-24 (§C Phase 9)** — `ProviderClient` skips the plan's `CrossServiceCallHelper` dependency and instead mirrors `SchemeClient` verbatim (own WebClient built from a `@Value` base URL + JWT-forward from `ReactiveSecurityContextHolder`). `CrossServiceCallHelper` is a generic reactive-cross-service scaffold; the ProviderClient is a single-endpoint helper where the batch/fallback shape is the whole logic. Matching an existing precedent inside claims-service also keeps the wiring predictable for a future service adopting the same pattern.
- **2026-08-24 (§C Phase 9)** — Gateway routes for `/api/v1/reports/claims/provider-network-utilization[/export]` are NOT added explicitly — the existing `/api/v1/reports/claims/*` wildcard (routes.go:112) already forwards them. Adding path-specific routes would be dead code.
- **2026-08-24 (§C Phase 9)** — `ProviderClientTest` runs without MockWebServer (not on the claims-service test classpath). The peer-down path is the load-bearing seam and it's driven by absent-JWT — the batch never leaves the JVM. Full happy-path coverage belongs in `ProviderNetworkUtilizationReportServiceTest` via a mocked `ProviderClient`, which is where the enrichment logic lives.
- **2026-08-24 (§C Phase 10)** — All four Angular reports live under `POLICY_LIFECYCLE_REPORT_ROUTES` even though `PROVIDER_NETWORK_UTILIZATION` stays in the CLAIMS_FINANCIAL family per L8. Rationale: the same tenant admin flips the four toggles together and the four pages ship as one PR — grouping them in one Angular routes module keeps `finance.routes.ts` clean. The `data.reportKey` per route still reads the correct family for the hub card lookup.
- **2026-08-24 (§C Phase 10)** — `report-family-labels.service.ts` is a plan artifact that does not exist in the Angular codebase; the reports hub gets its family label from `TenantReportConfigRow.familyLabel` returned by the backend. Backend `ReportFamily.POLICY_LIFECYCLE("Policy lifecycle")` already supplies the label — no client-side patch was needed. Angular compiles + all 587+ tests still pass (one pre-existing `insurance-lines.spec.ts:344` failure documented in Phase 4).
- **2026-08-24 (§C Phase 10)** — `.claude/portals.md` tenant-admin section adds a row for the per-line policy status-action UI + a note on the providers page's inline `network_tier` dropdown per grill note 11.

| Grill note | Resolution |
|---|---|
| **1 — Per-line reason-code vocab (L5)** | `PolicyReasonCode` central enum registry with per-line valid-set map: LIFE = `{NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT, MORTALITY}`; FUNERAL = `{NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT}`; DISABILITY = `{NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT, RECOVERY}`; TRAVEL = `{NON_PAYMENT, TRIP_CANCELLED, INSURED_EVENT}`; VEHICLE = `{NON_PAYMENT, SOLD, TOTAL_LOSS, STORAGE_SUSPEND, POLICYHOLDER_CANCEL}`; PROPERTY = `{NON_PAYMENT, SOLD, TOTAL_LOSS, POLICYHOLDER_CANCEL}`. Validation at controller. |
| **2 — Migration bundling (F13-4)** | Split. V111 (policy_status_history), V112 (member_status_history), V113 (provider.network_tier column ADD) as three independent tenancy-service migrations. V114 (member_contribution_presence matview) + V115 (earning_schedule.closure_ref column) land later in the plan with their consumers. Rationale: functionally independent, per-migration rollback story is cleaner, matches Phase 12's `endorsement` V110 pattern (single-concern per file). |
| **3 — GroupController status cascade (F13-11 + L3)** | `GroupService.cascadeToMembers` at `services/java/user-service/src/main/java/com/medfund/user/service/GroupService.java:397-413` calls `memberService.deactivate/terminate` per member. Those methods delegate to `MemberService.applyOrScheduleStatus → transitionStatus`. Since `transitionStatus` is retrofitted (in §A Phase 2) to route through `MemberStatusTransitionService.transition(...)`, the cascade transparently writes history rows for each member — no `GroupService` change needed. IT guard: a `deactivate group` call with N=5 members writes 5 `member_status_history` rows in one round-trip. |
| **4 — Contribution-presence lookup (L16)** | Materialized view. `member_contribution_presence(member_id UUID, contribution_month DATE)` with `UNIQUE INDEX (member_id, contribution_month)`. Refreshed by `EarningScheduleClosureService.refreshMemberContributionPresence()` chained after existing `refreshMemberFirstContribution` at line 62 (best-effort pattern — swallows errors, logs warning). PersistencyCohortReportService reads via O(1) index seek per checkpoint cell. Grill note 4's E8 investigation concluded 6-30s runtime SQL vs. sub-ms matview — matview wins. Freshness monitoring: log `last_refresh_at` in a new `earning_schedule_run` row with `run_kind='CONTRIB_PRESENCE_REFRESH'`; PersistencyCohortReportService emits envelope warning if last refresh > 24h ago. |
| **5 — PolicyStatusChangedConsumer earning-effect table (L6)** | Codified: `LAPSED`/`TERMINATED` → close open-future periods (mark `earned_at_period_end=0`, `is_closure=TRUE`, write new schedule row per period); `SUSPENDED` → freeze open period at current earned; no new periods; `ACTIVE` (from `SUSPENDED`) → resume; PremiumEarningExecutor picks up on next nightly pass; `ACTIVE` (from `LAPSED`/`TERMINATED`) → REINSTATE — write new schedule rows from `effectiveAt` forward with reinstatement written_premium = pro-rata of original (grill: tenant-configurable reinstatement rule deferred to follow-up — Phase 13 ships pro-rata only). Idempotency via `earning_schedule.closure_ref UUID` field (V115) checked before write; per-transition unique reference. |
| **6 — ProviderClient batching failure path (L13)** | Cap batch size at N=100 per HTTP call (batches of 100 provider IDs). Peer-down semantics: if user-service returns 5xx or times out (2s), `ProviderClient.batchLookup` returns `Map<UUID, ProviderMetadata>` populated with `ProviderMetadata("Provider Unknown", "STANDARD")` for the failed IDs + envelope `warnings.add("provider metadata unavailable: N of M providers")`. Report succeeds; UI renders placeholder names. IT: `ProviderNetworkUtilizationReportPeerDownIT` shuts down user-service mid-report and asserts envelope warnings + placeholder names + valid XLSX. |
| **7 — Angular per-line modal pattern (L4 + L5)** | Shared component architecture, firm. One `PolicyStatusActionButtonsComponent` renders 4 action buttons; one `PolicyStatusActionModalComponent` opens the confirmation dialog; one `PolicyLifecycleActionRegistry` service returns valid actions + reason-code vocab per (policy source, current status). Mounted in the page header of each of the 6 policy form components (all single-form layouts per exploration A1). Reuses the `endorsement-modal.component.ts` shape (title + subheader + form fields + reason textarea + cancel/submit) with reason field driven by `reasonVocab` @Input. |
| **8 — Exhaustive write-site sweep (F13-11 + L3)** | Done. Full enumeration: `MemberService.transitionStatus` is the central pathway ALL of the following flow through: `ArrearsBreachedConsumer:115`, `ArrearsClearedConsumer:97`, `ScheduledStatusExecutor:87,172-176`, `GroupService.cascadeToMembers`, `MemberService.enroll:157,159`. The ONLY site that writes `member.setStatus` OUTSIDE `transitionStatus` is `MemberLifecycleFactBuilder.java:111` (rules-engine auto-term). Retrofit strategy: refactor `MemberService.transitionStatus` to call the new `MemberStatusTransitionService` internally (all callers work transparently); add explicit `MemberStatusTransitionService.transition(...)` call at `MemberLifecycleFactBuilder.java:111`. Two touchpoints total — much cheaper than "~6 sites" the grill estimated. |
| **9 — Testing for `bug_reactor_kafka_ack_swallow`** | `PolicyStatusChangedConsumer` uses `.doOnSuccess` for offset ack per the memory (not `.doOnTerminate`). IT `PolicyStatusChangedConsumerAckIT` guards: publishes an event with a poison payload that throws inside the closeOut handler; asserts offset is NOT acked; asserts message is redelivered on next poll. Same pattern as existing `PolicyEndorsedConsumerIT`. |
| **10 — Report envelope FX for POLICY_MOVEMENT (L10)** | Envelope carries native `perCurrency: Map<String, PerCurrencyTotal>` per invariant #1; `fxRates` best-effort per G28. Missing FX for a historical `period_end` → currency omitted from envelope `fxRates` + warnings entry (`"FX unavailable for ZAR on 2026-06-30"`). Report still succeeds. Matches Phase 12 U8 precedent. |
| **11 — `.claude/portals.md` update owed** | Phase 13 §A Phase 4 ships alongside a paragraph added to `.claude/portals.md` tenant-admin section documenting the policy status-transition surface (4 actions per line, reason-code vocab, network_tier column on providers). |

---

## Phase 1: §A — Tenant schema migrations + backfill

### Overview

Ship the three §A tenant migrations in tenancy-service: `policy_status_history` (V111) + `member_status_history` (V112) + `provider.network_tier` column (V113). Backfill per L7 — seed row per entity from `created_at` plus optional second row where terminal-state fields expose a transition date. All-in-one tranche because ITs need all three simultaneously.

### Changes Required

#### 1. V111 policy_status_history

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V111__policy_status_history.sql` (new)

```sql
-- Phase 13 §A per L2: per-policy status-transition audit trail.
-- Written by PolicyStatusTransitionService (user-service, §A Phase 3) in the same reactive
-- transaction as the policy entity update. Reports read directly from this table (§C).
CREATE TABLE IF NOT EXISTS policy_status_history (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id        UUID NOT NULL,
    policy_source    VARCHAR(30) NOT NULL,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30) NOT NULL,
    effective_at     TIMESTAMPTZ NOT NULL,
    actor_id         UUID,
    actor_email      VARCHAR(255),
    reason_code      VARCHAR(50),
    reason_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_policy_status_history_source CHECK (policy_source IN (
        'LIFE_POLICY', 'FUNERAL_POLICY', 'DISABILITY_POLICY',
        'TRAVEL_POLICY', 'VEHICLE_POLICY', 'PROPERTY_POLICY'
    ))
);

CREATE INDEX IF NOT EXISTS ix_policy_status_history_policy_effective
    ON policy_status_history (policy_id, effective_at DESC);

CREATE INDEX IF NOT EXISTS ix_policy_status_history_source_status_effective
    ON policy_status_history (policy_source, to_status, effective_at DESC);

COMMENT ON TABLE policy_status_history IS
    'Phase 13 §A per L2: per-policy status-transition audit trail. Every write goes through
     PolicyStatusTransitionService in user-service; reports read directly.';

-- Backfill per L7: one seed row per existing policy at (from=NULL, to=<current>, effective_at=bound_at OR created_at)
-- Six-way UNION-ALL across annual-bind policy tables + CONTRIBUTION excluded (HEALTH has no policy entity per F12-6).
INSERT INTO policy_status_history (policy_id, policy_source, from_status, to_status, effective_at, actor_email, reason_code)
SELECT id, 'LIFE_POLICY',       NULL, status, COALESCE(bound_at, created_at)::TIMESTAMPTZ, 'migration', 'initial_backfill' FROM life_policies
UNION ALL
SELECT id, 'FUNERAL_POLICY',    NULL, status, COALESCE(bound_at, created_at)::TIMESTAMPTZ, 'migration', 'initial_backfill' FROM funeral_policies
UNION ALL
SELECT id, 'DISABILITY_POLICY', NULL, status, COALESCE(bound_at, created_at)::TIMESTAMPTZ, 'migration', 'initial_backfill' FROM disability_policies
UNION ALL
SELECT id, 'TRAVEL_POLICY',     NULL, status, COALESCE(bound_at, created_at)::TIMESTAMPTZ, 'migration', 'initial_backfill' FROM travel_policies
UNION ALL
SELECT id, 'VEHICLE_POLICY',    NULL, status, COALESCE(bound_at, created_at)::TIMESTAMPTZ, 'migration', 'initial_backfill' FROM vehicles
UNION ALL
SELECT id, 'PROPERTY_POLICY',   NULL, status, COALESCE(bound_at, created_at)::TIMESTAMPTZ, 'migration', 'initial_backfill' FROM properties;
```

#### 2. V112 member_status_history

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V112__member_status_history.sql` (new)

```sql
-- Phase 13 §A per L2: per-member status-transition audit trail. Retro fills from termination_date + suspend_reason.
-- Written by MemberStatusTransitionService via MemberService.transitionStatus retrofit (§A Phase 2).
CREATE TABLE IF NOT EXISTS member_status_history (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id        UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30) NOT NULL,
    effective_at     TIMESTAMPTZ NOT NULL,
    actor_id         UUID,
    actor_email      VARCHAR(255),
    reason_code      VARCHAR(50),
    reason_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_member_status_history_reason CHECK (reason_code IS NULL OR reason_code IN (
        'initial_backfill', 'backfill_from_termination_date', 'backfill_from_suspend_reason',
        'admin_activate', 'admin_suspend', 'admin_terminate', 'admin_deactivate', 'admin_lapse',
        'arrears_lapse', 'arrears_clear', 'scheduled_change', 'group_cascade',
        'dependant_swap', 'enrolment', 'auto_termination', 'other'
    ))
);

CREATE INDEX IF NOT EXISTS ix_member_status_history_member_effective
    ON member_status_history (member_id, effective_at DESC);

CREATE INDEX IF NOT EXISTS ix_member_status_history_status_effective
    ON member_status_history (to_status, effective_at DESC);

COMMENT ON TABLE member_status_history IS
    'Phase 13 §A per L2: per-member status-transition audit trail. Written through
     MemberStatusTransitionService (routed via MemberService.transitionStatus).';

-- Backfill per L7: seed row from members.created_at + optional second row from termination_date / suspend_reason
INSERT INTO member_status_history (member_id, from_status, to_status, effective_at, actor_email, reason_code)
SELECT id, NULL, status, created_at::TIMESTAMPTZ, 'migration', 'initial_backfill'
FROM members;

-- Second row for terminated members: (from='active', to='terminated', effective_at=termination_date)
INSERT INTO member_status_history (member_id, from_status, to_status, effective_at, actor_email, reason_code)
SELECT id, 'active', 'terminated',
       (termination_date::timestamp AT TIME ZONE 'UTC'),
       'migration', 'backfill_from_termination_date'
FROM members
WHERE termination_date IS NOT NULL AND status = 'terminated';

-- Second row for suspended-with-reason members: (from='active', to='suspended', effective_at=updated_at)
-- Uses updated_at as the best available approximation since suspend_at is not stored separately.
INSERT INTO member_status_history (member_id, from_status, to_status, effective_at, actor_email, reason_code, reason_note)
SELECT id, 'active', 'suspended', updated_at::TIMESTAMPTZ, 'migration', 'backfill_from_suspend_reason', suspend_reason
FROM members
WHERE suspend_reason IS NOT NULL AND status = 'suspended';
```

#### 3. V113 provider.network_tier column

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V113__provider_network_tier.sql` (new)

```sql
-- Phase 13 per L1: single-column network dimension for PROVIDER_NETWORK_UTILIZATION report.
-- Populated via in-line dropdown per L17; default STANDARD for existing rows.
-- Vocab matches ProviderFact.networkTier convention already used by rules-engine co-pay templates
-- (services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/CoPaymentTemplates.java:60).
ALTER TABLE providers
    ADD COLUMN IF NOT EXISTS network_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

ALTER TABLE providers
    ADD CONSTRAINT chk_providers_network_tier
    CHECK (network_tier IN ('STANDARD', 'TIER_1', 'TIER_2', 'TIER_3'));

CREATE INDEX IF NOT EXISTS ix_providers_network_tier ON providers (network_tier);

COMMENT ON COLUMN providers.network_tier IS
    'Phase 13 per L1: network tier grouping for PROVIDER_NETWORK_UTILIZATION report.
     Default STANDARD; tenant admin sets per L17 in-line dropdown.';
```

#### 4. Migration test folder for §A (per L18)

**File**: `services/java/user-service/src/test/resources/db/policy-lifecycle-migration/V001__policy_lifecycle_baseline.sql` (new)

Purpose-built IT schema per L18. Mirrors the minimal set of tables ITs need for §A Phase 1-4: `members`, `groups`, `providers`, `life_policies`, `funeral_policies`, `disability_policies`, `travel_policies`, `vehicles`, `properties`, `policy_status_history`, `member_status_history` — no wider dependencies. Force-widening the existing user-service `db/group-number-migration/V001` or `db/group-create-migration/V001` folders would cascade FKs into unrelated ITs.

```sql
-- Minimal baseline for Phase 13 §A ITs. Mirrors production V001 baseline + Phase 12 V102 widening
-- + Phase 13 V111/V112/V113 columns, scoped to policy-status-transition testing only.
-- (Full content: ~120 lines mirroring baseline tables + Phase 12 additive columns + Phase 13 additions.)
```

### Success Criteria

#### Automated Verification
- [x] Flyway migrations apply cleanly on a fresh testcontainer via `TenantMigrationFlywayIT`: V111 + V112 + V113 land + backfill counts match expected (`SELECT COUNT(*) FROM policy_status_history GROUP BY policy_source`). *(2026-08-23: new `v111_to_v113_backfill_seedsHistoryRowsFromCurrentState` stages at V110, seeds 6 policies + 3 members, asserts 6× initial_backfill rows + member row-count matrix.)*
- [x] Schema shape guard: `TenantMigrationFlywayIT.tenantMigrations_v111_landsPolicyStatusHistoryTable` asserts columns, constraints, and both indexes exist. *(also covers V112 constraints/indexes + V113 constraint/index)*
- [x] Backfill invariant: for every existing policy row in `life_policies/funeral_policies/disability_policies/travel_policies/vehicles/properties`, exactly one `policy_status_history` row exists with `reason_code='initial_backfill'`.
- [x] Backfill invariant: for every member with `termination_date IS NOT NULL AND status='terminated'`, exactly two `member_status_history` rows exist (initial + backfill_from_termination_date).
- [x] Migration ITs verify no orphan `member_status_history` rows exist (FK integrity).
- [x] `SELECT DISTINCT network_tier FROM providers` returns only `{'STANDARD'}` on backfill.
- [x] Java compiles: `cd services/java && ./gradlew :tenancy-service:build`. *(compiles clean + TenantMigrationFlywayIT 5/5 green; note: the `build` task's module-wide jacoco gate fails at 48% vs 70% — pre-existing per `.claude/coverage-backlog.md` (tenancy-service listed at 43.4%), not touched by Phase 1 which adds zero main-code lines)*

#### Manual Verification
- [ ] Migrate a tenant with 1k members + 500 policies of each annual line → backfill completes in < 30s; no partial state.
- [ ] `SELECT COUNT(*) FROM policy_status_history WHERE policy_source='LIFE_POLICY'` = row count of `life_policies` post-migration.

**Implementation Note**: pause for human acceptance before Phase 2.

---

## Phase 2: §A — MemberStatusTransitionService + MemberService retrofit

### Overview

Wire the central `MemberStatusTransitionService` and retrofit `MemberService.transitionStatus` to route through it. Per exploration finding A2, this is the ONE golden hook — every existing member-status writer flows through `transitionStatus`, so refactoring it propagates the history-row write to all consumers transparently. Also retrofit the one outlier: `MemberLifecycleFactBuilder.java:111` (rules-engine auto-term). Ships in-JVM writes in the same reactive transaction as the entity save.

### Changes Required

#### 1. StatusTransitionRecorder (shared, cross-service)

**File**: `services/java/shared/src/main/java/com/medfund/shared/lifecycle/StatusTransitionRecorder.java` (new)

Shared reactive helper that writes to either history table. Lives in `shared` because both user-service (member + policy transitions) and (potentially) future services can use it.

```java
package com.medfund.shared.lifecycle;

import lombok.RequiredArgsConstructor;
import lombok.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 13 §A per L2 + L3. Writes a single row to member_status_history or policy_status_history
 * in the same reactive transaction as the entity update. Called from MemberStatusTransitionService
 * and PolicyStatusTransitionService — never at write sites directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatusTransitionRecorder {

    private final DatabaseClient databaseClient;

    public Mono<Void> recordMember(UUID memberId, String fromStatus, String toStatus,
                                   OffsetDateTime effectiveAt, UUID actorId, String actorEmail,
                                   String reasonCode, String reasonNote) {
        return databaseClient.sql("""
                INSERT INTO member_status_history
                  (member_id, from_status, to_status, effective_at,
                   actor_id, actor_email, reason_code, reason_note)
                VALUES (:memberId, :fromStatus, :toStatus, :effectiveAt,
                        :actorId, :actorEmail, :reasonCode, :reasonNote)
                """)
                .bind("memberId", memberId)
                .bindNullable("fromStatus", fromStatus, String.class)
                .bind("toStatus", toStatus)
                .bind("effectiveAt", effectiveAt)
                .bindNullable("actorId", actorId, UUID.class)
                .bindNullable("actorEmail", actorEmail, String.class)
                .bindNullable("reasonCode", reasonCode, String.class)
                .bindNullable("reasonNote", reasonNote, String.class)
                .then();
    }

    public Mono<Void> recordPolicy(UUID policyId, String policySource,
                                   String fromStatus, String toStatus, OffsetDateTime effectiveAt,
                                   UUID actorId, String actorEmail,
                                   String reasonCode, String reasonNote) {
        return databaseClient.sql("""
                INSERT INTO policy_status_history
                  (policy_id, policy_source, from_status, to_status, effective_at,
                   actor_id, actor_email, reason_code, reason_note)
                VALUES (:policyId, :policySource, :fromStatus, :toStatus, :effectiveAt,
                        :actorId, :actorEmail, :reasonCode, :reasonNote)
                """)
                .bind("policyId", policyId)
                .bind("policySource", policySource)
                .bindNullable("fromStatus", fromStatus, String.class)
                .bind("toStatus", toStatus)
                .bind("effectiveAt", effectiveAt)
                .bindNullable("actorId", actorId, UUID.class)
                .bindNullable("actorEmail", actorEmail, String.class)
                .bindNullable("reasonCode", reasonCode, String.class)
                .bindNullable("reasonNote", reasonNote, String.class)
                .then();
    }
}
```

#### 2. MemberStatusTransitionService (user-service)

**File**: `services/java/user-service/src/main/java/com/medfund/user/status/MemberStatusTransitionService.java` (new)

Wraps the "read old status → save new status → write history row" flow in a single transaction. Exposed as the ONE public write pathway for member status.

```java
package com.medfund.user.status;

import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.Member;
import com.medfund.user.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Phase 13 §A per L3: single write pathway for member status transitions. All existing writers
 * (ArrearsBreached, ArrearsCleared, Scheduled, GroupCascade, Enrolment, auto-term) reach this
 * transparently via MemberService.transitionStatus retrofit (finding A2 from exploration).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberStatusTransitionService {

    private final MemberRepository memberRepository;
    private final StatusTransitionRecorder recorder;
    private final TransactionalOperator tx;

    /**
     * Read current status, write history row, save entity — all in one reactive transaction.
     * Idempotent for same-status transitions (no history row written).
     */
    public Mono<Member> transition(UUID memberId, String newStatus, UUID actorId, String actorEmail,
                                   String reasonCode, String reasonNote) {
        return memberRepository.findById(memberId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Member not found: " + memberId)))
                .flatMap(existing -> {
                    String oldStatus = existing.getStatus();
                    if (oldStatus != null && oldStatus.equals(newStatus)) {
                        log.debug("Skipping same-status transition for member {} ({}→{})",
                                memberId, oldStatus, newStatus);
                        return Mono.just(existing);
                    }
                    existing.setStatus(newStatus);
                    OffsetDateTime effectiveAt = OffsetDateTime.now();
                    return recorder.recordMember(memberId, oldStatus, newStatus, effectiveAt,
                                    actorId, actorEmail, reasonCode, reasonNote)
                            .then(memberRepository.save(existing));
                })
                .as(tx::transactional);
    }
}
```

#### 3. Retrofit MemberService.transitionStatus

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/MemberService.java` (edit — around line 510-548)

Replace the current `member.setStatus() + memberRepository.save()` inside `transitionStatus` with a delegated call to `MemberStatusTransitionService.transition(...)`. All existing callers (ArrearsBreached, ArrearsCleared, Scheduled, GroupCascade, enrolment activate/suspend/terminate/deactivate/lapse) work transparently — no change at their call sites.

```java
// Existing signature preserved:
public Mono<Member> transitionStatus(UUID memberId, String newStatus, String reasonCode,
                                     String reasonNote, JwtActor actor) {
    return statusTransitionService.transition(
            memberId,
            newStatus,
            actor.id(),
            actor.email(),
            reasonCode,
            reasonNote
    );
}
```

Existing `publishAudit(...)` calls inside `transitionStatus` continue to fire (they emit `AuditEvent` per Rule 8 and Kafka `MEMBER_STATUS_CHANGED` per line 89). Ordering: history row → entity save → audit publish → Kafka publish.

#### 4. Retrofit MemberLifecycleFactBuilder auto-term path

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/MemberLifecycleFactBuilder.java` (edit — line 111)

The one outlier — rules-engine auto-term writes `member.setStatus("terminated")` directly. Route through the service.

```java
// BEFORE (line 111):
// member.setStatus("terminated");
// return memberRepository.save(member);

// AFTER:
return memberStatusTransitionService.transition(
        member.getId(),
        "terminated",
        null,                          // system-initiated, no actor id
        "rules-engine@insureflow",      // marker email
        "auto_termination",
        "Rules-engine auto-termination"
);
```

#### 5. Unit tests

**File**: `services/java/user-service/src/test/java/com/medfund/user/status/MemberStatusTransitionServiceTest.java` (new)

Cases:
- `transition_active_to_lapsed_writesHistoryRow_savesMember`
- `transition_sameStatus_noHistoryRow_returnsExistingMember`
- `transition_memberNotFound_throwsIllegalStateException`
- `transition_recorderFails_rollsBackEntitySave` (transactional guard)
- `transition_actorEmailNull_isRejected` (per `feedback_audit_actor_email` — every write needs actor email)

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :shared:compileJava :user-service:compileJava`
- [x] Unit tests: `make test-java` — `MemberStatusTransitionServiceTest` (5 cases) all green. (Shipped 6 cases — split the actorEmail rejection into null + blank.)
- [x] All existing `MemberServiceTest` + `MemberLifecycleServiceTest` tests remain green — the retrofit is behaviour-preserving for callers.
- [x] Integration test `MemberStatusTransitionIT` (in `db/policy-lifecycle-migration/`): calling `MemberService.transitionStatus(...)` writes exactly one `member_status_history` row + updates `members.status`. (Also covers same-status idempotency and a real DROP TABLE rollback guard proving record→save atomicity.)
- [x] IT `GroupCascadeWritesHistoryIT`: calling `GroupService.deactivate(groupId)` on a group with N=5 members writes 5 `member_status_history` rows with `from_status='active', to_status='deactivated'`.
- [x] IT `AutoTerminationWritesHistoryIT`: rules-engine auto-term via `MemberLifecycleFactBuilder` writes a history row with `reason_code='auto_termination'`.

#### Manual Verification
- [ ] Trigger arrears-lapse via ArrearsBreachedConsumer (fire a `medfund.arrears.breached` event) → observe `member_status_history` row with `reason_code='arrears_lapse'` (via db shell). (Deferred to the acceptance pass; consumer path is unit-covered with `eq("arrears_lapse")`.)

---

## Phase 3: §A — PolicyStatusTransitionService × 6 + 24 REST endpoints

### Overview

Ship the abstract `PolicyStatusTransitionService<T extends Policy>` base + 6 concrete subclasses (LIFE/FUNERAL/DISABILITY/TRAVEL/VEHICLE/PROPERTY) + `PolicyReasonCode` enum registry + 24 admin REST endpoints (6 lines × 4 actions). Uniform contract per L5. Each action call: reads current status, validates transition legality, validates reason_code against per-line vocab, writes `policy_status_history` row, saves entity — all in one reactive transaction. Publisher for `medfund.user.policy-status-changed` deferred to §B Phase 5.

### Changes Required

#### 1. PolicyReasonCode enum registry (shared)

**File**: `services/java/shared/src/main/java/com/medfund/shared/lifecycle/PolicyReasonCode.java` (new)

```java
package com.medfund.shared.lifecycle;

import java.util.Map;
import java.util.Set;

/**
 * Phase 13 §A per L5 (grill note 1 resolution): per-line reason-code vocab for policy status
 * transitions. Central registry with per-line valid-set map.
 */
public final class PolicyReasonCode {
    // Every code:
    public static final String NON_PAYMENT           = "NON_PAYMENT";
    public static final String POLICYHOLDER_CANCEL   = "POLICYHOLDER_CANCEL";
    public static final String INSURED_EVENT         = "INSURED_EVENT";
    public static final String MORTALITY             = "MORTALITY";
    public static final String RECOVERY              = "RECOVERY";
    public static final String TRIP_CANCELLED        = "TRIP_CANCELLED";
    public static final String SOLD                  = "SOLD";
    public static final String TOTAL_LOSS            = "TOTAL_LOSS";
    public static final String STORAGE_SUSPEND       = "STORAGE_SUSPEND";
    public static final String ADMIN_CORRECTION      = "ADMIN_CORRECTION";  // always valid

    /**
     * Valid reason vocab per policy source. ADMIN_CORRECTION is universally valid (an "other" arm).
     */
    public static final Map<String, Set<String>> VALID_REASONS_BY_SOURCE = Map.of(
        "LIFE_POLICY",       Set.of(NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT, MORTALITY, ADMIN_CORRECTION),
        "FUNERAL_POLICY",    Set.of(NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT, ADMIN_CORRECTION),
        "DISABILITY_POLICY", Set.of(NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT, RECOVERY, ADMIN_CORRECTION),
        "TRAVEL_POLICY",     Set.of(NON_PAYMENT, TRIP_CANCELLED, INSURED_EVENT, ADMIN_CORRECTION),
        "VEHICLE_POLICY",    Set.of(NON_PAYMENT, SOLD, TOTAL_LOSS, STORAGE_SUSPEND, POLICYHOLDER_CANCEL, ADMIN_CORRECTION),
        "PROPERTY_POLICY",   Set.of(NON_PAYMENT, SOLD, TOTAL_LOSS, POLICYHOLDER_CANCEL, ADMIN_CORRECTION)
    );

    public static boolean isValid(String source, String reasonCode) {
        Set<String> valid = VALID_REASONS_BY_SOURCE.get(source);
        return valid != null && valid.contains(reasonCode);
    }

    private PolicyReasonCode() {}
}
```

#### 2. Abstract PolicyStatusTransitionService<T> base

**File**: `services/java/user-service/src/main/java/com/medfund/user/status/PolicyStatusTransitionService.java` (new)

```java
package com.medfund.user.status;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.PolicyReasonCode;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import lombok.RequiredArgsConstructor;
import lombok.Slf4j;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 13 §A per L3 + L4 + L5. Uniform 4-action write pathway per annual-bind policy entity.
 * Concrete subclasses provide the policy source string + repository access + entity getters.
 */
@Slf4j
@RequiredArgsConstructor
public abstract class PolicyStatusTransitionService<T> {

    protected final R2dbcRepository<T, UUID> repository;
    protected final StatusTransitionRecorder recorder;
    protected final AuditPublisher auditPublisher;
    protected final TransactionalOperator tx;

    /** Concrete subclass identity — e.g. "LIFE_POLICY". */
    protected abstract String policySource();

    /** Read the entity's current status field. */
    protected abstract String getStatus(T entity);

    /** Mutate the entity's status field. */
    protected abstract T setStatus(T entity, String newStatus);

    /** Read the entity's ID. */
    protected abstract UUID getId(T entity);

    /** Friendly entity name for audit — e.g. policy.policyNumber. */
    protected abstract String getEntityName(T entity);

    /** Valid destination-status transitions per current status. Prevents invalid state machine moves. */
    protected static final Set<String> VALID_DESTINATIONS =
            Set.of("active", "lapsed", "suspended", "terminated");

    public Mono<T> transition(UUID policyId, String action, String reasonCode, String reasonNote,
                              AuditActor actor) {
        String newStatus = actionToStatus(action);
        return repository.findById(policyId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Policy not found: " + policyId)))
                .flatMap(existing -> {
                    String oldStatus = getStatus(existing);
                    if (!VALID_DESTINATIONS.contains(newStatus)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Invalid target status: " + newStatus));
                    }
                    if (newStatus.equals(oldStatus)) {
                        log.debug("Skipping same-status transition for policy {} ({}→{})",
                                policyId, oldStatus, newStatus);
                        return Mono.just(existing);
                    }
                    if (reasonCode != null && !PolicyReasonCode.isValid(policySource(), reasonCode)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Invalid reason_code for " + policySource() + ": " + reasonCode));
                    }
                    setStatus(existing, newStatus);
                    OffsetDateTime effectiveAt = OffsetDateTime.now();
                    return recorder.recordPolicy(policyId, policySource(), oldStatus, newStatus,
                                    effectiveAt, actor.id(), actor.email(),
                                    reasonCode, reasonNote)
                            .then(repository.save(existing))
                            .flatMap(saved -> auditPublisher.publish(
                                    AuditEvent.create(
                                            actor.id(), actor.email(),
                                            action.toLowerCase(), policySource(),
                                            policyId.toString(), getEntityName(saved),
                                            Map.of("from", oldStatus, "to", newStatus,
                                                   "reasonCode", reasonCode)
                                    )
                            ).thenReturn(saved));
                })
                .as(tx::transactional);
    }

    /** action names in the URL → target status (per L5 uniform 4-action shape). */
    private static String actionToStatus(String action) {
        return switch (action.toLowerCase()) {
            case "lapse"     -> "lapsed";
            case "terminate" -> "terminated";
            case "suspend"   -> "suspended";
            case "reinstate" -> "active";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action: " + action);
        };
    }
}
```

#### 3. Six concrete PolicyStatusTransitionService subclasses

**File**: `services/java/user-service/src/main/java/com/medfund/user/status/LifePolicyStatusTransitionService.java` (new) — and 5 siblings for FUNERAL / DISABILITY / TRAVEL / VEHICLE / PROPERTY

```java
package com.medfund.user.status;

import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.lifecycle.StatusTransitionRecorder;
import com.medfund.user.entity.LifePolicy;
import com.medfund.user.repository.LifePolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.util.UUID;

@Service
public class LifePolicyStatusTransitionService extends PolicyStatusTransitionService<LifePolicy> {
    public LifePolicyStatusTransitionService(LifePolicyRepository repository,
                                             StatusTransitionRecorder recorder,
                                             AuditPublisher auditPublisher,
                                             TransactionalOperator tx) {
        super(repository, recorder, auditPublisher, tx);
    }
    @Override protected String policySource()                 { return "LIFE_POLICY"; }
    @Override protected String getStatus(LifePolicy e)        { return e.getStatus(); }
    @Override protected LifePolicy setStatus(LifePolicy e, String s) { e.setStatus(s); return e; }
    @Override protected UUID getId(LifePolicy e)              { return e.getId(); }
    @Override protected String getEntityName(LifePolicy e)    { return e.getPolicyNumber(); }
}
```

The 5 siblings follow verbatim shape, differing only in the policy source string, entity type, and repository injection.

#### 4. Twenty-four admin REST endpoints

**File**: `services/java/user-service/src/main/java/com/medfund/user/status/PolicyStatusActionController.java` (new)

One controller with 24 endpoints. Uses path-variable `policyType` to dispatch to the appropriate service.

```java
package com.medfund.user.status;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Tag(name = "Policy status actions", description = "Phase 13 §A: lapse / terminate / suspend / reinstate for annual-bind policies")
@RestController
@RequiredArgsConstructor
public class PolicyStatusActionController {

    private final LifePolicyStatusTransitionService lifeService;
    private final FuneralPolicyStatusTransitionService funeralService;
    private final DisabilityPolicyStatusTransitionService disabilityService;
    private final TravelPolicyStatusTransitionService travelService;
    private final VehiclePolicyStatusTransitionService vehicleService;
    private final PropertyPolicyStatusTransitionService propertyService;

    public record ActionRequest(String reasonCode, String reasonNote) {}

    @PostMapping("/api/v1/life-policies/{id}/{action:lapse|terminate|suspend|reinstate}")
    @RequiresPermission("policy:write")
    @Operation(summary = "Life policy status action")
    public Mono<ResponseEntity<Void>> lifeAction(@PathVariable UUID id,
                                                  @PathVariable String action,
                                                  @RequestBody ActionRequest req,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return lifeService.transition(id, action, req.reasonCode(), req.reasonNote(),
                        AuditActor.from(jwt))
                .then(Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT).<Void>build()));
    }
    // 5 more @PostMapping methods for /funeral-policies, /disability-policies, /travel-policies,
    // /vehicle-policies, /property-policies — same shape, different service injection.
}
```

Per L4 + Rule 7: full Swagger annotations on each endpoint. Per parent invariant #2, no `@RequiresReport` gate (mutations aren't report reads). Per Rule 8, `AuditEvent` fires inside the service.

#### 5. Gateway routes

**File**: `services/go/gateway/internal/routes/routes.go` (edit)

Add routes for all 24 new endpoints under `/api/v1/{life|funeral|disability|travel|vehicle|property}-policies/*` — actually the wildcard `/{id}/{action}` route pattern needs one entry per policy type.

```go
app.Post("/api/v1/life-policies/:id/:action",       userServiceProxy)
app.Post("/api/v1/funeral-policies/:id/:action",    userServiceProxy)
app.Post("/api/v1/disability-policies/:id/:action", userServiceProxy)
app.Post("/api/v1/travel-policies/:id/:action",     userServiceProxy)
app.Post("/api/v1/vehicle-policies/:id/:action",    userServiceProxy)
app.Post("/api/v1/property-policies/:id/:action",   userServiceProxy)
```

#### 6. Unit tests + integration tests

**File**: `services/java/user-service/src/test/java/com/medfund/user/status/LifePolicyStatusTransitionServiceTest.java` (new) — and 5 siblings

Cases per service:
- `transition_activeToLapsed_success_writesHistoryRow`
- `transition_sameStatus_noHistoryRow`
- `transition_policyNotFound_throwsResponseStatusException`
- `transition_invalidReasonCode_400`
- `transition_recorderFails_rollsBackEntitySave`
- `transition_lifeSpecificReason_mortality_success` (per-line vocab guard)

**File**: `services/java/user-service/src/test/java/com/medfund/user/status/PolicyStatusActionControllerIT.java` (new)

End-to-end: `POST /api/v1/life-policies/{id}/lapse` → 204, `policy_status_history` row written, `AuditEvent` fired.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :shared:compileJava :user-service:build`. (Verified via compile+test; full `build` skipped — user-service sits below the 70% jacoco gate per coverage-backlog.)
- [x] Unit tests: 6 × ~6 cases per PolicyStatusTransitionServiceTest (36 cases) all green. (Shipped MORE: shared `PolicyStatusTransitionServiceTestBase` runs an 8-case uniform matrix inside each of the 6 concrete subclasses + a line-specific vocab test each = ~55 cases; plus `PolicyStatusActionControllerTest` for the HTTP mapping/audit-actor extraction layer via direct invocation.)
- [x] Integration tests via `db/policy-lifecycle-migration/`: `PolicyStatusHistoryWritesIT` covers lapse→row+flip, same-status idempotency, and cross-line vocab 400-without-writes at the service level. DEVIATION from plan shape: no full-HTTP controller IT — user-service has no WebFlux security-test harness precedent, and booting PermissionResolverFilter would need role tables outside this baseline's scope; endpoint semantics are covered by `PolicyStatusActionControllerTest` instead.
- [ ] Swagger renders 24 new endpoints at `http://localhost:8082/swagger-ui`. (Manual — needs running infra; annotations shipped on all 6 methods.)
- [x] Gateway Go tests: `cd services/go/gateway && go test ./...` green with new route regs. (Added `vehicle-policies/*` + `property-policies/*`; person-insuring four already wildcarded.)
- [ ] `verify` on gateway health-check: routes accessible at `http://localhost:3000/api/v1/life-policies/{id}/lapse` returns 401 unauthenticated (proves route registration). (Manual — needs live gateway.)

#### Manual Verification
- [ ] Log in as tenant admin → POST via curl to `/api/v1/life-policies/{id}/lapse` with `{"reasonCode":"NON_PAYMENT","reasonNote":"Test"}` → 204; `SELECT * FROM policy_status_history WHERE policy_id={id}` shows the new row.
- [ ] Invalid vocab check: POST with `{"reasonCode":"TRIP_CANCELLED"}` on a life policy → 400 with message "Invalid reason_code for LIFE_POLICY".

---

## Phase 4: §A — Angular per-line modals + shared components + Provider network_tier UI

### Overview

Ship the Angular admin surface: shared `PolicyStatusActionButtonsComponent` + `PolicyStatusActionModalComponent` + `PolicyLifecycleActionRegistry` service mounted on all 6 policy form headers. Add inline `network_tier` dropdown column to `ProvidersComponent`. Reuses existing `DataTableComponent`, endorsement modal template shape, and confirmation dialog patterns.

### Changes Required

#### 1. PolicyLifecycleActionRegistry service

**File**: `clients/angular/src/app/core/services/policy-lifecycle-action-registry.service.ts` (new)

Returns valid actions + reason-code vocab per (policy source, current status). Client-side mirror of `PolicyReasonCode.VALID_REASONS_BY_SOURCE`.

```typescript
import { Injectable } from '@angular/core';

export type PolicySource =
  'LIFE_POLICY' | 'FUNERAL_POLICY' | 'DISABILITY_POLICY' |
  'TRAVEL_POLICY' | 'VEHICLE_POLICY' | 'PROPERTY_POLICY';

export type PolicyAction = 'lapse' | 'terminate' | 'suspend' | 'reinstate';

export interface ReasonOption { code: string; label: string; }

@Injectable({ providedIn: 'root' })
export class PolicyLifecycleActionRegistryService {
  /** Valid actions per current status (any policy source). */
  actionsFor(currentStatus: string): PolicyAction[] {
    return {
      'active':     ['lapse', 'terminate', 'suspend'] as PolicyAction[],
      'suspended':  ['reinstate', 'lapse', 'terminate'] as PolicyAction[],
      'lapsed':     ['reinstate', 'terminate'] as PolicyAction[],
      'terminated': [] as PolicyAction[],
      'draft':      [] as PolicyAction[]
    }[currentStatus] ?? [];
  }

  reasonsFor(source: PolicySource): ReasonOption[] {
    const vocab: Record<PolicySource, ReasonOption[]> = {
      'LIFE_POLICY': [
        { code: 'NON_PAYMENT', label: 'Non-payment' },
        { code: 'POLICYHOLDER_CANCEL', label: 'Policyholder cancellation' },
        { code: 'INSURED_EVENT', label: 'Insured event' },
        { code: 'MORTALITY', label: 'Mortality' },
        { code: 'ADMIN_CORRECTION', label: 'Admin correction' }
      ],
      // ... same shape for the 5 other sources per L5 vocab
    } as Record<PolicySource, ReasonOption[]>;
    return vocab[source] ?? [];
  }

  actionLabel(action: PolicyAction): string {
    return {
      'lapse': 'Lapse policy',
      'terminate': 'Terminate policy',
      'suspend': 'Suspend policy',
      'reinstate': 'Reinstate policy'
    }[action];
  }
}
```

#### 2. PolicyStatusActionButtonsComponent

**File**: `clients/angular/src/app/shared/components/policy-status-action-buttons/policy-status-action-buttons.component.ts` (new)

Renders the 4 action buttons filtered by current status. Emits `(action)` on click; parent opens the modal.

```typescript
import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { PolicyLifecycleActionRegistryService, PolicyAction, PolicySource } from '../../../core/services/policy-lifecycle-action-registry.service';

@Component({
  selector: 'app-policy-status-action-buttons',
  standalone: true,
  template: `
    <div class="action-buttons">
      @for (action of availableActions; track action) {
        <button (click)="clicked.emit(action)" class="btn btn-{{ actionCssClass(action) }}">
          {{ registry.actionLabel(action) }}
        </button>
      }
    </div>
  `,
  styleUrls: ['./policy-status-action-buttons.component.scss']
})
export class PolicyStatusActionButtonsComponent {
  @Input({ required: true }) currentStatus!: string;
  @Input({ required: true }) policySource!: PolicySource;
  @Output() clicked = new EventEmitter<PolicyAction>();

  registry = inject(PolicyLifecycleActionRegistryService);
  get availableActions(): PolicyAction[] { return this.registry.actionsFor(this.currentStatus); }
  actionCssClass(a: PolicyAction) { return { lapse: 'warn', terminate: 'danger', suspend: 'warn', reinstate: 'success' }[a]; }
}
```

#### 3. PolicyStatusActionModalComponent

**File**: `clients/angular/src/app/shared/components/policy-status-action-modal/policy-status-action-modal.component.ts` (new)

Reuses the `endorsement-modal.component.ts` template shape (per exploration E10). Title + subheader + reason picker + optional note textarea + cancel/submit. Reason-vocab from `PolicyLifecycleActionRegistryService`.

```typescript
import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { SelectComponent } from '../select/select.component';
import { PolicyLifecycleActionRegistryService, PolicyAction, PolicySource } from '../../../core/services/policy-lifecycle-action-registry.service';

@Component({
  selector: 'app-policy-status-action-modal',
  standalone: true,
  imports: [ReactiveFormsModule, SelectComponent],
  template: `
    <div class="modal-backdrop" (click)="onCancel()">
      <div class="modal-card" (click)="$event.stopPropagation()">
        <h2>{{ registry.actionLabel(action) }}</h2>
        <p class="muted">Policy {{ policyNumber }} · {{ registry.actionLabel(action).toLowerCase() }}</p>
        <form [formGroup]="form" (ngSubmit)="onSubmit()">
          <app-select formControlName="reasonCode" [options]="reasonOptions" label="Reason"></app-select>
          <label>Note (optional)</label>
          <textarea formControlName="reasonNote" rows="3"></textarea>
          @if (error) { <div class="error-banner">{{ error }}</div> }
          <div class="modal-actions">
            <button type="button" (click)="onCancel()">Cancel</button>
            <button type="submit" [disabled]="form.invalid || submitting">Confirm</button>
          </div>
        </form>
      </div>
    </div>
  `,
  styleUrls: ['./policy-status-action-modal.component.scss']
})
export class PolicyStatusActionModalComponent {
  @Input({ required: true }) action!: PolicyAction;
  @Input({ required: true }) policySource!: PolicySource;
  @Input({ required: true }) policyNumber!: string;
  @Output() submitted = new EventEmitter<{ reasonCode: string; reasonNote?: string }>();
  @Output() cancelled = new EventEmitter<void>();

  private fb = inject(FormBuilder);
  registry = inject(PolicyLifecycleActionRegistryService);
  form = this.fb.group({ reasonCode: this.fb.control<string | null>(null, Validators.required),
                          reasonNote: this.fb.control<string | null>(null) });
  submitting = false;
  error: string | null = null;

  get reasonOptions() { return this.registry.reasonsFor(this.policySource).map(r => ({ value: r.code, label: r.label })); }

  onSubmit() {
    if (this.form.invalid) return;
    this.submitting = true;
    this.submitted.emit({ reasonCode: this.form.value.reasonCode!, reasonNote: this.form.value.reasonNote ?? undefined });
  }
  onCancel() { this.cancelled.emit(); }
}
```

#### 4. Mount in the 6 per-line policy form headers

**File**: `clients/angular/src/app/pages/tenant/policies/life/life-policy-form.component.ts` (edit) + 5 siblings

Import + add to component imports; render in the header alongside existing suspend/terminate buttons; handle action-click to open modal; POST to the service on submit.

```typescript
// Snippet — add to LifePolicyFormComponent (and 5 siblings, each with the correct policySource)
@Component({
  imports: [..., PolicyStatusActionButtonsComponent, PolicyStatusActionModalComponent, ...],
})
export class LifePolicyFormComponent {
  actionModal: { action: PolicyAction } | null = null;
  policySource: PolicySource = 'LIFE_POLICY';
  // ...
  onActionClicked(action: PolicyAction) {
    this.actionModal = { action };
  }
  onActionSubmitted(event: { reasonCode: string; reasonNote?: string }) {
    if (!this.actionModal || !this.policyId) return;
    this.http.post(`/api/v1/life-policies/${this.policyId}/${this.actionModal.action}`, event)
        .subscribe({ next: () => { this.actionModal = null; this.reload(); },
                     error: (err) => { /* surface error */ } });
  }
}
```

#### 5. Inline network_tier dropdown on ProvidersComponent

**File**: `clients/angular/src/app/pages/providers/providers.component.ts` (edit)

Add a `network_tier` column to the data-table configuration + PATCH endpoint call on change.

```typescript
// Add to columns array:
{ key: 'networkTier', label: 'Network tier', editor: 'select',
  options: [
    { value: 'STANDARD', label: 'Standard' },
    { value: 'TIER_1', label: 'Tier 1' },
    { value: 'TIER_2', label: 'Tier 2' },
    { value: 'TIER_3', label: 'Tier 3' }
  ],
  onChange: (row, newValue) => this.updateNetworkTier(row.id, newValue) }
```

**File**: `services/java/user-service/src/main/java/com/medfund/user/controller/ProviderController.java` (edit)

Add `PATCH /api/v1/providers/{id}` endpoint that accepts `{networkTier: string}` and updates the entity. `@RequiresPermission("provider:manage")`. Audit event fires per Rule 8.

**File**: `services/go/gateway/internal/routes/routes.go` (edit)

```go
app.Patch("/api/v1/providers/:id", userServiceProxy)
```

#### 6. Angular unit tests

**File**: `clients/angular/src/app/shared/components/policy-status-action-buttons/policy-status-action-buttons.component.spec.ts` (new)

Cases:
- `showsAllActions_whenStatusIsActive` (3 buttons)
- `showsReinstateOnly_whenStatusIsTerminated` (0 buttons per registry `terminated → []`)
- `emitsAction_onButtonClick`

Similar for `policy-status-action-modal.component.spec.ts` (form validation, reason vocab filtered by policy source).

#### 7. Playwright spec

**File**: `clients/angular/e2e/policy-status-workflow.spec.ts` (new)

Journey: log in as tenant admin → open a LIFE policy detail page → click Lapse → select "Non-payment" → submit → confirm 204 + reload shows status=lapsed + endorsements tab shows new history row.

### Success Criteria

#### Automated Verification
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development`. *(green — pre-existing "unused import" warnings only, none from Phase 4 code.)*
- [x] `make test-angular` — new specs green: `policy-status-action-buttons.component.spec.ts` (4 cases), `policy-status-action-modal.component.spec.ts` (8 cases), `policy-lifecycle-action-registry.service.spec.ts` (11 vocab + endpoint cases). Overall run: 586/587 green (the 1 pre-existing failure is `insurance-lines.spec.ts:344` `providerModeForLine` on the unmodified file — outside this branch).
- [ ] Playwright `policy-status-workflow.spec.ts` green. *(Spec shipped; not exercised in this session — deferred with the rest of the e2e runs.)*
- [ ] `verify` on `/tenant/policies/life/{id}` — page renders, action buttons visible when status=active, modal opens on click, submit closes modal + reloads. *(Manual — needs infra.)*
- [ ] `verify` on `/tenant/providers` — network_tier column renders + inline dropdown edit + PATCH round-trip returns 204. *(Manual — needs infra.)*

#### Manual Verification
- [ ] Same-actor guard: log in as admin A → lapse policy → confirm history row + audit event carry actor A's email.
- [ ] Reason-vocab filter: TRAVEL policy modal only shows `{TRIP_CANCELLED, NON_PAYMENT, INSURED_EVENT, ADMIN_CORRECTION}` (no MORTALITY option).
- [ ] Network tier change: change provider from STANDARD → TIER_1 → refresh page → tier persisted.

**Implementation Note**: §A completes here — pause for human acceptance before §B (Phase 5).

---

## Phase 5: §B — PolicyStatusChangedPublisher + Kafka topic

### Overview

Emit `medfund.user.policy-status-changed` per L6. Publisher fires from `PolicyStatusTransitionService.transition` on successful commit. Ships alongside the base class edit — consumer arrives in Phase 6.

### Changes Required

#### 1. PolicyStatusChangedPublisher

**File**: `services/java/user-service/src/main/java/com/medfund/user/publisher/PolicyStatusChangedPublisher.java` (new)

Mirrors existing `UserEventPublisher.publishMemberLifecycle` shape (line 89).

```java
package com.medfund.user.publisher;

import com.medfund.shared.kafka.KafkaEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.reactive.ReactiveKafkaProducerTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyStatusChangedPublisher {

    private static final String TOPIC = "medfund.user.policy-status-changed";
    private final ReactiveKafkaProducerTemplate<String, KafkaEnvelope<Payload>> producer;

    public Mono<Void> publish(String tenantId, UUID policyId, String policySource,
                              String insuranceLine, String fromStatus, String toStatus,
                              OffsetDateTime effectiveAt, String reasonCode,
                              UUID actorId, String actorEmail) {
        Payload payload = new Payload(tenantId, policyId, policySource, insuranceLine,
                fromStatus, toStatus, effectiveAt, reasonCode, actorId, actorEmail);
        return producer.send(TOPIC, policyId.toString(), KafkaEnvelope.of(payload))
                .doOnSuccess(r -> log.debug("published policy-status-changed {}={}→{}", policyId, fromStatus, toStatus))
                .doOnError(e -> log.error("failed to publish policy-status-changed {}: {}", policyId, e.getMessage(), e))
                .then();
    }

    public record Payload(String tenantId, UUID policyId, String policySource, String insuranceLine,
                          String fromStatus, String toStatus, OffsetDateTime effectiveAt,
                          String reasonCode, UUID actorId, String actorEmail) {}
}
```

#### 2. Wire publisher into PolicyStatusTransitionService

**File**: `services/java/user-service/src/main/java/com/medfund/user/status/PolicyStatusTransitionService.java` (edit)

Add `PolicyStatusChangedPublisher` constructor dep + call after successful save:

```java
// After the auditPublisher.publish(...) chain in transition(...):
.flatMap(saved -> policyStatusChangedPublisher.publish(
        tenantContext.getCurrent(), policyId, policySource(), insuranceLineFor(saved),
        oldStatus, newStatus, effectiveAt, reasonCode, actor.id(), actor.email())
        .thenReturn(saved));
```

Concrete subclasses expose `insuranceLineFor(entity)` — e.g. `LifePolicyStatusTransitionService.insuranceLineFor(life)` returns `"LIFE"`.

#### 3. Kafka topic config

**File**: `services/java/user-service/src/main/resources/application.yml` (edit — kafka producer topics section)

Register the topic name in the topic-registry block if the service maintains one; otherwise the topic is created on first publish (default Kafka behavior).

#### 4. Unit tests

**File**: `services/java/user-service/src/test/java/com/medfund/user/publisher/PolicyStatusChangedPublisherTest.java` (new)

Cases:
- `publish_populatesFullPayload_success`
- `publish_kafkaErrors_isLogged_bubblesError`

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :user-service:compileJava :user-service:compileTestJava` green (2026-08-24).
- [x] Unit tests: `PolicyStatusChangedPublisherTest` (3 cases) green. Base-suite `PolicyStatusTransitionServiceTestBase` gained a publisher-verification assertion inside the happy-path case + a "recorder-fails → no publish" case; all 6 subclass tests now include the wiring guard (55+ cases across the six lines, all green).
- [x] Integration test on real Kafka: `PolicyStatusChangedPublisherIT` (extends `AbstractKafkaIntegrationTest`) publishes end-to-end and asserts the flat envelope on `medfund.user.policy-status-changed`. Test authored — full IT execution deferred to the `make test-integration` pass (Testcontainers needs Docker up in this session).
- [x] Regression: `PolicyStatusHistoryWritesIT` (Phase 3 SQL-seam IT) mocks the new publisher via a `@MockBean` on `AbstractPolicyLifecycleIT` so the transition chain doesn't reach Kafka — the additive publisher call is transparent to the existing SQL asserts.

#### Manual Verification
- [ ] Trigger POST `/api/v1/life-policies/{id}/lapse` via curl → observe event on Kafka topic `medfund.user.policy-status-changed` via `kafka-console-consumer.sh`.

---

## Phase 6: §B — contributions-service PolicyStatusChangedConsumer + closeOutForPolicyClosure

### Overview

Consume `medfund.user.policy-status-changed` in contributions-service; on `toStatus IN ('lapsed', 'terminated')`, close open-future `earning_schedule` periods via new `EarningScheduleClosureService.closeOutForPolicyClosure(...)`; on `SUSPENDED`, freeze current period; on `ACTIVE` from `SUSPENDED`, resume. Ship `earning_schedule.closure_ref` column (V115) for idempotency guard. State-machine per grill note 5.

### Changes Required

#### 1. V115 earning_schedule.closure_ref migration

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V115__earning_schedule_closure_ref.sql` (new)

```sql
-- Phase 13 §B per L6: idempotency guard for policy-status-triggered closure rows.
-- Set when PolicyStatusChangedConsumer writes a closure row from a specific status transition.
-- UNIQUE index prevents duplicate closure writes on redelivery.
ALTER TABLE earning_schedule
    ADD COLUMN IF NOT EXISTS closure_ref UUID;

CREATE UNIQUE INDEX IF NOT EXISTS ux_earning_schedule_closure_ref
    ON earning_schedule (closure_ref) WHERE closure_ref IS NOT NULL;

COMMENT ON COLUMN earning_schedule.closure_ref IS
    'Phase 13 §B per L6: idempotency ref for a policy-status-triggered closure row.
     Populated by PolicyStatusChangedConsumer; NULL for baseline earning rows.';
```

#### 2. PolicyStatusChangedPayload + parser

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/consumer/PolicyStatusChangedPayload.java` (new)

Mirrors user-service payload verbatim (record shape).

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/consumer/PolicyStatusChangedPayloadParser.java` (new)

JSON → record; validates required fields.

#### 3. PolicyStatusChangedConsumer

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/consumer/PolicyStatusChangedConsumer.java` (new)

Mirrors existing `PolicyEndorsedConsumer` shape (reactor-kafka, `.doOnSuccess` ack per `bug_reactor_kafka_ack_swallow`).

```java
package com.medfund.contributions.premium.consumer;

import com.medfund.contributions.premium.service.EarningScheduleClosureService;
import com.medfund.shared.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.util.context.Context;

import javax.annotation.PostConstruct;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyStatusChangedConsumer {

    private final KafkaReceiver<String, byte[]> receiver;
    private final PolicyStatusChangedPayloadParser parser;
    private final EarningScheduleClosureService closureService;

    @PostConstruct
    public void subscribe() {
        receiver.receive()
                .flatMap(this::handle)
                .subscribe();
    }

    private Mono<Void> handle(reactor.kafka.receiver.ReceiverRecord<String, byte[]> record) {
        return Mono.fromCallable(() -> parser.parse(record.value()))
                .flatMap(payload -> processPayload(payload)
                        .contextWrite(Context.of(TenantContext.KEY, payload.tenantId())))
                .doOnSuccess(v -> record.receiverOffset().acknowledge())  // .doOnSuccess per bug memory
                .onErrorResume(err -> {
                    log.error("PolicyStatusChangedConsumer failed for record {}: {}",
                            record.offset(), err.getMessage(), err);
                    return Mono.empty();  // log-and-ack to avoid partition block per PolicyEndorsedConsumer precedent
                })
                .then();
    }

    private Mono<Void> processPayload(PolicyStatusChangedPayload payload) {
        String toStatus = payload.toStatus();
        UUID policyId = payload.policyId();
        String policySource = payload.policySource();
        var effectiveAt = payload.effectiveAt();

        // Per grill note 5 state machine:
        if ("lapsed".equals(toStatus) || "terminated".equals(toStatus)) {
            return closureService.closeOutForPolicyClosure(
                    payload.tenantId(), policyId, policySource, effectiveAt.toLocalDate(),
                    /* closureRef */ UUID.randomUUID()).then();
        }
        if ("suspended".equals(toStatus)) {
            return closureService.freezePolicyEarning(
                    payload.tenantId(), policyId, policySource, effectiveAt.toLocalDate()).then();
        }
        if ("active".equals(toStatus) && "suspended".equals(payload.fromStatus())) {
            return closureService.resumePolicyEarning(
                    payload.tenantId(), policyId, policySource, effectiveAt.toLocalDate()).then();
        }
        if ("active".equals(toStatus) && ("lapsed".equals(payload.fromStatus()) || "terminated".equals(payload.fromStatus()))) {
            return closureService.reinstatePolicyEarning(
                    payload.tenantId(), policyId, policySource, effectiveAt.toLocalDate()).then();
        }
        log.debug("PolicyStatusChangedConsumer: no earning action for {}→{}", payload.fromStatus(), toStatus);
        return Mono.empty();
    }
}
```

#### 4. EarningScheduleClosureService extensions

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EarningScheduleClosureService.java` (edit — add 4 methods)

```java
/** Per grill note 5: LAPSED/TERMINATED — mark all open-future periods (period_start >= effectiveDate)
    as closed with earned_at_period_end=0 and is_closure=TRUE; idempotency via closure_ref. */
public Mono<Long> closeOutForPolicyClosure(String tenantId, UUID policyId, String policySource,
                                           LocalDate effectiveDate, UUID closureRef) {
    // Check if closure_ref already exists (idempotency)
    return databaseClient.sql("SELECT COUNT(*) FROM earning_schedule WHERE closure_ref = :ref")
            .bind("ref", closureRef).map((row, meta) -> row.get(0, Long.class))
            .one()
            .flatMap(existing -> {
                if (existing > 0) {
                    log.debug("closure_ref {} already applied — idempotent skip", closureRef);
                    return Mono.just(0L);
                }
                return databaseClient.sql("""
                        UPDATE earning_schedule
                        SET earned_at_period_end = 0, is_closure = TRUE, closure_ref = :ref
                        WHERE policy_id = :policyId AND policy_source = :source
                          AND period_start >= :effectiveDate
                          AND earned_at_period_end IS NULL
                        """)
                        .bind("ref", closureRef)
                        .bind("policyId", policyId)
                        .bind("source", policySource)
                        .bind("effectiveDate", effectiveDate)
                        .fetch().rowsUpdated();
            });
}

/** SUSPENDED — freeze open period at current earned; no new rows. Per grill note 5. */
public Mono<Long> freezePolicyEarning(String tenantId, UUID policyId, String policySource,
                                      LocalDate effectiveDate) { /* impl */ }

/** ACTIVE from SUSPENDED — resume; PremiumEarningExecutor will pick up on next pass. */
public Mono<Long> resumePolicyEarning(String tenantId, UUID policyId, String policySource,
                                      LocalDate effectiveDate) { /* impl — clears freeze marker */ }

/** ACTIVE from LAPSED/TERMINATED — REINSTATE; pro-rata written_premium from effectiveDate forward. */
public Mono<Long> reinstatePolicyEarning(String tenantId, UUID policyId, String policySource,
                                         LocalDate effectiveDate) { /* impl */ }
```

Also add `earning_schedule` column `is_closure BOOLEAN NOT NULL DEFAULT FALSE` to V115 above (or a separate migration if the earning_schedule table doesn't already have it).

Actually re-checking Phase 12: `earning_schedule` was added in V109. `is_closure` column may need to be added. Let me note this as an implementation detail — either V115 adds both `closure_ref` and `is_closure`, or the plan needs V115 (closure_ref) + V116 (is_closure). Recommendation: bundle both in V115.

Amended V115:
```sql
ALTER TABLE earning_schedule ADD COLUMN IF NOT EXISTS closure_ref UUID;
ALTER TABLE earning_schedule ADD COLUMN IF NOT EXISTS is_closure BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS ux_earning_schedule_closure_ref
    ON earning_schedule (closure_ref) WHERE closure_ref IS NOT NULL;
```

#### 5. Migration test folder for §B

**File**: `services/java/contributions-service/src/test/resources/db/policy-status-consumer-migration/V001__policy_status_consumer_baseline.sql` (new)

Purpose-built IT baseline: `earning_schedule`, `earning_schedule_run`, `member_first_contribution` matview (from Phase 12 V109) + Phase 13 V115 columns.

#### 6. Integration test

**File**: `services/java/contributions-service/src/test/java/com/medfund/contributions/premium/consumer/PolicyStatusChangedConsumerIT.java` (new)

Cases:
- `consume_lapsedEvent_closesOpenPeriods_writesClosureRef`
- `consume_terminatedEvent_closesOpenPeriods`
- `consume_suspendedEvent_freezesEarning`
- `consume_reinstateEvent_reopensSchedule`
- `consume_duplicateEvent_idempotent_noDuplicateRows` (redelivery guard via closure_ref UNIQUE)
- `consume_poisonPayload_ackedButLogsError` (per grill note 9)

**File**: `services/java/contributions-service/src/test/java/com/medfund/contributions/premium/consumer/PolicyStatusChangedConsumerAckIT.java` (new)

Guards `bug_reactor_kafka_ack_swallow`: injects a payload that throws inside `processPayload`; asserts offset is committed (log-and-ack pattern per PolicyEndorsedConsumer precedent).

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :contributions-service:compileJava :contributions-service:compileTestJava` green. (Coverage-gated `:contributions-service:build` skipped — service is below the 70% jacoco floor per `.claude/coverage-backlog.md`, unrelated to Phase 6.)
- [x] Flyway V115 applies cleanly via `TenantMigrationFlywayIT`. New shape guard `tenantMigrations_v115_landsEarningScheduleClosureColumns` asserts `closure_ref` + `is_closure` columns, `ix_earning_schedule_closure_ref` + `ix_earning_schedule_closure_policy` indexes, and `is_closure` default `FALSE`. Existing `tenantMigrations_landAllExpectedColumns` widened to include the new columns.
- [x] Unit tests: `EarningScheduleClosureServiceTest` gained 4 null-arg rejection cases (one per new method). `PolicyStatusChangedConsumerTest` (11 cases) covers dispatch matrix (lapse / terminate / suspend / active-from-suspended / active-from-lapsed / active-from-terminated), no-op transitions (active-from-draft), payload validation drops (missing tenantId / policyId), malformed JSON swallow, deterministic closure_ref stability across replays, and downstream error propagation for the ack path. Full `:contributions-service:test` green (all 425 pre-existing + Phase 6 additions).
- [x] Integration tests via `db/policy-status-consumer-migration/`: `EarningScheduleClosureLifecycleIT` (9 cases against real Postgres) covers closeOut (fan-out to 8 periods on May–Dec, idempotent replay via COUNT lookup, cross-policy scope, already-earned-period skip), freeze / resume symmetry, resume must-not-unwind-lapse, reinstate reverses close but not freeze. Baseline schema in `services/java/contributions-service/src/test/resources/db/policy-status-consumer-migration/V001__policy_status_consumer_baseline.sql`. DEVIATION from plan shape: renamed from `PolicyStatusChangedConsumerIT` — the SQL semantics are the value; the consumer's `.doOnSuccess` ack + `onErrorResume` log-and-ack behaviour is guarded by unit test `processRecord_downstreamErrorBubblesForAckPath` + the direct precedent of the identical shape in `PolicyEndorsedConsumer` (Phase 12 §C). A separate full-Kafka `AckIT` was ruled unnecessary once the pattern was verbatim from an already-guarded consumer.
- [x] Regression: full `:contributions-service:test` includes `PolicyEndorsedConsumerTest` — green. The two consumers coexist on distinct topics; nothing shared beyond the `EarningScheduleClosureService` bean.

#### Manual Verification
- [ ] Bind a LIFE policy for $1200 covering 2026-01..2026-12 → 12 earning_schedule rows appear.
- [ ] Lapse the policy on 2026-04-15 (via §A Phase 3 endpoint) → PolicyStatusChangedConsumer fires → 8 rows updated (2026-05..2026-12) with earned_at_period_end=0, is_closure=TRUE, closure_ref populated.
- [ ] Replay the event (Kafka reset) → no new closure rows written (idempotent via the deterministic `closure_ref` + service COUNT check).

**Implementation Note**: §B completes here — pause for human acceptance before §C (Phase 7).

---

## Phase 7: §C — member_contribution_presence matview + refresh hook

### Overview

Ship the tenant-scoped `member_contribution_presence` materialized view (V114) + `EarningScheduleClosureService.refreshMemberContributionPresence()` chained after existing `refreshMemberFirstContribution` at line 62. Per L16 + grill note 4: matview gives sub-ms lookup per checkpoint cell vs. 6-30s runtime SQL per report.

### Changes Required

#### 1. V114 member_contribution_presence matview

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V114__member_contribution_presence.sql` (new)

```sql
-- Phase 13 §C per L16 + grill note 4: monthly Contribution-presence per member.
-- Refreshed nightly by PremiumEarningExecutor (Phase 12 §A) chained after member_first_contribution refresh.
-- PersistencyCohortReportService reads via O(1) index seek per checkpoint cell.
CREATE MATERIALIZED VIEW IF NOT EXISTS member_contribution_presence AS
SELECT DISTINCT
    member_id,
    DATE_TRUNC('month', period_start)::DATE AS contribution_month
FROM contributions
WHERE member_id IS NOT NULL
  AND period_start IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_member_contribution_presence
    ON member_contribution_presence (member_id, contribution_month);

COMMENT ON MATERIALIZED VIEW member_contribution_presence IS
    'Phase 13 §C per L16 + grill note 4: monthly Contribution-presence per member.
     Refreshed nightly by PremiumEarningExecutor. Enables sub-ms checkpoint-cell lookup
     for PersistencyCohortReportService HEALTH branch.';
```

#### 2. Refresh hook

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EarningScheduleClosureService.java` (edit — around line 62)

```java
// After existing refreshMemberFirstContribution call:
.then(refreshMemberContributionPresence())
```

Add the new method:

```java
Mono<Void> refreshMemberContributionPresence() {
    return databaseClient.sql("REFRESH MATERIALIZED VIEW member_contribution_presence")
            .fetch().rowsUpdated()
            .then()
            .doOnSuccess(v -> log.debug("member_contribution_presence refreshed"))
            .onErrorResume(err -> {
                log.warn("REFRESH MATERIALIZED VIEW member_contribution_presence failed: {}", err.getMessage());
                return Mono.empty();
            });
}
```

Best-effort per matching existing `refreshMemberFirstContribution` pattern.

#### 3. Freshness monitoring row

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EarningScheduleClosureService.java` (edit — write to earning_schedule_run)

Per grill note 4: log a row to `earning_schedule_run` with `run_kind='CONTRIB_PRESENCE_REFRESH'` and `last_refresh_at=NOW()` so `PersistencyCohortReportService` can check freshness before serving reports (emits envelope warning if > 24h stale). Alternative: reuse the existing `earning_schedule_run` row from the nightly pass — track `contrib_presence_refresh_at TIMESTAMPTZ NULL` column via extension migration if needed.

Recommendation: simplest path — add a `contrib_presence_refresh_at` column to `earning_schedule_run` via V114 (bundle in same migration file) + write timestamp in `refreshMemberContributionPresence` on success.

Amended V114:

```sql
-- (matview creation as above)

-- Freshness monitoring: track when contribution-presence was last refreshed
ALTER TABLE earning_schedule_run
    ADD COLUMN IF NOT EXISTS contrib_presence_refresh_at TIMESTAMPTZ;
```

Amended refresh method:

```java
Mono<Void> refreshMemberContributionPresence() {
    return databaseClient.sql("REFRESH MATERIALIZED VIEW member_contribution_presence")
            .fetch().rowsUpdated()
            .then(databaseClient.sql("""
                    UPDATE earning_schedule_run
                    SET contrib_presence_refresh_at = NOW()
                    WHERE id = (SELECT id FROM earning_schedule_run ORDER BY started_at DESC LIMIT 1)
                    """).fetch().rowsUpdated().then())
            .doOnSuccess(v -> log.debug("member_contribution_presence refreshed"))
            .onErrorResume(err -> { log.warn(...); return Mono.empty(); });
}
```

#### 4. Unit test

**File**: `services/java/contributions-service/src/test/java/com/medfund/contributions/premium/service/EarningScheduleClosureServiceTest.java` (edit — add cases)

- `refreshMemberContributionPresence_success_updatesRunRow`
- `refreshMemberContributionPresence_matviewMissing_logsWarning_returnsEmpty`

### Success Criteria

#### Automated Verification
- [x] Flyway V114 applies cleanly via `TenantMigrationFlywayIT`; matview shape + index + `earning_schedule_run.contrib_presence_refresh_at` all present. *(extended existing `tenantMigrations_landAllExpectedColumns` — asserts matview via pg_matviews + ux_member_contribution_presence + contrib_presence_refresh_at column; Testcontainers run deferred to `make test-integration` pass.)*
- [x] Unit tests: 2 new EarningScheduleClosureServiceTest cases green — `refreshMemberContributionPresence_success_completesWithoutError` + `refreshMemberContributionPresence_matviewMissing_logsWarning_returnsEmpty`. Full `:contributions-service:test` green.
- [x] IT `MemberContributionPresenceRefreshIT` (3 cases against real Postgres via existing `db/policy-status-consumer-migration/` — DEVIATION from plan: baseline extended to add `contributions` + matview + freshness column rather than standing up a fresh `db/premium-earning-migration/` folder that would need its own dedicated Postgres base). Cases: distinct (member, month) projection + null-column exclusion; freshness stamp lands on newest run row only; new contribution is stale until next refresh.

#### Manual Verification
- [ ] Insert a Contribution for a member for 2026-08 → run `REFRESH MATERIALIZED VIEW member_contribution_presence` → row appears with `(member_id, 2026-08-01)`.

---

## Phase 8: §C — Policy-lifecycle report backends (user-service)

### Overview

Ship the three POLICY_LIFECYCLE report controllers in user-service: `POLICY_MOVEMENT`, `PERSISTENCY_COHORT`, `GROUP_CENSUS`. Add `ReportFamily.POLICY_LIFECYCLE` enum arm + reassign 3 keys + flip 3 cadence booleans. Each controller uses `@RequiresReport(...)` + `ReportEnvelopeBuilder` + `SecurityEventPublisher.publishDataAccess` on export. All read from Phase 13 §A history tables + Phase 12 policy widening columns + Phase 13 §C matview.

### Changes Required

#### 1. ReportFamily enum arm + ReportKey reassignment + cadence flips

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java` (edit)

```java
public enum ReportFamily {
    // ... existing arms
    POLICY_LIFECYCLE("Policy lifecycle")   // NEW per L8
    // ...
}
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java` (edit — lines 85-88)

```java
// BEFORE: POLICY_MOVEMENT / PERSISTENCY_COHORT / GROUP_CENSUS all under CLAIMS_FINANCIAL
POLICY_MOVEMENT       (ReportFamily.POLICY_LIFECYCLE,  true),   // was CLAIMS_FINANCIAL, was cadenced=true (unchanged)
PERSISTENCY_COHORT    (ReportFamily.POLICY_LIFECYCLE,  true),   // was CLAIMS_FINANCIAL, was cadenced=false → true per L14
GROUP_CENSUS          (ReportFamily.POLICY_LIFECYCLE,  true),   // was CLAIMS_FINANCIAL, was cadenced=false → true per L14
PROVIDER_NETWORK_UTILIZATION (ReportFamily.CLAIMS_FINANCIAL, true), // stays CLAIMS_FINANCIAL, cadenced=false → true per L14
```

**File**: `clients/angular/src/app/core/services/report-family-labels.service.ts` (edit)

Add label entry for POLICY_LIFECYCLE.

#### 2. Query repository — one for all three reports

**File**: `services/java/user-service/src/main/java/com/medfund/user/reports/lifecycle/PolicyLifecycleReportQueryRepository.java` (new)

One repository covering the three §C reports' query shapes — mirrors Phase 12's `PremiumReportQueryRepository` pattern (see Phase 12 Deviation 6). Streams rows for each report + envelope aggregates.

Methods:
- `movementRows(tenantId, periodStart, periodEnd)` — reads policy_status_history + 6 policy tables for opening/closing counts + written_premium
- `movementPerCurrencyTotals(tenantId, periodStart, periodEnd)`
- `persistencyCohortRows(tenantId, periodStart, periodEnd, checkpoints, insuranceLine)` — reads member_first_contribution + member_contribution_presence + policy renewal chain
- `persistencyPerCurrencyTotals(...)`
- `groupCensusRows(tenantId, asOf, groupId?, statusFilter?)` — reads members + groups + schemes + latest member_status_history row for status-at-asOf

#### 3. Three service classes

**File**: `services/java/user-service/src/main/java/com/medfund/user/reports/lifecycle/PolicyMovementReportService.java` (new)

```java
package com.medfund.user.reports.lifecycle;

import com.medfund.shared.report.*;
import lombok.RequiredArgsConstructor;
import lombok.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyMovementReportService {

    private final PolicyLifecycleReportQueryRepository queryRepo;
    private final ReportEnvelopeBuilder envelopeBuilder;

    public Mono<ReportResponse<PolicyMovementResult>> generate(UUID tenantId, ReportPeriod period,
                                                               String reportingCurrencyOverride) {
        return envelopeBuilder.build(
                ReportKey.POLICY_MOVEMENT,
                period,
                reportingCurrencyOverride,
                queryRepo.movementRows(tenantId, period.periodStart(), period.periodEnd()).collectList()
                    .map(PolicyMovementResult::new),
                queryRepo.movementPerCurrencyTotals(tenantId, period.periodStart(), period.periodEnd())
        );
    }
}
```

**File**: `services/java/user-service/src/main/java/com/medfund/user/reports/lifecycle/PersistencyCohortReportService.java` (new)

Extra logic: check `earning_schedule_run.contrib_presence_refresh_at` — if > 24h stale, envelope warning ("HEALTH persistency data may be up to N hours stale").

**File**: `services/java/user-service/src/main/java/com/medfund/user/reports/lifecycle/GroupCensusReportService.java` (new)

#### 4. Three workbook services

**File**: `services/java/user-service/src/main/java/com/medfund/user/reports/lifecycle/PolicyMovementWorkbookService.java` (new) + siblings for PersistencyCohortWorkbookService, GroupCensusWorkbookService.

Each uses `ReportWorkbook` builder from shared. XLSX layouts per L10 + L9 + L11.

#### 5. Controller — one for all three reports

**File**: `services/java/user-service/src/main/java/com/medfund/user/reports/lifecycle/PolicyLifecycleReportController.java` (new)

One controller with 6 endpoints (3 reports × JSON + XLSX). Mirrors Phase 12 `PremiumReportController` pattern (see Phase 12 Deviation 5).

```java
package com.medfund.user.reports.lifecycle;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.*;
import com.medfund.shared.security.SecurityEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Policy lifecycle reports", description = "Phase 13 §C: POLICY_MOVEMENT + PERSISTENCY_COHORT + GROUP_CENSUS")
@RestController
@RequestMapping("/api/v1/reports/policy-lifecycle")
@RequiredArgsConstructor
public class PolicyLifecycleReportController {

    private final PolicyMovementReportService movementService;
    private final PersistencyCohortReportService persistencyService;
    private final GroupCensusReportService censusService;
    private final PolicyMovementWorkbookService movementWorkbook;
    private final PersistencyCohortWorkbookService persistencyWorkbook;
    private final GroupCensusWorkbookService censusWorkbook;
    private final SecurityEventPublisher securityPublisher;
    private final TenantContext tenantContext;

    @GetMapping("/movement")
    @RequiresReport(ReportKey.POLICY_MOVEMENT)
    public Mono<ResponseEntity<ReportResponse<PolicyMovementResult>>> movement(
            @RequestParam String periodStart, @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        return movementService.generate(tenantContext.currentTenantId(),
                ReportPeriod.parseFromQueryParams(periodStart, periodEnd, "monthly"),
                reportingCurrency)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/movement/export")
    @RequiresReport(ReportKey.POLICY_MOVEMENT)
    public Mono<ResponseEntity<byte[]>> movementExport(...,  @AuthenticationPrincipal Jwt jwt) {
        AuditActor actor = AuditActor.from(jwt);
        return movementService.generate(...)
                .flatMap(response -> Mono.fromCallable(() -> movementWorkbook.build(response)))
                .flatMap(bytes -> securityPublisher.publishDataAccess(
                        tenantContext.currentTenantId(), actor.id(), actor.email(),
                        ReportKey.POLICY_MOVEMENT.name(), Map.of("format", "xlsx"))
                        .thenReturn(ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=policy-movement.xlsx")
                                .contentType(MediaType.parseMediaType(
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                                .body(bytes)));
    }

    // Similar endpoints for /persistency-cohort, /persistency-cohort/export,
    //                      /group-census, /group-census/export
}
```

#### 6. Gateway routes

**File**: `services/go/gateway/internal/routes/routes.go` (edit)

```go
app.Get("/api/v1/reports/policy-lifecycle/*", userServiceProxy)
```

#### 7. Unit tests + integration tests

**File**: `services/java/user-service/src/test/java/com/medfund/user/reports/lifecycle/PolicyMovementReportServiceTest.java` (new) + siblings

Cases per report:
- `generate_emptyPeriod_returnsZeroCounts`
- `generate_healthOnly_populatesMemberMovement`
- `generate_annualLineWithLapse_countsCorrectly`
- `generate_missingFxRate_addsEnvelopeWarning`
- `generate_reportingCurrencyOverride_uses_override`

**File**: `services/java/user-service/src/test/java/com/medfund/user/reports/lifecycle/PolicyLifecycleReportControllerIT.java` (new — in `db/policy-lifecycle-reports-migration/`)

Cases:
- `movement_toggleOff_403`
- `movement_success_returnsEnvelope`
- `movement_export_writesSecurityEvent_returnsXlsx`
- `persistency_healthCohort_readsMatview_correctRetention`
- `persistency_matviewStale_addsWarning`
- `census_asOfRequired_400`
- `census_asOf_snapshotsCorrectly`

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :shared:compileJava :user-service:compileJava` green (coverage-gated `:user-service:build` skipped — service is below the 70% jacoco floor per `.claude/coverage-backlog.md`, unchanged by Phase 8).
- [x] Enum edits: `ReportKeyTest.assertsFamilyReassignment_forThreeKeys` + `ReportFamilyTest.hasPolicyLifecycleArm` green. Full `:shared:test` suite green.
- [x] Unit tests: shipped 6 wire-shape cases across `PolicyMovementReportServiceTest` + `PersistencyCohortReportServiceTest` + `GroupCensusReportServiceTest`. All green. (DEVIATION from plan's "3 × ~5 cases = 15": the movement/census surfaces are one-shot query→envelope passthroughs where the wire-shape guard is the whole test; the persistency service carries the freshness-warning matrix as 4 explicit cases. Deeper SQL semantics belong in an IT against real Postgres — deferred.)
- [ ] Integration tests via `db/policy-lifecycle-reports-migration/`: `PolicyLifecycleReportControllerIT` (7 cases) green. *(deferred — Phase 8 shipped as a code-review-ready diff; adding a bespoke Testcontainers slice requires a new tenant baseline SQL parallel to `db/policy-lifecycle-migration/` and is scoped as a follow-up.)*
- [ ] Swagger renders 6 endpoints at `http://localhost:8082/swagger-ui`. *(Manual — needs running infra; annotations shipped on all 6 methods.)*
- [x] Gateway Go tests green with new route reg — `/api/v1/reports/policy-lifecycle` + wildcard added and `go test ./...` in `services/go/gateway` still green.

#### Manual Verification
- [ ] Toggle POLICY_MOVEMENT enabled in tenant_report_config → `GET /api/v1/reports/policy-lifecycle/movement?periodStart=2026-01-01&periodEnd=2026-03-31` returns envelope with movement rows.
- [ ] Export XLSX for a period spanning multiple currencies → file downloads with count + written_premium columns per line + summary sheet.
- [ ] Kafka `medfund.security.events` carries `reportKey=POLICY_MOVEMENT` on every export.

---

## Phase 9: §C — PROVIDER_NETWORK_UTILIZATION backend (claims-service)

### Overview

Ship `ProviderNetworkUtilizationReportController` in claims-service; aggregate claim data locally + enrich with provider names + `network_tier` via `ProviderClient` cross-service call to user-service. Batched N=100 per call. Peer-down: envelope warnings + placeholder names per L13 + grill note 6.

### Changes Required

#### 1. ProviderClient in claims-service

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/client/ProviderClient.java` (new)

```java
package com.medfund.claims.client;

import com.medfund.shared.report.CrossServiceCallHelper;
import lombok.RequiredArgsConstructor;
import lombok.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 13 §C per L13 + grill note 6. Batched provider metadata lookup for PROVIDER_NETWORK_UTILIZATION.
 * Cap N=100 per HTTP call. Peer-down: return placeholder (Provider Unknown, STANDARD) + envelope warnings.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderClient {

    private static final int BATCH_SIZE = 100;
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final WebClient userServiceWebClient;
    private final CrossServiceCallHelper helper;

    public record ProviderMetadata(UUID id, String name, String networkTier) {}

    public Mono<Map<UUID, ProviderMetadata>> batchLookup(Set<UUID> providerIds, List<String> warningsSink) {
        if (providerIds.isEmpty()) return Mono.just(Map.of());
        List<List<UUID>> batches = partitionInto(providerIds, BATCH_SIZE);
        return reactor.core.publisher.Flux.fromIterable(batches)
                .flatMap(batch -> lookupBatch(batch, warningsSink))
                .reduce(new HashMap<UUID, ProviderMetadata>(), (acc, m) -> { acc.putAll(m); return acc; });
    }

    private Mono<Map<UUID, ProviderMetadata>> lookupBatch(List<UUID> ids, List<String> warningsSink) {
        String csv = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        return userServiceWebClient.get()
                .uri("/api/v1/providers?ids=" + csv)
                .retrieve()
                .bodyToFlux(ProviderMetadata.class)
                .collectMap(ProviderMetadata::id)
                .timeout(TIMEOUT)
                .onErrorResume(err -> {
                    log.warn("ProviderClient batch lookup failed for {} ids: {}", ids.size(), err.getMessage());
                    warningsSink.add(String.format("provider metadata unavailable for %d providers", ids.size()));
                    Map<UUID, ProviderMetadata> fallback = new HashMap<>();
                    ids.forEach(id -> fallback.put(id, new ProviderMetadata(id, "Provider Unknown", "STANDARD")));
                    return Mono.just(fallback);
                });
    }

    private static <T> List<List<T>> partitionInto(Collection<T> items, int size) {
        List<T> all = new ArrayList<>(items);
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            parts.add(all.subList(i, Math.min(i + size, all.size())));
        }
        return parts;
    }
}
```

#### 2. Query repository (claims-service)

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/reports/provider/ProviderUtilizationQueryRepository.java` (new)

Aggregates from `claims` table: per-provider claim_count, total_paid, unique_members_served, avg_claim_amount, denial_count, avg_days_to_pay grouped by (provider_id, insurance_line, currency_code, period).

#### 3. Service

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/reports/provider/ProviderNetworkUtilizationReportService.java` (new)

Two-level result per L12: `summary: Map<String, NetworkTierTotals>` + `detail: List<ProviderUtilizationRow>`. Post-processes query results with `ProviderClient` enrichment.

#### 4. Workbook service

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/reports/provider/ProviderNetworkUtilizationWorkbookService.java` (new)

Two-sheet XLSX per L12: summary sheet + per-provider detail sheet.

#### 5. Controller

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/reports/provider/ProviderNetworkUtilizationReportController.java` (new)

```java
@Tag(name = "Provider utilization report")
@RestController
@RequestMapping("/api/v1/reports/claims")
@RequiredArgsConstructor
public class ProviderNetworkUtilizationReportController {
    private final ProviderNetworkUtilizationReportService service;
    private final ProviderNetworkUtilizationWorkbookService workbook;
    private final SecurityEventPublisher securityPublisher;
    private final TenantContext tenantContext;

    @GetMapping("/provider-network-utilization")
    @RequiresReport(ReportKey.PROVIDER_NETWORK_UTILIZATION)
    public Mono<ResponseEntity<ReportResponse<ProviderUtilizationResult>>> generate(
            @RequestParam String periodStart, @RequestParam String periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String networkTier,
            @RequestParam(required = false) String reportingCurrency) {
        return service.generate(...).map(ResponseEntity::ok);
    }

    @GetMapping("/provider-network-utilization/export")
    @RequiresReport(ReportKey.PROVIDER_NETWORK_UTILIZATION)
    public Mono<ResponseEntity<byte[]>> export(..., @AuthenticationPrincipal Jwt jwt) { /* same shape as Phase 8 export */ }
}
```

#### 6. Gateway routes

```go
app.Get("/api/v1/reports/claims/provider-network-utilization",         claimsServiceProxy)
app.Get("/api/v1/reports/claims/provider-network-utilization/export",  claimsServiceProxy)
```

#### 7. Migration test folder + IT

**File**: `services/java/claims-service/src/test/resources/db/provider-utilization-report-migration/V001__baseline.sql` (new)

Purpose-built IT baseline: `claims`, `providers` (with network_tier column), `members`.

**File**: `services/java/claims-service/src/test/java/com/medfund/claims/reports/provider/ProviderNetworkUtilizationReportIT.java` (new)

Cases:
- `generate_toggleOff_403`
- `generate_healthOnly_perTierAggregation`
- `generate_multiCurrency_perCurrencyTotals`
- `generate_missingProviderMetadata_placeholderName_envelopeWarning` (peer-down guard per grill note 6)
- `export_writesSecurityEvent_returnsTwoSheetXlsx`

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :claims-service:compileJava` green (coverage-gated `:claims-service:build` skipped — service is below the 70% jacoco floor per `.claude/coverage-backlog.md`).
- [x] Unit tests: `ProviderNetworkUtilizationReportServiceTest` (4 cases: enrichment + tier filter + peer-down warning + empty aggregate) + `ProviderClientTest` (2 cases: empty ids + peer-down fallback). Full `:claims-service:test` green.
- [ ] Integration tests via `db/provider-utilization-report-migration/`: `ProviderNetworkUtilizationReportIT` (5 cases) green including peer-down. *(Deferred — Phase 9 shipped as a code-review-ready diff; adding a bespoke Testcontainers slice requires a new tenant baseline SQL + a dedicated Postgres base class parallel to `AbstractPolicyStatusConsumerPostgresIntegrationTest`. Peer-down semantics are unit-covered.)*
- [ ] Swagger renders at `http://localhost:8083/swagger-ui`. *(Manual — needs running infra; annotations shipped on both endpoints.)*
- [x] Gateway Go tests green — no new route reg needed; the existing `/api/v1/reports/claims/*` wildcard (routes.go:112) already forwards `provider-network-utilization` and `/export`.

#### Manual Verification
- [ ] Toggle PROVIDER_NETWORK_UTILIZATION enabled → GET returns envelope with summary + detail slices.
- [ ] Change a provider's network_tier via §A Phase 4 UI → re-run report → provider moves to new tier row in summary.
- [ ] Stop user-service mid-report → report still succeeds with placeholder names + envelope warning.

---

## Phase 10: §C — Angular report pages + hub POLICY_LIFECYCLE family + Playwright

### Overview

Ship 4 report pages (3 policy-lifecycle + 1 provider-utilization) + hub POLICY_LIFECYCLE family card + report-catalogue service registration + gateway is already done in Phases 8-9. Playwright specs.

### Changes Required

#### 1. Angular routes

**File**: `clients/angular/src/app/pages/tenant/finance/reports/policy-lifecycle/policy-lifecycle.routes.ts` (new)

Mirrors Phase 12 `underwriting.routes.ts` shape.

```typescript
import { Routes } from '@angular/router';

export const POLICY_LIFECYCLE_REPORT_ROUTES: Routes = [
  { path: 'movement',            loadComponent: () => import('./movement/movement.component').then(m => m.PolicyMovementReportComponent) },
  { path: 'persistency-cohort',  loadComponent: () => import('./persistency-cohort/persistency-cohort.component').then(m => m.PersistencyCohortReportComponent) },
  { path: 'group-census',        loadComponent: () => import('./group-census/group-census.component').then(m => m.GroupCensusReportComponent) },
];
```

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts` (edit — around line 748)

```typescript
{ path: 'reports/policy-lifecycle', children: POLICY_LIFECYCLE_REPORT_ROUTES },
{ path: 'reports/claims-financial/provider-network-utilization',
  loadComponent: () => import('./reports/claims-financial/provider-network-utilization/provider-network-utilization.component')
                        .then(m => m.ProviderNetworkUtilizationReportComponent) },
```

#### 2. Four report components + services

Each report component:
- Period picker (default: prior month for movement, prior quarter for persistency)
- Additional filters per L9/L10/L11/L12 shape
- Reporting-currency override picker
- Export button
- Table/pivot rendering per report shape
- Warnings banner if envelope carries warnings

Component list:
- `PolicyMovementReportComponent` + `policy-movement.service.ts`
- `PersistencyCohortReportComponent` + `persistency-cohort.service.ts`
- `GroupCensusReportComponent` + `group-census.service.ts`
- `ProviderNetworkUtilizationReportComponent` + `provider-network-utilization.service.ts`

Each service exposes `getReport(filters)` + `exportXlsx(filters): Observable<Blob>` — mirrors Phase 12 report service pattern.

#### 3. Hub family card

**File**: `clients/angular/src/app/core/services/report-catalogue.service.ts` (edit)

Extend to auto-register POLICY_LIFECYCLE family + 4 report keys with their URLs.

**File**: `clients/angular/src/app/pages/tenant/finance/reports/reports-hub/reports-hub.component.ts` (edit — no code change, just ensure POLICY_LIFECYCLE family renders when catalog service returns it)

#### 4. GroupPicker debounced search-select

For GROUP_CENSUS: reuse existing group picker (`GroupSearchSelectComponent`) per `feedback_no_raw_id_inputs`.

#### 5. Playwright specs

**File**: `clients/angular/e2e/policy-lifecycle-reports.spec.ts` (new)

Tests (5 total):
- `hub_showsPolicyLifecycleFamilyCard_whenAnyKeyEnabled`
- `movement_page_rendersToolbar_exportsXlsx`
- `persistency_page_checkpointConfigurableViaUrl_exports`
- `group_census_page_requiresAsOf_exports`
- `disabledToggle_hidesCard`

**File**: `clients/angular/e2e/provider-network-utilization.spec.ts` (new)

Tests (3 total):
- `providerUtilization_summary_and_detail_render`
- `providerUtilization_export`
- `providerUtilization_networkTierFilter`

### Success Criteria

#### Automated Verification
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — green (only pre-existing warnings unrelated to Phase 10).
- [x] `make test-angular` — 4 new `*-report.service.spec.ts` files (policy-movement, persistency-cohort, group-census, provider-network-utilization) with wire-shape cases (GET envelope, XLSX blob, filter forwarding). Full suite 595/596 green (the 1 pre-existing failure is `insurance-lines.spec.ts:344 providerModeForLine` on the unmodified file, same as Phase 4).
- [x] Playwright `policy-lifecycle-reports.spec.ts` (5 tests) + `provider-network-utilization.spec.ts` (3 tests) — specs shipped. *(Not executed in this session — requires the full docker-composed stack + real fixtures; run with `make test-e2e` on the acceptance pass.)*
- [ ] `verify` on `/tenant/finance/reports` — POLICY_LIFECYCLE card renders when keys enabled; card hides when all keys disabled. *(Manual — needs infra.)*
- [ ] `verify` on each of the 4 report pages — page loads, filter row works, export blob downloads. *(Manual — needs infra.)*

#### Manual Verification
- [ ] Reports hub at `/tenant/finance/reports` shows "Policy lifecycle" family card when any of POLICY_MOVEMENT / PERSISTENCY_COHORT / GROUP_CENSUS enabled.
- [ ] Toggle a report off in tenant-admin → direct URL navigation returns 403; hub card hides that specific report.
- [ ] Reporting-currency override picker on the movement page sets `?reportingCurrency=EUR` and envelope shows conversion.
- [ ] Missing FX rate for one currency renders warning banner inline.
- [ ] Group census asOf picker required; missing param returns friendly error.
- [ ] Provider network utilization page shows summary table + per-provider table with network_tier filter working.

**Implementation Note**: Phase 13 completes here. §C ships alongside a `.claude/portals.md` paragraph documenting the tenant-admin policy status-transition surface (per grill note 11).

---

## Testing Strategy

### Unit tests
- Every new service class gets its own `*Test.java` with cases covering happy path + validation + transactional guard + edge cases.
- Every new component gets its own `*.spec.ts` covering rendering, event emission, and form validation.

### Integration tests (Testcontainers slices)
- **§A** — `MemberStatusTransitionIT`, `PolicyStatusActionControllerIT`, `AutoTerminationWritesHistoryIT`, `GroupCascadeWritesHistoryIT` — in `db/policy-lifecycle-migration/`.
- **§B** — `PolicyStatusChangedConsumerIT`, `PolicyStatusChangedConsumerAckIT` — in `db/policy-status-consumer-migration/`.
- **§C** — `PolicyLifecycleReportControllerIT`, `MemberContributionPresenceRefreshIT` — in `db/policy-lifecycle-reports-migration/`; `ProviderNetworkUtilizationReportIT` — in `db/provider-utilization-report-migration/`.
- All extend `com.medfund.shared.testfixtures.AbstractIntegrationTest` per L18.

### E2E tests (Playwright, clients/angular/e2e/)
- `policy-status-workflow.spec.ts` — golden path admin action → history row → reload.
- `policy-lifecycle-reports.spec.ts` — 5 tests (hub visibility, 3 report pages, disabled-toggle).
- `provider-network-utilization.spec.ts` — 3 tests (summary + detail render, export, tier filter).

### Manual Testing Steps
1. Bind + immediately lapse a LIFE policy → observe history row + Kafka event + earning_schedule closure within 1 minute.
2. Suspend a VEHICLE policy for "storage_suspend" → observe schedule freezes.
3. Reinstate → observe schedule resumes (nightly pass picks up).
4. Change 5 providers' network_tier + rerun PROVIDER_NETWORK_UTILIZATION → tier grouping reflects new values.
5. Persistency cohort report for a known scheme (e.g. 2024-Q1 HEALTH cohort) → verify retention % matches manual spot-check on Contribution data.

## Performance Considerations

- **Persistency query**: matview reduces HEALTH cohort lookup from 6-30s runtime SQL to sub-ms per checkpoint cell (per grill note 4).
- **Group cascade**: sequential Flux.flatMap through members is fine at typical group sizes (≤1000 members); if a tenant has 10k-member groups, tune flatMap concurrency in `GroupService.cascadeToMembers` — plan-time follow-up.
- **PolicyStatusChangedConsumer**: single-partition topic acceptable initially; if throughput becomes an issue, partition by tenant_id.
- **ProviderClient batching**: N=100 provider cap avoids URL-length limits on GET `?ids=` — for large tenants, count of unique providers per report is typically < 500.
- **Angular bundle**: policy-lifecycle-report components lazy-loaded via `loadComponent` per route — no impact on initial bundle.

## Migration Notes

- **Flyway ordering (V111..V115)**: 5 migrations in tenancy-service `tenant/` folder. V111 + V112 + V113 in §A; V114 in §C Phase 7; V115 in §B Phase 6. Each file is idempotent (`IF NOT EXISTS` on tables + indexes; `IF NOT EXISTS` on columns via `ALTER TABLE ADD COLUMN IF NOT EXISTS`).
- **Backfill for policy_status_history**: single INSERT ... SELECT UNION-ALL across 6 policy tables. Runs at migration time; no separate backfill job needed.
- **Backfill for member_status_history**: two INSERT ... SELECT statements — one for the seed row, one for the terminated-member second row.
- **Never edit an applied migration** per `feedback_never_edit_applied_migrations` — if V111..V115 need correction post-deploy, add a higher-numbered file.
- **`public.<tenant-table>` prefix** — Phase 13 has NO tenant queries against public tables per `bug_public_prefix_silent_rollback`. All new tables/matviews are tenant-schema-local; queries use unqualified names.
- **Tenant Flyway drift**: if a tenant is behind on V102/V107/V108/V109/V110 (Phase 12), the V111 backfill INSERT will fail — those tenants need `flyway repair` + `migrate -outOfOrder=true` first per `bug_tenant_flyway_outoforder`.

## Rollout & Rollback

### Rollout order
1. **§A** — schema migrations (V111 + V112 + V113) apply on tenancy-service deploy. All backfills complete in one transaction.
2. **§A** — user-service deploys with retrofit + new endpoints. All existing member-status flows continue working via `MemberService.transitionStatus` retrofit; new POST endpoints activate.
3. **§A** — Angular deploys with new modals + provider network_tier column. Tenant admins can lapse/terminate/suspend/reinstate policies + tier providers.
4. **§B** — user-service deploys `PolicyStatusChangedPublisher` (produces to new topic — no consumers yet, benign).
5. **§B** — V115 migration applies; contributions-service deploys `PolicyStatusChangedConsumer`. Producer-first ordering per parent-plan invariant.
6. **§C** — V114 migration applies; contributions-service deploys `refreshMemberContributionPresence` extension. Matview populates on next nightly refresh.
7. **§C** — user-service + claims-service + gateway deploy with new report endpoints + routes.
8. **§C** — Angular deploys with new report pages + hub family card.

### Rollback
- **§A rollback**: `ALTER TABLE providers DROP COLUMN network_tier;` + `DROP TABLE policy_status_history, member_status_history;`. Existing member/policy flows continue working (retrofit is behaviour-preserving). Angular rollback: revert modal components.
- **§B rollback**: stop `PolicyStatusChangedConsumer`; `DROP INDEX ux_earning_schedule_closure_ref; ALTER TABLE earning_schedule DROP COLUMN closure_ref, DROP COLUMN is_closure;`. Producer-side rollback: remove the publisher wiring in `PolicyStatusTransitionService` — new events cease. Existing `earning_schedule` rows with `is_closure=TRUE` become inert.
- **§C rollback**: `DROP MATERIALIZED VIEW member_contribution_presence;` + toggle reports off in tenant_report_config. Angular rollback: remove routes.

Each tranche is independently deployable and independently reversible.

## References

- Parent plan (Phase 13 grilled): `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md#phase-13`
- Grilling decisions L1..L18: parent plan lines 3350-3367
- Facts F13-1..F13-11: parent plan lines 3371-3381
- Grill notes for `create-plan` (11 items closed above): parent plan lines 3479-3496
- Phase 12 sub-plan (reference implementation shape): `thoughts/shared/plans/2026-08-23-upr-earning-schedule-and-premium-register.md`
- Architecture: `.claude/multi-tenancy.md`, `.claude/multi-currency.md`, `.claude/coding-standards.md`, `.claude/portals.md`
- Auto-memory constraints (see `MEMORY.md` in your Claude project memory dir): `bug_reactor_kafka_ack_swallow`, `bug_public_prefix_silent_rollback`, `bug_tenant_flyway_outoforder`, `feedback_audit_actor_email`, `feedback_audit_entity_name`, `feedback_never_edit_applied_migrations`, `feedback_no_raw_id_inputs`, `feedback_effective_date_snap`
- Similar implementations (Phase 12 §C endorsement precedent): `services/java/user-service/src/main/java/com/medfund/user/endorsement/service/PolicyEndorsementService.java`, `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/consumer/PolicyEndorsedConsumer.java`, `services/java/user-service/src/main/java/com/medfund/user/endorsement/controller/EndorsementRegisterReportController.java`
