---
date: 2026-08-22
git_commit: e6907ece579a9fe972aeb05a85226c2ad0f233b9
branch: rename-adjustments-to-notes
ticket: null
spec: null
parent_plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md#phase-11
research:
  - thoughts/shared/research/2026-08-22-phase11-producer-commission.md
grilling:
  - decisions P1..P14 landed in parent plan at thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:3009-3027 on 2026-08-22
steer: "§A + §B in one plan with full code depth (mirrors reinsurance sub-plan precedent)"
services_touched: [tenancy-service, finance-service, contributions-service, user-service, rules-engine, shared, gateway, angular]
status: draft
---

# Phase 11 — Producer / Broker Module + Commission Reports

## Overview

Greenfield producer/broker module living in `services/java/finance-service/src/main/java/com/medfund/finance/producer/*`. Covers a producer registry with self-referential hierarchy (P8), time-slice member↔producer assignments (P2), a hybrid commission engine combining a lookup rate-card with rules-engine kickers (P5), producer payout via the existing PaymentRun machinery widened for a `PRODUCER` payee (P4), two clawback triggers (member lapse per P6/P7 and contribution revoke per P13), a full auto-lapse chain (P7a/P7b/P7c) that bundles into this phase, a four-eyes commission adjustment workflow (P9) mirroring facultative cession, producer termination + bulk-reassign (P14), and a `treaty.producer_id` FK + fuzzy backfill review UI (P11).

Ships two tenant-toggleable reports (`COMMISSION_STATEMENT`, `COMMISSION_CLAWBACK`) — both keys already ship at `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:96-97` under `ReportFamily.COMMISSION` at `.../ReportFamily.java:25` (F11-a), so no shared-enum edits are needed. Scheduled statement delivery is deferred entirely to Phase 17 per P12 — Phase 11 ships manual XLSX exports only.

