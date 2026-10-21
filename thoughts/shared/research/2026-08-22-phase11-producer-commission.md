---
date: 2026-08-22T00:00:00Z
researcher: Methuseli
git_commit: e6907ece579a9fe972aeb05a85226c2ad0f233b9
branch: rename-adjustments-to-notes
repository: medfund
topic: "Phase 11 — Producer / Broker Module + Commission Reports (financial-reporting-suite)"
tags: [research, codebase, ticket-mode, phase-11, finance-service, commission, producer, broker, rules-engine, kafka, angular]
status: complete
last_updated: 2026-08-22
last_updated_by: Methuseli
---

# Research: Phase 11 — Producer / Broker Module + Commission Reports

**Date**: 2026-08-22 · **Researcher**: Methuseli · **Commit**: `e6907ec` · **Branch**: rename-adjustments-to-notes

## Research Question

Phase 11 of `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:2958-2973` is documented at outline depth only. Before it can be grilled + planned it needs a codebase audit: current Kafka contracts the calc will consume, existing member↔producer linkage (none is expected), reusable patterns from the just-shipped reinsurance module, the correct home service for the module, whether commission calc should ride the rules-engine or a domain service, clawback semantics against the existing lapse flow, currency handling per the platform's conventions, payout mechanism (extend PaymentRun or new pipeline), report gating in the shared enum, duplicate-scaffolding scan, tenant-admin Angular CRUD conventions, and auto-memory constraints Phase 11 has to honor upfront.

## Summary

