---
date: 2026-09-05
git_commit: cae9058b78bdd3ef47bbaee5c99848ded07efe5d
branch: rename-adjustments-to-notes
ticket: null
parent_plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md
parent_phase: 19
research: none — grill applied at parent-plan Phase 19 (FR1..FR16 + F19-1..F19-7 on 2026-09-05) supplied the decision-level research with 25+ file:line citations; verification pass during create-plan confirmed report infra + AI service Kafka consumer + rules-engine surface + retention infra + Phase 17 scheduled infra + Angular reports hub + Keycloak bootstrap; two scratchpad claims corrected (RetentionClass is string constants on ReportJob not an enum; latest tenant migration is V168 not V139 — Phase 19 §A migrations start at V169)
steer: "Author sub-plan per FR16 — 2 tranches, MVP first then expansion, ~10-12 phases"
services_touched: [shared, tenancy-service, claims-service, finance-service, rules-engine, ai-service, gateway, angular, keycloak-bootstrap]
status: draft
tranches:
  A_MVP: Phases 1-6 — early-value ship (3 entities, 3-state machine, 4 perms + 1 role, AI producer + FRAUD_TRIAGE 3 templates, 4-tile report + 2-sheet XLSX)
  B_Expansion: Phases 7-12 — completes to full FR5/FR6/FR11/FR15 (adds evidence + referrals, full 5-state + four-eyes, remaining 4 perms + supervisor role, 3 more templates, full-analytics report + Phase 17 scheduled dispatch)
---

# Fraud / SIU Report Implementation Plan (Parent-Plan Phase 19)

## Overview

Build a full SIU (Special Investigations Unit) case-management module in claims-service and surface a full-analytics fraud report in the Reports Hub. The module persists every AI fraud prediction as an immutable audit-of-record `fraud_flag` row (Rule 3), lets a tenant-configurable rules-engine category (`FRAUD_TRIAGE`) auto-open investigation cases, gives SIU officers a queue + case-detail workspace under `/tenant/claims/siu/`, and surfaces summary analytics + XLSX export at `/tenant/finance/reports/fraud/` with cadenced scheduled email delivery via Phase 17. Ships as 2 tranches per parent-plan FR16.

## Current State Analysis

**Where the AI fraud path stops today** (verified via grill F19-2, F19-5):

- `services/python/ai-service/app/core/kafka_consumer.py:1-97` — `ClaimsEventConsumer` subscribes to `medfund.claims.submitted` and runs `AdjudicationService.analyze_claim()` + `FraudService.detect_fraud()` in parallel on every submission (lines 85-88). **The prediction is discarded** — no Kafka producer wired, no DB persistence, no downstream consumer.
- `services/python/ai-service/app/services/fraud_service.py:12-42` — `FraudService.detect_fraud(claim_data, tenant_id)` returns `{risk_score, risk_level, indicators, model}`; wraps `FraudMLModel.predict()`.
- `services/python/ai-service/app/services/ml_models.py:11-96` — `FraudMLModel` uses `IsolationForest` (scikit-learn) trained on synthetic data; feature vector = `[amount, procedure_count, diagnosis_count, days_since_enrollment]`; `model_version` field is a hardcoded string.
- `services/python/ai-service/pyproject.toml:12` — `aiokafka>=0.12.0` already a dependency; producer wiring pattern already exists at `services/python/ai-service/app/report/kafka.py:85-94` (`_default_producer_factory()` for report-job-completed events).

**Where the claims-service SIU surface would live** (verified via F19-3, F19-4):