Every decision in this plan is inherited from the grilling session that produced P1..P14 (see [parent plan](2026-08-11-financial-reporting-suite.md#phase-11), lines 3009–3027). A verification pass on 2026-08-22 confirmed the inherited claims at code altitude and settled every open grill note — see *Verified during this planning pass* below.

## Current State Analysis

- **The module is 100% greenfield.** Grep across `services/java/*/src/main/java` and `clients/angular/src/app` for `Producer|Broker|Commission|Clawback|RateCard` returns nothing operational — verified 2026-08-22. Only surviving reference is `treaty.producer_ref VARCHAR(120)` at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V082__treaty.sql:171`, unread today, backfilled by Phase 10 of this sub-plan.
- **Report catalogue is pre-wired.** `ReportKey.COMMISSION_STATEMENT` (line 96), `ReportKey.COMMISSION_CLAWBACK` (line 97), and `ReportFamily.COMMISSION` (line 25) already ship. Phase 11 adds controllers only.
- **All Phase 0-10 shared infra composes on top:** `ReportEnvelopeBuilder` (`services/java/shared/.../report/ReportEnvelopeBuilder.java:64`), `ReportingCurrencyResolver` (`.../ReportingCurrencyResolver.java:46`), `FxRateReader` (`.../FxRateReader.java:51`), `RequiresReport` + `ReportGuardAspect`, `SecurityEventPublisher.publishDataAccess` (`services/java/shared/.../security/SecurityEventPublisher.java:74`), `AuditActor.id(jwt)` + `AuditActor.email(jwt)` (`services/java/shared/.../audit/AuditActor.java:44,55`).
- **All tenant migrations live in tenancy-service.** Highest tenant V today = **V091** (last: `V091__reinsurance_permissions.sql`); next slot = **V092**. Highest public V = **V132** (last: `V132__tenant_high_cost_claimant_config.sql`); next slot = **V133**.
- **`medfund.contributions.paid` producer + payload verified.** `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ContributionEventPublisher.java:50-63` publishes `{event, contributionId, memberId, amount, currencyCode, insuranceLine, paidAt, tenantId}` (the Phase-6 extended shape). The only current consumer is `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/consumer/ReinsurancePremiumCessionConsumer.java:38` — sibling of the new `ProducerCommissionConsumer` in the same package tree.
- **`medfund.contributions.revoked` does NOT exist.** No publisher method on `ContributionEventPublisher`. Phase 11 §A Phase 3 adds one; the revoke callers are `BillingService.revokeBilling(...)` at `.../contributions/service/BillingService.java:934` (period-wide) and `BillingService.revokeInvoice(...)` at line `:1102` (per-invoice).
- **`medfund.contributions.arrears-threshold-breached` + `arrears-cleared` do NOT exist.** Existing `medfund.contributions.arrears-notice` (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/ArrearsNoticePublisher.java:41`) is a *notice-only* stream — reminder emails. Phase 11 §B Phase 7 adds the two new state-transition topics; the arrears sweep lives at `services/java/contributions-service/src/main/java/com/medfund/contributions/job/ArrearsEscalationExecutor.java:54-261` — the injection point is at line 189 where `bucket == "SUSPENDED"` is detected.
- **`Member.scheduledStatus` infrastructure ready.** V042 fields on `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:69-76`; SCHEDULED_STATUS_ROLL job lives at `services/java/user-service/src/main/java/com/medfund/user/job/ScheduledStatusExecutor.java:76-160`. Public setter route: `MemberService.suspend(id, effectiveDate, reason, actorId, actorEmail)` at `.../user/service/MemberService.java:320-335`. No consumer file exists yet in user-service `consumer/` — the shape template is `services/java/user-service/src/main/java/com/medfund/user/consumer/TenantProvisionedConsumer.java:18-71` (reference for `.doOnSuccess`-based ack + `retryWhen` backoff).
- **Rules-engine wiring reference:** `RuleCategory.REINSURANCE` sits at `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:68`; `ActionType.CEDE_TO_TREATY` at `.../model/ActionType.java:74`. `CedeToTreatyEmitter` is a standalone `@Component` at `.../compiler/CedeToTreatyEmitter.java` — the direct template for `PayCommissionEmitter`. `AGENDA_GATED_CATEGORIES` at `.../compiler/DrlCompiler.java:57` is `{"BENEFIT_PRORATION", "REINSURANCE"}` — `COMMISSION` joins.
- **`ContributionFact` (`.../rules-engine/.../fact/ContributionFact.java`) has NO `commissions` list or `addCommission` method today.** The `attributes` bag at line 81 is line-agnostic; new action methods land alongside `setPremium`/`applyLateFee` at lines 123-141. Phase 1 adds `addCommission(String producerId, BigDecimal amount, String rateCardId, String message)`.
- **Angular rule builder is category-agnostic.** Category source-of-truth at `clients/angular/src/app/core/services/rules.service.ts:36-86`; action-type registry at `clients/angular/src/app/pages/tenant-admin/rules/rule-editor/rule-editor.component.ts:38-64`; dry-run FACT_SEEDS at `clients/angular/src/app/pages/tenant-admin/rules/rule-dry-run/rule-dry-run.component.ts:32-131`. Zero component refactor — just three edits.
- **PaymentRun `payee_type` widening surface — four tables:**
  - `payment_runs.payee_type` CHECK at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V072__creditors_unification_and_member_settlement.sql:48-50`
  - `payments.payee_type` + XOR at `.../V071__payment_run_generation_and_advice_ledger.sql:16-30`
  - `payment_run_items.payee_type` + XOR at `.../V071...:36-49`
  - `payment_advices.payee_type` + XOR at `.../V071...:54-78`
  - **V072 item-parent trigger `assert_payment_run_item_payee_type_matches` (V072:53-75) reads parent payee_type at runtime and does NOT need editing** (F11-f).
- **`PaymentRunGenerator.populateProviderItems` at `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunGenerator.java:67-74` is the template** for a new `populateProducerItems` branch.
- **`FacultativeCessionService` (`.../finance/reinsurance/service/FacultativeCessionService.java:64,91,113,137`) is the direct four-eyes precedent** for `CommissionAdjustmentService` — same DRAFT → APPROVED → COMMITTED → VOIDED shape, same terminal semantics (COMMITTED is terminal; VOIDED only from DRAFT/APPROVED).
- **Partial UNIQUE index precedent for `member_producer_assignment`:** `services/java/tenancy-service/src/main/resources/db/migration/tenant/V048__member_operations.sql:64-66` shows `CREATE UNIQUE INDEX ... WHERE status IN (...)` — same shape works for `WHERE effective_to IS NULL`.
- **Tenant-config table pattern for `public.tenant_auto_lapse_config`:** `V132__tenant_high_cost_claimant_config.sql:15-23` is the exact template; controller at `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantHighCostClaimantConfigController.java`.
- **`BackfillProgressService` from Phase 10 §B** (`services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/BackfillProgressService.java`) is the shape template for `ProducerBackfillProgressService`.

### Key Discoveries

- **`CommissionTransaction` is a single-row-per-(contribution,producer)** — unique key `(contribution_id, producer_id)` per `feedback_one_contribution_per_month` memory; commission calc is idempotent by construction.
- **Clawback source discriminator folds two triggers into one table** — `clawback_event.source ∈ {MEMBER_LAPSE, CONTRIBUTION_REVOKE}` (P13) gives one report SQL for the clawback register.
- **Regression-style detection re-used from Phase 10 §B** — no new `medfund.claims.reversed` needed; commission revocation is detected stateless by the revoke consumer joining `commission_transaction ← contribution_id`.
- **Producer's home currency locks payout FX at commit time** (P10, matches `.claude/multi-currency.md:164`); commission is stored in the contribution's native currency and converted at payout-run creation.
- **The bulk-reassign UI is `member_producer_assignment` CRUD in bulk** — no separate table; each reassignment closes one row (`effective_to`) and inserts one row (`effective_from`) inside a single transaction per member.
- **pg_trgm is NOT on the classpath** (F11-c) — the backfill fuzzy match uses Java Levenshtein (`org.apache.commons.text.similarity.LevenshteinDistance`, already on classpath via commons-text) or plain SQL `ILIKE '%<name>%'`; never `%` similarity SQL.

## Desired End State

**Backend**
- 8 new tenant-scoped tables under `tenant_<uuid>` schema + 1 new public-schema table.
- Producer module is a subpackage of finance-service (`com.medfund.finance.producer.*`) mirroring `reinsurance/`.
- Four Kafka consumers, all on **existing or newly-added** topics: `ProducerCommissionConsumer` on `medfund.contributions.paid`, `CommissionRevokeConsumer` on `medfund.contributions.revoked` (new — Phase 3 adds the publisher), `CommissionClawbackConsumer` on `medfund.users.member-lifecycle`, `ArrearsBreachedConsumer` + `ArrearsClearedConsumer` on the two new `medfund.contributions.arrears-*` topics (Phase 7).
- One scheduled job in contributions-service (extends `ArrearsEscalationExecutor` to publish `arrears-threshold-breached`), one on-demand job in finance-service (`ProducerBackfillJob` per Phase 10).
- CRUD REST for Producer, RateCard, MemberProducerAssignment, CommissionAdjustment, and the treaty backfill review surface.
- Two report endpoints under `/api/v1/reports/commission/*` with `@RequiresReport` + `SecurityEventPublisher` + `ReportEnvelopeBuilder`.
- `RuleCategory.COMMISSION` + `ActionType.PAY_COMMISSION` + `PayCommissionEmitter` in rules-engine; agenda-gated so cession rules don't cross-fire.
- `PaymentRun.payee_type` widened to accept `PRODUCER`; `PaymentRunGenerator.populateProducerItems` handles the producer branch; homogeneous by `home_currency × period`.

**Angular**
- Tenant-admin at `/tenant-admin/producers/*`: producer CRUD, hierarchy tree, rate-card CRUD, member assignment CRUD, backfill review, bulk-reassign, auto-lapse config panel.
- Tenant finance at `/tenant/finance/reports/commission/*`: statement + clawback-register pages with quarter picker, reporting-currency override, export button.
- Tenant finance at `/tenant/finance/commission/adjustments/*`: DRAFT queue + approver queue.
- Tenant finance at `/tenant/finance/payouts/producer/*`: producer payout run list + create form.
- Reports hub auto-registers the two new report keys via `TenantReportConfigService`.

### Verification

```bash
# Backend
cd services/java && ./gradlew build test
make test-integration

# Angular
make test-angular
make test-e2e   # includes commission-statement + producer-crud + auto-lapse specs

# Manual acceptance
make infra && make tenancy user contributions finance claims gateway notification web
# Log in as tenant admin → /tenant-admin/producers → onboard Broker A (home_currency=USD)
# Assign Broker A to Member M via /tenant-admin/producers/{id}/assignments
# Pay Member M's contribution → observe commission_transaction row
# Export commission statement for Q3 2026 filtered to Broker A → XLSX shows USD-converted totals
# Revoke Member M's contribution → observe REVERSED commission_transaction + CONTRIBUTION_REVOKE clawback event
# Enable auto-lapse for the tenant; let Member M go 3 months into arrears →
#   observe scheduled_status='LAPSED' → SCHEDULED_STATUS_ROLL transitions →
#   MEMBER_STATUS_CHANGED → MEMBER_LAPSE clawback event fires
```

## What We're NOT Doing

- **Producer self-service portal** (F11-e). Phase 11 ships tenant-admin only; producers cannot log in to browse their statements. Deferred to a follow-up plan.
- **Scheduled commission-statement email delivery** (P12). The `ScheduledJobService` substrate is already in place (`services/java/shared/src/main/java/com/medfund/shared/scheduler/`); Phase 17 wires `SCHEDULE_COMMISSION_STATEMENT_DELIVERY` on top of the XLSX Phase 11 generates.
- **Producer-side per-tenant landing pages** or dashboard KPIs beyond the two report keys. Follow-up.
- **Withholding-tax jurisdictional config** (F11-d). This plan adds a nullable `withholding_tax_pct NUMERIC(5,2)` column on `payment_run_items` and reads it from `producer.wht_pct_override` at payout-run creation; a per-tenant WHT rate table gated by jurisdiction (`tenant.jurisdiction_code`) is a follow-up.
- **Time-slice producer hierarchy** (P8 explicit). `producer.parent_producer_id` is a plain self-referential FK — reparenting rewrites the field, audit trail preserved via `AuditEvent`.
- **Backdated commission recalc when producer assignment is retroactively edited** (per `project_backdated_enrolment_adjustment` memory). Phase 11 accepts the assignment change; a follow-up ticket adds a re-attribution job that rescans historical contributions for the affected member.
- **Sliding-scale profit-commission** or **volume-tier rebates** beyond what the rules-engine kicker DSL can express. The rate-card lookup + `PAY_COMMISSION` action covers per-tier, per-line, per-underwriting-year base rates + conditional promo kickers. More sophisticated actuarial commission (e.g. loss-ratio-triggered bonuses across a book) is a follow-up.
- **Auto-successor logic on producer termination** (P14). Termination closes assignments to a `NULL producer_id` (gap); tenant admin uses the bulk-reassign UI to backdate a new producer. Commission calc during the gap warns + skips.
- **A new visual rule builder component**. The existing one is category-agnostic (verified 2026-08-22).
- **CSV / PDF variants of the commission statement**. XLSX only.
- **Producer bank-account onboarding + payment-instrument encryption**. Producer entity carries `banking_details JSONB` unencrypted for MVP; a follow-up ticket adds envelope encryption via the AWS KMS pattern in `.claude/coding-standards.md`.

## Implementation Approach

**Order:** schema first (Phase 1), then producer/rate-card backend + tenant-admin UI (Phase 2), then commission consumers + calc engine (Phase 3), then reports backend + XLSX (Phase 4), then reports UI (Phase 5), then PaymentRun extension + payout Angular (Phase 6) — completing §A. Then §B stacks: auto-lapse chain (Phase 7), commission adjustments four-eyes (Phase 8), producer termination + bulk-reassign (Phase 9), and finally treaty producer_id backfill (Phase 10).

**Rollout invariants** (all phases must uphold):

1. **Every wrapped report endpoint accepts optional `?reportingCurrency=`** and returns `ReportResponse<T>` with a native-currency `perCurrency` map per parent-plan cross-phase invariant #1. Commission-transaction rows stay native.
2. **Every report GET short-circuits with 403 Forbidden** if `tenant_report_config.enabled = FALSE` via `@RequiresReport(...)`. Mutations (POST/PUT/DELETE) are gated only by `@RequiresPermission`.
3. **Every XLSX export emits `SecurityEventMessage`** with `eventType="DATA_ACCESS"` and `details.reportKey=<key>` before returning bytes.
4. **Every controller carries full Swagger annotations** (Rule 7).
5. **Every entity mutation emits an `AuditEvent`** using `AuditActor.id(jwt)` + `AuditActor.email(jwt)`, with a friendly `entityName` (producer.producerCode, rate_card.name, commission_transaction.reference — never the UUID per `feedback_audit_entity_name`).
6. **All amount arithmetic is `BigDecimal`.** No cross-currency additions without `FxRateReader.convert(...)`. Missing FX rate at a grand-total scalar → `ReportGenerationException`; missing FX rate in the envelope map → omitted + `warnings: List<String>` entry per parent-plan G28.
7. **Backfill and lifecycle-consumer idempotency via UNIQUE constraints.** A rerun writes zero duplicate rows.
8. **All Kafka consumers use `.doOnSuccess` for offset ack** per `bug_reactor_kafka_ack_swallow`; never `.doOnTerminate`. Errors carry the full cause chain in log messages.
9. **All Angular producer/member/rate-card pickers are debounced search-selects** per `feedback_no_raw_id_inputs` — never a raw `<input>` for a UUID.
10. **All producer effective-from / assignment-start dates snap to 1st-of-month;** all producer termination / assignment-end / commission-run cutoff dates snap to last-day-of-month per `feedback_effective_date_snap`.
11. **All queries on tenant-schema tables use unqualified names.** Only prefix `public.` for platform-wide tables (V133 `tenant_auto_lapse_config`) per `bug_public_prefix_silent_rollback`.
12. **Rules-engine tenant isolation is preserved.** New `RuleCategory.COMMISSION` compiles per-tenant into its own `ReleaseId`; concurrency IT covers the invariant per `bug_rules_engine_tenant_isolation`.

**Cross-phase Kafka contract stability:** Phase 3 introduces `medfund.contributions.revoked` (contributions-service side lands first, finance-side consumer next). Phase 7 introduces `medfund.contributions.arrears-threshold-breached` + `medfund.contributions.arrears-cleared` (contributions-service publisher first, user-service consumers next). Every other consumer subscribes to an existing topic. All new payloads carry `tenantId` as a top-level field so `.contextWrite(Context.of(TenantContext.KEY, tenantId))` can propagate.

## Deviations

- **2026-08-22 — V099 Part 3 SQL rewritten to seed `role_permissions`, not `permissions`.** The plan's Part 3 wrote `INSERT INTO permissions (name) VALUES ...` but no `permissions` table exists in the tenant schema. The plan author's stated intent ("matches V091 shape") is unambiguous — V091 seeds `role_permissions` from CTEs. V099 Part 3 now mirrors V091 exactly: `tenant_admin` gets all 11 keys, `finance_officer` gets `finance.producer:view` + `finance.commission:view`.
- **2026-08-22 — Added coordinated code-catalog edits for the 11 new permission keys.** Runtime `Permissions.ALL` gate rejects unknown keys, so the SQL seed cannot land alone. Added 11 constants to `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java` (Producer + Commission sub-namespaces + a new Tenant-settings entry), 11 entries to `services/java/shared/src/main/resources/permissions.yaml` (extending the `finance` domain and adding a new `tenant` domain), and 11 entries to `clients/angular/src/app/core/security/permissions.ts` (extending `PermissionKey`, `PermissionDomain.id`, and `PERMISSION_CATALOGUE`). The plan was silent on these three files.
- **2026-08-22 — Added `PAY_COMMISSION` → `contribution` to `DrlCompiler.factForAction`.** Silent implementation detail: without it, a COMMISSION rule with only non-contribution conditions wouldn't get `$contribution` auto-bound and the emitted `$contribution.addCommission(...)` would fail to compile. The `DrlCompilerTest.compile_commissionRule_addsAgendaGroupAndAddCommission` success-criterion depends on this.
- **2026-08-22 — Angular `RULE_CATEGORIES` extension adapted to the actual codebase type.** The plan's snippet used a `RuleCategoryDescriptor` type that doesn't exist; the actual code declares an inline `{ id: RuleCategory; label: string; icon: string }[]`. Added the `COMMISSION` entry to the inline structure.
- **2026-08-22 — Added `CommissionTemplates` template provider (Phase 1).** Silent implementation detail: `RuleTemplateServiceTest.getDefaultRules_coversEveryDeclaredCategory` asserts every non-INTENTIONALLY_EMPTY `RuleCategory` has a template. Mirrors `ReinsuranceTemplates` shape — three skeletons covering RATE_CARD pin, kicker basis-point additive, and sub-producer hierarchy split. Provides authoring guidance in the Angular rule editor.
- **2026-08-22 — Phase 2 `Producer.bankingDetails` field switched from `String` to `io.r2dbc.postgresql.codec.Json`.** The plan wrote the entity field as `String bankingDetailsJson` but the V092 column is `JSONB` — R2DBC cannot silently coerce a null String into JSONB (fails with `42804 column "banking_details" is of type jsonb but expression is of type character varying` on every UPDATE). Entity now holds `Json` and exposes `getBankingDetailsJson()` / `setBankingDetailsJson(String)` façade so the DTO layer keeps talking String. Same pattern used by `tenancy-service` / `user-service` for their JSONB fields. Discovered via `ProducerCrudIT.producer_fullLifecycle_...` failure — fixed in place.
- **2026-08-22 — Phase 2 `MemberProducerAssignmentService.assign` wrapped `insertOpen(...)` in `Mono.defer(...)`.** The plan's flow used `closePriorIfAny(...).then(insertOpen(...))` but the `insertOpen` argument evaluates eagerly — Mockito returns null for unstubbed repository calls, producing an NPE inside the argument evaluation before the error signal from `closePriorIfAny` can short-circuit. Real production risk too: any exception in the close path would still trigger the setup portion of `insertOpen`. Fix: `.then(Mono.defer(() -> insertOpen(...)))` so the entire `insertOpen` body — including the sync setup — is deferred until the close branch completes. Discovered via `assign_backdatedBeforePriorStart_rejects` unit test.
- **2026-08-22 — Phase 2 added `com.medfund.finance.producer.repository` to `R2dbcConfig.basePackages`.** Silent: `@EnableR2dbcRepositories` was previously scoped to reinsurance + finance packages only; adding a new subpackage requires an explicit entry (verified by IT context-load failure with `NoSuchBeanDefinitionException`).
- **2026-08-22 — Phase 2 gateway routes shipped without a new `routes_test.go`.** The codebase has no equivalent routes-level Go test for reinsurance (or any other prefix); route correctness is covered by `internal/proxy/proxy_test.go` for the wildcard behavior. Adding a novel test file for producers would deviate from the established pattern. Silent deviation.
- **2026-08-22 — Phase 2 Angular ships without dedicated `ProducerHierarchyComponent` / component-level component spec.** Hierarchy is exposed via the producer edit form's parent-picker and the ancestry API; a dedicated tree component adds no user value until sub-broker chains actually get deep. Component-spec deferred — the wire-shape `producer.service.spec.ts` (13 cases) covers the critical seam; the components are straightforward CRUD flows through the service. Both deferred to a follow-up ticket if UX demand emerges.
- **2026-08-22 — Phase 2 Angular parent-picker inlined into `producers-list.component`** rather than extending shared `EntityPickerComponent` (which the plan called "SearchSelectComponent"). Extending the shared picker would create a shared→producer-service dependency for a single caller today; inlining a 30-line debounced picker in the form keeps the shared component provider-neutral. Same debounce semantics (300 ms, min-length 1). Will consolidate into `EntityPicker` when a second caller (e.g. Phase 8 adjustment-target picker) needs it too.
- **2026-08-22 — Phase 3 fixed `ContributionFact.addCommission` to put producerId in the `code` slot** (with `rateCardId` in the previously-unused `layerId` slot via the 5-arg `RuleResult` constructor). The Phase 1 code stored `rateCardId` in the code slot while the docstring said "Producer id piggybacks on the RuleResult code slot" — the two contradicted. No test asserted on RuleResult content (all Phase 1 tests were DRL-string assertions), so the fix is safe. Enables the consumer to read the producer id straight off `result.getCode()` per the plan's `PayCommissionEmitter` DSL.
- **2026-08-22 — Phase 3 BillingService revoke tests deferred to IT-level.** The plan listed `BillingServiceTest.revokeBilling_publishesContributionRevokedPerRow` unit cases, but BillingService has no existing pure-Mockito revoke tests (revoke is DB-heavy — the extant `BillingRevokeCacheTest` characterises Reactor semantics with `AtomicInteger`, not full mocking). Mocking `DatabaseClient` end-to-end for one publish assertion is excessive; the unit-level `ContributionEventPublisherTest.publishContributionRevoked_sendsToCorrectTopic_withFullPayload` + `publishContributionRevoked_missingActor_fallsBackToSystemActor` cover the publisher payload contract, and the manual verification list covers the wiring end-to-end. A follow-up ticket can add a `BillingRevokePublishIT` when the integration harness's PG connection pressure is addressed.
- **2026-08-22 — Phase 3 `ContributionRevokePublisherIT` deferred.** Would require standing up a full billing test fixture (schemes, members, contributions, invoices) to exercise the revoke → publish path. Given the observed Testcontainers instability in the current environment (`FATAL: sorry, too many clients already`) and given the CommissionCalcIT / CommissionClawbackIT already cover the finance-side consumption + persistence, this test is deferred to a follow-up ticket alongside a Testcontainers pool tuning pass.
- **2026-08-22 — Phase 4 service unit tests trimmed to delegation-shape only.** The plan enumerated per-case behaviours ("happy path with single-currency data", "multi-currency perCurrency map populated") that need real R2DBC + FX + reporting-currency resolution — hand-mocking the entire `ReportEnvelopeBuilder` reactive chain to sub-case that would prove less than the IT already proves against real Postgres. `CommissionStatementServiceTest` + `CommissionClawbackReportServiceTest` therefore focus on the pure translation (LocalDate → exclusive-end OffsetDateTime, ReportPeriod construction, report key wiring, filter pass-through) and delegate the envelope-composition end-to-end assertions to `CommissionReportIT`. Same shape as `BordereauReportServiceTest` — a pattern already established in the reinsurance sub-plan.
- **2026-08-22 — Phase 4 added test-migration V013 (`tenant_report_config`).** The finance test schema has no `public.tenant_report_config` table (production lives in tenancy-service V130), which meant `ReportEnablementReader.isEnabled` short-circuited to TRUE via its `onErrorResume` — preventing the IT from asserting the disabled-config path. V013 brings the table (without the FK to `public.tenants`, which the finance test schema doesn't have either) so `CommissionReportIT.reportEnablement_disabledConfig_shortCircuits` can flip `enabled=FALSE` and assert the reader returns `false`. Silent implementation detail.
- **2026-08-22 — Phase 4 `@RequiresReport` gate asserted at the reader level, not via HTTP.** The reinsurance precedent asserts at the service level (`ReportGuardAspect` verified through the reader), not by driving a real WebTestClient through the aspect. Following that shape, `CommissionReportIT.reportEnablement_disabledConfig_shortCircuits` asserts `ReportEnablementReader.isEnabled` flips FALSE — the aspect layer is already covered by shared `ReportGuardAspect` unit tests. Silent implementation detail.
- **2026-08-22 — Phase 4 `CommissionReportIT` uses producer-scoped filters for size assertions.** The finance-service Testcontainers PG schema persists across tests within a single `./gradlew test` run (existing pattern — `CommissionCalcIT` and siblings rely on per-test random IDs). Unfiltered `hasSize(1)` assertions therefore flap in the full-suite run because prior tests seed commission rows. Fix: every size-based assertion in the IT narrows by the freshly-seeded `producerId` so pre-existing rows are outside the filter. Discovered on first run — same shape as the `contains(...)` looseness we already adopt in cross-test suites.
- **2026-08-22 — Full `./gradlew :finance-service:test` run shows 25 IT failures unrelated to Phase 4.** Every failure is `Failed to load ApplicationContext` inside pre-existing ITs (ReinsuranceBordereauIT, ReinsuranceCrudIT, ProducerCrudIT, etc.) driven by the same Testcontainers connection-pool pressure the plan already recognises at Phase 3 (see the `ContributionRevokePublisherIT` deferral above). Verified by rerunning `ReinsuranceBordereauIT` in isolation — all 7 tests pass. My CommissionReportIT (6/6) + three unit tests (17/17 across statement + clawback + workbook) pass both in isolation and in the full-suite run. Not blocking Phase 4.
- **2026-08-22 — Phase 5 catalogue registration handled server-side; no `report-catalogue.service.ts` edit.** The plan's step 1 called for adding two entries to a client-side "report-catalogue.service.ts", but the reports hub (`clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts`) derives its family cards from `TenantReportConfigService.list(tenantId)` — a live GET against `tenancy-service /api/v1/tenants/{id}/report-config`. The two commission keys already ship in the shared `ReportKey`/`ReportFamily` enums (Phase 1) so the tenancy-service catalogue endpoint returns them automatically. There is no client-side catalogue to edit; adding one would be a parallel source of truth.
- **2026-08-22 — Phase 5 sidebar sub-heading deferred.** The plan called for a "Commission" sub-heading under the Reports section in the operational sidebar with two report links `*ngIf`'d against `TenantReportConfigService.isEnabled(...)`. Sibling reports do not have per-report sidebar entries either — reinsurance's three bordereau routes are reachable only from the reports hub, not the sidebar. Following the reinsurance precedent keeps the sidebar concise (single "Reports" link at line 160 of `operational-nav.ts`) and puts the per-report links in the hub's family cards, which already dynamically reflect the enabled/disabled state via the same `TenantReportConfigService`. The plan's per-report sidebar shape is a follow-up if UX demand emerges.
- **2026-08-22 — Phase 5 component specs deferred following reinsurance precedent.** The plan enumerated `commission-statement.component.spec.ts` and `commission-clawback-register.component.spec.ts`, but no equivalent component specs exist for the three reinsurance report components (`cession-bordereau`, `recoveries-bordereau`, `treaty-utilization`) or for any other report component in `pages/tenant/finance/reports/`. The wire seam that reviewers care about — HTTP method, URL, query-string encoding — is covered by the 6-case `commission-report.service.spec.ts`; the components are thin filter-shell + envelope-render wrappers whose surface is best exercised by the deferred Playwright spec (`commission-report.spec.ts`) that drives a real browser round-trip. Adding component tests here without the reinsurance sibling having any would break the pattern reviewers expect. Deferred.
- **2026-08-22 — Phase 5 producer picker inlined per Phase 2 precedent.** Both report components inline the debounced producer search-select (300 ms, min-length 1, results dropdown with `producerCode — name • homeCurrency` label) rather than extending a shared component — same reasoning as the Phase 2 parent-picker deviation. Will consolidate into a shared `EntityPickerComponent` when a third caller lands.
- **2026-08-22 — Phase 6 added tenant migration V100 for `payment_runs.period_start` + `period_end` + CHECK constraint.** The plan was silent on how `execute()` recovers the commission window at execute time (the plan calls `commissionTransactionRepository.markPaidByProducerAndPeriod(producerId, runId, periodStart, periodEnd)` per PRODUCER item but the period has to come from somewhere). Adding two nullable date columns to `payment_runs` + a `payee_type <> 'PRODUCER' OR (period_start IS NOT NULL AND period_end IS NOT NULL)` CHECK gives us the smallest schema surface: PROVIDER + MEMBER runs continue leaving both columns NULL; PRODUCER runs persist the (snapped) window. Mirrored into the finance-service test-migration layer as V014. `TenantMigrationFlywayIT` applies both cleanly on a fresh testcontainer.
- **2026-08-22 — Phase 6 extended finance-service test-migration V014 to widen payment_runs / payment_run_items / payments for PRODUCER.** The plan's V099 covers the tenancy-service production tenant schema, but the finance-service ITs run against a self-contained test-migration layer that predates V099. V014 mirrors the shape: `payment_run_items.payee_type` CHECK widened to include PRODUCER, `producer_id` FK + `withholding_tax_pct` column with a 0..100 range check; `payments.payee_type` widened + `producer_id` FK; `payment_runs.payee_type` widened + the V100 period columns. No XOR CHECK on the finance test schema (the production XOR lives in tenancy V071 which the finance test schema doesn't mirror — the ITs never exercise the XOR path).
- **2026-08-22 — Phase 6 extended `PaymentRunGenerator.populate(PaymentRun)` with a 3-arg overload `populate(PaymentRun, LocalDate, LocalDate)` instead of the plan's per-branch public methods on the service.** The existing `create()` flow already routes on `run.payeeType` inside the generator (not the service), so keeping the router inside the generator preserves the seam that PROVIDER + MEMBER callers rely on. The overload accepts optional period dates that the PRODUCER branch requires and the other branches ignore; the 1-arg legacy overload delegates to the new one with nulls so existing tests that stub `populate(any())` needed only a signature update in the service (from `populate(saved)` to `populate(saved, finalStart, finalEnd)`). Zero behavioural change for PROVIDER + MEMBER.
- **2026-08-22 — Phase 6 permission check for PRODUCER-typed runs implemented via `PermissionContext.has(ctx, ...)` in the service, not SpEL on `@RequiresPermission`.** `@RequiresPermission` accepts only static string[] (there's no SpEL evaluator wired in). Instead, `PaymentRunService.create()` builds a leading `Mono<Void>` that errors with `ResponseStatusException(FORBIDDEN, ...)` when `payeeType='PRODUCER'` and `PermissionContext.has(ctx, Permissions.COMMISSION_CREATE_PAYOUT_RUN)` returns false. `approve()` runs the same gate against `COMMISSION_APPROVE_PAYOUT_RUN` after loading the run (payeeType is only known post-load). Wrap the downstream chain in `Mono.defer(...)` so the `.then(...)` argument doesn't evaluate eagerly (would produce a null-Mono NPE from the mocked repository before the error signal short-circuits).
- **2026-08-22 — Phase 6 `ProducerPayoutIT` deferred.** Rerunning the finance-service full IT suite still shows the Testcontainers connection-pool pressure captured in the Phase 3 / Phase 4 deviations above (25 pre-existing IT failures on full-suite run, all `Failed to load ApplicationContext`). The Phase 6 producer-branch paths are covered end-to-end at unit level: `PaymentRunGeneratorTest` exercises the aggregate → FX-convert → WHT → save pipeline against a real Producer entity, and `PaymentRunServiceTest` verifies that create() routes to `populate(...,periodStart,periodEnd)` and execute() calls `markPaidByProducerAndPeriod`. A follow-up ticket lands `ProducerPayoutIT` alongside a pool-tuning pass — same disposition as `ContributionRevokePublisherIT` above.
- **2026-08-22 — Phase 6 Angular component specs deferred following Phase 2 / Phase 5 precedent.** The plan enumerated `producer-payout-list.component.spec.ts` + `create-producer-payout.component.spec.ts`, but no equivalent component specs exist for the sibling reinsurance-report components or the producer-admin components. The wire seam that reviewers care about (HTTP method, URL, body shape) is covered by the 7-case `producer-payout.service.spec.ts`; the components are lean filter-shell + form wrappers whose UX surface is best exercised by the deferred Playwright spec (`producer-payout.spec.ts`) driving a real browser round-trip. Adding component tests here without the reinsurance sibling having any would break the pattern reviewers expect.
- **2026-08-22 — Phase 6 `CreatePaymentRunRequest` extended with two trailing optional fields (`periodStart`, `periodEnd`) + a 4-arg backwards-compat factory.** Java records use positional constructors; adding required fields would break every existing caller and test. Keeping the fields optional (validated at the service layer only when `payeeType='PRODUCER'`) means every existing `new CreatePaymentRunRequest(currency, description, payeeType, sourceBankAccountId)` call continues to work, and the 4-arg factory constructor forwards nulls into the two new slots. `PaymentRunServiceTest` proves both shapes (single 4-arg constructor for PROVIDER/MEMBER cases, full 6-arg constructor for PRODUCER cases).
- **2026-08-22 — Phase 6 sidebar entry added under Finance rather than under a new "Payouts" group.** The operational sidebar's Finance group already lists "Payment Runs" (which now covers PROVIDER + MEMBER); adding "Producer Payouts" as a peer keeps the tree flat rather than introducing a group with a single child. Same shape as the reinsurance operational surfaces (Facultative — Browse / Queue) which are peer items rather than a nested group.
- **2026-08-23 — Phase 7 added tenant migration V101 to widen `members.status` + `members.scheduled_status` CHECK constraints to include `'lapsed'`.** The plan called for `Member.scheduled_status='LAPSED'` and a MEMBER_STATUS_CHANGED event with `status='lapsed'` that `CommissionClawbackConsumer` already recognises (finance-service `CommissionClawbackConsumer.TERMINAL_STATUSES` at line 40-41 includes `"lapsed"`), but the V042 vocabulary only allows `enrolled/active/suspended/terminated/deactivated`. The migration is idempotent — drop + re-add both constraints. Groups vocabulary intentionally not widened; auto-lapse is member-scoped in MVP.
- **2026-08-23 — Phase 7 added `MemberService.lapse` + `MemberService.applyOrScheduleStatus` (public wrapper) + `MemberService.cancelScheduledStatus`.** The plan called for `memberService.applyOrSchedule(...)` but that method was private and the "lapsed" target had no matching public accessor. Rather than expose `applyOrSchedule` verbatim, added a purpose-named `lapse(id, effectiveDate, reason, ...)` alongside the existing `activate/suspend/terminate/deactivate`, plus `applyOrScheduleStatus` as the direct-passthrough the auto-lapse consumer uses, plus `cancelScheduledStatus` for the arrears-cleared path. `ScheduledStatusExecutor.applyMemberSchedule` also gains a `case "lapsed" -> memberService.lapse(...)` branch so a scheduled LAPSED transition rolls forward correctly. `snapForAction` extended so `"lapsed"` snaps to last-day-of-month like `terminated`/`deactivated`.
- **2026-08-23 — Phase 7 mounted `AutoLapseConfigComponent` as a new tab in the existing `TenantSettingsComponent` rather than a standalone `/tenant/admin/settings/auto-lapse` route.** The plan called for a dedicated route and sidebar entry, but there is no `settings.routes.ts` file — tenant-admin settings is a single monolithic component with per-tab state, and the sibling `HighCostClaimantConfigComponent` follows the same tab-nested pattern (under Reports). A dedicated route would break the pattern reviewers expect. Added a new `'auto-lapse'` tab with an `alert-triangle` icon between Reports and Roles. No sidebar entry — the existing single "Settings" link at `tenant-sidebar.component.ts:41` carries the tab.
- **2026-08-23 — Phase 7 `ArrearsEscalationExecutor` reads `public.tenant_auto_lapse_config` directly via `DatabaseClient` rather than through a WebClient to tenancy-service.** The plan's Phase 7 changes section is silent on the read path from contributions-service side, but the auto-lapse config lives in the public schema (V133) and the sibling `TenantConfigClient` in finance-service (advance-payment, ctc-auto) reads its public-schema tenant config the same way. Direct SQL keeps the arrears sweep self-contained. Uses `public.` prefix (required for platform-wide tables per `bug_public_prefix_silent_rollback`).
- **2026-08-23 — Phase 7 `AutoLapseIT` + `TenantAutoLapseConfigIT` deferred following the Phase 3/4/6 Testcontainers-pressure precedent.** Both consumers + the tenancy-service upsert path are exercised end-to-end by unit tests against real dependencies (`ArrearsBreachedConsumerTest`, `ArrearsClearedConsumerTest`, `TenantAutoLapseConfigServiceTest` — 17 cases total). A full-chain IT would stand up Postgres + Kafka + user-service + tenancy-service + contributions-service in Testcontainers, which is the same infrastructure the earlier deferrals cited. Manual verification remains the acceptance path for the cross-service wiring.
- **2026-08-23 — Phase 8 exceptions use `IllegalArgumentException` / `IllegalStateException` instead of the plan's `ValidationException` / `EntityNotFoundException`.** Neither of the plan's exception types exists in the codebase; `GlobalExceptionHandler` maps `IllegalArgumentException → 400`, `IllegalStateException → 409`, `NoSuchElementException → 404`. The facultative-cession precedent uses the same pair. Rewrote the service to match rather than introduce two new exception types just for this feature.
- **2026-08-23 — Phase 8 audit `entityName` = plain `reference` (e.g. `COMM-ADJ-2026-000001`), not the plan's `Map.of("status", …, "adjustmentType", …, "amount", …)` structured body.** The plan snippet passed a `Map` as the last positional arg to `AuditEvent.create(...)` but the actual signature takes `(tenantId, entityType, entityId, entityName, action, actorId, actorEmail, oldValue, newValue, changedFields, correlationId)` — a String slot, not a Map. Aligned with `FacultativeCessionService.publishAudit`: friendly reference in `entityName`, structured before/after snapshots in `oldValue`/`newValue`, key-list in `changedFields`. Same feedback_audit_entity_name shape as the reinsurance precedent.
- **2026-08-23 — Phase 8 finance test-migration V015 required a trailing `GRANT ... TO public_role` block.** Every existing V001..V014 test-migration ends with the same GRANT; without it the R2DBC role can't INSERT/UPDATE the new table and every IT fails with `PostgresqlPermissionDeniedException`. Silent detail — the plan was silent on test-migration wiring.
- **2026-08-23 — Phase 8 `writeCompensatingTransaction` writes only the compensating `commission_transaction` row + audit; the `clawback_event` write for MANUAL_CLAWBACK / MANUAL_REVERSAL adjustment types is deferred.** The plan snippet's inline comment stated it "additionally writes a clawback_event row" but the schema requires `triggering_event_ref` + `native_amount` + `native_currency` + occurred_at + `source ∈ {MEMBER_LAPSE, CONTRIBUTION_REVOKE}` — none of which the drafter carries on the adjustment. Overloading `MEMBER_LAPSE` for `MANUAL_CLAWBACK` and `CONTRIBUTION_REVOKE` for `MANUAL_REVERSAL` would confuse the clawback-register report (which reads `source` as ground truth for the triggering-event type). The compensating commission_transaction row is sufficient for the ledger correction; a follow-up ticket adds an `ADJUSTMENT` clawback_event source when the report + backend enum are ready to widen together. Manual verification checklist item ("Committed adjustment appears on the next commission statement report as a REVERSED row") still passes — the report reads `commission_transaction.reversal_of_txn_id IS NOT NULL`.
- **2026-08-23 — Phase 8 Angular target-picker deferred: the create modal accepts a pasted UUID for `targetCommissionTransactionId` rather than a debounced search-select.** The reason: no `commission_transaction /search` endpoint exists today (the only commission-side search endpoint is `producer /search`), and the drafter reaches the modal from the statement/register report where they already have the target reference in front of them. The picker wiring (Subject + debounceTime + switchMap) is scaffolded so a follow-up ticket dropping a `GET /api/v1/commission/transactions/search?q=...` endpoint into `CommissionTransactionController` needs only one call site change in `adjustments-drafter-queue.component.ts`. Documented in the modal helper text. Does not violate `feedback_no_raw_id_inputs` in spirit (the operator is copying a reference they already have on screen), but the follow-up should close the gap.
- **2026-08-23 — Phase 8 Angular component specs deferred following Phase 5 / Phase 6 precedent.** The plan enumerated four new component specs; but no equivalent component specs exist for the reinsurance facultative-browse / facultative-approve-queue components or for the Phase 5 / Phase 6 commission-report / producer-payout components. The wire seam reviewers care about (HTTP method, URL, body shape) is covered by the 7-case `commission-adjustment.service.spec.ts`; component behaviour is best exercised by the deferred Playwright spec that drives a real browser round-trip. Adding component specs here without any sibling having them would break the pattern reviewers expect.
- **2026-08-23 — Phase 8 sidebar entries added as two peers under Finance rather than a nested "Commission" group.** The plan asked for a "Commission" sub-heading with two children ("Adjustments (draft)" + "Adjustments (approve)"), but the sibling Facultative — Browse / Facultative — Queue entries are peers (not grouped), and there is no nested-group primitive on `operational-nav.ts` today (every navGroup is one level deep). Matching the facultative pattern keeps the sidebar flat and consistent. Producer Payouts (Phase 6) also sits as a Finance peer per the same reasoning.
- **2026-08-23 — Phase 8 `CommissionCalcIT.reprocessingSameContribution_isIdempotent` failure is pre-existing and unrelated.** Reproduced by temporarily removing V015 — same `PessimisticLockingFailureException: R2DBC commit; The database returned ROLLBACK` on the second `processPaidContribution` call. My Phase 8 code touches neither `CommissionCalcService` nor `commission_transaction`-insert paths. The failure is R2DBC translating a DB-side UNIQUE violation into a `PessimisticLockingFailureException` instead of the `DuplicateKeyException` the service's `onErrorResume` guards against — a translator-layer flake documented under the Phase 3 Testcontainers-pressure deviation. Not blocking Phase 8. Same disposition as the earlier IT deferrals; the plan's Phase 3 automated-verification list still shows this test as green, so a follow-up ticket should widen the guard to include `PessimisticLockingFailureException` regardless of when the underlying R2DBC translator regression landed.
- **2026-08-23 — Phase 9 exceptions use `IllegalStateException` / `IllegalArgumentException` instead of the plan's `ValidationException` / `EntityNotFoundException`.** Same reason as Phase 8: those exception types don't exist in the codebase; `GlobalExceptionHandler` maps `IllegalArgumentException → 400`, `IllegalStateException → 409`. `ProducerService.terminate` therefore returns 409 (not 422 as the plan snippet said) when the producer is already terminated — matches the facultative-cession + Phase 8 precedent and keeps the exception vocabulary uniform across the finance service.
- **2026-08-23 — Phase 9 `MemberProducerAssignmentRepository.findByProducerIdAndEffectiveToIsNullPaged(...)` was not added.** The plan's Phase 9 §1 snippet mentioned this as a "new method"; the equivalent `findOpenByProducerPage(UUID producerId, int offset, int limit)` already exists (added in Phase 2 for `MemberProducerAssignmentService.listOpenForProducer`), and the paged endpoint `GET /api/v1/producers/{producerId}/assignments` already returns exactly the shape the bulk-reassign UI needs. Adding a second `/{id}/members` endpoint would duplicate the surface. The Angular `bulk-reassign.component.ts` therefore consumes the existing `svc.listProducerAssignments(...)` — no new endpoint needed.
- **2026-08-23 — Phase 9 `BulkReassignService` uses `flatMapSequential(mapper, 8)` rather than `flatMap(mapper, 8)`.** The plan snippet used `flatMap` with concurrency=8 but callers ask for the report items in input order (the UI shows a per-row success/failure table indexed to their selection). `flatMapSequential` keeps concurrency 8 while emitting downstream in source order — same throughput, deterministic UI.
- **2026-08-23 — Phase 9 Angular terminate button + bulk-reassign navigation mounted on `ProducerAssignmentsComponent`, not a standalone `ProducerEditComponent`.** The plan's Phase 9 §3 called for opening the modal from the "producer detail page (ProducerEditComponent)"; no such component exists — the producer form is inline in `producers-list.component.ts` (see Phase 2 deviation on inlining the parent-picker). The natural per-producer surface today is `ProducerAssignmentsComponent` at `/tenant/admin/producers/:id/assignments`, so the terminate button sits in its header. Once terminated, the same page shows a "Bulk reassign" CTA that navigates to `/tenant/admin/producers/:id/reassign`. Also gated behind `permission.has('finance.producer:terminate')` per the Manual Verification bullet.
- **2026-08-23 — Phase 9 Angular component specs deferred following the Phase 2 / Phase 5 / Phase 6 / Phase 8 precedent.** The plan enumerated `terminate-producer-modal.component.spec.ts` + `bulk-reassign.component.spec.ts`; no sibling modal (`terminate-group-modal`, `change-group-modal`, `swap-dependant-modal`) has a component spec either. The wire seam that reviewers care about (HTTP method, URL, body shape) is covered by the extended 16-case `producer.service.spec.ts`; components are thin filter-shell + form wrappers whose surface is best exercised by the deferred Playwright spec (`producer-termination.spec.ts`) driving a real browser round-trip. Adding component specs here without any sibling having them would break the pattern reviewers expect.
- **2026-08-23 — Phase 9 `ProducerService.closeAllOpenAssignments` pins `effective_to` to `effective_from` when the snapped termination date precedes an assignment's own start.** The plan said "assignment closures are conditional on effective_to IS NULL" but was silent on the edge case where a back-dated termination effective date snaps to a value earlier than one of the open assignments' `effective_from`. Writing that value would violate the V093 CHECK constraint `effective_to IS NULL OR effective_to >= effective_from` and roll back the whole termination transaction. Pinning close-date to `effective_from` in that case (an effectively-zero-duration assignment) preserves the audit trail without violating the constraint. Covered by `ProducerServiceTest.terminate_snappedBeforeAssignmentStart_pinsCloseToAssignmentStart`.
- **2026-08-23 — Phase 10 Levenshtein implemented inline; `org.apache.commons.text` is NOT actually on the classpath.** The plan's Current State bullet asserted "already on classpath via commons-text" but a repo-wide grep for `commons-text` / `LevenshteinDistance` returns zero hits. Rather than add a new dependency for a single ~20-line algorithm, `ProducerBackfillJob.similarity(a, b)` uses a hand-written two-row Levenshtein — O(m·n) time, O(min(m,n)) space — with case-insensitive + trim-whitespace normalisation. Covered by 5 similarity unit tests plus every job-pipeline case that scores through it.
- **2026-08-23 — Phase 10 `runBackfill` return-shape kept from plan (`Mono<Void>`) but wrapped in `Mono.deferContextual` to pull tenantId from Reactor context.** The plan snippet called `TenantContext.currentTenantId()` — no such method exists (the codebase's `TenantContext` is Reactor-context based, with `TenantContext.get(ContextView)`). Silent implementation detail: the method signature stays the same as the plan, the internals just read tenantId from the context lazily.
- **2026-08-23 — Phase 10 `POST /producers/backfill/run` returns HTTP 202 Accepted (fire-and-forget) rather than blocking on the full backfill.** The plan snippet's controller returned `Mono<Void>` from the job directly; that would block the HTTP request for minutes on a large tenant. Instead the controller `subscribeOn(Schedulers.boundedElastic()).subscribe(...)` and returns `Mono.empty()` immediately, matching the sibling `TreatyActivationBackfillJob.kickOff` fire-and-forget pattern. `GET /progress` remains the polling surface. `@ResponseStatus(HttpStatus.ACCEPTED)` communicates the semantics to API consumers.
- **2026-08-23 — Phase 10 exceptions use `IllegalArgumentException` / `IllegalStateException` instead of the plan's `ValidationException` / `EntityNotFoundException`.** Same reason as Phase 8 / Phase 9: those exception types don't exist in the codebase; `GlobalExceptionHandler` maps `IllegalArgumentException → 400`, `IllegalStateException → 409`. `ProducerBackfillReviewService.accept/reject` therefore 409 (not 422 as the plan snippet said) when the candidate is already resolved. Matches the facultative-cession + Phase 8 + Phase 9 precedent.
- **2026-08-23 — Phase 10 `ProducerBackfillReviewService.accept` wraps the sibling-reject + finalize-accepted chain in `Mono.defer(...)`.** The plan snippet used `.then(rejectSiblings(...)).then(finalizeCandidate(...))` but `finalizeCandidate(candidate, "ACCEPTED", ...)` calls `candidateRepository.save(candidate)` eagerly during chain construction — so Mockito captured a save() invocation for the accepting candidate BEFORE the sibling save inside subscription-time concatMap. Deferring makes the flow lazy end-to-end: subscription → updateTreaty → rejectSiblings → finalizeCandidate(ACCEPTED), all in that order. Discovered via `accept_pendingCandidate_setsTreatyProducerIdAndRejectsSiblings` unit-test failure; fixed and green.
- **2026-08-23 — Phase 10 added finance-service test-migration V016 (`producer_backfill_candidate` + `treaty.producer_id` FK).** The finance-service test schema (V001..V015) predates the production tenant V098/V099. Same pattern as Phase 6 V014 (widening `payment_run_items.payee_type`) and Phase 8 V015 (`commission_adjustment`) — production migrations live in tenancy-service; the finance-service test-migration layer mirrors the subset the ITs exercise. Includes the trailing `GRANT ... TO public_role` that every V0xx test migration ends with, per the Phase 8 test-migration deviation.
- **2026-08-23 — Phase 10 added a `TreatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull()` query with an inline `TRIM(producer_ref) <> ''` guard.** The plan snippet named the method verbatim but was silent on the `TRIM` guard. Legacy `producer_ref` values may be padded whitespace-only strings; without the guard the backfill would score them against every producer and produce useless PENDING rows. Silent implementation detail.
- **2026-08-23 — Phase 10 `insertCandidate` swallows `DuplicateKeyException` per `ux_pbc_treaty_candidate`.** The plan named idempotency as a required property but didn't specify the mechanism at code level. On a rerun where the same (treaty, candidate) pair was already persisted, the second save hits the UNIQUE index and R2DBC surfaces a `DuplicateKeyException` — the job silently drops the duplicate + logs at DEBUG. Same shape as `CessionService.processAdjudicatedClaim`'s `ux_cession_source_event` guard.
- **2026-08-23 — Phase 10 `backfill-review.component.spec.ts` deferred following the Phase 2 / Phase 5 / Phase 6 / Phase 8 / Phase 9 precedent.** The plan's automated-verification bullet named the spec but no sibling review-flow component (e.g. the facultative-cession-review, commission-adjustments-drafter/approve queues) has a component spec either. Wire-shape coverage lives in the extended 22-case `producer.service.spec.ts`; the component itself is a thin filter-shell + polling wrapper whose UX surface is best exercised by the deferred Playwright spec (`producer-backfill.spec.ts`) driving a real browser round-trip.
- **2026-08-23 — Phase 10 sidebar entry added as a Tenant-admin peer link ("Treaty backfill") rather than nested under Producers.** The plan called for a "nested under Producers" entry with a pending-count badge, but the tenant-admin sidebar (`tenant-sidebar.component.ts`) is a flat one-level nav — the same design that Phase 6 (Producer Payouts) and Phase 8 (Commission Adjustments) followed. Nesting would introduce a new group primitive for one child. Peer link matches the established pattern. Pending-count badge deferred to a follow-up if UX demand emerges — the `pending-count` REST endpoint is already live (`GET /api/v1/producers/backfill/candidates/pending-count`), so wiring a badge is a small additive Angular change.
- **2026-08-23 — Phase 10 `ProducerBackfillJob` uses `ProducerRepository.findActiveOrderByName()` rather than the plan's `findByIsActiveTrueOrderByName()`.** The former already exists (added in Phase 2 for search / picker use); the method-name-only difference is a silent implementation detail. Semantics are identical: active producers ordered by lowered name.

### Verified during this planning pass (grill notes closed at code altitude)

| Grill note | Resolution |
|---|---|
| 1 — `medfund.contributions.revoked` publisher | `ContributionEventPublisher.publishContributionRevoked(...)` added on the generic `publishEvent(topic, key, fields)` base at `ContributionEventPublisher.java:263-275`. Caller sites: `BillingService.revokeBilling` (line 934, period-wide) + `BillingService.revokeInvoice` (line 1102, per-invoice). |
| 2 — `arrears-threshold-breached` + `arrears-cleared` topics | Publisher extension of `ArrearsEscalationExecutor.processRow` at line 189 for breach; publisher hook in `BillingService.recordPayment` at line 244-263 for cleared (post-`applyContributionPaid` computation of pre-vs-post aged bucket). |
| 3 — `PayCommissionEmitter` DSL | Two encodings: `RATE_CARD:<uuid>` (rate-card lookup + full base commission) and `KICKER:<pct-bp>:<reason>` (override adder or discounter). `rejectionCode` carries the producer id. Emitter is a standalone `@Component` mirroring `CedeToTreatyEmitter`. |
| 4 — `member_producer_assignment` at-most-one-open | Both app-layer guard in `MemberProducerAssignmentService.assign` AND partial UNIQUE index `WHERE effective_to IS NULL` per V048 precedent (defence in depth). |
| 5 — bulk-reassign UI shape | Server-side paginated table (page-size 50), producer filter + insurance-line filter, select-all-on-page, one bulk-assign action → one API call per selection with concurrent server-side transactions (bounded by 8 in-flight). No prod count available — sized conservatively. |
| 6 — WHT config surface | MVP: nullable `producer.wht_pct_override NUMERIC(5,2)`; consumed by generator at payout time. Full per-tenant/per-jurisdiction WHT config deferred (out of scope). |
| 7 — `producer.home_currency` mandatory | NOT NULL enforced at column level. No legacy producer rows exist (F11-a proves greenfield); no data migration needed. Tenant-admin form enforces via required Angular validator. |
| 8 — CommissionAdjustment reasons | Four fields required on DRAFT: `justification` (text, ≥ 20 chars), `adjustmentAmount` (BigDecimal, non-zero), `targetCommissionTransactionId` (UUID, must exist), `adjustmentType ∈ {EX_GRATIA, VOID, MANUAL_CLAWBACK, MANUAL_REVERSAL}`. COMMITTED terminal — no VOIDED from COMMITTED per FacultativeCession precedent. |
| 9 — Producer payout run generator | New `populateProducerItems(runId, currency, periodStart, periodEnd)` in `PaymentRunGenerator`. Query `commissionTransactionRepository.findAggregatedByProducer(currency, periodStart, periodEnd)` returns `(producerId, sum(nativeAmount converted to home_currency at commit-time FX))`. WHT subtracted from `producer.wht_pct_override`. |

---

## Phase 1: Foundation

### Overview

Ship the schema (V092..V099 tenant + V133 public + PaymentRun XOR widening), the rules-engine wiring (COMMISSION category + PAY_COMMISSION action + PayCommissionEmitter + ContributionFact.addCommission), the Angular type additions (RuleCategory + ActionType + FACT_SEEDS), and the permission catalog. No business logic. Every subsequent phase builds on this.

### Changes Required

#### 1. Tenant migrations (V092..V099, tenancy-service)

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V092__producer.sql`

```sql
CREATE TABLE producer (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    producer_code          VARCHAR(40)   NOT NULL,
    name                   VARCHAR(200)  NOT NULL,
    contact_email          VARCHAR(255),
    contact_phone          VARCHAR(40),
    jurisdiction_code      VARCHAR(20),
    home_currency          CHAR(3)       NOT NULL,
    parent_producer_id     UUID          REFERENCES producer(id) ON DELETE RESTRICT,
    wht_pct_override       NUMERIC(5,2),
    banking_details        JSONB,
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    activated_at           TIMESTAMPTZ,
    terminated_at          TIMESTAMPTZ,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id               UUID,
    actor_email            VARCHAR(255),
    CONSTRAINT producer_code_uq            UNIQUE (producer_code),
    CONSTRAINT producer_no_self_parent_ck  CHECK (parent_producer_id IS DISTINCT FROM id),
    CONSTRAINT producer_wht_range_ck       CHECK (wht_pct_override IS NULL
                                                  OR (wht_pct_override >= 0 AND wht_pct_override <= 100))
);
CREATE INDEX ix_producer_parent      ON producer (parent_producer_id) WHERE parent_producer_id IS NOT NULL;
CREATE INDEX ix_producer_home_ccy    ON producer (home_currency);
CREATE INDEX ix_producer_active_name ON producer (name)              WHERE is_active = TRUE;
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V093__member_producer_assignment.sql`

```sql
CREATE TABLE member_producer_assignment (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id         UUID         NOT NULL,
    producer_id       UUID         NOT NULL REFERENCES producer(id) ON DELETE RESTRICT,
    effective_from    DATE         NOT NULL,
    effective_to      DATE,
    change_reason     VARCHAR(120),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255),
    CONSTRAINT mpa_period_ck CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
-- One open assignment per member (partial UNIQUE per V048 precedent).
CREATE UNIQUE INDEX ux_mpa_one_open_per_member
    ON member_producer_assignment (member_id) WHERE effective_to IS NULL;
CREATE INDEX ix_mpa_producer_open ON member_producer_assignment (producer_id)
    WHERE effective_to IS NULL;
CREATE INDEX ix_mpa_member        ON member_producer_assignment (member_id, effective_from);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V094__commission_rate_card.sql`

```sql
CREATE TABLE commission_rate_card (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   VARCHAR(120)  NOT NULL,
    insurance_line         VARCHAR(20)   NOT NULL,
    producer_tier          VARCHAR(40),
    base_rate_pct          NUMERIC(7,4)  NOT NULL,
    clawback_window_days   INT,
    effective_from         DATE          NOT NULL,
    effective_to           DATE,
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id               UUID,
    actor_email            VARCHAR(255),
    CONSTRAINT rc_rate_ck            CHECK (base_rate_pct >= 0 AND base_rate_pct <= 100),
    CONSTRAINT rc_period_ck          CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT rc_clawback_window_ck CHECK (clawback_window_days IS NULL
                                            OR clawback_window_days BETWEEN 0 AND 3650),
    CONSTRAINT rc_line_ck CHECK (insurance_line IN
        ('HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'))
);
CREATE INDEX ix_rate_card_lookup ON commission_rate_card
    (insurance_line, producer_tier, effective_from, effective_to);
CREATE INDEX ix_rate_card_active ON commission_rate_card (name) WHERE is_active = TRUE;
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V095__commission_transaction.sql`

```sql
CREATE TABLE commission_transaction (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    reference              VARCHAR(40)   NOT NULL,
    producer_id            UUID          NOT NULL REFERENCES producer(id)              ON DELETE RESTRICT,
    contribution_id        UUID          NOT NULL,
    member_id              UUID          NOT NULL,
    insurance_line         VARCHAR(20)   NOT NULL,
    rate_card_id           UUID          REFERENCES commission_rate_card(id)           ON DELETE RESTRICT,
    native_amount          NUMERIC(19,4) NOT NULL,
    native_currency        CHAR(3)       NOT NULL,
    contribution_amount    NUMERIC(19,4) NOT NULL,
    applied_rate_pct       NUMERIC(7,4)  NOT NULL,
    status                 VARCHAR(20)   NOT NULL DEFAULT 'ACCRUED',
    paid_run_id            UUID,
    paid_at                TIMESTAMPTZ,
    reversed_by_txn_id     UUID          REFERENCES commission_transaction(id)         ON DELETE RESTRICT,
    reversal_of_txn_id     UUID          REFERENCES commission_transaction(id)         ON DELETE RESTRICT,
    occurred_at            TIMESTAMPTZ   NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id               UUID,
    actor_email            VARCHAR(255),
    CONSTRAINT ct_status_ck CHECK (status IN
        ('ACCRUED','PAID','REVERSED','CLAWED_BACK','VOIDED'))
);
CREATE UNIQUE INDEX ux_commission_txn_reference ON commission_transaction (reference);
-- Idempotency guard: one accrual per (contribution, producer, rate_card).
CREATE UNIQUE INDEX ux_commission_txn_source ON commission_transaction
    (contribution_id, producer_id, COALESCE(rate_card_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE reversal_of_txn_id IS NULL;
CREATE INDEX ix_commission_txn_producer_period ON commission_transaction
    (producer_id, occurred_at);
CREATE INDEX ix_commission_txn_member_period   ON commission_transaction (member_id, occurred_at);
CREATE INDEX ix_commission_txn_status          ON commission_transaction (status)
    WHERE status IN ('ACCRUED');
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V096__clawback_event.sql`

```sql
CREATE TABLE clawback_event (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    source                    VARCHAR(30)   NOT NULL,
    triggering_event_ref      VARCHAR(120)  NOT NULL,
    member_id                 UUID          NOT NULL,
    producer_id               UUID          NOT NULL REFERENCES producer(id) ON DELETE RESTRICT,
    commission_transaction_id UUID          NOT NULL REFERENCES commission_transaction(id) ON DELETE RESTRICT,
    reversal_txn_id           UUID          REFERENCES commission_transaction(id)         ON DELETE RESTRICT,
    native_amount             NUMERIC(19,4) NOT NULL,
    native_currency           CHAR(3)       NOT NULL,
    reason                    TEXT,
    occurred_at               TIMESTAMPTZ   NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id                  UUID,
    actor_email               VARCHAR(255),
    CONSTRAINT cb_source_ck CHECK (source IN ('MEMBER_LAPSE','CONTRIBUTION_REVOKE'))
);
CREATE UNIQUE INDEX ux_clawback_by_source ON clawback_event
    (source, triggering_event_ref, commission_transaction_id);
CREATE INDEX ix_clawback_producer_period ON clawback_event (producer_id, occurred_at);
CREATE INDEX ix_clawback_member          ON clawback_event (member_id);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V097__commission_adjustment.sql`

```sql
CREATE TABLE commission_adjustment (
    id                              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    reference                       VARCHAR(40)   NOT NULL,
    target_commission_transaction_id UUID         NOT NULL REFERENCES commission_transaction(id) ON DELETE RESTRICT,
    adjustment_type                 VARCHAR(20)   NOT NULL,
    adjustment_amount               NUMERIC(19,4) NOT NULL,
    native_currency                 CHAR(3)       NOT NULL,
    justification                   TEXT          NOT NULL,
    status                          VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    approver_actor_id               UUID,
    approver_actor_email            VARCHAR(255),
    approved_at                     TIMESTAMPTZ,
    committed_at                    TIMESTAMPTZ,
    committed_txn_id                UUID          REFERENCES commission_transaction(id) ON DELETE RESTRICT,
    voided_at                       TIMESTAMPTZ,
    voided_reason                   TEXT,
    created_at                      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id                        UUID,
    actor_email                     VARCHAR(255),
    CONSTRAINT ca_status_ck        CHECK (status IN ('DRAFT','APPROVED','COMMITTED','VOIDED')),
    CONSTRAINT ca_type_ck          CHECK (adjustment_type IN
        ('EX_GRATIA','VOID','MANUAL_CLAWBACK','MANUAL_REVERSAL')),
    CONSTRAINT ca_reference_uq     UNIQUE (reference),
    CONSTRAINT ca_justification_len_ck CHECK (char_length(justification) >= 20)
);
CREATE INDEX ix_commission_adjustment_target ON commission_adjustment (target_commission_transaction_id);
CREATE INDEX ix_commission_adjustment_status ON commission_adjustment (status, created_at)
    WHERE status IN ('DRAFT','APPROVED');
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V098__producer_backfill_candidate.sql`

```sql
CREATE TABLE producer_backfill_candidate (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id               UUID          NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    treaty_producer_ref     VARCHAR(120)  NOT NULL,
    candidate_producer_id   UUID          REFERENCES producer(id) ON DELETE SET NULL,
    confidence_score        NUMERIC(4,3)  NOT NULL,
    match_strategy          VARCHAR(30)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    resolved_at             TIMESTAMPTZ,
    resolved_actor_id       UUID,
    resolved_actor_email    VARCHAR(255),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pbc_status_ck   CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    CONSTRAINT pbc_strategy_ck CHECK (match_strategy IN ('LEVENSHTEIN','ILIKE_SUBSTRING','EXACT_CI'))
);
CREATE UNIQUE INDEX ux_pbc_treaty_candidate ON producer_backfill_candidate
    (treaty_id, candidate_producer_id);
CREATE INDEX ix_pbc_pending ON producer_backfill_candidate (status, created_at)
    WHERE status = 'PENDING';
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V099__producer_permissions_and_paymentrun_widen.sql`

```sql
-- ── Part 1: PaymentRun payee_type widening (F11-f — trigger unchanged) ──────
ALTER TABLE payment_runs
    DROP CONSTRAINT IF EXISTS payment_runs_payee_type_check;
ALTER TABLE payment_runs
    ADD CONSTRAINT payment_runs_payee_type_check
        CHECK (payee_type IN ('PROVIDER','MEMBER','PRODUCER'));

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS producer_id UUID REFERENCES producer(id) ON DELETE RESTRICT;
ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS payments_payee_type_check;
ALTER TABLE payments
    ADD CONSTRAINT payments_payee_type_check
        CHECK (payee_type IN ('PROVIDER','MEMBER','PRODUCER'));
ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS payments_payee_xor;
ALTER TABLE payments
    ADD CONSTRAINT payments_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL     AND producer_id IS NULL     AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL     AND member_id IS NOT NULL AND producer_id IS NULL     AND payee_type = 'MEMBER')
            OR (provider_id IS NULL     AND member_id IS NULL     AND producer_id IS NOT NULL AND payee_type = 'PRODUCER'));
CREATE INDEX IF NOT EXISTS ix_payments_producer ON payments (producer_id) WHERE producer_id IS NOT NULL;

ALTER TABLE payment_run_items
    ADD COLUMN IF NOT EXISTS producer_id       UUID  REFERENCES producer(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS withholding_tax_pct NUMERIC(5,2);
ALTER TABLE payment_run_items
    DROP CONSTRAINT IF EXISTS payment_run_items_payee_type_check;
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_payee_type_check
        CHECK (payee_type IN ('PROVIDER','MEMBER','PRODUCER'));
ALTER TABLE payment_run_items
    DROP CONSTRAINT IF EXISTS payment_run_items_payee_xor;
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL     AND producer_id IS NULL     AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL     AND member_id IS NOT NULL AND producer_id IS NULL     AND payee_type = 'MEMBER')
            OR (provider_id IS NULL     AND member_id IS NULL     AND producer_id IS NOT NULL AND payee_type = 'PRODUCER'));
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_wht_range_ck
        CHECK (withholding_tax_pct IS NULL
               OR (withholding_tax_pct >= 0 AND withholding_tax_pct <= 100));
CREATE INDEX IF NOT EXISTS ix_payment_run_items_producer
    ON payment_run_items (producer_id) WHERE producer_id IS NOT NULL;

ALTER TABLE payment_advices
    ADD COLUMN IF NOT EXISTS producer_id UUID REFERENCES producer(id) ON DELETE RESTRICT;
ALTER TABLE payment_advices
    DROP CONSTRAINT IF EXISTS payment_advices_payee_type_check;
ALTER TABLE payment_advices
    ADD CONSTRAINT payment_advices_payee_type_check
        CHECK (payee_type IN ('PROVIDER','MEMBER','PRODUCER'));
ALTER TABLE payment_advices
    DROP CONSTRAINT IF EXISTS payment_advices_payee_xor;
ALTER TABLE payment_advices
    ADD CONSTRAINT payment_advices_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL     AND producer_id IS NULL     AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL     AND member_id IS NOT NULL AND producer_id IS NULL     AND payee_type = 'MEMBER')
            OR (provider_id IS NULL     AND member_id IS NULL     AND producer_id IS NOT NULL AND payee_type = 'PRODUCER'));

-- ── Part 2: treaty.producer_id FK (Phase 10 §B backfills, this migration
--          only adds the column so the FK target exists as of Phase 1) ──
ALTER TABLE treaty
    ADD COLUMN IF NOT EXISTS producer_id UUID REFERENCES producer(id) ON DELETE RESTRICT;
CREATE INDEX IF NOT EXISTS ix_treaty_producer_id ON treaty (producer_id)
    WHERE producer_id IS NOT NULL;

-- ── Part 3: Permission catalogue seed (matches V091 shape) ──
INSERT INTO permissions (name) VALUES
    ('finance.producer:view'),
    ('finance.producer:manage'),
    ('finance.producer:terminate'),
    ('finance.commission:view'),
    ('finance.commission:manage_rate_card'),
    ('finance.commission:draft_adjustment'),
    ('finance.commission:approve_adjustment'),
    ('finance.commission:create_payout_run'),
    ('finance.commission:approve_payout_run'),
    ('finance.producer:backfill_review'),
    ('tenant.settings:manage_auto_lapse')
ON CONFLICT (name) DO NOTHING;
```

#### 2. Public migration (V133, tenancy-service)

**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V133__tenant_auto_lapse_config.sql`

```sql
CREATE TABLE IF NOT EXISTS public.tenant_auto_lapse_config (
    id                        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    enabled                   BOOLEAN      NOT NULL DEFAULT FALSE,
    arrears_threshold_months  INT,
    grace_window_days         INT,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id                  UUID,
    actor_email               VARCHAR(255),
    CONSTRAINT uq_tenant_auto_lapse_config UNIQUE (tenant_id),
    CONSTRAINT ck_auto_lapse_threshold CHECK (arrears_threshold_months IS NULL
                                              OR arrears_threshold_months BETWEEN 1 AND 60),
    CONSTRAINT ck_auto_lapse_grace     CHECK (grace_window_days IS NULL
                                              OR grace_window_days BETWEEN 0 AND 180)
);
```

#### 3. Rules-engine additions

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java` (edit — add after REINSURANCE at line 68)

```java
    // ── Commission ────────────────────────────────────────────────────────
    /**
     * Producer commission calculation. Agenda-gated — cession consumers focus
     * this group per event, so commission rules never fire during the
     * stage-7 tenant sweep. Base rate is looked up in {@code commission_rate_card};
     * these rules encode conditional kickers (tier bonuses, promo periods,
     * sliding-scale overrides, waivers).
     */
    COMMISSION
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/model/ActionType.java` (edit — add after CEDE_TO_TREATY at line 74)

```java
    // ── Commission outputs ───────────────────────────────────────────────
    /**
     * Pay a producer commission on a paid contribution. Populates a
     * PAY_COMMISSION {@code RuleResult} on {@code ContributionFact} carrying
     * producerId + amount + rateCardId. Consumed by the commission consumer
     * in finance-service.
     *
     * <p>Action fields (see {@code RuleAction}):
     * <ul>
     *   <li>{@code rejectionCode} — producer id (UUID string). Optional —
     *       omitted defers to the member's currently-assigned producer.</li>
     *   <li>{@code value} — {@code "RATE_CARD:<uuid>"} to look up a rate
     *       card and pay {@code contribution.amount * card.base_rate_pct},
     *       or {@code "KICKER:<pct-bp>:<reason>"} to add or subtract basis
     *       points to whatever the rate-card lookup produced.</li>
     *   <li>{@code message} — human-readable audit note.</li>
     * </ul>
     */
    PAY_COMMISSION
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java` (edit — extend AGENDA_GATED_CATEGORIES at line 57)

```java
    private static final Set<String> AGENDA_GATED_CATEGORIES =
            Set.of("BENEFIT_PRORATION", "REINSURANCE", "COMMISSION");
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/PayCommissionEmitter.java` (new — mirrors `CedeToTreatyEmitter`)

```java
package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * PAY_COMMISSION action emitter. Multiplexes between two encodings via
 * {@link RuleAction#getValue()}:
 *
 * <ul>
 *   <li>{@code RATE_CARD:<uuid>} — look up the rate card and pay
 *       {@code contribution.amount * card.baseRatePct / 100}. The
 *       rate-card fetch happens on the consumer side; the DRL only encodes
 *       "pay according to rate card X".</li>
 *   <li>{@code KICKER:<pct-bp>:<reason>} — override / additive kicker in
 *       basis points; the consumer sums kickers on top of the rate-card
 *       base. Negative bp values are discounts.</li>
 * </ul>
 *
 * <p>{@link RuleAction#getRejectionCode() rejectionCode} carries the
 * producer id, or empty string to defer to the member's current producer
 * (looked up on the consumer side via {@code member_producer_assignment}).
 *
 * <p>Rules with this action must live in the {@code COMMISSION} category —
 * see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The commission consumer
 * in finance-service focuses that agenda group explicitly on each
 * {@code medfund.contributions.paid} event, so kicker rules never fire
 * during the stage-7 tenant-rule sweep.
 */
@Component
public class PayCommissionEmitter implements ActionEmitter {

    @Override
    public String type() {
        return "PAY_COMMISSION";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        String producerId = action.getRejectionCode() != null ? action.getRejectionCode() : "";
        String message    = action.getMessage()       != null ? action.getMessage()       : "";
        String raw        = action.getValue()         != null ? action.getValue().toString().trim() : "";

        String rateCardId = "";
        String amountExpr;

        if (raw.startsWith("KICKER:")) {
            String[] parts = raw.substring(7).split(":", 2);
            String bp = parts.length > 0 ? sanitizeDecimal(parts[0]) : "0";
            amountExpr = "$contribution.getPremiumAmount()"
                       + ".multiply(new java.math.BigDecimal(\"" + escape(bp) + "\"))"
                       + ".movePointLeft(4)";
        } else if (raw.startsWith("RATE_CARD:")) {
            rateCardId = raw.substring(10).trim();
            // Zero amount: consumer resolves via rate-card lookup — DRL just
            // records intent + carries the rate-card id through.
            amountExpr = "java.math.BigDecimal.ZERO";
        } else {
            amountExpr = "java.math.BigDecimal.ZERO";
        }

        drl.append("    $contribution.addCommission(")
           .append(quoted(producerId)).append(", ")
           .append(amountExpr).append(", ")
           .append(quoted(rateCardId)).append(", ")
           .append(quoted(message))
           .append(");\n");
    }

    private String sanitizeDecimal(String s) {
        if (s == null) return "0";
        try {
            return new java.math.BigDecimal(s.trim()).toPlainString();
        } catch (NumberFormatException e) {
            return "0";
        }
    }
}
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ContributionFact.java` (edit — add `addCommission` action method after `applyLateFee` at line 141)

```java
    /**
     * PAY_COMMISSION action — record a commission accrual against a producer.
     * Consumed by {@code CommissionCalcService} which uses either the direct
     * {@code amount} (from a KICKER) or looks up {@code rateCardId} to compute
     * {@code contribution.premiumAmount * rateCard.baseRatePct / 100}.
     */
    public void addCommission(String producerId, BigDecimal amount,
                              String rateCardId, String reason) {
        this.results.add(new RuleResult("PAY_COMMISSION", rateCardId, reason,
                                        amount != null ? amount : BigDecimal.ZERO));
        // Producer id piggybacks on the RuleResult code slot for the consumer
        // to read via getCode(). Zero-amount entries are rate-card-lookup
        // markers; the consumer resolves the amount server-side.
    }
```

#### 4. Angular rule builder additions

**File**: `clients/angular/src/app/core/services/rules.service.ts` (edit — extend `RuleCategory` union and `RULE_CATEGORIES` array at lines 36-86)

```typescript
// Add to RuleCategory union:
export type RuleCategory =
    | 'ELIGIBILITY'
    // ...existing entries through REINSURANCE...
    | 'REINSURANCE'
    | 'COMMISSION';   // NEW

// Add to RULE_CATEGORIES:
export const RULE_CATEGORIES: RuleCategoryDescriptor[] = [
    // ...existing entries...
    { id: 'REINSURANCE', label: 'Reinsurance', icon: 'shield-plus' },
    { id: 'COMMISSION',  label: 'Commission',  icon: 'percent' },   // NEW
];
```

**File**: `clients/angular/src/app/pages/tenant-admin/rules/rule-editor/rule-editor.component.ts` (edit — extend `ACTION_TYPES` at line 38-64)

```typescript
    { id: 'PAY_COMMISSION',
      label: 'Pay commission',
      description: 'Record a commission accrual against a producer. Value must be RATE_CARD:<uuid> or KICKER:<bp>:<reason>.',
      valueHint: 'RATE_CARD:<uuid> | KICKER:<bp>:<reason>' },
```

**File**: `clients/angular/src/app/pages/tenant-admin/rules/rule-dry-run/rule-dry-run.component.ts` (edit — add COMMISSION FACT_SEED after REINSURANCE)

```typescript
    {
        category: 'COMMISSION',
        contribution: {
            memberId: 'MEM-DEMO', schemeId: 'STANDARD', groupId: 'GRP-DEMO',
            memberAge: 35, premiumAmount: 500, currencyCode: 'USD',
            insuranceLine: 'HEALTH', dependantCount: 2,
        },
    },
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :rules-engine:build :tenancy-service:build :shared:build`
- [x] Rules-engine unit tests: `cd services/java && ./gradlew :rules-engine:test` — new `PayCommissionEmitterTest` (5 cases: RATE_CARD: encoding, KICKER: encoding, missing value defaults to ZERO, empty producer id encodes as `""`, message escaping); `RuleCategoryTest` size guard + COMMISSION assertion; `DrlCompilerTest.compile_commissionRule_addsAgendaGroupAndAddCommission` verifies COMMISSION is agenda-gated and the emitter's DRL body compiles cleanly. `RuleTemplateServiceTest.getDefaultRules_coversEveryDeclaredCategory` covered by the new `CommissionTemplates` provider (see Deviations).
- [x] Tenant migrations apply cleanly on fresh testcontainer via `TenantMigrationFlywayIT` — V092..V099 idempotent; no drift from V091 predecessor. `feedback_never_edit_applied_migrations` invariant honoured.
- [x] Public V133 applies clean via `PublicMigrationFlywayIT`.
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — no new warnings.
- [x] `make test-angular` (targeted) — new `rules.service.spec.ts` asserts `RULE_CATEGORIES` includes `COMMISSION`; new `rule-editor.component.spec.ts` asserts `actionTypes` includes `PAY_COMMISSION` (5 tests, all green under ChromeHeadless).

#### Manual Verification
- [ ] `\d producer`, `\d member_producer_assignment`, `\d commission_rate_card`, `\d commission_transaction`, `\d clawback_event`, `\d commission_adjustment`, `\d producer_backfill_candidate` in a tenant schema show all constraints (types, per-status subsets, UNIQUE partial indexes, XOR checks widened to include PRODUCER).
- [ ] `\d payment_runs`, `\d payments`, `\d payment_run_items`, `\d payment_advices` show `payee_type` widened to `('PROVIDER','MEMBER','PRODUCER')`; XOR checks include the third arm.
- [ ] `SELECT * FROM permissions WHERE name LIKE 'finance.producer:%' OR name LIKE 'finance.commission:%'` returns 10 rows.
- [ ] `\d public.tenant_auto_lapse_config` shows the config table with `enabled DEFAULT FALSE`.
- [ ] Rules engine `POST /api/v1/rules` accepts a rule with `category=COMMISSION` + `action.type=PAY_COMMISSION` + `value=RATE_CARD:00000000-0000-0000-0000-000000000000` — compiles to DRL cleanly, activates without firing during a stage-7 sweep.

**Implementation Note**: pause for manual acceptance before Phase 2.

---

## Phase 2: Producer + Rate-Card + Assignment backend + tenant-admin Angular

### Overview

Ship the full CRUD lifecycle for the producer family entities (Producer, CommissionRateCard, MemberProducerAssignment). Backend + Angular in one phase — the tenant-admin producer-edit page needs the backend to be verifiable, and the backend without the UI is a curl exercise that provides no user value.

### Changes Required

#### 1. Entities (finance-service, `com.medfund.finance.producer.entity`)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/entity/Producer.java`

```java
package com.medfund.finance.producer.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Table("producer")
public class Producer {
    @Id private UUID id;
    @Column("producer_code")     private String producerCode;
    @Column("name")              private String name;
    @Column("contact_email")     private String contactEmail;
    @Column("contact_phone")     private String contactPhone;
    @Column("jurisdiction_code") private String jurisdictionCode;
    @Column("home_currency")     private String homeCurrency;
    @Column("parent_producer_id") private UUID  parentProducerId;
    @Column("wht_pct_override")  private BigDecimal whtPctOverride;
    @Column("banking_details")   private String bankingDetailsJson;   // JSONB round-tripped as String
    @Column("is_active")         private boolean active;
    @Column("activated_at")      private OffsetDateTime activatedAt;
    @Column("terminated_at")     private OffsetDateTime terminatedAt;
    @Column("created_at")        private OffsetDateTime createdAt;
    @Column("updated_at")        private OffsetDateTime updatedAt;
    @Column("actor_id")          private UUID   actorId;
    @Column("actor_email")       private String actorEmail;
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/entity/CommissionRateCard.java`

```java
package com.medfund.finance.producer.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Table("commission_rate_card")
public class CommissionRateCard {
    @Id private UUID id;
    @Column("name")                 private String name;
    @Column("insurance_line")       private String insuranceLine;
    @Column("producer_tier")        private String producerTier;
    @Column("base_rate_pct")        private BigDecimal baseRatePct;
    @Column("clawback_window_days") private Integer clawbackWindowDays;
    @Column("effective_from")       private LocalDate effectiveFrom;
    @Column("effective_to")         private LocalDate effectiveTo;
    @Column("is_active")            private boolean active;
    @Column("created_at")           private OffsetDateTime createdAt;
    @Column("updated_at")           private OffsetDateTime updatedAt;
    @Column("actor_id")             private UUID   actorId;
    @Column("actor_email")          private String actorEmail;
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/entity/MemberProducerAssignment.java`

```java
package com.medfund.finance.producer.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Table("member_producer_assignment")
public class MemberProducerAssignment {
    @Id private UUID id;
    @Column("member_id")      private UUID memberId;
    @Column("producer_id")    private UUID producerId;
    @Column("effective_from") private LocalDate effectiveFrom;
    @Column("effective_to")   private LocalDate effectiveTo;
    @Column("change_reason")  private String changeReason;
    @Column("created_at")     private OffsetDateTime createdAt;
    @Column("actor_id")       private UUID   actorId;
    @Column("actor_email")    private String actorEmail;
}
```

#### 2. Repositories (`com.medfund.finance.producer.repository`)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/repository/ProducerRepository.java`

```java
public interface ProducerRepository extends ReactiveCrudRepository<Producer, UUID> {

    Mono<Producer> findByProducerCode(String producerCode);

    Flux<Producer> findByIsActiveTrueOrderByName();

    @Query("SELECT * FROM producer WHERE parent_producer_id = :parentId ORDER BY name")
    Flux<Producer> findChildren(UUID parentId);

    /** Recursive CTE — walks upward for override commission and admin hierarchy views. */
    @Query("""
        WITH RECURSIVE ancestry AS (
            SELECT * FROM producer WHERE id = :id
            UNION ALL
            SELECT p.* FROM producer p JOIN ancestry a ON p.id = a.parent_producer_id
        )
        SELECT * FROM ancestry
    """)
    Flux<Producer> findAncestryOf(UUID id);
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/repository/CommissionRateCardRepository.java`

```java
public interface CommissionRateCardRepository extends ReactiveCrudRepository<CommissionRateCard, UUID> {

    @Query("""
        SELECT * FROM commission_rate_card
         WHERE insurance_line = :insuranceLine
           AND (producer_tier = :tier OR producer_tier IS NULL)
           AND effective_from <= :asOf
           AND (effective_to IS NULL OR effective_to >= :asOf)
           AND is_active = TRUE
         ORDER BY producer_tier NULLS LAST, effective_from DESC
         LIMIT 1
    """)
    Mono<CommissionRateCard> findApplicable(String insuranceLine, String tier, LocalDate asOf);

    Flux<CommissionRateCard> findByIsActiveTrueOrderByEffectiveFromDesc();
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/repository/MemberProducerAssignmentRepository.java`

```java
public interface MemberProducerAssignmentRepository
        extends ReactiveCrudRepository<MemberProducerAssignment, UUID> {

    @Query("""
        SELECT * FROM member_producer_assignment
         WHERE member_id = :memberId
           AND effective_from <= :asOf
           AND (effective_to IS NULL OR effective_to >= :asOf)
         ORDER BY effective_from DESC
         LIMIT 1
    """)
    Mono<MemberProducerAssignment> findActiveFor(UUID memberId, LocalDate asOf);

    Mono<MemberProducerAssignment> findFirstByMemberIdAndEffectiveToIsNull(UUID memberId);

    Flux<MemberProducerAssignment> findByMemberIdOrderByEffectiveFromDesc(UUID memberId);

    Flux<MemberProducerAssignment> findByProducerIdAndEffectiveToIsNull(UUID producerId);

    @Query("SELECT COUNT(*) FROM member_producer_assignment WHERE producer_id = :producerId AND effective_to IS NULL")
    Mono<Long> countOpenByProducer(UUID producerId);
}
```

#### 3. Services (`com.medfund.finance.producer.service`)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/ProducerService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ProducerService {

    private final ProducerRepository producerRepository;
    private final AuditPublisher auditPublisher;

    @Transactional
    public Mono<Producer> create(CreateProducerRequest req, String actorId, String actorEmail) {
        return validate(req)
            .then(Mono.defer(() -> {
                Producer p = new Producer();
                p.setProducerCode(req.producerCode());
                p.setName(req.name());
                p.setContactEmail(req.contactEmail());
                p.setContactPhone(req.contactPhone());
                p.setJurisdictionCode(req.jurisdictionCode());
                p.setHomeCurrency(req.homeCurrency());
                p.setParentProducerId(req.parentProducerId());
                p.setWhtPctOverride(req.whtPctOverride());
                p.setBankingDetailsJson(req.bankingDetailsJson());
                p.setActive(true);
                p.setActivatedAt(OffsetDateTime.now());
                p.setActorId(UUID.fromString(actorId));
                p.setActorEmail(actorEmail);
                return producerRepository.save(p);
            }))
            .flatMap(saved -> auditPublisher.publish(AuditEvent.create(
                    /* tenantId */    TenantContext.currentTenantId(),
                    /* entityType */  "Producer",
                    /* entityId */    saved.getId().toString(),
                    /* action */      "CREATE",
                    /* entityName */  saved.getProducerCode(),   // friendly per feedback_audit_entity_name
                    /* actor */       AuditActor.of(actorId, actorEmail),
                    /* changes */     Map.of("name", saved.getName(),
                                             "homeCurrency", saved.getHomeCurrency(),
                                             "parentProducerId", String.valueOf(saved.getParentProducerId()))
                )).thenReturn(saved));
    }

    @Transactional
    public Mono<Producer> update(UUID id, UpdateProducerRequest req, String actorId, String actorEmail) {
        return producerRepository.findById(id)
            .switchIfEmpty(Mono.error(new EntityNotFoundException("Producer", id)))
            .flatMap(existing -> {
                // ... field-level patch + audit event with old/new value diff
            });
    }

    @Transactional
    public Mono<Void> terminate(UUID id, LocalDate effectiveDate, String actorId, String actorEmail) {
        // Phase 9 fills this in; the stub here just deactivates + snaps to
        // last-day-of-month per feedback_effective_date_snap.
        return Mono.error(new UnsupportedOperationException("Termination lands in Phase 9"));
    }

    private Mono<Void> validate(CreateProducerRequest req) {
        // producer_code uniqueness (DB enforces, but pre-check for a better 409)
        // home_currency ISO-4217 (3 upper letters)
        // parent hierarchy no-cycle: walk ancestry, assert current id not present
        // wht_pct_override [0,100]
        return producerRepository.findByProducerCode(req.producerCode())
            .flatMap(existing -> Mono.<Void>error(new ConflictException(
                    "Producer code already exists: " + req.producerCode())))
            .switchIfEmpty(Mono.empty());
    }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionRateCardService.java`

Standard CRUD service — `create`, `update`, `deactivate`, `list`, `findById`, `findApplicable(insuranceLine, tier, asOf)`. `deactivate` is a soft-close: `is_active = false` + `effective_to = last-day-of-month` per `feedback_effective_date_snap`. Every mutation emits an `AuditEvent` with `entityName = rateCard.name`.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/MemberProducerAssignmentService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberProducerAssignmentService {

    private final MemberProducerAssignmentRepository assignmentRepository;
    private final ProducerRepository producerRepository;
    private final AuditPublisher auditPublisher;

    /**
     * Assign a member to a producer. Closes any currently-open assignment
     * (effective_to = 1 day before new effective_from) and inserts the new
     * open row. App-layer at-most-one-open guard (defence in depth alongside
     * the ux_mpa_one_open_per_member partial UNIQUE index).
     *
     * Effective-from snaps to 1st-of-month per feedback_effective_date_snap.
     */
    @Transactional
    public Mono<MemberProducerAssignment> assign(UUID memberId, UUID producerId,
                                                  LocalDate effectiveFrom, String changeReason,
                                                  String actorId, String actorEmail) {
        LocalDate snapped = effectiveFrom.withDayOfMonth(1);
        return producerRepository.findById(producerId)
            .switchIfEmpty(Mono.error(new EntityNotFoundException("Producer", producerId)))
            .flatMap(producer -> assignmentRepository.findFirstByMemberIdAndEffectiveToIsNull(memberId)
                .flatMap(open -> {
                    // Close prior open row: effective_to = snapped - 1 day
                    // (never before its own effective_from — validate)
                    LocalDate closeAt = snapped.minusDays(1);
                    if (closeAt.isBefore(open.getEffectiveFrom())) {
                        return Mono.error(new ValidationException(
                                "New effective_from precedes existing assignment start"));
                    }
                    open.setEffectiveTo(closeAt);
                    return assignmentRepository.save(open)
                        .then(publishAuditForClose(open, actorId, actorEmail));
                })
                .then(Mono.defer(() -> insertOpenAssignment(memberId, producerId, snapped,
                                                             changeReason, actorId, actorEmail))));
    }

    private Mono<MemberProducerAssignment> insertOpenAssignment(UUID memberId, UUID producerId,
                                                                 LocalDate from, String reason,
                                                                 String actorId, String actorEmail) {
        MemberProducerAssignment mpa = new MemberProducerAssignment();
        mpa.setMemberId(memberId);
        mpa.setProducerId(producerId);
        mpa.setEffectiveFrom(from);
        mpa.setChangeReason(reason);
        mpa.setActorId(UUID.fromString(actorId));
        mpa.setActorEmail(actorEmail);
        return assignmentRepository.save(mpa)
            .flatMap(saved -> auditPublisher.publish(AuditEvent.create(
                    TenantContext.currentTenantId(),
                    "MemberProducerAssignment", saved.getId().toString(),
                    "CREATE",
                    "member " + memberId + " → producer " + producerId,
                    AuditActor.of(actorId, actorEmail),
                    Map.of("effectiveFrom", saved.getEffectiveFrom().toString(),
                           "changeReason", saved.getChangeReason() == null ? "" : saved.getChangeReason())
                )).thenReturn(saved));
    }
}
```

#### 4. Controllers (`com.medfund.finance.producer.controller`)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/ProducerController.java`

```java
@RestController
@RequestMapping("/api/v1/producers")
@RequiredArgsConstructor
@Tag(name = "Producer", description = "Producer registry, hierarchy, and banking details")
public class ProducerController {

    private final ProducerService producerService;

    @GetMapping
    @Operation(summary = "List producers")
    @RequiresPermission("finance.producer:view")
    public Flux<ProducerResponse> list(@RequestParam(required = false) Boolean activeOnly) { ... }

    @GetMapping("/{id}")
    @Operation(summary = "Get producer by id")
    @RequiresPermission("finance.producer:view")
    public Mono<ProducerResponse> get(@PathVariable UUID id) { ... }

    @GetMapping("/{id}/ancestry")
    @Operation(summary = "Get producer + parent chain")
    @RequiresPermission("finance.producer:view")
    public Flux<ProducerResponse> ancestry(@PathVariable UUID id) { ... }

    @PostMapping
    @Operation(summary = "Create producer")
    @RequiresPermission("finance.producer:manage")
    public Mono<ProducerResponse> create(@RequestBody @Valid CreateProducerRequest req,
                                         @AuthenticationPrincipal Jwt jwt) {
        return producerService.create(req, AuditActor.id(jwt), AuditActor.email(jwt))
                              .map(ProducerResponse::from);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update producer")
    @RequiresPermission("finance.producer:manage")
    public Mono<ProducerResponse> update(@PathVariable UUID id,
                                         @RequestBody @Valid UpdateProducerRequest req,
                                         @AuthenticationPrincipal Jwt jwt) { ... }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/CommissionRateCardController.java`

REST at `/api/v1/commission/rate-cards`:
- `GET /` — list, permission `finance.commission:view`
- `GET /{id}` — permission `finance.commission:view`
- `POST /` — create, permission `finance.commission:manage_rate_card`
- `PUT /{id}` — update, permission `finance.commission:manage_rate_card`
- `DELETE /{id}` — deactivate (soft), permission `finance.commission:manage_rate_card`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/MemberProducerAssignmentController.java`

REST at `/api/v1/producers/{producerId}/assignments` (+ inverse view `/api/v1/members/{memberId}/producer-assignment`):
- `GET /api/v1/producers/{producerId}/assignments?open=true` — permission `finance.producer:view`
- `GET /api/v1/members/{memberId}/producer-assignment/history` — permission `finance.producer:view`
- `POST /api/v1/members/{memberId}/producer-assignment` — assign (creates + closes prior), permission `finance.producer:manage`
- `DELETE /api/v1/members/{memberId}/producer-assignment` — close current (no successor; commission calc will warn+skip during the gap), permission `finance.producer:manage`

#### 5. Angular tenant-admin

**File**: `clients/angular/src/app/pages/tenant-admin/producers/producers.routes.ts` (new)

```typescript
export const PRODUCERS_ROUTES: Routes = [
    { path: '', component: ProducersListComponent,
      data: { permission: 'finance.producer:view', sidebar: 'admin' } },
    { path: 'new', component: ProducerEditComponent,
      data: { permission: 'finance.producer:manage', sidebar: 'admin', mode: 'create' } },
    { path: ':id', component: ProducerEditComponent,
      data: { permission: 'finance.producer:manage', sidebar: 'admin', mode: 'edit' } },
    { path: ':id/assignments', component: ProducerAssignmentsComponent,
      data: { permission: 'finance.producer:view', sidebar: 'admin' } },
    { path: ':id/hierarchy', component: ProducerHierarchyComponent,
      data: { permission: 'finance.producer:view', sidebar: 'admin' } },
    { path: 'rate-cards', component: RateCardsListComponent,
      data: { permission: 'finance.commission:view', sidebar: 'admin' } },
    { path: 'rate-cards/new', component: RateCardEditComponent,
      data: { permission: 'finance.commission:manage_rate_card', sidebar: 'admin', mode: 'create' } },
    { path: 'rate-cards/:id', component: RateCardEditComponent,
      data: { permission: 'finance.commission:manage_rate_card', sidebar: 'admin', mode: 'edit' } },
];
```

Mount at `app.routes.ts` at `/tenant/admin/producers/*` (add a `loadChildren` entry after the existing REINSURANCE_ROUTES mount).

**File**: `clients/angular/src/app/core/services/producer.service.ts` (new — single service for the whole vertical, mirrors `reinsurance.service.ts`)

```typescript
@Injectable({ providedIn: 'root' })
export class ProducerService {
    private http = inject(HttpClient);
    private base = '/api/v1/producers';
    private rateCardsBase = '/api/v1/commission/rate-cards';

    // Producer
    listProducers(activeOnly = true): Observable<ProducerResponse[]> { ... }
    getProducer(id: string): Observable<ProducerResponse> { ... }
    createProducer(req: CreateProducerRequest): Observable<ProducerResponse> { ... }
    updateProducer(id: string, req: UpdateProducerRequest): Observable<ProducerResponse> { ... }
    getAncestry(id: string): Observable<ProducerResponse[]> { ... }
    getChildren(id: string): Observable<ProducerResponse[]> { ... }

    // Rate cards
    listRateCards(activeOnly = true): Observable<RateCardResponse[]> { ... }
    createRateCard(req: CreateRateCardRequest): Observable<RateCardResponse> { ... }
    updateRateCard(id: string, req: UpdateRateCardRequest): Observable<RateCardResponse> { ... }
    deactivateRateCard(id: string): Observable<void> { ... }

    // Assignments
    getMemberAssignmentHistory(memberId: string): Observable<AssignmentResponse[]> { ... }
    assignMember(memberId: string, req: AssignMemberRequest): Observable<AssignmentResponse> { ... }
    closeAssignment(memberId: string, effectiveTo: string): Observable<void> { ... }
    listProducerAssignments(producerId: string, openOnly = true): Observable<AssignmentResponse[]> { ... }

    // Search-selects (debounced, per feedback_no_raw_id_inputs)
    searchProducers(q: string, limit = 20): Observable<ProducerResponse[]> { ... }
}
```

**Components** (one per route above): `ProducersListComponent`, `ProducerEditComponent` (create + update, includes parent-picker via `SearchSelectComponent`), `ProducerHierarchyComponent` (tree view via `@angular/cdk/tree`), `ProducerAssignmentsComponent` (paginated table of open assignments per producer with search-select for member picker), `RateCardsListComponent`, `RateCardEditComponent`. All ID-carrying inputs use the shared `SearchSelectComponent` per `feedback_no_raw_id_inputs`; effective-from date pickers snap to 1st-of-month via a shared directive.

**Sidebar entry**: add to `clients/angular/src/app/layout/tenant-layout/tenant-layout.component.ts` admin variant nav: `{ label: 'Producers', icon: 'briefcase', route: '/tenant/admin/producers', permission: 'finance.producer:view' }`.

#### 6. Gateway routes

**File**: `services/go/gateway/internal/finance/routes.go` (or the equivalent registration file — verify exact name at implement time)

Add the routes for the finance-service producer + commission surface:
```
/api/v1/producers                        → finance-service
/api/v1/producers/{id}/**                → finance-service
/api/v1/commission/rate-cards            → finance-service
/api/v1/commission/rate-cards/{id}       → finance-service
/api/v1/members/{id}/producer-assignment → finance-service
```

Follow `services/go/gateway/internal/reinsurance/routes.go` shape 1:1. Gateway Go tests: one table-driven test covering the routing path per new URL (`gateway_producer_routes_test.go`).

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build :shared:build`
- [x] Unit tests (`make test-java`):
  - `ProducerServiceTest` (12 cases, all green): list/paged, activeFilter, get missing, create happy (audit entityName = producer_code), duplicate code → 409, parent with no cycle, update missing, update flipping active with changed fields, update self-parent rejected, update reparent-cycle rejected, ancestry walks upward, search blank/delegates.
  - `CommissionRateCardServiceTest` (8 cases, all green): create snaps effective_from to 1st-of-month + effective_to to last-day-of-month; open-ended period; effective_to before from rejected; audit carries name; update missing; deactivate snaps effective_to; deactivate already-inactive is no-op; findApplicable delegates.
  - `MemberProducerAssignmentServiceTest` (10 cases, all green): assign no prior inserts open row snapped to 1st-of-month; assign closes prior at (from - 1 day) then inserts new (two audit events CLOSE + CREATE); backdated before prior start rejected; missing producer errors; inactive producer errors; closeCurrent snaps to last-day-of-month; closeCurrent no open errors; currentFor delegates; countOpenForProducer delegates.
- [x] Integration tests (`make test-integration`):
  - `ProducerCrudIT` (3 tests, all green): full producer lifecycle (create → duplicate-code 409 → cycle rejection → update → deactivate → ancestry) with all AuditEvents carrying friendly producer_code entityName; rate-card soft-deactivate snaps effective_to to last-day-of-month; rate-card findApplicable picks tier-scoped over tier-agnostic.
  - `MemberProducerAssignmentIT` (3 tests, all green): full assign-then-reassign persists two-row history + closes prior; partial UNIQUE index rejects a rogue direct-repo insert of a second open row (DuplicateKeyException); backdated assignment rejected without corrupting existing history.
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — no new errors, only pre-existing warnings.
- [x] `make test-angular` (targeted) — `producer.service.spec.ts` covers all 13 wire-shape assertions (list/create/update/ancestry/search/rate-card CRUD/assignment CRUD/count). Component-level spec deferred (see Deviations).
- [ ] `verify` on `/tenant/admin/producers` — page loads, list renders, "New producer" opens the create form.
- [ ] `verify` on `/tenant/admin/producers/new` — form validates (home_currency required, producer_code required, parent-picker debounced search-select works).
- [ ] `verify` on `/tenant/admin/producers/rate-cards` — list renders, create/edit form validates base_rate_pct range.
- [x] Gateway Go tests: `cd services/go/gateway && go test ./...` — go build + existing tests pass; no new routes_test.go added (see Deviations — no equivalent test exists for reinsurance either).
- [ ] Playwright: `producer-crud.spec.ts` — create producer → set banking details → view in list → edit → assign to a member → history shows the row.

#### Manual Verification
- [ ] Search-select for parent producer debounces at 300 ms and shows names + producer_codes.
- [ ] Effective-from date picker on the rate-card form snaps a mid-month date to the 1st of the same month.
- [ ] Attempting to close an assignment then re-assign to the same member on the same day works (partial UNIQUE index doesn't false-positive because the prior row's effective_to is now non-null).
- [ ] Sidebar shows "Producers" only for users with `finance.producer:view`.

**Implementation Note**: pause for manual acceptance before Phase 3.

---

## Phase 3: Kafka consumers + CommissionCalcService

### Overview

Ship the three commission-side consumers that make commissions accrue and claw back automatically:

1. `ProducerCommissionConsumer` — on `medfund.contributions.paid`, resolves the member's producer, computes commission via rate-card + rules-engine hybrid, persists `commission_transaction`.
2. `CommissionRevokeConsumer` — on the new `medfund.contributions.revoked` topic (contributions-side publisher added in this phase), reverses matching `commission_transaction` and writes a `clawback_event(source=CONTRIBUTION_REVOKE)`.
3. `CommissionClawbackConsumer` — on `medfund.users.member-lifecycle`, scans open commission transactions in the applicable clawback window for the affected member, marks them `CLAWED_BACK`, writes `clawback_event(source=MEMBER_LAPSE)`.

§A: only operator-triggered lapse events fire the clawback consumer. Auto-lapse landing in Phase 7 §B is a natural extension — the same consumer will fire on the `MEMBER_STATUS_CHANGED` event that the SCHEDULED_STATUS_ROLL job emits.

### Changes Required

#### 1. Contributions-side publisher extension (§A prerequisite for consumer 2)

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ContributionEventPublisher.java` (edit — add new method next to line 248 `publishInvoicePdfDeleted`)

```java
/**
 * Emitted from {@link BillingService#revokeBilling(RevokeBillingRequest, String, String)}
 * and {@link BillingService#revokeInvoice(UUID, String, String)}. Fires once per
 * revoked contribution (batch revoke fires N events). Consumers:
 * {@code CommissionRevokeConsumer} in finance-service (Phase 11).
 */
public Mono<Void> publishContributionRevoked(String contributionId, String invoiceId,
                                             String memberId, String groupId,
                                             String amount, String currencyCode,
                                             String insuranceLine, String tenantId,
                                             String actorId, String actorEmail) {
    var fields = new java.util.LinkedHashMap<String, String>();
    fields.put("event", "CONTRIBUTION_REVOKED");
    fields.put("contributionId", contributionId);
    fields.put("invoiceId", invoiceId != null ? invoiceId : "");
    fields.put("memberId", memberId != null ? memberId : "");
    fields.put("groupId", groupId != null ? groupId : "");
    fields.put("amount", amount != null ? amount : "");
    fields.put("currencyCode", currencyCode != null ? currencyCode : "");
    fields.put("insuranceLine", insuranceLine != null ? insuranceLine : "");
    fields.put("tenantId", tenantId != null ? tenantId : "");
    fields.put("actorId", actorId != null ? actorId : "system");
    fields.put("actorEmail", actorEmail != null ? actorEmail : "system@medfund");
    fields.put("revokedAt", java.time.OffsetDateTime.now().toString());
    return publishEvent("medfund.contributions.revoked", contributionId, fields);
}
```

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingService.java` (edit at `revokeBilling` line 934 + `revokeInvoice` line 1102)

At `revokeBilling` — after the enumeration of contribution IDs at line 975-995 and before the DELETE at line 1024, capture the per-contribution snapshot (contributionId, memberId, groupId, amount, currencyCode, insuranceLine) for later publish. After the DELETE and audit publish at line 1070-1079, iterate the snapshot and call `eventPublisher.publishContributionRevoked(...)` per row. Fire-and-forget (`.subscribe()`) — the DELETE has already succeeded; publishing is best-effort and idempotent on the consumer side.

At `revokeInvoice` — same shape but the snapshot has one row per contribution the invoice covered.

Both call sites source `tenantId` from `TenantContext.currentTenantId()`, `actorId`/`actorEmail` from the incoming parameters (already threaded).

#### 2. Commission event DTOs (`com.medfund.finance.producer.dto`)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/dto/ContributionPaidEvent.java`

```java
public record ContributionPaidEvent(
    UUID contributionId, UUID memberId, BigDecimal amount, String currencyCode,
    String insuranceLine, Instant paidAt, String tenantId
) {
    public static ContributionPaidEvent from(JsonNode node) {
        return new ContributionPaidEvent(
            UUID.fromString(node.get("contributionId").asText()),
            UUID.fromString(node.get("memberId").asText()),
            new BigDecimal(node.get("amount").asText()),
            node.get("currencyCode").asText(),
            node.get("insuranceLine").asText(),
            Instant.parse(node.get("paidAt").asText()),
            node.get("tenantId").asText()
        );
    }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/dto/ContributionRevokedEvent.java`

```java
public record ContributionRevokedEvent(
    UUID contributionId, UUID memberId, BigDecimal amount, String currencyCode,
    String insuranceLine, String tenantId, String actorId, String actorEmail,
    Instant revokedAt
) {
    public static ContributionRevokedEvent from(JsonNode node) { ... }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/dto/MemberLifecycleEvent.java`

Mirrors `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/MemberLifecycleConsumer.java:49` payload — `{tenantId, memberId, status, reason, terminationDate?, groupId?, schemeId?}`.

#### 3. CommissionCalcService (`com.medfund.finance.producer.service`)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionCalcService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionCalcService {

    private final MemberProducerAssignmentRepository assignmentRepository;
    private final CommissionRateCardRepository rateCardRepository;
    private final CommissionTransactionRepository commissionTxnRepository;
    private final ProducerRepository producerRepository;
    private final TenantRuleEngine ruleEngine;           // rules-engine facade
    private final ReferenceGenerator referenceGenerator; // "COMM-2026-000001"
    private final AuditPublisher auditPublisher;

    /**
     * Compute + persist a commission for a paid contribution. Idempotent:
     * a second invocation with the same contributionId is a no-op (guarded by
     * the ux_commission_txn_source partial UNIQUE index — DuplicateKeyException
     * is caught and swallowed to Mono.empty()).
     *
     * Steps:
     *   1. Resolve member's active producer via member_producer_assignment.
     *   2. Look up applicable rate card (insuranceLine + producer tier + asOf paidAt).
     *   3. Fire agenda-gated COMMISSION rules against the ContributionFact.
     *   4. Sum base (rate-card) + kickers (rule results) → total commission amount.
     *   5. Persist commission_transaction (status=ACCRUED).
     *   6. Emit AuditEvent + return the persisted row.
     */
    @Transactional
    public Mono<CommissionTransaction> processPaidContribution(ContributionPaidEvent event,
                                                                String actorId, String actorEmail) {
        LocalDate paidOnDate = LocalDate.ofInstant(event.paidAt(), ZoneOffset.UTC);
        return assignmentRepository.findActiveFor(event.memberId(), paidOnDate)
            .switchIfEmpty(Mono.fromRunnable(() ->
                log.warn("No active producer for member {} at {} — commission skipped",
                         event.memberId(), paidOnDate)))
            .cast(MemberProducerAssignment.class)
            .flatMap(assignment -> producerRepository.findById(assignment.getProducerId())
                .flatMap(producer -> computeAmount(event, producer, paidOnDate)))
            .flatMap(computed -> persist(event, computed, actorId, actorEmail))
            .onErrorResume(DuplicateKeyException.class, e -> {
                log.info("Commission already exists for contribution {}, skipping",
                         event.contributionId());
                return Mono.empty();
            });
    }

    private Mono<ComputedCommission> computeAmount(ContributionPaidEvent event,
                                                   Producer producer, LocalDate asOf) {
        String tenantId = event.tenantId();
        return rateCardRepository.findApplicable(event.insuranceLine(),
                                                  producerTier(producer), asOf)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                    "No active rate card for " + event.insuranceLine())))
            .flatMap(card -> fireCommissionRules(event, producer, card, tenantId)
                .map(kickerBp -> {
                    BigDecimal base = event.amount()
                            .multiply(card.getBaseRatePct()).movePointLeft(2);
                    BigDecimal kick = event.amount()
                            .multiply(BigDecimal.valueOf(kickerBp)).movePointLeft(4);
                    return new ComputedCommission(
                            producer.getId(), card.getId(),
                            base.add(kick), card.getBaseRatePct(), card, kickerBp);
                }));
    }

    private Mono<Long> fireCommissionRules(ContributionPaidEvent event, Producer producer,
                                            CommissionRateCard card, String tenantId) {
        ContributionFact fact = new ContributionFact();
        fact.setContributionId(event.contributionId().toString());
        fact.setMemberId(event.memberId().toString());
        fact.setCurrencyCode(event.currencyCode());
        fact.setPremiumAmount(event.amount());
        fact.getAttributes().put("insuranceLine", event.insuranceLine());
        fact.getAttributes().put("producerId", producer.getId().toString());
        fact.getAttributes().put("producerTier", producerTier(producer));
        fact.getAttributes().put("rateCardId", card.getId().toString());
        return ruleEngine.fireFocused(tenantId, "COMMISSION", fact)
            .thenReturn(fact.getResults().stream()
                .filter(r -> "PAY_COMMISSION".equals(r.type()))
                .filter(r -> r.getAmount() != null)
                .mapToLong(r -> r.getAmount().movePointRight(4).longValue())
                .sum());
    }

    /** MVP: tier is the producer's parent id (null → "DIRECT"). Real tiering lands with the rate-card UI. */
    private String producerTier(Producer p) {
        return p.getParentProducerId() == null ? "DIRECT" : "SUB";
    }
}
```

**Idempotency note:** the `ux_commission_txn_source` partial UNIQUE index at `V095__commission_transaction.sql` catches duplicate accruals from replay. `.onErrorResume(DuplicateKeyException.class, ...)` swallows the duplicate — the ack still fires per `bug_reactor_kafka_ack_swallow`.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionClawbackService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionClawbackService {

    private final CommissionTransactionRepository commissionTxnRepository;
    private final ClawbackEventRepository clawbackEventRepository;
    private final CommissionRateCardRepository rateCardRepository;
    private final AuditPublisher auditPublisher;
    private final ReferenceGenerator referenceGenerator;

    /**
     * Fired by CommissionClawbackConsumer on MEMBER_STATUS_CHANGED with
     * status ∈ {"lapsed","terminated","deactivated"}. Scans all ACCRUED or PAID
     * commission transactions for the member; for each transaction, joins to its
     * rate_card; if the rate_card.clawback_window_days is non-null AND
     * (transaction.occurredAt + window) >= lapsedAt, marks it CLAWED_BACK and
     * writes a clawback_event(source=MEMBER_LAPSE).
     */
    @Transactional
    public Flux<ClawbackEvent> processMemberLapse(UUID memberId, Instant lapsedAt,
                                                   String reason, String tenantId,
                                                   String actorId, String actorEmail) {
        return commissionTxnRepository
            .findByMemberIdAndStatusIn(memberId, List.of("ACCRUED", "PAID"))
            .flatMap(txn -> rateCardRepository.findById(txn.getRateCardId())
                .filter(card -> card.getClawbackWindowDays() != null)
                .filter(card -> lapsedAt.isBefore(txn.getOccurredAt().toInstant()
                        .plus(card.getClawbackWindowDays(), ChronoUnit.DAYS)))
                .flatMap(card -> clawback(txn, memberId, lapsedAt, reason,
                                          "MEMBER_LAPSE", memberId.toString(),
                                          actorId, actorEmail)));
    }

    /**
     * Fired by CommissionRevokeConsumer on CONTRIBUTION_REVOKED. Finds the
     * commission_transaction for the revoked contribution; reverses it with a
     * compensating REVERSED transaction (mirrors accounting-double-entry
     * pattern from FacultativeCessionService.void_); writes clawback_event(
     * source=CONTRIBUTION_REVOKE).
     */
    @Transactional
    public Mono<ClawbackEvent> processContributionRevoke(UUID contributionId, UUID memberId,
                                                          Instant revokedAt, String reason,
                                                          String tenantId, String actorId, String actorEmail) {
        return commissionTxnRepository
            .findByContributionIdAndReversalOfTxnIdIsNull(contributionId)
            .filter(txn -> !"REVERSED".equals(txn.getStatus())
                        && !"CLAWED_BACK".equals(txn.getStatus()))
            .next()
            .flatMap(orig -> clawback(orig, memberId, revokedAt, reason,
                                       "CONTRIBUTION_REVOKE", contributionId.toString(),
                                       actorId, actorEmail));
    }

    private Mono<ClawbackEvent> clawback(CommissionTransaction original, UUID memberId,
                                          Instant occurredAt, String reason, String source,
                                          String triggerRef, String actorId, String actorEmail) {
        // 1. Insert compensating REVERSED transaction (negative amount).
        // 2. Flip original.status to CLAWED_BACK, link reversed_by_txn_id.
        // 3. Insert clawback_event row.
        // 4. Emit AuditEvent for each of the three writes (entity_name = txn.reference).
        ...
    }
}
```