**Phase 11 is truly greenfield.** No `Producer`, `Broker`, `Commission`, `Clawback`, or `Agent` entity exists anywhere in Java, Angular, Go, or Python. Only one placeholder: `treaty.producer_ref VARCHAR(120)` at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V082__treaty.sql:23`, written but unread today — Phase 11 backfills it with a real `producer_id UUID FK` per reinsurance R15.

**Report catalogue is pre-wired.** `ReportKey.COMMISSION_STATEMENT` and `ReportKey.COMMISSION_CLAWBACK` already ship at `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:96-97`, and `ReportFamily.COMMISSION` at `.../ReportFamily.java:25`. Phase 11 does **not** add enum values — it adds controllers gated on the existing keys.

**Recommended home: finance-service, `com.medfund.finance.producer.*`.** Mirrors the reinsurance-sub-plan §R3 precedent (subpackage of finance-service; same tenant-schema locality; same Kafka topology proximity). `medfund.contributions.paid` is already consumed only by finance-service (`ReinsurancePremiumCessionConsumer`), so a `ProducerCommissionConsumer` is a same-JVM cohabitant with no new cross-service HTTP hop.

**Commission calc should ride the rules-engine as a new `RuleCategory.COMMISSION`.** Precedent: Phase 10 added `RuleCategory.REINSURANCE` at `RuleCategory.java:68` with a `CEDE_TO_TREATY` action emitter and an agenda-group gate. The identical pattern (new category + agenda-gated + a `PAY_COMMISSION` action emitter + a `ProducerCommissionTemplates` provider) drops in cleanly. Critical Rule 5 is explicit that tenant-varying business rules go through the rules-engine — commission rate cards (per line, per producer tier, per underwriting year, per policy age, sliding scale) are the textbook case.

**PaymentRun cannot be extended in-place for producer payout.** `payment_runs.payee_type` today accepts only `PROVIDER` and `MEMBER` (`services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRun.java:32-34`) with V072 trigger enforcing homogeneous items. Producer payout adds a third payee-type — either an additive migration widens the enum + trigger, or a new lightweight `producer_payout_run` table piggybacks the same four-eyes shape. The grill needs to pick.

**Clawback trigger event exists.** `medfund.users.member-lifecycle` (event `MEMBER_STATUS_CHANGED`) is emitted at `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java:89-103` on every explicit status transition. Clawback is a same-topic consumer that scans producer commissions in the clawback window for the affected member. **Auto-lapse is not implemented** — there is no scheduler that lapses non-paying members today; every lapse today is operator-driven. That is a Phase-11-adjacent Decision (bundle auto-lapse into 11, or rely on operator transitions).

**Cross-currency: store native, convert at report time.** Same pattern as reinsurance R7 (`FxRateReader.findRate(base, quote, asOf, tenantId)` at `.../FxRateReader.java:51`, best-effort with `warnings[]` on missing rate). Commission transaction row holds native amount + native currency; the statement export converts to the producer's home currency or the tenant's reporting currency at export time.

**Scheduled email delivery infrastructure exists.** `services/java/shared/src/main/java/com/medfund/shared/scheduler/` ships `ScheduledJobConfig`, `ScheduledJobService`, `JobDispatcher`, `JobExecutor`, plus V114–V116 + V121 public migrations. Phase 17 wires commission-statement scheduled delivery on top; Phase 11 does not need to touch it.

## Findings

### Service: contributions-service — `medfund.contributions.paid` event contract

Producer class + method + topic + payload:

- File `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ContributionEventPublisher.java:50-63`
  ```java
  public Mono<Void> publishContributionPaid(String contributionId, String memberId, String amount,
                                            String currencyCode, String insuranceLine,
                                            String paidAt, String tenantId)
  return publishEvent("medfund.contributions.paid", contributionId, fields);
  ```
- **Payload (final Phase 6-extended shape):** `event="CONTRIBUTION_PAID"`, `contributionId`, `memberId`, `amount`, `currencyCode`, `insuranceLine`, `paidAt`, `tenantId`.
- **Consumers today:** only `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/consumer/ReinsurancePremiumCessionConsumer.java:38` (topic constant hardcoded on both producer and consumer — **no shared `TopicConstants` class**).
- **What's missing for commission calc:** no `producerId` on payload; commission calc must lookup `member → producer` at consume time. Two options for the grill: (a) enrich the event with `producerId` at publish time (extend `ContributionEventPublisher` and every existing subscriber tolerates additive fields); (b) leave the event thin and lookup via a repository call inside the consumer.

### Service: user-service — Member lifecycle events + no `MemberStatus` enum

- **`Member.status` is `String`**, not an enum: `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:51`. String literals observed in grep: `"active"`, `"suspended"`, `"terminated"`, `"deactivated"`, `"enrolled"`. Values are stored as passed by callers.
- **Scheduled status roll (V042):** `Member` carries `scheduledStatus`, `scheduledStatusEffectiveFrom`, `scheduledStatusReason` at `Member.java:69-76`. A daily `SCHEDULED_STATUS_ROLL` job rolls future-dated status changes; not the same as an "auto-lapse on arrears" job — that does not exist.
- **Suspend reason tracking (V043):** `suspendReason` at `Member.java:86-87` — cleared on return to active; distinguishes operator vs arrears-escalation suspend.
- **`MemberStatusHistory` does NOT exist as a dedicated history table.** History is implicit in the `medfund.users.member-lifecycle` event stream.
- **Lapse trigger:** every lapse today is operator-driven (POST /members/{id}/deactivate, or the SCHEDULED_STATUS_ROLL for future-dated transitions). No arrears-driven auto-lapse scheduler across user/contributions/finance services.

**All `medfund.users.*` topics (from `UserEventPublisher.java`):**

| Topic | Line | Event name | Key fields |
|---|---|---|---|
| `medfund.users.member-enrolled` | 34-49 | `MEMBER_ENROLLED` | memberId, memberNumber, groupId, schemeId, enrollmentDate, dateOfBirth |
| `medfund.users.dependant-enrolled` | 62-79 | `DEPENDANT_ENROLLED` | dependantId, memberNumber, memberId, groupId, schemeId, enrollmentDate, dateOfBirth |
| `medfund.users.member-lifecycle` | 89-103 | `MEMBER_STATUS_CHANGED` | tenantId, memberId, status, reason, terminationDate, groupId, schemeId |
| `medfund.users.group-lifecycle` | 113-122 | `GROUP_STATUS_CHANGED` | tenantId, groupId, status, reason |
| `medfund.users.member-changed` | 141-159 | `MEMBER_CHANGED` | tenantId, memberId, changeKind, oldValue, newValue, effectiveDate, backdated, actorId, actorEmail |
| `medfund.users.provider-onboarded` | 161-167 | — | — |
| `medfund.users.role-assigned` | 169-176 | — | — |
| `medfund.permissions.invalidated` | 191 | — | — |

**Existing consumer of `medfund.users.member-lifecycle`:** `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/MemberLifecycleConsumer.java:49` — precedent for how to structure a `ProducerCommissionLapseConsumer` in finance-service.

### Service: user-service + contributions-service — no member↔producer linkage today

- **Zero hits** across `services/java/user-service/src/main/java/com/medfund/user/entity/*` (Member, Dependant, HealthPolicy, LifePolicy, FuneralPolicy, DisabilityPolicy, TravelPolicy, Vehicle, Property) for `producer`, `broker`, `agent`, `intermediary`, `producerId`, `brokerId`, `agentId`.
- **Zero hits** across `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/*` (Contribution).
- **Zero hits** across `services/java/shared/src/main/java/com/medfund/shared/*` (domain layer).
- **Migration path** the grill needs to lock: (a) add nullable `producer_id UUID` on `members` table (per-member producer allocation), (b) add on `policies` (per-policy producer), or (c) introduce a join table `member_producer_mapping (member_id, producer_id, effective_from, effective_to)` (time-slice-aware, matches the person-centric + asset-centric line split at `InsuranceLine.isPersonCentric()`).

### Service: finance-service — reinsurance module layout (Phase 11 template)

Package `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/*`:

| Sub | Classes (count) | Notable |
|---|---|---|
| `entity/` | 10 | `Treaty`, `TreatyLayer`, `TreatyParticipant`, `TreatyApplicableLine`, `Reinsurer`, `CessionRule`, `Cession`, `Recovery`, `BordereauPeriodExport`, `ReinsuranceReviewTask` |
| `repository/` | 11 | Standard `R2dbcRepository<T,UUID>` per entity + one custom `BordereauQueryRepository` for the report join |
| `service/` | 16 | `TreatyService`, `TreatyValidationService`, `TreatyLayerService`, `TreatyParticipantService`, `TreatyApplicableLineService`, `ReinsurerService`, `CessionService`, `FacultativeCessionService`, `PremiumCessionService`, `CessionRuleService`, `RecoveryService`, `BordereauReportService`, `BordereauReportWorkbookService`, `ReinsuranceReviewTaskService`, `TreatyActivationBackfillJob`, `BackfillProgressService` |
| `controller/` | 10 | Standard REST controllers, plus `BordereauReportController` for the three report endpoints |
| `consumer/` | 2 | `ReinsuranceLossCessionConsumer` (medfund.claims.adjudicated), `ReinsurancePremiumCessionConsumer` (medfund.contributions.paid) |
| `dto/` | ~37 | Request/response records |
| `job/` | 1 | `ReinsuranceTreatyPremiumExecutor` (scheduled inception cession) |

**Four-eyes workflow — direct template for commission approval:**

- `FacultativeCessionService` at `.../reinsurance/service/FacultativeCessionService.java`:
  - `createDraft(...)` line 64 — underwriter creates DRAFT, validates ACTIVE treaty + line covered
  - `approve(...)` line 91 — supervisor DRAFT → APPROVED
  - `commit(...)` line 113 — supervisor APPROVED → CEDED + writes Recovery(EXPECTED)
  - `voidCession(...)` line 137 — cascade void of linked Recovery

- Permission gating via `@RequiresPermission` at `FacultativeCessionController`:
  - Line 79 `REINSURANCE_CEDE_FACULTATIVE` on create
  - Lines 151, 164, 178 `REINSURANCE_APPROVE_FACULTATIVE` on approve/commit/void

**Kafka consumer pattern (per `bug_reactor_kafka_ack_swallow`):**

- `ReinsuranceLossCessionConsumer.java:74` — `.doOnSuccess(v -> record.receiverOffset().acknowledge())`
- Line 78 — `.onErrorResume(e -> Mono.empty())` to avoid poison pills
- Same pattern at `ReinsurancePremiumCessionConsumer.java:52,56`

**Report controller + gating + export:**

- `BordereauReportController.java`:
  - Line 80 `@RequiresReport(ReportKey.REINSURANCE_CESSION_BORDEREAU)` on GET
  - Line 101 same on `/export/excel`
  - Lines 123-124 `securityEventPublisher.publishDataAccess(...)` before returning bytes
  - Lines 119-125 delegates XLSX generation to `BordereauReportWorkbookService` (Apache POI)

Every one of these shapes is 1:1 reusable for `ProducerCommissionController` + `CommissionStatementService` + `ProducerCommissionConsumer` + `CommissionCalcService` + `CommissionClawbackController`.

### Service: shared — report infrastructure ready to reuse

Package `services/java/shared/src/main/java/com/medfund/shared/report/*`:

| File | Purpose |
|---|---|
| `ReportKey.java` | 60+ report catalogue entries; **`COMMISSION_STATEMENT` line 96, `COMMISSION_CLAWBACK` line 97 already exist** |
| `ReportFamily.java` | Family enum; **`COMMISSION` line 25 already exists** |
| `RequiresReport.java` | Method-level annotation for report gating |
| `ReportGuardAspect.java` | AOP aspect; returns 403 if `tenant_report_config.enabled=false` |
| `ReportEnvelopeBuilder.java` | Builds `{data, perCurrency, fxRates, warnings}` envelope reactively |
| `ReportingCurrencyResolver.java:46` | `resolve(UUID tenantId, String override) → Mono<String>` |
| `FxRateReader.java:51` | `findRate(String base, String quote, LocalDate asOf, UUID tenantId) → Mono<BigDecimal>` |
| `ReportEnablementReader.java` | Queries `public.tenant_report_config` |
| `CrossServiceCallHelper.java:36-98` | Timeout + retry + fallback + warnings capture |
| `ReportResponse.java`, `MonthlyAggregateRow.java`, `PerCurrencyTotal.java`, `ReportPeriod.java`, `ReportGenerationException.java` | Supporting DTOs |

Also:
- **`SecurityEventPublisher.publishDataAccess(tenantId, actorId, actorEmail, reportKey, details)`** at `services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java:74-102` (topic `medfund.security.events`).
- **`AuditActor`** at `services/java/shared/src/main/java/com/medfund/shared/audit/AuditActor.java`:
  - `id(Jwt jwt)` line 44 — subject, falls back to `"system"`
  - `email(Jwt jwt)` line 55 — email > preferred_username > subject; **never null** per `feedback_audit_actor_email`
  - `systemActor()` line 69 — `String[]{"system", "system@medfund"}`

**`ReportWorkbook` is NOT in shared** — it lives per-domain (finance-service has `BordereauReportWorkbookService`, etc.). Commission statement XLSX belongs alongside its service, not in shared.

### Service: finance-service — PaymentRun machinery

Files under `services/java/finance-service/src/main/java/com/medfund/finance/`:

- **Entity `entity/PaymentRun.java`:**
  - Line 14 `@Table("payment_runs")`
  - Line 23 `status` default `"draft"` (String — no enum)
  - Line 28-29 `currencyCode` (one currency per run, homogeneous)
  - Line 32-34 **`payeeType` field, values today "PROVIDER" | "MEMBER" only** (default "PROVIDER"); V072 trigger enforces homogeneous child items
  - Line 47-60 V067 balance carry-over fields; V075 source bank account

- **Service `service/PaymentRunService.java:77-116`** — constructor wires PaymentRepository, PaymentAdviceService, FxConverter, etc. Four-eyes shape: `draft → generated → approved → executed` (transition methods live inside service; string statuses, no enum class).

- **Controller `controller/PaymentRunController.java`** — line 59 `@RequiresReport(ReportKey.PAYMENT_RUNS)`; wires `SecurityEventPublisher` for DATA_ACCESS.

- **Event emission:** `service/FinanceEventPublisher.java`
  - Line 34 `publishPaymentCreated(paymentId, providerId, amount) → medfund.finance.payment-created`
  - Line 56 `publishPaymentRunCreated(runId, runNumber, currencyCode, totalAmount, count) → medfund.payments.run.created`

- **No `PRODUCER` or `BROKER` payeeType stubbed** — grep confirmed zero hits.

**Design choice for the grill:** extend `payment_runs.payee_type` to accept `PRODUCER` (additive migration + trigger update + service enum widening) vs. introduce a parallel `producer_payout_run` table that mirrors the shape but keeps concerns isolated. The reinsurance-plan §R2 four-eyes precedent used the same table with a `source ∈ {AUTOMATIC, FACULTATIVE}` discriminator — a `payeeType='PRODUCER'` addition to PaymentRun is the more consistent choice, but the grill needs to weigh trigger churn.

### Service: rules-engine — RuleCategory, facts, and the CEDE_TO_TREATY template

Package `services/java/rules-engine/src/main/java/com/medfund/rules/*`:

- **`model/RuleCategory.java:12-69`** — 15 categories. **No `COMMISSION`.** `REINSURANCE` at line 68 was Phase 10's addition. Existing closest matches: `PROVIDER_PAYMENT` (line 57), `CONTRIBUTION_BILLING` (line 25), `CONTRIBUTION_PRICING` (line 23).
- **Agenda-gated categories:** `BENEFIT_PRORATION`, `REINSURANCE` (line 57 comment). `COMMISSION` should also be agenda-gated so it only fires when a contribution-paid consumer explicitly `setFocus`es on it (no cross-firing with generic contribution rules).
- **`fact/ContributionFact.java:28`** — has `contributionId`, `memberId`, `schemeId`, `groupId`, `currencyCode`, `periodStart`, `periodEnd`, `billingDate`, member demographics (age, region, gender, dependantCount), risk factors (chronic, smoking, bmi), `attributes` (line-agnostic bag), `baseAmount`, `premiumAmount`, `totalLateFees`, `premiumLoadingFactor`, `daysOverdue`, `paid`, `inPaymentPlan`, `results`. **No `producerId` field.** Extension: add `producerId` (with `producerTier`, `producerHomeCurrency`) or route through the existing `attributes` map for MVP.
- **All facts** in `fact/`: `ClaimFact`, `ClaimDetailFact`, `MemberFact`, `ContributionFact`, `PaymentRunFact`, `MemberLifecycleFact`, `DependantFact`, `FamilyFact`, `ProviderFact`, `TimeFact`, `SchemeChangeContext`.
- **`compiler/DrlCompiler.java:34`** — compiles `RuleDefinition` → DRL. `FACT_MAPPINGS` at line 59-74 registers prefixes (`claim.`, `contribution.`, etc.). `emittersByType` at line 77 dispatches by action type. Agenda-gated categories at line 57. New `PAY_COMMISSION` action follows the same auto-collected `@Component` pattern.
- **`compiler/CedeToTreatyEmitter.java:32-64`** — template for a `PayCommissionEmitter`:
  - `type()` line 35 returns `"CEDE_TO_TREATY"`
  - `emit()` line 40 handles two DSL encodings: `PCT:<pct>`, `XOL:<retention>;<limit>;<layerId>`
  - Emits `$claim.addCession(treatyId, amountExpr, layerId, message)` line 64
- **Auto-collection pattern** at `DrlCompiler.java:79` — new `PayCommissionEmitter` beans need zero registry edits.
- **`ContributionFact.attributes` map** already lets commission templates read tenant-defined fact facets without a fact-schema change.
- **Tenant isolation IT to preserve:** `services/java/rules-engine/src/test/java/com/medfund/rules/integration/TenantRuleEngineConcurrencyIT.java:49` — the `bug_rules_engine_tenant_isolation` memory guards `TenantRuleEngine.loadRules` minting a per-tenant `ReleaseId`. Do not weaken.

### Client: Angular — reinsurance tenant-admin CRUD (Phase 11 template)

- **Route registration:** `clients/angular/src/app/pages/tenant-admin/reinsurance/reinsurance.routes.ts:11-31`
  - Reinsurers list → `reinsurers-list.component.ts:28-60`
  - Treaties list → `treaties-list.component.ts:24-70` (groups by renewal chain)
  - Treaty edit → `treaty-edit.component.ts` (create + update, layer/participant/line/rule sub-editors inline)
- **Mount point:** app.routes.ts:75 mounts `REINSURANCE_ROUTES` at `/tenant/admin/reinsurance/*`.
- **Data service:** `clients/angular/src/app/core/services/reinsurance.service.ts:1-136` — one service for the whole vertical (Reinsurer + Treaty + Layer + Participant + Applicable-Line + Cession-Rule).
- **Sidebar variant switching:** `clients/angular/src/app/layout/tenant-layout/tenant-layout.component.ts:56-57` — `data.sidebar: 'admin' | 'operational'` in the route determines nav.

### Client: Angular — reports hub + report-key gating

- **Reports hub:** `clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts:34-85`
  - Line 57-62 loads `TenantReportConfigService.list(tenantId)`, filters to `enabled=true`, groups by family
  - Line 71-84 `groupByFamily()` renders one card per family
- **Report route registration:** `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:679-715` for reinsurance:
  - Line 681 `reports/reinsurance/cession-bordereau` with `data: { reportKey: 'REINSURANCE_CESSION_BORDEREAU' }`
  - Line 693 `reports/reinsurance/recoveries-bordereau` (`REINSURANCE_RECOVERIES`)
  - Line 705 `reports/reinsurance/treaty-utilization` (`REINSURANCE_TREATY_UTILIZATION`)
- **Report config service:** `clients/angular/src/app/core/services/tenant-report-config.service.ts:11-60`
  - `list(tenantId)` line 37-45 — cached `shareReplay(1)`
  - `isEnabled(tenantId, reportKey)` line 53-55
  - `invalidate()` line 57-60 — clears cache on write

Two new routes for Phase 11: `reports/commission/statement` (`COMMISSION_STATEMENT`) and `reports/commission/clawback-register` (`COMMISSION_CLAWBACK`), plus a new tenant-admin `producer` route tree parallel to `reinsurance`.

### Cross-service HTTP — no `UserServiceClient` in finance-service today

- Existing WebClients in `services/java/finance-service/src/main/java/com/medfund/finance/client/`:
  - `ContributionsClient` line 30 — hits `/api/v1/reports/aggregate/billing` + `/receipts`
  - `ClaimsClient`
  - `TenantConfigClient`
- **`UserServiceClient` / `MemberClient` does NOT exist.** Commission calc needing member→producer lookup either adds a new client, or (better) enriches the contribution-paid event upstream to carry `producerId`.
- **All new clients must use `CrossServiceCallHelper`** at `services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java:36-98` — 2s timeout, 1 retry with 250ms backoff, warnings-list capture. Do NOT introduce Resilience4j (repo-wide precedent line 33).

### Duplicate-scaffolding scan — verdict: greenfield

- **Java tree** — grep `\bProducer\b|\bBroker\b|\bCommission\b|\bClawback\b` across `services/java/*/src/main/java`: zero domain entities. Only hit is `treaty.producer_ref` at V082 line 23 (the R15 placeholder).
- **Angular tree** — grep `producer|broker` across `clients/angular/src/app`: zero domain matches.
- **Python/Go** — zero matches.
- **Tenant migrations** — zero matches on `RateCard`, `commission_rate_card`, `commission_transaction`, `producer_hierarchy`, `clawback_event`, `producer.sql` under `services/java/tenancy-service/src/main/resources/db/migration/tenant/`.

### Migration numbering

- **Next tenant V-number:** `V092` (last: `V091__reinsurance_permissions.sql`).
- **Next public V-number:** `V133` (last: `V132__tenant_high_cost_claimant_config.sql`).
- **Precedent for adding a new tenant-scoped `producer_*` config table:** the `V132__tenant_high_cost_claimant_config.sql` shape (single-row-per-tenant config) is the model if commission calc needs a per-tenant "commission scheme". Grill decision.

### Scheduled email delivery — Phase 17 substrate is in place

Package `services/java/shared/src/main/java/com/medfund/shared/scheduler/`:

- `ScheduledJobConfig.java`, `ScheduledJobRun.java`, `ScheduledJobService.java`, `ScheduledJobController.java`
- `JobType.java`, `JobDispatcher.java`, `JobExecutor.java`, `ResultfulJobExecutor.java`
- `JobEventPublisher.java`, `ScheduledJobRepository.java`, `ScheduledJobRunRepository.java`, `ScheduledJobRunSummary.java`

Backing tables via public migrations V114 (scheduled_jobs_to_public), V115 (settings_text), V116 (result_payload), V121 (triggered_by_email). Notification-service email dispatch lives at `services/go/notification-service/internal/mail/sender.go` + `internal/lifecycle/dispatcher.go` + `internal/retry/scheduler.go`. Phase 17 (out-of-scope for 11) wires `SCHEDULE_COMMISSION_STATEMENT_DELIVERY` → dispatch of the same XLSX Phase 11 generates.

## Cross-service flow (proposed Phase 11 shape)

```
[contributions-service]                              [finance-service]                                      [user-service]
    Contribution                                          reinsurance/                                          Member
    ContributionEventPublisher                            producer/                                             UserEventPublisher
        │ publishContributionPaid(...)                      │
        │ topic: medfund.contributions.paid                 │
        │ payload: {contributionId, memberId,               │
        │           amount, currencyCode,                   │
        │           insuranceLine, paidAt, tenantId}        │
        └──────────────────────────────────────────────────►│
                                                            │ ProducerCommissionConsumer.doOnSuccess ack
                                                            │   ├─ lookup member.producer_id (via join or WebClient)
                                                            │   ├─ tenant-focus rules-engine agenda "COMMISSION"
                                                            │   ├─ fire ContributionFact → PAY_COMMISSION emitter
                                                            │   └─ persist commission_transaction (native currency)
                                                            │
                                                            │ ProducerCommissionLapseConsumer.doOnSuccess ack
        ┌───────────────────────────────────────────────────┤   ├─ scan commission_transactions for member in window
        │ topic: medfund.users.member-lifecycle             │   ├─ mark eligible transactions CLAWED_BACK
        │ payload: {tenantId, memberId, status,             │◄──┤   └─ write clawback_event + AuditEvent
        │           reason, terminationDate, ...}           │
        │ event: MEMBER_STATUS_CHANGED                      │
        │                                                   │ CommissionStatementService (report time)
        │                                                   │   ├─ ReportEnvelopeBuilder → perCurrency + fxRates + warnings
        │                                                   │   ├─ FxRateReader → convert native → producer.homeCurrency
        │                                                   │   ├─ SecurityEventPublisher.publishDataAccess on export
        │                                                   │   └─ CommissionWorkbookService → XLSX (Apache POI)
        │
        │ (backfill of producer_ref → producer_id)
        │  finance-service migrator/job → UPDATE treaty
        │  SET producer_id = producer.id
        │  FROM producer WHERE fuzzy(producer_ref, name)