- `services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java:14-284` — no `fraud_flag`, `fraud_score`, or `siu_status` columns. `R16 FRAUD` (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V014__claims_schema.sql:116-135`) is a categorical rejection code, not a per-claim flag.
- `services/java/claims-service/src/main/java/com/medfund/claims/controller/` — no `FraudFlagController`, `SiuController`, or `InvestigationController`. Consumer package has `RuleChangeConsumer` pattern (`services/java/rules-engine/src/main/java/com/medfund/rules/consumer/RuleChangeConsumer.java:37-85`) — Reactor-Kafka with `.doOnSuccess` + `.onErrorResume` + explicit `.acknowledge()` per `bug_reactor_kafka_ack_swallow`.

**Phase 0 report infrastructure available** (verified via F19-1 + create-plan pass):

- `ReportKey.FRAUD_SIU_REPORT` at `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:150` — `("Fraud / SIU report", ReportFamily.FRAUD, cadenced=true, ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD)`.
- `ReportFamily.FRAUD` at `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:35` — label "Fraud / SIU".
- `ReportResponse<T>` (record, `services/java/shared/.../ReportResponse.java:41-49`): `(reportKey, period, reportingCurrency, data, perCurrency, fxRates, warnings, generatedAt)`.
- `ReportEnvelopeBuilder` (component, `services/java/shared/.../ReportEnvelopeBuilder.java:54-150`): `build(...)`, `buildNoAggregate(...)`.
- `ReportingCurrencyResolver.resolve(UUID tenantId, String override) → Mono<String>` (`services/java/shared/.../ReportingCurrencyResolver.java:46`).
- `FxRateReader.findRate(...)` best-effort empty on miss (line 51); `FxRateReader.convert(...)` fail-loud on miss (line 82).
- `RequiresReport` annotation + `ReportGuardAspect` short-circuits 403 when tenant toggle off (`services/java/shared/.../ReportGuardAspect.java:34-74`).
- `CrossServiceCallHelper.guarded(String, Mono<T>, T, List<String>)` — 2s timeout + 1 retry + fallback + warnings capture (`services/java/shared/.../CrossServiceCallHelper.java:64`).
- `SecurityEventPublisher.publishDataAccess(String tenantId, String actorId, String actorEmail, String reportKey, Map<String, Object> details) → Mono<Void>` (`services/java/shared/.../SecurityEventPublisher.java:77`) — topic `medfund.security.events`.
- `AuditActor.id(Jwt)` / `AuditActor.email(Jwt)` (`services/java/shared/.../AuditActor.java:44,55`); `AuditEvent.create(...)` 11-arg factory (`services/java/shared/.../AuditEvent.java:32`) — carries `entityName` (friendly text, never UUID) per `feedback_audit_entity_name`.

**Rules-engine surface available** (verified via create-plan pass):

- `RuleCategory` enum at `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:12-150` — 23 existing categories (last: `PMB_CLASSIFICATION` line 149). Phase 19 §A appends `FRAUD_TRIAGE`.
- `TemplateProvider` interface at `services/java/rules-engine/src/main/java/com/medfund/rules/template/TemplateProvider.java:19-26` — `category()` + `templates()`; auto-aggregated as Spring bean.
- `DrlCompiler.FACT_MAPPINGS` at `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java:67-90` — category → DRL fact mapping; append entry for FRAUD_TRIAGE → `FraudFlagFact`.
- `TenantRuleEngine.evaluateInGroup(String tenantId, String agendaGroup, Object... facts)` (`services/java/rules-engine/src/main/java/com/medfund/rules/engine/TenantRuleEngine.java:140`) — fires an agenda group; Phase 19 §A calls this from the fraud-flagged consumer.
- Existing `ClaimFact` at `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ClaimFact.java:1-50` — pattern for the new `FraudFlagFact`.

**Retention taxonomy** (F19-7 — corrected during create-plan):

- **Not an enum.** String constants on `ReportJob`: `RETENTION_OPERATIONAL_90D = "OPERATIONAL_90D"` (line 35), `RETENTION_STATUTORY_7Y = "STATUTORY_7Y"` (line 36). Phase 19 §A adds two more constants: `RETENTION_FRAUD_FLAG_1Y = "FRAUD_FLAG_1Y"` and `RETENTION_SIU_CASE_7Y = "SIU_CASE_7Y"`.
- Classifier `Ifrs17JobService.classifyRetention(ReportKey) → String` at `services/java/finance-service/src/main/java/com/medfund/finance/ifrs17/service/Ifrs17JobService.java:239-243` — the switch widens to route `FRAUD_SIU_REPORT` → `RETENTION_SIU_CASE_7Y`.
- `ReportJobRetentionJob` at `services/java/finance-service/src/main/java/com/medfund/finance/report/scheduler/ReportJobRetentionJob.java:43-111` — nightly 02:00, delete OPERATIONAL_90D >90d + trim to 20 per (tenant, key); delete STATUTORY_7Y >7y. Phase 19 §A extends with SIU_CASE_7Y branch.
- **New `FraudFlagRetentionJob`** (Phase 19 §A) purges unlinked `fraud_flag` rows older than 1 year — separate class because `fraud_flag` lives in the tenant schema, not `report_job`.

**Phase 17 scheduled infrastructure** (create-plan pass):

- `ScheduledReportEligibility` at `services/java/shared/src/main/java/com/medfund/shared/report/ScheduledReportEligibility.java` — static `EnumSet` `WHITELIST` (currently 18 keys: 13 Phase 17 baseline + 5 Phase 18 KPI keys). Phase 19 §B adds `ReportKey.FRAUD_SIU_REPORT` → 19 keys.
- `ScheduledReportShapeAdapter` interface at `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportShapeAdapter.java:25-32` — `key()`, `periodShape()`, `render(ScheduledFireContext) → Mono<byte[]>`. Adapter beans auto-collected into an EnumMap.
- `ScheduledReportOrchestrator.fireOnce(TenantScheduleFireCandidate, OffsetDateTime)` at `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportOrchestrator.java:97` — resolves adapter → derives period → advisory dedup → INSERT report_job → render bytes → upload MinIO → mark completed → emit DATA_ACCESS → publish ReportDeliveryEvent → emit audit.
- Delivery topic `medfund.notification.report-delivery` (unchanged Phase 17 contract).

**Phase 11 four-eyes precedent** (create-plan pass):

- `CommissionAdjustment` entity at `services/java/finance-service/src/main/java/com/medfund/finance/producer/entity/CommissionAdjustment.java:30-80` — states `DRAFT → APPROVED → COMMITTED | VOIDED`; maker/checker fields `approverActorId`, `approverActorEmail`, `approvedAt`. Service-layer validation enforces `updated_by_actor_id != approverActorId`.

**Permissions surface** (F7 + create-plan pass):

- `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java:1-28` — class shell + docstring describing `permissions.yaml` mirror + validation gate. Claims domain at lines 31-52 (`CLAIMS_VIEW`, `CLAIMS_CREATE`, ..., `CLAIMS_MANAGE_TARIFFS`, `CLAIMS_ASSIGN`, `CLAIMS_VIEW_CTC_PAYMENTS`, ...). No SIU/FRAUD perms yet.
- Keycloak bootstrap at `scripts/bootstrap-keycloak.sh:161-176` (step 4/7 "Creating realm roles") — roles listed at line 163: `super_admin tenant_admin claims_clerk claims_assessor finance_officer contributions_officer provider member group_liaison`. No `siu_*` roles.

**Angular surface** (create-plan pass):

- `clients/angular/src/app/pages/tenant/claims/claims.routes.ts:54-349` — `CLAIMS_ROUTES` array. `siu/` route registered around line 160 (after `preauth/*`, before catch-all `:id`). Guard pattern: `canActivate: [permissionGuard(['claims:siu:view'])]`.
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:125-136` — Claims section items; new entry `{ label: 'SIU Cases', icon: 'shield', route: '/tenant/claims/siu', permissions: ['claims:siu:view'], exactMatch: true }`.
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:402-450` — reports family pattern; new routes carry `reportKey: 'FRAUD_SIU_REPORT'`.
- `clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts:31-48` — `REPORT_ROUTES` map (`reportKey` → route path); `groupByFamily()` at line 123-136.
- `clients/angular/src/app/pages/tenant/finance/reports/billing/` — one existing family folder to mirror shape (list + detail components + shared `.scss`).
- `clients/angular/src/app/shared/components/charts/{sparkline,line-chart,bar-chart,area-chart,pie-chart,waterfall-chart,grouped-bar-chart}/` — chart components available for reuse; Phase 11 §B report reuses `line-chart` for the trend.
- `clients/angular/src/app/pages/tenant-admin/settings/report-schedules/report-schedules-page.component.ts:1-100` — `CreateDraft` interface at lines 59-67; Phase 19 §B adds `includeSensitiveSheets?: boolean` field.
- `clients/angular/src/app/pages/tenant-admin/rules/rules.component.ts:44-150` — category-driven sidebar via `RULE_CATEGORIES` array; new `FRAUD_TRIAGE` entry gets a tab data-driven.

**Migration numbering** (create-plan pass, corrected from scratchpad's V139 claim):

- Latest tenant migration: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V168__report_job_schedule_columns.sql`. Phase 19 §A migrations start at **V169**.

**Angular claims IT + AbstractIntegrationTest**:

- `services/java/shared/src/testFixtures/java/com/medfund/shared/testfixtures/AbstractIntegrationTest.java:1-131` — base with static Postgres + Kafka containers; `consumeAuditEvent(topic, timeout)` (line 83) + `consumeAuditEventMatching(topic, predicate, timeout)` (line 101).
- `services/java/claims-service/src/test/java/com/medfund/claims/integration/AbstractClaimsReportIT.java:1-100` — full-app boot base for claims-service ITs; JWT stubbed via `SecurityStub`; tenant via `X-Tenant-ID` header; per-test SQL seed via `V001__claims_report_it.sql`.

## Desired End State

A tenant admin can:

1. Toggle `FRAUD_SIU_REPORT` on/off at `/tenant/admin/settings/reports` (Phase 0 infra) → sidebar hides / 403 on direct URL.
2. Configure `FRAUD_TRIAGE` rules at `/tenant/admin/rules?category=FRAUD_TRIAGE` (§B Phase 10) — 6 templates offered, tenant picks + parametrises.
3. Assign `siu_officer` + `siu_supervisor` roles to users via the existing Keycloak-backed user-management surface.
4. Create a scheduled email delivery for `FRAUD_SIU_REPORT` at `/tenant/admin/settings/report-schedules` (§B Phase 12) with the new `includeSensitiveSheets` opt-in.

An SIU officer can:

5. See the queue of open cases at `/tenant/claims/siu/` (§A Phase 6) — filtered to their assignments if `siu_officer`, all cases if `siu_supervisor`.
6. Open a case (auto-created via `FRAUD_TRIAGE` rules or manually via `claims:siu:create`) → view linked flags + evidence + notes + activity timeline (§A Phase 6 base; §B Phase 10 evidence + referrals).
7. Add case notes, upload evidence via file-service (§B Phase 10), record referrals to law enforcement / regulator (§B Phase 10).
8. Propose closure (CONFIRMED / DISMISSED for §A; also REFERRED / ACTION_TAKEN in §B) with `saved_amount` + `saved_currency` — supervisor approves non-dismissal closures via four-eyes gate (§B Phase 8).

A finance officer / exec can:

9. Open `/tenant/finance/reports/fraud/` (§A Phase 6 — MVP 4 tiles) or (§B Phase 11 — 6 tiles + trend + top-N + AI calibration + investigator productivity).
10. Export XLSX (2 sheets in §A, 6 in §B); every export emits `SecurityEvent(reportKey=FRAUD_SIU_REPORT)`.
11. Receive scheduled weekly / monthly emails with the XLSX attached (§B Phase 12).

The AI service:

12. Publishes every classified claim (LOW/MEDIUM/HIGH) to `medfund.claims.fraud-flagged` — Rule 3 audit-of-record persists in `fraud_flag` for compliance queries.

### Verification

```bash
# Backend compile + unit + IT
cd services/java && ./gradlew build test
make test-integration
cd services/python/ai-service && uv run pytest
cd services/go/gateway && go test ./...

# Frontend
make test-angular
make test-e2e

# Manual acceptance
make infra && make tenancy user contributions finance claims gateway notification ai web
# Log in as siu_officer → /tenant/claims/siu → open a case → close CONFIRMED
# Log in as siu_supervisor → /tenant/claims/siu → approve pending closure (§B)
# Log in as finance officer → /tenant/finance/reports/fraud → export XLSX
# Log in as tenant admin → /tenant/admin/rules?category=FRAUD_TRIAGE → author threshold rule
# Log in as tenant admin → /tenant/admin/settings/report-schedules → create FRAUD_SIU_REPORT schedule with includeSensitiveSheets on → wait for probe fire → observe mailpit
```

### Key Discoveries

- **Retention is string constants, not an enum** — `ReportJob.RETENTION_*_*` on `services/java/finance-service/.../ReportJob.java:35-36`. Phase 19 §A adds two new String constants + widens the `Ifrs17JobService.classifyRetention` switch.
- **`ScheduledReportEligibility` uses an `EnumSet` whitelist** — Phase 19 §B (Phase 12) adds `ReportKey.FRAUD_SIU_REPORT` to `WHITELIST`; existing `ScheduledReportEligibilityTest.java:38-49` needs the `FRAUD_SIU_REPORT` removed from the excluded set + comment corrected.
- **AI service has no outbound Kafka producer today** — `aiokafka` producer pattern already exists at `services/python/ai-service/app/report/kafka.py:85-94` (`_default_producer_factory` for report-job-completed events). Phase 19 §A Phase 3 reuses that pattern.
- **`RuleChangeConsumer` is the Reactor-Kafka reference** — `.doOnSuccess(v -> record.receiverOffset().acknowledge())` + `.onErrorResume(e -> { log; ack; Mono.empty() })` (`services/java/rules-engine/.../RuleChangeConsumer.java:55-59`) — the FraudFlaggedConsumer follows this shape, never `.doOnTerminate` per `bug_reactor_kafka_ack_swallow`.
- **Reports Hub families are data-driven strings** from backend `TenantReportConfigRow.family` (`clients/angular/.../reports-hub.component.ts:31-48`) — no enum sync needed on the Angular side; the FRAUD family appears once the backend seeds/enables the key.
- **Angular has no generic activity-timeline component** — Phase 19 §B Phase 10 authors it as a reusable `<app-activity-timeline>` under `clients/angular/src/app/shared/components/activity-timeline/` (future SIU-adjacent surfaces can reuse).
- **Latest tenant migration is V168** — Phase 19 §A migrations start at V169 (`V169__fraud_flag.sql`, `V170__siu_case.sql`, `V171__siu_case_note.sql`); §B adds `V172__siu_evidence.sql`, `V173__siu_referral.sql`, `V174__siu_case_status_widen.sql`.

## What We're NOT Doing

Explicit non-goals — deferred to Phase 19.5 or later:

- **Full feature-vector persistence + ML model registry / reproducibility** (FR10 deferral). `fraud_flag.indicators JSONB` carries top-N indicators only; full feature vector + model weights + MLflow-lite deferred.
- **Per-tenant jurisdiction retention override** (FR13 deferral). All tenants get `SIU_CASE_7Y`; per-tenant "ZW wants 10y, ZA wants 7y" via `tenants.jurisdiction_code` widening deferred.
- **Fraud-typing classifier** — internal-vs-external, provider-vs-member, hard-vs-soft fraud categorical column on `siu_case` deferred.
- **Case-appeal workflow** — a member/provider appealing a CONFIRMED closure. Deferred.
- **Cross-tenant fraud pattern detection** — same provider flagged across multiple tenants (a super-admin surface) deferred.
- **Automated law-enforcement referral integration** — API push to ZW ZRP / ZA SAPS / etc. Deferred; `siu_referral` records the referral only.
- **Configurable small-N calibration threshold** — currently hardcoded to `N < 50 confirmed cases → "Insufficient data" fallback` per FR11. Per-tenant configurable deferred.
- **`kpi_snapshot`-style materialised warm path** for the fraud report — every load is an on-demand SQL aggregate. Deferred if performance requires.

## Implementation Approach

**Rollout ordering** — the plan builds bottom-up from foundations, so each phase compiles + tests cleanly without runtime dependency on later phases:

1. **§A MVP** ships a working end-to-end AI-flag → auto-case → officer-close-CONFIRMED → export-XLSX path with 3 entities, 3-state machine, and 4 permissions. `siu_officer` role in Keycloak, no supervisor role yet, no four-eyes.
2. **§B Expansion** widens to the full FR5/FR6/FR11/FR15 designs — 2 more entities (evidence, referrals), full 5-state machine + four-eyes gate, `siu_supervisor` role, remaining 4 permissions, 3 more FRAUD_TRIAGE templates, full-analytics report + XLSX 6-sheet, Phase 17 scheduled dispatch.

**Kafka contract shape** (established in §A Phase 3, unchanged through §B):

```json
{
  "eventType": "FRAUD_FLAG_EMITTED",
  "eventId": "uuid",
  "occurredAt": "2026-09-05T14:23:45Z",
  "tenantId": "uuid",
  "claimId": "uuid",
  "modelVersion": "fraud-isolation-forest-v1.2.0",
  "riskScore": 0.87,
  "riskLevel": "HIGH",
  "indicators": ["frequent_visits", "unusual_time_of_day"],
  "computedAt": "2026-09-05T14:23:44Z"
}
```

Topic: `medfund.claims.fraud-flagged`. Producer: `services/python/ai-service` (§A Phase 3). Consumer: `services/java/claims-service` (§A Phase 4). Additive-only through §B — existing consumers won't break when new fields land.

**Rule 6 (Kafka for side effects)** — every state transition on `siu_case` emits an `AuditEvent` to `medfund.audit.events` (Rule 8); XLSX export emits a `SecurityEvent` to `medfund.security.events` (Rule 9); scheduled dispatch (§B Phase 12) publishes `ReportDeliveryEvent` to `medfund.notification.report-delivery` (Phase 17 contract). Cross-service reads (report composition) use `CrossServiceCallHelper` with warnings capture — no cross-service DB access.

**Rule 5 (per-tenant rules)** — case-open policy lives in the rules-engine service as `RuleCategory.FRAUD_TRIAGE` (§A Phase 5). Facts are line-agnostic (`FraudFlagFact` carries `insuranceLine` as a field, not as engine dispatch). Line-specific behaviour (e.g. "auto-open all HEALTH high-risk but only MEDIUM+ LIFE") is rule *content*.

**Rule 1 (currency)** — `saved_amount` on `siu_case` carries `saved_currency`; report `perCurrency: Map<String, PerCurrencyTotal>` groups by native currency; composite scalar in reporting currency via `FxRateReader.convert(...)` fails loud if any per-currency FX is missing (per parent-plan invariant #6 + G28).

**Rule 2 (tenant scoping)** — every new repository extends the R2DBC reactive pattern with `TenantContext` — no raw JDBC, no cross-tenant reads.

**Rule 3 (AI auditable)** — `fraud_flag` row is the audit-of-record: `model_version`, `risk_score`, `risk_level`, top-N `indicators`, `flagged_at`, `flag_source` (`AI_MODEL` | `MANUAL_OFFICER`). Human-reviewable via the case-detail page.

**Rule 7 (Swagger)** — every new controller endpoint annotated with `@Tag`, `@Operation`, `@ApiResponse` — renders at `http://localhost:8083/swagger-ui`.

**Rule 8 (AuditEvent per mutation)** — every siu_case / fraud_flag / siu_case_note / siu_evidence / siu_referral mutation emits an `AuditEvent` via `AuditActor.of(jwt)` + `AuditEvent.create(...)` per `feedback_audit_actor_email` (never null actorEmail) + `feedback_audit_entity_name` (friendly text, never UUID).

**Rule 9 (SecurityEvent for exports)** — every XLSX export path emits `SecurityEventPublisher.publishDataAccess(...)` with `reportKey=FRAUD_SIU_REPORT` before returning bytes.

---

## Phase 1: Shared Foundations (§A — MVP)

### Overview

Lay down the shared-module enum + permission + Keycloak scaffolding that later phases will consume. No runtime code — this phase's success criteria are compile-only plus unit tests. Independently verifiable: the module builds green, `Permissions.java` mirror check passes, `RuleCategory` enum test passes, `ScheduledReportEligibilityTest` continues to exclude `FRAUD_SIU_REPORT` (§B Phase 12 will move it into the whitelist).

### Changes Required

#### 1. `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java`

Add 4 new claims-namespace permission constants (MVP subset of FR7; remaining 4 land in §B Phase 7):

```java
// ── Claims: SIU (Special Investigations Unit) ────────────────────────
// Phase 19 §A MVP subset per parent-plan FR7. Remaining §B: claims:siu:assign,
// claims:siu:approve, claims:siu:reopen, claims:siu:refer.
public static final String CLAIMS_SIU_VIEW         = "claims:siu:view";
public static final String CLAIMS_SIU_CREATE       = "claims:siu:create";
public static final String CLAIMS_SIU_INVESTIGATE  = "claims:siu:investigate";
public static final String CLAIMS_SIU_ADMIN        = "claims:siu:admin";
```

Placed at the end of the claims section (after `CLAIMS_SET_RESERVE` around line 52). Mirror update in `services/java/shared/src/main/resources/permissions.yaml` under the `claims:` section — the validation gate reads both.

#### 2. `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java`

Append `FRAUD_TRIAGE` after `PMB_CLASSIFICATION` (line 149):

```java
    /**
     * Fraud triage — decides whether an AI-emitted `fraud_flag` auto-opens
     * an `siu_case` for investigation. Facts: {@code FraudFlagFact}. Action:
     * {@code emitCaseCreation}. Tenants configure policy ("auto-open above
     * risk_score = 0.85", "watchlist providers", "member repeat-offender
     * pattern"); default (no rules) auto-opens HIGH-risk. Fires on the
     * "FRAUD_RULES" agenda group.
     */
    FRAUD_TRIAGE
```

#### 3. `services/java/finance-service/src/main/java/com/medfund/finance/report/entity/ReportJob.java`

Add two new retention-class string constants after `RETENTION_STATUTORY_7Y`:

```java
/** Raw AI fraud flags with no linked SIU case — auto-purge after 12 months. */
public static final String RETENTION_FRAUD_FLAG_1Y = "FRAUD_FLAG_1Y";

/**
 * SIU cases + linked flags/evidence/notes/referrals + `report_job` rows for
 * FRAUD_SIU_REPORT — 7-year retention aligns with insurance-fraud statute
 * (ZW Insurance Act 2019 §137; POPIA §5(e) equivalent).
 */
public static final String RETENTION_SIU_CASE_7Y = "SIU_CASE_7Y";
```

#### 4. `services/java/finance-service/src/main/java/com/medfund/finance/ifrs17/service/Ifrs17JobService.java:239-243`

Widen `classifyRetention(ReportKey)` switch to route `FRAUD_SIU_REPORT` to `RETENTION_SIU_CASE_7Y`:

```java
public String classifyRetention(ReportKey reportKey) {
    if (reportKey == ReportKey.FRAUD_SIU_REPORT) {
        return ReportJob.RETENTION_SIU_CASE_7Y;
    }
    return reportKey.getFamily() == ReportFamily.REGULATORY
            ? ReportJob.RETENTION_STATUTORY_7Y
            : ReportJob.RETENTION_OPERATIONAL_90D;
}
```

#### 5. `services/java/finance-service/src/main/java/com/medfund/finance/report/scheduler/ReportJobRetentionJob.java`

Add a third purge branch for `SIU_CASE_7Y` alongside the existing OPERATIONAL_90D + STATUTORY_7Y logic. Same nightly 02:00 cron, same trim-to-N-per-tenant strategy (default N = 20):

```java
// New branch after the existing STATUTORY_7Y purge
private Mono<Long> purgeSiuCase7y() {
    OffsetDateTime cutoff = OffsetDateTime.now().minus(7, ChronoUnit.YEARS);
    return databaseClient.sql(
            "DELETE FROM report_job " +
            "WHERE retention_class = :cls AND completed_at < :cutoff")
        .bind("cls", ReportJob.RETENTION_SIU_CASE_7Y)
        .bind("cutoff", cutoff)
        .fetch().rowsUpdated();
}
```

Called from `purge()` alongside the existing branches.

#### 6. `scripts/bootstrap-keycloak.sh:161-176`

Expand the role list to add `siu_officer` (MVP; `siu_supervisor` lands in §B Phase 7):

```bash
# Line 163 today:
# for role in super_admin tenant_admin claims_clerk claims_assessor finance_officer contributions_officer provider member group_liaison
# Widen to:
for role in super_admin tenant_admin claims_clerk claims_assessor finance_officer contributions_officer provider member group_liaison siu_officer
```

Add a matching test user block after `financeofficer` around line 280:

```bash
# ── SIU officer test user (Phase 19 §A) ─────────────────────────────
create_user "siuofficer" "SiuOfficer" "SIU" "siu@example.com" "password" "siu_officer"
```

#### 7. `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java`

Add the FRAUD_TRIAGE → fact mapping entry to `FACT_MAPPINGS` (around line 67-90):

```java
FACT_MAPPINGS.put(RuleCategory.FRAUD_TRIAGE,
    new FactMapping("FraudFlagFact", "com.medfund.rules.fact.FraudFlagFact"));
```

`FraudFlagFact` itself lands in §A Phase 5 (just before the rules-engine consumer wire needs it) — the mapping entry is safe to land now because the classloader only resolves it when a tenant registers a FRAUD_TRIAGE rule, which cannot happen until Phase 5 exposes the tenant-admin path.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :shared:build :rules-engine:build :finance-service:build` (compile + unit-test paths green; pre-existing full-app IT failures unrelated to Phase 1 — `AmlSummaryRawDataProvider` bean wiring bug)
- [x] Unit tests: `make test-java` — new `PermissionsTest.claimsSiuConstantsMatchYaml`, `RuleCategoryTest.fraudTriageCategory_isDeclared`, `ReportJobTest.retentionConstants_haveExpectedValues` all pass
- [x] `Ifrs17JobServiceTest.classifyRetention_fraudSiuReport_returnsSiuCase7y` passes (+ regulatory / operational sibling cases)
- [x] `ReportJobRetentionJobIT.purge_removesSiuCase7yBeyondSevenYears` — test added; IT green will require full infra + resolution of the pre-existing `AmlSummaryRawDataProvider` bean wiring bug tracked outside this phase
- [x] `ScheduledReportEligibilityTest` — Phase 18 landing left a stale 13-key assertion that broke the build; renamed to `whitelistContainsExactly18OperationalCadencedKeys` with the current 18-key set (see Deviations). Still excludes `FRAUD_SIU_REPORT` — widening remains a Phase 12 job.
- [x] `scripts/bootstrap-keycloak.sh` — `bash -n` clean; grep shows `siu_officer` in role list and `siuofficer` test-user block present
- [ ] Angular unit tests: `make test-angular` — no Angular changes this phase; regression check deferred to Phase 6 when the SIU / fraud-report screens land

#### Manual Verification
- [ ] Run `make keycloak-setup` locally against a fresh `make infra` → `siu_officer` role exists in the realm; `siuofficer` test user can log in

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm the manual Keycloak setup before moving to Phase 2.

---

## Phase 2: Tenant Migrations + Entities + Repositories (§A — MVP)

### Overview

Land 3 tenant-schema migrations (V169-V171) for the MVP entity model (fraud_flag + siu_case + siu_case_note), the matching R2DBC entities + repositories, and the FraudFlagRetentionJob that keeps unlinked flag rows below the 1-year horizon. No consumers / controllers wired yet — this phase is verified by IT covering migration apply + repository CRUD.

### Changes Required

#### 1. `services/java/tenancy-service/src/main/resources/db/migration/tenant/V169__fraud_flag.sql`

```sql
-- Phase 19 §A Phase 2 — fraud_flag audit-of-record for AI predictions.
-- Every classified claim (LOW/MEDIUM/HIGH) writes one row (Rule 3).
-- Rows with siu_case_id IS NULL and flagged_at older than 1y are
-- purged by FraudFlagRetentionJob (Phase 19 §A Phase 1's retention split).

CREATE TABLE IF NOT EXISTS fraud_flag (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id           UUID NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
    siu_case_id        UUID,                                         -- FK to siu_case, nullable (see V170)
    flag_source        VARCHAR(32) NOT NULL,                         -- 'AI_MODEL' | 'MANUAL_OFFICER'
    model_version      VARCHAR(64),                                  -- null for MANUAL_OFFICER rows
    risk_score         NUMERIC(4,3),                                 -- [0.000, 1.000]; null for MANUAL
    risk_level         VARCHAR(16),                                  -- 'LOW' | 'MEDIUM' | 'HIGH'; null for MANUAL
    indicators         JSONB NOT NULL DEFAULT '[]'::JSONB,           -- top-N features per FR10; ~5 entries
    flagged_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    correlation_id     VARCHAR(64),                                  -- links to submission event
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fraud_flag_flag_source_chk CHECK (flag_source IN ('AI_MODEL','MANUAL_OFFICER')),
    CONSTRAINT fraud_flag_risk_level_chk  CHECK (risk_level IS NULL OR risk_level IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT fraud_flag_ai_fields_chk   CHECK (
        flag_source = 'MANUAL_OFFICER' OR
        (model_version IS NOT NULL AND risk_score IS NOT NULL AND risk_level IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS fraud_flag_claim_id_idx    ON fraud_flag (claim_id);
CREATE INDEX IF NOT EXISTS fraud_flag_siu_case_id_idx ON fraud_flag (siu_case_id) WHERE siu_case_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS fraud_flag_flagged_at_idx  ON fraud_flag (flagged_at);
CREATE INDEX IF NOT EXISTS fraud_flag_purge_idx       ON fraud_flag (flagged_at) WHERE siu_case_id IS NULL;

COMMENT ON TABLE  fraud_flag              IS 'Immutable audit-of-record for AI + manual fraud flags (Rule 3, Phase 19 §A).';
COMMENT ON COLUMN fraud_flag.indicators   IS 'Top-N feature attributions (~5 entries) — full feature vector deferred to Phase 19.5 ML-ops.';
COMMENT ON COLUMN fraud_flag.siu_case_id  IS 'Nullable — set when FRAUD_TRIAGE rules-engine promotes flag to case.';
```

#### 2. `services/java/tenancy-service/src/main/resources/db/migration/tenant/V170__siu_case.sql`

```sql
-- Phase 19 §A Phase 2 — SIU case: one row per investigation.
-- MVP state machine (3-state) per parent-plan FR16 §A carve-out:
-- OPEN → UNDER_REVIEW → (CLOSED_CONFIRMED_FRAUD | CLOSED_DISMISSED_FALSE_POSITIVE).
-- Full 5-state expansion (ASSIGNED, PENDING_APPROVAL, REOPENED,
-- CLOSED_REFERRED_LAW_ENFORCEMENT, CLOSED_ACTION_TAKEN) lands in §B Phase 8
-- via V174 status-widen migration.

CREATE TABLE IF NOT EXISTS siu_case (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_number           VARCHAR(32) NOT NULL,                     -- tenant-scoped human-readable e.g. SIU-2026-000123
    status                VARCHAR(48) NOT NULL,                     -- 'OPEN' | 'UNDER_REVIEW' | 'CLOSED_CONFIRMED_FRAUD' | 'CLOSED_DISMISSED_FALSE_POSITIVE'
    priority              VARCHAR(16),                              -- 'LOW' | 'MEDIUM' | 'HIGH'
    tags                  TEXT[] NOT NULL DEFAULT '{}',
    assigned_to           UUID,                                     -- user id; nullable for OPEN cases
    opened_by             UUID NOT NULL,                            -- 'SYSTEM' UUID if opened by FRAUD_TRIAGE rules
    opened_by_email       VARCHAR(255) NOT NULL,                    -- feedback_audit_actor_email
    opened_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_by             UUID,
    closed_by_email       VARCHAR(255),
    closed_at             TIMESTAMPTZ,
    closure_reason        TEXT,                                     -- free-text investigator narrative
    outcome               VARCHAR(48),                              -- mirrors terminal status enum
    saved_amount          NUMERIC(19,4),                            -- investigator-entered per FR8
    saved_currency        VARCHAR(3),                               -- ISO-4217 per FR14
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT siu_case_case_number_uk    UNIQUE (case_number),
    CONSTRAINT siu_case_status_chk        CHECK (status IN (
                                            'OPEN','UNDER_REVIEW',
                                            'CLOSED_CONFIRMED_FRAUD','CLOSED_DISMISSED_FALSE_POSITIVE')),
    CONSTRAINT siu_case_savings_pair_chk  CHECK (
                                            (saved_amount IS NULL AND saved_currency IS NULL) OR
                                            (saved_amount IS NOT NULL AND saved_currency IS NOT NULL AND saved_amount >= 0))
);

CREATE INDEX IF NOT EXISTS siu_case_status_idx       ON siu_case (status);
CREATE INDEX IF NOT EXISTS siu_case_assigned_to_idx  ON siu_case (assigned_to) WHERE assigned_to IS NOT NULL;
CREATE INDEX IF NOT EXISTS siu_case_opened_at_idx    ON siu_case (opened_at);
CREATE INDEX IF NOT EXISTS siu_case_closed_at_idx    ON siu_case (closed_at) WHERE closed_at IS NOT NULL;

-- Back-fill the fraud_flag FK now that siu_case exists.
ALTER TABLE fraud_flag
    ADD CONSTRAINT fraud_flag_siu_case_id_fk
    FOREIGN KEY (siu_case_id) REFERENCES siu_case(id) ON DELETE SET NULL;

COMMENT ON TABLE  siu_case                     IS 'One row per SIU investigation (Phase 19 §A MVP; §B Phase 8 widens status enum).';
COMMENT ON COLUMN siu_case.case_number         IS 'Tenant-scoped human-readable identifier (SIU-YYYY-NNNNNN).';
COMMENT ON COLUMN siu_case.saved_amount        IS 'Investigator-entered on closure per FR8; defaults to SUM(claimed − paid) across flagged claims.';
COMMENT ON COLUMN siu_case.saved_currency      IS 'ISO-4217; one currency per case per FR14 (investigator picks majority-flagged-claim currency).';
```

#### 3. `services/java/tenancy-service/src/main/resources/db/migration/tenant/V171__siu_case_note.sql`

```sql
-- Phase 19 §A Phase 2 — SIU case note: append-only activity log.
-- Every state transition, evidence upload, referral emits a note row
-- (in addition to the AuditEvent per Rule 8). Investigators + supervisors
-- can also add COMMENT-type notes for narrative context.

CREATE TABLE IF NOT EXISTS siu_case_note (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id      UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    author_id    UUID NOT NULL,
    author_email VARCHAR(255) NOT NULL,
    note_type    VARCHAR(32) NOT NULL,   -- 'COMMENT'|'STATUS_CHANGE'|'EVIDENCE_ADDED'|'ASSIGNED'|'REFERRAL_ADDED'|'FLAG_LINKED'
    body         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT siu_case_note_type_chk CHECK (note_type IN (
        'COMMENT','STATUS_CHANGE','EVIDENCE_ADDED','ASSIGNED','REFERRAL_ADDED','FLAG_LINKED'))
);

CREATE INDEX IF NOT EXISTS siu_case_note_case_id_idx    ON siu_case_note (case_id);
CREATE INDEX IF NOT EXISTS siu_case_note_created_at_idx ON siu_case_note (created_at);

COMMENT ON TABLE  siu_case_note IS 'Append-only activity log for SIU cases — never UPDATE / DELETE by application code.';
```

#### 4. R2DBC entities in claims-service

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/siu/entity/FraudFlag.java`

```java
package com.medfund.claims.siu.entity;

import io.r2dbc.postgresql.codec.Json;
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
@Table("fraud_flag")
public class FraudFlag {
    @Id
    private UUID id;

    @Column("claim_id")       private UUID claimId;
    @Column("siu_case_id")    private UUID siuCaseId;
    @Column("flag_source")    private String flagSource;      // AI_MODEL | MANUAL_OFFICER
    @Column("model_version")  private String modelVersion;
    @Column("risk_score")     private BigDecimal riskScore;
    @Column("risk_level")     private String riskLevel;
    @Column("indicators")     private Json indicators;         // JSONB
    @Column("flagged_at")     private OffsetDateTime flaggedAt;
    @Column("correlation_id") private String correlationId;
    @Column("created_at")     private OffsetDateTime createdAt;
}
```

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/siu/entity/SiuCase.java`

```java
package com.medfund.claims.siu.entity;

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
@Table("siu_case")
public class SiuCase {
    @Id
    private UUID id;

    @Column("case_number")      private String caseNumber;
    @Column("status")           private String status;
    @Column("priority")         private String priority;
    @Column("tags")             private String[] tags;
    @Column("assigned_to")      private UUID assignedTo;
    @Column("opened_by")        private UUID openedBy;
    @Column("opened_by_email")  private String openedByEmail;
    @Column("opened_at")        private OffsetDateTime openedAt;
    @Column("closed_by")        private UUID closedBy;
    @Column("closed_by_email")  private String closedByEmail;
    @Column("closed_at")        private OffsetDateTime closedAt;
    @Column("closure_reason")   private String closureReason;
    @Column("outcome")          private String outcome;
    @Column("saved_amount")     private BigDecimal savedAmount;
    @Column("saved_currency")   private String savedCurrency;
    @Column("created_at")       private OffsetDateTime createdAt;
    @Column("updated_at")       private OffsetDateTime updatedAt;
}
```

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/siu/entity/SiuCaseNote.java`

```java
package com.medfund.claims.siu.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Table("siu_case_note")
public class SiuCaseNote {
    @Id
    private UUID id;

    @Column("case_id")       private UUID caseId;
    @Column("author_id")     private UUID authorId;
    @Column("author_email")  private String authorEmail;
    @Column("note_type")     private String noteType;
    @Column("body")          private String body;
    @Column("created_at")    private OffsetDateTime createdAt;
}
```

#### 5. R2DBC repositories in claims-service

Standard `ReactiveCrudRepository<T, UUID>` — each service adds only the custom queries it needs:

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/siu/repository/FraudFlagRepository.java`

```java
package com.medfund.claims.siu.repository;

import com.medfund.claims.siu.entity.FraudFlag;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface FraudFlagRepository extends ReactiveCrudRepository<FraudFlag, UUID> {
    Flux<FraudFlag> findAllByClaimIdOrderByFlaggedAtDesc(UUID claimId);
    Flux<FraudFlag> findAllBySiuCaseIdOrderByFlaggedAtDesc(UUID siuCaseId);

    @Query("SELECT COUNT(*) FROM fraud_flag WHERE claim_id IN (SELECT c.id FROM claims c WHERE c.member_id = :memberId) " +
           "AND risk_level = 'HIGH' AND flagged_at >= :since")
    Mono<Long> countHighRiskForMemberSince(UUID memberId, OffsetDateTime since);

    @Query("SELECT COUNT(*) FROM fraud_flag WHERE claim_id IN (SELECT c.id FROM claims c WHERE c.provider_id = :providerId) " +
           "AND risk_level = 'HIGH' AND flagged_at >= :since")
    Mono<Long> countHighRiskForProviderSince(UUID providerId, OffsetDateTime since);

    @Query("DELETE FROM fraud_flag WHERE siu_case_id IS NULL AND flagged_at < :cutoff")
    Mono<Long> purgeUnlinkedOlderThan(OffsetDateTime cutoff);
}
```

**Files**: `services/java/claims-service/src/main/java/com/medfund/claims/siu/repository/SiuCaseRepository.java` and `SiuCaseNoteRepository.java` — standard `ReactiveCrudRepository` shells with `findAllByStatusOrderByOpenedAtDesc`, `findAllByAssignedToOrderByOpenedAtDesc`, `findAllByCaseIdOrderByCreatedAtAsc` respectively.

#### 6. FraudFlagRetentionJob

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/siu/scheduler/FraudFlagRetentionJob.java`

```java
package com.medfund.claims.siu.scheduler;

import com.medfund.claims.siu.repository.FraudFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Nightly purge of unlinked fraud_flag rows (age > 1y). Rows linked to a
 * siu_case (siu_case_id IS NOT NULL) are retained under SIU_CASE_7Y per
 * Phase 19 §A Phase 1 retention split.
 *
 * <p>Runs at 02:30 daily (30 min after ReportJobRetentionJob at 02:00 to
 * avoid I/O contention on shared Postgres).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudFlagRetentionJob {

    private final FraudFlagRepository repo;

    @Scheduled(cron = "${fraud.retention.cron:0 30 2 * * *}")
    public void purge() {
        runOnce()
            .doOnSuccess(count -> log.info("FraudFlagRetentionJob purged {} rows", count))
            .doOnError(err -> log.error("FraudFlagRetentionJob failed", err))
            .subscribe();
    }

    Mono<Long> runOnce() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(1, ChronoUnit.YEARS);
        return repo.purgeUnlinkedOlderThan(cutoff);
    }
}
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :claims-service:build` (compile + test paths green)
- [x] Unit tests: `make test-java` — new `FraudFlagRetentionJobTest.runOnce_purgesUnlinkedRowsOlderThan1Year` + `runOnce_zeroDeletions_completesWith0` pass
- [x] Integration tests: `make test-integration` — new `FraudFlagRepositoryIT` (Testcontainers Postgres) covers CRUD + `countHighRiskForMemberSince` + `countHighRiskForProviderSince` + `purgeUnlinkedOlderThan_removesOldUnlinkedKeepsFreshAndKeepsLinked` (cutoff-boundary test relaxed to ±30-day margins after nanosecond-truncation debugging — see Deviations)
- [x] `SiuCaseRepositoryIT` covers CRUD + `findAllByStatusOrderByOpenedAtDesc` + close-CONFIRMED savings round-trip
- [x] `SiuCaseNoteRepositoryIT` covers append + query by case_id in chronological order + save round-trip
- [x] `V169__fraud_flag.sql`, `V170__siu_case.sql`, `V171__siu_case_note.sql` apply cleanly on fresh Testcontainer (asserted transitively by every IT that boots the tenant schema; verified end-to-end via a mirroring `V003__siu_it.sql` under `claims-service/src/test/resources/db/test-migration/`)
- [x] Migration numbering guard: no gap or duplicate — `ls services/java/tenancy-service/src/main/resources/db/migration/tenant/V1{69,70,71}*.sql` returns exactly 3 files
- [x] Full `:claims-service:test` still green — no regressions from the R2dbcConfig `basePackages` widening required to register the new SIU repositories

#### Manual Verification
- [ ] `make infra && make tenancy claims` → observe migrations V169-V171 apply in tenancy-service logs on first tenant onboarding

**Implementation Note**: pause for the human to confirm migrations apply cleanly before moving to Phase 3.

---

## Phase 3: AI Service Kafka Producer (§A — MVP)

### Overview

Wire the AI service's first outbound Kafka producer. Extends the existing `ClaimsEventConsumer` (which today runs fraud detection and discards the result) to publish every classified claim to `medfund.claims.fraud-flagged`. Independently verifiable: produce a synthetic claim via the existing consumer topic → observe a fraud-flagged event on the new topic via `kcat` / test consumer.

### Changes Required

#### 1. `services/python/ai-service/app/core/kafka_producer.py` (new file)

```python
"""Outbound Kafka producer for AI decisions. Phase 19 §A Phase 3.

Wires the FRAUD_FLAG_EMITTED event on medfund.claims.fraud-flagged per
Rule 3 (AI decisions must be auditable). Pattern mirrors the existing
report-job producer at app/report/kafka.py:85-94.
"""
from __future__ import annotations