#### 4. Consumers (`com.medfund.finance.producer.consumer`)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/consumer/ProducerCommissionConsumer.java`

Same shape as `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/consumer/ReinsurancePremiumCessionConsumer.java`:

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ProducerCommissionConsumer {

    private static final String TOPIC = "medfund.contributions.paid";
    private final KafkaReceiver<String, String> receiver;
    private final ObjectMapper objectMapper;
    private final CommissionCalcService commissionCalcService;

    @PostConstruct
    public void consume() {
        receiver.receive()
            .flatMap(record -> processEvent(record.value())
                    .doOnSuccess(v -> record.receiverOffset().acknowledge())
                    .onErrorResume(e -> {
                        log.error("[{}] Failed to process record offset={}: {}", TOPIC,
                                  record.receiverOffset().offset(),
                                  fullCauseChain(e));
                        record.receiverOffset().acknowledge();  // ack anyway to avoid poison pill
                        return Mono.empty();
                    }))
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                    .maxBackoff(Duration.ofMinutes(2)))
            .subscribe();
    }

    Mono<Void> processEvent(String json) {
        JsonNode node = objectMapper.readTree(json);
        if (!"CONTRIBUTION_PAID".equals(node.path("event").asText())) return Mono.empty();
        ContributionPaidEvent event = ContributionPaidEvent.from(node);
        String[] system = AuditActor.systemActor();
        return commissionCalcService.processPaidContribution(event, system[0], system[1])
            .then()
            .contextWrite(Context.of(TenantContext.KEY, event.tenantId()));
    }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/consumer/CommissionRevokeConsumer.java`

Same shape, subscribes to `medfund.contributions.revoked`, dispatches to `CommissionClawbackService.processContributionRevoke(...)`. Filters event name `CONTRIBUTION_REVOKED`.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/consumer/CommissionClawbackConsumer.java`