```

## Architecture doc vs. code

| Doc | What it says | What the code shows | Drift |
|---|---|---|---|
| `.claude/rules-engine.md:7-26` | "15 template categories" | `RuleCategory.java:12-69` — 15 values today, no `COMMISSION` | Consistent. Phase 11 adds a 16th. |
| `.claude/multi-currency.md:7,164` | "provider payout flows through the same currency + exchange-rate machinery" | Reinsurance R7 model: store native, convert at report via `FxRateReader` (best-effort, warnings-on-miss) | Consistent. Commission follows the same pattern. |
| `.claude/payments.md:301-356,506-550` | Discusses provider + member payouts; `payment_transactions.recipient_type ∈ {provider, member, platform}` | `PaymentRun.payeeType` today `PROVIDER | MEMBER` only (V072 trigger enforces) | Doc mentions `platform` payee, code doesn't have it. Adding `PRODUCER` continues the same pattern. **Doc + code both silent on `PRODUCER` payout — Phase 11 defines it.** |
| `.claude/portals.md:191-230` | Provider portal exists; **no producer/broker portal spec** | Angular has zero producer/broker routes | Consistent (both empty). Phase 11 adds a tenant-admin `producer` section. |
| `.claude/architecture.md:85-92,114-131` | Finance owns provider balance + advance payments; User owns members/dependants/providers | Finance already hosts reinsurance vertical | Doc is silent on where producer belongs. **Grill decision — see recommendation.** |
| `.claude/coding-standards.md:570-593` | Audit `entityName` friendly-text conventions per entity | Reinsurance uses `treaty.treatyRef`, `reinsurer.name` | Consistent. Phase 11 needs `producer.producerCode` + `producer.name` + `commission_transaction.reference` in the friendly-text table. |

**No load-bearing drift.** All Critical Rules apply cleanly.

## What this plan needs decided

> **Superseded 2026-08-22 by the Grilling Decisions Log below.** All 12 decisions crystallised (11 as recommended, P7 as scope-expansion — full auto-lapse bundled), plus 3 emerged during grilling (P13 revoke-clawback, P14 producer-termination policy, plus P7 sub-splits P7a/P7b/P7c). The subsection below is retained as the pre-grill state — useful for showing what forks *existed*, and for tracking that each was answered. Do not act on the pre-grill wording; act on the Decisions Log.

The grill had to crystallize these. Every one had evidence-for and evidence-against — none was settled by the code alone.

1. **Where does the commission module live?** Finance-service (subpackage `producer/`, mirrors reinsurance R3) vs. user-service (member↔producer is a user-domain relationship) vs. new `producer-service` (isolation).
   - *For finance:* consumer proximity (finance already consumes `medfund.contributions.paid`); tenant-schema locality (all commission tables migrated by tenancy but owned by finance); reinsurance precedent; payout mechanism (PaymentRun) is in finance.
   - *For user:* Member and Producer are both parties (user-domain nouns); avoids cross-service call to look up member's producer.
   - *Against a new service:* deployment overhead; another Kafka consumer; no existing precedent.
   - **Recommendation in this doc:** finance-service.

2. **Producer↔member linkage shape.** Direct FK on `members.producer_id` (simplest, one-producer-per-member) vs. time-slice-aware join table `member_producer_assignment(member_id, producer_id, effective_from, effective_to)` (supports producer changes over policy life) vs. FK on `policies` (per-line producer, matches person-centric vs. asset-centric split at `InsuranceLine.isPersonCentric()`).
   - Ties into: how commission credit is allocated when a member switches producers mid-year, and how backdated enrollment (per `project_backdated_enrolment_adjustment` memory) triggers historical commission recalc.

3. **Contribution-paid event enrichment vs. lookup-at-consume-time.** Enrich `medfund.contributions.paid` payload with `producerId` at publish time (contributions-service must know the producer — leaks producer concept into a lower service), or keep event thin and lookup member→producer in the finance-service consumer.
   - *For enrichment:* consumer doesn't need cross-schema/cross-service lookup; event carries everything.
   - *Against:* forces contributions-service to know about producers (violates "producer is finance's business" separation).
   - **Consequence:** if lookup-at-consume, need a `MemberClient` WebClient in finance-service (per `CrossServiceCallHelper` pattern) OR a copy of the member↔producer table into finance's tenant schema.

4. **PaymentRun extension vs. parallel `producer_payout_run`.** Extend `payment_runs.payeeType` to accept `PRODUCER` (additive migration + widen V072 trigger + service enum) vs. new `producer_payout_run` table that mirrors the four-eyes shape.
   - *For extension:* one payout pipeline for the whole platform; existing carry-forward, source-bank, and audit already work.
   - *Against:* trigger churn; withholding-tax and clawback-offset math may not fit the provider-payout shape cleanly.

5. **`COMMISSION` as a new `RuleCategory`.** Add `RuleCategory.COMMISSION` (line 69 in the enum, agenda-gated) + a `PAY_COMMISSION` action emitter (mirrors `CEDE_TO_TREATY`) vs. hard-coded commission calc as a domain service.
   - *For rules-engine:* Critical Rule 5 says tenant-varying business rules go through rules-engine; rate cards are the textbook case; agenda gating means zero cross-firing.
   - *Against:* rate cards may be structured (per-tier tables) not conditional (DRL is best at conditionals). If commission calc becomes a lookup on a fixed table rather than "IF X THEN pay Y", the rules-engine is the wrong tool.
   - **Recommendation:** rules-engine for the *conditional* rules (waivers, tier promotions, sliding-scale kickers); a `commission_rate_card` table for the *lookup* base rate. Both feed into `CommissionCalcService`.

6. **Clawback window shape.** Per producer (each producer negotiates own clawback) vs. per rate card (each rate card has its own window) vs. per insurance line (regulatory-driven per line) vs. per tenant (one platform-wide config).
   - **Consequence:** determines the columns on `commission_rate_card` or a new `clawback_config` table.

7. **Auto-lapse — bundle into Phase 11 or defer.** Today no scheduler lapses non-paying members; every lapse is operator-driven. Commission clawback triggers on the existing `medfund.users.member-lifecycle` event, so as long as an operator marks the member LAPSED the clawback fires. If Phase 11 wants automatic clawback (member goes N days into arrears → auto-lapse → clawback), a lapse scheduler must ship first.
   - **Recommendation:** rely on operator-driven lapse for MVP; deferred ticket for auto-lapse scheduler.

8. **Producer hierarchy shape.** Master broker → sub-broker → producer (self-referential FK on `producer.parent_producer_id`) vs. `producer_hierarchy(producer_id, parent_id, effective_from)` join table (time-slice-aware) vs. materialized-path column.
   - **Consequence:** determines commission-split maths for hierarchies (do parent brokers get an override percentage on child production?).

9. **Facultative-style workflow for commission adjustments.** Should manual commission adjustments (a broker gets an ex-gratia payment, or a supervisor voids a wrongly-paid commission) go through a DRAFT → APPROVED → COMMITTED four-eyes flow like facultative cession?
   - **Recommendation:** yes; reuse the FacultativeCession pattern verbatim for `CommissionAdjustment` entity.

10. **Currency at payout time.** Commissions calculated in the contribution's native currency (per R7 precedent), but paid to the producer in whose currency: producer's home currency (needs `producer.homeCurrency` field) or tenant's reporting currency (uniform statements, harder for producers).
    - **Recommendation:** producer's home currency; FX at commit time locks the rate (matches `.claude/multi-currency.md:164` "Exchange rate at time of payment commitment is locked").

11. **`producer_ref` → `producer_id` backfill for `treaty` table (R15).** Immediate additive migration (`ALTER TABLE treaty ADD COLUMN producer_id UUID`) + a backfill job that fuzzy-matches `producer_ref` → `producer.name`. Two decisions: (a) can `producer_ref` be dropped after backfill or does the audit trail need it retained; (b) is the backfill a one-shot Flyway repeatable or a manually-triggered job with progress tracking (à la `TreatyActivationBackfillJob`)?

12. **Scheduled statement delivery scope.** The parent-plan Phase 11 intro says "Sets up scheduled delivery (used again in Phase 17)". Confirm: does Phase 11 build the scheduled-delivery admin surface (producer picks their delivery frequency + email) or does that land entirely in Phase 17? The `ScheduledJobService` substrate is already in place, so it's a small addition.

## Grilling — Decisions Log (settled 2026-08-22)

Prefix `P` (Producer) chosen to avoid collision with the parent plan's `G` (financial-suite-wide) and reinsurance sub-plan's `R`.

- **P1 — Module home**: finance-service, `com.medfund.finance.producer.*` subpackage. Mirrors reinsurance §R3.
- **P2 — Member↔producer linkage**: finance-service time-slice `member_producer_assignment(member_id, producer_id, effective_from, effective_to NULL, actor_id, actor_email)` with app-layer at-most-one-open guard. Commission calc joins on `WHERE :paid_at BETWEEN effective_from AND COALESCE(effective_to, 'infinity')`.
- **P3 — Producer resolution at consume time**: local DB lookup on the commission consumer. No event enrichment.
- **P4 — Payout mechanism**: extend PaymentRun. Additive migration touches 4 tables (payment_runs + V071's payments + payment_run_items + payment_advices) — widen CHECK to include PRODUCER, add nullable `producer_id UUID`, widen 3 XOR-CHECKs, add indexes. V072 trigger unchanged (F11-f). Withholding tax as nullable `withholding_tax_pct NUMERIC(5,2)` on payment_run_items.
- **P5 — Commission engine**: hybrid. `commission_rate_card` for base-rate lookups + new `RuleCategory.COMMISSION` (agenda-gated, line 69 of the enum) + `PayCommissionEmitter` for conditional kickers. `CommissionCalcService` orchestrates.
- **P6 — Clawback window**: `commission_rate_card.clawback_window_days INT NULL` (nullable = no clawback). Rules-engine COMMISSION kicker can override for edge cases per P5.
- **P7 — Auto-lapse scope**: bundled into Phase 11 (scope expansion — user chose the non-recommended option). Splits into P7a/P7b/P7c.
- **P7a — Lapse scheduler home + write path**: event-driven. New scheduler in contributions-service publishes new topic `medfund.contributions.arrears-threshold-breached` with `{tenantId, memberId, arrearsMonths, currentBalance, currencyCode, breachedAt}`. User-service `ArrearsBreachedConsumer` applies policy, sets scheduledStatus, and `medfund.users.member-lifecycle` fires naturally on transition.
- **P7b — Lapse policy shape**: two-stage. Consumer sets `Member.scheduledStatus='LAPSED'` + `scheduledStatusEffectiveFrom = today + graceWindowDays` (V042 infrastructure). If contributions-service publishes new `medfund.contributions.arrears-cleared` before the scheduled_from date, user-service consumer nulls the scheduledStatus. Otherwise SCHEDULED_STATUS_ROLL job transitions to LAPSED.
- **P7c — Lapse config granularity**: per-tenant single-row `public.tenant_auto_lapse_config(tenant_id UUID PK, enabled BOOLEAN NOT NULL DEFAULT false, arrears_threshold_months INT, grace_window_days INT, actor_id, actor_email)`. Matches V127/V128/V132 pattern.
- **P8 — Producer hierarchy**: self-referential FK `producer.parent_producer_id UUID NULL`. Recursive CTE walks upward for override commission. No time-slice for MVP. Outline's separate `producer_hierarchy` table dropped.
- **P9 — Commission adjustment workflow**: four-eyes mirroring FacultativeCession. `CommissionAdjustment` entity with `{DRAFT, APPROVED, COMMITTED, VOIDED}`. Two permissions: `finance.commission:draft_adjustment` + `finance.commission:approve_adjustment`. AuditEvent on every transition.
- **P10 — Payout currency**: producer's home currency. New `producer.home_currency CHAR(3) NOT NULL`. Payout runs homogeneous by `home_currency × period`. FX at commit time locks the rate.
- **P11 — `treaty.producer_ref` backfill**: additive `treaty.producer_id UUID NULL REFERENCES producer(id)`. Manual `ProducerBackfillJob` (mirrors `TreatyActivationBackfillJob`) fuzzy-matches to `producer.name` (Java Levenshtein or ILIKE — pg_trgm unavailable per F11-c); writes to `producer_backfill_candidate` staging with confidence score. Tenant-admin review UI at `/tenant-admin/producers/backfill`. `producer_ref` retained forever as audit trail.
- **P12 — Scheduled statement delivery**: deferred entirely to Phase 17. Phase 11 ships manual XLSX export from report UIs only.
- **P13 — Revoked-contribution clawback**: new `medfund.contributions.revoked` event + `CommissionRevokeConsumer` in finance-service. Contributions-service adds `publishContributionRevoked(...)` in the revoke flow. Consumer writes compensating commission_transaction + `clawback_event(source=CONTRIBUTION_REVOKE)`.
- **P14 — Producer termination**: manual reassignment. Termination closes active `member_producer_assignment` rows (`effective_to = last-day-of-month`); no auto-successor; bulk-reassign UI at `/tenant-admin/producers/{terminatedId}/reassign`; commission calc during gap warns + skips.

## Settled by fact (not asked)

- **F11-a — `ReportKey.COMMISSION_STATEMENT`, `COMMISSION_CLAWBACK`, `ReportFamily.COMMISSION` already ship** (`ReportKey.java:96-97`, `ReportFamily.java:25`). Phase 11 adds controllers only.
- **F11-b — Next tenant V-number = V092** (last: `V091__reinsurance_permissions.sql`); next public V-number = **V133** (last: `V132__tenant_high_cost_claimant_config.sql`).
- **F11-c — pg_trgm is NOT on the classpath** (per Phase 4 §B G45 memory). P11 fuzzy match uses Java Levenshtein or plain ILIKE substring; never `%%`-similarity SQL.
- **F11-d — Withholding tax** is a nullable `withholding_tax_pct NUMERIC(5,2)` column on `payment_run_items` per P4; MVP retains full amount if null; jurisdictional WHT config deferred.
- **F11-e — Producer self-service portal out of scope** by parent-plan outline; Phase 11 ships tenant-admin only.
- **F11-f — V072 payment_run item-parent trigger is generic** (reads parent's `payee_type` at runtime via `SELECT payee_type INTO run_payee_type FROM payment_runs WHERE id = NEW.payment_run_id`); does NOT need editing when payee_type widens. Only the 4 CHECK constraints on payment_runs + payments + payment_run_items + payment_advices need widening.

## Owed back to the parent plan

Applied 2026-08-22 as part of this grill (see the rewritten Phase 11 block in `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md`):

- Outline's `producer_hierarchy` table struck through, replaced by self-referential FK per P8.
- Outline's single-event trigger list (`medfund.contributions.paid` + `medfund.users.member-lifecycle`) expanded — three new topics introduced by grilling (`medfund.contributions.revoked` per P13; `medfund.contributions.arrears-threshold-breached` + `arrears-cleared` per P7a).
- Outline's silent auto-lapse assumption made explicit — Phase 11 now bundles the full auto-lapse chain per P7/P7a/P7b/P7c (was silent in outline; user chose to expand scope during grilling).
- Outline's `clawback_event` table confirmed as-is but with new column `source ∈ {MEMBER_LAPSE, CONTRIBUTION_REVOKE}` per P13.
- Outline's "sets up scheduled delivery" wording weakened to "deferred to Phase 17" per P12.
- Added §A/§B tranche split matching the reinsurance sub-plan pattern.
- Added 9 grill notes for `create-plan` on decisions that need code-altitude resolution (event publisher for `.revoked`, arrears-cleared payment-side hook, `PayCommissionEmitter` DSL, member_producer_assignment guard, bulk-reassign UI sizing, WHT config, producer.home_currency data migration, CommissionAdjustment fields, producer payout run generator shape).

## Gaps between spec and code

**The Phase 11 outline vs. reality:**

- Outline says "New tables: `producer`, `producer_hierarchy`, `commission_rate_card`, `commission_transaction`, `clawback_event`." **All greenfield — zero exist.** Confirmed by tenant-migrations grep.
- Outline says "Report keys `COMMISSION_STATEMENT`, `COMMISSION_CLAWBACK`". **Both already ship** at `ReportKey.java:96-97`. Phase 11 does not add enum values — it adds controllers gated on them.
- Outline says "Commission calculation service triggered by `medfund.contributions.paid` events." **The event is well-established** — post-Phase-6 payload carries `contributionId`, `memberId`, `amount`, `currencyCode`, `insuranceLine`, `paidAt`, `tenantId`. Only missing field is `producerId` — see Decision 3.
- Outline says "Clawback trigger on `medfund.users.member-lifecycle` (lapse events)." **The event ships** — `MEMBER_STATUS_CHANGED` at `UserEventPublisher.java:89-103`. **But there is no auto-lapse today** — every lapse is operator-driven. See Decision 7.
- Outline is silent on producer↔member linkage — **Phase 11 must define it.** See Decision 2.
- Outline is silent on payout mechanism — **Phase 11 must pick PaymentRun extension vs. new pipeline.** See Decision 4.
- Outline is silent on where the module lives — **Phase 11 must pick.** See Decision 1.
- R15 in reinsurance sub-plan promised `treaty.producer_id UUID FK REFERENCES producer(id)` in Phase 11. **Backfill job unspecified.** See Decision 11.

## Architecture Insights

**Critical Rules (`.claude/CLAUDE.md`) relevant to Phase 11:**

- **Rule 1 (never mix currencies):** Commission stored native; conversion at report/payout via `FxRateReader`. Rule enforced by using `BigDecimal` throughout; producer payout in producer home currency (or tenant reporting currency — grill decision).
- **Rule 2 (tenant-scoped queries):** All commission tables tenant-scoped. Use unqualified table names in queries; only prefix `public.` for the platform-wide tables (V133+ commission config table if the grill adds one; audit-memory `bug_public_prefix_silent_rollback` applies).
- **Rule 5 (per-tenant rules in rules-engine):** Commission rate cards are tenant-varying business rules. Directly indicates the `RuleCategory.COMMISSION` addition + `PAY_COMMISSION` action emitter.
- **Rule 6 (Kafka for side effects):** ProducerCommissionConsumer reads `medfund.contributions.paid`; ProducerCommissionLapseConsumer reads `medfund.users.member-lifecycle`. No synchronous inter-service calls for side effects.
- **Rule 7 (Swagger):** New controllers (`ProducerController`, `CommissionRateCardController`, `CommissionStatementController`, `CommissionClawbackController`, `CommissionAdjustmentController`) all need `@Tag` + `@Operation` + `@ApiResponse`.
- **Rule 8 (audit-log every mutation):** Every commission mutation (rate-card CRUD, adjustment, clawback, payout) emits an AuditEvent via `AuditActor.id + email` (per `feedback_audit_actor_email`); `entityName` is friendly text like `producer.producerCode`, `commission_transaction.reference` (per `feedback_audit_entity_name`).
- **Rule 9 (security events):** Commission report exports emit `SecurityEventPublisher.publishDataAccess(..., reportKey=COMMISSION_STATEMENT, ...)` matching reinsurance precedent.

**Reactor-Kafka ack pattern (`bug_reactor_kafka_ack_swallow`):** every new consumer uses `.doOnSuccess(v -> record.receiverOffset().acknowledge())` + `.onErrorResume(e -> Mono.empty())` with full cause-chain logging; **never `.doOnTerminate`.**

**Tenant Flyway invariants (`bug_tenant_flyway_outoforder`, `feedback_never_edit_applied_migrations`, `bug_public_flyway_history_load_bearing`):** Phase 11 migrations go into `services/java/tenancy-service/src/main/resources/db/migration/tenant/` starting at **V092**; any commission config table lives under `.../public/` starting at **V133**. Never edit applied migrations; correct via a new higher-numbered file. Never touch `public.flyway_schema_history` rows.

**No raw ID inputs (`feedback_no_raw_id_inputs`):** the producer admin UI must use debounced search-select for producer/broker pickers, not `<input>` for `producerId`. Same for member pickers on commission adjustment forms.

**Effective-date snap (`feedback_effective_date_snap`):** producer effective-from = 1st-of-month; producer termination/deactivation = last-day-of-month. Apply to modals, forms, backend validators, and test assertions.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/plans/2026-08-22-reinsurance-module-and-bordereau-reports.md` — the immediate template for Phase 11. §R3 sets the "subpackage of finance-service" precedent; §R4 sets the "rules-engine-as-DSL" precedent; §R6 sets the four-eyes precedent; §R7 sets the "native storage + convert at report time" currency precedent; §R15 is the direct handoff to Phase 11 for `producer_id` FK.
- `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:2958-2973` — parent plan's Phase 11 outline (this doc's target).
- `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:125-302` — Phase 0 established `ReportKey`, `ReportFamily`, `RequiresReport`, `ReportGuardAspect`, `SecurityEventPublisher` shared infra that Phase 11 composes on.
- `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:303-637` — Phase 1 established the `ReportRetrofitAssertions` shared-testFixtures helper Phase 11 IT reuses.
- No prior research doc under `thoughts/shared/research/` covers producers, commission, or clawback — this is the first.