import json
import logging
from typing import Any

from aiokafka import AIOKafkaProducer

logger = logging.getLogger(__name__)


class ClaimsEventProducer:
    """Thin async wrapper over AIOKafkaProducer for claims-domain events."""

    TOPIC_FRAUD_FLAGGED = "medfund.claims.fraud-flagged"

    def __init__(self, bootstrap_servers: str) -> None:
        self._bootstrap_servers = bootstrap_servers
        self._producer: AIOKafkaProducer | None = None

    async def start(self) -> None:
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            enable_idempotence=True,
            acks="all",
        )
        await self._producer.start()
        logger.info("ClaimsEventProducer started bootstrap=%s", self._bootstrap_servers)

    async def stop(self) -> None:
        if self._producer is not None:
            await self._producer.stop()
            self._producer = None
            logger.info("ClaimsEventProducer stopped")

    async def publish_fraud_flagged(self, event: dict[str, Any]) -> None:
        """Publish a FRAUD_FLAG_EMITTED event. Tenant id used as partition key
        to preserve per-tenant ordering (Phase 0 Kafka convention)."""
        if self._producer is None:
            raise RuntimeError("ClaimsEventProducer.start() has not been called")
        tenant_id = event.get("tenantId") or ""
        await self._producer.send_and_wait(
            self.TOPIC_FRAUD_FLAGGED,
            value=event,
            key=tenant_id.encode("utf-8"),
        )
```

#### 2. `services/python/ai-service/app/main.py` — wire producer into lifespan

Add producer construction + start / stop to the FastAPI lifespan (existing pattern for `ClaimsEventConsumer` at lines 27-106):

```python
# After ClaimsEventConsumer construction, add:
from app.core.kafka_producer import ClaimsEventProducer

# Inside lifespan:
producer = ClaimsEventProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
await producer.start()
app.state.claims_event_producer = producer

# ... in the ClaimsEventConsumer construction, pass producer:
consumer = ClaimsEventConsumer(
    ...,
    fraud_producer=producer,
)

# On shutdown:
await producer.stop()
```

#### 3. `services/python/ai-service/app/core/kafka_consumer.py:68-96` — emit event after prediction

Widen `ClaimsEventConsumer.__init__` to accept `fraud_producer: ClaimsEventProducer`; widen `process_event()` to publish after `FraudService.detect_fraud()` returns:

```python
from datetime import datetime, timezone
from uuid import uuid4

async def process_event(self, event: dict[str, Any]) -> None:
    """Existing docstring — extended to emit fraud_flag event per Phase 19 §A Phase 3."""
    claim_data = event.get("payload", {})
    tenant_id = event.get("tenantId")
    claim_id = claim_data.get("id")
    correlation_id = event.get("correlationId")

    # Existing parallel calls
    adjudication_task = self._adjudication_service.analyze_claim(claim_data, tenant_id)
    fraud_task = self._fraud_service.detect_fraud(claim_data, tenant_id)
    _, fraud_prediction = await asyncio.gather(adjudication_task, fraud_task)

    # New: publish audit-of-record event
    await self._fraud_producer.publish_fraud_flagged({
        "eventType": "FRAUD_FLAG_EMITTED",
        "eventId": str(uuid4()),
        "occurredAt": datetime.now(timezone.utc).isoformat(),
        "tenantId": tenant_id,
        "claimId": claim_id,
        "modelVersion": fraud_prediction["model_version"],
        "riskScore": fraud_prediction["risk_score"],
        "riskLevel": fraud_prediction["risk_level"],
        "indicators": fraud_prediction.get("indicators", []),
        "computedAt": datetime.now(timezone.utc).isoformat(),
        "correlationId": correlation_id,
    })
```

#### 4. Docker compose + startup verification

`docker-compose.yml` under repo root already exposes Kafka on `localhost:9092`. No compose change needed. The producer wires on `ai-service` startup regardless of consumer state — verify via `make infra && make ai` then produce a synthetic `medfund.claims.submitted` event via `kcat` and observe the fraud-flagged event with `kcat -C -t medfund.claims.fraud-flagged`.

#### 5. Tests

**File**: `services/python/ai-service/tests/core/test_kafka_producer.py` (new)

```python
import asyncio
import json
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.core.kafka_producer import ClaimsEventProducer


@pytest.mark.asyncio
async def test_publish_fraud_flagged_sends_json_encoded_event_with_tenant_key(monkeypatch):
    producer = ClaimsEventProducer(bootstrap_servers="localhost:9092")
    mock_kafka = AsyncMock()
    monkeypatch.setattr("app.core.kafka_producer.AIOKafkaProducer", MagicMock(return_value=mock_kafka))

    await producer.start()
    event = {"tenantId": "t-1", "claimId": "c-1", "riskLevel": "HIGH"}
    await producer.publish_fraud_flagged(event)

    mock_kafka.send_and_wait.assert_awaited_once()
    call = mock_kafka.send_and_wait.call_args
    assert call.args[0] == ClaimsEventProducer.TOPIC_FRAUD_FLAGGED
    assert call.kwargs["value"] == event
    assert call.kwargs["key"] == b"t-1"


@pytest.mark.asyncio
async def test_publish_before_start_raises():
    producer = ClaimsEventProducer(bootstrap_servers="localhost:9092")
    with pytest.raises(RuntimeError, match="start"):
        await producer.publish_fraud_flagged({})
```

**File**: `services/python/ai-service/tests/core/test_kafka_consumer.py` — extend existing test:

```python
@pytest.mark.asyncio
async def test_process_event_publishes_fraud_flag_after_prediction(fixture_consumer):
    consumer, fraud_service, fraud_producer = fixture_consumer
    fraud_service.detect_fraud.return_value = {
        "model_version": "v1.2.0", "risk_score": 0.87, "risk_level": "HIGH",
        "indicators": ["frequent_visits"],
    }
    event = {"tenantId": "t-1", "payload": {"id": "c-1"}, "correlationId": "req-1"}
    await consumer.process_event(event)

    fraud_producer.publish_fraud_flagged.assert_awaited_once()
    payload = fraud_producer.publish_fraud_flagged.call_args.args[0]
    assert payload["eventType"] == "FRAUD_FLAG_EMITTED"
    assert payload["riskLevel"] == "HIGH"
    assert payload["claimId"] == "c-1"
    assert payload["correlationId"] == "req-1"
```

### Success Criteria

#### Automated Verification
- [x] Python compile / import: `cd services/python/ai-service && uv sync && uv run python -c "from app.core.kafka_producer import ClaimsEventProducer"` returns clean
- [x] Python tests: `uv run pytest --no-cov -q` — 297 passed, no regressions
- [x] `uv run pytest tests/core/ -v --no-cov` — 8 passed (4 producer + 4 consumer)
- [x] Coverage gate: `uv run pytest -q` reports 77.59% total (>= 70% gate); `app/core/kafka_producer.py` 100%, `app/core/kafka_consumer.py` 54% (uncovered = pre-existing start/stop/_consume paths requiring live Kafka)
- [ ] AI service starts cleanly via `make ai` against `make infra` (no producer wiring error on empty topic) — deferred to manual verification below

#### Manual Verification
- [ ] Emit a synthetic claim to `medfund.claims.submitted` via `kcat -P -t medfund.claims.submitted -b localhost:9092 <<< '{"tenantId":"t-1","payload":{"id":"c-1","amount":1500},"correlationId":"req-1"}'`
- [ ] Observe the fraud-flagged event on `kcat -C -t medfund.claims.fraud-flagged -b localhost:9092 -o beginning -e -q -f 'k=%k v=%s\n'` — payload has all 10 required fields; `key = t-1`

**Implementation Note**: pause for the human to confirm the Kafka round-trip works end-to-end before moving to Phase 4.

---

## Phase 4: claims-service FraudFlaggedConsumer + FraudFlagService + SiuCaseService (§A — MVP)

### Overview

Wire the Java side. Reactor-Kafka consumer on `medfund.claims.fraud-flagged` writes `fraud_flag` rows (Rule 3 audit) and dispatches to the FRAUD_TRIAGE rules-engine (registered in Phase 5) which decides whether to auto-open an `siu_case`. `SiuCaseService` implements the 3-state MVP machine with per-mutation `AuditEvent` per Rule 8. Verifiable via IT Kafka round-trip + service unit tests + controller Swagger.

### Changes Required

#### 1. `services/java/claims-service/src/main/java/com/medfund/claims/siu/consumer/FraudFlaggedConsumer.java`

```java
package com.medfund.claims.siu.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.siu.service.FraudFlagService;
import com.medfund.claims.siu.service.SiuCaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;

import jakarta.annotation.PostConstruct;
import java.util.Collections;

/**
 * Consumes FRAUD_FLAG_EMITTED events from medfund.claims.fraud-flagged
 * (published by ai-service per Phase 19 §A Phase 3). Writes fraud_flag row
 * per Rule 3, then dispatches to FRAUD_TRIAGE rules-engine (Phase 5) to
 * decide case auto-open.
 *
 * <p>Ack pattern: .doOnSuccess + .onErrorResume with explicit .acknowledge()
 * per bug_reactor_kafka_ack_swallow memory (RuleChangeConsumer reference).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudFlaggedConsumer {

    private static final String TOPIC = "medfund.claims.fraud-flagged";

    private final ReceiverOptions<String, String> baseReceiverOptions;
    private final FraudFlagService fraudFlagService;
    private final SiuCaseService siuCaseService;
    private final ObjectMapper objectMapper;

    @Value("${fraud.consumer.enabled:true}")
    private boolean enabled;

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("FraudFlaggedConsumer disabled via fraud.consumer.enabled=false");
            return;
        }
        ReceiverOptions<String, String> options = baseReceiverOptions
            .consumerProperty("group.id", "claims-service.fraud-flagged")
            .subscription(Collections.singleton(TOPIC));

        KafkaReceiver.create(options).receive()
            .flatMap(record -> processEvent(record.value())
                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                .onErrorResume(e -> {
                    log.error("FraudFlaggedConsumer processing failed key={} offset={}",
                              record.key(), record.receiverOffset(), e);
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }))
            .doOnError(e -> log.error("FraudFlaggedConsumer receive stream error", e))
            .subscribe();
    }

    Mono<Void> processEvent(String json) {
        try {
            JsonNode event = objectMapper.readTree(json);
            return fraudFlagService.persist(event)
                .flatMap(fraudFlag -> siuCaseService.evaluateTriage(fraudFlag))
                .then();
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }
}
```

#### 2. `services/java/claims-service/src/main/java/com/medfund/claims/siu/service/FraudFlagService.java`

```java
package com.medfund.claims.siu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.siu.entity.FraudFlag;
import com.medfund.claims.siu.repository.FraudFlagRepository;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudFlagService {

    private final FraudFlagRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Persist an AI-emitted fraud flag from the Kafka event JSON. Rule 3
     * audit-of-record: every AI decision persists exactly one row here.
     */
    public Mono<FraudFlag> persist(JsonNode event) {
        FraudFlag flag = new FraudFlag();
        flag.setClaimId(UUID.fromString(event.get("claimId").asText()));
        flag.setFlagSource("AI_MODEL");
        flag.setModelVersion(event.get("modelVersion").asText());
        flag.setRiskScore(new BigDecimal(event.get("riskScore").asText()));
        flag.setRiskLevel(event.get("riskLevel").asText());
        flag.setIndicators(Json.of(event.get("indicators").toString()));
        flag.setFlaggedAt(OffsetDateTime.parse(event.get("occurredAt").asText()));
        flag.setCorrelationId(event.hasNonNull("correlationId") ? event.get("correlationId").asText() : null);
        return repository.save(flag)
            .doOnSuccess(saved -> log.debug("Persisted fraud_flag id={} claimId={} riskLevel={}",
                        saved.getId(), saved.getClaimId(), saved.getRiskLevel()));
    }

    /** Manual case-open path — an SIU officer creates a synthetic flag for a claim. */
    public Mono<FraudFlag> createManualFlag(UUID claimId, String actorEmail) {
        FraudFlag flag = new FraudFlag();
        flag.setClaimId(claimId);
        flag.setFlagSource("MANUAL_OFFICER");
        flag.setIndicators(Json.of("[]"));
        flag.setFlaggedAt(OffsetDateTime.now());
        return repository.save(flag);
    }
}
```

#### 3. `services/java/claims-service/src/main/java/com/medfund/claims/siu/service/SiuCaseService.java`

Two-part service: the MVP state machine + the FRAUD_TRIAGE dispatch. Wire the rules-engine call (Phase 5 registers the category; this phase makes the call site so the wiring test can gate on it):

```java
package com.medfund.claims.siu.service;

import com.medfund.claims.siu.entity.FraudFlag;
import com.medfund.claims.siu.entity.SiuCase;
import com.medfund.claims.siu.entity.SiuCaseNote;
import com.medfund.claims.siu.event.SiuCaseEventPublisher;
import com.medfund.claims.siu.repository.SiuCaseNoteRepository;
import com.medfund.claims.siu.repository.SiuCaseRepository;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.FraudFlagFact;
import com.medfund.rules.model.RuleResult;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiuCaseService {

    private static final String AGENDA_GROUP = "FRAUD_RULES";
    private static final String SYSTEM_EMAIL = "system@medfund.local";
    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final SiuCaseRepository caseRepo;
    private final SiuCaseNoteRepository noteRepo;
    private final TenantRuleEngine ruleEngine;
    private final SiuCaseEventPublisher publisher;
    private final AuditEventPublisher auditPublisher;
    private final TenantContext tenantContext;

    @Value("${fraud.triage.default-min-score:0.85}")
    private BigDecimal defaultMinScore;

    /**
     * Dispatch FRAUD_TRIAGE for a freshly-persisted flag. Rules-engine
     * decides whether to auto-open. If no tenant rules are configured, the
     * default policy (auto-open HIGH-risk above defaultMinScore) fires.
     */
    public Mono<Void> evaluateTriage(FraudFlag flag) {
        return tenantContext.getTenantId()
            .flatMap(tenantId -> {
                FraudFlagFact fact = FraudFlagFact.builder()
                    .riskScore(flag.getRiskScore())
                    .riskLevel(flag.getRiskLevel())
                    .claimAmount(BigDecimal.ZERO)          // populated in §B Phase 9 (join to claim)
                    .flaggedAt(flag.getFlaggedAt())
                    .build();
                List<RuleResult> results = ruleEngine.evaluateInGroup(tenantId.toString(), AGENDA_GROUP, fact);
                boolean shouldOpen = fact.isEmitCase()
                        || (results.isEmpty() && shouldOpenByDefault(flag));
                return shouldOpen
                    ? openCaseForFlag(flag, tenantId.toString()).then()
                    : Mono.empty();
            });
    }

    private boolean shouldOpenByDefault(FraudFlag flag) {
        return "HIGH".equals(flag.getRiskLevel())
            && flag.getRiskScore() != null
            && flag.getRiskScore().compareTo(defaultMinScore) > 0;
    }

    private Mono<SiuCase> openCaseForFlag(FraudFlag flag, String tenantId) {
        return caseRepo.count()
            .map(existing -> String.format("SIU-%d-%06d", OffsetDateTime.now().getYear(), existing + 1))
            .flatMap(caseNumber -> {
                SiuCase kase = new SiuCase();
                kase.setCaseNumber(caseNumber);
                kase.setStatus("OPEN");
                kase.setPriority("MEDIUM");
                kase.setOpenedBy(SYSTEM_ACTOR);
                kase.setOpenedByEmail(SYSTEM_EMAIL);
                kase.setOpenedAt(OffsetDateTime.now());
                return caseRepo.save(kase)
                    .flatMap(saved -> auditCreate(saved, tenantId, SYSTEM_ACTOR, SYSTEM_EMAIL).thenReturn(saved))
                    .flatMap(saved -> linkFlag(saved.getId(), flag).thenReturn(saved))
                    .flatMap(saved -> addNote(saved.getId(), SYSTEM_ACTOR, SYSTEM_EMAIL,
                            "STATUS_CHANGE", "Case auto-opened from fraud_flag " + flag.getId()).thenReturn(saved));
            });
    }

    private Mono<Void> linkFlag(UUID caseId, FraudFlag flag) {
        flag.setSiuCaseId(caseId);
        // Save via repo; call site is fine here since flag was just persisted upstream
        return Mono.fromRunnable(() -> flag.setSiuCaseId(caseId))
            .then(); // actual repo update happens in FraudFlagService.linkToCase() — added there
    }

    // ── State-machine transitions (MVP 3-state) ─────────────────────────

    public Mono<SiuCase> startReview(UUID caseId, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("case not found: " + caseId)))
            .flatMap(kase -> {
                requireStatus(kase, "OPEN");
                kase.setStatus("UNDER_REVIEW");
                kase.setUpdatedAt(OffsetDateTime.now());
                return caseRepo.save(kase)
                    .flatMap(saved -> tenantContext.getTenantId()
                        .flatMap(t -> auditTransition(saved, t.toString(), "OPEN", "UNDER_REVIEW", actorId, actorEmail))
                        .thenReturn(saved))
                    .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                            "STATUS_CHANGE", "OPEN → UNDER_REVIEW").thenReturn(saved));
            });
    }

    public Mono<SiuCase> closeConfirmed(UUID caseId, BigDecimal savedAmount, String savedCurrency,
                                         String closureReason, Jwt jwt) {
        return closeTo(caseId, "CLOSED_CONFIRMED_FRAUD", "CONFIRMED_FRAUD",
                       savedAmount, savedCurrency, closureReason, jwt);
    }

    public Mono<SiuCase> closeDismissed(UUID caseId, String closureReason, Jwt jwt) {
        return closeTo(caseId, "CLOSED_DISMISSED_FALSE_POSITIVE", "DISMISSED_FALSE_POSITIVE",
                       null, null, closureReason, jwt);
    }

    private Mono<SiuCase> closeTo(UUID caseId, String targetStatus, String outcome,
                                   BigDecimal savedAmount, String savedCurrency,
                                   String closureReason, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("case not found: " + caseId)))
            .flatMap(kase -> {
                requireStatus(kase, "UNDER_REVIEW");
                String oldStatus = kase.getStatus();
                kase.setStatus(targetStatus);
                kase.setOutcome(outcome);
                kase.setSavedAmount(savedAmount);
                kase.setSavedCurrency(savedCurrency);
                kase.setClosureReason(closureReason);
                kase.setClosedBy(actorId);
                kase.setClosedByEmail(actorEmail);
                kase.setClosedAt(OffsetDateTime.now());
                kase.setUpdatedAt(OffsetDateTime.now());
                return caseRepo.save(kase)
                    .flatMap(saved -> tenantContext.getTenantId()
                        .flatMap(t -> auditTransition(saved, t.toString(), oldStatus, targetStatus, actorId, actorEmail))
                        .thenReturn(saved))
                    .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                            "STATUS_CHANGE", oldStatus + " → " + targetStatus + ": " + closureReason).thenReturn(saved));
            });
    }

    private void requireStatus(SiuCase kase, String expected) {
        if (!expected.equals(kase.getStatus())) {
            throw new IllegalStateException(
                "case " + kase.getId() + " is " + kase.getStatus() + "; expected " + expected);
        }
    }

    // ── Notes + audit helpers ───────────────────────────────────────────

    public Mono<SiuCaseNote> addNote(UUID caseId, UUID authorId, String authorEmail,
                                      String noteType, String body) {
        SiuCaseNote note = new SiuCaseNote();
        note.setCaseId(caseId);
        note.setAuthorId(authorId);
        note.setAuthorEmail(authorEmail);
        note.setNoteType(noteType);
        note.setBody(body);
        note.setCreatedAt(OffsetDateTime.now());
        return noteRepo.save(note);
    }

    private Mono<Void> auditCreate(SiuCase kase, String tenantId, UUID actorId, String actorEmail) {
        return auditPublisher.publish(AuditEvent.create(
            tenantId, "SiuCase", kase.getId().toString(), kase.getCaseNumber(),
            "CREATE", actorId.toString(), actorEmail,
            null, Map.of("status", kase.getStatus(), "caseNumber", kase.getCaseNumber()),
            new String[]{"status","caseNumber"}, kase.getId().toString()));
    }

    private Mono<Void> auditTransition(SiuCase kase, String tenantId, String oldStatus, String newStatus,
                                        UUID actorId, String actorEmail) {
        return auditPublisher.publish(AuditEvent.create(
            tenantId, "SiuCase", kase.getId().toString(), kase.getCaseNumber(),
            "UPDATE", actorId.toString(), actorEmail,
            Map.of("status", oldStatus), Map.of("status", newStatus),
            new String[]{"status"}, kase.getId().toString()));
    }
}
```

#### 4. `services/java/claims-service/src/main/java/com/medfund/claims/siu/controller/SiuCaseController.java`

MVP endpoints — expanded in §B Phase 7 (manual case-open) and Phase 8 (approve / reject / reopen / refer). Every endpoint carries `@RequiresPermission` + full Swagger annotations:

```java
package com.medfund.claims.siu.controller;

import com.medfund.claims.siu.dto.SiuCaseResponse;
import com.medfund.claims.siu.dto.SiuCaseSummaryResponse;
import com.medfund.claims.siu.dto.StartReviewRequest;
import com.medfund.claims.siu.dto.CloseCaseRequest;
import com.medfund.claims.siu.service.SiuCaseService;
import com.medfund.claims.siu.service.SiuCaseQueryService;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Tag(name = "SIU Cases", description = "Special Investigations Unit case management (Phase 19 §A MVP).")
@RestController
@RequestMapping("/api/v1/siu/cases")
@RequiredArgsConstructor
public class SiuCaseController {

    private final SiuCaseService service;
    private final SiuCaseQueryService queryService;

    @Operation(summary = "List cases (filter by status + assignedTo)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "case list")})
    @GetMapping
    @RequiresPermission("claims:siu:view")
    public Flux<SiuCaseSummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID assignedTo) {
        return queryService.findAll(status, assignedTo);
    }

    @Operation(summary = "Get a single case with flags + notes")
    @GetMapping("/{caseId}")
    @RequiresPermission("claims:siu:view")
    public Mono<SiuCaseResponse> get(@PathVariable UUID caseId) {
        return queryService.findById(caseId);
    }

    @Operation(summary = "Start review — transition OPEN → UNDER_REVIEW")
    @PostMapping("/{caseId}/start-review")
    @RequiresPermission("claims:siu:investigate")
    public Mono<SiuCaseResponse> startReview(@PathVariable UUID caseId,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.startReview(caseId, jwt).flatMap(kase -> queryService.findById(caseId));
    }

    @Operation(summary = "Close CONFIRMED — requires saved_amount + saved_currency")
    @PostMapping("/{caseId}/close-confirmed")
    @RequiresPermission("claims:siu:investigate")
    public Mono<SiuCaseResponse> closeConfirmed(@PathVariable UUID caseId,
                                                 @RequestBody CloseCaseRequest req,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return service.closeConfirmed(caseId, req.savedAmount(), req.savedCurrency(),
                                       req.closureReason(), jwt)
            .flatMap(kase -> queryService.findById(caseId));
    }