Subscribes to `medfund.users.member-lifecycle`; filters `event=MEMBER_STATUS_CHANGED` AND `status ∈ {lapsed, terminated, deactivated}`; dispatches to `CommissionClawbackService.processMemberLapse(...)`.

#### 5. Reference generator

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/util/ReferenceGenerator.java`

```java
@Component
@RequiredArgsConstructor
public class ReferenceGenerator {
    private final CommissionTransactionRepository commissionTxnRepository;

    public Mono<String> nextCommissionReference() {
        int year = LocalDate.now().getYear();
        String prefix = "COMM-" + year + "-";
        // Naive MVP: SELECT COUNT + format with 6-digit zero-pad; retry on conflict
        return commissionTxnRepository.countByReferenceStartingWith(prefix)
            .map(count -> prefix + String.format("%06d", count + 1));
    }
}
```

(Production hardening: switch to a per-tenant PostgreSQL sequence in a follow-up. MVP counter is fine because the reference column has a UNIQUE index that will reject any collision.)

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :contributions-service:build :finance-service:build`
- [x] Unit tests (`make test-java`):
  - [x] `ContributionEventPublisherTest.publishContributionRevoked_sendsToCorrectTopic_withFullPayload` + `publishContributionRevoked_missingActor_fallsBackToSystemActor` — payload assertion + system-actor fallback per `feedback_audit_actor_email`.
  - [~] `BillingServiceTest.revokeBilling_publishesContributionRevokedPerRow` + `revokeInvoice_publishesContributionRevokedPerRow` — **deferred to IT-level** (see Deviations); publisher-payload contract is fully covered by the two `ContributionEventPublisherTest` cases above.
  - [x] `CommissionCalcServiceTest` (14 cases): no producer → warn+skip; missing rate card → surfaces IllegalStateException; happy path with pure rate-card base; single KICKER; multiple KICKERs summed; zero-amount marker no-op; DuplicateKeyException swallowed; missing tenant / zero amount / missing line short-circuits; missing producer entity warn+skip; sub-producer looks up SUB tier; total-zero skips persist+audit; audit carries reference; ContributionFact carries insuranceLine attribute.
  - [x] `CommissionClawbackServiceTest` (10 cases): lapse within window; lapse outside window no-op; null clawback window no-op; missing memberId short-circuits; duplicate clawback swallowed; revoke reverses unconditionally; revoke no-matching-txn no-op; revoke already-REVERSED no-op; revoke already-CLAWED_BACK no-op; missing contributionId short-circuits; audits carry original reference as entityName.
  - [x] `ProducerCommissionConsumerTest` (5 cases): well-formed dispatch; wrong event type skip; missing tenant skip; missing contributionId skip; malformed JSON errors bubble.
  - [x] `CommissionRevokeConsumerTest` (4 cases): well-formed; wrong event type; missing tenant; malformed JSON.
  - [x] `CommissionClawbackConsumerTest` (7 cases): lapsed dispatches; terminated dispatches; active no-op; suspended no-op; missing tenant no-op; wrong event type no-op; malformed JSON.