## Related Research

None (first Phase-11-focused research).

## Auto-memory constraints — Phase 11 must honor upfront

| Memory | Applies to Phase 11 because | Enforce at |
|---|---|---|
| `feedback_audit_actor_email` | Every commission mutation (CRUD, adjustment, clawback, payout) | Every service method; use `AuditActor` helper; never null email |
| `feedback_audit_entity_name` | Audit `entityName` must be friendly text | Producer.producerCode, CommissionTransaction.reference — not UUIDs |
| `bug_reactor_kafka_ack_swallow` | Two new Kafka consumers (contribution-paid, member-lifecycle) | `.doOnSuccess` not `.doOnTerminate`; full cause-chain error logging |
| `bug_rules_engine_tenant_isolation` | New `RuleCategory.COMMISSION` + per-tenant rate cards must not cross-pollute | Keep `TenantRuleEngine.loadRules` per-tenant ReleaseId; add concurrency IT if the category has meaningful state |
| `feedback_never_edit_applied_migrations` | Phase 11 tenant migrations at V092+; public at V133+ | Idempotent SQL (`CREATE … IF NOT EXISTS`); new file for any correction |
| `bug_tenant_flyway_outoforder` | Multi-migration Phase 11 tranche | Migrations arrive in strict numerical order; if a hotfix migration slots in later, `flyway repair` + `-outOfOrder=true` on tenant schema |
| `bug_public_prefix_silent_rollback` | Any query on tenant tables must use unqualified names | Only prefix `public.` for platform-wide tables (V133+ if used) |
| `feedback_no_raw_id_inputs` | Producer admin UI + commission adjustment forms | Debounced search-select components for producer/member pickers |
| `feedback_effective_date_snap` | Producer effective-from + termination/deactivation dates | 1st-of-month for starts, last-day-of-month for terminations |
| `project_backdated_enrolment_adjustment` | Backdated producer assignment triggers commission recalc for prior periods | Deferred follow-up; document the pattern in the sub-plan |
| `feedback_one_contribution_per_month` | Commission calc reacts to contribution-paid events, which are unique-per-month | Commission transaction unique key can safely be `(contributionId, producerId)` |
| `feedback_grouped_members_cannot_pay` | Producer hierarchies may aggregate payouts at parent level, mirroring group aggregation | Producer-payout picker excludes producers that route through a parent |
| `feedback_stats_serverside` | Commission KPIs on the report hub / producer dashboard | Server-side aggregation only; new `/reports/commission/*/kpi` endpoints |