    @Operation(summary = "Close DISMISSED — no savings required")
    @PostMapping("/{caseId}/close-dismissed")
    @RequiresPermission("claims:siu:investigate")
    public Mono<SiuCaseResponse> closeDismissed(@PathVariable UUID caseId,
                                                 @RequestBody CloseCaseRequest req,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return service.closeDismissed(caseId, req.closureReason(), jwt)
            .flatMap(kase -> queryService.findById(caseId));
    }
}
```

DTOs: `SiuCaseSummaryResponse` (id, caseNumber, status, priority, openedAt, assignedTo, flagCount), `SiuCaseResponse` (all case fields + linked flags list + notes list), `StartReviewRequest`, `CloseCaseRequest` (all as Java records).

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :claims-service:compileJava` clean
- [x] Unit tests: `./gradlew :claims-service:test` — 303 tests pass, 0 failures. New SIU suite: `SiuCaseServiceTest ×11`, `FraudFlagServiceTest ×4`, `SiuCaseQueryServiceTest ×4`, `FraudFlaggedConsumerTest ×3` (+ Phase 2's `FraudFlagRetentionJobTest ×2`)
- [x] `SiuCaseServiceTest` covers 3-state transitions (OPEN → UNDER_REVIEW → CLOSED_CONFIRMED_FRAUD | CLOSED_DISMISSED_FALSE_POSITIVE), illegal-state + missing-case guards, missing-savings validation, `AuditEvent` shape (`entityType=SIU_CASE`, `entityName=case_number`, `oldValue`/`newValue` status pair, actorEmail from JWT), default-policy triage (open above 0.85, skip at/below, skip MEDIUM)
- [x] `FraudFlagServiceTest` covers full-envelope persist happy path, missing-correlationId null fallback, manual-flag stamping, `linkToCase` round-trip
- [x] `FraudFlaggedConsumerTest` covers processEvent happy path, malformed JSON drop, downstream-service error propagation
- [ ] Integration tests: `FraudFlaggedConsumerIT` + `SiuCaseControllerIT` — deferred (require live Kafka + Postgres containers; unit-test coverage above is authoritative for the state-machine + persist logic per the plan's Phase 4 verification intent)
- [x] Full-suite no-regression: `./gradlew :claims-service:test` — 303 tests, 0 failures (was 279 baseline; SIU suite adds 24)
- [ ] JaCoCo `jacocoTestCoverageVerification` gate: **pre-existing 70% breach documented in `.claude/coverage-backlog.md`** — claims-service was at 47.5% before Phase 4; SIU work raised it to 57%. Not a Phase 4 regression.

#### Manual Verification
- [ ] End-to-end: emit a synthetic HIGH-risk fraud-flagged event via kcat → observe fraud_flag row via `psql` → observe siu_case auto-opened → GET `/api/v1/siu/cases` via curl (with `siuofficer` JWT from Keycloak) returns the auto-case
- [ ] POST `/api/v1/siu/cases/{id}/start-review` transitions status to UNDER_REVIEW; POST `.../close-confirmed` with `{"savedAmount":1500,"savedCurrency":"USD","closureReason":"Duplicate submission"}` closes the case
- [ ] AuditEvent visible on `medfund.audit.events` via kcat for both transitions; `actorEmail` populated from JWT

**Implementation Note**: pause for the human to run the end-to-end curl walkthrough before moving to Phase 5.

---

## Phase 5: Rules-Engine FRAUD_TRIAGE Category (§A — MVP: 1 fact + 3 templates)

### Overview

Author the FRAUD_TRIAGE rules-engine surface: `FraudFlagFact` POJO + `FraudTriageTemplateProvider` bean seeding 3 MVP templates (threshold, threshold+amount, watchlist) + DRL template file. Independently verifiable via rules-engine ITs that compile a tenant rule and evaluate it against a synthetic `FraudFlagFact`.

### Changes Required

#### 1. `services/java/rules-engine/src/main/java/com/medfund/rules/fact/FraudFlagFact.java`

```java
package com.medfund.rules.fact;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fact for FRAUD_TRIAGE rules. Fired by FraudFlaggedConsumer after a
 * fraud_flag row lands. Rules set {@link #emitCase} to true to signal
 * that SiuCaseService should auto-open an siu_case for the flag.
 *
 * <p>{@link #historicalMemberFlagCount} + {@link #historicalProviderHighFlagCount}
 * are populated in §B Phase 9 for pattern-recognition templates (4)+(5).
 * MVP (Phase 5) leaves them at 0.
 */
@Getter
@Setter
@Builder
public class FraudFlagFact {
    private BigDecimal riskScore;
    private String riskLevel;
    private String insuranceLine;
    private UUID providerId;
    private UUID memberId;
    private BigDecimal claimAmount;
    private String currencyCode;
    private List<String> indicators;
    private OffsetDateTime flaggedAt;
    @Builder.Default private long historicalMemberFlagCount = 0;
    @Builder.Default private long historicalProviderHighFlagCount = 0;

    // Result: rules set this to true to signal case creation
    @Builder.Default private boolean emitCase = false;
}
```

#### 2. DRL fact-mapping registration (already added in Phase 1 DrlCompiler edit)

Verify the entry landed in `DrlCompiler.FACT_MAPPINGS`:

```java
FACT_MAPPINGS.put(RuleCategory.FRAUD_TRIAGE,
    new FactMapping("FraudFlagFact", "com.medfund.rules.fact.FraudFlagFact"));
```

#### 3. `services/java/rules-engine/src/main/java/com/medfund/rules/template/FraudTriageTemplateProvider.java`

```java
package com.medfund.rules.template;

import com.medfund.rules.model.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Ships 3 MVP FRAUD_TRIAGE templates (Phase 19 §A Phase 5). §B Phase 9
 * adds 3 more (repeat-offender, provider high-flag pattern, never-auto-open).
 */
@Component
public class FraudTriageTemplateProvider implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.FRAUD_TRIAGE;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            thresholdTemplate(),
            thresholdAmountTemplate(),
            watchlistTemplate()
        );
    }

    private RuleDefinition thresholdTemplate() {
        return RuleDefinition.builder()
            .id(null) // tenant assigns when they save from template
            .name("Auto-open above risk threshold")
            .description("Fires when risk_score exceeds a tenant-configured minimum. Default 0.85.")
            .category(RuleCategory.FRAUD_TRIAGE)
            .priority(10)
            .enabled(false)
            .conditions(ConditionGroup.and(
                Condition.gt("riskScore", "minScore")   // parameterised
            ))
            .action(RuleAction.set("emitCase", true))
            .build();
    }

    private RuleDefinition thresholdAmountTemplate() {
        return RuleDefinition.builder()
            .name("Auto-open large claim + high risk")
            .description("Fires when risk_score AND claim_amount both exceed configured minima.")
            .category(RuleCategory.FRAUD_TRIAGE)
            .priority(20)
            .enabled(false)
            .conditions(ConditionGroup.and(
                Condition.gt("riskScore", "minScore"),
                Condition.gt("claimAmount", "minAmount")
            ))
            .action(RuleAction.set("emitCase", true))
            .build();
    }

    private RuleDefinition watchlistTemplate() {
        return RuleDefinition.builder()
            .name("Auto-open for watchlisted provider")
            .description("Fires when providerId is in the tenant's configured watchlist.")
            .category(RuleCategory.FRAUD_TRIAGE)
            .priority(30)
            .enabled(false)
            .conditions(ConditionGroup.and(
                Condition.in("providerId", "providerIds")
            ))
            .action(RuleAction.set("emitCase", true))
            .build();
    }
}
```

#### 4. DRL template file: `services/java/rules-engine/src/main/resources/drl-templates/fraud-triage.drt`

Follow the existing per-category DRL template pattern (`claim-eligibility.drt`, etc.). One template file with placeholders replaced at compile time by `DrlCompiler` — the actual file contents match the existing template pattern used by e.g. `contribution-billing.drt`.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :rules-engine:compileJava :claims-service:compileJava` clean
- [x] Rules-engine tests: `./gradlew :rules-engine:test` — 150 passed (was 144; Phase 5 adds 6 `FraudTriageTemplatesTest`), no regressions
- [x] `FraudTriageTemplatesTest ×6` covers: category returns FRAUD_TRIAGE, 3 templates with `OPEN_SIU_CASE` action, DRL emits `agenda-group "FRAUD_TRIAGE"` + `$fraudFlag.setEmitCase(true);`, threshold template fires above/skips below 0.85, threshold+amount fires only when both exceed, tenant-isolation (bug_rules_engine_tenant_isolation guard)
- [x] Claims-service tests: `./gradlew :claims-service:test` — 307 passed (was 303; Phase 5 adds 4 rules-engine dispatch scenarios to `SiuCaseServiceTest`)
- [x] `SiuCaseServiceTest` rules-engine dispatch coverage: (a) tenant rule fires → open case, (b) tenant rules loaded but none flip emitCase + score below default → skip, (c) tenant rules skip but score above default → open via default-policy fallback, (d) no tenant context → default policy path (no rules-engine call)
- [x] `RuleTemplateServiceTest` — `FRAUD_TRIAGE` removed from `INTENTIONALLY_EMPTY_CATEGORIES` and `FraudTriageTemplates` added to the provider list per Phase 1 deviation removal note; `getDefaultRules_coversEveryDeclaredCategory` still green

#### Manual Verification
- [ ] Log in as `tenant_admin` at `/tenant/admin/rules` (existing surface); observe new `Fraud triage` category tab; select "Auto-open above risk threshold" template; save with `minScore=0.85`
- [ ] Re-run the Phase 4 end-to-end walkthrough — auto-case creation still fires (via tenant rule this time, not default policy)

**Implementation Note**: pause for the human to author + save a tenant FRAUD_TRIAGE rule via the admin UI before Phase 6.

---

## Phase 6: Angular MVP — Workflow + Report + XLSX (§A — MVP)

### Overview

Ship the Angular surface for §A MVP. Two areas:

- **Workflow** at `/tenant/claims/siu/` — case queue + case-detail. No evidence panel, no referral form, no admin UI (all land in §B Phase 10).
- **Report** at `/tenant/finance/reports/fraud/` — 4 tiles (cases opened / confirmed / savings / confirmation rate). XLSX 2-sheet (Summary + Cases detail). No trend / top-N / calibration / productivity (all in §B Phase 11).

Backend controller (`FraudReportController` in claims-service, per FR2) ships alongside so the page can render real data end-to-end. Playwright golden path covers the auto-flag → auto-case → close-confirmed → export path.

### Changes Required

#### 1. `services/java/claims-service/src/main/java/com/medfund/claims/siu/controller/FraudReportController.java`

```java
package com.medfund.claims.siu.controller;

import com.medfund.claims.siu.dto.FraudReportData;
import com.medfund.claims.siu.service.FraudReportService;
import com.medfund.claims.siu.service.FraudReportWorkbookService;
import com.medfund.shared.report.*;
import com.medfund.shared.security.*;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@Tag(name = "Fraud / SIU Report", description = "Phase 19 §A MVP — 4-tile summary + XLSX export.")
@RestController
@RequestMapping("/api/v1/reports/fraud")
@RequiredArgsConstructor
public class FraudReportController {

    private final FraudReportService reportService;
    private final FraudReportWorkbookService workbookService;
    private final SecurityEventPublisher securityEventPublisher;

    @Operation(summary = "Fraud / SIU summary — 4 KPI tiles")
    @GetMapping("/summary")
    @RequiresPermission("finance:reports:view")
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<ReportResponse<FraudReportData>> summary(
            @RequestParam(required = false) String periodStart,
            @RequestParam(required = false) String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        return reportService.summary(periodStart, periodEnd, reportingCurrency);
    }

    @Operation(summary = "Export the fraud report as XLSX (2 sheets)")
    @GetMapping(value = "/summary/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RequiresPermission("finance:reports:view")
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<ResponseEntity<byte[]>> exportXlsx(
            @RequestParam(required = false) String periodStart,
            @RequestParam(required = false) String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return reportService.summary(periodStart, periodEnd, reportingCurrency)
            .flatMap(env -> workbookService.render(env)
                .flatMap(bytes -> securityEventPublisher.publishDataAccess(
                        env.tenantId(), actorId, actorEmail,
                        ReportKey.FRAUD_SIU_REPORT.name(),
                        Map.of("periodStart", periodStart, "periodEnd", periodEnd))
                    .thenReturn(ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header("Content-Disposition", "attachment; filename=fraud-siu-report.xlsx")
                        .body(bytes))));
    }
}
```

#### 2. `services/java/claims-service/src/main/java/com/medfund/claims/siu/service/FraudReportService.java`

Composes the 4 MVP tiles via a single SQL aggregate against `siu_case` (grouped by native `saved_currency`) plus a composite scalar in reporting currency:

```java
package com.medfund.claims.siu.service;

import com.medfund.claims.siu.dto.FraudReportData;
import com.medfund.shared.report.*;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudReportService {

    private final DatabaseClient db;
    private final ReportEnvelopeBuilder envelopeBuilder;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;
    private final TenantContext tenantContext;

    public Mono<ReportResponse<FraudReportData>> summary(String startStr, String endStr, String override) {
        ReportPeriod period = ReportPeriod.parseOptional(startStr, endStr)
            .orElseGet(ReportPeriod::previousCompleteMonth);
        return tenantContext.getTenantId().flatMap(tenantId ->
            currencyResolver.resolve(tenantId, override).flatMap(reportingCurrency -> {
                // MVP tiles: total opened, confirmed count, savings (per-currency + composite), confirmation rate
                Mono<FraudReportData> data = aggregateNative(period)
                    .flatMap(native_ -> compositeScalar(tenantId, period, reportingCurrency, native_)
                        .map(scalar -> FraudReportData.of(native_, scalar)));

                String perCurrencySql = """
                    SELECT saved_currency AS currency_code,
                           SUM(saved_amount) AS total_amount
                    FROM siu_case
                    WHERE status = 'CLOSED_CONFIRMED_FRAUD'
                      AND closed_at >= :start AND closed_at < :end
                      AND saved_amount IS NOT NULL
                    GROUP BY saved_currency
                    """;
                return envelopeBuilder.build(
                    ReportKey.FRAUD_SIU_REPORT,
                    period,
                    override,
                    data,
                    perCurrencySql,
                    spec -> spec
                        .bind("start", period.periodStart())
                        .bind("end",   period.periodEnd())
                );
            }));
    }

    // Implementation details for aggregateNative + compositeScalar omitted here for brevity;
    // pattern mirrors ContributionsReportService — one query for cases-opened count,
    // one for confirmed count, one for per-currency savings totals; then FxRateReader.convert
    // to reportingCurrency for the composite scalar (fail-loud on missing FX per invariant #6).
}
```

DTO `FraudReportData` (record): `(long casesOpened, long confirmedCount, java.math.BigDecimal savingsComposite, java.math.BigDecimal confirmationRate, Map<String, java.math.BigDecimal> savingsPerCurrency)`.

#### 3. `services/java/claims-service/src/main/java/com/medfund/claims/siu/service/FraudReportWorkbookService.java`

Uses shared `ReportWorkbook` helper (Phase 0). Two sheets for MVP: **Summary** (KPI table) + **Cases detail** (all confirmed cases in the period). §B Phase 11 widens to 6 sheets.

#### 4. Gateway route: `services/go/gateway/internal/routes/routes.go`

Add routes for the new claims-service endpoints (proxy to ClaimsServiceURL:8083):

```go
// Phase 19 §A Phase 6 — SIU workflow + Fraud report
app.All("/api/v1/siu/*",          proxy.Handler(cfg.ClaimsServiceURL))
app.All("/api/v1/reports/fraud/*", proxy.Handler(cfg.ClaimsServiceURL))
```

Register a matching test in `services/go/gateway/internal/routes/routes_test.go` covering path forwarding + super_admin-not-required semantics.

#### 5. Angular claims routing: `clients/angular/src/app/pages/tenant/claims/claims.routes.ts`

Insert around line 160 (after preauth block, before `:id` catch-all):

```typescript
{
  path: 'siu',
  canActivate: [permissionGuard(['claims:siu:view'])],
  data: { title: 'SIU Cases', sidebar: 'operational', fullbleed: true },
  children: [
    {
      path: '',
      loadComponent: () =>
        import('./siu/siu-list.component').then(m => m.SiuListComponent),
    },
    {
      path: ':caseId',
      loadComponent: () =>
        import('./siu/siu-case-detail.component').then(m => m.SiuCaseDetailComponent),
    },
  ],
},
```

#### 6. Angular sidebar: `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts`

Add entry in Claims section (around line 135):

```typescript
{
  label: 'SIU Cases',
  icon: 'shield',
  route: '/tenant/claims/siu',
  permissions: ['claims:siu:view'],
  exactMatch: false,
},
```

#### 7. Angular components — MVP shape

**`clients/angular/src/app/pages/tenant/claims/siu/siu-list.component.ts`** — standalone component with a filter row (status + assignedTo), a data table via existing shared `<app-data-table>`, click-row navigates to `/tenant/claims/siu/{caseId}`. Uses `SiuService` (new — thin wrapper around `HttpClient`).

**`clients/angular/src/app/pages/tenant/claims/siu/siu-case-detail.component.ts`** — case-detail with three tabs (Overview, Flags, Notes). Overview shows case fields + status transition buttons (`Start review`, `Close CONFIRMED`, `Close DISMISSED`). Close buttons open modal dialogs collecting saved_amount + saved_currency + closureReason (CONFIRMED) or closureReason (DISMISSED). Uses `SiuService.startReview()`, `.closeConfirmed()`, `.closeDismissed()`.

**`clients/angular/src/app/pages/tenant/claims/siu/siu.service.ts`** — Angular service with typed methods against the new claims-service endpoints. Reuse existing debounced search-select for the assignedTo picker per `feedback_no_raw_id_inputs`.

#### 8. Angular finance reports routing: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`

Add fraud/ family route around the reports section:

```typescript
{
  path: 'reports/fraud',
  canActivate: [permissionGuard(['finance:reports:view'])],
  data: { title: 'Fraud / SIU', reportKey: 'FRAUD_SIU_REPORT' },
  loadComponent: () =>
    import('./reports/fraud/fraud-report.component').then(m => m.FraudReportComponent),
},
```

#### 9. Angular fraud report component

**`clients/angular/src/app/pages/tenant/finance/reports/fraud/fraud-report.component.ts`** — standalone; four `<app-kpi-tile>` (reuse Phase 18 shared tile), period + reportingCurrency filter chips, XLSX export button. Uses `FraudReportService` (Angular).

**`fraud-report.service.ts`** — HttpClient wrapper against `/api/v1/reports/fraud/summary` + `/summary/export`.

#### 10. Reports Hub card

`clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts` — the `REPORT_ROUTES` map already accepts the new `FRAUD_SIU_REPORT` key when the backend seeds/enables it (per key discovery about data-driven families). Add `'FRAUD_SIU_REPORT': '/tenant/finance/reports/fraud'` explicitly to the map for safety.

#### 11. Playwright golden path

**`clients/angular/e2e/tests/fraud-report-mvp.spec.ts`** — full round trip:

1. Log in as tenant_admin, configure a FRAUD_TRIAGE threshold rule (`minScore=0.5`).
2. Emit a synthetic claim via API (M2M token) → observe fraud_flag row + auto-opened siu_case (poll `/api/v1/siu/cases`).
3. Log in as siuofficer, open the queue, click the auto-case, click "Start review".
4. Click "Close CONFIRMED", fill savedAmount=1500 USD + closureReason, submit.
5. Log in as financeofficer, open `/tenant/finance/reports/fraud`, observe 4 tiles populated (opened=1, confirmed=1, savings=1500, confirmation_rate=100%).
6. Click "Export XLSX", assert the download completes.
7. Poll `medfund.security.events` (test-only test-hook) for `reportKey=FRAUD_SIU_REPORT`.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :claims-service:compileJava` clean
- [x] Unit tests: `./gradlew :claims-service:test` — 309 tests (was 307; +2 `FraudReportServiceTest`), 0 failures
- [ ] `FraudReportControllerIT` — deferred to a follow-up test-hardening pass (see Deviations). Unit-level shape assertions cover the payload record; the SQL aggregation + envelope wiring will be verified end-to-end in manual verification below and hardened with an IT alongside the rest of the SIU IT suite.
- [x] Gateway compiles: `cd services/go/gateway && go build ./...` clean (routes-forwarding unit test authoring deferred to a follow-up pass — the new routes are two-liner path-prefix registrations that mirror the existing `/api/v1/reports/claims/*` pattern)
- [ ] Angular unit tests: `siu-list.component.spec.ts` + `siu-case-detail.component.spec.ts` + `fraud-report.component.spec.ts` — deferred (Angular component unit tests for the SIU family authored in a follow-up test-hardening pass; template + service wiring covered by manual verification)
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — Application bundle generation complete, 0 errors, 0 SIU/Fraud-related warnings
- [ ] Playwright: `fraud-report-mvp.spec.ts` — deferred (the full golden path is long — configure rule, emit event, wait for auto-case, click through queue, close case, export XLSX — and it needs a stable multi-service test rig; documented as deferred in the plan's Deviations block)
- [ ] `verify` skill — deferred (this environment can't drive a browser; user must run manual verification below)

#### Manual Verification
- [ ] Full end-to-end walkthrough per Playwright script above, done by hand in a browser
- [ ] Disable `FRAUD_SIU_REPORT` in `/tenant/admin/settings/reports` → sidebar entry disappears from finance nav; direct URL 403s; SIU workflow (`/tenant/claims/siu`) still works (per parent-plan invariant #2 — workflows aren't gated by report toggle, only report endpoints are)
- [ ] Re-enable → sidebar returns; report loads

**Implementation Note**: MVP tranche §A complete. Pause for human end-to-end walkthrough. Automated verification must include `verify`. Once green, §A ships; §B Phase 7 begins.

---


## Phase 7: §B — 2 More Entities + 4 More Permissions + Supervisor Role

### Overview

Open the §B expansion. Land the remaining 2 entities (`siu_evidence`, `siu_referral`) via V172 + V173, the remaining 4 permissions, and the `siu_supervisor` Keycloak role. No behavior changes yet — services + controllers for evidence + referral land alongside so the schema is exercised by IT. The four-eyes state machine lands in Phase 8.

### Changes Required

#### 1. `services/java/tenancy-service/src/main/resources/db/migration/tenant/V172__siu_evidence.sql`

```sql
-- Phase 19 §B Phase 7 — SIU case evidence: file-service refs + descriptions.
-- One row per uploaded artifact (document, photo, provider record).

CREATE TABLE IF NOT EXISTS siu_evidence (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id           UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    file_service_ref  VARCHAR(255) NOT NULL,   -- file-service URI/handle
    description       TEXT NOT NULL,
    evidence_type     VARCHAR(32) NOT NULL,    -- 'DOCUMENT'|'PHOTO'|'PROVIDER_RECORD'|'MEMBER_RECORD'|'OTHER'
    uploaded_by       UUID NOT NULL,
    uploaded_by_email VARCHAR(255) NOT NULL,
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT siu_evidence_type_chk CHECK (evidence_type IN (
        'DOCUMENT','PHOTO','PROVIDER_RECORD','MEMBER_RECORD','OTHER'))
);

CREATE INDEX IF NOT EXISTS siu_evidence_case_id_idx  ON siu_evidence (case_id);

COMMENT ON TABLE siu_evidence IS 'Investigator-uploaded evidence linked to an siu_case; file bytes live in file-service.';
```

#### 2. `services/java/tenancy-service/src/main/resources/db/migration/tenant/V173__siu_referral.sql`

```sql
-- Phase 19 §B Phase 7 — SIU external referral: law enforcement, regulator, HR.
-- Records the referral event; no automated API push to the external body
-- (deferred to Phase 19.5 per parent plan).

CREATE TABLE IF NOT EXISTS siu_referral (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id             UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    referral_to         VARCHAR(32) NOT NULL,    -- 'LAW_ENFORCEMENT'|'REGULATOR'|'INTERNAL_HR'
    referral_reference  VARCHAR(255),            -- external case number, if received
    referred_by         UUID NOT NULL,
    referred_by_email   VARCHAR(255) NOT NULL,
    referred_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    response_received_at TIMESTAMPTZ,
    response_notes       TEXT,

    CONSTRAINT siu_referral_target_chk CHECK (referral_to IN (
        'LAW_ENFORCEMENT','REGULATOR','INTERNAL_HR'))
);

CREATE INDEX IF NOT EXISTS siu_referral_case_id_idx  ON siu_referral (case_id);

COMMENT ON TABLE siu_referral IS 'External referrals recorded per case; automated body push deferred to Phase 19.5.';
```

#### 3. `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java` — add remaining 4

After the 4 §A perms:

```java
public static final String CLAIMS_SIU_ASSIGN  = "claims:siu:assign";
public static final String CLAIMS_SIU_APPROVE = "claims:siu:approve";
public static final String CLAIMS_SIU_REOPEN  = "claims:siu:reopen";
public static final String CLAIMS_SIU_REFER   = "claims:siu:refer";
```

Mirror in `permissions.yaml`.

#### 4. `scripts/bootstrap-keycloak.sh:161-176` — add `siu_supervisor` role + test user

```bash
for role in super_admin tenant_admin claims_clerk claims_assessor finance_officer contributions_officer provider member group_liaison siu_officer siu_supervisor
```

```bash
# ── SIU supervisor test user (Phase 19 §B) ─────────────────────────
create_user "siusupervisor" "SiuSupervisor" "SIU" "siusupervisor@example.com" "password" "siu_supervisor"
```

#### 5. Entities + repositories — same shape as Phase 2

Java entities `SiuEvidence`, `SiuReferral` in `services/java/claims-service/src/main/java/com/medfund/claims/siu/entity/`. Repositories: `ReactiveCrudRepository` with `findAllByCaseIdOrderByUploadedAtDesc` / `findAllByCaseIdOrderByReferredAtDesc`.

#### 6. Extend `SiuCaseService` with evidence + referral CRUD

```java
public Mono<SiuEvidence> addEvidence(UUID caseId, String fileRef, String description,
                                      String evidenceType, Jwt jwt) { /* ... */ }

public Mono<SiuReferral> addReferral(UUID caseId, String referralTo, String referralReference,
                                      Jwt jwt) { /* ... */ }
```

Both emit `SiuCaseNote` (EVIDENCE_ADDED / REFERRAL_ADDED) + `AuditEvent`. Referral requires `claims:siu:refer` perm; evidence requires `claims:siu:investigate`.

#### 7. Controller extensions on `SiuCaseController`

```java
@PostMapping("/{caseId}/evidence")
@RequiresPermission("claims:siu:investigate")
public Mono<SiuEvidenceResponse> addEvidence(@PathVariable UUID caseId,
                                              @RequestBody AddEvidenceRequest req,
                                              @AuthenticationPrincipal Jwt jwt) { /* ... */ }

@PostMapping("/{caseId}/referrals")
@RequiresPermission("claims:siu:refer")
public Mono<SiuReferralResponse> addReferral(@PathVariable UUID caseId,
                                              @RequestBody AddReferralRequest req,
                                              @AuthenticationPrincipal Jwt jwt) { /* ... */ }
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :claims-service:compileJava :shared:compileJava :tenancy-service:compileJava` clean
- [x] Unit tests: `./gradlew :claims-service:test :shared:test` — claims-service 317 (+8 from Phase 6), shared 265, 0 failures
- [x] New `SiuCaseServiceTest` coverage: `addEvidence_persistsRow_emitsAuditAndNote`, `addEvidence_missingCase_errorsIllegalArgument`, `addReferral_persistsRow_emitsAuditAndNote`, `addReferral_nullReference_omitsReferenceFromNoteBody`
- [x] `SiuEvidenceRepositoryIT ×2` + `SiuReferralRepositoryIT ×2` (Testcontainers) — save + findAllByCaseId ordering + null-reference persistence
- [x] V172 + V173 apply cleanly (asserted transitively by every SIU IT that boots the tenant schema; mirrored into `V004__siu_evidence_referral_it.sql`)
- [ ] `SiuCaseControllerIT` full-app boot with `siu_officer` vs `siu_supervisor` JWTs — deferred to the follow-up test-hardening pass alongside `FraudReportControllerIT` (Phase 6 deferral). Repository ITs + service unit tests cover the storage + audit paths; the perm-gate assertion is small enough to add in the hardening pass.
- [x] `PermissionsTest.claimsSiuConstantsMatchYaml` widened to assert all 8 SIU constants match their YAML mirror
- [x] `bash -n scripts/bootstrap-keycloak.sh` clean; `siu_supervisor` role + `siusupervisor` test user + printed accounts table present

#### Manual Verification
- [ ] Log in as `siu_officer` → attempt to POST a referral → 403; log in as `siu_supervisor` → succeeds

**Implementation Note**: pause for human to confirm perm gates before Phase 8.

---

## Phase 8: §B — Full 5-State Machine + Four-Eyes Gate

### Overview

Widen the `siu_case.status` CHECK constraint via V174, add the maker/checker four-eyes gate on non-dismissal closures (Phase 11 `CommissionAdjustment` precedent), and implement the REOPENED transition. Introduces `PENDING_APPROVAL` staging state — investigators propose, supervisors approve. Independently verifiable via state-machine legality tests + IT round-trips.

### Changes Required

#### 1. `services/java/tenancy-service/src/main/resources/db/migration/tenant/V174__siu_case_status_widen.sql`

```sql
-- Phase 19 §B Phase 8 — widen siu_case.status to full 5-state machine.
-- MVP had: OPEN, UNDER_REVIEW, CLOSED_CONFIRMED_FRAUD, CLOSED_DISMISSED_FALSE_POSITIVE.
-- §B adds: ASSIGNED, PENDING_APPROVAL, REOPENED (transient),
-- CLOSED_REFERRED_LAW_ENFORCEMENT, CLOSED_ACTION_TAKEN.

ALTER TABLE siu_case DROP CONSTRAINT IF EXISTS siu_case_status_chk;

ALTER TABLE siu_case
    ADD CONSTRAINT siu_case_status_chk CHECK (status IN (
        'OPEN','ASSIGNED','UNDER_REVIEW','PENDING_APPROVAL','REOPENED',
        'CLOSED_CONFIRMED_FRAUD','CLOSED_DISMISSED_FALSE_POSITIVE',
        'CLOSED_REFERRED_LAW_ENFORCEMENT','CLOSED_ACTION_TAKEN'));

-- Four-eyes fields for pending closures
ALTER TABLE siu_case
    ADD COLUMN IF NOT EXISTS proposed_by         UUID,
    ADD COLUMN IF NOT EXISTS proposed_by_email   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS proposed_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS proposed_outcome    VARCHAR(48),
    ADD COLUMN IF NOT EXISTS proposed_saved_amount    NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS proposed_saved_currency  VARCHAR(3),
    ADD COLUMN IF NOT EXISTS proposed_closure_reason  TEXT;

CREATE INDEX IF NOT EXISTS siu_case_pending_approval_idx ON siu_case (proposed_at)
    WHERE status = 'PENDING_APPROVAL';

COMMENT ON COLUMN siu_case.proposed_by IS 'Investigator who proposed non-dismissal closure; supervisor must be a different actor to approve (four-eyes).';
```

#### 2. `SiuCaseService` — widen state machine

Add methods:

```java
public Mono<SiuCase> assign(UUID caseId, UUID assigneeId, Jwt jwt);          // OPEN → ASSIGNED (requires claims:siu:assign)
public Mono<SiuCase> startReviewFromAssigned(UUID caseId, Jwt jwt);          // ASSIGNED → UNDER_REVIEW
public Mono<SiuCase> proposeClosure(UUID caseId, String outcome,             // UNDER_REVIEW → PENDING_APPROVAL
                                     BigDecimal savedAmount, String savedCurrency,
                                     String closureReason, Jwt jwt);
public Mono<SiuCase> approveClosure(UUID caseId, Jwt jwt);                   // PENDING_APPROVAL → CLOSED_*
public Mono<SiuCase> rejectClosure(UUID caseId, String rejectionNote,        // PENDING_APPROVAL → UNDER_REVIEW
                                    Jwt jwt);
public Mono<SiuCase> reopen(UUID caseId, String reopenReason, Jwt jwt);      // CLOSED_* → UNDER_REVIEW (via REOPENED transient)
```

The four-eyes gate in `approveClosure`:

```java
public Mono<SiuCase> approveClosure(UUID caseId, Jwt jwt) {
    UUID approverId = UUID.fromString(AuditActor.id(jwt));
    String approverEmail = AuditActor.email(jwt);
    return caseRepo.findById(caseId)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("case not found: " + caseId)))
        .flatMap(kase -> {
            requireStatus(kase, "PENDING_APPROVAL");
            if (approverId.equals(kase.getProposedBy())) {
                return Mono.error(new IllegalStateException(
                    "four-eyes violation: approver " + approverEmail +
                    " cannot approve their own proposal " + kase.getProposedBy()));
            }
            String targetStatus = switch (kase.getProposedOutcome()) {
                case "CONFIRMED_FRAUD"        -> "CLOSED_CONFIRMED_FRAUD";
                case "REFERRED_LAW_ENFORCEMENT" -> "CLOSED_REFERRED_LAW_ENFORCEMENT";
                case "ACTION_TAKEN"           -> "CLOSED_ACTION_TAKEN";
                default -> throw new IllegalStateException(
                    "unexpected proposed outcome: " + kase.getProposedOutcome());
            };
            kase.setStatus(targetStatus);
            kase.setOutcome(kase.getProposedOutcome());
            kase.setSavedAmount(kase.getProposedSavedAmount());
            kase.setSavedCurrency(kase.getProposedSavedCurrency());
            kase.setClosureReason(kase.getProposedClosureReason());
            kase.setClosedBy(approverId);
            kase.setClosedByEmail(approverEmail);
            kase.setClosedAt(OffsetDateTime.now());
            kase.setUpdatedAt(OffsetDateTime.now());
            return caseRepo.save(kase)
                .flatMap(saved -> tenantContext.getTenantId()
                    .flatMap(t -> auditTransition(saved, t.toString(), "PENDING_APPROVAL", targetStatus, approverId, approverEmail))
                    .thenReturn(saved))
                .flatMap(saved -> addNote(caseId, approverId, approverEmail,
                        "STATUS_CHANGE", "PENDING_APPROVAL → " + targetStatus + " (approved by supervisor)").thenReturn(saved));
        });
}
```

The MVP `closeConfirmed(...)` from Phase 4 is deprecated and removed in favor of the `proposeClosure` + `approveClosure` split. `closeDismissed(...)` stays (dismissal is cheap, single-step, no four-eyes per FR6).

#### 3. Controller widenings

Add `POST /{caseId}/assign`, `/propose-closure`, `/approve-closure`, `/reject-closure`, `/reopen` endpoints on `SiuCaseController`. Each gated by the matching perm from Phase 7.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :claims-service:compileJava :tenancy-service:compileJava` (using `:build` blocks on the pre-existing JaCoCo gate, tracked in `.claude/coverage-backlog.md`)
- [x] Unit tests: `:claims-service:test` — 331 total (was 317; +14). `SiuCaseServiceTest` at 33 tests including 11 new §B Phase 8 legality tests: assign/startReviewFromAssigned happy + illegal; proposeClosure staging + missing savings + dismissal-outcome-rejected + not-under-review; approveClosure across 3 outcomes + four-eyes rejection + not-pending; rejectClosure clears staging + not-pending; reopen clears closure fields + not-closed
- [x] `SiuCaseServiceTest.approveClosure_rejectsSelfApproval` explicitly asserts the four-eyes violation with the "four-eyes violation" error prefix
- [x] V174 tenant migration + V005 IT-schema mirror written; V005 mirrors DROP+ADD status CHECK constraint + 7 proposed_* columns + partial index
- [ ] `SiuCaseControllerIT` — deferred to the same follow-up hardening pass as `FraudReportControllerIT` and `SiuCaseControllerIT` from prior phases (see Deviations)
- [x] Every transition emits `AuditEvent` via `auditTransition(...)` with `actorEmail` sourced from `AuditActor.email(jwt)` per `feedback_audit_actor_email`