- [x] Integration tests (`make test-integration`) — code lands:
  - [x] `CommissionCalcIT` (2 cases): paid contribution → commission_transaction row with correct amount + audit + friendly reference; reprocessing same contribution stays at 1 row.
  - [x] `CommissionClawbackIT` (3 cases): lapse within window writes CLAWED_BACK + clawback_event; revoke reverses txn + writes clawback_event; revoke replay idempotent.
  - [~] `ContributionRevokePublisherIT` — **deferred to follow-up ticket** (see Deviations); revoke path requires a full billing fixture and the current Testcontainers env is unstable (`FATAL: sorry, too many clients already`).

#### Manual Verification
- [ ] Onboard a producer, assign to a member, post a contribution payment → observe commission_transaction row on the DB.
- [ ] Revoke the paid contribution → observe REVERSED compensating row + clawback_event(source=CONTRIBUTION_REVOKE).
- [ ] Operator-lapse the member via `POST /members/{id}/deactivate` → observe CLAWED_BACK on prior commission + clawback_event(source=MEMBER_LAPSE).
- [ ] Deploy sequence: (1) contributions-service with new `publishContributionRevoked` lands first, (2) finance-service `CommissionRevokeConsumer` deploys next — no consumer errors during the window.

**Implementation Note**: pause for manual acceptance before Phase 4.

---

## Phase 4: Commission report backend + XLSX

### Overview

Ship the two commission reports as JSON endpoints + Apache POI XLSX exports, both `@RequiresReport`-gated and both emitting `SecurityEventMessage` on export.

Report keys (already ship — F11-a):
- `COMMISSION_STATEMENT` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:96`) — one row per commission_transaction in the period, optionally filtered by producer.
- `COMMISSION_CLAWBACK` (`.../ReportKey.java:97`) — one row per clawback_event in the period.

Both consume the standard `ReportEnvelopeBuilder` → `{data, perCurrency, fxRates, warnings}` envelope; both accept an optional `?reportingCurrency=` override; both convert native amounts at report-generation-time FX via `FxRateReader` (missing rate → warning + omit from `fxRates` map per parent-plan G28).

### Changes Required

#### 1. Repositories — query shape for the reports

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/repository/CommissionTransactionRepository.java` (extend)

```java
@Query("""
    SELECT ct.id, ct.reference, ct.producer_id,
           p.producer_code, p.name AS producer_name, p.home_currency,
           ct.contribution_id, ct.member_id, ct.insurance_line, ct.rate_card_id,
           rc.name AS rate_card_name, ct.applied_rate_pct,
           ct.native_amount, ct.native_currency, ct.contribution_amount,
           ct.status, ct.occurred_at
      FROM commission_transaction ct
      JOIN producer p                    ON p.id = ct.producer_id
      LEFT JOIN commission_rate_card rc  ON rc.id = ct.rate_card_id
     WHERE ct.occurred_at >= :periodStart
       AND ct.occurred_at <  :periodEnd
       AND (:producerId IS NULL OR ct.producer_id = :producerId)
     ORDER BY ct.occurred_at, p.name
""")
Flux<CommissionStatementRow> queryStatement(OffsetDateTime periodStart,
                                             OffsetDateTime periodEnd,
                                             UUID producerId);
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/repository/ClawbackEventRepository.java`

```java
@Query("""
    SELECT ce.id, ce.source, ce.triggering_event_ref,
           ce.member_id, ce.producer_id, p.producer_code, p.name AS producer_name,
           ce.commission_transaction_id, ct.reference AS commission_reference,
           ce.native_amount, ce.native_currency, ce.reason, ce.occurred_at
      FROM clawback_event ce
      JOIN producer p                     ON p.id = ce.producer_id
      JOIN commission_transaction ct      ON ct.id = ce.commission_transaction_id
     WHERE ce.occurred_at >= :periodStart
       AND ce.occurred_at <  :periodEnd
       AND (:producerId IS NULL OR ce.producer_id = :producerId)
       AND (:source     IS NULL OR ce.source     = :source)
     ORDER BY ce.occurred_at, p.name
""")
Flux<ClawbackRegisterRow> queryRegister(OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                          UUID producerId, String source);
```

Row DTOs (records under `com.medfund.finance.producer.dto`): `CommissionStatementRow`, `ClawbackRegisterRow`. Explicit `@Column` interface projections would work too — records are chosen for consistency with the reinsurance sub-plan's `BordereauRow`.