## Code References

**Java — shared:**
- `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:96-97` — `COMMISSION_STATEMENT`, `COMMISSION_CLAWBACK` already defined
- `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:25` — `COMMISSION` family already defined
- `services/java/shared/src/main/java/com/medfund/shared/report/RequiresReport.java:32-35` — annotation
- `services/java/shared/src/main/java/com/medfund/shared/report/ReportGuardAspect.java:34-68` — 403 enforcement
- `services/java/shared/src/main/java/com/medfund/shared/report/ReportEnvelopeBuilder.java:64-70` — envelope builder
- `services/java/shared/src/main/java/com/medfund/shared/report/ReportingCurrencyResolver.java:46` — currency resolver
- `services/java/shared/src/main/java/com/medfund/shared/report/FxRateReader.java:51` — FX lookup
- `services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java:36-98` — HTTP guard
- `services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java:74-102` — data-access event
- `services/java/shared/src/main/java/com/medfund/shared/audit/AuditActor.java:44,55,69` — actor helper

**Java — contributions-service:**
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ContributionEventPublisher.java:50-63` — `publishContributionPaid`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Contribution.java:15-63` — Contribution entity (no producer field)
- `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/MemberLifecycleConsumer.java:49` — precedent consumer for `medfund.users.member-lifecycle`