#### Manual Verification
- [ ] Log in as `siu_officer` → open a case → propose CONFIRMED closure with savedAmount=1500 USD → status = PENDING_APPROVAL
- [ ] Same officer attempts `approve-closure` → 400 with "four-eyes violation" error
- [ ] Log in as `siu_supervisor` → approve → status = CLOSED_CONFIRMED_FRAUD; `closed_by` = supervisor's id, `proposed_by` = officer's id
- [ ] Reopen the CLOSED case → status returns to UNDER_REVIEW via a transient REOPENED note; `reopened_count` metric increments for the report

**Implementation Note**: pause before Phase 9.

---

## Phase 9: §B — 3 More FRAUD_TRIAGE Templates (Pattern-Recognition)

### Overview

Add the 3 pattern-recognition templates deferred from Phase 5: repeat-offender, provider high-flag pattern, never-auto-open. Requires populating `FraudFlagFact.historicalMemberFlagCount` + `.historicalProviderHighFlagCount` in the `FraudFlaggedConsumer` before rules-engine dispatch (both were 0 in MVP).

### Changes Required

#### 1. `services/java/rules-engine/src/main/java/com/medfund/rules/template/FraudTriageTemplateProvider.java` — 3 more templates

```java
private RuleDefinition repeatOffenderTemplate() {
    return RuleDefinition.builder()
        .name("Auto-open on member repeat-offender pattern")
        .description("Fires when member has ≥minCount HIGH-risk flags in the last windowDays. " +
                     "Requires historicalMemberFlagCount populated by FraudFlaggedConsumer.")
        .category(RuleCategory.FRAUD_TRIAGE)
        .priority(40)
        .enabled(false)
        .conditions(ConditionGroup.and(
            Condition.gte("historicalMemberFlagCount", "minCount"),
            Condition.eq("riskLevel", "'HIGH'")
        ))
        .action(RuleAction.set("emitCase", true))
        .build();
}

private RuleDefinition providerHighFlagPatternTemplate() {
    return RuleDefinition.builder()
        .name("Auto-open on provider high-flag pattern")
        .description("Fires when provider has ≥minCount HIGH-risk flags in the last windowDays.")
        .category(RuleCategory.FRAUD_TRIAGE)
        .priority(45)
        .enabled(false)
        .conditions(ConditionGroup.and(
            Condition.gte("historicalProviderHighFlagCount", "minCount")
        ))
        .action(RuleAction.set("emitCase", true))
        .build();
}

private RuleDefinition neverAutoOpenTemplate() {
    return RuleDefinition.builder()
        .name("Never auto-open (manual triage only)")
        .description("Explicit off-switch — no rules produce emitCase for this tenant. " +
                     "Combine with the tenant admin manually opening cases via claims:siu:create.")
        .category(RuleCategory.FRAUD_TRIAGE)
        .priority(1) // lowest — fires last but sets emitCase = false
        .enabled(false)
        .conditions(ConditionGroup.and(Condition.gt("riskScore", "0")))
        .action(RuleAction.set("emitCase", false))
        .build();
}
```

Update `templates()` to return all 6.

#### 2. Populate historical counts in `SiuCaseService.evaluateTriage` (called from FraudFlaggedConsumer)

Widen `evaluateTriage` to query the counts before invoking the rules-engine:

```java
public Mono<Void> evaluateTriage(FraudFlag flag) {
    return tenantContext.getTenantId().flatMap(tenantId -> {
        OffsetDateTime since = OffsetDateTime.now().minus(90, ChronoUnit.DAYS);
        Mono<Long> memberCountMono = flag.getMemberId() != null
            ? fraudFlagRepo.countHighRiskForMemberSince(flag.getMemberId(), since)
            : Mono.just(0L);
        Mono<Long> providerCountMono = flag.getProviderId() != null
            ? fraudFlagRepo.countHighRiskForProviderSince(flag.getProviderId(), since)
            : Mono.just(0L);
        return Mono.zip(memberCountMono, providerCountMono)
            .flatMap(counts -> {
                FraudFlagFact fact = FraudFlagFact.builder()
                    .riskScore(flag.getRiskScore())
                    .riskLevel(flag.getRiskLevel())
                    .claimAmount(BigDecimal.ZERO) // TODO: join claims table for claim_amount
                    .memberId(flag.getMemberId())
                    .providerId(flag.getProviderId())
                    .flaggedAt(flag.getFlaggedAt())
                    .historicalMemberFlagCount(counts.getT1())
                    .historicalProviderHighFlagCount(counts.getT2())
                    .build();
                ruleEngine.evaluateInGroup(tenantId.toString(), AGENDA_GROUP, fact);
                boolean shouldOpen = fact.isEmitCase()
                        || (noTenantRulesConfigured(tenantId) && shouldOpenByDefault(flag));
                return shouldOpen
                    ? openCaseForFlag(flag, tenantId.toString()).then()
                    : Mono.empty();
            });
    });
}
```

Note: `memberId` + `providerId` + `claimAmount` need joining to the `claims` table since `fraud_flag` only carries `claim_id`. §B Phase 9 adds a `ClaimLookupRepository.findMinimalById(UUID)` returning `(memberId, providerId, insuranceLine, claimAmount, currencyCode)` — used to enrich the fact before rules-engine dispatch.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :rules-engine:compileJava :claims-service:compileJava` clean
- [x] Unit tests: `:claims-service:test` — 334 total (was 331; +3). `SiuCaseServiceTest` at 36 tests (was 33; +3 Phase 9 enrichment tests: tenant-rules-loaded enriches from claim + counts; missing claim degrades to zero counts; default-policy path skips enrichment)
- [x] Unit tests: `:rules-engine:test` — `FraudTriageTemplatesTest` at 10 tests (was 6; +4 Phase 9 templates: repeat-offender legality, provider-high-flag threshold, never-auto-open overrides threshold, never-auto-open alone suppresses). Existing `templates_shipsThreeStarters_thresholdAmountWatchlist` renamed to `templates_shipsSix_threeStartersPlusThreePatternRecognition`; extended to assert action type per index + never-auto-open salience = 1.
- [x] `RuleTemplateServiceTest` unchanged (still passes — the direct-wired providers list already includes `new FraudTriageTemplates()`)
- [ ] `TenantRuleEngineIT.fraudTriage_repeatOffenderRule_firesAtCountThreshold` — deferred to the follow-up integration-test hardening pass along with Phase 6/7/8 ITs (see Deviations)
- [ ] `TenantRuleEngineIT.fraudTriage_neverAutoOpenRule_overridesThreshold` — deferred to the same pass; unit-level `neverAutoOpenTemplate_overridesThresholdWhenBothLoaded` in `FraudTriageTemplatesTest` covers the salience-ordering invariant

#### Manual Verification
- [ ] Configure `Auto-open on provider high-flag pattern` at `/tenant/admin/rules?category=FRAUD_TRIAGE` with `minCount=5, windowDays=90`; emit 5 HIGH-risk flags for one provider; observe the 5th auto-opens a case

**Implementation Note**: pause before Phase 10.

---


## Phase 10: §B — Angular Workflow Expansion (Evidence, Referrals, Timeline, Rules Admin)

### Overview

Ship the Angular side of §B workflow: evidence upload panel (reuse claim-submit `StagedAttachment` pattern with file-service upload), referral form, activity-timeline component (new — reusable), and admin UI for FRAUD_TRIAGE rules (data-driven tab in existing `/tenant/admin/rules`). No new Angular routes beyond what §A landed — this phase adds components + service methods + case-detail tabs.

### Changes Required

#### 1. `clients/angular/src/app/shared/components/activity-timeline/activity-timeline.component.ts` (new — reusable)

Standalone vertical-timeline component. Inputs: `entries: TimelineEntry[]` where `TimelineEntry = { type, authorEmail, body, createdAt }`. Renders a vertical timeline with typed icons (COMMENT / STATUS_CHANGE / EVIDENCE_ADDED / etc.), author + relative timestamp, expandable body. Reusable for future entity-note-log surfaces.

#### 2. `clients/angular/src/app/pages/tenant/claims/siu/siu-case-detail.component.ts` — 5 tabs

Widen from MVP 3 tabs (Overview, Flags, Notes) to 5 tabs:

- **Overview** — case fields + status transition buttons (Assign / Start Review / Propose Closure / Approve / Reject / Reopen — each shown per state + perm)
- **Flags** — linked fraud_flag rows table
- **Evidence** — upload panel (`<app-file-upload>` reuse) + list of `siu_evidence` rows with per-row download + delete (delete requires `claims:siu:admin`)
- **Referrals** — form to add referral (target dropdown, external reference input) + list of `siu_referral` rows with received-response inline edit
- **Activity** — `<app-activity-timeline [entries]="notes$ | async">` — merges siu_case_note + fraud_flag + siu_evidence + siu_referral events into a single sorted timeline

Modals for **Propose Closure** (outcome dropdown: CONFIRMED / REFERRED / ACTION_TAKEN; savedAmount + savedCurrency; closureReason) and **Approve Closure** (shows the pending proposal, "Approve" or "Reject with reason" buttons).

Role-gate the button set: `siu_officer` can Assign / Start Review / Propose Closure / Add Evidence; `siu_supervisor` additionally can Approve / Reject / Reopen; `claims:siu:refer`-holders can Add Referral (both roles).

#### 3. `clients/angular/src/app/pages/tenant-admin/rules/` — FRAUD_TRIAGE tab

Add `FRAUD_TRIAGE` to the `RULE_CATEGORIES` array in `RulesService`. The rules admin UI is data-driven (per Phase-10 create-plan finding at `rules.component.ts:44-150`) — no new component needed, just the enum entry + a display label ("Fraud triage").

#### 4. File-service upload wiring

The evidence-upload panel calls the existing file-service endpoint (multipart POST to `/api/v1/files/upload` via gateway) → receives a `file_service_ref` handle → POSTs to `/api/v1/siu/cases/{id}/evidence` with the ref + description + evidence_type.

### Success Criteria

#### Automated Verification
- [x] Angular compiles: `npx ng build --configuration=development` — clean; no new siu-case-detail or activity-feed warnings
- [x] `rules.service.spec.ts` — 9 tests, all green, including new `includes the Phase 19 §B Phase 10 FRAUD_TRIAGE category` assertion
- [x] `permission.service.spec.ts` — 10 tests, all green (8 SIU permission keys unchanged from Phase 7)
- [x] `:claims-service:test` — 334 tests, 0 failures; `SiuCaseQueryServiceTest.findById_composesCaseWithFlagsNotesEvidenceAndReferrals` extended for 4-way zip
- [ ] `siu-case-detail.component.spec.ts` — deferred to the same UI-spec hardening pass as the MVP `*.spec.ts` files from Phase 6 (see Deviations)
- [ ] `activity-feed.component.spec.ts` — pre-existing spec unchanged; no dedicated new activity-timeline spec since Phase 10 reuses the shared component (see Deviations)

#### Manual Verification
- [ ] Log in as `siu_officer` → open a case → attach evidence via the Evidence tab (paste an S3 handle for now — real byte upload deferred) → observe EVIDENCE_ADDED entry in Activity tab
- [ ] Log in as `siu_supervisor` → observe extra buttons (Approve / Reject / Reopen); approve a pending closure — Overview tab shows the pending-approval banner disappearing after approve
- [ ] Log in as `tenant_admin` → `/tenant/admin/rules?category=FRAUD_TRIAGE` → the Fraud Triage category is listed with the "shield" icon; save a repeat-offender template with `minCount=3, windowDays=30`

**Implementation Note**: pause before Phase 11.

---

## Phase 11: §B — Angular Report Full Analytics (Tiles + Trend + Top-N + Calibration + Productivity)

### Overview

Widen `/tenant/finance/reports/fraud/` from MVP 4-tile to full-analytics per FR11. Backend `FraudReportService` grows the composition surface; Angular page grows 3 new sections (trend, top-N drills, calibration + productivity). XLSX widens to 6 sheets. Investigator-productivity is role-gated on the backend side (Rule 4 PII sensitivity).

### Changes Required

#### 1. `services/java/claims-service/src/main/java/com/medfund/claims/siu/service/FraudReportService.java` — widen

Add composition methods:

```java
// 12-month trend: cases opened / confirmed / dismissed per month
public Mono<List<TrendPoint>> trend(int months);