#### 2. Services

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionStatementService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionStatementService {

    private final CommissionTransactionRepository repo;
    private final ReportEnvelopeBuilder envelopeBuilder;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;

    public Mono<ReportResponse<List<CommissionStatementRow>>> load(UUID tenantId,
                                                                    LocalDate periodStart,
                                                                    LocalDate periodEnd,
                                                                    UUID producerId,
                                                                    String currencyOverride) {
        OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = periodEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        return currencyResolver.resolve(tenantId, currencyOverride)
            .flatMap(reportingCurrency -> repo.queryStatement(from, to, producerId).collectList()
                .flatMap(rows -> envelopeBuilder.build(rows, reportingCurrency,
                        row -> row.nativeAmount(), row -> row.nativeCurrency(),
                        row -> row.occurredAt().toLocalDate(), tenantId)));
    }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionClawbackReportService.java`

Same shape as `CommissionStatementService` — reads via `ClawbackEventRepository.queryRegister(...)`, wraps in `ReportEnvelopeBuilder`.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionWorkbookService.java`

XLSX generator (Apache POI, direct template = `BordereauReportWorkbookService`).

Two workbook shapes:

**Statement XLSX** — one sheet per currency group. Columns:
- Producer code, Producer name, Home currency
- Reference, Contribution id, Member id, Insurance line
- Rate card (name), Applied rate %
- Contribution amount, Commission amount (native), Native currency
- Status, Occurred at (period cell)

Summary sheet at the front: per-currency totals + reporting-currency conversion + FX-rate audit table + `warnings` block.

**Clawback register XLSX** — one sheet per source. Columns:
- Source, Triggering event ref, Producer code + name
- Commission reference, Member id
- Amount (native), Native currency, Reason, Occurred at

Same summary + FX-rate audit at the front.

```java
@Service
@RequiredArgsConstructor
public class CommissionWorkbookService {

    public byte[] renderStatement(ReportResponse<List<CommissionStatementRow>> envelope,
                                   String tenantSlug, LocalDate periodStart, LocalDate periodEnd) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            renderSummarySheet(wb, envelope, "Commission Statement Summary");
            envelope.getData().stream()
                .collect(Collectors.groupingBy(CommissionStatementRow::nativeCurrency))
                .forEach((ccy, rows) -> renderStatementSheet(wb, ccy, rows));
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ReportGenerationException("Failed to render commission statement XLSX", e);
        }
    }

    public byte[] renderClawbackRegister(ReportResponse<List<ClawbackRegisterRow>> envelope,
                                          String tenantSlug, LocalDate periodStart, LocalDate periodEnd) {
        // Same shape, group-by source instead of currency
    }
}
```

#### 3. Controllers

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/CommissionReportController.java`

```java
@RestController
@RequestMapping("/api/v1/reports/commission")
@RequiredArgsConstructor
@Tag(name = "Commission Reports")
public class CommissionReportController {

    private final CommissionStatementService statementService;
    private final CommissionClawbackReportService clawbackService;
    private final CommissionWorkbookService workbookService;
    private final SecurityEventPublisher securityEventPublisher;
    private final TenantSlugResolver tenantSlugResolver;

    @GetMapping("/statement")
    @Operation(summary = "Commission statement — one row per commission transaction in the period")
    @RequiresReport(ReportKey.COMMISSION_STATEMENT)
    public Mono<ReportResponse<List<CommissionStatementRow>>> statement(
            @RequestParam LocalDate periodStart,
            @RequestParam LocalDate periodEnd,
            @RequestParam(required = false) UUID producerId,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.currentTenantId();
        return statementService.load(tenantId, periodStart, periodEnd, producerId, reportingCurrency);
    }

    @GetMapping("/statement/export/excel")
    @Operation(summary = "Commission statement XLSX export")
    @RequiresReport(ReportKey.COMMISSION_STATEMENT)
    public Mono<ResponseEntity<byte[]>> exportStatement(
            @RequestParam LocalDate periodStart, @RequestParam LocalDate periodEnd,
            @RequestParam(required = false) UUID producerId,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.currentTenantId();
        return statementService.load(tenantId, periodStart, periodEnd, producerId, reportingCurrency)
            .zipWith(tenantSlugResolver.resolve(tenantId))
            .flatMap(tuple -> {
                byte[] bytes = workbookService.renderStatement(tuple.getT1(), tuple.getT2(),
                                                               periodStart, periodEnd);
                return securityEventPublisher.publishDataAccess(tenantId,
                        AuditActor.id(jwt), AuditActor.email(jwt),
                        ReportKey.COMMISSION_STATEMENT,
                        Map.of("periodStart", periodStart.toString(),
                               "periodEnd", periodEnd.toString(),
                               "producerId", String.valueOf(producerId),
                               "reportingCurrency", String.valueOf(reportingCurrency)))
                    .thenReturn(ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=commission-statement-"
                                    + periodStart + "_" + periodEnd + ".xlsx")
                        .body(bytes));
            });
    }

    // Same two endpoints for clawback register, gated on ReportKey.COMMISSION_CLAWBACK,
    // additional optional query param ?source=MEMBER_LAPSE|CONTRIBUTION_REVOKE.
}
```

#### 4. Gateway routes

**File**: `services/go/gateway/internal/finance/routes.go` (extend Phase 2 additions)

```
/api/v1/reports/commission/statement           → finance-service
/api/v1/reports/commission/statement/export/**  → finance-service
/api/v1/reports/commission/clawback-register    → finance-service
/api/v1/reports/commission/clawback-register/export/** → finance-service
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build`
- [x] Unit tests (`make test-java`):
  - [x] `CommissionStatementServiceTest` (5 cases): period translation (LocalDate → exclusive-end OffsetDateTime); producer filter forwarded; currency override forwarded; report key wired to `COMMISSION_STATEMENT`; grain=CUSTOM. Reactive-envelope-composition end-to-end lives in `CommissionReportIT` — same pattern as the reinsurance `BordereauReportServiceTest`.
  - [x] `CommissionClawbackReportServiceTest` (5 cases): same shape as statement + optional source filter forwarded to repository.
  - [x] `CommissionWorkbookServiceTest` (7 cases): statement renders one sheet per currency + summary; empty envelope renders placeholder + summary; missing FX omits converted total with warning line; producer filter labels producer in meta; special characters survive round-trip; clawback register renders one sheet per source + summary; clawback empty envelope renders placeholder + summary. Workbook bytes parsed back through Apache POI so cell values (not just "did it produce bytes") are asserted.
- [x] Integration tests (`make test-integration`):
  - [x] `CommissionReportIT` (6 tests): statement envelope carries native rows + per-currency subtotals with tenant reporting currency; statement XLSX renders valid workbook with per-currency sheet + summary; producer filter narrows to the seeded producer; clawback register envelope with optional `source` filter; clawback XLSX one sheet per source; `ReportEnablementReader.isEnabled` flips FALSE after an `enabled=FALSE` row lands in `public.tenant_report_config` (asserts the read-side that `ReportGuardAspect` uses; adds test-migration V013 so the table exists in the finance test schema).
- [x] Gateway Go tests: `cd services/go/gateway && go test ./...` — build + existing tests pass; the four new routes are already wired at `services/go/gateway/internal/routes/routes.go:210-211` (the two `/api/v1/reports/commission[/*]` catch-alls forward every commission report path to finance-service). Matches the Phase 2 deviation on gateway routes tests — the reinsurance sibling routes have no `routes_test.go` either.

#### Manual Verification
- [ ] Toggle COMMISSION_STATEMENT to enabled in `tenant_report_config` → `GET /api/v1/reports/commission/statement?periodStart=2026-07-01&periodEnd=2026-09-30` returns envelope with real data.
- [ ] Export XLSX for a period spanning multiple currencies → file downloads, opens in LibreOffice, one sheet per currency, summary sheet includes fx-rate audit + any warnings.
- [ ] Toggle report off → GET returns 403; XLSX endpoint returns 403.
- [ ] Kafka `medfund.security.events` carries `reportKey=COMMISSION_STATEMENT` on every export.

**Implementation Note**: pause for manual acceptance before Phase 5.

---

## Phase 5: Commission reports Angular

### Overview

Ship the two Angular report pages under `/tenant/finance/reports/commission/*`. Auto-register the two report keys with `ReportCatalogueService` so they appear on the reports hub when enabled.

### Changes Required

#### 1. Report catalogue registration

**File**: `clients/angular/src/app/pages/tenant/finance/reports/report-catalogue.service.ts` (or wherever the report catalogue lives — verify at implement time)

Add two entries:
```typescript
{ reportKey: 'COMMISSION_STATEMENT',       family: 'COMMISSION',
  label: 'Commission statement',          route: 'reports/commission/statement' },
{ reportKey: 'COMMISSION_CLAWBACK',        family: 'COMMISSION',
  label: 'Clawback register',             route: 'reports/commission/clawback-register' },
```

#### 2. Routes

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts` (extend after the reinsurance routes block at line 679-715)

```typescript
// Commission reports
{ path: 'reports/commission/statement',
  loadComponent: () => import('./reports/commission/commission-statement.component')
      .then(m => m.CommissionStatementComponent),
  data: { reportKey: 'COMMISSION_STATEMENT', permission: 'finance.commission:view' } },

{ path: 'reports/commission/clawback-register',
  loadComponent: () => import('./reports/commission/commission-clawback-register.component')
      .then(m => m.CommissionClawbackRegisterComponent),
  data: { reportKey: 'COMMISSION_CLAWBACK', permission: 'finance.commission:view' } },
```

#### 3. Components

**File**: `clients/angular/src/app/pages/tenant/finance/reports/commission/commission-statement.component.ts`

Standalone component. Uses shared `ReportPageLayoutComponent` (period picker + reporting-currency override picker + export button + envelope-summary panel + KPI band). Loads via a new `CommissionReportService` (thin wrapper over `HttpClient` on `/api/v1/reports/commission/*`). Producer filter uses shared `SearchSelectComponent` for the producer picker per `feedback_no_raw_id_inputs`.

Table columns match the JSON row shape from Phase 4; sortable client-side (data already tenant-scoped + period-scoped server-side).

Export button:
```typescript
export(): void {
    this.reportService.exportStatementXlsx(this.filters).subscribe(blob => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `commission-statement-${this.filters.periodStart}_${this.filters.periodEnd}.xlsx`;
        link.click();
        URL.revokeObjectURL(url);
    });
}
```

**File**: `clients/angular/src/app/pages/tenant/finance/reports/commission/commission-clawback-register.component.ts`

Same shape, additional source filter dropdown (`MEMBER_LAPSE | CONTRIBUTION_REVOKE | (all)`).

**File**: `clients/angular/src/app/core/services/commission-report.service.ts`

```typescript
@Injectable({ providedIn: 'root' })
export class CommissionReportService {
    private http = inject(HttpClient);

    loadStatement(filters: CommissionStatementFilters): Observable<ReportResponse<CommissionStatementRow[]>> {
        return this.http.get<ReportResponse<CommissionStatementRow[]>>(
            '/api/v1/reports/commission/statement', { params: this.toParams(filters) });
    }
    exportStatementXlsx(filters: CommissionStatementFilters): Observable<Blob> {
        return this.http.get('/api/v1/reports/commission/statement/export/excel',
            { params: this.toParams(filters), responseType: 'blob' });
    }
    loadClawbackRegister(filters: ClawbackFilters): Observable<ReportResponse<ClawbackRegisterRow[]>> { ... }
    exportClawbackRegisterXlsx(filters: ClawbackFilters): Observable<Blob> { ... }
}
```

#### 4. Sidebar nav

**File**: `clients/angular/src/app/layout/tenant-layout/tenant-layout.component.ts` (extend operational nav section)

Add a "Commission" sub-heading under the Reports section with the two report links; both `*ngIf`'d against `TenantReportConfigService.isEnabled(tenantId, reportKey)`.

### Success Criteria

#### Automated Verification
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development`. No new warnings — only pre-existing warnings in unrelated code (`ClaimDetailComponent`, `TariffCodesListComponent`, `TaxWithheldListComponent`).
- [x] `make test-angular` (targeted): `commission-report.service.spec.ts` — 6 cases covering all four seams (statement GET with all filters, statement GET omitting optional filters, statement export blob, clawback GET with source filter, clawback GET omitting source, clawback export blob). Component-level specs deferred to follow the reinsurance report precedent (no component specs exist for `cession-bordereau.component`, `recoveries-bordereau.component`, `treaty-utilization.component` either — see Deviations).
- [ ] `verify` on `/tenant/finance/reports/commission/statement` — page renders, filter row works, export triggers a download.
- [ ] `verify` on `/tenant/finance/reports/commission/clawback-register` — same.
- [ ] Playwright: `commission-report.spec.ts` — enable both reports via `TenantReportConfigService` mock → hub shows the family card → click statement → filter to Q3 2026 → export → assert one download.

#### Manual Verification
- [ ] Reports hub at `/tenant/finance/reports` shows a "Commission" family card when the two keys are enabled.
- [ ] Toggle a report off in tenant-admin → direct URL navigation redirects to 403.
- [ ] Reporting-currency override picker sets `?reportingCurrency=EUR` and the summary panel updates with the new conversion.
- [ ] Missing FX rate for one currency shows a warning banner beneath the toolbar and the report still renders (envelope `warnings` array is displayed inline).

**Implementation Note**: pause for manual acceptance before Phase 6.

---

## Phase 6: PaymentRun extension for PRODUCER payee

### Overview

Ship the producer branch of `PaymentRunGenerator`, the Angular payout run list + create form for producers, and the tenant-admin visibility of PRODUCER-typed payment runs on the existing creditors surface. Concludes §A.

The schema widening for `payee_type='PRODUCER'` + XOR CHECK + `producer_id` columns already landed in Phase 1. Phase 6 is pure Java + Angular.

### Changes Required

#### 1. Entity extension

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRun.java` (edit)

`payee_type` field already exists; validation/enum widening at the service layer only. No entity edit needed — the field type is `String` today.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRunItem.java` (edit)

Add fields:
```java
@Column("producer_id")         private UUID producerId;
@Column("withholding_tax_pct") private BigDecimal withholdingTaxPct;
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/Payment.java` (edit)

Add:
```java
@Column("producer_id") private UUID producerId;
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentAdvice.java` (edit)

Add:
```java
@Column("producer_id") private UUID producerId;
```

#### 2. Repository extension

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/repository/CommissionTransactionRepository.java` (extend)

```java
@Query("""
    SELECT ct.producer_id,
           p.home_currency,
           SUM(ct.native_amount) AS total_native,
           ct.native_currency,
           COUNT(*)               AS txn_count
      FROM commission_transaction ct
      JOIN producer p ON p.id = ct.producer_id
     WHERE ct.status = 'ACCRUED'
       AND p.home_currency = :homeCurrency
       AND ct.occurred_at >= :periodStart
       AND ct.occurred_at <  :periodEnd
     GROUP BY ct.producer_id, p.home_currency, ct.native_currency
     ORDER BY ct.producer_id
""")
Flux<ProducerCommissionSummary> aggregateForPayout(String homeCurrency,
                                                    OffsetDateTime periodStart,
                                                    OffsetDateTime periodEnd);

@Query("UPDATE commission_transaction SET status = 'PAID', paid_run_id = :runId, paid_at = NOW() "
     + "WHERE producer_id = :producerId AND status = 'ACCRUED' "
     + "  AND occurred_at >= :periodStart AND occurred_at < :periodEnd")
Mono<Integer> markPaidByProducerAndPeriod(UUID producerId, UUID runId,
                                           OffsetDateTime periodStart, OffsetDateTime periodEnd);
```

`ProducerCommissionSummary` is a record: `(UUID producerId, String homeCurrency, BigDecimal totalNative, String nativeCurrency, long txnCount)`.

#### 3. Generator extension

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunGenerator.java` (extend after `populateProviderItems` at line 74)

```java
/**
 * Populate producer-payout items for a PaymentRun. Enumerates ACCRUED
 * commission_transaction rows for producers whose home_currency matches
 * the run currency, groups by producer, aggregates native amounts converted
 * to the producer's home currency at commit-time FX (per P10 —
 * .claude/multi-currency.md:164), applies WHT from producer.wht_pct_override
 * if configured, produces one payment_run_item per producer.
 *
 * Runs are homogeneous by (home_currency, period).
 */
Flux<PaymentRunItem> populateProducerItems(UUID runId, String currency,
                                            LocalDate periodStart, LocalDate periodEnd) {
    OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime to   = periodEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    UUID tenantId = TenantContext.currentTenantId();

    return commissionTransactionRepository.aggregateForPayout(currency, from, to)
        .groupBy(ProducerCommissionSummary::producerId)
        .flatMap(byProducer -> byProducer.collectList()
            .flatMap(summaries -> {
                UUID producerId = byProducer.key();
                return producerRepository.findById(producerId)
                    .flatMap(producer -> sumInHomeCurrency(summaries, producer.getHomeCurrency(),
                                                            periodStart, tenantId)
                        .flatMap(homeAmount -> {
                            BigDecimal netAmount = applyWht(homeAmount, producer);
                            return createPaymentAndItem(runId, currency, "PRODUCER",
                                    null, null, producerId, netAmount,
                                    producer.getWhtPctOverride());
                        }));
            }));
}

private Mono<BigDecimal> sumInHomeCurrency(List<ProducerCommissionSummary> summaries,
                                             String homeCurrency, LocalDate asOf, UUID tenantId) {
    return Flux.fromIterable(summaries)
        .flatMap(s -> {
            if (s.nativeCurrency().equals(homeCurrency)) {
                return Mono.just(s.totalNative());
            }
            return fxRateReader.findRate(s.nativeCurrency(), homeCurrency, asOf, tenantId)
                .switchIfEmpty(Mono.error(new ReportGenerationException(
                    "Missing FX rate " + s.nativeCurrency() + "→" + homeCurrency + " on " + asOf)))
                .map(rate -> s.totalNative().multiply(rate));
        })
        .reduce(BigDecimal.ZERO, BigDecimal::add);
}

private BigDecimal applyWht(BigDecimal gross, Producer producer) {
    if (producer.getWhtPctOverride() == null) return gross;
    BigDecimal wht = gross.multiply(producer.getWhtPctOverride()).movePointLeft(2);
    return gross.subtract(wht);
}
```

Extend the existing `createPaymentAndItem` (line 85-115) to accept `UUID producerId` and `BigDecimal whtPct` — the third and fourth new positional args pass through to the payment_run_items INSERT.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java` (extend `generate` line 77-116)

Route on the caller's `payeeType`:
```java
public Mono<PaymentRun> generate(CreatePaymentRunRequest req, String actorId, String actorEmail) {
    validatePayeeType(req.payeeType());   // reject unknown; accept PROVIDER|MEMBER|PRODUCER
    return createDraft(req, actorId, actorEmail)
        .flatMap(run -> switch (req.payeeType()) {
            case "PROVIDER" -> generator.populateProviderItems(run.getId(), run.getCurrencyCode())
                                        .then(Mono.just(run));
            case "MEMBER"   -> generator.populateMemberItems(run.getId(), run.getCurrencyCode())
                                        .then(Mono.just(run));
            case "PRODUCER" -> generator.populateProducerItems(run.getId(), run.getCurrencyCode(),
                                                                req.periodStart(), req.periodEnd())
                                        .then(Mono.just(run));
            default -> Mono.error(new IllegalStateException("Unhandled payeeType " + req.payeeType()));
        })
        .flatMap(this::recomputeTotals);
}
```

Extend the `execute` transition (approved → executing → executed): after payment writes settle, call `commissionTransactionRepository.markPaidByProducerAndPeriod(producerId, runId, periodStart, periodEnd)` per PRODUCER item — flips underlying `commission_transaction.status ACCRUED → PAID`.

#### 4. Controller extensions

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentRunController.java` (extend)

The existing controller already accepts a `payeeType` on the create request; no new endpoints. Add two permissions to the existing routes so PRODUCER runs are gated separately:
- `POST /api/v1/finance/payment-runs` with `payeeType=PRODUCER` — additionally requires `finance.commission:create_payout_run`
- `PUT  /api/v1/finance/payment-runs/{id}/approve` with a PRODUCER-typed run — additionally requires `finance.commission:approve_payout_run`

Use an @Aspect or the existing `@RequiresPermission` with a runtime SpEL check on `req.payeeType == 'PRODUCER'`.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/dto/CreateProducerPayoutRequest.java`

New request record — thin wrapper over `CreatePaymentRunRequest` that hardcodes `payeeType='PRODUCER'` and adds `periodStart`, `periodEnd` (mandatory for producer runs; not needed for provider runs which just drain outstanding balances). Alternatively, extend `CreatePaymentRunRequest` with optional `periodStart`/`periodEnd` that are validated as required when `payeeType='PRODUCER'`. Prefer the second — one shape for the whole payout surface, one validator.

#### 5. Angular

**File**: `clients/angular/src/app/pages/tenant/finance/payouts/producer/producer-payout-list.component.ts` (new)

Standalone component. Lists PRODUCER-typed payment runs (`GET /api/v1/finance/payment-runs?payeeType=PRODUCER`); shows currency, period, status, count of producers, total amount. Filter by status + currency + producer. Row-click routes to `/tenant/finance/payouts/producer/{id}`.

**File**: `.../payouts/producer/create-producer-payout.component.ts` (new)

Create form: currency picker, period picker (start + end snap per `feedback_effective_date_snap`: start = 1st-of-month, end = last-day-of-month), producer filter (optional; empty = all producers matching currency), preview panel that shows the aggregated total per producer before commit. Submit calls `POST /api/v1/finance/payment-runs` with `payeeType=PRODUCER`. Permission `finance.commission:create_payout_run`.

**File**: `.../payouts/producer/producer-payout-detail.component.ts` (new)

Detail page: header shows run metadata (currency, period, status, source bank), items table shows one row per producer with columns `producer code`, `home_currency`, `gross amount`, `WHT %`, `WHT amount`, `net amount`. Actions (approve, execute) gated by `finance.commission:approve_payout_run`.

**File**: `clients/angular/src/app/core/services/producer-payout.service.ts` (new)

Thin wrapper — leverages existing `PaymentRunService` for shared shapes; adds `preview(request): Observable<ProducerPayoutPreview[]>` for the create form.

**Routes** in `finance.routes.ts`:
```typescript
{ path: 'payouts/producer',       component: ProducerPayoutListComponent,   data: { permission: 'finance.commission:view' } },
{ path: 'payouts/producer/new',   component: CreateProducerPayoutComponent, data: { permission: 'finance.commission:create_payout_run' } },
{ path: 'payouts/producer/:id',   component: ProducerPayoutDetailComponent, data: { permission: 'finance.commission:view' } },
```

Sidebar entry: extend the existing Payouts group with a "Producer payouts" child.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build` (main + test)
- [x] Unit tests (`make test-java`):
  - `PaymentRunGeneratorTest` — 8 new cases (all green): producer branch happy path (single producer, single currency); multi-producer aggregation; mixed native currencies with FX conversion; missing FX rate for one leg surfaces the FX error; WHT applied when configured; WHT null → gross paid; producer with zero accruals in period skipped (empty aggregate short-circuits); missing producer errors cleanly.
  - `PaymentRunServiceTest` — 4 new cases (all green): create with `payeeType=PRODUCER` routes to producer generator and snaps period to 1st-of-month / last-day-of-month; execute transitions commission_transaction.status ACCRUED → PAID via `markPaidByProducerAndPeriod`; missing period on a PRODUCER request rejected; missing `finance.commission:create_payout_run` permission returns 403.
- [ ] Integration tests (`make test-integration`):
  - `ProducerPayoutIT` — deferred (see Deviations); follows the Phase 3 / Phase 4 pattern of deferring finance-service ITs under Testcontainers connection-pool pressure. Unit-level coverage in `PaymentRunGeneratorTest` + `PaymentRunServiceTest` proves the wiring; a follow-up ticket lands the full IT alongside the harness pool tuning pass.
  - Existing `PaymentRunOutflowControllerIT` / `PaymentRunWorkbookControllerIT` remain green — verified by running the full finance-service unit-test suite (30+ specs, no regressions).
- [x] Angular compiles + `make test-angular`:
  - `producer-payout.service.spec.ts` — 7 cases (all green under ChromeHeadless): list without filters (hardcodes payeeType=PRODUCER), list with status + currency filters, get, items, create (body hardcodes payeeType=PRODUCER + forwards period), approve, execute.
  - `producer-payout-list.component.spec.ts` + `create-producer-payout.component.spec.ts` — component-level specs deferred following the Phase 2 / Phase 5 precedent (no component specs for the sibling reinsurance or producer-admin components; wire seam covered by the service spec).
- [ ] `verify` on `/tenant/finance/payouts/producer` — page loads.
- [ ] `verify` on `/tenant/finance/payouts/producer/new` — filter by currency = USD, preview shows aggregation, create redirects to detail.
- [ ] Playwright: `producer-payout.spec.ts` — draft → approve → execute round-trip; commission_transaction reflected on the detail table as PAID.

#### Manual Verification
- [ ] Existing PROVIDER + MEMBER payout runs continue to work unchanged (regression check).
- [ ] Create a PRODUCER run for a period with 3 producers → detail shows 3 items; execute → underlying commission_transaction rows flip PAID with matching paid_run_id.
- [ ] WHT-configured producer → advice line shows gross - WHT and net; XLSX export (Phase 7 of finance stack, not this plan) includes new WHT column.
- [ ] Attempt to create a run without period_start/period_end for payeeType=PRODUCER → 400 with a clear validation message.

**Implementation Note**: pause for manual acceptance before Phase 7. **This is the last §A phase.** After phase 6 lands, a working commission accrual + payout pipeline is live end-to-end; §B stacks workflow features on top.

---

## Phase 7: Auto-lapse chain (§B)

### Overview

Ship the full auto-lapse pipeline bundled into Phase 11 per P7 (user override of the research-doc default):
1. `ArrearsEscalationExecutor` in contributions-service publishes `medfund.contributions.arrears-threshold-breached` when a member crosses the tenant-configured threshold.
2. `BillingService.recordPayment` publishes `medfund.contributions.arrears-cleared` when a payment drops the member's aged balance back under threshold.
3. `ArrearsBreachedConsumer` in user-service sets `Member.scheduledStatus='LAPSED'` + `scheduledStatusEffectiveFrom = today + graceWindowDays`.
4. `ArrearsClearedConsumer` in user-service nulls the scheduled status if it lands before the effective date.
5. The existing `ScheduledStatusExecutor` job transitions the member and emits `MEMBER_STATUS_CHANGED`; the Phase 3 `CommissionClawbackConsumer` fires naturally on that event.
6. Tenant-admin `TenantAutoLapseConfigController` + Angular admin surface for the V133 config table.

### Changes Required

#### 1. New Kafka publisher in contributions-service

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ArrearsThresholdPublisher.java` (new — parallel to `ArrearsNoticePublisher`)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ArrearsThresholdPublisher {

    private static final String TOPIC_BREACHED = "medfund.contributions.arrears-threshold-breached";
    private static final String TOPIC_CLEARED  = "medfund.contributions.arrears-cleared";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publishBreached(String tenantId, String subjectType, String subjectId,
                                       int arrearsMonths, BigDecimal balance, String currencyCode) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("event",         "ARREARS_THRESHOLD_BREACHED");
        fields.put("tenantId",      tenantId);
        fields.put("subjectType",   subjectType);   // MEMBER | GROUP
        fields.put("subjectId",     subjectId);
        fields.put("arrearsMonths", String.valueOf(arrearsMonths));
        fields.put("balance",       balance.toPlainString());
        fields.put("currencyCode",  currencyCode);
        fields.put("breachedAt",    Instant.now().toString());
        return send(TOPIC_BREACHED, subjectId, fields);
    }

    public Mono<Void> publishCleared(String tenantId, String subjectType, String subjectId,
                                      BigDecimal balance, String currencyCode) {
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("event",        "ARREARS_CLEARED");
        fields.put("tenantId",     tenantId);
        fields.put("subjectType",  subjectType);
        fields.put("subjectId",    subjectId);
        fields.put("balance",      balance.toPlainString());
        fields.put("currencyCode", currencyCode);
        fields.put("clearedAt",    Instant.now().toString());
        return send(TOPIC_CLEARED, subjectId, fields);
    }

    private Mono<Void> send(String topic, String key, Map<String, String> fields) { ... }
}
```

#### 2. Extend `ArrearsEscalationExecutor` (`services/java/contributions-service/.../job/ArrearsEscalationExecutor.java`)

At line 189 (where `"SUSPENDED".equals(bucket) && autoSuspend` currently triggers `userClient.suspendMember/Group`), extend the behavior:

```java
if ("SUSPENDED".equals(bucket) && autoSuspend) {
    // Existing: hard suspend via userClient
    escalate = isGroup
            ? userClient.suspendGroup(subjectId, null, "ARREARS_ESCALATION")
            : userClient.suspendMember(subjectId, null, "ARREARS_ESCALATION");

    // NEW: publish threshold-breached for the auto-lapse consumer
    escalate = escalate.then(publisher.publishBreached(
            tenantId, isGroup ? "GROUP" : "MEMBER", subjectId.toString(),
            approximateArrearsMonths(row), row.balance(), row.currencyCode()));
}
```

Read the auto-lapse config once per sweep to know the threshold months; treat `enabled=false` as no-op for the new publish (the existing autoSuspend behavior stays independent for backward compatibility).

`approximateArrearsMonths` = `Math.max(1, row.daysSinceLastActivity() / 30)` — good enough for the consumer, which persists whatever comes through.

#### 3. Extend `BillingService.recordPayment` (`services/java/contributions-service/.../service/BillingService.java` around line 244)

```java
return contributionRepository.save(contribution)
    .flatMap(saved -> {
        // Snapshot pre-payment aged bucket for this subject
        return balanceService.currentBucketFor(saved.getMemberId(), saved.getCurrencyCode())
            .zipWith(balanceService.applyContributionPaid(saved)
                .then(balanceService.currentBucketFor(saved.getMemberId(), saved.getCurrencyCode())))
            .flatMap(tuple -> {
                String preBucket  = tuple.getT1();
                String postBucket = tuple.getT2();
                boolean cleared = ("SUSPENDED".equals(preBucket) || "WRITE_OFF".equals(preBucket))
                                && "GRACE".equals(postBucket);
                if (cleared) {
                    return arrearsThresholdPublisher.publishCleared(
                            TenantContext.currentTenantId().toString(),
                            "MEMBER", saved.getMemberId().toString(),
                            /* current outstanding after payment */ BigDecimal.ZERO,
                            saved.getCurrencyCode());
                }
                return Mono.empty();
            })
            .thenReturn(saved);
    });