**Java — user-service:**
- `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:14,51,69-76,86-87` — Member (no producer field, `status` String, scheduled/suspend fields)
- `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java:89-103,141-159` — member-lifecycle + member-changed
- `services/java/user-service/src/main/java/com/medfund/user/service/MemberLifecycleService.java` — lifecycle orchestration

**Java — finance-service:**
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/FacultativeCessionService.java:64,91,113,137` — four-eyes template
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/controller/FacultativeCessionController.java:79,151,164,178` — `@RequiresPermission` gating
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/consumer/ReinsurancePremiumCessionConsumer.java:38,52,56` — Kafka consumer template
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/consumer/ReinsuranceLossCessionConsumer.java:74,78` — same
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/controller/BordereauReportController.java:80,101,123-125` — report controller + XLSX + SecurityEvent
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRun.java:14,23,28-34,47-60` — PaymentRun entity
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:77-116` — service constructor
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentRunController.java:12,59` — controller
- `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java:34,56` — payment-created + payment-run-created

**Java — rules-engine:**
- `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:12-69` — enum (no COMMISSION today)
- `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ContributionFact.java:28` — fact with `attributes` bag
- `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java:34,57,59-74,77,79` — compiler + FACT_MAPPINGS + agenda-gated + emitter auto-collect
- `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/CedeToTreatyEmitter.java:32,35,40,64` — emitter template
- `services/java/rules-engine/src/test/java/com/medfund/rules/integration/TenantRuleEngineConcurrencyIT.java:49,66-69,73,94,98` — tenant-isolation guard