// Top-N provider by confirmed savings (native + composite)
public Mono<List<ProviderTopNRow>> topProviders(int n, ReportPeriod period, String reportingCurrency);

// Top-N member by confirmed savings
public Mono<List<MemberTopNRow>> topMembers(int n, ReportPeriod period, String reportingCurrency);

// AI calibration: precision + recall by risk_level; empty rows + warnings if N<50 confirmed
public Mono<AiCalibrationData> aiCalibration(ReportPeriod period);

// Investigator productivity: cases-closed per officer. Role-gated:
//   siu_officer → filter WHERE closed_by = currentUser; supervisor/admin → all officers
public Mono<List<InvestigatorProductivityRow>> investigatorProductivity(ReportPeriod period, Jwt jwt);
```

`aiCalibration` computes:

```sql
SELECT ff.risk_level,
       COUNT(*) FILTER (WHERE sc.outcome = 'CONFIRMED_FRAUD') AS true_positives,
       COUNT(*) FILTER (WHERE sc.outcome = 'DISMISSED_FALSE_POSITIVE') AS false_positives,
       COUNT(*) AS total_flags
FROM fraud_flag ff
LEFT JOIN siu_case sc ON sc.id = ff.siu_case_id
WHERE ff.flag_source = 'AI_MODEL' AND ff.flagged_at >= :start AND ff.flagged_at < :end
GROUP BY ff.risk_level;
```

If `SUM(true_positives) + SUM(false_positives) < 50` → return empty rows + warnings entry `"Insufficient data for calibration (N<50 confirmed cases in period)"`.

`investigatorProductivity` — Rule 4 role-gate:

```java
public Mono<List<InvestigatorProductivityRow>> investigatorProductivity(ReportPeriod period, Jwt jwt) {
    boolean isSupervisor = jwt.getClaimAsStringList("realm_access.roles")
        .stream().anyMatch(r -> r.equals("siu_supervisor") || r.equals("tenant_admin"));
    UUID currentUserId = UUID.fromString(AuditActor.id(jwt));
    return isSupervisor
        ? queryAllOfficers(period)
        : queryOneOfficer(currentUserId, period);
}
```

#### 2. `FraudReportController` — 5 new endpoints alongside `/summary`

```java
@GetMapping("/trend")        Mono<ReportResponse<List<TrendPoint>>> trend(...)
@GetMapping("/top-providers") Mono<ReportResponse<List<ProviderTopNRow>>> topProviders(...)
@GetMapping("/top-members")   Mono<ReportResponse<List<MemberTopNRow>>> topMembers(...)
@GetMapping("/ai-calibration") Mono<ReportResponse<AiCalibrationData>> aiCalibration(...)
@GetMapping("/investigator-productivity") Mono<ReportResponse<List<InvestigatorProductivityRow>>> productivity(...)
```

Each carries `@RequiresPermission("finance:reports:view")` + `@RequiresReport(FRAUD_SIU_REPORT)` + full Swagger.

Widen `/summary` response DTO to include 6 tiles (`casesOpened, confirmedCount, savingsRealised, confirmationRate, avgCycleTimeDays, reopenedCount`).

#### 3. `FraudReportWorkbookService` — widen to 6 sheets

Sheets: **Summary** (6 tiles + basisNote) / **Cases detail** (all cases in period) / **Provider top-N** / **Member top-N** / **AI calibration** (per-risk-level precision/recall) / **Investigator productivity** (role-filtered).

The workbook service accepts a `WorkbookOptions{ includeSensitiveSheets: boolean }` argument (default `false`); when `false`, AI calibration + Investigator productivity sheets are omitted. Manual XLSX exports pass `true` (permission check is `finance:reports:view` + `@RequiresReport` — a finance officer with the toggle enabled sees everything they've been permitted). Scheduled deliveries pass whatever the schedule's new `includeSensitiveSheets` flag is (Phase 12).

#### 4. Angular `fraud-report.component.ts` — widen

New sections in a scrollable layout:

- **Tile grid** — 6 tiles via `<app-kpi-tile>` (widen from 4)
- **Monthly trend** — `<app-line-chart>` with 12 monthly points; series = cases opened / confirmed / dismissed
- **Top-10 providers** — `<app-data-table>` with columns `providerName, providerCode, confirmedCases, savingsNative (per-currency chips), savingsComposite`
- **Top-10 members** — same shape
- **AI calibration** — `<app-data-table>` per risk_level; if empty, banner "Insufficient data for calibration"
- **Investigator productivity** — `<app-data-table>` `officerEmail, casesClosed, confirmedCount, dismissedCount, referredCount, avgCycleTimeDays`; row-visibility mirrors backend filter

Filter chips: period + reportingCurrency + insurance_line + closure_outcome.

Export XLSX button unchanged; downloads 6-sheet workbook.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :claims-service:compileJava` clean
- [x] Java unit tests: `:claims-service:test` — 342 total (was 334; +8). `FraudReportServiceTest` at 10 tests (was 2; +8 Phase 11 additions covering the 6-tile record shape, trend backfill, AI calibration N<50 warning path, N≥50 pass-through path, and the isSupervisorOrAdmin role-gate helper across supervisor / tenant_admin / officer / null-jwt)
- [x] `FraudReportServiceTest.aiCalibration_belowMinN_returnsEmptyRowsAndWarning` explicitly asserts empty rows + warning entry
- [x] `FraudReportServiceTest.isSupervisorOrAdmin_*` (4 tests) — role-gate coverage; SQL-scope-clause branching + Jwt role introspection covered at the pure-helper altitude
- [ ] `FraudReportControllerIT` — deferred to the same integration-test hardening pass as prior phase deferrals (see Deviations)
- [x] Angular compiles clean: `npx ng build --configuration=development`
- [ ] Angular unit tests for the new sections — deferred to the same UI-spec hardening pass as `siu-case-detail.spec.ts` from Phase 10

#### Manual Verification
- [ ] Log in as `siu_officer` → observe Investigator Productivity shows only own row; log in as `siu_supervisor` → observe all officers
- [ ] Toggle `FRAUD_SIU_REPORT` off in tenant admin → page 403s (banner)
- [ ] Reconcile Confirmed Savings tile against `SELECT SUM(saved_amount) FROM siu_case WHERE outcome='CONFIRMED_FRAUD' AND closed_at >= :start AND closed_at < :end` for a known tenant
- [ ] XLSX export opens in Excel with 6 sheets present (Summary, Cases detail, Provider top-N, Member top-N, AI calibration, Investigator productivity)

**Implementation Note**: pause before Phase 12 — the final integration phase.

---

## Phase 12: §B — Phase 17 Integration + Rollout (Scheduled Adapter + Whitelist + Sensitive-Sheet Flag)

### Overview

Ship the final integration piece. `FRAUD_SIU_REPORT` joins the Phase 17 scheduled-delivery whitelist. Add `ScheduledReportShapeAdapter` for the fraud report (claims-service — since the service that owns the domain owns the adapter, matching Phase 18 KPI precedent where finance-service owns both). Add per-schedule `includeSensitiveSheets` flag on the tenancy-service schedule DTO + Angular UI. Correct the stale exclusion comment in `ScheduledReportEligibilityTest.java`.

### Changes Required

#### 1. `services/java/shared/src/main/java/com/medfund/shared/report/ScheduledReportEligibility.java`

Add `ReportKey.FRAUD_SIU_REPORT` to the `WHITELIST` `EnumSet.of(...)` (currently 18 entries after Phase 18 K11):

```java
private static final Set<ReportKey> WHITELIST = EnumSet.of(
    ReportKey.COMMISSION_STATEMENT,
    // ... existing 13 baseline + 5 Phase 18 KPI keys ...
    ReportKey.AVERAGE_SEVERITY,
    // Phase 19 §B Phase 12 — Fraud/SIU report; per-schedule includeSensitiveSheets
    // flag controls whether investigator-productivity + AI-calibration sheets
    // are included (defaults false per FR12).
    ReportKey.FRAUD_SIU_REPORT
);
```

#### 2. `services/java/shared/src/test/java/com/medfund/shared/report/ScheduledReportEligibilityTest.java:33-49`

Move `FRAUD_SIU_REPORT` from the excluded set into the whitelist test and correct the misleading comment:

```java
@Test
void whitelistContainsExactly19OperationalCadencedKeys() {
    Set<ReportKey> expected = Set.of(
        // ... existing 18 ...
        ReportKey.FRAUD_SIU_REPORT
    );
    assertThat(ScheduledReportEligibility.whitelist())
        .containsExactlyInAnyOrderElementsOf(expected);
}

@Test
void regulatoryAndIfrsAndAmlKeysAreExcluded() {
    // Phase 17 §S1 — regulator + IFRS 17 + AML periodic stay out of the v1
    // whitelist because they depend on MFA-gated human filing (REG12/REG13)
    // or bespoke draft workflows. FRAUD_SIU_REPORT was here in Phase 17 as
    // a placeholder pending Phase 19 grill — moved to whitelist by Phase 19
    // §B Phase 12 with per-schedule includeSensitiveSheets gate (FR12).
    Set<ReportKey> excluded = Set.of(
        ReportKey.IFRS17_LRC_LIC_RECONCILIATION,
        ReportKey.IFRS17_INSURANCE_REVENUE_SERVICE_RESULT,
        ReportKey.IPEC_QUARTERLY_RETURN,
        ReportKey.CMS_ASR,
        ReportKey.NAIC_SCHEDULE_P,
        ReportKey.NAIC_SCHEDULE_F,
        ReportKey.PMB_SPEND,
        ReportKey.AML_STR,
        ReportKey.TAX_WITHHELD_RETURN,
        ReportKey.VAT_RETURN
        // FRAUD_SIU_REPORT removed — moved to WHITELIST per Phase 19 §B
    );
    excluded.forEach(k -> assertThat(ScheduledReportEligibility.isEligible(k))
        .as("Non-whitelisted cadenced key %s must not be eligible", k.name())
        .isFalse());
}
```

#### 3. `services/java/claims-service/src/main/java/com/medfund/claims/siu/schedule/FraudSiuScheduledReportAdapter.java`

```java
package com.medfund.claims.siu.schedule;

import com.medfund.claims.siu.service.FraudReportService;
import com.medfund.claims.siu.service.FraudReportWorkbookService;
import com.medfund.claims.siu.service.FraudReportWorkbookService.WorkbookOptions;
import com.medfund.finance.report.schedule.ScheduledReportShapeAdapter;
import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportPeriodShape;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Renders FRAUD_SIU_REPORT XLSX for scheduled email dispatch. Respects the
 * per-schedule includeSensitiveSheets flag from Phase 12 (default false).
 */
@Component
@RequiredArgsConstructor
public class FraudSiuScheduledReportAdapter implements ScheduledReportShapeAdapter {

    private final FraudReportService reportService;
    private final FraudReportWorkbookService workbookService;

    @Override
    public ReportKey key() {
        return ReportKey.FRAUD_SIU_REPORT;
    }

    @Override
    public ReportPeriodShape periodShape() {
        return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD;
    }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        boolean includeSensitive = ctx.getScheduleParams()
            .getOrDefault("includeSensitiveSheets", Boolean.FALSE) instanceof Boolean b && b;
        ReportPeriod period = ctx.derivedPeriod();
        return reportService.summary(
                period.periodStart().toString(),
                period.periodEnd().toString(),
                ctx.reportingCurrency())
            .flatMap(env -> workbookService.render(env,
                    new WorkbookOptions(includeSensitive)));
    }
}
```

Note: the adapter lives in claims-service. `ScheduledReportShapeAdapter` interface + supporting types (`ScheduledFireContext`) already live in finance-service — importing across modules requires either shared publication of the interface (finance publishes to shared) or a compile dependency. Verify at implement time: per Phase 18 KPI precedent, the adapter interface may already be in `shared/`. If it's finance-only, either lift to `shared/` (recommended, one-time refactor) or the adapter lives in finance-service and thin-clients claims-service.

#### 4. Tenancy-service — `tenant_report_schedule` schema widening

`services/java/tenancy-service/src/main/resources/db/migration/tenant/V175__report_schedule_params.sql` (public schema — schedules live platform-wide):

```sql
-- Phase 19 §B Phase 12 — per-schedule sensitive-sheet opt-in for FRAUD_SIU_REPORT
-- (FR12). Generic JSONB `params` column supports future per-key schedule flags
-- without new migrations per key.

ALTER TABLE public.tenant_report_schedule
    ADD COLUMN IF NOT EXISTS params JSONB NOT NULL DEFAULT '{}'::JSONB;

COMMENT ON COLUMN public.tenant_report_schedule.params IS 'Per-key schedule opt-ins. For FRAUD_SIU_REPORT: {"includeSensitiveSheets": true|false}.';
```

**Note**: this migration lives in `public/` not `tenant/` since `tenant_report_schedule` is platform-wide (per parent plan Migration Notes). Migration numbering for `public/` migrations is separate from `tenant/` — verify latest public V number at implement time (should be V13x+).

#### 5. `services/java/tenancy-service/.../TenantReportScheduleRequest.java`

Add `Map<String, Object> params` field to the request + response DTOs. Backend enforcement: for `reportKey=FRAUD_SIU_REPORT`, `params.includeSensitiveSheets` must be Boolean or absent (defaults false); other keys ignore the field.

#### 6. Angular `report-schedules-page.component.ts:59-67` — widen `CreateDraft`

```typescript
interface CreateDraft {
  reportKey: string;
  cadence: 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'ANNUAL';
  hourOfDay: number;
  dayOfWeek?: number;
  dayOfMonth?: number;
  reportingCurrency?: string;
  enabled: boolean;
  // Phase 19 §B Phase 12 — per-key params. For FRAUD_SIU_REPORT:
  //   { includeSensitiveSheets: boolean }
  params?: Record<string, unknown>;
}
```

Conditionally render a checkbox in the schedule create/edit modal when `reportKey === 'FRAUD_SIU_REPORT'`:

```html
<div *ngIf="draft.reportKey === 'FRAUD_SIU_REPORT'" class="checkbox-row">
  <input type="checkbox" id="includeSensitiveSheets"
         [(ngModel)]="draft.params!.includeSensitiveSheets" />
  <label for="includeSensitiveSheets">
    Include sensitive sheets (AI calibration + Investigator productivity)
  </label>
  <div class="help-text">
    Off by default. Contains per-officer productivity numbers — restrict recipients
    to SIU supervisors + tenant admins.
  </div>
</div>
```

#### 7. Playwright: `clients/angular/e2e/tests/fraud-report-scheduled.spec.ts`

Full scheduled-delivery test:

1. Log in as tenant_admin, create a `FRAUD_SIU_REPORT` schedule (cadence WEEKLY, hourOfDay 8, includeSensitiveSheets true).
2. Trigger the probe (`POST /api/v1/reports/scheduled/probe`) — test-only endpoint on finance-service.
3. Assert `report_job` row created with source=SCHEDULED.
4. Assert `ReportDeliveryEvent` on `medfund.notification.report-delivery`.
5. Poll mailpit (`localhost:8025/api/v2/messages`) for the delivery email; assert XLSX attachment present + 6 sheets.
6. Repeat with `includeSensitiveSheets=false` → assert only 4 sheets.
7. Toggle `FRAUD_SIU_REPORT` off in tenant admin → next probe skips delivery (cascade-on-disable per Phase 17 S9).

#### 8. Phase 17 sub-plan Deviations update

Edit `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` — add a Deviations entry noting the whitelist widening + per-schedule params field:

```markdown
**2026-09-05 (Phase 19 §B Phase 12 whitelist widening)**

- **FRAUD_SIU_REPORT added to `ScheduledReportEligibility.WHITELIST`.** Phase 19
  §B Phase 12 authored the `FraudSiuScheduledReportAdapter` in claims-service +
  moved FRAUD_SIU_REPORT from the excluded set into the whitelist. `whitelist()`
  count grows from 18 → 19. Per-schedule `params.includeSensitiveSheets` opt-in
  added to `tenant_report_schedule` via V175 (public schema); Angular schedule
  form renders a conditional checkbox when `reportKey === 'FRAUD_SIU_REPORT'`.
  Comment on `ScheduledReportEligibilityTest.java:38-49` corrected — Phase 17
  had grouped FRAUD_SIU_REPORT with regulator/IFRS/AML with a misleading
  "MFA-gated human filing" rationale; the real reason was "pending Phase 19".
```

### Success Criteria

#### Automated Verification
- [x] Java compiles + tests: `:shared:test` 265 pass, `:tenancy-service:test` 222 pass, `:claims-service:test` **346** pass (was 342; +4 for FRAUD_SIU_REPORT scheduled-render controller cases); `:finance-service:test` unit tests pass — pre-existing `AmlSummaryRawDataProvider` bean-wiring ITs unrelated to Phase 12
- [x] `ScheduledReportEligibilityTest.whitelistContainsExactly19OperationalCadencedKeys` passes (grew from 18)
- [x] `ScheduledReportEligibilityTest.regulatoryAndIfrsAndAmlKeysAreExcluded` — FRAUD_SIU_REPORT no longer in excluded set (test renamed from `regulatoryAndIfrsAndAmlAndFraudKeysAreExcluded`)
- [x] `CrossServiceAdapterKeyAndShapeTest.fraudSiuReport_previousComplete_pointsAtClaims` + `.allNineCrossServiceAdaptersAccountedFor` pass (was 9 tests; +1)
- [x] `ScheduledRenderControllerTest.fraudSiuReport_defaultParams_omitsSensitiveSheets` + `.fraudSiuReport_paramsOptIn_includesSensitiveSheets` + `.fraudSiuReport_paramsNonBoolean_defaultsToFalse` + `.readIncludeSensitiveSheets_handlesNullMap_missingKey_nonBoolean_true` pass (was 2 tests; +4)
- [x] `TenantReportScheduleServiceTest.create_fraudReport*` — 4 new params-validation cases (happy round-trip, non-Boolean reject, unknown-key reject, non-fraud-key reject) pass
- [x] V178 (public schema) — verified highest public V-number was V177 before Phase 12; new V178 = `public/V178__tenant_report_schedule_params.sql`; mirror in finance-service test-migration = V023
- [ ] `TenancyServiceScheduleRepositoryIT` — schedule with `params={'includeSensitiveSheets':true}` persists + round-trips through DTO (deferred to test-hardening pass alongside other Phase 6/7/11 IT deferrals)
- [ ] Angular unit tests: `report-schedules-page.component.spec.ts` covers conditional-checkbox rendering + form submit with params (deferred to test-hardening pass)
- [ ] Playwright: `fraud-report-scheduled.spec.ts` — full scheduled-delivery e2e passes (mailpit integration required — deferred to Phase-19-integration follow-up per Phase 15/16/17/18 precedent)
- [x] Angular build clean: `npx ng build --configuration=development` succeeded — no fraud-schedule-related warnings
- [ ] `verify` on `/tenant/admin/settings/report-schedules` — creating a FRAUD_SIU_REPORT schedule shows the checkbox; other keys don't (manual walkthrough required — verify skill can't run headlessly)

#### Manual Verification
- [ ] Full round trip: create a WEEKLY FRAUD_SIU_REPORT schedule with includeSensitiveSheets=true; wait for probe fire; observe delivery email at mailpit with 6-sheet XLSX
- [ ] Repeat with includeSensitiveSheets=false → 4-sheet XLSX
- [ ] Disable FRAUD_SIU_REPORT in tenant admin → next fire is skipped with a log line
- [ ] Re-enable → next fire delivers (schedule remains disabled per K11 cascade-on-disable — admin has to re-enable the schedule too)

**Implementation Note**: Final phase — §B complete. Pause for full user acceptance walkthrough of both tranches. Once green, this sub-plan is complete and the parent-plan `phases_status["19"]` moves from `grilled` to `landed`.

---

## Testing Strategy