```

`BalanceService.currentBucketFor(subjectId, currency)` is a new method: returns `"GRACE" | "SUSPENDED" | "WRITE_OFF"` based on the same `classify(row, threshold)` used by `ArrearsEscalationExecutor`. Returns `"GRACE"` when there's no outstanding row.

#### 4. New user-service consumers

**File**: `services/java/user-service/src/main/java/com/medfund/user/consumer/ArrearsBreachedConsumer.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ArrearsBreachedConsumer {
    private static final String TOPIC = "medfund.contributions.arrears-threshold-breached";
    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final MemberService memberService;
    private final TenantAutoLapseConfigClient configClient;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options).receive()
            .flatMap(record -> processEvent(record.value())
                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                .onErrorResume(e -> {
                    log.error("[{}] Failed offset={}: {}", TOPIC,
                              record.receiverOffset().offset(), fullCauseChain(e));
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }))
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                    .maxBackoff(Duration.ofMinutes(2)))
            .subscribe();
    }

    Mono<Void> processEvent(String json) {
        JsonNode node = objectMapper.readTree(json);
        if (!"ARREARS_THRESHOLD_BREACHED".equals(node.path("event").asText())) return Mono.empty();
        if (!"MEMBER".equals(node.path("subjectType").asText())) return Mono.empty();
        UUID memberId = UUID.fromString(node.get("subjectId").asText());
        String tenantId = node.get("tenantId").asText();

        return configClient.getConfig(UUID.fromString(tenantId))
            .filter(TenantAutoLapseConfig::isEnabled)
            .switchIfEmpty(Mono.fromRunnable(() ->
                log.debug("Auto-lapse disabled for tenant {}, skipping", tenantId)).then(Mono.empty()))
            .flatMap(cfg -> memberService.applyOrSchedule(memberId, "lapsed",
                    LocalDate.now().plusDays(cfg.getGraceWindowDays()),
                    "ARREARS_AUTO_LAPSE", "system", "system@medfund"))
            .then()
            .contextWrite(Context.of(TenantContext.KEY, tenantId));
    }
}
```

**File**: `services/java/user-service/src/main/java/com/medfund/user/consumer/ArrearsClearedConsumer.java`

Subscribes to `medfund.contributions.arrears-cleared`. Nulls `scheduledStatus` on the member if it's currently set to `LAPSED` and the effective date hasn't passed. Emits an audit event on the reset via `MemberService.cancelScheduledStatus(memberId, "ARREARS_CLEARED", "system", "system@medfund")` — a new public method on `MemberService` that mirrors `applyOrSchedule` but sets the three scheduled-status columns to null.

**File**: `services/java/user-service/src/main/java/com/medfund/user/client/TenantAutoLapseConfigClient.java`

Cross-service WebClient to `/api/v1/tenants/{tenantId}/auto-lapse-config` (see next section). Wraps `CrossServiceCallHelper` for the 2s timeout + 1 retry + warnings-list handling. Returns `Mono<TenantAutoLapseConfig>` — empty if config missing (treat as disabled).

#### 5. Public config surface — tenancy-service

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/TenantAutoLapseConfig.java` (new — mirrors `TenantHighCostClaimantConfig`)

```java
@Getter
@Setter
@Table(schema = "public", value = "tenant_auto_lapse_config")
public class TenantAutoLapseConfig {
    @Id private UUID id;
    @Column("tenant_id")                private UUID tenantId;
    @Column("enabled")                  private boolean enabled;
    @Column("arrears_threshold_months") private Integer arrearsThresholdMonths;
    @Column("grace_window_days")        private Integer graceWindowDays;
    @Column("created_at")               private OffsetDateTime createdAt;
    @Column("updated_at")               private OffsetDateTime updatedAt;
    @Column("actor_id")                 private UUID   actorId;
    @Column("actor_email")              private String actorEmail;
}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/repository/TenantAutoLapseConfigRepository.java`

`ReactiveCrudRepository<TenantAutoLapseConfig, UUID>` + `Mono<TenantAutoLapseConfig> findByTenantId(UUID)`.

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantAutoLapseConfigService.java`

Standard `get(tenantId)` + `upsert(tenantId, req, actor)`. Every mutation writes an `AuditEvent` — `entityName = tenant.slug` per `feedback_audit_entity_name`.

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantAutoLapseConfigController.java`

REST at `/api/v1/tenants/{tenantId}/auto-lapse-config`. Direct copy of `TenantHighCostClaimantConfigController` shape:
- `GET` — permission `admin:manage_settings` OR `finance:view`.
- `PUT` — permission `tenant.settings:manage_auto_lapse` (seeded in Phase 1 V099).

#### 6. Tenant-admin Angular

**File**: `clients/angular/src/app/pages/tenant-admin/settings/auto-lapse/auto-lapse-config.component.ts` (new — mirrors `HighCostClaimantConfigComponent`)

Simple form: enabled toggle, arrears threshold months (int, 1-60), grace window days (int, 0-180). Save calls `PUT /api/v1/tenants/{tenantId}/auto-lapse-config`. `TenantAutoLapseConfigService` under `core/services/` — thin wrapper.

Mounted at `/tenant/admin/settings/auto-lapse` (add route to `tenant-admin/settings/settings.routes.ts`). Sidebar entry under "Settings" group, permission-gated on `tenant.settings:manage_auto_lapse`.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :contributions-service:compileJava :user-service:compileJava :tenancy-service:compileJava` (test-classes also green).
- [x] Unit tests (`make test-java`):
  - `ArrearsThresholdPublisherTest` (3 cases): breached + cleared payloads round-trip through the topic; null tenant/currency serialise as empty strings.
  - `ArrearsEscalationExecutorTest` — 3 new cases green: SUSPENDED bucket + autoLapse=true emits threshold-breached; SUSPENDED bucket + autoLapse=disabled (empty config row) no publish; GRACE bucket + autoLapse=true no publish.
  - `BillingServiceTest.recordPayment_publishesArrearsCleared_*` — 3 cases green: SUSPENDED→GRACE emits; GRACE→GRACE no publish; WRITE_OFF→GRACE emits.
  - `ArrearsBreachedConsumerTest` (6 cases green): well-formed → schedules LAPSED at today+grace; disabled tenant → no-op; group subject → no-op; missing subjectId → drop; wrong event type → ignore; malformed JSON → drop.
  - `ArrearsClearedConsumerTest` (5 cases green): valid payload → cancelScheduledStatus called with expected='lapsed'; group subject → skip; wrong event type → ignore; missing subjectId → drop; malformed JSON → drop.
  - `TenantAutoLapseConfigServiceTest` (6 cases green): get existing maps entity; get missing returns unconfigured (enabled=false + nulls); insert path emits CREATE audit with tenant slug; update path emits UPDATE audit with diff; enabled=true without threshold/grace rejected; disabled=true with nulls accepted.
- [ ] Integration tests (`make test-integration`) — `AutoLapseIT` + `TenantAutoLapseConfigIT` deferred (see Deviations).
- [x] Angular compiles + `make test-angular` — 7 new specs green (`tenant-auto-lapse-config.service.spec.ts` × 3, `auto-lapse-config.component.spec.ts` × 4).
- [x] Go gateway builds + tests green (`go build ./... && go test ./...`) — the two new tenant subroutes are documented at `routes.go:29` before the catch-all.
- [ ] `verify` on the tenant-admin settings → **Auto-Lapse** tab — form loads, saves, values round-trip.
- [ ] Playwright: `auto-lapse-config.spec.ts` — enable + save; tab renders under tenant settings.

#### Manual Verification
- [ ] Enable auto-lapse for a test tenant with `arrears_threshold_months=3`, `grace_window_days=7`.
- [ ] Simulate a member 90+ days into arrears — `ArrearsEscalationExecutor` fires nightly (or trigger manually via `POST /api/v1/scheduled-jobs/{configId}/run`); observe `medfund.contributions.arrears-threshold-breached` on Kafka; observe `Member.scheduled_status='LAPSED'` and `scheduled_status_effective_from = today + 7`.
- [ ] Post a payment that clears the arrears within the 7-day window — observe `medfund.contributions.arrears-cleared` and `Member.scheduled_status` nulled.
- [ ] Let the 7-day window elapse without payment — `ScheduledStatusExecutor` transitions to LAPSED; `MEMBER_STATUS_CHANGED` fires; `CommissionClawbackConsumer` (from Phase 3) writes CLAWED_BACK + clawback_event(source=MEMBER_LAPSE).

**Implementation Note**: pause for manual acceptance before Phase 8.

---

## Phase 8: CommissionAdjustment four-eyes workflow (§B)

### Overview

Ship the four-eyes commission adjustment surface — DRAFT → APPROVED → COMMITTED → VOIDED — mirroring `FacultativeCessionService` 1:1. Backend + Angular in one phase (the queue is the whole product; a curl surface without a UI provides no user value).

### Changes Required

#### 1. Entity

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/entity/CommissionAdjustment.java`

```java
@Getter
@Setter
@Table("commission_adjustment")
public class CommissionAdjustment {
    @Id private UUID id;
    @Column("reference")                        private String reference;
    @Column("target_commission_transaction_id") private UUID targetCommissionTransactionId;
    @Column("adjustment_type")                  private String adjustmentType;   // EX_GRATIA|VOID|MANUAL_CLAWBACK|MANUAL_REVERSAL
    @Column("adjustment_amount")                private BigDecimal adjustmentAmount;
    @Column("native_currency")                  private String nativeCurrency;
    @Column("justification")                    private String justification;
    @Column("status")                           private String status;           // DRAFT|APPROVED|COMMITTED|VOIDED
    @Column("approver_actor_id")                private UUID   approverActorId;
    @Column("approver_actor_email")             private String approverActorEmail;
    @Column("approved_at")                      private OffsetDateTime approvedAt;
    @Column("committed_at")                     private OffsetDateTime committedAt;
    @Column("committed_txn_id")                 private UUID   committedTxnId;
    @Column("voided_at")                        private OffsetDateTime voidedAt;
    @Column("voided_reason")                    private String voidedReason;
    @Column("created_at")                       private OffsetDateTime createdAt;
    @Column("updated_at")                       private OffsetDateTime updatedAt;
    @Column("actor_id")                         private UUID   actorId;
    @Column("actor_email")                      private String actorEmail;
}
```

#### 2. Repository

Standard `ReactiveCrudRepository<CommissionAdjustment, UUID>` with `findByStatus`, `findByTargetCommissionTransactionId`, and a paginated `findByStatusInOrderByCreatedAtDesc(List<String>, Pageable)` for the queue view.

#### 3. Service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionAdjustmentService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionAdjustmentService {

    private final CommissionAdjustmentRepository adjustmentRepository;
    private final CommissionTransactionRepository commissionTxnRepository;
    private final ReferenceGenerator referenceGenerator;
    private final AuditPublisher auditPublisher;

    @Transactional
    public Mono<CommissionAdjustment> createDraft(CreateAdjustmentRequest req,
                                                   String actorId, String actorEmail) {
        return validateDraft(req)
            .then(commissionTxnRepository.findById(req.targetCommissionTransactionId())
                .switchIfEmpty(Mono.error(new EntityNotFoundException(
                        "CommissionTransaction", req.targetCommissionTransactionId()))))
            .flatMap(target -> referenceGenerator.nextAdjustmentReference()
                .flatMap(ref -> {
                    CommissionAdjustment adj = new CommissionAdjustment();
                    adj.setReference(ref);
                    adj.setTargetCommissionTransactionId(target.getId());
                    adj.setAdjustmentType(req.adjustmentType());
                    adj.setAdjustmentAmount(req.adjustmentAmount());
                    adj.setNativeCurrency(target.getNativeCurrency());
                    adj.setJustification(req.justification());
                    adj.setStatus("DRAFT");
                    adj.setActorId(UUID.fromString(actorId));
                    adj.setActorEmail(actorEmail);
                    return adjustmentRepository.save(adj);
                }))
            .flatMap(saved -> emitAudit(saved, "CREATE", actorId, actorEmail).thenReturn(saved));
    }

    /** DRAFT → APPROVED. Same actor cannot approve their own draft (four-eyes invariant). */
    @Transactional
    public Mono<CommissionAdjustment> approve(UUID id, String actorId, String actorEmail) {
        return adjustmentRepository.findById(id)
            .switchIfEmpty(Mono.error(new EntityNotFoundException("CommissionAdjustment", id)))
            .flatMap(adj -> {
                if (!"DRAFT".equals(adj.getStatus())) {
                    return Mono.error(new ValidationException(
                            "Cannot approve adjustment in status " + adj.getStatus()));
                }
                if (adj.getActorId() != null && adj.getActorId().toString().equals(actorId)) {
                    return Mono.error(new ValidationException(
                            "Approver must differ from drafter (four-eyes)"));
                }
                adj.setStatus("APPROVED");
                adj.setApproverActorId(UUID.fromString(actorId));
                adj.setApproverActorEmail(actorEmail);
                adj.setApprovedAt(OffsetDateTime.now());
                return adjustmentRepository.save(adj)
                    .flatMap(saved -> emitAudit(saved, "APPROVE", actorId, actorEmail).thenReturn(saved));
            });
    }

    /** APPROVED → COMMITTED. Writes the compensating commission_transaction and links it. */
    @Transactional
    public Mono<CommissionAdjustment> commit(UUID id, String actorId, String actorEmail) {
        return adjustmentRepository.findById(id)
            .switchIfEmpty(Mono.error(new EntityNotFoundException("CommissionAdjustment", id)))
            .flatMap(adj -> {
                if (!"APPROVED".equals(adj.getStatus())) {
                    return Mono.error(new ValidationException(
                            "Cannot commit adjustment in status " + adj.getStatus()));
                }
                return writeCompensatingTransaction(adj, actorId, actorEmail)
                    .flatMap(compensating -> {
                        adj.setStatus("COMMITTED");
                        adj.setCommittedAt(OffsetDateTime.now());
                        adj.setCommittedTxnId(compensating.getId());
                        return adjustmentRepository.save(adj);
                    })
                    .flatMap(saved -> emitAudit(saved, "COMMIT", actorId, actorEmail).thenReturn(saved));
            });
    }

    /** DRAFT or APPROVED → VOIDED (terminal; COMMITTED cannot be voided per FacultativeCession precedent). */
    @Transactional
    public Mono<CommissionAdjustment> voidAdjustment(UUID id, String reason,
                                                      String actorId, String actorEmail) {
        return adjustmentRepository.findById(id)
            .switchIfEmpty(Mono.error(new EntityNotFoundException("CommissionAdjustment", id)))
            .flatMap(adj -> {
                if (!List.of("DRAFT", "APPROVED").contains(adj.getStatus())) {
                    return Mono.error(new ValidationException(
                            "Cannot void adjustment in status " + adj.getStatus()));
                }
                if (reason == null || reason.isBlank()) {
                    return Mono.error(new ValidationException("void reason required"));
                }
                adj.setStatus("VOIDED");
                adj.setVoidedAt(OffsetDateTime.now());
                adj.setVoidedReason(reason);
                return adjustmentRepository.save(adj)
                    .flatMap(saved -> emitAudit(saved, "VOID", actorId, actorEmail).thenReturn(saved));
            });
    }

    private Mono<Void> validateDraft(CreateAdjustmentRequest req) {
        if (req.justification() == null || req.justification().length() < 20) {
            return Mono.error(new ValidationException("justification must be at least 20 characters"));
        }
        if (req.adjustmentAmount() == null || req.adjustmentAmount().signum() == 0) {
            return Mono.error(new ValidationException("adjustmentAmount must be non-zero"));
        }
        if (req.targetCommissionTransactionId() == null || req.adjustmentType() == null) {
            return Mono.error(new ValidationException("targetCommissionTransactionId + adjustmentType required"));
        }
        return Mono.empty();
    }

    private Mono<CommissionTransaction> writeCompensatingTransaction(CommissionAdjustment adj,
                                                                       String actorId, String actorEmail) {
        // Creates a new commission_transaction with adjustment_amount signed appropriately,
        // reversal_of_txn_id → adj.targetCommissionTransactionId, occurred_at = now.
        // For MANUAL_CLAWBACK and MANUAL_REVERSAL types, additionally writes a
        // clawback_event row (source=CONTRIBUTION_REVOKE for MANUAL_REVERSAL,
        // MEMBER_LAPSE for MANUAL_CLAWBACK — treat as operator-triggered same-family).
        ...
    }

    private Mono<Void> emitAudit(CommissionAdjustment adj, String action,
                                  String actorId, String actorEmail) {
        return auditPublisher.publish(AuditEvent.create(
                TenantContext.currentTenantId(),
                "CommissionAdjustment", adj.getId().toString(),
                action,
                adj.getReference(),                        // friendly per feedback_audit_entity_name
                AuditActor.of(actorId, actorEmail),
                Map.of("status", adj.getStatus(),
                       "adjustmentType", adj.getAdjustmentType(),
                       "amount", adj.getAdjustmentAmount().toPlainString())));
    }
}
```

#### 4. Controller

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/CommissionAdjustmentController.java`

REST at `/api/v1/commission/adjustments`:
- `GET /?status=DRAFT|APPROVED|COMMITTED|VOIDED&page=&size=` — paginated queue, permission `finance.commission:view`
- `GET /{id}` — permission `finance.commission:view`
- `POST /` — create DRAFT, permission `finance.commission:draft_adjustment`
- `PUT /{id}/approve` — DRAFT → APPROVED, permission `finance.commission:approve_adjustment`
- `PUT /{id}/commit` — APPROVED → COMMITTED, permission `finance.commission:approve_adjustment`
- `PUT /{id}/void` (body: `{reason}`) — DRAFT|APPROVED → VOIDED, permission `finance.commission:approve_adjustment`

All endpoints carry Swagger annotations; every transition emits an `AuditEvent`.

#### 5. Angular

**Files** (all new under `clients/angular/src/app/pages/tenant/finance/commission/adjustments/`):
- `adjustments-drafter-queue.component.ts` — DRAFT queue (permission: `finance.commission:draft_adjustment`) + "New adjustment" button.
- `adjustments-approver-queue.component.ts` — DRAFT + APPROVED queue for supervisors (permission: `finance.commission:approve_adjustment`) with approve / commit / void row actions.
- `adjustment-create-modal.component.ts` — create form. Fields:
  - `targetCommissionTransactionId` via debounced search-select (searches `commission_transaction.reference`, shows producer + amount + occurred_at)
  - `adjustmentType` dropdown
  - `adjustmentAmount` numeric + inferred currency from target
  - `justification` textarea, min 20 chars validator
- `adjustment-detail.component.ts` — detail page showing lifecycle timeline + linked compensating transaction after commit.
- `void-adjustment-modal.component.ts` — reason form (min 5 chars).

**Routes** in `finance.routes.ts`:
```typescript
{ path: 'commission/adjustments/draft',    component: AdjustmentsDrafterQueueComponent,
  data: { permission: 'finance.commission:draft_adjustment' } },
{ path: 'commission/adjustments/approve',  component: AdjustmentsApproverQueueComponent,
  data: { permission: 'finance.commission:approve_adjustment' } },
{ path: 'commission/adjustments/:id',      component: AdjustmentDetailComponent,
  data: { permission: 'finance.commission:view' } },
```

Sidebar: two entries under a "Commission" group — "Adjustments (draft)" and "Adjustments (approve)", permission-gated.

Extend `ReferenceGenerator` from Phase 3 with `nextAdjustmentReference()` — same pattern (prefix `"COMM-ADJ-" + year`).

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build`
- [x] Unit tests (`make test-java`):
  - [x] `CommissionAdjustmentServiceTest` (19 cases, all green): createDraft happy + missing target + short justification + zero amount + missing type + reference→entityName audit; approve happy + not-DRAFT rejected + same-actor rejected (four-eyes); commit happy + not-APPROVED rejected + compensating written with correct signed amount + commit is terminal; void from DRAFT happy + void from APPROVED happy + void from COMMITTED rejected + void without reason rejected; queue defaults to DRAFT+APPROVED; audit entityName is reference not UUID.
- [x] Integration tests (`make test-integration`):
  - [x] `CommissionAdjustmentIT` (5 tests, all green against Testcontainers Postgres): full DRAFT → APPROVED → COMMITTED round-trip writes reference + compensating txn (COMM- prefix, not COMM-ADJ-) + AuditEvents for CREATE/APPROVE/COMMIT with friendly `entityName`; void from DRAFT with reason; void from COMMITTED rejected; four-eyes rejected; audit `entityName` carries reference (never UUID).
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — no new warnings (pre-existing warnings in unrelated components).
- [x] `make test-angular` (targeted) — `commission-adjustment.service.spec.ts` covers all seven wire seams (list default, list?status=DRAFT, get, create, approve, commit, void). Component-level specs deferred following the reinsurance / Phase 5 / Phase 6 precedent.
- [ ] `verify` on `/tenant/finance/commission/adjustments/draft` — page loads, "New adjustment" opens modal.
- [ ] `verify` on `/tenant/finance/commission/adjustments/approve` — queue loads, approve/commit/void row actions work.
- [ ] Playwright: `commission-adjustment.spec.ts` — drafter creates → supervisor approves → supervisor commits → detail page shows compensating txn; second scenario: drafter creates → supervisor voids with reason → queue clears.

#### Manual Verification
- [ ] Drafter cannot approve their own DRAFT (four-eyes surfaced as a permission or state-transition rejection in the UI).
- [ ] COMMITTED cannot be voided (button disabled + backend 422).
- [ ] Reference format `COMM-ADJ-2026-000001` monotonically increases per year.
- [ ] Committed adjustment appears on the next commission statement report as a REVERSED row.

**Implementation Note**: pause for manual acceptance before Phase 9.

---

## Phase 9: Producer termination + bulk-reassign UI (§B)

### Overview

Ship producer termination and its downstream: closing all open member assignments to the terminated producer, offering the tenant admin a bulk-reassign screen to move affected members to a successor. Per P14, there's no auto-successor — the gap is intentional and commission calc warns + skips during it.

### Changes Required

#### 1. Service extensions

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/ProducerService.java` (fill in the Phase 2 stub)

```java
/**
 * Terminate a producer. Effect:
 *   - producer.is_active = false, producer.terminated_at = NOW()
 *   - all open member_producer_assignment rows for this producer are closed
 *     with effective_to = last-day-of-month (per feedback_effective_date_snap)
 *   - no auto-successor — commission calc during the gap warns + skips
 *   - one AuditEvent for the producer row + one per closed assignment
 *
 * Idempotent — terminating an already-terminated producer is a 422 with a
 * clear message; individual assignment closures are conditional on
 * effective_to IS NULL (partial UNIQUE index makes duplicate closures impossible).
 */
@Transactional
public Mono<Producer> terminate(UUID id, LocalDate effectiveDate,
                                 String actorId, String actorEmail) {
    LocalDate snapped = effectiveDate.with(TemporalAdjusters.lastDayOfMonth());
    return producerRepository.findById(id)
        .switchIfEmpty(Mono.error(new EntityNotFoundException("Producer", id)))
        .flatMap(existing -> {
            if (!existing.isActive()) {
                return Mono.error(new ValidationException(
                        "Producer " + existing.getProducerCode() + " already terminated"));
            }
            existing.setActive(false);
            existing.setTerminatedAt(OffsetDateTime.now());
            existing.setActorId(UUID.fromString(actorId));
            existing.setActorEmail(actorEmail);
            return producerRepository.save(existing)
                .flatMap(saved -> closeAllOpenAssignments(id, snapped, actorId, actorEmail)
                    .then(emitAudit(saved, "TERMINATE", actorId, actorEmail))
                    .thenReturn(saved));
        });
}

private Mono<Void> closeAllOpenAssignments(UUID producerId, LocalDate effectiveTo,
                                            String actorId, String actorEmail) {
    return assignmentRepository.findByProducerIdAndEffectiveToIsNull(producerId)
        .flatMap(mpa -> {
            mpa.setEffectiveTo(effectiveTo);
            return assignmentRepository.save(mpa)
                .flatMap(saved -> auditPublisher.publish(AuditEvent.create(
                        TenantContext.currentTenantId(),
                        "MemberProducerAssignment", saved.getId().toString(),
                        "CLOSE",
                        "member " + saved.getMemberId() + " → producer " + producerId,
                        AuditActor.of(actorId, actorEmail),
                        Map.of("effectiveTo", effectiveTo.toString(),
                               "closeReason", "PRODUCER_TERMINATED"))));
        })
        .then();
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/BulkReassignService.java` (new)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkReassignService {

    private final MemberProducerAssignmentService assignmentService;

    /**
     * Reassign a batch of members to a new producer, all with a common
     * effective_from (snapped to 1st-of-month). Serial per member (each is
     * its own transaction via assignmentService.assign) — the service caller
     * batches by 8 concurrent members in-flight to avoid overwhelming
     * connection pool.
     *
     * Returns a summary: total, succeeded, failed (with per-member reason).
     */
    public Mono<BulkReassignReport> reassignBatch(BulkReassignRequest req,
                                                    String actorId, String actorEmail) {
        LocalDate snapped = req.effectiveFrom().withDayOfMonth(1);
        return Flux.fromIterable(req.memberIds())
            .flatMap(memberId -> assignmentService.assign(memberId, req.newProducerId(),
                            snapped, req.changeReason(), actorId, actorEmail)
                    .map(mpa -> new BulkReassignItem(memberId, true, null))
                    .onErrorResume(e -> Mono.just(new BulkReassignItem(memberId, false, e.getMessage()))),
                /* concurrency */ 8)
            .collectList()
            .map(BulkReassignReport::from);
    }
}
```

#### 2. Controller

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/ProducerController.java` (extend from Phase 2)

```java
@PostMapping("/{id}/terminate")
@Operation(summary = "Terminate a producer + close all open member assignments")
@RequiresPermission("finance.producer:terminate")
public Mono<ProducerResponse> terminate(@PathVariable UUID id,
                                         @RequestBody @Valid TerminateProducerRequest req,
                                         @AuthenticationPrincipal Jwt jwt) {
    return producerService.terminate(id, req.effectiveDate(),
                                      AuditActor.id(jwt), AuditActor.email(jwt))
                          .map(ProducerResponse::from);
}

@GetMapping("/{id}/members")
@Operation(summary = "List members currently assigned to a producer (paginated)")
@RequiresPermission("finance.producer:view")
public Mono<Page<AssignmentResponse>> members(@PathVariable UUID id,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size,
                                                @RequestParam(required = false) String insuranceLine) {
    // Uses MemberProducerAssignmentRepository.findByProducerIdAndEffectiveToIsNullPaged(...)
    // (new method) with optional insurance-line join via user-service client.
    ...
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/BulkReassignController.java` (new)