**Tenant migrations:**
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V082__treaty.sql:23` — `producer_ref VARCHAR(120)` placeholder
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V091__reinsurance_permissions.sql` — last used V (next: V092)
- `services/java/tenancy-service/src/main/resources/db/migration/public/V132__tenant_high_cost_claimant_config.sql` — last public V (next: V133)

**Angular:**
- `clients/angular/src/app/pages/tenant-admin/reinsurance/reinsurance.routes.ts:11-31` — routes template
- `clients/angular/src/app/pages/tenant-admin/reinsurance/reinsurers-list.component.ts:28-60` — list template
- `clients/angular/src/app/pages/tenant-admin/reinsurance/treaties-list.component.ts:24-70` — grouped-list template
- `clients/angular/src/app/core/services/reinsurance.service.ts:1-136` — single-vertical data service template
- `clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts:34-85` — reports hub
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:679-715` — report route registration with `data.reportKey`
- `clients/angular/src/app/core/services/tenant-report-config.service.ts:11-60` — enablement cache
- `clients/angular/src/app/layout/tenant-layout/tenant-layout.component.ts:56-57` — sidebar variant switching

**Architecture docs:**
- `.claude/CLAUDE.md` — 9 Critical Rules
- `.claude/rules-engine.md:7-26` — 15 template categories (no COMMISSION)
- `.claude/multi-currency.md:7,164` — currency handling for payouts
- `.claude/payments.md:301-356,506-550` — payout ledgers + `recipient_type` enum
- `.claude/coding-standards.md:570-593` — audit `entityName` conventions
- `.claude/portals.md:191-230` — Provider Portal (no producer portal today)
- `.claude/architecture.md:85-92,114-131` — service scopes

**Scheduler substrate (Phase 17 hook):**
- `services/java/shared/src/main/java/com/medfund/shared/scheduler/` — ScheduledJobConfig, ScheduledJobService, JobDispatcher, JobExecutor, JobEventPublisher, ScheduledJobController + repositories
- `services/java/tenancy-service/src/main/resources/db/migration/public/V114..V116,V121` — scheduler tables
- `services/go/notification-service/internal/mail/sender.go`, `internal/lifecycle/dispatcher.go`, `internal/retry/scheduler.go`, `internal/template/resolver.go` — email dispatch surface

## Open Questions

- **Withholding tax on commissions.** Some tenants withhold tax on producer payouts (per jurisdiction). Not touched in this pass; the `.claude/coding-standards.md:593` reference to "withholding tax rates" on ProviderPayment suggests reusable machinery in the provider-payment domain. Grill.
- **Producer-side portal.** Producers may need a login to see their own statements. `.claude/portals.md` has zero producer portal spec. Deferred question for the plan — Phase 11 MVP probably ships tenant-admin only; producer self-service portal is a follow-up.
- **Backfill of `treaty.producer_ref` → `producer_id`.** Fuzzy-match strategy (Levenshtein? case-insensitive exact?) + confidence threshold + manual-review queue for low-confidence matches. Grill.
- **Commission on refunded/revoked contributions.** If a contribution is revoked (`feedback_one_contribution_per_month` mentions revoke goes through `next-month-only` route), does the commission clawback? Automatic (commission_transaction status flips) or operator-driven (commission_adjustment)? Grill.
- **Producer termination + book roll.** When a producer is deactivated, do their members auto-reassign to another producer? Manually? Left orphaned? Grill.

---

**Next step (RPI loop):**

Research written to `thoughts/shared/research/2026-08-22-phase11-producer-commission.md`.

Clear your context, then run:

```
grilling thoughts/shared/research/2026-08-22-phase11-producer-commission.md \
         thoughts/shared/plans/2026-08-11-financial-reporting-suite.md \
         "focus on Decisions 1-11 in the research doc; treat Decision 12 (scheduled-delivery scope) as a deferrable and settle it last"
```

The grill will crystallize the 12 open Decisions into R-numbered entries, then hand off to `create-plan` for a Phase-10-style code-altitude sub-plan (target file: `thoughts/shared/plans/2026-08-22-producer-broker-module-and-commission-reports.md`).