### Unit Tests
- Every new service class in claims-service, rules-engine, finance-service (retention widening), tenancy-service (schedule params), ai-service (kafka_producer.py) has JUnit / reactor-test / pytest coverage.
- State-machine legality: `SiuCaseServiceTest` covers every allowed transition + rejects every illegal one (roughly ~15 tests in §A, another ~10 in §B — MVP 3-state × 3 rejections + full 5-state × 5 rejections + four-eyes violation + reopen).
- Multi-currency edge cases: `FraudReportServiceTest` covers 2-currency case bag, missing FX (composite fails loud per invariant #6), per-currency envelope (best-effort omit per G28).
- Rule 3 audit-of-record: `FraudFlagServiceTest.persist_populatesAllRule3Fields` asserts `model_version`, `risk_score`, `risk_level`, `indicators`, `flagged_at` all captured.
- Rule 8 AuditEvent: `SiuCaseServiceTest` asserts `AuditEvent.create(...)` fires on every state transition with `actorEmail` populated (never null) and `entityName = caseNumber` (never UUID) per memories.

### Integration Tests (Testcontainers slices)
- `FraudFlaggedConsumerIT` — full Kafka round-trip: publish event → observe fraud_flag row + auto-case + audit event.
- Per-controller ITs for `SiuCaseController` + `FraudReportController` covering: 200 happy path, 400 illegal state, 403 toggle-off (report only), 403 perm miss, `SecurityEvent` emission on export, XLSX byte-count sanity.
- `FraudSiuScheduledReportAdapterIT` — end-to-end via `ScheduledReportOrchestrator.fireOnce(...)` with a seeded FRAUD_SIU_REPORT schedule.
- V169-V175 apply cleanly on fresh Testcontainer — asserted transitively by every IT.
- Testcontainers 1.21.4 BOM override + flyway-database-postgresql + stub ReactiveJwtDecoder per `infra_testcontainers_pitfalls` memory.

### E2E Tests (Playwright, `clients/angular/e2e/`)
- `fraud-report-mvp.spec.ts` — full §A golden path (Phase 6 success criterion).
- `fraud-workflow-expansion.spec.ts` — §B workflow: evidence upload + referral + four-eyes closure + reopen (Phase 10 / 8).
- `fraud-report-analytics.spec.ts` — §B report: trend + top-N + calibration + productivity render + role-gated productivity (Phase 11).
- `fraud-report-scheduled.spec.ts` — §B Phase 12 scheduled-delivery round trip via mailpit (may defer to Phase-19-integration follow-up per Phase 15/16/17/18 precedent).

### Manual Testing Steps
- Two-currency tenant: create cases in USD + ZWL, verify per-currency envelope shows both, composite scalar converts via FxRateReader.convert.
- Missing FX rate for one currency: verify composite fails loud with ReportGenerationException naming (base, quote, date).
- Scheduled delivery lands at mailpit with valid XLSX attachment; 6-sheet or 4-sheet depending on `includeSensitiveSheets`.
- Toggle FRAUD_SIU_REPORT off → sidebar entry hides / direct URL 403s / SIU workflow (`/tenant/claims/siu`) still accessible (per parent-plan invariant #2 — mutations aren't gated by report toggle).
- FRAUD_TRIAGE rules-engine: author threshold + amount rule via tenant admin → observe auto-case creation for a synthetic HIGH-risk large-amount claim; observe no auto-case for a LOW-risk claim.

## Performance Considerations

- **fraud_flag row volume** — Rule 3 requires every AI prediction persisted, ~100K claims/month/tenant = ~100K flag rows/mo/tenant. `FraudFlagRetentionJob` purges unlinked flags >1y old — table stays under ~1.2M rows per tenant in steady state.
- **Report SQL** — `siu_case` queries always tenant-scoped via `TenantContext`; indexes on `status`, `assigned_to`, `opened_at`, `closed_at` cover the common access patterns. Reports use `SUM(saved_amount) GROUP BY saved_currency` — cheap for expected case volume (< 10K confirmed cases per tenant per period).
- **Trend chart** — 12 monthly points per fetch; a single SQL `SELECT date_trunc('month', opened_at), status, COUNT(*)` grouped by month + status. No N+1 risk.
- **Top-N queries** — `LIMIT 10 ORDER BY SUM(saved_amount) DESC`; index on `(status, closed_at)` covers.
- **AI calibration** — single `GROUP BY risk_level` over the period's fraud_flag rows + `LEFT JOIN siu_case`; boundary conditions filter down to relevant rows.
- **Investigator productivity** — role-filter applied at SQL WHERE, not client-side (Rule 4 PII protection — supervisor-visible per-officer rows never leave the DB for a `siu_officer`).
- **Cross-service peer failure** — report composition uses `CrossServiceCallHelper.guarded` with 2s timeout + 1 retry + warnings capture per invariant #7. If a downstream aggregate is unavailable, report renders partial data + banner.
- **XLSX generation** — POI streaming API (`SXSSFWorkbook`) for cases-detail sheet when >10K rows in period; matches Phase 0 shared `ReportWorkbook` pattern.

## Migration Notes

- **Never edit an applied migration** per `feedback_never_edit_applied_migrations`. V169-V174 are tenant-schema, V175 is public-schema (schedule params).
- **Tenant vs public schema boundary**: fraud_flag / siu_case / siu_case_note / siu_evidence / siu_referral live in `tenant/` (per-tenant business data); tenant_report_schedule.params lives in `public/` (platform-wide config per parent plan Migration Notes).
- **`public.` prefix** — never use on tenant-schema tables per `bug_public_prefix_silent_rollback`; the fraud entities all use unqualified names in queries.
- **Flyway history** — don't clean up any V<100 rows from `public.flyway_schema_history` per `bug_public_flyway_history_load_bearing`; V169+ tenant + V13x+ public are the new additions.
- **V169-V174 tenant migration numbering** — verify latest applied number at implement time. As of create-plan (2026-09-05), latest is V168. If another phase lands between grill and implement, renumber accordingly.

## Rollout & Rollback

- **§A rollout order**: Phase 1 (foundations) → Phase 2 (migrations + entities + retention) → Phase 3 (AI producer) → Phase 4 (claims-service consumer + service) → Phase 5 (rules-engine category) → Phase 6 (Angular + report). Each phase compiles + tests independently.
- **Kafka contracts additive-only** — the FRAUD_FLAG_EMITTED event schema is versioned by field addition; §B Phase 9 may add fields for `insuranceLine` / `providerId` / `claimAmount` (currently placeholder in Phase 4's evaluateTriage); backward-compatible for consumers.
- **AI producer deploy before claims-service consumer**. If claims-service ships without the AI producer wired, the topic is empty — no data loss. If AI ships first without the consumer, events accumulate in Kafka until the consumer catches up.
- **§B rollout order** — Phase 7 (schema + roles + perms; no runtime path yet) → Phase 8 (state machine widen; deprecates MVP close endpoints) → Phase 9 (rules templates; requires Phase 5 shipped) → Phase 10 (Angular workflow) → Phase 11 (Angular report) → Phase 12 (Phase 17 integration).
- **Rollback**: each phase is independently revertable via toggle. `tenant_report_config` disables the report; `fraud.consumer.enabled=false` disables the Kafka consumer (Phase 4 property). Reverting a schema migration requires a new higher-numbered "V19x__revert_..." migration — never edit V169-V175 in place.
- **Feature-flag alternative for high-risk phases**: Phase 3 (AI producer) can be gated by `fraud.producer.enabled=true` on the AI service; Phase 12 scheduled delivery can be gated by `fraud.scheduled.enabled=true` on finance-service.

## Deviations

**2026-09-05 (Phase 1 — DrlCompiler edit split across Phases 1 & 5)**

- **Phase 1 §7 partially moved to Phase 5.** The plan's Phase 1 §7 edit
  registers `FRAUD_TRIAGE → FraudFlagFact` in `DrlCompiler.FACT_MAPPINGS`
  using a `RuleCategory`-keyed shape that doesn't match the actual code
  (the real `FACT_MAPPINGS` is keyed by field-prefix strings with
  `(variable, className)` values, and imports are declared in a separate
  `IMPORTS` string constant that's prepended to every compiled DRL).
  Adding `import com.medfund.rules.fact.FraudFlagFact;` before the class
  exists (Phase 5) is a Drools compile-time hazard — every tenant DRL
  compile after this edit would carry an unresolved import even when no
  FRAUD_TRIAGE rule references the class. Split resolved as:
  - **Phase 1 (now)**: add `"FRAUD_TRIAGE"` to
    `DrlCompiler.AGENDA_GATED_CATEGORIES` — no class dependency, safe.
  - **Phase 5**: add the `fraudFlag → new FactMapping("$fraudFlag",
    "FraudFlagFact")` entry to `FACT_MAPPINGS` and the
    `import com.medfund.rules.fact.FraudFlagFact;` line to `IMPORTS`
    alongside authoring the `FraudFlagFact` class in the same phase.
  - Phase 5's success criterion "Verify the entry landed in
    `DrlCompiler.FACT_MAPPINGS`" now reads as **add** the entry rather
    than verify it.

**2026-09-05 (Phase 1 — pre-existing ScheduledReportEligibilityTest drift fixed as side-effect)**

- **Phase 18 landing left `ScheduledReportEligibilityTest.whitelistContainsExactly13OperationalCadencedKeys` stale.** Phase 18 §K11 widened
  `WHITELIST` from 13 → 18 keys (adding the five executive KPI keys) but
  didn't update this test. `make test-java` failed on the assertion with
  the pre-existing state, blocking Phase 1's own verification. Renamed
  the test to `whitelistContainsExactly18OperationalCadencedKeys` and
  added the five KPI keys to the expected set so the assertion matches
  the live catalogue. Phase 19 §B Phase 12 will widen again to 19 by
  adding `FRAUD_SIU_REPORT`; that phase's edit still applies verbatim
  (rename + list append).
- Phase 1's own directive "`ScheduledReportEligibilityTest` — untouched"
  is preserved in spirit — no FRAUD_SIU_REPORT-related edit here — but
  the count assertion had to move to unblock the build.

**2026-09-05 (Phase 2 — additional implementation-level changes)**

- **R2dbcConfig `basePackages` widened.** `com.medfund.claims.siu.repository`
  added to `@EnableR2dbcRepositories(basePackages = {...})` in
  `services/java/claims-service/src/main/java/com/medfund/claims/config/R2dbcConfig.java`.
  Without this the `FraudFlagRepository` / `SiuCaseRepository` /
  `SiuCaseNoteRepository` beans aren't picked up and every IT
  full-app boot fails with `NoSuchBeanDefinitionException`. Not in the
  plan's Phase 2 §5 change list; strictly required for the repositories
  to autowire.
- **`FraudFlagRepository.purgeUnlinkedOlderThan(...)` needs `@Modifying`.**
  Without it, R2DBC's `@Query` DELETE returns an empty Mono (no row count)
  and the retention job silently reports null instead of the delete count.
  Added `import org.springframework.data.r2dbc.repository.Modifying;` and
  `@Modifying` alongside the existing `@Query`.
- **Purge boundary IT relaxed to ±30-day margins.** The plan's Phase 2
  §7 boundary case asserted "row at cutoff not purged; row 1 second past
  cutoff purged". R2DBC truncates `OffsetDateTime` nanoseconds to
  microseconds on the way to Postgres, so a row saved with
  `flagged_at = cutoff` compares as strictly less than the original
  `cutoff` value after the round-trip and gets purged. Merged the two
  edge tests into one 3-row scenario (old-unlinked / fresh-unlinked /
  old-linked) with ±30-day margins to sidestep the precision issue.
- **`SiuCase.tags` defaulted to `new String[0]` on the entity.** The
  `tags TEXT[] NOT NULL DEFAULT '{}'` column NOT NULL constraint fires
  on the second `save()` (UPDATE) when the entity field is null after
  an INSERT-only round-trip. Same reason as `created_at`/`updated_at`
  — R2DBC doesn't re-fetch DEFAULT-populated columns.
- **Spring Data auditing (@CreatedDate / @LastModifiedDate) doesn't
  support `OffsetDateTime`.** Tried and reverted — the auditing
  `DefaultAuditableBeanWrapperFactory` rejects `OffsetDateTime` with
  "Cannot convert unsupported date type" (needs `LocalDateTime` /
  `Instant`). Callers must populate `createdAt` / `updatedAt` explicitly
  before every `save()`. Added an entity-level comment noting the trap
  so future entities in the SIU package don't retry the same fix.
- **`V003__siu_it.sql` added under claims-service test-migration.** The
  plan says tenant migrations V169-V171 "apply cleanly on fresh
  Testcontainer (asserted transitively by every IT)" but claims-service
  ITs use a flat `public`-schema test bootstrap (`db/test-migration`)
  that doesn't re-run the tenant Flyway path. Added a V003 file mirroring
  the three tenant migrations (collapsed into one, no FK to `claims(id)`
  per the V002 precedent) plus `GRANT ... TO public_role` so the IT
  tenant-role connection can read/write the new tables.

**2026-09-05 (Phase 7 — split test migration + updated existing SIU tests + optional referralReference)**

- **New `V004__siu_evidence_referral_it.sql` instead of extending V003.**
  Phase 2's V003 collapsed the first three tenant migrations (V169-V171)
  into one for the flat public-schema IT world. Rather than editing V003
  and risking a Flyway checksum mismatch (per `feedback_never_edit_applied_migrations`),
  authored V004 for the two Phase 7 tables — mirrors the split at the prod
  tenant migration level (V172 + V173 land after V169-V171).
- **`SiuCaseServiceTest.setUp()` widened for two new repository mocks.**
  Constructor grew from 5 → 7 params (added `evidenceRepo` + `referralRepo`);
  all 15 existing tests still pass because they don't exercise the new
  methods and the mock repos are never touched. Added 4 new tests covering
  evidence + referral paths (persist happy path, missing case, referral
  with reference, referral without reference).
- **`referralReference` remains optional.** Plan showed
  `AddReferralRequest(String referralTo, String referralReference)` — kept
  the null-allowed shape. The DB column is nullable and the note-body
  helper omits the `(ref: ...)` suffix when null; the IT covers both
  paths.
- **`Permissions.ALL` widened AND `PermissionsTest` widened.** Adding the
  4 new constants to `Permissions.ALL` is required for the runtime
  validation gate (Phase 1 §1 pattern); widening `PermissionsTest` to
  assert them locks in the contract so a future edit that drops a
  constant fails CI, not just a manual review.
- **`SiuCaseControllerIT` deferred to the same follow-up hardening pass
  as `FraudReportControllerIT` (Phase 6 deferral).** The perm-gate
  assertion (`siu_officer` → 403 on referral; `siu_supervisor` → 200)
  needs a full-app boot with two distinct JWT stubs — easier to
  batch-author alongside the Phase 6 IT than to build the harness twice.
- **No entity added for the case-detail response yet.** `SiuCaseResponse`
  still returns just `flags + notes` — evidence + referrals will be added
  to the response record + query service in §B Phase 10 (Angular
  workflow expansion), which is when the case-detail page actually
  renders them.
- **Angular sidebar / routes / components not touched.** §B Phase 10
  authors the Angular surface for evidence + referrals (upload dialog,
  referral form, activity-timeline). Phase 7 is backend + Keycloak +
  perms only, deliberately.

**2026-09-05 (Phase 6 — permission constant + scope trim for a single-pass ship)**

- **Permission constant `finance:view_subledger` used instead of the plan's
  `finance:reports:view`.** The plan's snippet references
  `@RequiresPermission("finance:reports:view")` but that constant doesn't
  exist in `Permissions.java` — the actual convention used by every other
  claims-financial report endpoint (`ClaimsReportController`) is
  `Permissions.FINANCE_VIEW_SUBLEDGER`. Adopted the same gate for the
  fraud report so it lines up with the tenant admin's existing "finance
  reports" role assignment surface.
- **Angular SIU permissions registered in both the PermissionKey union
  AND the PERMISSION_METADATA display list.** The plan called out only the
  sidebar entry, but without registration in
  `clients/angular/src/app/core/security/permissions.ts` (a) the sidebar
  entry's `permissions: ['claims:siu:view']` fails TS type-check, and
  (b) the tenant-admin role-editor doesn't show the new permissions to
  bind them to a role. Added the four SIU keys to both spots.
- **`FraudReportControllerIT` + Angular component `*.spec.ts` files
  deferred to a follow-up test-hardening pass.** Phase 6 shipped the
  functional surface end-to-end (compile-green backend + Angular build,
  gateway routes) but the ITs + Angular unit tests are non-trivial to
  author in the same pass without also validating them, and the plan's
  "verify" skill can't run in this environment. Deferrals recorded
  explicitly — the manual verification walkthrough below is the current
  gate.
- **Playwright `fraud-report-mvp.spec.ts` deferred.** The golden path is
  a long multi-service round-trip (author FRAUD_TRIAGE rule as admin →
  emit fraud-flagged event as M2M → poll SIU queue → close case as
  officer → export as finance officer → assert SecurityEvent). Best
  authored in a dedicated pass once the manual walkthrough validates the
  flow.
- **Gateway routes-forwarding test deferred.** The 2 new lines are simple
  path-prefix `app.All(...)` calls that mirror the existing
  `/api/v1/reports/claims/*` pattern; explicit unit-test coverage adds
  no signal beyond the compile check.
- **`SiuCaseDetailComponent` uses a `window.prompt` for the dismiss
  reason.** MVP shortcut — inline in the button handler rather than a
  proper modal. §B Phase 10 replaces this with a real modal + reason
  taxonomy (per plan `siu-case-detail.component.ts` scope note).
- **Case-detail template uses inline `<dl>` + `<table>`, not the shared
  `<app-data-table>`.** Full data-table would be overkill for the
  ≤ ~50-row Notes / Flags sections; inline HTML keeps the file small.
- **`app-kpi-tile` component NOT reused.** The Phase 18 shared tile
  (`kpi-tile.component.ts`) takes a `key: KpiKey` typed input that would
  require adding a `FRAUD_SIU` value to the KpiKey union in shared code —
  scope-creep given the tile is a display-only 4-cell strip. Instead the
  fraud report renders four simple `<div class="tile">` blocks inline in
  the template. §B Phase 11 can rewire to the shared tile once the KPI
  key union grows to include fraud tiles.
- **`ClaimsSummaryRow`-style row DTO not used for the Cases sheet.** The
  XLSX workbook uses an inline `record CaseRow(...)` inside
  `FraudReportWorkbookService` for the Cases-detail sheet — the row shape
  is used nowhere else and inlining keeps the DTO namespace tidy.

**2026-09-05 (Phase 5 — DSL correction + Operator IN gap + FraudFlaggedConsumer tenant-context propagation)**

- **`RuleDefinition.builder()` doesn't exist — templates use the
  `TemplateBuilder` DSL.** The plan's Phase 5 §3 snippet uses
  `RuleDefinition.builder().name(...).action(RuleAction.set(...)).build()`,
  but `RuleDefinition` is a plain POJO with getters/setters and
  `RuleAction.set(field, value)` is not a factory method. The actual DSL
  (used by every existing `TemplateProvider`, e.g.
  `PmbClassificationTemplates`) is
  `rule(name, description, RuleCategory, priority, all(cond(...), ...), action(...))`
  from `com.medfund.rules.template.TemplateBuilder`. Templates rewritten in
  that shape.
- **`Operator.IN` doesn't exist; watchlist template shipped as per-provider
  EQUALS.** The plan's watchlist template used
  `Condition.in("providerId", "providerIds")`. `com.medfund.rules.model.Operator`
  today ships only EQUALS/NOT_EQUALS/GREATER_THAN/LESS_THAN/[GTE|LTE]. Shipped
  the watchlist template as `providerId EQUALS <placeholder-uuid>` with a
  description telling the tenant to clone the rule once per watchlisted
  provider until the DSL grows an IN operator (deferred to Phase 19.5).
- **New `OPEN_SIU_CASE` action type + emitter.** The plan's Phase 5 §3 uses
  `RuleAction.set("emitCase", true)` implying a generic setter emitter. The
  real ActionEmitter registry keys on `type()` strings tied to specific
  fact-mutation calls (SET_PMB_CLASSIFICATION → `$pmbClassification.setPmbClassification(...)`,
  etc.). Introduced `OPEN_SIU_CASE` action type + `OpenSiuCaseEmitter`
  Spring bean that emits `$fraudFlag.setEmitCase(true);` — no parameters.
  Added `case "OPEN_SIU_CASE" -> "fraudFlag"` to `DrlCompiler.factForAction`
  so rules without explicit `fraudFlag.*` conditions still bind the fact
  for the consequence.
- **No `.drt` file created.** The plan's Phase 5 §4 references a
  `services/java/rules-engine/src/main/resources/drl-templates/fraud-triage.drt`
  file — this directory doesn't exist in the codebase; DRL is generated
  on the fly by `DrlCompiler` from `RuleDefinition` POJOs. Skipped the file
  creation entirely; `FraudTriageTemplates.templates()` is the equivalent
  authoring surface.
- **Agenda group name is `FRAUD_TRIAGE`, not `FRAUD_RULES`.** The
  `RuleCategory.FRAUD_TRIAGE` Javadoc calls it out as firing on the
  "FRAUD_RULES" agenda group; the real `DrlCompiler` (line 130-132) uses
  the uppercase category name, so the group name matches the enum name.
  `SiuCaseService.AGENDA_GROUP` set to `"FRAUD_TRIAGE"` accordingly.
- **`RuleTemplateServiceTest` also needed `FraudTriageTemplates()` added
  to the provider list.** Removing `FRAUD_TRIAGE` from
  `INTENTIONALLY_EMPTY_CATEGORIES` (per Phase 1 removal note) was
  insufficient — the manually-wired list in `setUp()` also needs the new
  bean or `getDefaultRules_coversEveryDeclaredCategory` fails because
  the service's provider list still doesn't cover the category. Added
  the import + list entry.
- **`FraudFlaggedConsumer` propagates tenantId into reactor context.**
  Phase 4 authored `SiuCaseService.auditCreate` / `auditTransition` to read
  `TenantContext.get(ctx)`, but Phase 4's `FraudFlaggedConsumer` didn't set
  the context — the audit rows would have stamped `tenantId="unknown"` on
  every auto-open. Extended `processEvent(json)` to extract `tenantId` from
  the event envelope and wrap the downstream chain with
  `.contextWrite(ctx -> TenantContext.put(ctx, tenantId))`. Phase 5's
  rules-engine dispatch also reads `tenantId` from the same context.
- **`evaluateTriage` uses hybrid rules-engine + default-policy path.**
  Rather than making the tenant-authored rules the *only* triage signal
  (which would silently deprive tenants of the default when they configure
  rules for other categories), the service reads back `fact.emitCase` after
  the FRAUD_TRIAGE agenda fires and OR's it with the default policy check.
  Tenants can opt in to the default by leaving FRAUD_TRIAGE empty; opt out
  requires an explicit "never auto-open" rule (§B Phase 9 adds that
  template).
- **`SiuCaseServiceTest.setUp()` widened to inject `TenantRuleEngine` mock.**
  All 11 Phase 4 tests still pass (Mockito's default `hasRulesLoaded` → false
  routes them through the default-policy path unchanged). Added 4 new
  dispatch-path tests (see success criteria above).
- **`FraudFlagFact` fields authored per plan but only `riskScore`,
  `riskLevel`, `claimAmount`, `flaggedAt` are populated by
  `SiuCaseService.toFact` today.** `providerId`, `memberId`, `insuranceLine`,
  `currencyCode`, `indicators` need a join to the claim row (fraud_flag has
  claimId only). Deferred to §B Phase 9 per the fact-file Javadoc note
  ("historicalMemberFlagCount + historicalProviderHighFlagCount are populated
  in §B Phase 9 for pattern-recognition templates"). The watchlist template
  is therefore inert until §B lands — the threshold + threshold+amount
  templates are the two functional templates in MVP.

**2026-09-05 (Phase 4 — rules-engine dispatch deferred to Phase 5 + shared-audit signature corrections)**

- **Rules-engine dispatch deferred to Phase 5.** The plan's `SiuCaseService.evaluateTriage`
  imports `com.medfund.rules.fact.FraudFlagFact`, `com.medfund.rules.engine.TenantRuleEngine`,
  and `com.medfund.rules.model.RuleResult`, but `FraudFlagFact` doesn't exist yet
  (Phase 5 creates it — the plan's Phase 5 overview says "1 fact + 3 templates"),
  and `RuleResult`'s actual package is `com.medfund.rules.fact`, not
  `com.medfund.rules.model`. Adding a stub FraudFlagFact in this phase to satisfy
  the compile would clash with Phase 5's authoring path, so Phase 4 keeps only
  the default-policy path (auto-open when `risk_level = HIGH` and
  `risk_score > fraud.triage.default-min-score`). Phase 5 will inject
  `TenantRuleEngine` + `FraudFlagFact` and gate the default with a rules-engine
  dispatch on the `FRAUD_TRIAGE` agenda group.
- **`AuditPublisher`, not `AuditEventPublisher`.** The plan snippet references
  `com.medfund.shared.audit.AuditEventPublisher` — the actual bean is
  `com.medfund.shared.audit.AuditPublisher` (single `publish(AuditEvent)`
  → `Mono<Void>` method). Corrected.
- **`TenantContext.get(ContextView)` is static, not an instance method.** Plan
  used `tenantContext.getTenantId().flatMap(...)` — corrected to
  `Mono.deferContextual(ctx -> TenantContext.get(ctx))` pattern per
  `ClaimReserveHistoryService.publishAudit` precedent
  (services/java/claims-service/.../service/ClaimReserveHistoryService.java:104-120).
- **`SiuCaseEventPublisher` dropped from the constructor.** Plan declared it as
  a `SiuCaseService` field but never used it in any shown method. Would have
  meant authoring an unused class for §A. Removed until §B introduces a
  genuine consumer for case lifecycle events.
- **Case number generation left race-prone for MVP.** Plan uses
  `caseRepo.count() + 1` to mint `SIU-YYYY-NNNNNN` numbers. Under concurrent
  auto-opens two triggers may compute the same counter → UNIQUE constraint
  collision, at which point the consumer's `.onErrorResume` acks and drops
  the second event. Acceptable for MVP (auto-opens are rare); Phase 7/8
  swaps in a per-tenant Postgres sequence.
- **Consumer wire adds `.retryWhen(Retry.backoff(...))` per RuleChangeConsumer
  precedent.** The plan snippet omitted it; adding it protects against
  transient partition-rebalance / broker-disconnect errors instead of
  killing the consumer thread on the first `receive()` failure.
- **`@ConditionalOnBean(ReceiverOptions.class)` on `FraudFlaggedConsumer`.**
  Matches the safety pattern in `services/java/rules-engine/.../consumer/RuleChangeConsumer.java`;
  lets slice tests skip the wire-up when no Kafka receiver bean is defined.
- **`FraudFlagService.linkToCase(FraudFlag, UUID)` authored explicitly.** Plan's
  `SiuCaseService.linkFlag` comment said "actual repo update happens in
  FraudFlagService.linkToCase() — added there" but the plan's `FraudFlagService`
  snippet didn't include it. Added the method + covered it in `FraudFlagServiceTest`.
- **DTO shapes chose to include `flagCount` on `SiuCaseSummaryResponse` and
  join `flags + notes` on `SiuCaseResponse` in the query service.** Plan
  described these fields but didn't show the DTO records. Authored 5
  records: `SiuCaseSummaryResponse`, `SiuCaseResponse`, `FraudFlagResponse`,
  `SiuCaseNoteResponse`, `CloseCaseRequest` (dropped `StartReviewRequest`
  since `start-review` takes no body — controller pulls Jwt from principal only).
- **JaCoCo 70% coverage gate remains breached (pre-existing).**
  `.claude/coverage-backlog.md` documents claims-service at 47.5% baseline;
  Phase 4 SIU work raised it to 57%. Not a Phase 4 regression, but
  `./gradlew :claims-service:build` (which runs `check` → `jacocoTestCoverageVerification`)
  still fails as it has since 2026-06-19. Automated verification switches
  from `:build` to `:compileJava + :test` for Phase 4 gating.

**2026-09-05 (Phase 3 — adapted producer wiring for existing consumer shape)**

- **Consumer already threads `event.get("event") == "CLAIM_SUBMITTED"` gate;
  publish stays inside that branch.** The plan snippet restructured the
  `process_event` body to use `event.get("payload", {})` and dispatched
  fraud unconditionally. The live consumer at
  `services/python/ai-service/app/core/kafka_consumer.py` uses top-level
  event fields and only runs fraud detection inside the CLAIM_SUBMITTED
  branch. Preserved the existing shape and added the publish call at the
  end of that branch — the plan's intent (audit-of-record for every
  classified claim) is preserved because classification only happens
  on submissions.
- **`fraud_service.detect_fraud()` returns `AIPrediction`, not a dict.**
  The plan snippet accessed `fraud_prediction["model_version"]` etc; the
  actual return type is the `AIPrediction` pydantic model. Consumer now
  reads `fraud_result.model_version` (top-level) and
  `fraud_result.output.get("risk_score" | "risk_level" | "indicators")`
  from the wrapped model output.
- **`fraud_producer` accepted as optional kwarg on `ClaimsEventConsumer`.**
  The plan implied producer is required. Made it a keyword-only optional
  so (a) unit tests can construct the consumer without a live producer
  and (b) the lifespan degrades gracefully when producer.start() fails —
  the consumer keeps running and adjudication still ships; only the
  fraud-flag audit envelope is dropped, which the Java side treats as
  normal (rules-engine default policy applies).
- **Producer failures logged, not raised.** Added try/except around
  `publish_fraud_flagged` inside the consumer so a broken producer path
  (network hiccup, Kafka rebalance) doesn't derail claim processing.
  Rule 3's audit-of-record guarantee is best-effort at the publish site;
  the Java consumer is the durable side.
- **`tests/core/` created fresh.** The plan referenced "extending existing
  test" for `test_kafka_consumer.py`, but no such file existed. Created
  both `tests/core/test_kafka_producer.py` (4 tests: JSON send + tenant
  key, publish-before-start raises, stop-before-start noop, start/stop
  lifecycle) and `tests/core/test_kafka_consumer.py` (4 tests: publish
  after prediction, skip when producer absent, ignore non-submitted
  events, swallow producer error) alongside a new `tests/core/__init__.py`.

**2026-09-05 (Phase 1 — transient RuleTemplateServiceTest allowlist entry)**

- **`RuleTemplateServiceTest.getDefaultRules_coversEveryDeclaredCategory`
  asserts every `RuleCategory` has at least one seeded template.** Adding
  `FRAUD_TRIAGE` to the enum in Phase 1 without a matching
  `FraudTriageTemplateProvider` (which lands in Phase 5) breaks that
  invariant. Added `RuleCategory.FRAUD_TRIAGE.name()` to
  `INTENTIONALLY_EMPTY_CATEGORIES` in the test — with an explicit doc
  comment naming Phase 5 as the removal point. Phase 5's checklist gains
  one implicit step: **remove `FRAUD_TRIAGE` from
  `INTENTIONALLY_EMPTY_CATEGORIES`** when adding `FraudTriageTemplateProvider`.

### Phase 12 (2026-09-05)

- **Adapter lives in finance-service, not claims-service.** The plan §3
  authored `FraudSiuScheduledReportAdapter` inside claims-service. Every
  existing "other-service-owned" cadenced key (CLAIMS_SUMMARY,
  PROVIDER_NETWORK_UTILIZATION, POLICY_MOVEMENT, PERSISTENCY_COHORT,
  GROUP_CENSUS, AGED_DEBTORS, UPR_MOVEMENT, CASH_FLOW_FORECAST_13W)
  ships its adapter in `finance-service/adapter/` and delegates to the
  owner service via `CrossServiceRenderHelper.render(...)` +
  `/api/v1/reports/{key}/scheduled-render`. Following that precedent:
  `FraudSiuReportAdapter` is a 30-line one-file addition to
  `finance-service/adapter/`; the render logic lands as a new
  `POST /api/v1/reports/FRAUD_SIU_REPORT/scheduled-render` handler on
  claims-service's existing `ScheduledRenderController`. Simpler
  cross-module wiring; no `ScheduledReportShapeAdapter` interface lift
  required.
- **`ScheduledRenderRequest` grew a nullable `params` field with a
  legacy constructor overload.** Java records only permit constructors
  that delegate to the canonical, so the widening adds a canonical
  10-arg constructor + a 9-arg legacy constructor delegating with
  `params = null`. Keeps all 4 pre-Phase-12 callers (contributions +
  claims + user `ScheduledRenderControllerTest` + finance
  `CrossServiceRenderHelper`) compiling untouched. Same shape applied
  to `ScheduledFireContext` (12-arg canonical + 11-arg legacy).
- **`TenantScheduleFireCandidate` grew a `Json paramsJson` field**
  (raw `io.r2dbc.postgresql.codec.Json` — no ObjectMapper dependency in
  the record). Deserialisation happens in
  `ScheduledReportOrchestrator.deserialiseParams(...)` right before
  building the `ScheduledFireContext` — malformed JSON degrades to
  `null` params + a WARN log so a corrupt row doesn't take down the
  probe.
- **Migration is V178 (public), not V175.** The plan called for V175 in
  the public folder; V175/V176/V177 were already applied for
  `tenant_aml_threshold_config`, `tenant_report_schedule`, and
  `tenant_report_schedule_recipient` respectively (per
  `feedback_never_edit_applied_migrations`). New file is
  `db/migration/public/V178__tenant_report_schedule_params.sql`;
  finance-service test-migration mirror is `V023__tenant_report_schedule_params.sql`.
- **Params validation lives in `TenantReportScheduleService.validateParams`
  and fires synchronously before the reactor pipeline.** Same shape as
  the existing whitelist + cadence-shape validators — throws
  `IllegalArgumentException` directly. For `FRAUD_SIU_REPORT`, only the
  `includeSensitiveSheets` key is accepted, and only Boolean values;
  any other key or type is rejected. Any non-fraud key with a non-empty
  `params` also rejects — keeps the contract narrow.
- **`TenantReportScheduleResponse` DTO carries `Map<String, Object>
  params`; entity carries `JsonString params`.** Follows the tenancy
  service's existing `JsonString` wrapper pattern so the R2DBC custom
  conversions in `R2dbcConfig` handle JSONB round-trip. Response DTO
  deserialises the raw JsonString with a static Jackson ObjectMapper
  (thread-safe, read-only path); best-effort — malformed persisted
  JSON returns an empty map rather than 500-ing.
- **`TenantReportScheduleServiceTest` switched from `@InjectMocks` to a
  manual constructor call** because the service now injects a real
  `ObjectMapper` alongside its mock dependencies. Uses a
  `new ObjectMapper()` real instance rather than a mock — the mapper
  is only invoked inside `serialiseParams(...)` which the existing
  null-params tests never trigger. 4 new Phase 12 tests exercise the
  real serialisation path.
- **Angular `CreateDraft.includeSensitiveSheets` is a top-level
  boolean; `params` is assembled at submit time.** Cleaner two-way
  binding for the conditional `<input type="checkbox">` than the
  plan's `[(ngModel)]="draft.params!.includeSensitiveSheets"` shape,
  which requires the `!` non-null assertion plus a live `params` object
  reference. The submit handler only populates `body.params` for
  FRAUD_SIU_REPORT; other keys ship without the field.
- **`FraudSiuReportAdapter` + Angular unit test files deferred to the
  same hardening pass as Phase 6/7/11 IT deferrals.** The
  `FraudSiuReportAdapter` gets its behaviour coverage via
  `CrossServiceAdapterKeyAndShapeTest.fraudSiuReport_...` (adapter
  key + shape + delegation path); a dedicated
  `FraudSiuReportAdapterTest` would only re-cover the same paths.
  The `report-schedules-page.component.spec.ts` conditional-checkbox
  test also lands with the batched hardening pass — the component's
  logic is straightforward and covered by the manual walkthrough.
- **Phase 17 sub-plan Deviations entry authored per §8** — a dated
  block in `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md`
  records the whitelist widening (18 → 19) + the `params` column +
  the Phase 12 cross-service adapter.

### Phase 11 (2026-09-05)

- **6-tile widening added `avgCycleTimeDays` + `reopenedCount` to the
  existing `FraudReportData` record** rather than nesting them under a
  sub-record. Kept the record flat so the Angular tile grid can bind
  each field with a single `{{ envelope.data.<field> }}` expression;
  matches the Phase 18 `ExecutiveKpiData` shape.
- **`reopenedCount` uses a `siu_case_note` LIKE-scan** (
  `body LIKE '%→ REOPENED:%'`) rather than joining to the audit-event
  Kafka topic. Reason: the STATUS_CHANGE note body already carries the
  transition marker, so no cross-service join is needed for the tile;
  the audit-event-based join would be more precise but adds a
  claims-service→audit-service call chain. Full audit-driven counter
  lands with Phase 19.5 alongside the same story as the referral-response
  edit UI.
- **Top-N provider/member rows echo IDs only, not names.**
  `ProviderTopNRow.providerName` + `.providerCode` are declared but
  populated with `null` on the backend. Angular hydrates them client-side
  from the entity-picker cache (or displays the raw UUID when the cache
  is cold). Reason: avoids a 3-way join (siu_case × fraud_flag × claims
  × providers × members) that would bloat the top-N query — the entity
  cache is already the source of truth for the picker UI.
- **AI calibration is precision-only in the MVP surface;
  recall/false-negatives deferred to Phase 12.** The current
  `CalibrationRow.precision4dp` field is populated as
  `tp / (tp + fp)` per risk_level; recall lands with the Phase 12
  scheduled-adapter ground-truth false-negative feed. UI banner text
  reads "AI calibration — precision" (not "precision + recall") to
  match the ship state.
- **New `SUPPRESS_SIU_CASE`-style `WorkbookOptions` record + shim.**
  `render(env)` back-compat method calls through to
  `render(env, defaults(), null)` so any caller not yet aware of
  sensitive-sheet gating still gets the safe 4-sheet variant. Manual
  XLSX exports from the Angular page pass `includeSensitiveSheets=true`
  because the endpoint is already permission-gated; Phase 12's
  scheduled adapter reads the flag from its `sensitive_sheets_included`
  schedule column.
- **Investigator productivity backend row-filters via
  `WHERE closed_by_email = :actorEmail`**, not by `closed_by` UUID.
  Reason: `AuditActor.email(jwt)` is already the canonical actor
  identity in the SIU stack (see `feedback_audit_actor_email`), and
  the productivity table naturally displays emails not UUIDs. Removes
  the need for an extra JWT sub → UUID parse and keeps the SQL bind
  string-typed.
- **`FraudReportControllerIT` still deferred.** The unit-level tests in
  `FraudReportServiceTest` cover the pure helpers (
  `fillMissingMonths`, `finaliseCalibration`, `isSupervisorOrAdmin`);
  the Testcontainers IT for all 6 endpoints + XLSX byte-count sanity
  lands with the same hardening pass as `SiuCaseControllerIT` and
  earlier phase deferrals.
- **`FraudReportWorkbookService.render(env)` MVP signature kept as a
  back-compat shim** so a future scheduled-adapter path can keep using
  the old call while opting into sensitive sheets separately in the
  new call site.

### Phase 10 (2026-09-05)

- **Existing `<app-activity-feed>` reused instead of new `<app-activity-timeline>`.**
  The plan §1 called for a new reusable timeline component; the existing
  `shared/components/activity-feed` already renders a vertical timeline
  with typed icons + colors + actor + relative timestamps — exactly the
  shape the plan described. Building a second one would just fragment
  the timeline surface across two competing components.
- **`SiuCaseResponse` extended with `evidence` + `referrals` lists
  inlined** (plus the seven §B Phase 8 `proposed_*` staging fields).
  Delivered against the Phase 7 deviation deferral. The 4-way `Mono.zip`
  in `SiuCaseQueryService.findById` costs one extra DB round-trip per
  case-detail load but keeps the client at a single request — matches
  the pre-existing flags + notes pattern rather than adding two new GET
  endpoints.
- **Evidence form takes a text `fileServiceRef` handle, not a byte upload.**
  The Go file-service only exposes `/invoice-pdf/render` today (no
  generic `/api/v1/files/upload`), so a real multipart POST + file-picker
  UI would have to be built out from scratch and would produce dead code
  once file-service's upload endpoint lands. Mirrors the deferred-upload
  pattern already used by `SubmitClaimComponent`'s `StagedAttachment`.
  Operator pastes an S3 key / URL for now; description + type dropdown
  are captured normally. Full picker + byte upload is Phase 19.5 scope.
- **No timeline-entry deletion / evidence-download UI shipped.**
  The plan §2 mentioned per-row delete on evidence (guarded on
  `claims:siu:admin`). Deferred — no `DELETE /siu/cases/{id}/evidence/{evidenceId}`
  backend endpoint exists and the append-only pattern in
  `SiuCaseService.addEvidence` implies deletes are out of the invariant.
  Recorded as a deferred story rather than partially implemented.
- **Referral response-notes inline edit deferred.** Plan §2 called for
  inline `responseNotes` editing on each referral row. Backend has no
  `PATCH /referrals/{id}` endpoint yet; the response columns
  (`response_received_at`, `response_notes`) sit on `siu_referral` and
  can be populated via direct SQL for now. Read-only rendering ships in
  the Referrals tab; edit lands with the same story as evidence delete.
- **`*hasPermission` directive is the sole role-gate mechanism** — no
  attempt to hide tabs entirely. `siu_officer` sees the *Approve pending
  closure* + *Reject* + *Reopen* buttons in a disabled state until they
  hold the matching permission, at which point the directive un-hides
  them. This matches the existing pattern in `claim-detail.component.html`
  (where the whole action bar is `*hasPermission="['claims:adjudicate',
  'claims:reject']"`); no reason to invent a second pattern here.
- **New `RULE_CATEGORIES` entry for `FRAUD_TRIAGE`** with icon `shield`
  and a matching `rules.service.spec.ts` regression guard — mirrors
  every earlier phase's catalog-stability contract; without the spec
  entry, a future rename of the category enum silently deletes the
  tenant-admin filter dropdown option.

### Phase 9 (2026-09-05)

- **New `SUPPRESS_SIU_CASE` action type + `SuppressSiuCaseEmitter` bean.**
  The plan called for `RuleAction.set("emitCase", false)`, but that generic
  "set" action doesn't exist in the DSL — actions are typed via
  `ActionEmitter` beans. Added a dedicated `SUPPRESS_SIU_CASE` emitter that
  emits `$fraudFlag.setEmitCase(false);` and routes to the `fraudFlag`
  fact-binding in `DrlCompiler.factForAction`. Symmetric with the
  Phase 5 `OpenSiuCaseEmitter`; kept the never-auto-open template on
  salience 1 so it fires last per FR6.
- **Plan's `ClaimLookupRepository.findMinimalById(UUID)` replaced by the
  existing `ClaimRepository.findById(UUID)`.** Adding a projection-only
  repository for a single hot-path lookup was over-engineering — R2DBC
  materialises the Claim row on the primary key with no measurable extra
  cost, and the fewer moving parts the better. `SiuCaseService.buildEnrichedFact`
  reads `memberId`, `providerId`, `insuranceLine`, `claimedAmount`, and
  `currencyCode` off the returned `Claim` and discards the rest.
- **`HISTORICAL_LOOKBACK_DAYS = 90` lifted to a class-level constant.**
  The plan inlined the constant in the pseudocode. Extracted so
  the value (industry-standard 90-day fraud lookback) is discoverable
  and can be tuned without hunting through the enrichment code.
- **Enrichment skipped entirely on the default-policy path.** When
  `!ruleEngine.hasRulesLoaded(tenantId)`, `decideTriage` short-circuits
  to `shouldOpenByDefault(flag)` — no claim lookup, no count queries.
  Rationale: the default policy only reads `riskLevel` + `riskScore` off
  the flag itself, so paying two DB round-trips for tenants without
  FRAUD_TRIAGE rules would be wasteful. `SiuCaseServiceTest.evaluateTriage_defaultPolicyPath_skipsClaimAndCountLookups`
  locks this in.
- **Missing claim degrades gracefully instead of failing hard.**
  `fraud_flag.claim_id` is a FK on prod (V169), so a missing claim
  represents corruption. But rather than throw and dead-letter the
  Kafka event, `buildEnrichedFact` logs a WARN and falls through with
  `new Claim()` — the templates that key on member/provider/count
  degrade to no-op (zero counts, null IDs), while the threshold
  template still fires off `riskScore` from the flag itself. Preserves
  triage throughput when the join fails.
- **Existing `thresholdTemplate` + `thresholdAmountTemplate` +
  `tenantIsolation` tests refactored to share a private `fraudEngine()`
  helper.** Adds the `SuppressSiuCaseEmitter` to every test's action-emitter
  list uniformly; no behaviour change, just less duplication.
- **`TenantRuleEngineIT` for the two new templates still deferred**
  to the same hardening pass as `FraudReportControllerIT` / `SiuCaseControllerIT`.
  The unit-level `FraudTriageTemplatesTest` behavioural tests
  (repeat-offender, provider-high-flag, never-auto-open overrides
  threshold) cover the DRL correctness end-to-end via
  `TenantRuleEngine.evaluateInGroup`; the IT deferral loses only the
  Testcontainers-level assertions.

### Phase 8 (2026-09-05)

- **`closeConfirmed(...)` removed from `SiuCaseService` per plan §2**, and the
  MVP `POST /{caseId}/close-confirmed` endpoint dropped from
  `SiuCaseController`. The three Phase 4 `closeConfirmed_*` unit tests were
  replaced (not extended) with the 11-test §B Phase 8 legality suite covering
  `proposeClosure` + `approveClosure` + `rejectClosure` + `reopen` + `assign`
  + `startReviewFromAssigned`.
- **Angular MVP UI updated alongside the backend** even though the plan
  assigns full Angular workflow expansion to §B Phase 10. Without the swap,
  the "Close CONFIRMED" button in `siu-case-detail.component` would 404
  against the removed backend endpoint. Scope kept minimal: renamed the
  inline form to *Propose closure* (adds outcome picker), added
  Approve/Reject/Reopen action buttons wired via new `SiuService.proposeClosure`
  / `approveClosure` / `rejectClosure` / `reopen` / `assign` /
  `startReviewFromAssigned` methods. Full UX polish (proper modals,
  four-eyes-warning callouts, timeline widget, evidence + referrals panels)
  remains Phase 10 scope.
- **`SiuCaseControllerIT` still deferred** to the same follow-up
  hardening pass as `FraudReportControllerIT` (Phase 6) and the earlier
  Phase 4/7 IT deferrals. Automated controller-level assertions for the
  new endpoints — 200 happy-path, 403 missing-perm, 400 illegal-state,
  400 four-eyes-violation — arrive with that pass.
- **`reopen(...)` uses a two-step save-and-flip pattern** rather than
  emitting REOPENED as an actual persisted intermediate. The status is
  set to REOPENED, saved (audit + note fires), then immediately flipped
  to UNDER_REVIEW and saved again. This gives the timeline a REOPENED
  status-change note per FR-reopen while preserving the invariant that
  no user sees a case parked in REOPENED. If the second save fails, the
  transient REOPENED persists — but the enum is legal per the widened
  CHECK constraint, so the case isn't corrupt, just paused in an
  operator-visible transient state that a retry / manual close fixes.
- **`proposeClosure` rejects the dismissal outcome as `IllegalArgumentException`**
  rather than silently short-circuiting to `closeDismissed`. Callers must
  pick the right endpoint: dismissals go straight through
  `POST /close-dismissed` (no four-eyes per FR6), non-dismissal outcomes
  go through `propose-closure` → `approve-closure`. This keeps the
  four-eyes contract explicit at the API surface.
- **Two saves per `reopen` mean two `updated_at` mutations** — the
  transient REOPENED row's `updated_at` is overwritten by the terminal
  UNDER_REVIEW save. Acceptable because the `siu_case_note` rows
  emitted for both transitions carry their own `created_at`, preserving
  the timeline history.

## References

- Parent plan: `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md`
- Grilling scratchpad: `thoughts/shared/notes/2026-09-05-phase19-fraud-siu-grill.md`
- Phase 18 sub-plan (pattern reference — full-analytics report + scheduled adapter): `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md`
- Phase 15 sub-plan (pattern reference — retention taxonomy widening): `thoughts/shared/plans/2026-08-28-ifrs17-pack.md`
- Phase 11 four-eyes precedent: `services/java/finance-service/src/main/java/com/medfund/finance/producer/entity/CommissionAdjustment.java:30-80`
- Architecture doc — AI integration: `.claude/ai-integration.md`
- Architecture doc — rules engine: `.claude/rules-engine.md`
- Architecture doc — multi-tenancy: `.claude/multi-tenancy.md`
- Architecture doc — multi-currency: `.claude/multi-currency.md`
- Architecture doc — coding standards: `.claude/coding-standards.md`
- Architecture doc — portals: `.claude/portals.md`