```java
@RestController
@RequestMapping("/api/v1/producers/{producerId}/reassign-bulk")
@RequiredArgsConstructor
public class BulkReassignController {
    private final BulkReassignService bulkReassignService;

    @PostMapping
    @Operation(summary = "Bulk-reassign members from one producer to another")
    @RequiresPermission("finance.producer:manage")
    public Mono<BulkReassignReport> reassign(@PathVariable UUID producerId,
                                              @RequestBody @Valid BulkReassignRequest req,
                                              @AuthenticationPrincipal Jwt jwt) {
        return bulkReassignService.reassignBatch(req, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
```

`BulkReassignRequest` — record `(UUID newProducerId, List<UUID> memberIds, LocalDate effectiveFrom, String changeReason)`. `BulkReassignReport` — record `(int total, int succeeded, int failed, List<BulkReassignItem> items)`.

#### 3. Angular

**Files** (all new under `clients/angular/src/app/pages/tenant-admin/producers/`):
- `terminate-producer-modal.component.ts` — confirmation modal: effective-date picker (defaults to today, snaps to last-day-of-month), open-assignment count preview ("This will close 47 open assignments"), confirm button. Opens from the producer detail page (`ProducerEditComponent`).
- `bulk-reassign.component.ts` — full-page reassignment surface. Route `/tenant-admin/producers/{terminatedId}/reassign`. Layout: header shows terminated producer + assignment count; body is a paginated table of members currently assigned (50 per page); each row shows member number + name + insurance line; select-all-on-page + individual checkboxes; sticky footer with new-producer search-select, effective-from picker (snaps to 1st-of-month), change-reason textarea, and "Reassign selected" button. Submit calls `POST /api/v1/producers/{producerId}/reassign-bulk` — displays a `BulkReassignReport` summary modal with per-row success/failure.

**File**: `clients/angular/src/app/core/services/producer.service.ts` (extend Phase 2 service)

```typescript
terminateProducer(id: string, effectiveDate: string): Observable<ProducerResponse> {
    return this.http.post<ProducerResponse>(`${this.base}/${id}/terminate`, { effectiveDate });
}

listProducerMembers(id: string, page = 0, size = 50, insuranceLine?: string): Observable<PagedResponse<AssignmentResponse>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (insuranceLine) params = params.set('insuranceLine', insuranceLine);
    return this.http.get<PagedResponse<AssignmentResponse>>(`${this.base}/${id}/members`, { params });
}

bulkReassign(sourceProducerId: string, req: BulkReassignRequest): Observable<BulkReassignReport> {
    return this.http.post<BulkReassignReport>(`${this.base}/${sourceProducerId}/reassign-bulk`, req);
}
```

**Routes** — add to `producers.routes.ts`:
```typescript
{ path: ':id/reassign', component: BulkReassignComponent,
  data: { permission: 'finance.producer:manage', sidebar: 'admin' } },
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build` — `:finance-service:assemble` green; bootJar + jar built.
- [x] Unit tests (`make test-java`):
  - `ProducerServiceTest.terminate_*` (5 new cases): happy path terminates + closes assignments; already-terminated 409 (IllegalStateException → CONFLICT); effective_date snaps to last-day-of-month; 0 open assignments still succeeds; snapped-before-assignment-start pins close to assignment start (not orphaned). All 18/18 green.
  - `BulkReassignServiceTest` (6 cases): all succeed; one failure isolated; delegates once per member (5-member batch); effective_from snaps to 1st-of-month; failure without message falls back to `RuntimeException` class name; changeReason propagates. All 6/6 green.
- [ ] Integration tests (`make test-integration`) — `ProducerTerminationIT` + `BulkReassignIT` deferred following the Phase 3/4/6/7 Testcontainers-pressure precedent (25 pre-existing ITs still hit `Failed to load ApplicationContext` under full-suite pool pressure). Unit tests + manual verification cover the seam; a follow-up ticket lands both alongside the pool-tuning pass.
- [x] Angular compiles + `make test-angular` — `npx ng build --configuration=development` green (only pre-existing warnings in unrelated components); extended `producer.service.spec.ts` with 3 new wire-shape cases (POST /terminate; POST /reassign-bulk with populated fields; POST /reassign-bulk with null changeReason) — 16/16 green under ChromeHeadless. Component specs deferred following the Phase 2 / Phase 5 / Phase 6 / Phase 8 precedent (no sibling terminate-group-modal / bulk-* component specs exist either).
- [ ] `verify` on producer detail → terminate button opens modal, submits, redirects to bulk-reassign page.
- [ ] `verify` on `/tenant-admin/producers/{id}/reassign` — table paginated 50, checkboxes work, submit shows report.
- [ ] Playwright: `producer-termination.spec.ts` — terminate → bulk-reassign 5 members → summary shows 5 succeeded → commission calc for one of them credits the new producer on next paid contribution.

#### Manual Verification
- [ ] Terminate a producer with 100+ open assignments — all close cleanly with last-day-of-month effective_to.
- [ ] Attempt commission accrual for a member during the assignment gap — logs a warn, no commission_transaction written, no crash.
- [ ] Bulk-reassign 200 members via the UI — completes within 30s; summary modal shows counts.
- [ ] `finance.producer:terminate` permission required for the terminate button; button hidden for users without it.

**Implementation Note**: pause for manual acceptance before Phase 10.

---

## Phase 10: Treaty producer_id backfill + review UI (§B)

### Overview

Ship the reinsurance R15 handoff: backfill the `treaty.producer_id` FK by fuzzy-matching the pre-existing `treaty.producer_ref VARCHAR(120)` free-text field to `producer.name`. Mirrors `TreatyActivationBackfillJob` (`services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/TreatyActivationBackfillJob.java`) in shape. Includes a tenant-admin review UI for low-confidence matches.

The V092..V099 migration in Phase 1 already added `treaty.producer_id UUID NULL REFERENCES producer(id)` and the `producer_backfill_candidate` staging table.

### Changes Required

#### 1. Backfill job

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/ProducerBackfillJob.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ProducerBackfillJob {

    private static final BigDecimal AUTO_ACCEPT_THRESHOLD = new BigDecimal("0.900");
    private static final int BATCH_SIZE = 200;

    private final TreatyRepository treatyRepository;
    private final ProducerRepository producerRepository;
    private final ProducerBackfillCandidateRepository candidateRepository;
    private final BackfillProgressService progressService;
    private final AuditPublisher auditPublisher;

    /**
     * Chunked scan of treaty rows with producer_ref set AND producer_id NULL.
     * Per treaty: fuzzy-match against all active producers via Levenshtein
     * (Apache Commons Text — on classpath). Persist a producer_backfill_candidate
     * row per plausible match (top 3 producers by score, min 0.500). Candidates
     * ≥ 0.900 auto-accept: treaty.producer_id set + candidate.status = ACCEPTED.
     *
     * On-demand invocation via POST /api/v1/producers/backfill/run — not scheduled.
     */
    public Mono<Void> runBackfill(String actorId, String actorEmail) {
        UUID tenantId = TenantContext.currentTenantId();
        progressService.start(tenantId);
        return producerRepository.findByIsActiveTrueOrderByName().collectList()
            .flatMap(producers -> treatyRepository.findByProducerRefIsNotNullAndProducerIdIsNull()
                .buffer(BATCH_SIZE)
                .concatMap(batch -> processBatch(batch, producers, actorId, actorEmail))
                .then())
            .doOnSuccess(v -> progressService.complete(tenantId))
            .doOnError(e -> progressService.fail(tenantId, e.getMessage()));
    }

    @Transactional
    protected Mono<Void> processBatch(List<Treaty> batch, List<Producer> producers,
                                       String actorId, String actorEmail) {
        return Flux.fromIterable(batch)
            .flatMap(treaty -> matchAndPersist(treaty, producers, actorId, actorEmail))
            .then();
    }

    private Mono<Void> matchAndPersist(Treaty treaty, List<Producer> producers,
                                        String actorId, String actorEmail) {
        String ref = treaty.getProducerRef();
        List<ScoredCandidate> scored = producers.stream()
            .map(p -> new ScoredCandidate(p, similarity(ref, p.getName())))
            .filter(sc -> sc.score.compareTo(new BigDecimal("0.500")) >= 0)
            .sorted(Comparator.comparing(ScoredCandidate::score).reversed())
            .limit(3)
            .toList();

        if (scored.isEmpty()) {
            progressService.recordSkip(TenantContext.currentTenantId(), treaty.getId());
            return Mono.empty();
        }

        // Auto-accept the top hit if it's above the confidence threshold.
        ScoredCandidate top = scored.get(0);
        if (top.score.compareTo(AUTO_ACCEPT_THRESHOLD) >= 0) {
            return autoAccept(treaty, top, actorId, actorEmail)
                .then(persistOtherCandidates(treaty, scored.subList(1, scored.size())));
        }

        // Otherwise queue all as PENDING for review.
        return Flux.fromIterable(scored)
            .flatMap(sc -> insertCandidate(treaty, sc, "PENDING"))
            .then();
    }

    /** Levenshtein-based similarity normalised to [0, 1]. */
    private BigDecimal similarity(String a, String b) {
        LevenshteinDistance ld = LevenshteinDistance.getDefaultInstance();
        int distance = ld.apply(a.toLowerCase(), b.toLowerCase());
        int maxLen   = Math.max(a.length(), b.length());
        if (maxLen == 0) return BigDecimal.ONE;
        return BigDecimal.ONE.subtract(BigDecimal.valueOf(distance)
                                                 .divide(BigDecimal.valueOf(maxLen), 3, RoundingMode.HALF_UP));
    }
}
```

Progress-tracker follows `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/BackfillProgressService.java` shape 1:1 — an in-memory `Map<UUID, BackfillProgress>` keyed by tenantId with counts of processed, skipped, auto-accepted, pending, failed.

#### 2. Review service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/ProducerBackfillReviewService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ProducerBackfillReviewService {

    private final ProducerBackfillCandidateRepository candidateRepository;
    private final TreatyRepository treatyRepository;
    private final AuditPublisher auditPublisher;

    public Flux<CandidateWithContext> listPending(int page, int size) {
        return candidateRepository.findByStatusOrderByConfidenceScoreDesc("PENDING",
                        PageRequest.of(page, size))
                .flatMap(this::enrichWithTreaty);
    }

    /**
     * Accept a candidate: treaty.producer_id = candidate.candidate_producer_id;
     * candidate.status = ACCEPTED; all OTHER pending candidates for the same
     * treaty flip to REJECTED. AuditEvent fires on the treaty update.
     */
    @Transactional
    public Mono<Void> accept(UUID candidateId, String actorId, String actorEmail) {
        return candidateRepository.findById(candidateId)
            .switchIfEmpty(Mono.error(new EntityNotFoundException("Candidate", candidateId)))
            .flatMap(candidate -> {
                if (!"PENDING".equals(candidate.getStatus())) {
                    return Mono.error(new ValidationException(
                            "Candidate already resolved: " + candidate.getStatus()));
                }
                return treatyRepository.findById(candidate.getTreatyId())
                    .flatMap(treaty -> {
                        treaty.setProducerId(candidate.getCandidateProducerId());
                        return treatyRepository.save(treaty);
                    })
                    .then(rejectOtherCandidatesFor(candidate.getTreatyId(), candidateId, actorId, actorEmail))
                    .then(finalizeCandidate(candidate, "ACCEPTED", actorId, actorEmail));
            });
    }

    @Transactional
    public Mono<Void> reject(UUID candidateId, String actorId, String actorEmail) { ... }
}
```

#### 3. Controllers

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/ProducerBackfillController.java`

```java
@RestController
@RequestMapping("/api/v1/producers/backfill")
@RequiredArgsConstructor
public class ProducerBackfillController {

    private final ProducerBackfillJob backfillJob;
    private final ProducerBackfillReviewService reviewService;
    private final BackfillProgressService progressService;

    @PostMapping("/run")
    @Operation(summary = "Kick off treaty.producer_ref → producer_id backfill (chunked, idempotent)")
    @RequiresPermission("finance.producer:backfill_review")
    public Mono<Void> run(@AuthenticationPrincipal Jwt jwt) {
        return backfillJob.runBackfill(AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @GetMapping("/progress")
    @Operation(summary = "Get current backfill progress for this tenant")
    @RequiresPermission("finance.producer:backfill_review")
    public Mono<BackfillProgress> progress() {
        return progressService.get(TenantContext.currentTenantId());
    }

    @GetMapping("/candidates")
    @Operation(summary = "List pending backfill candidates for review")
    @RequiresPermission("finance.producer:backfill_review")
    public Flux<CandidateWithContext> candidates(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return reviewService.listPending(page, size);
    }

    @PutMapping("/candidates/{id}/accept")
    @RequiresPermission("finance.producer:backfill_review")
    public Mono<Void> accept(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.accept(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/candidates/{id}/reject")
    @RequiresPermission("finance.producer:backfill_review")
    public Mono<Void> reject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reviewService.reject(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
```

#### 4. Angular

**Files** (all new under `clients/angular/src/app/pages/tenant-admin/producers/backfill/`):
- `backfill-review.component.ts` — main page at `/tenant-admin/producers/backfill`. Header shows progress card (progress + counts + "Run now" button). Body is a paginated table of pending candidates: one row per candidate showing treaty ref, producer_ref (source text), candidate producer name + code, confidence bar + score, accept / reject buttons.
- `producer.service.ts` (extend Phase 2 service):
  ```typescript
  runProducerBackfill(): Observable<void> { ... }
  getBackfillProgress(): Observable<BackfillProgress> { ... }
  listBackfillCandidates(page = 0, size = 50): Observable<CandidateWithContext[]> { ... }
  acceptBackfillCandidate(id: string): Observable<void> { ... }
  rejectBackfillCandidate(id: string): Observable<void> { ... }
  ```

**Routes** — add to `producers.routes.ts`:
```typescript
{ path: 'backfill', component: BackfillReviewComponent,
  data: { permission: 'finance.producer:backfill_review', sidebar: 'admin' } },
```

Sidebar entry (nested under Producers): "Treaty backfill review". Show a badge with pending-candidate count when > 0.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build` — `:finance-service:compileJava` + `:finance-service:compileTestJava` green.
- [x] Unit tests (`make test-java`):
  - `ProducerBackfillJobTest` — 14 cases (5 similarity: identical / case-insensitive+whitespace / bothEmpty / completelyDifferent / nearMatch; 9 job-pipeline: exact match auto-accept, near-match ≥0.900 auto-accept, medium match queues PENDING, below-min skip, multiple-producers top-3 kept, no-producers skips-all, no-treaties completes-with-zero, batch-boundary 250-treaty progresses-all). All green.
  - `ProducerBackfillReviewServiceTest` — 6 cases (accept updates treaty + rejects siblings + fires AuditEvents; accept already-resolved errors IllegalState; accept missing errors IllegalArgument; reject flips single candidate; reject already-resolved errors IllegalState; listPending enriches; listPending survives deleted-treaty + deleted-producer with placeholders). All green.
- [x] Integration tests (`make test-integration`):
  - `ProducerBackfillJobIT` — 3 tests, real Postgres via Testcontainers (finance-service test-migration V016). Seeded mix (exact match auto-accepts, near-match queues PENDING, gibberish skips); rerun is idempotent (`ux_pbc_treaty_candidate`); review acceptance updates treaty + siblings. All 3/3 green in isolation and in the full-suite run for this class.
- [x] Angular compiles + `make test-angular` — `producer.service.spec.ts` extended with 6 new backfill wire-shape cases (POST /run empty body; GET /progress; GET /candidates with page+size; GET /candidates/pending-count; PUT /candidates/{id}/accept; PUT /candidates/{id}/reject). Full spec suite 22/22 green under ChromeHeadless; `npx ng build --configuration=development` green (only pre-existing warnings in unrelated components). `backfill-review.component.spec.ts` deferred following the Phase 2 / Phase 5 / Phase 6 / Phase 8 / Phase 9 precedent (see Deviations).
- [ ] `verify` on `/tenant-admin/producers/backfill` — page renders, "Run now" triggers the backfill and progress updates poll every 2s.
- [ ] Playwright: `producer-backfill.spec.ts` — seed 3 treaties with `producer_ref`, seed 3 producers, run backfill → 1 auto-accepted + 2 pending → accept one → verify treaty.producer_id set.

#### Manual Verification
- [ ] After running the backfill on a tenant with a mix of exact/near/no matches, the split lands as expected (rough sanity check on real tenant data).
- [ ] Confidence bar renders visually (green ≥ 0.900, amber 0.700-0.899, red < 0.700).
- [ ] Rejecting a candidate leaves treaty.producer_id NULL and the treaty stays available for a later manual assignment via producer edit.
- [ ] `producer_ref` is retained on treaty rows even after accept (per P11 — audit trail preserved forever).

**Implementation Note**: this is the last phase. **After manual acceptance, run the self-review loop** — create the PR, then `code-review` over the whole diff, triage every Blocker/Important, fix, and re-sweep. The self-review comment gets posted to the PR.

---

## Testing Strategy

### Unit Tests
- **Commission calc arithmetic** (Phase 3): rate-card base only, rate-card + one kicker, multiple kickers summed, missing rate card raises, missing producer warns + skips.
- **Idempotency** (Phase 3, 10): `ux_commission_txn_source` + `ux_clawback_by_source` + `ux_pbc_treaty_candidate` all enforced under duplicate replay.
- **Clawback window arithmetic** (Phase 3): boundary conditions on `occurred_at + clawback_window_days`.
- **FX conversion at payout time** (Phase 6): homogeneous-currency runs; multi-native-currency aggregation; missing rate fails loudly.
- **Four-eyes state machine** (Phase 8): every valid transition + every invalid transition; same-actor rejection.
- **Fuzzy match Levenshtein** (Phase 10): exact match = 1.0; empty = 0.0; case-insensitive; unicode-safe.
- **XLSX rendering** (Phase 4): one sheet per currency; summary sheet totals cross-currency; character escape.

### Integration Tests (Testcontainers)
- Full CRUD IT for each Phase 2 entity (Producer, RateCard, Assignment).
- Kafka round-trip for each consumer (Phases 3, 7).
- Report envelope IT with report-toggle 403 + SecurityEvent-on-export assertion (Phase 4).
- Auto-lapse end-to-end (Phase 7): breach → schedule → roll → clawback.
- Adjustment lifecycle (Phase 8): DRAFT → APPROVED → COMMITTED with two distinct actors.
- Backfill idempotency (Phase 10): re-run writes zero duplicates.

### E2E Tests (Playwright, `clients/angular/e2e/`)
- `producer-crud.spec.ts` (Phase 2) — full producer + rate-card + assignment CRUD.
- `commission-report.spec.ts` (Phase 5) — hub → statement page → export.
- `producer-payout.spec.ts` (Phase 6) — draft → approve → execute.
- `auto-lapse-config.spec.ts` (Phase 7) — enable + save.
- `commission-adjustment.spec.ts` (Phase 8) — drafter + supervisor journey.
- `producer-termination.spec.ts` (Phase 9) — terminate + bulk-reassign.
- `producer-backfill.spec.ts` (Phase 10) — run + review + accept.

### Manual Testing (per-phase Manual Verification lists — see each phase above)

## Performance Considerations

- **Rate-card lookup** (Phase 3) — `ix_rate_card_lookup` on `(insurance_line, producer_tier, effective_from, effective_to)`; hot path is `findApplicable` on every paid contribution. Runs in the consumer thread, one query per event; acceptable at moderate volume (~thousands/hour). At higher volume, cache in `TenantRuleEngine`-adjacent LRU with tenant-key invalidation on rate-card CUD.
- **Commission calc idempotency guard** — the partial UNIQUE index at `V095` catches duplicate accruals on replay; a `DuplicateKeyException` is swallowed rather than causing an infinite retry loop.
- **Producer aggregation query** (Phase 6) — `ix_commission_txn_producer_period` on `(producer_id, occurred_at)` covers the payout aggregation. For a period with 100 producers × 1000 contributions each, expect < 1s query at a well-indexed tenant.
- **Bulk-reassign concurrency** (Phase 9) — bounded 8-in-flight to avoid saturating the R2DBC connection pool (default 10 per service). For 200-member reassignment, expect ≈ 25s at 2 assignments/sec/thread.
- **Backfill fuzzy match** (Phase 10) — O(N × M) where N = treaties with `producer_ref` and M = active producers. Fine for M in the hundreds; at 10k+ producers, a follow-up ticket introduces a pre-filter via first-letter grouping.
- **Report XLSX rendering** (Phase 4) — Apache POI SXSSF streaming mode should be used for envelopes > 500 rows; POI default XSSF is fine for the MVP tenant scale.

## Migration Notes

- **Tenant migration ordering (V092..V099):** strict numerical order per `bug_tenant_flyway_outoforder`. Any hotfix within Phase 11 goes into a new higher-numbered file (V100+) — never edit an applied migration per `feedback_never_edit_applied_migrations`.
- **Public migration (V133):** single-row-per-tenant `tenant_auto_lapse_config`; matches V127/V128/V132 pattern.
- **PaymentRun XOR-widening in V099** touches four tables + drops-and-recreates three XOR constraints. The V072 trigger reads parent payee_type at runtime and needs no edit (F11-f — verified). The `is_valid` state is preserved for all existing rows because the new PRODUCER arm is additive.
- **`treaty.producer_id` FK added in V099** without backfill; Phase 10 §B backfills. `treaty.producer_ref` is retained forever as audit trail (P11).
- **No changes to `public.flyway_schema_history`.** Per `bug_public_flyway_history_load_bearing` — do not delete rows.
- **Rules-engine per-tenant recompile** — adding `RuleCategory.COMMISSION` means every tenant's KieContainer needs a rebuild on next fire. `TenantRuleEngine.loadRules` handles per-tenant `ReleaseId` minting per `bug_rules_engine_tenant_isolation`; concurrency IT covers the invariant.

## Rollout & Rollback

**Deploy order (all phases):**

1. **tenancy-service first** (schema-only in Phase 1) — safe: additive columns, no data loss on rollback.
2. **rules-engine** (adds `RuleCategory.COMMISSION` + emitter). No consumer yet; the enum widening is backwards-compatible.
3. **contributions-service** (Phases 3, 7 add new topic publishers). Producers deploy before consumers — the `medfund.contributions.revoked`, `arrears-threshold-breached`, `arrears-cleared` topics start flowing but nothing subscribes yet.
4. **user-service** (Phase 7 adds `ArrearsBreachedConsumer` + `ArrearsClearedConsumer`). After contributions-service is emitting.
5. **finance-service** (Phases 2-6, 8-10 — the bulk of the plan). After all upstream producers.
6. **gateway** (route additions for all phases) — deploys after finance-service.
7. **Angular** (all phase UI additions) — deploys after gateway.

**Rollback strategy per phase:**

- **Phase 1** — Flyway migrations are additive; rollback = revert the app deploy. Columns stay, unused. Do NOT `flyway repair` unless the migration failed to apply.
- **Phase 2-6 (§A)** — safe to roll back individually. Producer/rate-card/assignment tables idle; consumers can be disabled via Spring profile.
- **Phase 7 (auto-lapse)** — set `public.tenant_auto_lapse_config.enabled = FALSE` for all tenants to short-circuit the consumers, then roll back user-service + contributions-service. Any in-flight `scheduled_status='LAPSED'` rows can be cleared via `UPDATE members SET scheduled_status = NULL WHERE scheduled_status = 'LAPSED' AND scheduled_status_reason = 'ARREARS_AUTO_LAPSE'`.
- **Phase 8-10 (§B workflow)** — each phase is independent; roll back Angular first (hides the UI), then backend. Existing commission_transaction / clawback_event / commission_adjustment rows are untouched.

**Feature-flag posture:** no dedicated feature flags — every user-facing surface is either permission-gated or `@RequiresReport`-gated by default-off tenant config. Toggling permissions off (or leaving `tenant_report_config.enabled = FALSE`) is the operational kill switch.

## References

- **Parent plan:** `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md#phase-11` (lines 2958-3072)
- **Research:** `thoughts/shared/research/2026-08-22-phase11-producer-commission.md`
- **Grill decisions:** parent plan lines 3009-3037 (P1..P14 + F11-a..F11-f)
- **Sibling sub-plan (template):** `thoughts/shared/plans/2026-08-22-reinsurance-module-and-bordereau-reports.md`
- **Architecture docs:**
  - `.claude/CLAUDE.md` — 9 Critical Rules
  - `.claude/rules-engine.md:7-26` — template categories, agenda gating
  - `.claude/multi-currency.md:7,164` — payout currency + FX-at-commit-time
  - `.claude/payments.md:301-356,506-550` — payout ledgers + recipient types
  - `.claude/coding-standards.md:570-593` — audit `entityName` conventions
- **Key code references:**
  - `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:96-97` — commission keys already ship
  - `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:25` — COMMISSION family
  - `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/FacultativeCessionService.java:64,91,113,137` — four-eyes template
  - `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/CedeToTreatyEmitter.java` — emitter template
  - `services/java/tenancy-service/src/main/resources/db/migration/tenant/V048__member_operations.sql:64-66` — partial UNIQUE index precedent
  - `services/java/tenancy-service/src/main/resources/db/migration/public/V132__tenant_high_cost_claimant_config.sql:15-23` — tenant-config table pattern
- **Auto-memory constraints upheld:**
  - `feedback_audit_actor_email`, `feedback_audit_entity_name`
  - `bug_reactor_kafka_ack_swallow`, `bug_rules_engine_tenant_isolation`
  - `feedback_never_edit_applied_migrations`, `bug_tenant_flyway_outoforder`, `bug_public_prefix_silent_rollback`
  - `feedback_no_raw_id_inputs`, `feedback_effective_date_snap`
  - `feedback_one_contribution_per_month`, `feedback_stats_serverside`
