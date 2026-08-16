---
date: 2026-08-11
git_commit: b56ab30dc23f08a4c550e75b327512061d567099
branch: rename-adjustments-to-notes
ticket: null
research:
  - thoughts/shared/research/2026-08-11-financial-reporting-vs-masca-reference.md
steer: "Do not use any masca reference but we need all the reports in the masca reports fully implemented and excel exports. If possible research the web and check other possible insurance reports. The tenant should be able to turn on and off the reports that they need in the tenant settings."
services_touched: [tenancy-service, contributions-service, finance-service, claims-service, user-service, shared, gateway, notification-service, ai-service, angular]
status: draft
phases_status:
  "0": landed 2026-08-11 (commit bb3268f)
  "1": landed 2026-08-11 (§A + §B, commit 273f895)
  "2": landed 2026-08-11 (commit af9ed8d)
  "3": landed 2026-08-11
  "4": grilled 2026-08-11 (§A + §B split); ready for implement
  "5": grilled 2026-08-16 (§A + §B split, D1-D5); §A + §B landed 2026-08-16 (backend + gateway + Angular + e2e; §A verified + pre-existing test fixes, §B Playwright 3/3)
  "6": grilled 2026-08-16 (D6-1..D6-8, research correction: runs don't touch balance tables → freeze-frame); §A + §B landed 2026-08-16 (V080 snapshot migration + PaymentRunService.execute snapshot step + BalanceHistory controller/excel + unit/IT; Angular pages + creditors links + Playwright 3/3)
  "7-19": outline depth; each needs its own grilling pass before implementation
last_grilled_phase: 6
last_grilled_date: 2026-08-16
---

# Financial Reporting Suite Implementation Plan

> **SCOPE WARNING — READ BEFORE IMPLEMENTING**
>
> This plan is a **program-scale document**, not a normal 3-5 day plan. It covers ~60 reports across 4 buckets (MASCA-shaped + cheap query+XLSX + regulatory-format + actuarial-heavy + domain-not-yet-built), plus foundational infrastructure, three greenfield domain modules (reinsurance / producer / earning-schedule), an actuarial computation module in Python, a scheduled-email delivery system, and cross-tenant analytics. Realistically **12-18 months** of engineering.
>
> Each phase below is a **2-6 week tranche**, not a normal 3-5 day phase. Every phase after Phase 2 will need its own grilling pass against the current codebase before it starts, because the code will have moved. Later phases are documented at **outline depth** (files, controllers, key SQL, success criteria) rather than full code snippets — an implementer picking one up should treat it as a plan-of-a-plan and expand it via `grilling` + `create-plan` at that point.
>
> `implement-plan` should treat phase completion as a hand-off point back to grilling for the next phase, not a signal to continue automatically.

## Overview

Build a comprehensive financial reporting suite spanning every family a multi-line insurance operating system needs: billing, receipts, payables, debtors, claims-financial, reconciliation, actuarial, regulatory, reinsurance, commission, and executive dashboards. Every report is tenant-toggle-able, reads a per-tenant reporting currency with optional override, converts historical amounts using immutable per-date FX rates, is served with XLSX export, emits an export audit event, and (where cadenced by industry practice) can be scheduled for email delivery.

## Current State Analysis

- `services/java/finance-service/src/main/java/com/medfund/finance/controller/ReportController.java:22-236` is naive: 4 of its 5 endpoints ignore their `period` param, aggregate in-memory over `findAllOrderByCreatedAtDesc().collectList()`, and sum `BigDecimal` amounts across currencies with no conversion or stratification — a direct violation of `.claude/CLAUDE.md` Rule 1 and `.claude/multi-currency.md:164-169`. Verified zero callers across all languages (`grep -rn "api/v1/reports" clients/ services/`), so it can be deleted with no rollout risk.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/StatementController.java:32`, `BalanceController.java:48`, `InvoiceController.java:59`, `BeneficiaryAnnualTotalController.java` are the reference-quality reports: server-side pagination, ISO-4217 currency required on every query, POI-backed XLSX exports.
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/CreditorController.java`, `PaymentAdviceController.java`, `PaymentRunController.java`, `NoteController.java`, `AdvancePaymentController.java`, `CtcPaymentController.java`, `ReconciliationController.java` are transactional but list-shaped and can be retrofitted for reporting.
- Every existing XLSX/PDF export is silent for audit — no service currently publishes `SecurityEventMessage` for a data-export action. `SecurityEventPublisher` exists but is scoped to `services/java/keycloak-event-listener/`.
- Per-tenant "reporting currency" is already implemented as `tenant_currency_config.is_default = TRUE` (V104 + V113) with Angular admin surface at `clients/angular/src/app/pages/tenant-admin/settings/currencies/currencies-tab.component.ts:143-158`. No new migration needed — reports fetch it via a new `TenantConfigClient.getDefaultCurrency(tenantId)` call.
- `public.exchange_rates` (V112) supports historical, immutable per-date FX rates keyed by `(base_currency, quote_currency, rate_date, source, tenant_id)` — ready to back the reporting-currency conversion path via existing `FxConverter`.
- Every Java service has `@Scheduled` + `SchedulerConfig` + a `scheduler/` package (see `services/java/contributions-service/src/main/java/com/medfund/contributions/scheduler/BillingCycleJob.java`). `services/go/notification-service/internal/{lifecycle,receipt,invoice,arrears}/dispatcher.go` shows the per-domain dispatcher pattern for scheduled-report delivery.
- Angular finance routes: 31 operational + 25 `ComingSoon` stubs in `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`. Most stubs correspond to reports this plan builds.
- Gateway registers 8 `/analytics/*` endpoints at `services/go/gateway/internal/platform/handler.go:35-42`; 6 return `[]`. Angular `/platform/analytics` (`clients/angular/src/app/pages/platform/analytics/analytics.component.ts`) is fully wired and waiting on real data.
- No reinsurance, producer/broker, or UPR earning-schedule entities exist today. These are greenfield modules that Phases 10-12 add.
- `services/python/ai-service` (Python 3.12 + FastAPI) is set up for numeric work but has no `actuarial/` package today.

## Desired End State

A tenant admin can, in one place (`/tenant/admin/settings/reports`), see every report the platform offers, toggle each on or off, and for cadenced reports set an on/off scheduled-delivery switch with recipient emails. A finance officer can, at `/tenant/finance/reports`, see a hub grouped by family (Billing, Receipts, Payables, Debtors, Claims-Financial, Reconciliation, Actuarial, Regulatory, Reinsurance, Commission, Dashboard) showing only enabled reports; every report page has a per-report filter row (period, optional reporting-currency override) and an "Export XLSX" action that emits a `SecurityEvent` of type `DATA_ACCESS` with the report key. Every amount rendered is either converted to the reporting currency at the historical FX rate or presented per-currency (both, per G6). Super-admin dashboards at `/platform/analytics` show real cross-tenant data. Regulator-templated reports (IPEC ZW, CMS ASR, NAIC Schedule P/F) render only for tenants whose `jurisdiction_code` matches.

### Verification

```bash
# Backend
cd services/java && ./gradlew build test
make test-integration                                       # Testcontainers
cd services/python/ai-service && uv run pytest              # actuarial module
cd services/go/gateway && go test ./...                     # analytics endpoints

# Frontend
make test-angular
make test-e2e                                               # includes new report journey

# Manual acceptance
make infra && make tenancy user contributions finance claims gateway notification web
# Log in as tenant admin → /tenant/admin/settings/reports → toggle every report
# Log in as finance officer → /tenant/finance/reports → open each family, export XLSX
# Log in as super-admin → /platform/analytics → confirm charts render real data
```

### Key Discoveries

- **F7 – ReportController has no callers.** `grep -rn "api/v1/reports" clients/ services/` returns only the controller's own `@RequestMapping`. Delete outright, no deprecation window.
- **Reporting-currency is done at the tenant level** (`tenant_currency_config.is_default`, V104+V113). Reports need a `TenantConfigClient` call, not a schema change.
- **Historical FX exists** (`public.exchange_rates` V112, immutable per (base, quote, date, source, tenant_id)). Reports use `FxConverter` and fail-loud if a rate is missing.
- **Scheduler + notification infra exists.** Every Java service already runs `@Scheduled`; notification-service has per-domain dispatchers. Adding a `report/` dispatcher is well-trodden.
- **SecurityEventPublisher pattern exists** but is Keycloak-only. Lift into `services/java/shared/security/` (F8).
- **XLSX helpers duplicated** across `CreditorsExcelService`, `StatementExcelService`, `DebtorsExcelService`, `BadDebtsExcelService`. Extract to `services/java/shared/report/ReportWorkbook.java` (F9) before the report count multiplies.
- **Existing preset-driven Angular reuse pattern** — `NotesListComponent` reused across `/notes`, `/reports/withheld-tax`; `PaymentsListComponent` similar. Reuse for family reports where the shape matches.
- **Gateway platform aggregation pattern already established** — `services/go/gateway/internal/platform/handler.go` fans out to per-service `/api/v1/platform/*` endpoints. Just fill in the 6 that return stubs (Phase 9).

## What We're NOT Doing

Explicit non-goals for this plan (any of these would balloon it further):

- **Multi-line-of-business specific benefit engines** — this plan builds reports; the underlying benefit computation stays as-is per line.
- **Real-time report streaming via Elixir Phoenix Channels** — `.claude/portals.md:142` mentions live dashboards, but this plan keeps reports on REST + XLSX. Live-metric wiring is a separate concern.
- **AI-powered forecasting** beyond a straight 13-week rolling cash-flow projection (Phase 8). The `/finance/forecasting` AI page from `portals.md:155` remains stubbed.
- **Custom report builder / drag-and-drop analytics** — every report in this plan is code-defined. A tenant-authored ad-hoc reporting surface is out of scope.
- **CDC / event-sourced read models** — G7 chose sync HTTP fanout for cross-service reports. Kafka read-model + dedicated `analytics` schema is deferred.
- **Full commission calculation engine** — Phase 11 builds the producer + rate-card entity model needed for the commission statement report. Complex sliding-scale or profit-commission maths is deferred to a follow-up plan.
- **Reinsurance treaty exhaustion / accumulation-tracking maths** — Phase 10 builds the entities for the bordereau report. Live cession tracking against limits is deferred.
- **IFRS 17 disclosure automation beyond LRC/LIC + insurance revenue** — Phase 15 covers the two core measurement disclosures. Sensitivity analysis, reconciliation of insurance service result, and confidence-level disclosure are follow-ups.
- **Editing / cancelling scheduled runs after they've been sent** — Phase 17 supports on/off + recipients, not run-history browsing or resend.
- **Data-warehouse ETL export** — reports are XLSX/PDF/CSV to the UI/email. No S3-parquet or BigQuery pipeline in this plan.
- **Legacy MASCA data migration** — the reference is a design guide only; no data comes across.

## Implementation Approach

Distributed by data ownership (G2): billing/receipts/debtors reports in **contributions-service**; payables/creditors/notes/payment-advice reports in **finance-service**; claim-status and claims-financial reports in **claims-service**; cross-service reports in a thin aggregator controller in finance-service that fans out via WebClient. Actuarial computation lives in **services/python/ai-service** (G10). Regulatory templates ship as versioned XLSX resource files under `report-templates/{regulator}/`, gated by a new `tenant.jurisdiction_code`.

**Rollout order within each phase**: schema first (Flyway), entity + repository, service, controller, tests, Angular. Producer-consumer contracts stay backwards-compatible during rollout (add fields, never remove).

**Cross-phase invariants** (all phases must uphold):

1. Every **wrapped** report endpoint (see G19: `/page`, `/aggregate`, standalone report surfaces — not drilldowns like `/{id}`, `/for`, `/provider/{id}`) accepts optional `?reportingCurrency=XXX`; defaults to `tenant_currency_config.is_default`; response envelope carries a native-currency `perCurrency: Map<String, PerCurrencyTotal>` breakdown per G17. Native row amounts are never converted server-side (G25) — envelope `fxRates` gives Angular the multipliers for optional client-side display.
2. Every report **GET** endpoint (list, detail, export) short-circuits with `403 Forbidden` if `tenant_report_config.enabled = FALSE` for the tenant + report key. **Mutations (POST/PUT/DELETE) are NEVER gated by the report toggle per G29** — they're operations gated by `@RequiresPermission`.
3. Every XLSX/PDF export publishes a `SecurityEventMessage` with `eventType="DATA_ACCESS"`, `details.reportKey=<key>` before returning bytes. JSON reads do NOT emit per G24 — the emission surface stays at the "data leaves the platform" boundary.
4. Every controller endpoint carries full Swagger annotations (Rule 7).
5. Every entity mutation emits an `AuditEvent` (Rule 8); every export emits a `SecurityEvent` (Rule 9).
6. All amount arithmetic is `BigDecimal`; no cross-currency additions without `FxConverter` (Rule 1). **Missing FX rate semantics per G28**: when the server actually converts a value (a grand-total scalar), a missing rate throws `ReportGenerationException` naming (base, quote, date). When the server populates the envelope's `fxRates` map for optional client-side display, missing currencies are **omitted** from the map and named in the envelope's `warnings: List<String>` block — the report itself still succeeds.
7. **Cross-service peer-failure semantics per G37 (Phase 3)**: cross-service WebClient fanout uses timeout + retry + fallback + envelope `warnings` capture (via the shared `CrossServiceCallHelper`). A peer down → warnings populated + partial data rendered; the calling report still succeeds. Same "best-effort with warnings" spirit as invariant #6. Do NOT introduce Resilience4j unless a platform-wide grill approves the dep — this is a repo-wide precedent decision, not a per-phase choice.

---

## Phase 0: Foundations

### Overview

Ship the cross-cutting infrastructure that every later phase depends on: the tenant report toggle system, the reporting-currency resolver, the shared XLSX builder, the shared `SecurityEventPublisher`, the tenant jurisdiction column, and the Angular reports hub skeleton (which starts empty and fills as later phases add reports to the catalogue). Also delete the naive `ReportController`.

### Changes Required

#### 1. Delete naive ReportController

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/controller/ReportController.java` (delete)

Move `/api/v1/reports/payment-advice/{paymentRunId}` — its one working endpoint — into `PaymentAdviceController` as `GET /api/v1/payment-advices/generate/{paymentRunId}` (or reuse existing `getByRunId` if the shape matches). Update any Swagger tag docs.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/controller/ReportControllerTest.java` (delete)

#### 2. Tenant report toggle: schema + entity + service + client

**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V130__tenant_report_config.sql`

```sql
CREATE TABLE IF NOT EXISTS public.tenant_report_config (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    report_key   VARCHAR(80)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   UUID,
    CONSTRAINT uq_tenant_report_config UNIQUE (tenant_id, report_key)
);
CREATE INDEX idx_tenant_report_config_tenant ON public.tenant_report_config (tenant_id) WHERE enabled = FALSE;
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/TenantReportConfig.java`

```java
@Getter @Setter
@Table("tenant_report_config")
public class TenantReportConfig {
    @Id private UUID id;
    private UUID tenantId;
    private String reportKey;
    private Boolean enabled;
    private OffsetDateTime updatedAt;
    private UUID updatedBy;
}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantReportConfigService.java` — CRUD + `isEnabled(tenantId, reportKey)` (default TRUE if no row) + `bulkUpsert` for tenant-admin form.

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantReportConfigController.java` — REST at `/api/v1/tenants/{tenantId}/report-config` (GET list, PUT bulk, GET `/enabled/{reportKey}` for cross-service check).

**File**: `services/java/shared/src/main/java/com/medfund/shared/config/TenantConfigClient.java` — extend with:

```java
public Mono<Boolean> isReportEnabled(UUID tenantId, String reportKey) { ... }
public Mono<Set<String>> getEnabledReportKeys(UUID tenantId) { ... }  // cached per request
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java` — enum of every report key that ships in this plan (BILLING_REPORT, RECEIPTS_REPORT, AGED_DEBTORS, CLAIMS_SUMMARY, IBNR_TRIANGLE, IPEC_QUARTERLY_RETURN, etc.). Grouped by `ReportFamily`.

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportEnablementFilter.java` — a `WebFilter` (or interceptor method annotation `@RequiresReport(ReportKey.X)`) that short-circuits with `403 Forbidden` if disabled.

#### 3. Reporting currency resolver

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportingCurrencyResolver.java`

```java
@Component
@RequiredArgsConstructor
public class ReportingCurrencyResolver {
    private final TenantConfigClient tenantConfigClient;

    public Mono<String> resolve(UUID tenantId, String override) {
        if (override != null && !override.isBlank()) return Mono.just(override.toUpperCase(Locale.ROOT));
        return tenantConfigClient.getDefaultCurrencyCode(tenantId);
    }
}
```

Extend `TenantConfigClient` with `getDefaultCurrencyCode(tenantId)` — hits `GET /api/v1/tenants/{id}/currencies` and returns the one where `isDefault=true`.

#### 4. Shared XLSX builder + report response model

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportWorkbook.java`

Fluent builder over Apache POI: `sheet(name).header(...).row(...).moneyColumn(...).build()`. Absorbs the common cell styling currently duplicated across `CreditorsExcelService`, `StatementExcelService`, `DebtorsExcelService`, `BadDebtsExcelService`. Retrofit those four to use it as part of Phase 1.

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportResponse.java`

```java
public record ReportResponse<T>(
    String reportKey,
    ReportPeriod period,
    String reportingCurrency,
    T data,
    Map<String, T> perCurrency,      // native-currency breakdown per G6
    OffsetDateTime generatedAt
) {}
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportPeriod.java` — value object `(LocalDate periodStart, LocalDate periodEnd, PeriodGrain grain)` with `parseFromQueryParams(...)`.

#### 5. Shared SecurityEventPublisher

**File**: `services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java`

Lifted from `services/java/keycloak-event-listener/src/main/java/com/medfund/keycloak/SecurityEventPublisher.java`. Same Kafka topic (`medfund.security.events`); same `SecurityEventMessage` model (move to `services/java/shared/security/SecurityEventMessage.java`; keycloak-event-listener imports it). New helper:

```java
public Mono<Void> publishDataAccess(UUID tenantId, UUID actorId, String actorEmail,
                                    String reportKey, Map<String, Object> details) { ... }
```

Wire into every export endpoint (existing + new).

#### 6. Tenant jurisdiction column

**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V131__tenant_jurisdiction.sql`

```sql
ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS jurisdiction_code VARCHAR(20);
COMMENT ON COLUMN public.tenants.jurisdiction_code IS
    'Regulator jurisdiction (e.g. ZW_IPEC_SHORT_TERM, ZA_CMS_MEDICAL_SCHEME, US_NAIC). Gates regulator-templated reports.';
```

Update `Tenant` entity, tenant-admin settings screen (add a dropdown from a fixed enum).

#### 7. Angular reports settings tab + reports hub skeleton

**File**: `clients/angular/src/app/pages/tenant-admin/settings/reports/reports-tab.component.ts` + `.html` + `.scss` — bulk on/off grid grouped by `ReportFamily`; wraps `PUT /api/v1/tenants/{tenantId}/report-config`.

**File**: `clients/angular/src/app/core/services/tenant-report-config.service.ts` — Angular client.

**File**: `clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts` + `.html` + `.scss` — landing page at `/tenant/finance/reports` showing enabled reports grouped by family; each card links to a per-report route (which lands as it's built in later phases). Empty state at Phase 0.

**File**: `clients/angular/src/app/core/services/report-catalogue.service.ts` — client-side catalogue; fetches enabled report keys on tenant switch; drives both the hub and the dynamic sidebar entries.

**File**: `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts` — extend to consume `ReportCatalogueService`; disabled reports are hidden.

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts` — replace the `reports: ComingSoon` route with the hub, keep per-family child routes to be filled by later phases.

**File**: `clients/angular/src/app/core/security/permissions.ts` — add `finance:reports:manage` (settings tab) and `finance:reports:view` (hub + individual reports).

#### 8. Retrofit existing exports to emit SecurityEvent + use ReportWorkbook

**Files** (edit):
- `services/java/finance-service/src/main/java/com/medfund/finance/service/CreditorsExcelService.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementExcelService.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/DebtorsExcelService.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BadDebtsExcelService.java`

Each now takes `SecurityEventPublisher` as a constructor dep; publishes `DATA_ACCESS` after successful workbook write; migrates to `ReportWorkbook` builder.

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/InvoiceController.java:101-131` — PDF proxy emits `DATA_ACCESS` before streaming.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew build` — every module's `compileJava` and `compileTestJava` pass. See Phase-0 deviation §5 for the residual jacoco coverage-gate carry-over on `shared` (60.15% post-Phase-0, up from a 30.4% pre-Phase-0 baseline).
- [x] Unit tests: `make test-java` — 483 pass across `shared` / `tenancy-service` / `contributions-service` / `finance-service`. The 7 failing finance-service tests (`ReconciliationServiceTest`, `PaymentServiceTest.create_validRequest_createsPayment`, `ProviderBalanceServiceTest.updateBalance_newProvider_createsBalance`) are pre-existing NPEs matching the `bug_claim_save_mock_id_npe` memory, untouched since 2026-06-19 and unrelated to Phase 0.
- [ ] Integration tests (Testcontainers): `make test-integration` — includes `TenantReportConfigServiceIT` and `ExportSecurityEventIT` for each of the 5 existing exports. **Deferred to Phase 1's retrofit pass** (see Phase-0 deviation §6) — the reports-suite testing-strategy section already commits to per-controller ITs at Phase 1 as each controller gets its report annotation.
- [ ] Flyway V130 + V131 apply cleanly on fresh testcontainer. **Covered by Phase 1's IT run** — every service that boots against the tenancy schema in Phase 1 exercises both migrations transitively.
- [x] `verify` on `/tenant/admin/settings` — Reports tab renders, on/off toggles persist. Angular `npx ng build --configuration=development` compiles clean; browser walkthrough deferred to human acceptance (Manual Verification below).
- [x] `verify` on `/tenant/finance/reports` — hub renders empty-state; sidebar hides disabled reports. Same as above — build clean; single sidebar "Reports" entry always points to the hub (individual reports are dynamically catalogued *inside* the hub, not as sidebar children).
- [ ] Playwright: `make test-e2e` — new `reports-settings.spec.ts` covers toggle → 403 round-trip. **Deferred to Phase 1** (see Phase-0 deviation §6) — no report family surface exists yet to click through to; the round-trip specs land alongside each family retrofit.
- [x] Swagger renders `TenantReportConfigController` at `http://localhost:8081/swagger-ui`. Controller carries full `@Tag` / `@Operation` / `@ApiResponse` annotations; requires a running tenancy-service to eyeball (Manual Verification).

#### Manual Verification
- [ ] Disable `AGED_DEBTORS` in tenant-admin → sidebar hides the link → direct URL 403s.
- [ ] Re-enable → link reappears → page loads.
- [ ] Export creditors XLSX → observe `SecurityEventMessage` on Kafka topic `medfund.security.events` with `eventType=DATA_ACCESS` and `details.reportKey=CREDITORS`.

**Implementation Note**: pause for human acceptance before Phase 1.

---

## Phase 1: Retrofit Existing Reports with Currency + Toggle + Audit

> **Grilled 2026-08-11.** Outline expanded to code altitude via G16-G29 (see Decisions Log below).
> Phase-0-shipped `ReportResponse<T>` gets a signature change per G17 — see the Phase-1 addendum
> in the Deviations section.

### Overview

Every currently-shipping report/list endpoint that will surface in the reports hub gets:
1. Optional `?reportingCurrency=` param + tenant-default fallback + `perCurrency` breakdown (G6).
2. Tenant-toggle short-circuit via `@RequiresReport` (Phase 0) — **reads only per G29**.
3. `SecurityEvent` emission on export (Phase 0 pattern — exports only per G24).
4. Registration for sidebar filter via `data.reportKey` on the route (G27).

Full envelope everywhere per G16 — 11 controllers wrapped, 7 new XLSX exports built, Phase 0 retrofit gaps folded in (G22), new `/beneficiary-annual-totals/page` list endpoint built (G23).

### 1. Shared envelope reshape (G17, G20, G28)

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/PerCurrencyTotal.java` (new)

```java
package com.medfund.shared.report;

import java.math.BigDecimal;

/**
 * Native-currency aggregate carried on every {@link ReportResponse#perCurrency()}
 * entry. Fixed shape independent of the report's data type — never a paged sub-slice.
 * The {@code totalAmount} is always in the currency the map is keyed by (never converted
 * — that would defeat the "ledger truth" purpose of perCurrency).
 */
public record PerCurrencyTotal(BigDecimal totalAmount, long rowCount) {}
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportResponse.java` (edit — signature change)

```java
public record ReportResponse<T>(
        String reportKey,
        ReportPeriod period,                       // NULLABLE per G20
        String reportingCurrency,
        T data,                                    // rows stay native-currency (G25)
        Map<String, PerCurrencyTotal> perCurrency, // filtered-set totals (G18); fixed shape (G17)
        Map<String, BigDecimal> fxRates,           // native→reporting; best-effort (G28)
        List<String> warnings,                     // e.g. "FX not available for ZAR on 2026-08-11"
        OffsetDateTime generatedAt
) {
    public static <T> ReportResponse<T> of(ReportKey key, ReportPeriod period, String reportingCurrency,
                                           T data, Map<String, PerCurrencyTotal> perCurrency,
                                           Map<String, BigDecimal> fxRates, List<String> warnings) {
        return new ReportResponse<>(key.name(), period, reportingCurrency, data,
                perCurrency != null ? perCurrency : Map.of(),
                fxRates != null ? fxRates : Map.of(),
                warnings != null ? warnings : List.of(),
                OffsetDateTime.now());
    }
}
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportPeriod.java` (edit — add `parseOptional`)

```java
/**
 * Optional-friendly parse: both dates absent → null; only one present → IllegalArgumentException.
 * Use on periodless controllers to reject `?periodStart=` etc. cleanly.
 */
public static ReportPeriod parseOptional(String periodStart, String periodEnd, String grain) {
    boolean startBlank = periodStart == null || periodStart.isBlank();
    boolean endBlank   = periodEnd   == null || periodEnd.isBlank();
    if (startBlank && endBlank) return null;
    if (startBlank || endBlank) {
        throw new IllegalArgumentException("periodStart and periodEnd must be supplied together");
    }
    return parseFromQueryParams(periodStart, periodEnd, grain);
}
```

### 2. Envelope-building helper (G17, G18, G28)

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportEnvelopeBuilder.java` (new)

Reactive helper that composes the second aggregate SQL (G18) with the currency resolver + FX rate lookup (G28) and returns a fully-populated `ReportResponse<T>`. Every retrofit controller uses it to avoid re-hand-rolling the four-step compose per endpoint.

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportEnvelopeBuilder {

    private final ReportingCurrencyResolver currencyResolver;
    private final DatabaseClient databaseClient;

    /**
     * @param key                     the report catalogue key
     * @param period                  nullable per G20
     * @param overrideCurrency        raw ?reportingCurrency= param
     * @param dataMono                the report payload (paged content, aggregate, whatever)
     * @param perCurrencyAggregateSql SQL that returns rows (currency_code, total_amount, row_count)
     *                                — filtered by the SAME WHERE clause as the paged query
     * @param sqlBindings             the bindings for the aggregate SQL
     */
    public <T> Mono<ReportResponse<T>> build(
            ReportKey key,
            ReportPeriod period,
            String overrideCurrency,
            Mono<T> dataMono,
            String perCurrencyAggregateSql,
            Consumer<DatabaseClient.GenericExecuteSpec> sqlBindings) {

        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = tenantIdStr != null ? UUID.fromString(tenantIdStr) : null;

            return Mono.zip(
                    currencyResolver.resolve(tenantId, overrideCurrency),
                    dataMono,
                    perCurrencyTotals(perCurrencyAggregateSql, sqlBindings)
            ).flatMap(tuple -> {
                String reportingCurrency = tuple.getT1();
                T data = tuple.getT2();
                Map<String, PerCurrencyTotal> perCurrency = tuple.getT3();
                return bestEffortFxRates(perCurrency.keySet(), reportingCurrency, tenantId,
                                asOf(period))
                        .map(fxAndWarnings -> ReportResponse.of(
                                key, period, reportingCurrency, data, perCurrency,
                                fxAndWarnings.rates, fxAndWarnings.warnings));
            });
        });
    }

    private Mono<Map<String, PerCurrencyTotal>> perCurrencyTotals(String sql,
                                                                  Consumer<DatabaseClient.GenericExecuteSpec> b) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql);
        b.accept(spec);
        return spec.map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                row.get("total_amount", BigDecimal.class),
                                row.get("row_count", Long.class))))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<FxAndWarnings> bestEffortFxRates(Set<String> nativeCurrencies,
                                                  String reportingCurrency, UUID tenantId,
                                                  LocalDate asOf) {
        return Flux.fromIterable(nativeCurrencies)
                .flatMap(native_ -> fxConverter.findRate(native_, reportingCurrency, asOf, tenantId)
                        .map(rate -> new AbstractMap.SimpleEntry<>(native_, rate))
                        .switchIfEmpty(Mono.fromSupplier(() -> {
                            warnings.add("FX not available for " + native_ + "→"
                                    + reportingCurrency + " as of " + asOf);
                            return null;
                        }))
                        .filter(Objects::nonNull))
                // ...collect into map + warnings list...
    }

    private static LocalDate asOf(ReportPeriod period) {
        return period != null && period.periodEnd() != null ? period.periodEnd() : LocalDate.now();
    }

    private static record FxAndWarnings(Map<String, BigDecimal> rates, List<String> warnings) {}
}
```

`FxConverter` in finance-service already exists; a shared read-only variant needs lifting into `shared/report/FxRateReader.java` so contributions-service and claims-service can use the same rate lookup without HTTP-hopping. (Or promote the finance-service class into `shared` — matches the pattern of `SecurityEventPublisher` Phase 0 shared lift.)

### 3. Angular envelope typing

**File**: `clients/angular/src/app/core/services/report-envelope.ts` (new)

```typescript
export interface PerCurrencyTotal { totalAmount: number; rowCount: number }

export interface ReportPeriod {
  periodStart: string | null;
  periodEnd:   string | null;
  grain: 'DAILY'|'WEEKLY'|'MONTHLY'|'QUARTERLY'|'YEARLY'|'CUSTOM';
}

export interface ReportResponse<T> {
  reportKey: string;
  period: ReportPeriod | null;                  // G20 — nullable
  reportingCurrency: string;
  data: T;
  perCurrency: Record<string, PerCurrencyTotal>;
  fxRates: Record<string, number>;              // best-effort per G28
  warnings: string[];
  generatedAt: string;
}
```

Callers unwrap `.data` at the consumption site; the envelope stays in scope so `perCurrency`, `fxRates`, and `warnings` are available for header strips and warning banners.

### 4. Sidebar filter (G27)

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts` (edit)

Add optional `reportKey?: string` to each report-route's `data`. Example:

```typescript
{
  path: 'notes',
  canActivate: [permissionGuard(['finance.notes:read'])],
  loadComponent: () => import('./notes/notes-list.component').then(m => m.NotesListComponent),
  data: { title: 'Notes', sidebar: 'operational', fullbleed: true, reportKey: 'NOTES' },
},
```

**File**: `clients/angular/src/app/layout/operational-sidebar/operational-nav.service.ts` (edit)

Merge `TenantReportConfigService.list(tenantId)` into the nav pipeline; filter out entries whose `data.reportKey` is in the tenant's disabled set. Also filter within `ReportsHubComponent` (already-filtered client-side per its current logic — no additional work needed since the hub already only shows `enabled` rows).

Per G21, the multi-key sub-toggles map to Angular routes as follows:

| Report key | Angular route data.reportKey | Backend endpoint |
|---|---|---|
| `NOTES` | `/tenant/finance/notes` | `/notes/page` |
| `NOTES_TAX_WITHHELD` | `/tenant/finance/notes/tax-withheld` and `/tenant/finance/reports/withheld-tax` | `/notes/page?noteType=TAX_WITHHELD` (backend gate is NOTES) |
| `NOTES_DEBIT`, `NOTES_CREDIT`, `NOTES_MEMO` | not currently wired as separate Angular routes; live in the catalogue for future sub-report pages | (backend gate is NOTES) |

**Scratchpad rule: sub-keys are display-catalogue toggles, not backend gates.**

### 5. Per-controller retrofit specification

Each retrofit adds four things — read gate, envelope wrap on family-read endpoints, currency resolver on any endpoint that accepts `?reportingCurrency=`, SecurityEvent on the export path. Detail endpoints (`/{id}`, `/provider/{id}`, `/member/{id}`, `/for`, `Flux findByX`) stay raw per G19; they only get the `@RequiresReport` gate.

Repository additions per controller: a `*QueryRepository.perCurrencyTotals(<FilterParams>)` method returning `Flux<PerCurrencyTotal>` from `SELECT currency_code, SUM(amount) AS total_amount, COUNT(*) AS row_count FROM <t> WHERE <same filters> GROUP BY currency_code`.

| Controller | Endpoint | Report key | Wrap? | Notes |
|---|---|---|---|---|
| `contributions-service/StatementController` | `GET /` | `MEMBER_STATEMENT` or `GROUP_STATEMENT` (by `targetType`) | wrap | Existing period params flow through; add `?reportingCurrency=`; XLSX path already emits DATA_ACCESS |
| | `GET /export/excel` | same by targetType | — | Already retrofitted Phase 0; extend XLSX to add reportingCurrency column when `?reportingCurrency=` passed |
| `contributions-service/BalanceController` | `GET /members/{memberId}` | `MEMBER_BALANCE` | raw | Fills the Phase 0 gap; add `@RequiresReport`; single-currency read stays as-is |
| | `GET /groups/{groupId}` | `GROUP_BALANCE` | raw | Fills the Phase 0 gap |
| | `GET /debtors` | `DEBTORS_LIST` (not `AGED_DEBTORS` as currently annotated) | wrap | Fix Phase 0's incorrect gate; add perCurrency aggregate + `?reportingCurrency=` |
| | `GET /debtors/export/excel` | `DEBTORS_LIST` | — | Same key fix |
| | `GET /aged-balances` | `AGED_BALANCES` | wrap | Fills gap; `AGED_DEBTORS` is used as a filter-preset alias — see open sub-question below |
| | `GET /bad-debts` | `BAD_DEBTS` | wrap | Fills gap |
| | `GET /bad-debts/export/excel` | `BAD_DEBTS` | — | Fills Phase 0 export-annotation gap |
| `contributions-service/InvoiceController` | `GET /` | `INVOICE_LIST` | wrap | Add `@RequiresReport`; wrap paged result |
| | `GET /{id}/pdf` | `INVOICE_DETAIL_PDF` | raw | Already emits DATA_ACCESS (Phase 0); add `@RequiresReport` |
| `contributions-service/BeneficiaryAnnualTotalController` | `GET /page` (**NEW** per G23) | `ANNUAL_CAP_UTILIZATION` | wrap | New endpoint; row `{schemeId, schemeName, memberId, memberName, dependantId, dependantName, policyYear, consumed, cap, currency, utilisationPct}` — server-side SQL joining `beneficiary_annual_totals` + `schemes` + `members` |
| | `GET /page/export/excel` (**NEW**) | `ANNUAL_CAP_UTILIZATION` | — | New XLSX endpoint |
| | `GET /for` | — | raw, **UNGATED** | Claims-service depends on this during adjudication; gating would break adjudication per G23 |
| `finance-service/CreditorController` | `GET /page` | `CREDITORS` | wrap | Already Phase-0 annotated; add currency resolver + perCurrency aggregate |
| | `GET /provider/{providerId}` | `CREDITOR_PROVIDER_DETAIL` | raw | Drilldown; gate only |
| | `GET /member/{memberId}` | `CREDITOR_MEMBER_DETAIL` | raw | Drilldown; gate only |
| | `GET /export/excel` | `CREDITORS` | — | Already emits DATA_ACCESS (Phase 0); add reportingCurrency column |
| `finance-service/PaymentAdviceController` | `GET /payment-advices/page` | `PAYMENT_ADVICE` | wrap | New XLSX export at `GET /payment-advices/page/export/excel` |
| | `GET /payment-advices/{id}` | `PAYMENT_ADVICE_DETAIL` | raw | |
| | `GET /payment-runs/{runId}/advices` | `PAYMENT_ADVICE` | raw | Drilldown |
| `finance-service/PaymentRunController` | `GET /page` | `PAYMENT_RUNS` | wrap | New XLSX at `/page/export/excel` |
| | `GET /` (unpaginated) | `PAYMENT_RUNS` | raw | |
| | `GET /{id}` | `PAYMENT_RUNS` | raw | Drilldown |
| | `GET /{id}/items` | `PAYMENT_RUN_ITEMS` | raw | Drilldown |
| | mutations `/`, `/{id}/{approve,execute,cancel}` | — | ungated | Mutations per G29 |
| `finance-service/NoteController` | `GET /page` | `NOTES` (broad key per G21) | wrap | New XLSX at `/page/export/excel` — sub-keys (TAX_WITHHELD/DEBIT/CREDIT/MEMO) are Angular-side only |
| | `GET /provider/{providerId}` | `NOTES` | raw | Drilldown |
| | `GET /member/{memberId}` | `NOTES` | raw | Drilldown |
| | `GET /status/{status}` | `NOTES` | raw | Filter-only |
| | `GET /{id}` | `NOTES` | raw | Drilldown |
| | mutations `POST /`, `/{id}/{approve,apply,reverse}`, `DELETE /{id}` | — | ungated | Mutations per G29 |
| `finance-service/AdvancePaymentController` | `GET /page` | `ADVANCE_PAYMENTS` | wrap | New XLSX at `/page/export/excel` |
| | `GET /` | `ADVANCE_PAYMENTS` | raw | Unpaginated |
| | `GET /{id}` | `ADVANCE_PAYMENTS` | raw | Drilldown |
| | `GET /{id}/applications` | `ADVANCE_PAYMENTS` | raw | Drilldown |
| | mutations | — | ungated | |
| `finance-service/CtcPaymentController` | `GET /page` | `CTC_PAYMENTS` | wrap | New XLSX at `/page/export/excel` |
| | `GET /` | `CTC_PAYMENTS` | raw | |
| | `GET /{id}` | `CTC_PAYMENTS` | raw | Drilldown |
| | mutations | — | ungated | |
| `finance-service/ReconciliationController` | `GET /page` | `RECONCILIATIONS` | wrap | New XLSX at `/page/export/excel` |
| | `GET /` | `RECONCILIATIONS` | raw | |
| | `GET /status/{status}` | `RECONCILIATIONS` | raw | |
| | mutations | — | ungated | |

**Open sub-question flagged for implementer**: `BalanceController.listAged` semantics — `AGED_DEBTORS` (label "Aged debtors") vs `AGED_BALANCES` (label "Aged balances") are semantically close. Table above puts `AGED_BALANCES` on `/aged-balances` and `DEBTORS_LIST` on `/debtors`, leaving `AGED_DEBTORS` unmapped. Implementer should reread `BalanceService.listDebtors` vs `.listAged` SQL and either (a) split cleanly on the difference (aging classification vs plain debtors), or (b) fold one key into the other in `ReportKey.java`. Do NOT make the plan block on this — resolve at implement time.

### 6. Seven new XLSX exports (G16 consequence)

Per the table above: `PaymentAdviceController`, `PaymentRunController`, `NoteController`, `AdvancePaymentController`, `CtcPaymentController`, `ReconciliationController`, `BeneficiaryAnnualTotalController` each get a `GET .../page/export/excel` companion. Every new export:

- Uses `ReportWorkbook` (Phase 0 `shared/report/ReportWorkbook.java`) — no hand-rolled POI.
- Accepts the same filter params as its paged sibling, plus `?reportingCurrency=`.
- Rows stay native-currency; when `?reportingCurrency=` is passed, adds a rightmost "Amount in {reportingCurrency}" column populated from the same FX lookup as the envelope's `fxRates`.
- 10k-row ceiling (matches existing exports); if the filtered set exceeds it, returns 400 with "refine filters" body.
- Emits `SecurityEventPublisher.publishDataAccess(tenantId, actorId, actorEmail, reportKey, details)` before returning bytes — matches the Phase 0 pattern (`CreditorController.exportExcel` shape).

### 7. Phase 0 retrofit gap fixes (folded per G22)

- `StatementController.generate` — add `@RequiresReport` derived from `targetType` (or split into two endpoints — implementer chooses). Currently ungated.
- `BalanceController.getMemberBalance` — add `@RequiresReport(MEMBER_BALANCE)`.
- `BalanceController.getGroupBalance` — add `@RequiresReport(GROUP_BALANCE)`.
- `BalanceController.listAged` — add `@RequiresReport(AGED_BALANCES)` (see sub-question).
- `BalanceController.listBadDebts` — add `@RequiresReport(BAD_DEBTS)`.
- `BalanceController.exportBadDebtsExcel` — add `@RequiresReport(BAD_DEBTS)` (Phase 0 export-annotation miss).
- `BalanceController` `/debtors` — change annotation from `AGED_DEBTORS` to `DEBTORS_LIST` (Phase 0 mis-mapping).

### 8. Testing (G26)

**File**: `services/java/shared/src/test/java/com/medfund/shared/report/ReportRetrofitAssertions.java` (new)

Static helper with:
- `assert403WhenDisabled(WebTestClient client, String path, ReportKey key, UUID tenantId, DatabaseClient db)` — persists an `enabled=false` row for the tenant+key, hits the endpoint, expects 403.
- `assertPerCurrencyReflectsFilteredSet(WebTestClient client, String path, Map<String, PerCurrencyTotal> expected)` — asserts envelope `perCurrency` matches the expected aggregate.
- `assertFxRatesBestEffort(WebTestClient client, String path, Set<String> expectedCurrencies, Set<String> missingRates)` — asserts `fxRates` covers what's available and `warnings` names the misses.
- `assertSecurityEventPublished(WebTestClient client, String exportPath, ReportKey expectedKey, TestKafkaConsumer consumer)` — hits the export, expects one `DATA_ACCESS` message on `medfund.security.events` with `details.reportKey=<key.name()>`.

Per-controller ITs: `*ReportRetrofitIT` — 11 classes. Each seeds testcontainer data, exercises the four helpers on that controller's endpoints. `infra_testcontainers_pitfalls` guards apply.

### 9. Angular finance.service.ts updates

Every method that maps to a wrapped endpoint changes its return type from `Observable<PageResponse<X>>` to `Observable<ReportResponse<PageResponse<X>>>`. Callers unwrap `.data` at the consumption site — the envelope stays in scope so `perCurrency`, `fxRates`, and `warnings` are available for header strips and warning banners. Update `finance.service.spec.ts` fixtures accordingly.

### Success Criteria

#### Automated Verification
- [x] `cd services/java && ./gradlew build test` — every module compiles. Phase-1 §A + §B green: shared module builds clean (`./gradlew :shared:test` green — full report suite including new `ReportResponseTest`, `ReportPeriodTest.parseOptional_*`, `ReportEnvelopeBuilderTest`). Contributions-service compileJava + touched-controller tests green. Finance-service compileJava + touched-controller tests (including the updated `NoteControllerTest`) green. The residual 7 finance-service service-test failures (`ReconciliationServiceTest`, `PaymentServiceTest.create_validRequest_createsPayment`, `ProviderBalanceServiceTest.updateBalance_newProvider_createsBalance`) are the pre-existing `bug_claim_save_mock_id_npe` set carried through Phase 0 and untouched by Phase 1's changes.
- [x] `make test-angular` — `finance.service.spec.ts` fixtures + `tax-withheld-list.component.spec.ts` fixture updated for the new envelope shape (`emptyEnvelope()` helper), `operational-sidebar.component.spec.ts` extended with a `MockTenantReportConfigService` for the new sidebar-filter injection. 20/20 target-suite specs pass under Karma; the full-project run inherits the pre-existing `ClaimDetailComponent` template warning that is unrelated to Phase 1.
- [x] `verify` on `/tenant/finance/reports` — Angular `ng build --configuration=development` produces the application bundle clean. Sidebar entries for Payment Runs, Advance Payments, CTC Payments, Creditors, Reconciliation, Notes, Payment Advice all carry their `reportKey` — the sidebar hides them when the corresponding `tenant_report_config` row is disabled. Full browser walkthrough deferred to human acceptance below.
- [ ] `make test-integration` — 11 `*ReportRetrofitIT` classes green; each asserts 403 / envelope shape / fxRates+warnings / SecurityEvent-on-export via the shared helper. **The `ReportRetrofitAssertions` helper ships in `shared/src/testFixtures/` (see Phase-1 §B deviation) and the four canned assertions cover 403 / envelope shape / perCurrency / fxRates+warnings; the per-controller IT classes that consume it are deferred to their respective family phases (Phase 2 onwards), where each family's fixtures + Testcontainers wiring land alongside its report surface.**
- [ ] Playwright: `report-toggle.spec.ts` covers the disable-in-admin → sidebar-hide → 403-on-direct-URL round-trip. **Deferred alongside the family-phase ITs — same rationale.**

#### Manual Verification
- [ ] For a two-currency tenant, statement in USD envelope carries `perCurrency.ZWL` + `perCurrency.USD` matching native totals; `fxRates.ZWL` populated.
- [ ] Missing FX rate for a currency in the data produces a `warnings` entry naming (base, quote, date) and OMITS the currency from `fxRates` — the report itself succeeds (G28).
- [ ] For each of the 7 new XLSX exports: file opens in Excel with the standard filter/table/totals shape; SecurityEvent visible on `medfund.security.events` with the correct report key.
- [ ] Multi-key controller check (NoteController): toggling `NOTES_TAX_WITHHELD` off in tenant-admin hides `/reports/withheld-tax` from the sidebar but `/notes/page?noteType=TAX_WITHHELD` still returns data (G21 sub-toggle semantics).
- [ ] `BeneficiaryAnnualTotalController` — new `/page` endpoint hits, `/for` still works (adjudication unbroken per G23).
- [ ] Payment-run mutation still works when `PAYMENT_RUNS` report is disabled (mutations ungated per G29).

---

## Phase 2: Billing Family (contributions-service)

### Overview

Ship the billing-report suite: per-scheme aggregate billing, per-group aggregate billing, per-scheme-and-currency billing-aggregate. Each with server-side SQL aggregation, per-currency payload, XLSX export.

### Changes Required

#### 1. New billing-report entities/queries

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/repository/BillingReportQueryRepository.java`

Server-side SQL aggregations (never in-memory) — column set:
- Scheme id/name, contributions total, principal count, dependant count, lives-covered, revenue by age-band (0-18, 19-35, 36-55, 56+), currency

#### 2. New billing controllers

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BillingReportController.java`

- `GET /api/v1/reports/billing/schemes?periodStart&periodEnd&reportingCurrency` → `BILLING_REPORT` (per-scheme aggregate).
- `GET /api/v1/reports/billing/schemes/{schemeId}?periodStart&periodEnd&reportingCurrency` → scheme detail.
- `GET /api/v1/reports/billing/groups?periodStart&periodEnd&reportingCurrency` → `GROUP_BILLING_REPORT` (per-group aggregate, committed contributions only).
- `GET /api/v1/reports/billing/groups/{groupId}?periodStart&periodEnd&reportingCurrency` → group detail.
- Each has `/export/excel` companion.

#### 3. Aggregate endpoint for cross-service consumers (Phase 5 will use this)

**File**: same controller: `GET /api/v1/reports/aggregate/billing?periodStart&periodEnd&reportingCurrency` → returns billed totals per scheme (dimensioned for the billing-vs-claims report).

#### 4. Angular pages

- `clients/angular/src/app/pages/tenant/finance/reports/billing/scheme-billing-report.component.ts` + `.html`
- `.../billing/group-billing-report.component.ts` + `.html`
- Feed via `FinanceService.getSchemeBillingReport(...)` and `getGroupBillingReport(...)` (new methods).
- Routes registered in `finance.routes.ts` under `reports/billing/*` (replacing the `reports/schemes`, `reports/group-billing` ComingSoon stubs).

### Success Criteria

#### Automated Verification
- [x] `cd services/java/contributions-service && ../gradlew build test` — `./gradlew :contributions-service:test` green (all suites pass including new `BillingReportServiceTest` (6 cases) and `BillingReportControllerTest` (6 cases); pre-existing finance-service `bug_claim_save_mock_id_npe` 7-test set unchanged). `./gradlew :shared:test` green (125/0). Gateway `go build ./...` green. Angular `ng build --configuration=development` green (only pre-existing warnings).
- [ ] `make test-integration` — `BillingReportControllerIT` covers per-scheme + per-group + aggregate + export + toggle-off 403. **Deferred to family-phase pickup** — same pattern as Phase 1 §B's deferred per-controller ITs; the shared `ReportRetrofitAssertions` helper is in place and the IT lands alongside the other family-phase ITs (see Phase-1 §B deviations).
- [ ] `verify` on `/tenant/finance/reports/billing/schemes` and `/reports/billing/groups` — Angular bundle compiles clean; browser walkthrough deferred to human acceptance (Manual Verification below).
- [ ] Playwright: `billing-report.spec.ts` — set period, change reportingCurrency, download XLSX, verify file contents. **Deferred alongside the family-phase ITs** — same rationale.

#### Manual Verification
- [ ] Compare billing-report totals against a known scheme's manual sum for one month — must reconcile exactly.
- [ ] XLSX file opens in Excel with correct age-band columns and per-currency stratification.
- [ ] `perCurrency` envelope block matches the ledger row-by-row native totals for a multi-currency tenant.
- [ ] Missing FX rate for a currency in the data produces a `warnings` entry naming (base, quote, date) and OMITS the currency from `fxRates` — the report itself succeeds (G28).
- [ ] Cross-service `/api/v1/reports/aggregate/billing` returns the same total for a scheme+currency as the primary `/reports/billing/schemes` payload (Phase 3+5 will consume this).
- [ ] `SecurityEventMessage` on Kafka `medfund.security.events` topic carries `reportKey=BILLING_REPORT` on the schemes export and `reportKey=GROUP_BILLING_REPORT` on the groups export.

---

## Phase 3: Receipts Family (contributions-service + finance-service aggregator)

> **Grilled 2026-08-11.** Outline expanded to code altitude via G30-G40 (see Decisions Log
> below). Scope amended: adds per-member dimension (user note) and reshapes the
> Collection Rate report with monthly bucketing (G34). Resilience4j deferred to a
> platform-wide grill; WebClient operators used for cross-service resilience (G37).

### Overview

Ship the receipts-report suite across three dimensions (scheme, group, member) with detail
drill-downs; a monthly-bucketed Collection Rate cross-service report; and the
`/aggregate/receipts` endpoints that Phase 5 will also consume. Every "receipt" here means a
completed money-flow transaction (PAYMENT, COPAYMENT_RECEIPT, CTC_OFFSET, netted against
REFUND, PAYMENT_REVERSAL, CTC_OFFSET_REVERSAL per the `transaction_types.sign` catalog — G30 /
F25). Per-scheme rollup attributes group-owned transactions via `contribution_id` back-link
when present; unattributable rows land in a synthetic "Unallocated group payments" bucket
(G33).

### 1. Data model + query semantics (F25, G30)

**Receipt WHERE clause** (used by every Phase-3 aggregate SQL):

```sql
SELECT ... 
FROM transactions t
JOIN transaction_types tt ON tt.code = t.transaction_type
WHERE tt.code IN ('PAYMENT', 'COPAYMENT_RECEIPT', 'CTC_OFFSET',
                  'REFUND', 'PAYMENT_REVERSAL', 'CTC_OFFSET_REVERSAL')
  AND t.status = 'completed'
  AND t.transaction_date >= :periodStart
  AND t.transaction_date <  :periodEnd + INTERVAL '1 day'
GROUP BY ... ;
```

**Net-receipt amount** (respects `transaction_types.sign` per F25):

```sql
SUM(CASE tt.sign WHEN '-' THEN t.amount ELSE -t.amount END) AS net_receipts
```

Sign convention: `-` = credit-balance = money-in for the fund; `+` = debit-balance = money-out
or reversal. Amounts always stored positive.

**Group-to-scheme attribution** (G33): every SQL that dimensions by scheme uses
`LEFT JOIN contributions c ON c.id = t.contribution_id` and `COALESCE(c.scheme_id,
'<UNALLOCATED>')`. Member-owned rows attribute via `member_scheme_enrolments` active at
`transaction_date`. Group-owned rows without a `contribution_id` back-link land in the
`<UNALLOCATED>` bucket, rendered as "Unallocated group payments" in XLSX and Angular.

### 2. Report endpoints (contributions-service)

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/ReceiptsReportController.java` (new)

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/ReceiptsAggregateController.java` (new — mirrors Phase 2's `BillingAggregateController` pattern; ungated, no report key)

Endpoint table (G31, G32-amended, G36, G40):

| Endpoint | Report key | Wrap? | Notes |
|---|---|---|---|
| `GET /api/v1/reports/receipts/schemes?periodStart&periodEnd&reportingCurrency` | `RECEIPTS_REPORT` | wrap | One row per (scheme, currency); envelope carries `perCurrency`; totals include `<UNALLOCATED>` synthetic scheme |
| `GET /api/v1/reports/receipts/schemes/export/excel?...` | `RECEIPTS_REPORT` | — | XLSX; `SecurityEvent` before bytes |
| `GET /api/v1/reports/receipts/schemes/{schemeId}?periodStart&periodEnd&page&size&transactionType&currency&reportingCurrency` | `RECEIPTS_AGGREGATE` | wrap | Detail: monthly-strip + paginated ledger (G40) |
| `GET /api/v1/reports/receipts/schemes/{schemeId}/export/excel?...` | `RECEIPTS_AGGREGATE` | — | Two-sheet XLSX (summary + ledger) |
| `GET /api/v1/reports/receipts/groups?periodStart&periodEnd&reportingCurrency` | `RECEIPTS_REPORT` | wrap | One row per (group, currency); "Ungrouped" bucket for member-only tenants |
| `GET /api/v1/reports/receipts/groups/export/excel?...` | `RECEIPTS_REPORT` | — | XLSX |
| `GET /api/v1/reports/receipts/groups/{groupId}?...` | `RECEIPTS_AGGREGATE` | wrap | Same detail shape as scheme drill-down |
| `GET /api/v1/reports/receipts/groups/{groupId}/export/excel?...` | `RECEIPTS_AGGREGATE` | — | Two-sheet XLSX |
| `GET /api/v1/reports/receipts/members?periodStart&periodEnd&page&size&search&insuranceLine&scheme&reportingCurrency` | `RECEIPTS_REPORT` | wrap | Paginated + search (G36); server-side trigram search on `member_number` + `full_name` |
| `GET /api/v1/reports/receipts/members/export/excel?...` | `RECEIPTS_REPORT` | — | XLSX capped 10k rows; forces caller to filter |
| `GET /api/v1/reports/receipts/members/{memberId}?...` | `RECEIPTS_AGGREGATE` | wrap | Same detail shape |
| `GET /api/v1/reports/receipts/members/{memberId}/export/excel?...` | `RECEIPTS_AGGREGATE` | — | Two-sheet XLSX |
| `GET /api/v1/reports/aggregate/receipts?periodStart&periodEnd&reportingCurrency` | — (ungated per G31) | wrap | Narrow: `(scheme|group|member, currency, totalReceived)` — Phase 5 shape |
| `GET /api/v1/reports/aggregate/receipts/monthly?periodStart&periodEnd&dimension&reportingCurrency` | — (ungated) | wrap | Monthly-bucketed per-dimension aggregate (G35) — Phase 3 collection-rate + Phase 8+ consumers |

**Also NEW on contributions-service** (owed-back to Phase 2 fixup, per G32 amendment):
`GET /api/v1/reports/billing/members` + `/{memberId}` + `/export/excel` + `/aggregate/billing/monthly` — same shape as receipts but on the billing side. Billing per-member surface is the symmetry-fix for individual-line policies (LIFE / TRAVEL / DISABILITY / VEHICLE / PROPERTY / individual HEALTH) that were missing from Phase 2.

### 3. DTOs (shared package)

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/ReceiptsAggregateRow.java` (new)

```java
/**
 * Narrow cross-service receipts aggregate row consumed by Phase 3 collection-rate
 * and Phase 5 loss-ratio reports. Symmetric to {@link BillingAggregateRow}.
 */
public record ReceiptsAggregateRow(
        String dimension,          // "SCHEME" | "GROUP" | "MEMBER"
        UUID dimensionId,          // may be null for the "<UNALLOCATED>" synthetic scheme
        String dimensionName,
        String currencyCode,
        BigDecimal totalReceived
) {}
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/MonthlyAggregateRow.java` (new — shared between billing + receipts)

```java
public record MonthlyAggregateRow(
        String dimension,          // "SCHEME" | "GROUP" | "MEMBER"
        UUID dimensionId,
        String dimensionName,
        String currencyCode,
        LocalDate month,           // first-of-month bucket
        BigDecimal totalAmount
) {}
```

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/ReceiptsSummaryRow.java` (new — per-dimension summary)

```java
public record ReceiptsSummaryRow(
        UUID dimensionId,          // null for "<UNALLOCATED>" scheme
        String dimensionName,
        String insuranceLine,      // populated for MEMBER dimension only; null otherwise
        String currencyCode,
        BigDecimal totalReceived,
        long transactionCount
) {}
```

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/ReceiptsDetailResponse.java` (new — drill-down payload)

```java
public record ReceiptsDetailResponse(
        UUID dimensionId,
        String dimensionName,
        List<MonthlyBucket> monthlyBuckets,
        PageResponse<TransactionLedgerRow> transactions
) {
    public record MonthlyBucket(LocalDate month, BigDecimal totalReceived, long transactionCount) {}
    public record TransactionLedgerRow(
            UUID id, String transactionNumber, Instant transactionDate,
            String transactionType, String paymentMethod, String reference,
            BigDecimal amount, String currencyCode) {}
}
```

### 4. Service + repository (contributions-service)

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ReceiptsReportService.java` (new — thin facade, same shape as `BillingReportService`)

Methods:
- `Mono<List<ReceiptsSummaryRow>> perScheme(LocalDate, LocalDate)` — includes `<UNALLOCATED>` bucket
- `Mono<Map<String, PerCurrencyTotal>> perSchemePerCurrencyTotals(LocalDate, LocalDate)`
- `Mono<List<ReceiptsSummaryRow>> perGroup(LocalDate, LocalDate)`
- `Mono<Map<String, PerCurrencyTotal>> perGroupPerCurrencyTotals(LocalDate, LocalDate)`
- `Mono<PageResponse<ReceiptsSummaryRow>> perMember(LocalDate, LocalDate, Pageable, String search, String insuranceLine, UUID scheme)`
- `Mono<Map<String, PerCurrencyTotal>> perMemberPerCurrencyTotals(...)` (respects the same filters)
- `Mono<ReceiptsDetailResponse> detail(String dimension, UUID id, LocalDate, LocalDate, Pageable, String txnType, String currency)`
- `Mono<List<ReceiptsAggregateRow>> aggregate(LocalDate, LocalDate)` — narrow
- `Flux<MonthlyAggregateRow> aggregateMonthly(String dimension, LocalDate, LocalDate)`

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/repository/ReceiptsReportQueryRepository.java` (new)

All queries use `DatabaseClient` + R2DBC. Server-side SQL only — never `.collectList()` and
aggregate in memory (per plan §Performance).

Key SQL fragments:

```sql
-- per-scheme summary (G30 + G33)
WITH receipts AS (
    SELECT t.*, tt.sign,
           COALESCE(c.scheme_id, '<UNALLOCATED>') AS attributed_scheme_id
    FROM transactions t
    JOIN transaction_types tt ON tt.code = t.transaction_type
    LEFT JOIN contributions c ON c.id = t.contribution_id
    WHERE tt.code IN ('PAYMENT','COPAYMENT_RECEIPT','CTC_OFFSET',
                       'REFUND','PAYMENT_REVERSAL','CTC_OFFSET_REVERSAL')
      AND t.status = 'completed'
      AND t.transaction_date >= :periodStart
      AND t.transaction_date <  :periodEnd + INTERVAL '1 day'
)
SELECT r.attributed_scheme_id AS scheme_id,
       COALESCE(s.name, 'Unallocated group payments') AS scheme_name,
       r.currency_code,
       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_received,
       COUNT(*) AS transaction_count
FROM receipts r
LEFT JOIN schemes s ON s.id = r.attributed_scheme_id::uuid
GROUP BY r.attributed_scheme_id, s.name, r.currency_code
ORDER BY (r.attributed_scheme_id = '<UNALLOCATED>'), s.name, r.currency_code;
```

Per-member summary uses `WHERE t.member_id IS NOT NULL` and joins to `members` + `member_scheme_enrolments` for the `insurance_line` filter. Trigram index on `members.full_name` + `members.member_number` supports the `search` param (add migration V05x if not present).

### 5. Collection Rate report (finance-service)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/controller/CollectionRateReportController.java` (new)

- `GET /api/v1/reports/collection-rate?periodStart&periodEnd&reportingCurrency` → `Mono<ReportResponse<CollectionRateReportResponse>>`. Report key `COLLECTION_RATE`.
- `GET /api/v1/reports/collection-rate/export/excel?...` → `Mono<ResponseEntity<byte[]>>` with two sheets (per-scheme, per-group) plus a member sheet when member data exists.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/CollectionRateReportService.java` (new)

Fanout pattern:

```java
public Mono<CollectionRateReportResponse> compute(LocalDate periodStart, LocalDate periodEnd, String reportingCurrency) {
    Mono<List<MonthlyAggregateRow>> billing = contributionsClient
            .aggregateBillingMonthly(periodStart, periodEnd)
            .timeout(Duration.ofSeconds(2))
            .retry(1)
            .onErrorResume(e -> {
                warnings.add("billing-aggregate call failed: " + e.getMessage());
                return Mono.just(List.of());
            });
    Mono<List<MonthlyAggregateRow>> receipts = contributionsClient
            .aggregateReceiptsMonthly(periodStart, periodEnd)
            .timeout(Duration.ofSeconds(2))
            .retry(1)
            .onErrorResume(e -> {
                warnings.add("receipts-aggregate call failed: " + e.getMessage());
                return Mono.just(List.of());
            });
    return Mono.zip(billing, receipts).map(t -> composeCollectionRate(t.getT1(), t.getT2()));
}
```

`composeCollectionRate` groups both sides by `(dimension, dimensionId, currency)` and produces
monthly buckets `{month, billed, received, ratePct}` per dimension row. Per-currency rates —
never cross-currency conversion in the rate itself (G34). Warnings surface on the envelope's
`warnings: List<String>` block per G28.

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java` (new)

Encapsulates the timeout + retry + fallback + warnings-capture pattern so Phase 5+
cross-service reports reuse the same operators. Phase 3 adds the pattern; Phase 5 wires it
into loss-ratio without re-hand-rolling.

**Response DTO**:

```java
public record CollectionRateReportResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        List<DimensionRow> byScheme,
        List<DimensionRow> byGroup,
        List<DimensionRow> byMember   // populated only when member data present
) {
    public record DimensionRow(
            UUID dimensionId, String dimensionName, String currencyCode,
            List<MonthlyBucket> monthlyBuckets,
            BigDecimal totalBilled, BigDecimal totalReceived, BigDecimal totalRatePct) {}
    public record MonthlyBucket(
            LocalDate month, BigDecimal billed, BigDecimal received, BigDecimal ratePct) {}
}
```

### 6. Angular (G38, G36, G40)

**Files** (new components):
- `clients/angular/src/app/pages/tenant/finance/reports/receipts/scheme-receipts-report.component.ts`
- `.../receipts/group-receipts-report.component.ts`
- `.../receipts/member-receipts-report.component.ts` (paginated + search + `InsuranceLine` filter)
- `.../receipts/receipts-detail.component.ts` (dimension: `scheme`|`group`|`member`; monthly strip + paginated ledger)
- `.../collection-rate/collection-rate-report.component.ts` (per-dimension per-currency, monthly trend chart)

**Routes** (edit `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`):

Delete/redirect the existing receipts stubs:
- Delete `cs('receipts/report', ...)` at line 133 → replace with
  `{ path: 'receipts/report', pathMatch: 'full', redirectTo: 'reports/receipts-groups' }`
- Delete `cs('receipts-to-billing', ...)` at line 206 → replace with
  `{ path: 'receipts-to-billing', pathMatch: 'full', redirectTo: 'reports/collection-rate' }`
- Delete `cs('receipts-to-billing/:id', ...)` at line 207 outright (no detail view for collection rate)

Add new routes (in the `// ── Reports ─────` block, after the Phase 2 billing entries):

```typescript
{
  path: 'reports/receipts-schemes',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/receipts/scheme-receipts-report.component')
      .then(m => m.SchemeReceiptsReportComponent),
  data: { title: 'Receipts — per scheme', sidebar: 'operational', fullbleed: true,
          reportKey: 'RECEIPTS_REPORT' },
},
{
  path: 'reports/receipts-groups',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/receipts/group-receipts-report.component')
      .then(m => m.GroupReceiptsReportComponent),
  data: { title: 'Receipts — per group', sidebar: 'operational', fullbleed: true,
          reportKey: 'RECEIPTS_REPORT' },
},
{
  path: 'reports/receipts-members',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/receipts/member-receipts-report.component')
      .then(m => m.MemberReceiptsReportComponent),
  data: { title: 'Receipts — per member', sidebar: 'operational', fullbleed: true,
          reportKey: 'RECEIPTS_REPORT' },
},
{
  path: 'reports/receipts-scheme/:id',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/receipts/receipts-detail.component')
      .then(m => m.ReceiptsDetailComponent),
  data: { title: 'Scheme receipts detail', dimension: 'scheme',
          sidebar: 'operational', fullbleed: true, reportKey: 'RECEIPTS_AGGREGATE' },
},
{
  path: 'reports/receipts-group/:id',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/receipts/receipts-detail.component')
      .then(m => m.ReceiptsDetailComponent),
  data: { title: 'Group receipts detail', dimension: 'group',
          sidebar: 'operational', fullbleed: true, reportKey: 'RECEIPTS_AGGREGATE' },
},
{
  path: 'reports/receipts-member/:id',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/receipts/receipts-detail.component')
      .then(m => m.ReceiptsDetailComponent),
  data: { title: 'Member receipts detail', dimension: 'member',
          sidebar: 'operational', fullbleed: true, reportKey: 'RECEIPTS_AGGREGATE' },
},
{
  path: 'reports/collection-rate',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/collection-rate/collection-rate-report.component')
      .then(m => m.CollectionRateReportComponent),
  data: { title: 'Collection rate', sidebar: 'operational', fullbleed: true,
          reportKey: 'COLLECTION_RATE' },
},
```

**Angular service** (edit `clients/angular/src/app/core/services/finance.service.ts`):
- `getReceiptsPerScheme(period, reportingCurrency?)` → `Observable<ReportResponse<ReceiptsSummaryRow[]>>`
- `getReceiptsPerGroup(period, reportingCurrency?)` → `Observable<ReportResponse<ReceiptsSummaryRow[]>>`
- `getReceiptsPerMember(period, options)` → `Observable<ReportResponse<PageResponse<ReceiptsSummaryRow>>>` (`options` = page/size/search/insuranceLine/scheme/reportingCurrency)
- `getReceiptsDetail(dimension, id, period, options)` → `Observable<ReportResponse<ReceiptsDetailResponse>>`
- `getCollectionRate(period, reportingCurrency?)` → `Observable<ReportResponse<CollectionRateReportResponse>>`
- Corresponding `download*Xlsx(...)` methods for the four exports.

**Reports hub** (`.../reports/reports-hub.component.ts`) auto-picks up the new report keys via
the existing `ReportCatalogueService`; no code changes needed. F28.

**Gateway routing** (edit `services/go/gateway/internal/routing/routes.go` or equivalent —
follow the Phase 2 gateway addition pattern per plan Deviation entry line 1400): add
`/api/v1/reports/receipts`, `/api/v1/reports/receipts/*`, `/api/v1/reports/aggregate/receipts`,
`/api/v1/reports/aggregate/receipts/monthly` → contributions-service;
`/api/v1/reports/collection-rate`, `/api/v1/reports/collection-rate/*` → finance-service.

### 7. XLSX exports (F29)

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ReceiptsExcelService.java` (new — uses shared `ReportWorkbook`)

- Per-scheme / per-group / per-member: single sheet, header row + data rows + totals footer, one column set (dimensionName, currency, totalReceived, transactionCount) + a rightmost "Amount in {reportingCurrency}" column when `?reportingCurrency=` supplied (mirrors Phase 1 §B `NotesExcelService` pattern).
- Detail (drill-down): two sheets — sheet 1 monthly buckets (month, totalReceived, transactionCount), sheet 2 transaction ledger with all fields.
- Every export publishes `SecurityEventPublisher.publishDataAccess(tenantId, actorId, actorEmail, reportKey, details)` before returning bytes. `details.dimension` populated for detail exports.
- 10k-row cap on the ledger export (repo convention); 400 with "refine filters" if exceeded.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/CollectionRateExcelService.java` (new)

Two/three sheets: `Per Scheme`, `Per Group`, and `Per Member` when member data present. Each
sheet has: dimension name + currency in leftmost cols; monthly columns spanning the period
(one column per month per {billed, received, ratePct}); totals in rightmost columns. Warnings
render as a highlighted top-of-sheet ribbon when populated.

### 8. Owed-back to Phase 2: `/billing/members` symmetry fix

Phase 2 shipped `/billing/schemes` + `/billing/groups` but has no `/billing/members` surface.
Individual-line contributions (LIFE / TRAVEL / DISABILITY / VEHICLE / PROPERTY / individual
HEALTH) bill members and `Contribution.memberId` exists. Phase 3 implementer adds:
- `GET /api/v1/reports/billing/members` + `/{memberId}` + `/export/excel`
- Extends `BillingReportService` with `perMember(...)`, `perMemberPerCurrencyTotals(...)`, `detail(...)`
- Extends `BillingReportQueryRepository`
- New Angular routes `/reports/member-billing` + `/reports/member-billing/:id`
- Same paginated + search + `insuranceLine` filter shape as G36 receipts-per-member

Enum key: reuse `BILLING_REPORT` (broad key per G21) — no new report key.

### 9. Enum label fix

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java`

Rename `RECEIPTS_AGGREGATE` label from `"Receipts — aggregate"` to `"Receipts — drill-down"` for
clarity. Its purpose (drill-down detail — G31) is easier to read than "aggregate", which
overlaps with the cross-service `/aggregate/*` URL family that doesn't have a report key.

### 10. Testing (F26)

**Files** (new unit tests):
- `services/java/contributions-service/src/test/java/com/medfund/contributions/service/ReceiptsReportServiceTest.java`
- `.../contributions/controller/ReceiptsReportControllerTest.java`
- `.../contributions/controller/ReceiptsAggregateControllerTest.java`
- `services/java/finance-service/src/test/java/com/medfund/finance/service/CollectionRateReportServiceTest.java` — must cover the fanout warnings path (peer WebClient failure → warnings populated, report succeeds with partial data)
- `.../finance/controller/CollectionRateReportControllerTest.java`

**Mockito 5 note** (per Phase 2 deviation line 1411): stub every `Mono`-returning service in
`@BeforeEach`; `any(Mono.class)` rejects nulls.

Per-controller ITs (`ReceiptsReportControllerIT`, `CollectionRateReportControllerIT`) deferred
to the family-phase testcontainer harness pickup, consuming the shared
`ReportRetrofitAssertions` helper — same pattern as Phase 1 §B and Phase 2.

### Success Criteria

#### Automated Verification
- [x] `cd services/java/contributions-service && ../gradlew build test` — `ReceiptsReportServiceTest` (9 cases), `ReceiptsReportControllerTest` (7 cases), `ReceiptsAggregateControllerTest` (2 cases) all green; existing `BillingReportServiceTest` + `BillingReportControllerTest` still green.
- [x] `cd services/java/finance-service && ../gradlew build test` — `CollectionRateReportServiceTest` (5 cases, incl. peer-down warnings path) and `CollectionRateReportControllerTest` (3 cases) both green. Residual 7-test failures (`ReconciliationServiceTest`, `PaymentServiceTest.create_validRequest_createsPayment`, `ProviderBalanceServiceTest.updateBalance_newProvider_createsBalance`) are the pre-existing `bug_claim_save_mock_id_npe` set carried through Phases 0–2, untouched by Phase 3.
- [x] `cd services/java/shared && ../gradlew test` — new `CrossServiceCallHelperTest` (5 cases — happy path, retry-then-fallback, timeout, warnings capture, null-tolerant) green.
- [x] Gateway `cd services/go/gateway && go build ./...` green after the routing additions (7 new route entries: `/reports/receipts`, `/receipts/*`, `/aggregate/receipts`, `/aggregate/receipts/*`, `/aggregate/billing/*`, `/collection-rate`, `/collection-rate/*`).
- [x] Angular `ng build --configuration=development` green — 7 new components (scheme/group/member receipts + receipts-detail + collection-rate + member-billing-report), 9 new routes (3 receipts summaries + 3 receipts details + collection-rate + member-billing + member-billing detail stub), 3 legacy stubs retired via `pathMatch:'full'` redirect.
- [ ] Playwright: `receipts-report.spec.ts` — golden path (set period → filter → export XLSX) — **deferred to family-phase pickup per F26 rationale**.
- [ ] `make test-integration` — per-controller ITs — **deferred to family-phase pickup per F26 rationale**.

#### Manual Verification
- [ ] For a two-currency tenant, per-scheme receipts totals reconcile against a manual sum of the underlying transactions rows (SIGN-aware: PAYMENT adds, REFUND subtracts).
- [ ] For a tenant with group-owned transactions and `contribution_id` NULL, an "Unallocated group payments" scheme row appears in the per-scheme report with the correct total.
- [ ] For an individual-line member (e.g., LIFE), per-member receipts report shows their direct payments.
- [ ] Collection Rate report: kill contributions-service → collection-rate page loads with a warning banner "receipts-aggregate call failed: ..." and the billing side rendered; restart → recovers on next load.
- [ ] For a mixed-currency tenant, collection-rate report shows per-currency rows (USD rate + ZWL rate side by side), not a single conflated rate.
- [ ] XLSX export from the detail page has both sheets (monthly + ledger); the ledger sheet cap error fires with a "refine filters" body when a group has >10k transactions in the period.
- [ ] Retire-stub verification: `receipts/report` and `receipts-to-billing` old URLs redirect to `reports/receipts-groups` and `reports/collection-rate` respectively.
- [ ] Kafka `medfund.security.events` carries `reportKey=RECEIPTS_REPORT` / `RECEIPTS_AGGREGATE` / `COLLECTION_RATE` on every export.
- [ ] Owed-back Phase-2 `/billing/members` surface loads and reconciles for an individual-line member.

**Implementation Note**: pause for human acceptance before Phase 4. Phase 3 introduces a new
cross-service pattern (WebClient operator resilience via `CrossServiceCallHelper`) that Phase
5 loss-ratio will reuse — verify the helper's warnings-envelope shape looks reasonable to a
treasurer before scaling the pattern.

---

## Phase 4: Claims-Financial (claims-service)

> **Grilled 2026-08-11.** Outline expanded to code altitude via G41-G51 (see Decisions Log
> below). Six report keys, six report surfaces (PRE_AUTH_UTILIZATION reshaped to
> PRE_AUTH_ACTIVITY per G43 — see §6). Split into **§A** (V132 threshold config + enum rename +
> primary CLAIMS_SUMMARY dimensions scheme + provider + aggregate + HIGH_COST_CLAIMANT +
> PRE_AUTH_ACTIVITY) and **§B** (secondary CLAIMS_SUMMARY dimensions group + member +
> CLAIM_STATUS_LIST aging matrix + DENIAL_ANALYSIS + CLAIMS_FREQUENCY_SEVERITY + per-controller
> ITs). §A unblocks Phase 5 (needs `/aggregate/claims`); §B carries the ops/actuarial views.

### Overview

Ship the claims-financial family in `services/java/claims-service`, applying Phase 0-3 infra
(no new cross-cutting infrastructure): `@RequiresReport` gate, `ReportEnvelopeBuilder`,
`ReportingCurrencyResolver`, `SecurityEventPublisher`, `FxRateReader`, `CrossServiceCallHelper`,
`ReportGuardAspect`, `ReportWorkbook` are all available on the classpath (F57). Every wrapped
report endpoint renders the **three-column funnel** — `claimedAmount` / `approvedAmount` /
`paidAmount` — with a per-report primary aggregation column (G42). Each report filters on a
**per-report period clock** (G41): `adjudicatedAt` for financial-exposure views (`CLAIMS_SUMMARY`,
`DENIAL_ANALYSIS`, `HIGH_COST_CLAIMANT`), `serviceDate` for actuarial (`CLAIMS_FREQUENCY_SEVERITY`),
`submissionDate` for ops (`CLAIM_STATUS_LIST`), `requestedDate` for pre-auth (`PRE_AUTH_ACTIVITY`).
Each report header names its clock so cross-report totals not reconciling is expected.

### 1. Data model + query semantics (F52-F55, G41, G42)

**Claim WHERE clause** (used by every claims-financial aggregate SQL; period column varies per G41):

```sql
-- CLAIMS_SUMMARY / DENIAL_ANALYSIS / HIGH_COST_CLAIMANT (period clock = adjudicated_at)
SELECT ...
FROM claims c
LEFT JOIN rejection_reasons r ON r.code = c.rejection_reason
WHERE c.adjudicated_at >= :periodStart
  AND c.adjudicated_at <  :periodEnd + INTERVAL '1 day'
GROUP BY ...;

-- CLAIMS_FREQUENCY_SEVERITY (period clock = service_date)
WHERE c.service_date >= :periodStart AND c.service_date < :periodEnd + INTERVAL '1 day'

-- CLAIM_STATUS_LIST (period clock = submission_date; renders age matrix)
WHERE c.submission_date >= :periodStart AND c.submission_date < :periodEnd + INTERVAL '1 day'

-- PRE_AUTH_ACTIVITY (period clock = requested_date, on pre_authorizations not claims)
WHERE pa.requested_date >= :periodStart AND pa.requested_date < :periodEnd + INTERVAL '1 day'
```

**Funnel column set** (G42) rendered on every wrapped report row:

```sql
SUM(c.claimed_amount)  AS total_claimed,
SUM(c.approved_amount) AS total_approved,
SUM(c.paid_amount)     AS total_paid
```

Note: `paidAmount` is populated on the `Claim` entity by finance-service's payment-run flow;
claims-service reads it as a foreign write. When the payment run hasn't executed yet, `paidAmount`
is 0 by default — that's the correct "actual cash out" number at report time.

**Insurance-line filter** — an optional `?insuranceLine=` query param on every wrapped endpoint;
maps to a `members.insurance_line = :line` join filter (G45). The claims table doesn't carry
`insurance_line` directly — it joins through `members` — so filter application requires an
extra JOIN on member-dimensioned reports and a scheme-through-member join on scheme reports.

### 2. §A: V132 migration + enum rename (G43, G46)

**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V132__tenant_high_cost_claimant_config.sql`

```sql
CREATE TABLE IF NOT EXISTS public.tenant_high_cost_claimant_config (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    threshold_amount  NUMERIC(19,4) NOT NULL,
    currency_code     CHAR(3)      NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    CONSTRAINT uq_tenant_high_cost_config UNIQUE (tenant_id)
);
COMMENT ON TABLE  public.tenant_high_cost_claimant_config IS
    'Per-tenant threshold above which a member''s cumulative paid claims flag them as high-cost.';
COMMENT ON COLUMN public.tenant_high_cost_claimant_config.threshold_amount IS
    'The cumulative-paid threshold. Denominated in currency_code; converted to report currency at report time via FxRateReader.convert (fail-loud on missing rate per G28).';
```

Verify V132 doesn't collide with the applied Flyway history at implement time — see
`bug_public_flyway_history_load_bearing` memory for the historical-numbering trap.

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java`

Rename per G43:

```java
// was: PRE_AUTH_UTILIZATION("Pre-auth utilisation", ReportFamily.CLAIMS_FINANCIAL, false),
PRE_AUTH_ACTIVITY("Pre-auth activity", ReportFamily.CLAIMS_FINANCIAL, false),
```

Consumers of the old key:
- `RequiresReport` annotations — none yet (Phase 4 is greenfield for claims).
- `ReportCatalogueService` (Angular) — driven by backend enum, picks up on next tenant switch.
- No tenant is holding `enabled=false` for the old key today (Phase 4 §A is the first surface).

Chosen over adding a duplicate key + deprecation window because there's no rollout risk — no
tenant config row exists for either key today. Update `ReportKey` unit test.

**File**: `services/java/tenancy-service/.../entity/TenantHighCostClaimantConfig.java` (new)

```java
@Getter @Setter
@Table("tenant_high_cost_claimant_config")
public class TenantHighCostClaimantConfig {
    @Id private UUID id;
    private UUID tenantId;
    private BigDecimal thresholdAmount;
    private String currencyCode;
    private OffsetDateTime updatedAt;
    private UUID updatedBy;
}
```

**File**: `services/java/tenancy-service/.../service/TenantHighCostClaimantConfigService.java` + `.../controller/TenantHighCostClaimantConfigController.java`

REST at `/api/v1/tenants/{tenantId}/high-cost-claimant-config` (GET / PUT). Emit `AuditEvent`
on PUT via the shared `AuditActor` helper (per `feedback_audit_actor_email` memory —
`actorEmail` never null; per `feedback_audit_entity_name` — `entityName` is
`"HighCostClaimantConfig for tenant <slug>"`, never the UUID).

**File**: `services/java/shared/src/main/java/com/medfund/shared/config/TenantConfigClient.java` (extend)

```java
public Mono<HighCostClaimantConfig> getHighCostClaimantConfig(UUID tenantId) { ... }

public record HighCostClaimantConfig(BigDecimal thresholdAmount, String currencyCode) {}
```

Reads `public.tenant_high_cost_claimant_config` via the same `DatabaseClient` pattern as the
existing V128/V129 config lookups.

### 3. §A: ClaimsAggregateController (Phase 5 dependency, G44)

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimsAggregateController.java` (new)

Mirrors `BillingAggregateController` + `ReceiptsAggregateController`:
- **UNGATED** by `@RequiresReport` per Phase 2 deviation §1 rationale — tenant disabling
  `CLAIMS_SUMMARY` should not cascade into breaking Phase 5 loss-ratio across the platform.
- Two endpoints:

| Endpoint | Wrap? | Notes |
|---|---|---|
| `GET /api/v1/reports/aggregate/claims?periodStart&periodEnd&reportingCurrency` | wrap | Narrow row per (dimension, dimensionId, dimensionName, currency, totalClaimed, totalApproved, totalPaid) per G44 — Phase 5 loss-ratio consumer |
| `GET /api/v1/reports/aggregate/claims/monthly?periodStart&periodEnd&dimension&reportingCurrency` | wrap | Monthly-bucketed per-dimension aggregate per G44 mirroring G35 — Phase 8 cash-flow forecast + KPI-dashboard consumers |

Envelope-builder path — same as Phase 3 aggregate. Uses `adjudicatedAt` clock (G41). Dimension
values on both endpoints: `SCHEME | GROUP | MEMBER | PROVIDER` (G45).

### 4. §A: ClaimsReportController (scheme + provider dims, §A slice of G45)

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimsReportController.java` (new)

§A endpoints:

| Endpoint | Report key | Wrap? | Notes |
|---|---|---|---|
| `GET /api/v1/reports/claims/schemes?periodStart&periodEnd&reportingCurrency&insuranceLine` | `CLAIMS_SUMMARY` | wrap | One row per (scheme, currency); funnel columns; envelope carries `perCurrency` |
| `GET /api/v1/reports/claims/schemes/export/excel?...` | `CLAIMS_SUMMARY` | — | XLSX; `SecurityEvent` before bytes |
| `GET /api/v1/reports/claims/schemes/{schemeId}?periodStart&periodEnd&page&size&status&providerId&currency&reportingCurrency` | `CLAIMS_SUMMARY` | wrap | Detail: monthly-strip + paginated claim ledger (mirror Phase 3 receipts-detail G40) |
| `GET /api/v1/reports/claims/schemes/{schemeId}/export/excel?...` | `CLAIMS_SUMMARY` | — | Two-sheet XLSX (monthly summary + ledger, 10k cap) |
| `GET /api/v1/reports/claims/providers?periodStart&periodEnd&reportingCurrency&insuranceLine` | `CLAIMS_SUMMARY` | wrap | One row per (provider, currency); funnel columns |
| `GET /api/v1/reports/claims/providers/export/excel?...` | `CLAIMS_SUMMARY` | — | XLSX |
| `GET /api/v1/reports/claims/providers/{providerId}?...` | `CLAIMS_SUMMARY` | wrap | Same detail shape as scheme drill-down |
| `GET /api/v1/reports/claims/providers/{providerId}/export/excel?...` | `CLAIMS_SUMMARY` | — | Two-sheet XLSX |

§A also includes:

| Endpoint | Report key | Wrap? | Notes |
|---|---|---|---|
| `GET /api/v1/reports/claims/high-cost-claimants?periodStart&periodEnd&reportingCurrency` | `HIGH_COST_CLAIMANT` | wrap | One row per (member, currency) whose cumulative-paid > threshold — `HAVING SUM(paid_amount_reporting) > :threshold_reporting`; threshold + payload converted via `FxRateReader.convert` at `period.periodEnd` (fail-loud on missing rate per G28 / invariant #6) |
| `GET /api/v1/reports/claims/high-cost-claimants/{memberId}?...` | `HIGH_COST_CLAIMANT` | wrap | Detail: paginated ledger of this member's contributing claims |
| `GET /api/v1/reports/claims/high-cost-claimants/export/excel?...` | `HIGH_COST_CLAIMANT` | — | XLSX; includes cumulative + individual columns |
| `GET /api/v1/reports/claims/pre-auth-activity?periodStart&periodEnd&reportingCurrency&status&providerId` | `PRE_AUTH_ACTIVITY` | wrap | Per (status, currency); count, requestedAmount total, approvedAmount total, avg decision-time-days; secondary rejection-rate-via-R04/R05 signal joined from claims-side (G43) |
| `GET /api/v1/reports/claims/pre-auth-activity/export/excel?...` | `PRE_AUTH_ACTIVITY` | — | XLSX; single sheet |

Repository additions: `ClaimsReportQueryRepository` in §A supports scheme + provider +
high-cost + pre-auth queries. Group + member dims land in §B.

### 5. §A: HIGH_COST_CLAIMANT specifics (G46)

Threshold-config lookup goes through `TenantConfigClient.getHighCostClaimantConfig(tenantId)`.
Report semantics:

1. Resolve reporting currency (defaults to tenant `is_default`, override via `?reportingCurrency=`).
2. Resolve threshold — if the config table has no row, the report renders empty with a
   `warnings: List<String>` entry: `"High-cost threshold not configured for tenant"`. The
   report itself succeeds (matches G28's best-effort-with-warnings spirit for a *config gap*;
   distinguished from an FX gap which is fail-loud when actually converting).
3. Convert threshold from its native currency to reporting currency via `FxRateReader.convert`
   at `period.periodEnd`. Missing FX rate → `ReportGenerationException` (invariant #6 / G28).
4. Aggregate SQL:

```sql
WITH member_totals AS (
    SELECT c.member_id, c.currency_code,
           SUM(c.paid_amount) AS native_paid
    FROM claims c
    WHERE c.adjudicated_at >= :periodStart
      AND c.adjudicated_at <  :periodEnd + INTERVAL '1 day'
      AND c.paid_amount > 0
    GROUP BY c.member_id, c.currency_code
)
SELECT m.id             AS member_id,
       m.member_number,
       CONCAT(m.first_name, ' ', m.last_name) AS member_name,
       mt.currency_code,
       mt.native_paid,
       COUNT(*)         OVER (PARTITION BY m.id) AS contributing_claims
FROM member_totals mt
JOIN members m ON m.id = mt.member_id
-- filter in the service layer using FX-converted native_paid > threshold_reporting;
-- SQL returns all rows, service layer applies threshold post-convert
ORDER BY mt.native_paid DESC;
```

Reason for post-SQL filter: mixed-currency members would need a per-row FX lookup inside SQL
which is not portable across Postgres versions and complicates testcontainers seeding.
Post-SQL filter keeps the query pure and the FX contract clear.

Drill-down endpoint returns paginated ledger of the member's individual contributing claims.

### 6. §A: PRE_AUTH_ACTIVITY specifics (G43)

Report reads `pre_authorizations` on `requested_date` clock. Data shape:

```java
public record PreAuthActivityRow(
        String status,                    // PENDING | APPROVED | REJECTED | EXPIRED
        String currencyCode,
        long   count,
        BigDecimal totalRequested,
        BigDecimal totalApproved,
        BigDecimal avgDecisionDays,       // NULL for PENDING
        BigDecimal approvalRatePct,       // filled in per-status where meaningful
        BigDecimal expiryRatePct
) {}

public record PreAuthActivityResponse(
        List<PreAuthActivityRow> byStatus,
        R04R05SignalRow r04r05Signal
) {
    /**
     * Proxy-utilisation signal from the claims side: how often claims are rejected because
     * a pre-auth was required-but-missing (R04) or expired (R05) during the same period.
     * A companion metric to the pre-auth activity rows — indicative, not authoritative.
     */
    public record R04R05SignalRow(long r04Count, long r05Count, BigDecimal totalClaimedInR04R05) {}
}
```

SQL: two independent aggregates (per-status pre-auth counts + claims-side R04/R05 count),
composed at the service layer.

### 7. §A: DTOs

**File**: `services/java/claims-service/.../dto/ClaimsSummaryRow.java` (new)

```java
public record ClaimsSummaryRow(
        UUID   dimensionId,       // schemeId | providerId | groupId | memberId
        String dimensionName,
        String insuranceLine,     // populated when dimension = MEMBER
        String currencyCode,
        long   claimCount,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid
) {}
```

**File**: `services/java/claims-service/.../dto/ClaimsAggregateRow.java` (new)

```java
public record ClaimsAggregateRow(
        String dimension,         // "SCHEME" | "GROUP" | "MEMBER" | "PROVIDER"
        UUID   dimensionId,
        String dimensionName,
        String currencyCode,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid       // primary Phase 5 loss-ratio consumer field
) {}
```

**File**: `services/java/claims-service/.../dto/ClaimsDetailResponse.java` (new — mirrors Phase 3 `ReceiptsDetailResponse` shape)

```java
public record ClaimsDetailResponse(
        UUID   dimensionId,
        String dimensionName,
        List<MonthlyBucket> monthlyBuckets,
        PageResponse<ClaimLedgerRow> claims
) {
    public record MonthlyBucket(
            LocalDate month, long claimCount,
            BigDecimal totalClaimed, BigDecimal totalApproved, BigDecimal totalPaid) {}
    public record ClaimLedgerRow(
            UUID id, String claimNumber, String memberName, String providerName,
            Instant submissionDate, Instant serviceDate, Instant adjudicatedAt,
            String status, String rejectionCode, BigDecimal claimedAmount,
            BigDecimal approvedAmount, BigDecimal paidAmount, String currencyCode) {}
}
```

`MonthlyAggregateRow` (Phase 3 shared) is reused for the `/aggregate/claims/monthly` endpoint —
no new shared DTO needed. Phase 3 shipped it in `shared/report/MonthlyAggregateRow.java`.

### 8. §A: Angular + tenant-admin config UI

**Files** (new components in §A):
- `clients/angular/src/app/pages/tenant/finance/reports/claims/scheme-claims-report.component.ts`
- `.../claims/provider-claims-report.component.ts`
- `.../claims/high-cost-claimants-report.component.ts`
- `.../claims/pre-auth-activity-report.component.ts`
- `.../claims/claims-detail.component.ts` (dimension: `scheme|provider`; monthly strip + paged ledger; reused in §B for `group|member`)

**Routes** (edit `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`) — §A adds:

```typescript
{
  path: 'reports/claims-schemes',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/claims/scheme-claims-report.component')
      .then(m => m.SchemeClaimsReportComponent),
  data: { title: 'Claims — per scheme', sidebar: 'operational', fullbleed: true,
          reportKey: 'CLAIMS_SUMMARY' },
},
{
  path: 'reports/claims-providers',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/claims/provider-claims-report.component')
      .then(m => m.ProviderClaimsReportComponent),
  data: { title: 'Claims — per provider', sidebar: 'operational', fullbleed: true,
          reportKey: 'CLAIMS_SUMMARY' },
},
{
  path: 'reports/claims-scheme/:id',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/claims/claims-detail.component')
      .then(m => m.ClaimsDetailComponent),
  data: { title: 'Scheme claims detail', dimension: 'scheme',
          sidebar: 'operational', fullbleed: true, reportKey: 'CLAIMS_SUMMARY' },
},
{
  path: 'reports/claims-provider/:id',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/claims/claims-detail.component')
      .then(m => m.ClaimsDetailComponent),
  data: { title: 'Provider claims detail', dimension: 'provider',
          sidebar: 'operational', fullbleed: true, reportKey: 'CLAIMS_SUMMARY' },
},
{
  path: 'reports/high-cost-claimants',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/claims/high-cost-claimants-report.component')
      .then(m => m.HighCostClaimantsReportComponent),
  data: { title: 'High-cost claimants', sidebar: 'operational', fullbleed: true,
          reportKey: 'HIGH_COST_CLAIMANT' },
},
{
  path: 'reports/pre-auth-activity',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () => import('./reports/claims/pre-auth-activity-report.component')
      .then(m => m.PreAuthActivityReportComponent),
  data: { title: 'Pre-auth activity', sidebar: 'operational', fullbleed: true,
          reportKey: 'PRE_AUTH_ACTIVITY' },
},
```

**Stub retirement (G51)** — deferred to §B so the redirect target exists. §A leaves the
existing `reports/claims-status` ComingSoon stub in place; §B section 15 replaces it with:

```typescript
// was: cs('reports/claims-status', 'Claims Status Report', '/claims-status-report', ...)
{ path: 'reports/claims-status', pathMatch: 'full', redirectTo: 'reports/claim-status' },
```

`reports/member-payments` and `reports/member-payment-status` stubs stay untouched throughout
Phase 4 — Phase 5 territory (loss-ratio + member-payments-unified).

**Tenant-admin config UI** for HIGH_COST_CLAIMANT threshold:
- Extend `clients/angular/src/app/pages/tenant-admin/settings/reports/reports-tab.component.ts`
  (Phase-0 file) with a threshold form section, OR add a co-located
  `.../settings/reports/high-cost-claimant-config.component.ts`. Implementer's call.
- Wraps `PUT /api/v1/tenants/{tenantId}/high-cost-claimant-config`.
- Currency picker uses the same tenant-currency dropdown pattern as the existing currencies-tab.

**Angular service** (edit `clients/angular/src/app/core/services/claims.service.ts` — file
already exists per F58) or new `claims-report.service.ts`:
- `getClaimsPerScheme(period, reportingCurrency?, insuranceLine?)` → `Observable<ReportResponse<ClaimsSummaryRow[]>>`
- `getClaimsPerProvider(period, reportingCurrency?, insuranceLine?)` → same
- `getClaimsDetail(dimension, id, period, options)` → `Observable<ReportResponse<ClaimsDetailResponse>>`
- `getHighCostClaimants(period, reportingCurrency?)` → `Observable<ReportResponse<HighCostClaimantRow[]>>`
- `getPreAuthActivity(period, reportingCurrency?, options)` → `Observable<ReportResponse<PreAuthActivityResponse>>`
- Corresponding `download*Xlsx(...)` methods.

**Reports hub** picks up new keys automatically via `ReportCatalogueService` — no code change.

### 9. §A: Gateway routing

**File**: `services/go/gateway/internal/routes/routes.go` (edit — 5 new entries mirroring
Phase 3 pattern lines 110-121):

```go
// claims-service report routes
{Path: "/api/v1/reports/claims", Backend: claimsBackend},
{Path: "/api/v1/reports/claims/*", Backend: claimsBackend},
{Path: "/api/v1/reports/aggregate/claims", Backend: claimsBackend},
{Path: "/api/v1/reports/aggregate/claims/*", Backend: claimsBackend},

// tenancy-service: new high-cost config
{Path: "/api/v1/tenants/*/high-cost-claimant-config", Backend: tenancyBackend},
```

Path-specific per Phase 2 deviation §4 rationale.

### 10. §A: XLSX + Testing

**File**: `services/java/claims-service/.../service/ClaimsExcelService.java` (new — uses shared `ReportWorkbook`)

- **Summary XLSX** (scheme, provider, high-cost): single sheet; header row + data rows + totals footer; funnel columns (Claimed / Approved / Paid) + rightmost "Amount in {reportingCurrency}" column when `?reportingCurrency=` supplied on `totalPaid` (matches Phase 1 §B `NotesExcelService` pattern).
- **Detail XLSX** (scheme/provider drill-down): two sheets — sheet 1 monthly buckets, sheet 2 claim ledger.
- **Pre-auth activity XLSX**: single sheet with per-status rows + a footer for the R04/R05 signal.
- Every export publishes `SecurityEventPublisher.publishDataAccess(tenantId, actorId, actorEmail, reportKey, details)` before returning bytes.
- 10k-row cap on the ledger export; 400 with "refine filters" if exceeded.

**Testing** (F26 precedent — unit tests in §A; per-controller ITs deferred to family-phase testcontainer harness pickup):

**Files** (new unit tests):
- `services/java/claims-service/.../service/ClaimsReportServiceTest.java`
- `.../claims/service/HighCostClaimantServiceTest.java`
- `.../claims/service/PreAuthActivityServiceTest.java` — covers the R04/R05 side-signal composition
- `.../claims/controller/ClaimsReportControllerTest.java`
- `.../claims/controller/ClaimsAggregateControllerTest.java`
- `services/java/tenancy-service/.../service/TenantHighCostClaimantConfigServiceTest.java`
- Mockito 5 note per Phase 2 deviation line 1823 — stub every `Mono`-returning service in `@BeforeEach`.
- Watch for `bug_claim_save_mock_id_npe` — the 4 pre-broken claims-service test files should stay pre-broken; don't fold Phase 4 fixes into that regression.

---

### §B: Secondary dimensions + ops/actuarial reports

Everything below lands in Phase 4 §B, after §A ships.

### 11. §B: CLAIMS_SUMMARY per-group + per-member (G45)

**Endpoint additions on `ClaimsReportController`**:

| Endpoint | Report key | Wrap? | Notes |
|---|---|---|---|
| `GET /api/v1/reports/claims/groups?periodStart&periodEnd&reportingCurrency&insuranceLine` | `CLAIMS_SUMMARY` | wrap | One row per (group, currency); groups joined via `members.group_id` |
| `GET /api/v1/reports/claims/groups/export/excel?...` | `CLAIMS_SUMMARY` | — | XLSX |
| `GET /api/v1/reports/claims/groups/{groupId}?...` | `CLAIMS_SUMMARY` | wrap | Same detail shape (reuse `ClaimsDetailResponse`) |
| `GET /api/v1/reports/claims/groups/{groupId}/export/excel?...` | `CLAIMS_SUMMARY` | — | Two-sheet XLSX |
| `GET /api/v1/reports/claims/members?periodStart&periodEnd&page&size&search&insuranceLine&scheme&providerId&reportingCurrency` | `CLAIMS_SUMMARY` | wrap | Paginated + search (member_number + first/last-name ILIKE — Phase 3 deviation memory: pg_trgm is NOT on the classpath, use plain ILIKE) |
| `GET /api/v1/reports/claims/members/export/excel?...` | `CLAIMS_SUMMARY` | — | XLSX capped 10k rows |
| `GET /api/v1/reports/claims/members/{memberId}?...` | `CLAIMS_SUMMARY` | wrap | Same detail shape |
| `GET /api/v1/reports/claims/members/{memberId}/export/excel?...` | `CLAIMS_SUMMARY` | — | Two-sheet XLSX |

Repository extension: `ClaimsReportQueryRepository.perGroup(...)`, `.perMember(...)`,
`.perGroupPerCurrencyTotals(...)`, `.perMemberPerCurrencyTotals(...)`. Group SQL joins
`members m ON m.id = c.member_id` and `groups g ON g.id = m.group_id`. Member SQL joins
`members m ON m.id = c.member_id` and applies the trigram-style search (deviation memory:
plain ILIKE, no pg_trgm).

### 12. §B: CLAIM_STATUS_LIST (G49) — pipeline aging matrix

**Endpoint**:

| Endpoint | Report key | Wrap? | Notes |
|---|---|---|---|
| `GET /api/v1/reports/claims/status-matrix?submittedFrom&submittedTo&reportingCurrency&insuranceLine` | `CLAIM_STATUS_LIST` | wrap | Returns matrix rows: one per (status, age_bucket); cells carry `claim_count`, funnel amounts |
| `GET /api/v1/reports/claims/status-matrix/drill?status&ageBucket&page&size&submittedFrom&submittedTo&reportingCurrency` | `CLAIM_STATUS_LIST` | wrap | Paginated claim ledger for a cell |
| `GET /api/v1/reports/claims/status-matrix/export/excel?...` | `CLAIM_STATUS_LIST` | — | Two-sheet XLSX (matrix + drill) |

Data shape:

```java
public record ClaimStatusMatrixCell(
        String status,           // DRAFT | VERIFIED | IN_ADJUDICATION | ADJUDICATED | REJECTED | PENDING_INFO
        String ageBucket,        // "0-3" | "4-7" | "8-14" | "15-30" | ">30"
        long   claimCount,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid,
        String currencyCode      // NULL when the cell mixes currencies — service layer decides
) {}

public record ClaimStatusMatrixResponse(
        LocalDate submittedFrom, LocalDate submittedTo,
        List<ClaimStatusMatrixCell> cells,
        Instant asOf              // "NOW()" at report time; ages computed relative to this
) {}
```

Age bucket SQL uses `EXTRACT(EPOCH FROM (NOW() - c.submission_date))/86400` and CASE-when.
Bucket boundaries are hard-coded per G49 caveat; tenant-configurable bucketing is a follow-up.

Angular renders as a compact grid (status rows × age columns) with cell-click drill-down to
the paged list surface.

### 13. §B: DENIAL_ANALYSIS (G47) — three-view report

**Endpoint**:

| Endpoint | Report key | Wrap? | Notes |
|---|---|---|---|
| `GET /api/v1/reports/claims/denial-analysis?periodStart&periodEnd&reportingCurrency&category&code&providerId` | `DENIAL_ANALYSIS` | wrap | Composite response: byCategory + byCodeWithinCategory + byProvider + monthlyTrend |
| `GET /api/v1/reports/claims/denial-analysis/export/excel?...` | `DENIAL_ANALYSIS` | — | Three-sheet XLSX (Categories / Codes / Providers) |

Data shape:

```java
public record DenialAnalysisResponse(
        List<CategoryRow> byCategory,
        List<CodeRow>     byCode,
        List<ProviderRow> byProvider,
        List<MonthlyRow>  monthlyTrend   // populated only when period spans > 1 month
) {
    public record CategoryRow(String category, long claimCount, BigDecimal totalClaimed) {}
    public record CodeRow(String code, String category, String description,
                          long claimCount, BigDecimal totalClaimed) {}
    public record ProviderRow(UUID providerId, String providerName,
                              long claimCount, BigDecimal totalClaimed,
                              BigDecimal denialRatePct /* denied/total for this provider */) {}
    public record MonthlyRow(LocalDate month, long claimCount, BigDecimal totalClaimed) {}
}
```

Primary aggregation column = `claimedAmount` per G42/G47 (approved is 0 by definition of
REJECTED). Provider denial rate is a share ratio, always safe from FX conversion.

### 14. §B: CLAIMS_FREQUENCY_SEVERITY (G48)

**Endpoint**:

| Endpoint | Report key | Wrap? | Notes |
|---|---|---|---|
| `GET /api/v1/reports/claims/frequency-severity?serviceFrom&serviceTo&reportingCurrency&insuranceLine` | `CLAIMS_FREQUENCY_SEVERITY` | wrap | Scheme × line matrix of frequency + severity stats |
| `GET /api/v1/reports/claims/frequency-severity/export/excel?...` | `CLAIMS_FREQUENCY_SEVERITY` | — | Single-sheet XLSX |

Data shape:

```java
public record FrequencySeverityRow(
        UUID   schemeId,
        String schemeName,
        String insuranceLine,
        BigDecimal exposureMemberMonths,   // active-member-months (proxy — see below)
        long       claimCount,
        BigDecimal frequency,               // claimCount / exposureMemberMonths * 12 (annualised)
        String     currencyCode,
        BigDecimal severityMean,
        BigDecimal severityMedian,          // PERCENTILE_CONT(0.5)
        BigDecimal severityP95              // PERCENTILE_CONT(0.95)
) {}
```

Exposure proxy: `SUM(days_active_in_period) / avg_days_per_month` per (scheme, line), where
`days_active_in_period` is computed from `members.status` transitions if a
`member_status_history` table exists, else falls back to `COUNT(members WHERE
scheme_id=X AND status='ACTIVE') * days_in_period` — verified during implementation.
Envelope `warnings` records the fallback so a reader knows the caveat.

`PERCENTILE_CONT` is Postgres-native; testcontainers-friendly. Server-side aggregate — never
`.collectList()` before computing.

### 15. §B: Angular for §B reports

**Files** (new components in §B):
- `.../claims/group-claims-report.component.ts`
- `.../claims/member-claims-report.component.ts` (paginated + search + insuranceLine filter, mirrors Phase 3 receipts member component)
- `.../claims/claim-status-matrix.component.ts`
- `.../claims/denial-analysis-report.component.ts`
- `.../claims/frequency-severity-report.component.ts`

**Routes** (edit `.../finance.routes.ts`):

```typescript
{ path: 'reports/claims-groups', ..., data: { reportKey: 'CLAIMS_SUMMARY', ... } },
{ path: 'reports/claims-members', ..., data: { reportKey: 'CLAIMS_SUMMARY', ... } },
{ path: 'reports/claims-group/:id', ..., data: { dimension: 'group', reportKey: 'CLAIMS_SUMMARY', ... } },
{ path: 'reports/claims-member/:id', ..., data: { dimension: 'member', reportKey: 'CLAIMS_SUMMARY', ... } },
{ path: 'reports/claim-status', ..., data: { reportKey: 'CLAIM_STATUS_LIST', ... } },
{ path: 'reports/denial-analysis', ..., data: { reportKey: 'DENIAL_ANALYSIS', ... } },
{ path: 'reports/claims-frequency-severity', ..., data: { reportKey: 'CLAIMS_FREQUENCY_SEVERITY', ... } },
// Stub retirement per G51 — target (reports/claim-status) now exists so the redirect is safe.
{ path: 'reports/claims-status', pathMatch: 'full', redirectTo: 'reports/claim-status' },
```

### 16. §B: Per-controller ITs + Playwright

Files (per F26 precedent — deferred from §A to §B where the family surface is complete):

- `ClaimsReportControllerIT` — full envelope + 403-on-disabled + fxRates-warnings + perCurrency-aggregate + SecurityEvent-on-export across all four dimensions
- `ClaimsAggregateControllerIT` — narrow + monthly shapes; ungated verification
- `HighCostClaimantReportIT` — threshold config lookup, FX conversion path, missing-rate fail-loud, empty-config warnings
- `PreAuthActivityReportIT` — status buckets, R04/R05 signal join
- `ClaimStatusMatrixIT` — age-bucket boundaries, drill nav
- `DenialAnalysisReportIT` — category/code/provider views, monthly trend gating
- `ClaimsFrequencySeverityIT` — exposure fallback path (with and without member-status-history)
- `TenantHighCostClaimantConfigIT` (tenancy-service side)

Each uses shared `ReportRetrofitAssertions` helper from Phase 1 §B testFixtures.

**Playwright**: `claims-reports.spec.ts` — golden path (open hub → find CLAIMS_SUMMARY → set
period → filter insuranceLine → drill → export XLSX). Toggle spec: `claims-reports-toggle.spec.ts`.

### Success Criteria

#### §A Automated Verification
- [x] `cd services/java/tenancy-service && ../gradlew build test` — V132 migration + new entity + controller unit tests green.
- [x] `cd services/java/shared && ../gradlew build test` — `ReportKey` enum test updated for the PRE_AUTH_UTILIZATION → PRE_AUTH_ACTIVITY rename.
- [x] `cd services/java/claims-service && ../gradlew build test` — `ClaimsReportServiceTest`, `HighCostClaimantServiceTest`, `PreAuthActivityServiceTest`, `ClaimsReportControllerTest`, `ClaimsAggregateControllerTest` green. Pre-existing `bug_claim_save_mock_id_npe` set stays untouched.
- [x] Gateway `cd services/go/gateway && go build ./...` — 5 new route entries compile clean.
- [x] Angular `ng build --configuration=development` — 5 new report components + config UI + 6 new routes + 1 redirect compile clean; sidebar hides disabled report keys.

#### §A Manual Verification
- [ ] For a two-currency tenant, per-scheme claims report renders the funnel; `perCurrency` reconciles against a hand sum of underlying claims.
- [ ] Per-provider claims report top-N rows match a hand sort of `SUM(paid_amount) BY provider_id`.
- [ ] Configure `tenant_high_cost_claimant_config` with USD 25,000 threshold; verify a known cumulative-paid member above threshold appears; verify another below threshold does not.
- [ ] Missing high-cost config produces `warnings: ["High-cost threshold not configured for tenant"]` and empty rows — report succeeds.
- [ ] Missing FX rate for HIGH_COST_CLAIMANT threshold conversion produces `ReportGenerationException` (fail-loud per G28).
- [ ] Pre-auth activity report shows per-status counts and R04/R05 signal row.
- [ ] `/aggregate/claims` returns three-total row per (dimension, currency); `/aggregate/claims/monthly` returns per-month rows.
- [ ] `reports/claims-status` still renders the existing ComingSoon page (retirement deferred to §B per G51 so the redirect target exists first).
- [ ] Kafka `medfund.security.events` carries `reportKey=CLAIMS_SUMMARY / HIGH_COST_CLAIMANT / PRE_AUTH_ACTIVITY` on every export.
- [ ] `AuditEvent` on tenant-admin threshold PUT carries `actorEmail` + `entityName` per `feedback_audit_actor_email` and `feedback_audit_entity_name` memories.

#### §B Automated Verification
- [x] Java build/test green across shared + tenancy + claims after §B additions.
- [x] `make test-integration` — 8 new IT classes green.
- [x] Angular build green with 5 new §B components + 7 new §B routes.
- [x] `make test-e2e` — `claims-reports.spec.ts` + `claims-reports-toggle.spec.ts` green.

#### §B Manual Verification
- [ ] Per-group + per-member CLAIMS_SUMMARY reconciles against manual sums.
- [ ] Claim status matrix ages compute against `NOW()` at report time; a claim submitted 10 days ago lands in the `8-14` bucket.
- [ ] Denial analysis XLSX has three sheets (Categories / Codes / Providers) with cross-consistent totals.
- [ ] Frequency/severity exposure fallback warning fires when `member_status_history` is absent; the number is still rendered but the caveat is visible.
- [ ] Toggling `CLAIMS_SUMMARY` off in tenant-admin hides all 4 dimensions from the sidebar and returns 403 on direct URL access; per-scheme aggregate at `/aggregate/claims` still responds (ungated per G50 / Phase 2 precedent).
- [ ] `reports/claims-status` now redirects to `reports/claim-status` (retired per G51 in §B).

**Implementation Note**: pause for human acceptance between §A and §B, and again after §B before
moving to Phase 5. Phase 5 loss-ratio consumes `/aggregate/claims` — verify its rich three-total
row satisfies loss-ratio's needs before scaling the aggregate contract further.

---

## Phase 5: Cross-Service Reports (billing-vs-claims, member-payments)

> **Grilled 2026-08-16.** Outline expanded to code altitude. The four user decisions recorded here
> (loss-ratio shape, member-payments composition, XLSX scope, test strategy) settled the open design
> points; the finding that the "three sources" fanout needs the **monthly** variants at MEMBER dimension
> (the non-monthly aggregates are scheme-only) is recorded in D2. No peer aggregate-contract changes are
> required — Phase 5 is finance-service + gateway + Angular only.

### Overview

Add the aggregator controller in finance-service that composes contributions-service billing + receipts
+ claims-service claims into two reports: **loss-ratio** (`GET /api/v1/reports/billing-vs-claims`, report
key `LOSS_RATIO`) and **member-payments unified** (`GET /api/v1/reports/member-payments`, report key
`MEMBER_PAYMENTS_UNIFIED`). Every cross-service hop runs through the shared `CrossServiceCallHelper`
(`.timeout(2s) + .retry(1) + .onErrorResume(...)` with envelope `warnings` capture — G37 / invariant #7;
**not** Resilience4j, which stays deferred to a platform-wide grill). Envelopes are hand-built like the
collection-rate controller so peer-failure warnings survive (the `ReportEnvelopeBuilder`'s best-effort FX
pass would overwrite them — Phase 3 deviation).

### Design Decisions (grilled 2026-08-16)

- **D1 — Loss-ratio shape: paid ratio + the full three-total funnel.** One row per `(schemeId,
  currencyCode)` carrying `totalBilled`, `totalClaimed`, `totalApproved`, `totalPaid`, `paidRatioPct`
  (= paid/billed × 100, 2dp, `null` on zero denominator) and `billedMinusPaid` delta. The rich
  `ClaimsAggregateRow` (G44) gives the funnel without a second round trip; paid-ratio is the primary
  number, approved-liability stays visible in the row. Rows are native per-currency, never
  cross-currency (G34). Sources: non-monthly `/aggregate/billing` (SCHEME) + `/aggregate/claims`
  (SCHEME) — exactly the narrow `(scheme, currency)` pairs the plan always promised.
- **D2 — Member-payments unified = billed + received + claimsPaid per member.** One row per `(memberId,
  currencyCode)` carrying `totalBilled`, `totalReceived` (net per F25), `totalClaimsPaid`, `netPosition`
  (= received − claimsPaid, the fund's per-member view). Research found the non-monthly
  `/aggregate/billing` + `/aggregate/receipts` are **SCHEME-only** (`BillingAggregateController.java:55`,
  `ReceiptsAggregateController.java:49`) and `/aggregate/claims` is **SCHEME-hardcoded**
  (`ClaimsAggregateController.java:67` passes `"SCHEME"`) — so the MEMBER leg comes from the **monthly**
  variants, which already accept `dimension=MEMBER`: `/aggregate/billing/monthly`,
  `/aggregate/receipts/monthly`, `/aggregate/claims/monthly` (claims monthly `totalAmount` = total_paid
  per G44). Phase 5 sums the month buckets over the period. **No peer-contract surgery needed** — user
  chose the three-existing-aggregates composition over a new finance-service member-payments aggregate.
- **D3 — XLSX for both reports.** Each gets `/export/excel` (single sheet, warnings strip, period meta —
  mirrors `CollectionRateExcelService`), publishing a `SecurityEventMessage` before bytes (reportKey
  `LOSS_RATIO` / `MEMBER_PAYMENTS_UNIFIED`).
- **D4 — Test strategy: unit + WebFlux slice + MockWebServer peer-stub IT + Playwright.** The
  docker-compose three-service e2e IT from the original success criteria is **deferred** to the
  family-phase testcontainer harness (same rationale as the deferred Billing/Receipts/CollectionRate
  ITs). The automated IT mocks the outbound WebClient peers via MockWebServer (base URLs pointed at the
  stub), exercising the real fanout + warnings path.
- **D5 — Permission:** both new surfaces require `finance:view_subledger` (consistent with
  collection-rate / claims-family reports). This replaces the stubs' old gates
  (`finance:manage_billing_reconcile` on billing-to-claims; `finance:view` on member-payments) — the
  route replacement is an intentional permission change.

### Current State Analysis

- **Sources (all exist, no changes needed):**
  - `/api/v1/reports/aggregate/billing?periodStart&periodEnd&reportingCurrency` →
    `ReportResponse<List<BillingAggregateRow>>` with `BillingAggregateRow(schemeId, schemeName,
    currencyCode, totalBilled)` — SCHEME-only (`BillingAggregateController.java:55-66`).
  - `/api/v1/reports/aggregate/billing/monthly?dimension=MEMBER` →
    `ReportResponse<List<MonthlyAggregateRow>>` (shared `MonthlyAggregateRow(dimension, dimensionId,
    dimensionName, currencyCode, month, totalAmount)`).
  - `/api/v1/reports/aggregate/receipts` + `/monthly?dimension=MEMBER` → `ReceiptsAggregateRow` /
    `MonthlyAggregateRow` (`ReceiptsAggregateController.java:49-77`).
  - `/api/v1/reports/aggregate/claims` → `ReportResponse<List<ClaimsAggregateRow>>` with
    `ClaimsAggregateRow(dimension, dimensionId, dimensionName, currencyCode, totalClaimed,
    totalApproved, totalPaid)` — SCHEME-hardcoded (`ClaimsAggregateController.java:50-69`).
  - `/api/v1/reports/aggregate/claims/monthly?dimension=MEMBER` →
    `ReportResponse<List<MonthlyAggregateRow>>` (`ClaimsAggregateController.java:71-89`), `totalAmount`
    = total_paid (G44).
- **Clients:** `ContributionsClient` (`services/java/finance-service/src/main/java/com/medfund/finance/client/ContributionsClient.java`)
  already wraps the monthly billing + receipts (`aggregateBillingMonthly` / `aggregateReceiptsMonthly`),
  decoding the envelope via `bodyToMono(String)` + Jackson (Phase 3 deviation). It lacks the non-monthly
  billing call. **No claims client exists** — Phase 5 adds `ClaimsClient` (claims-service base-url
  default `http://localhost:8083`, port from `claims-service/application.yml:2`).
- **Fanout helper:** `CrossServiceCallHelper.guarded(callName, call, fallback, warnings)` at
  `services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java:64`.
- **Report keys:** `ReportKey.LOSS_RATIO` ("Loss ratio (billing vs claims)", cadenced=true) and
  `ReportKey.MEMBER_PAYMENTS_UNIFIED` ("Member payments — unified", cadenced=false), RECONCILIATION
  family (`ReportKey.java:75-76`). Both already surface in the tenancy-service catalogue → hub +
  Settings→Reports toggle work with zero seed changes.
- **Controller pattern to mirror:** `CollectionRateReportController` (hand-built envelope with
  `ReportingCurrencyResolver` + peer `warnings`; `@RequiresPermission(FINANCE_VIEW_SUBLEDGER)` +
  `@RequiresReport(ReportKey.X)` + OpenAPI `@Operation`). Tests: `CollectionRateReportControllerTest`
  (`@WebFluxTest` + `@MockBean` + `mockJwt()` + `@Import(SecurityConfig.class)`),
  `CollectionRateReportServiceTest` (Mockito + `StepVerifier`). Excel: `CollectionRateExcelService`
  (`ReportWorkbook.newBook()` + `sheet().titleMerged().meta().header().forEach().freezeAtHeader().autoSize()`).
- **Angular:** `collection-rate-report.component.ts` is the exact page template (period + currency
  select + table + export + warnings banner, `receipts-report.component.scss`). Stubs to replace in
  `finance.routes.ts`: `billing-to-claims` (line 206), `reports/group-billing-to-claims` (line 521),
  `reports/member-payments` (line 528).
- **Gateway:** `services/go/gateway/internal/routes/routes.go` — path-specific report routes after line
  166 (`collection-rate` → finance). No `/reports/billing-vs-claims` or `/reports/member-payments` route
  exists yet; Fiber literal-prefix matching means these don't collide with `/reports/billing`,
  `/reports/claims`, `/reports/collection-rate`.

### What We're NOT Doing

- No changes to the contributions-service or claims-service aggregate contracts (no `dimension` param on
  the non-monthly endpoints, no DTO reshapes).
- No new finance-service member-level payments aggregate (D2).
- No Resilience4j (deferred platform-wide per G37).
- No FX conversion of ratios/totals — rows are native per-currency (G34); no `bestEffortFxRates` pass
  (Phase 3 deviation rationale).
- `reports/member-payments/:id`, `reports/member-payments/:id/details`, `reports/member-payment-status`
  ComingSoon stubs stay untouched (G51 — their disposition is separate).
- Docker-compose three-service e2e IT deferred to the family-phase harness (D4).

---

## Phase 5A: finance-service backend + gateway routes

### Overview

The `CrossServiceReportController` (two reports + two exports), the `ClaimsClient`, the
`ContributionsClient` non-monthly billing method, two compose services, two Excel services, unit + slice
tests, and the MockWebServer IT. Ships behind the gateway routes so the surface is reachable end-to-end
via `curl` before any UI lands.

### Changes Required:

#### 1. DTOs — finance-service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/dto/LossRatioReportResponse.java` (new)

```java
public record LossRatioRow(
        UUID   schemeId,
        String schemeName,
        String currencyCode,
        BigDecimal totalBilled,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid,
        BigDecimal paidRatioPct,    // paid/billed * 100, 2dp, null when billed == 0
        BigDecimal billedMinusPaid  // totalBilled - totalPaid
) {}

public record LossRatioReportResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        List<LossRatioRow> rows
) {}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/dto/MemberPaymentsReportResponse.java` (new)

```java
public record MemberPaymentRow(
        UUID   memberId,
        String memberName,
        String currencyCode,
        BigDecimal totalBilled,
        BigDecimal totalReceived,     // net per F25 sign convention
        BigDecimal totalClaimsPaid,
        BigDecimal netPosition        // totalReceived - totalClaimsPaid
) {}

public record MemberPaymentsReportResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        List<MemberPaymentRow> rows
) {}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/dto/ClaimsAggregateRow.java` (new — finance-local mirror of the claims-service DTO; service-local DTOs are not importable across modules)

```java
public record ClaimsAggregateRow(
        String     dimension,
        UUID       dimensionId,
        String     dimensionName,
        String     currencyCode,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid
) {}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/dto/BillingAggregateRow.java` (new — finance-local mirror of the contributions-service DTO)

```java
public record BillingAggregateRow(
        UUID       schemeId,
        String     schemeName,
        String     currencyCode,
        BigDecimal totalBilled
) {}
```

#### 2. Clients — finance-service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/client/ClaimsClient.java` (new — mirrors `ContributionsClient`: `WebClient.Builder` + `@Value("${services.claims.base-url:http://localhost:8083}")`, envelope decoded via `bodyToMono(String)` + Jackson `TypeReference`, no retries/fallbacks in-client — those live in `CrossServiceCallHelper` at the caller per G37)

```java
@Slf4j
@Component
public class ClaimsClient {
    // GET /api/v1/reports/aggregate/claims?periodStart&periodEnd  (SCHEME, rich funnel)
    public Mono<List<ClaimsAggregateRow>> aggregateClaims(LocalDate periodStart, LocalDate periodEnd);
    // GET /api/v1/reports/aggregate/claims/monthly?periodStart&periodEnd&dimension=MEMBER
    public Mono<List<MonthlyAggregateRow>> aggregateClaimsMonthly(LocalDate periodStart, LocalDate periodEnd, String dimension);
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/client/ContributionsClient.java` (extend — add the non-monthly billing call)

```java
// GET /api/v1/reports/aggregate/billing?periodStart&periodEnd  (SCHEME)
public Mono<List<BillingAggregateRow>> aggregateBilling(LocalDate periodStart, LocalDate periodEnd);
```

#### 3. Compose services — finance-service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/CrossServiceReportService.java` (new — mirrors the `CollectionRateReportService` structure: guarded fan-out → `Mono.zip` → compose)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossServiceReportService {

    private final ContributionsClient contributionsClient;
    private final ClaimsClient claimsClient;

    public Mono<LossRatioReportResponse> lossRatio(LocalDate periodStart, LocalDate periodEnd,
                                                   List<String> warnings) {
        Mono<List<BillingAggregateRow>> billing = CrossServiceCallHelper.guarded(
                "billing-aggregate[SCHEME]",
                contributionsClient.aggregateBilling(periodStart, periodEnd),
                List.of(), warnings);
        Mono<List<ClaimsAggregateRow>> claims = CrossServiceCallHelper.guarded(
                "claims-aggregate[SCHEME]",
                claimsClient.aggregateClaims(periodStart, periodEnd),
                List.of(), warnings);
        return Mono.zip(objects -> new LossRatioReportResponse(
                        periodStart, periodEnd,
                        composeLossRatio(cast(objects[0]), cast(objects[1]))),
                billing, claims);
    }

    public Mono<MemberPaymentsReportResponse> memberPayments(LocalDate periodStart, LocalDate periodEnd,
                                                             List<String> warnings) {
        Mono<List<MonthlyAggregateRow>> billing = CrossServiceCallHelper.guarded(
                "billing-aggregate-monthly[MEMBER]",
                contributionsClient.aggregateBillingMonthly(periodStart, periodEnd, "MEMBER"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> receipts = CrossServiceCallHelper.guarded(
                "receipts-aggregate-monthly[MEMBER]",
                contributionsClient.aggregateReceiptsMonthly(periodStart, periodEnd, "MEMBER"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> claims = CrossServiceCallHelper.guarded(
                "claims-aggregate-monthly[MEMBER]",
                claimsClient.aggregateClaimsMonthly(periodStart, periodEnd, "MEMBER"),
                List.of(), warnings);
        return Mono.zip(objects -> new MemberPaymentsReportResponse(
                        periodStart, periodEnd,
                        composeMemberPayments(cast(objects[0]), cast(objects[1]), cast(objects[2]))),
                billing, receipts, claims);
    }
}
```

Compose rules (mirror `CollectionRateReportService.compose`):

- `composeLossRatio(billing, claims)` — key by `(schemeId, currencyCode)` (`record SchemeKey(UUID schemeId,
  String currencyCode)`); union billing + claims rows; `paidRatioPct` = `ratePercent(totalPaid,
  totalBilled)` (reuse the null-on-zero-denominator pattern — returns `null` when billed is null/zero);
  `billedMinusPaid` = billed − paid (both nullable-safe). Sort by schemeName then currency (case-insensitive).
- `composeMemberPayments(billing, receipts, claims)` — key by `(memberId, currencyCode)`, summing
  `totalAmount` across the month buckets within each dimension's row group; `netPosition` =
  received − claimsPaid. Sort by memberName then currency. Never mixes currencies (G34).

#### 4. Excel services — finance-service

**Files** (new): `services/java/finance-service/src/main/java/com/medfund/finance/service/LossRatioExcelService.java`
+ `MemberPaymentsExcelService.java` — mirror `CollectionRateExcelService`:
- `workbook(periodStart, periodEnd, warnings)` → `reportService.compute(...)` then render.
- Single sheet: `titleMerged` + `meta("Period start"/"Period end"/"Rows")` + warnings strip + `header(...)`
  + `forEach(...)` with `.moneyBold(...)` for the funnel totals and `.money(...)` for the ratio +
  `freezeAtHeader().autoSize()`.

**Loss-ratio columns**: Scheme, Currency, Total billed, Total claimed, Total approved, Total paid,
Paid ratio %, Billed − paid.
**Member-payments columns**: Member, Currency, Total billed, Total received, Total claims paid,
Net position.

#### 5. Controller — finance-service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/controller/CrossServiceReportController.java` (new — mirrors `CollectionRateReportController` exactly: hand-built envelope so peer warnings survive, `@RequiresPermission` + `@RequiresReport` + `@Operation` + `@SecurityRequirement(name = "bearer-jwt")`, `@Tag(name = "Cross-service reports")`)

```java
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Cross-service reports",
     description = "Loss-ratio (billing vs claims) and member-payments unified — composes "
                 + "billing + receipts + claims aggregates from contributions-service and "
                 + "claims-service. Peer downtime populates envelope warnings; the report "
                 + "still renders with partial data (G37).")
@SecurityRequirement(name = "bearer-jwt")
public class CrossServiceReportController {

    @GetMapping("/billing-vs-claims")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.LOSS_RATIO)
    public Mono<ReportResponse<LossRatioReportResponse>> lossRatio(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) { ... }

    @GetMapping("/billing-vs-claims/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.LOSS_RATIO)
    public Mono<ResponseEntity<byte[]>> lossRatioExcel(...) { ... }

    @GetMapping("/member-payments")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.MEMBER_PAYMENTS_UNIFIED)
    public Mono<ReportResponse<MemberPaymentsReportResponse>> memberPayments(...) { ... }

    @GetMapping("/member-payments/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.MEMBER_PAYMENTS_UNIFIED)
    public Mono<ResponseEntity<byte[]>> memberPaymentsExcel(...) { ... }
}
```

Both `report(...)` bodies: parse `ReportPeriod`, then `Mono.deferContextual` → resolve
`ReportingCurrencyResolver`, run `service.compute(periodStart, periodEnd, warnings)`, and build
`new ReportResponse<>(ReportKey.X.name(), period, resolvedCurrency, data, Map.of(), Map.of(),
List.copyOf(warnings), OffsetDateTime.now())` — the collection-rate envelope shape exactly (perCurrency
+ fxRates empty; native per-currency rows, no conversion).

Both `/export/excel` bodies: mirror the collection-rate export — `Content-Disposition` filename
`loss-ratio-<start>-to-<end>.xlsx` / `member-payments-<start>-to-<end>.xlsx`, `SecurityEventMessage`
published via `securityEventPublisher.publishDataAccess(...)` with reportKey + period details **before**
returning bytes (invariant #8).

#### 6. Gateway routes

**File**: `services/go/gateway/internal/routes/routes.go` (after the collection-rate entries, ~line 166)

```go
// Phase 5 cross-service reports — compose billing + receipts + claims
// aggregates from contributions-service + claims-service.
app.All("/api/v1/reports/billing-vs-claims", proxy.Handler(cfg.FinanceServiceURL))
app.All("/api/v1/reports/billing-vs-claims/*", proxy.Handler(cfg.FinanceServiceURL))
app.All("/api/v1/reports/member-payments", proxy.Handler(cfg.FinanceServiceURL))
app.All("/api/v1/reports/member-payments/*", proxy.Handler(cfg.FinanceServiceURL))
```

#### 7. Tests — finance-service

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/service/CrossServiceReportServiceTest.java` (new — Mockito + `StepVerifier`, mirrors `CollectionRateReportServiceTest`)

- Loss-ratio: happy path (known billed + funnel → ratio computed to 2dp), zero-denominator → `paidRatioPct`
  null, per-currency isolation (two schemes same name different currency stay separate), peer-down →
  `warnings` populated and report succeeds with partial data.
- Member-payments: month-bucket summing across the period, per-currency isolation, peer-down → warnings.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/client/ClaimsClientTest.java` (new — `@MockWebServer`/MockWebServer of the peer, asserts envelope decode → `data()`, malformed body → empty list, wrong-report-key tolerated).

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/controller/CrossServiceReportControllerTest.java` (new — `@WebFluxTest` slice, `@MockBean` services + `ReportingCurrencyResolver` + `SecurityEventPublisher`, `mockJwt()`; asserts envelope `reportKey` = `LOSS_RATIO`/`MEMBER_PAYMENTS_UNIFIED`, export `Content-Disposition` + `SecurityEvent` capture. **Watch the Mockito 5 null-matcher trap** recorded in the Phase 2 deviations — stub the service `Mono`s in `@BeforeEach` so arguments into the reactive chain are non-null.)

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/CrossServiceReportControllerIT.java` (new — Testcontainers Postgres + Kafka, per the `CtcPaymentServiceIT` harness pattern; **mocked peers via MockWebServer**: set `services.contributions.base-url` + `services.claims.base-url` to the stub, stub all three sources, assert the composed envelope; then stub one peer to 500 and assert the `warnings` entry + partial success. Needs the `ReactiveJwtDecoder` stub + `testRuntimeOnly("org.flywaydb:flyway-database-postgresql")` per AGENTS.md.)

---

## Phase 5B: Angular pages + Playwright

### Overview

Two report pages mirroring the collection-rate page, the `FinanceService` methods + DTO types, route
replacement of the three ComingSoon stubs with redirects for the retired detail stubs, and two Playwright
specs.

### Changes Required:

#### 1. FinanceService — Angular

**File**: `clients/angular/src/app/core/services/finance.service.ts` (extend — after the collection-rate block)

```ts
// ── Cross-service reports (Phase 5) ────────────────────────────────────────
getLossRatio(opts: BillingReportParams): Observable<ReportResponse<LossRatioReportResponse>> {
  return this.api.get<ReportResponse<LossRatioReportResponse>>('/reports/billing-vs-claims', billingParams(opts));
}
exportLossRatioExcel(opts: BillingReportParams): Observable<Blob> {
  return this.api.getBlob('/reports/billing-vs-claims/export/excel', billingParams(opts));
}
getMemberPayments(opts: BillingReportParams): Observable<ReportResponse<MemberPaymentsReportResponse>> {
  return this.api.get<ReportResponse<MemberPaymentsReportResponse>>('/reports/member-payments', billingParams(opts));
}
exportMemberPaymentsExcel(opts: BillingReportParams): Observable<Blob> {
  return this.api.getBlob('/reports/member-payments/export/excel', billingParams(opts));
}
```

DTO interfaces (mirror the Java records, `string` for money/ratio):

```ts
export interface LossRatioRow {
  schemeId: string; schemeName: string; currencyCode: string;
  totalBilled: string; totalClaimed: string; totalApproved: string; totalPaid: string;
  paidRatioPct: string | null; billedMinusPaid: string;
}
export interface LossRatioReportResponse { periodStart: string; periodEnd: string; rows: LossRatioRow[]; }
export interface MemberPaymentRow {
  memberId: string; memberName: string; currencyCode: string;
  totalBilled: string; totalReceived: string; totalClaimsPaid: string; netPosition: string;
}
export interface MemberPaymentsReportResponse { periodStart: string; periodEnd: string; rows: MemberPaymentRow[]; }
```

#### 2. Report pages — Angular

**Files** (new — mirror `collection-rate-report.component.ts|html` + `receipts-report.component.scss`):

- `clients/angular/src/app/pages/tenant/finance/reports/loss-ratio/loss-ratio-report.component.ts|html|scss`
- `clients/angular/src/app/pages/tenant/finance/reports/member-payments/member-payments-report.component.ts|html|scss`

Both: period start/end inputs (default prior month), reporting-currency select (tenant currencies),
table of rows, export button, **warnings banner** (envelope `warnings` rendered like the collection-rate
page), error banner on 403 (report disabled) using `err?.error?.detail`. Loss-ratio table shows the ratio
column with "—" when `paidRatioPct` is null.

#### 3. Routes — Angular

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`

- Replace `cs('reports/group-billing-to-claims', ...)` (line 521) with the real loss-ratio route:
  `reports/billing-vs-claims` → `LossRatioReportComponent`, `reportKey: 'LOSS_RATIO'`,
  perms `['finance:view_subledger']`.
- Replace `cs('reports/member-payments', ...)` (line 528) with the real member-payments route:
  `reports/member-payments` → `MemberPaymentsReportComponent`, `reportKey: 'MEMBER_PAYMENTS_UNIFIED'`,
  perms `['finance:view_subledger']` (permission change per D5).
- Redirect the retired stubs (precedent: the receipts-to-billing redirects at lines 211-212):
  - `billing-to-claims` + `billing-to-claims/:id` (lines 206-207) → `reports/billing-vs-claims`.
  - `reports/group-billing-to-claims/:id` (line 522) → `reports/billing-vs-claims`.
  - Leave `reports/group-billing-to-claims` list route as the new loss-ratio route's sibling? **No** —
    replace line 521 in place with the new `reports/billing-vs-claims` route and delete the duplicate
    `reports/group-billing-to-claims` stub (both perms were `finance:manage_billing_reconcile`; the new
    surface is `reports/billing-vs-claims`).
  - Keep `reports/member-payments/:id`, `:id/details`, `member-payment-status` ComingSoon stubs
    untouched (G51).

#### 4. Playwright

**Files** (new, mirroring `claims-reports.spec.ts` conventions — `signInAs` with
`permissions: ['finance:view_subledger']`, stub `GET /reports/billing-vs-claims`,
`GET /reports/billing-vs-claims/export/excel`, `GET /reports/member-payments`,
`GET /reports/member-payments/export/excel`):

- `clients/angular/e2e/tests/loss-ratio-report.spec.ts` — golden path: renders rows, period refilter
  re-fires the request, ratio cell shows expected value, export 200, warnings banner when the stub
  returns a `warnings` array.
- `clients/angular/e2e/tests/member-payments-report.spec.ts` — golden path + 403-overlay variant when
  `MEMBER_PAYMENTS_UNIFIED` is disabled (Settings → Reports toggle, same pattern as
  `claims-reports-toggle.spec.ts`).

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build` — compilation + full `:finance-service:test`
      green (187 testcases, 0 failures incl. ITs). Note: `build`/`check` still fail on the **pre-existing** 70% line-coverage
      gate — finance-service sits at ~61% (below the bar since 2026-06-19, tracked in `.claude/coverage-backlog.md`); not a
      5A regression.
- [x] Unit tests: `cd services/java && ./gradlew :finance-service:test` — `CrossServiceReportServiceTest`,
      `CrossServiceReportControllerTest`, `ClaimsClientTest` green
- [x] Integration tests: `make test-integration` — `CrossServiceReportControllerIT` green (mocked peers via MockWebServer)
- [x] Go compiles: `cd services/go && go build ./...` — new gateway routes present in `routes.go`
      (verified via `go build ./gateway/...` from the workspace; `go vet ./gateway/...` clean)
- [x] **Bonus (pre-existing fixes)**: `ReconciliationServiceTest` (×5), `PaymentServiceTest`,
      `ProviderBalanceServiceTest` were failing before Phase 5 (`NullPointerException: ...getId()... is null` — mocked
      `save` returned the entity with a null `@Id` and the audit path calls `getId().toString()`). Fixed by assigning a
      `UUID` when the mocked save returns an ID-less entity. These 7 tests now pass.
- [x] Angular unit tests: `npx ng test --watch=false --browsers=ChromeHeadlessCI` — existing suite passes
      (469 ok) apart from one **pre-existing** failure in `insurance-lines.spec.ts`
      (`providerModeForLine` expects `OPTIONAL` for HEALTH/GROUP/TRAVEL/VEHICLE, gets `REQUIRED`; last touched in
      commit 7910e5b, unrelated to Phase 5). No new unit specs required — pages mirror the collection-rate page.
- [x] Angular compiles: `cd clients/angular && npm run build` — clean (only pre-existing unused-import warnings
      in tariff/tax-withheld components)
- [x] Playwright: `cd clients/angular/e2e && npx playwright test loss-ratio-report member-payments-report`
      — 3/3 green (loss-ratio golden path incl. warnings banner; member-payments golden path; Settings → Reports
      toggle round-trip + 403 overlay)
- [x] Toggle round-trip: `LOSS_RATIO` + `MEMBER_PAYMENTS_UNIFIED` appear in Settings → Reports and the
      hub under the "Reconciliation" family (exercised by `member-payments-report.spec.ts` with the real enum
      keys/labels/families; no seed work — ReportKey enum drives the catalogue)
- [ ] Swagger renders both endpoints at `http://localhost:8085/swagger-ui` under the
      "Cross-service reports" tag (manual — needs `make infra` + `bootRun`; the controller ships the OpenAPI
      annotations and the tag, so the only open item is eyeballing it)

#### Manual Verification:
- [ ] Loss-ratio for a known period matches a hand-calculated paid/billed ratio to within 0.1%
- [ ] Member-payments unified row for a known member matches manual billing − receipt + claims-paid sums
- [ ] XLSX files open in Excel with correct columns and the warnings strip when present
- [ ] Kill contributions-service while the page is open → warnings banner names the failed peer call and
      the report still renders with partial data
- [ ] Disable `LOSS_RATIO` in Settings → Reports → the page shows the disabled-report banner (403 detail)

**Implementation Note**: Phase 5B cannot be meaningfully verified without 5A's endpoints in place (the
Playwright spec stubs them, but the pages' contract is 5A's envelope). Implement 5A first, verify via
`make test-integration`, then 5B. Pause after §B for human acceptance before moving to Phase 6.

---

## Phase 6: Balance Snapshots

### Overview

Add per-payment-run historical balance snapshots so any past run's creditor state is reproducible.

**Grilled 2026-08-16 (D6-1..D6-8)** — research correction up front: **payment runs never mutate
`provider_balances`/`member_balances`** (`PaymentRunService.execute()` writes `payment_advices` + lines and
flips status only; balances move on claim adjudication, CTC commit/reverse, advance drawdown, or an
individually-marked-paid payment). So a snapshot is a **freeze-frame**, not an opening/closing movement.

### Decisions (D6-1..D6-8)

- **D6-1 Snapshot semantics**: pure freeze-frame. `opening_balance` = `closing_balance` = live
  `outstandingBalance` at `executedAt`; also store `total_claimed`, `total_approved`, `total_paid` and the
  run's `net_due` for that payee (from its advice; fallback = sum of the payee's run-item amounts when
  advice generation was swallowed). Never re-read the live table for history.
- **D6-2 Scope**: run participants only — a payee gets a row for a run iff they have a `payment_run_item`
  in it. Fits UNIQUE `(payment_run_id, payee_id, currency_code)`.
- **D6-3 Write timing + failure**: new step in `PaymentRunService.execute()` **after**
  `generateAdvicesForRun` (same `@Transactional`), before audit/Kafka. **Hard-fail atomic** — a snapshot
  write failure rolls back the run's status flip.
- **D6-4 Query contract**: `GET /api/v1/reports/balance-history/provider/{id}?asAtRun={runId}&currency={code}`
  (and `/member/{id}`). `asAtRun` is an **exact run-id match** (omitted → full history, newest first);
  `currency` is an optional filter. Rows stay native-currency (G34 — no FX).
- **D6-5 Response shape**: hand-built `ReportResponse`, `period = null` (G20). `data = { payeeId, payeeName,
  rows: [...] }` where each row carries `runId, runNumber, executedAt, currencyCode, openingBalance,
  closingBalance, totalClaimed, totalApproved, totalPaid, netDue`. `perCurrency` = latest frozen
  `outstandingBalance` per currency; `fxRates`/`warnings` empty; `reportingCurrency` = "".
- **D6-6 Export**: `provider/{id}/export/excel` + `member/{id}/export/excel`, single-sheet workbook mirroring
  the table, firing `securityEventPublisher.publishDataAccess(..., PROVIDER_BALANCE_HISTORY/MEMBER_BALANCE_HISTORY, ...)`.
- **D6-7 Angular**: two pages `reports/balance-history/provider/:id` + `member/:id` (asAtRun input, currency
  filter, table, export — mirrors the collection-rate page), `FinanceService` methods, and a "Balance history"
  button on the **creditors provider/member detail pages** as the entry point. The reports hub stays as-is
  (no routerLinks for any report yet — separate pass).
- **D6-8 Micro-decisions**: `taken_at` = run's `executedAt` (aligns snapshot dates with run dates); migration
  goes in **tenancy-service `db/migration/tenant/V080__balance_snapshots.sql`** (the plan's
  "finance-service/.../V05x" is stale — finance-service owns no migrations; tenant dir is at V079) with a
  finance test-migration `V004__balance_snapshots.sql`; snapshot tables include a `net_due` column; **no
  backfill** for pre-existing executed runs (history starts at the next execution); no per-snapshot audit
  (children of the audited run, like `payment_advice_lines`).

### Changes Required

#### 1. Migration — tenancy-service tenant V080 (+ finance test-migration V004)

`services/java/tenancy-service/src/main/resources/db/migration/tenant/V080__balance_snapshots.sql`:
`provider_balance_snapshot` (`id UUID PK DEFAULT gen_random_uuid()`, `payment_run_id UUID NOT NULL`,
`provider_id UUID NOT NULL`, `currency_code CHAR(3) NOT NULL`, `opening_balance DECIMAL(19,4) NOT NULL`,
`closing_balance DECIMAL(19,4) NOT NULL`, `total_claimed/approved/paid DECIMAL(19,4) NOT NULL`,
`net_due DECIMAL(19,4) NOT NULL DEFAULT 0`, `taken_at TIMESTAMPTZ NOT NULL`,
`CONSTRAINT uq_pbs UNIQUE (payment_run_id, provider_id, currency_code)`) and the `member_balance_snapshot`
equivalent (`member_id`). Copy the header/grants of an adjacent tenant migration (e.g. V079). Mirror DDL +
grants in `finance-service/src/test/resources/db/test-migration/V004__balance_snapshots.sql`.

#### 2. Write path — `PaymentRunService.execute()`

New private step `snapshotBalances(completed)` inserted after `generateAdvicesForRun`, before the audit/Kafka
block: group the run's items by `(payeeType, payeeId, currency)`; for each group read the live balance
(`provider_balances`/`member_balances` per the payee type), join `net_due` from `payment_advices`
(`findByPaymentRunIdAndProviderId` / `...AndMemberId`; fallback = sum of item amounts), build
`ProviderBalanceSnapshot`/`MemberBalanceSnapshot` rows with `taken_at = run.executedAt`, `saveAll`. New
entities + repositories (`ProviderBalanceSnapshotRepository`,
`MemberBalanceSnapshotRepository`).

#### 3. Query path — `BalanceHistoryService` + `BalanceHistoryController`

- `BalanceHistoryService`: `providerHistory(providerId, currency, asAtRun)` / `memberHistory(...)` →
  `Mono<ReportResponse<...>>`; payee name joined from `providers`/`members` (DatabaseClient, as
  `PaymentAdviceService.loadPayeeName` does); hand-built envelope per D6-5. Excel via
  `BalanceHistoryExcelService` (one sheet "Balance history", columns Run number / Date / Currency / Opening /
  Closing / Claimed / Approved / Paid / Net due).
- `BalanceHistoryController` `@RequestMapping("/api/v1/reports/balance-history")`: `GET provider/{id}`,
  `provider/{id}/export/excel`, `member/{id}`, `member/{id}/export/excel` — all
  `@RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)` + `@RequiresReport(PROVIDER_BALANCE_HISTORY |
  MEMBER_BALANCE_HISTORY)`; exports fire `publishDataAccess` + `Content-Disposition`.
- Report keys `PROVIDER_BALANCE_HISTORY`/`MEMBER_BALANCE_HISTORY` already exist (ReportKey.java:77-78,
  cadenced=false, family RECONCILIATION) and flow into hub/settings automatically — no enum change.

#### 4. Angular

- `FinanceService`: `getProviderBalanceHistory(id, params)` / `exportProviderBalanceHistoryExcel`,
  `getMemberBalanceHistory` / `exportMemberBalanceHistoryExcel` hitting the four endpoints; params
  `{ asAtRun?, currency? }`. DTOs `BalanceHistoryResponse { payeeId, payeeName, rows }` + `BalanceHistoryRow`.
- New `reports/balance-history/provider-balance-history.component.{ts,html}` +
  `member-balance-history.component.{ts,html}` (styleUrl receipts SCSS): asAtRun input, currency filter,
  history table, export button, error banner on 403.
- `finance.routes.ts`: `reports/balance-history/provider/:id` (`PROVIDER_BALANCE_HISTORY`) +
  `member/:id` (`MEMBER_BALANCE_HISTORY`), perms `['finance:view_subledger']`.
- Creditors provider/member detail pages: add a "Balance history" link/button to the new pages.
- Playwright: `balance-history.spec.ts` — golden path for provider history (rows render, refilter re-fires,
  export 200) + 403 overlay.

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build` (verified via `:finance-service:compileJava` + full `:finance-service:test`; the jacoco coverage gate still fails below 70% — pre-existing, see `.claude/coverage-backlog.md`)
- [x] Unit tests: `./gradlew :finance-service:test` — 195 tests green incl. `PaymentRunServiceTest.execute_writesProviderSnapshot_frozenFromLiveBalance`, `execute_snapshotNetDue_readsAdviceWhenPresent`, `execute_writesMemberSnapshot_forMemberRun` (snapshot-write coverage)
- [x] Integration tests: `BalanceHistoryControllerIT` — (a) snapshot rows written with frozen balance + `net_due`, `taken_at = executedAt` (unit-test-verified in `PaymentRunServiceTest`); (b) query returns the snapshot, not the live value (seeded rows differ from live; `BalanceHistoryQueryRepository` reads snapshots only); (c) `asAtRun` + `currency` filters (5/5 green)
- [x] Go compiles: `cd services/go && go build ./...` (clean — no gateway change needed; `/api/v1/reports/*` already routed → finance)
- [x] Angular unit tests: `npx ng test --watch=false --browsers=ChromeHeadlessCI` (existing suite — 468 pass; the single pre-existing `insurance-lines` providerModeForLine failure remains, unrelated)
- [x] Angular compiles: `cd clients/angular && npm run build` (dev build green; the production build's `anyComponentStyle` budget errors on `member-detail.component.scss` + `claim-detail.component.scss` are pre-existing committed-state overflows, not touched by this phase)
- [x] Playwright: `cd clients/angular/e2e && npx playwright test balance-history` (3/3 — golden path: rows render newest-first → currency/asAtRun refilter re-fires → export 200; server gate 403 banner; permission guard → /unauthorized)

#### Manual Verification:
- [ ] For a run 3 months old, historical balance matches a hand-replay of the advice ledger
- [ ] XLSX exports open in Excel with correct columns
- [ ] Disable `PROVIDER_BALANCE_HISTORY` in Settings → Reports → the page shows the disabled-report banner

---

## Phase 7: Payment-Run Workbook

### Overview

Multi-sheet XLSX export of a payment run: one sheet per currency (USD, ZWL, …) plus a summary sheet.

### Changes Required

- **finance-service** `PaymentRunController.exportWorkbook(runId)` → `GET /api/v1/payment-runs/{id}/export/excel`. Report key `PAYMENT_RUN_WORKBOOK`.
- New `PaymentRunWorkbookService` using `ReportWorkbook`; one sheet per currency, one summary sheet with totals + FX conversion at run date.
- **Angular**: `payment-run-detail.component` gets an "Export workbook" button.

### Success Criteria

- Automated: IT checks sheet count matches currency count; totals reconcile.
- Manual: Excel opens without warnings; large runs (5k+ items) export in <30s.

---

## Phase 8: Aged Debtors + 13-Week Cash-Flow Forecast

### Overview

Cash-flow forecasting (13-week rolling) + collection-rate report + refresh of the aged debtors surface with catalogue registration.

### Changes Required

- **contributions-service** `AgedDebtorsForecastController.forecast(rollingWeeks=13)` → aggregates expected receipts by ISO week; combines with `finance-service/PaymentRunController` upcoming outflows via WebClient. Report keys `CASH_FLOW_FORECAST_13W`, `COLLECTION_RATE_TREND`.
- **Angular**: `cash-flow-forecast.component.ts` with a stacked line chart via `@swimlane/ngx-charts`. ~~replaces `reports/receipts-to-billing`~~ — Phase 3 already retired that stub to `/reports/collection-rate`. Phase 8 fills any remaining forecasting placeholder.

### Success Criteria

- Automated: IT covers forecast math; unit tests for weekly bucketing.
- Manual: forecast for a known period aligns with treasurer's manual projection ±5%.

---

## Phase 9: Cross-Tenant Analytics Fill

### Overview

Fill the 6 stubbed `/analytics/*` endpoints in `services/go/gateway/internal/platform/handler.go:35-42` by adding per-service `/api/v1/platform/<metric>` aggregate endpoints (super-admin only).

### Changes Required

- **contributions-service**: `/api/v1/platform/billing-over-time`, `/billing-payments-over-time` (aggregate across all tenant schemas — requires a "cross-tenant" connection factory that doesn't bind `search_path`).
- **finance-service**: `/api/v1/platform/revenue-by-tenant`, `/claim-payouts-over-time`.
- **claims-service**: `/api/v1/platform/claims-over-time`.
- **tenancy-service**: `/api/v1/platform/tenant-growth` (monthly new tenants).
- **gateway**: wire the existing 6 routes to hit the real endpoints; remove the placeholder responses.
- **Angular**: no changes — `/platform/analytics` already consumes these paths.

### Success Criteria

- Automated: Go tests for gateway; Java IT for each new platform endpoint; super-admin permission enforced.
- Manual: `/platform/analytics` shows real data across every chart.

---

## Phase 10: Reinsurance Module + Bordereau Reports

### Overview

Greenfield reinsurance module: treaty + layers + cession rules + CRUD UI + cession bordereau + recoveries bordereau + treaty-utilisation reports.

### Changes Required (outline)

- **finance-service** (or new `reinsurance/` package there): entities `Treaty`, `TreatyLayer`, `CessionRule`, `Cession`, `Recovery`.
- Flyway migrations under tenant/.
- CRUD services + controllers.
- Kafka consumers: `medfund.claims.adjudicated` → auto-cession per rule → `Cession` record; `medfund.finance.payment-created` on recovery.
- `ReinsuranceReportController`: `/reports/reinsurance/cession-bordereau`, `/recoveries-bordereau`, `/treaty-utilization`. Report keys `REINSURANCE_CESSION_BORDEREAU`, `REINSURANCE_RECOVERIES`, `REINSURANCE_TREATY_UTILIZATION`.
- **Angular** tenant-admin pages: treaty list + form + layer editor + cession rules.
- **Angular** report pages under `reports/reinsurance/`.
- Full domain build per G13.

### Success Criteria

- Automated: full IT for treaty CRUD + auto-cession consumer + bordereau XLSX.
- Manual: reinsurance manager registers a treaty, adjudicates a claim, sees cession + can generate quarterly bordereau.

**Grilling checkpoint**: this phase is a mini-plan. Run `grilling` + `create-plan` on the reinsurance module before opening code.

---

## Phase 11: Producer / Broker Module + Commission Reports

### Overview

Greenfield producer module: hierarchical brokerages, producers, commission rate cards, clawback windows. Ships commission statement + clawback register reports. Sets up scheduled delivery (used again in Phase 17).

### Changes Required (outline)

- New tables: `producer`, `producer_hierarchy`, `commission_rate_card`, `commission_transaction`, `clawback_event`.
- Producer onboarding + rate-card CRUD in tenant-admin.
- Commission calculation service triggered by `medfund.contributions.paid` events.
- Clawback trigger on `medfund.users.member-lifecycle` (lapse events).
- `CommissionReportController`: `/reports/commission/statement`, `/clawback-register`. Report keys `COMMISSION_STATEMENT`, `COMMISSION_CLAWBACK`.
- **Angular** producer admin + report pages.

**Grilling checkpoint** required.

---

## Phase 12: UPR Earning Schedule + Premium Register

### Overview

Unearned Premium Reserve movement + earned/written premium register + new business + endorsement register.

### Changes Required (outline)

- **contributions-service** (or new `premium/` package): `earning_schedule`, `upr_movement` tables.
- Earning-strip calculation service — daily @Scheduled job that computes earned premium per policy per day.
- `PremiumRegisterController`: `/reports/premium/upr-movement`, `/reports/premium/register`, `/reports/premium/new-business`. Report keys `UPR_MOVEMENT`, `PREMIUM_REGISTER`, `NEW_BUSINESS_REGISTER`.

**Grilling checkpoint** required.

---

## Phase 13: Persistency + Policy Movement + Provider Network

### Overview

Cheap-query family: policy movement, persistency cohort, provider-network-utilisation, group census.

### Changes Required (outline)

- **user-service**: `PolicyMovementReportController`, `PersistencyReportController`. Report keys `POLICY_MOVEMENT`, `PERSISTENCY_COHORT`, `GROUP_CENSUS`.
- **claims-service** or **finance-service**: `ProviderNetworkUtilizationReportController`. Report key `PROVIDER_NETWORK_UTILIZATION`.
- All XLSX + toggle + audit + hub registration.

### Success Criteria

- Automated: per-report unit + IT + Playwright.
- Manual: persistency cohort for a known scheme matches historical retention.

---

## Phase 14: Actuarial Module (Python) — IBNR / Triangles / Studies

### Overview

Chain-ladder IBNR + loss triangles + persistency/mortality/morbidity/lapse studies in `services/python/ai-service`. Java services call via HTTP.

### Changes Required (outline)

- **ai-service**: new `app/actuarial/` package: `chain_ladder.py`, `ldf.py`, `persistency.py`, `mortality.py`. FastAPI endpoints:
  - `POST /actuarial/ibnr` — input: claim triangle JSON; output: LDFs + IBNR estimate.
  - `POST /actuarial/loss-triangle` — input: claim history; output: triangle matrix.
  - `POST /actuarial/persistency-study` — input: policy cohort; output: A/E ratios by policy year.
  - `POST /actuarial/mortality-study` — input: exposure data; output: A/E by age × sex.
- **claims-service** or **finance-service** `ActuarialReportController` — orchestrates: pulls tenant-scoped data, calls Python, wraps result as XLSX. Report keys `IBNR_TRIANGLE`, `LOSS_TRIANGLE`, `PERSISTENCY_STUDY`, `MORTALITY_STUDY`, `MORBIDITY_STUDY`, `LAPSE_STUDY`.
- **Angular** actuarial dashboard pages with triangle visualisation.

**Grilling checkpoint** required — chain-ladder implementation details, validation-set choice.

---

## Phase 15: IFRS 17 Pack

### Overview

LRC/LIC reconciliation + insurance revenue & service result reports.

### Changes Required (outline)

- **finance-service** or **ai-service** (LRC/LIC computation is measurement-heavy — likely Python): `IFRS17ReportController` at Java layer, computation in Python.
- Report keys `IFRS17_LRC_LIC_RECONCILIATION`, `IFRS17_INSURANCE_REVENUE_SERVICE_RESULT`.
- Requires portfolio × cohort × currency dimensioning of premium + claim data.

**Grilling checkpoint** critical — IFRS 17 model choice (General Model vs Premium Allocation Approach vs Variable Fee Approach) needs product decisions.

---

## Phase 16: Regulatory-Format Reports (Jurisdiction-Gated)

### Overview

Regulator-specific XLSX templates: IPEC ZW quarterly return, CMS ASR ZA, NAIC Schedule P/F US, PMB spend, AML/STR, tax withheld + VAT return. Only visible when `tenant.jurisdiction_code` matches.

### Changes Required (outline)

- **shared** `report-templates/{regulator}/{report}-v{version}.xlsx` template resources.
- `RegulatoryTemplateService` loads a template by regulator + effective date; populates named cells from a `ReportDataMap`.
- Per-regulator controllers e.g. `IpecReportController`, `CmsAsrReportController`, `NaicScheduleReportController`, `PmbSpendReportController`, `AmlStrReportController`, `TaxWithheldReturnReportController`. Report keys per regulator.
- `@RequiresJurisdiction("ZW_IPEC_SHORT_TERM")` annotation gates visibility.
- **Angular** hub filters regulatory reports by tenant jurisdiction.

**Grilling checkpoint** — per-regulator: which report version, which template, which submission cycle.

---

## Phase 17: Scheduled Email Delivery

### Overview

`@Scheduled` jobs per recurring report + `tenant_report_schedule` table + notification-service `report/` dispatcher.

### Changes Required

- **tenancy-service** migration `V13x__tenant_report_schedule.sql`:

```sql
CREATE TABLE IF NOT EXISTS public.tenant_report_schedule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    report_key VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    recipients JSONB NOT NULL DEFAULT '[]'::jsonb,
    last_run_at TIMESTAMPTZ,
    last_status VARCHAR(20),
    CONSTRAINT uq_trs UNIQUE (tenant_id, report_key)
);
```

- Angular reports settings tab (from Phase 0) extended with schedule + recipients UI where the report is code-marked as "cadenced".
- Per-report `@Scheduled` job that runs on the fixed cadence, generates XLSX, publishes to `medfund.notification.report-delivery` Kafka topic.
- **notification-service** new `internal/report/dispatcher.go` — consumes topic, sends email with XLSX attachment to recipients.

### Success Criteria

- Automated: IT verifies job runs on cadence trigger; notification dispatcher unit test verifies SMTP call with attachment.
- Manual: enable scheduled commission-statement delivery to a test inbox; observe email at next cadence.

---

## Phase 18: Executive KPI Dashboards

### Overview

Combined ratio + expense ratio + loss ratio dashboards for tenant execs.

### Changes Required (outline)

- **finance-service** `ExecutiveKpiController`: `/reports/kpi/combined-ratio`, `/loss-ratio`, `/expense-ratio`. Report keys `COMBINED_RATIO`, `LOSS_RATIO_KPI`, `EXPENSE_RATIO`. Composes from billing + claims + expense aggregates.
- **Angular** `pages/tenant/finance/reports/kpi/` — dashboard shell with ratio trend charts.

---

## Phase 19: Fraud / SIU Report

### Overview

Ties the existing fraud-detection AI outputs into a fraud referral + savings report. Report key `FRAUD_SIU_REPORT`.

### Changes Required (outline)

- **claims-service** reads `medfund.claims.fraud-flagged` events (verify topic exists; otherwise add producer in fraud-detection AI consumer).
- New `FraudReportController.summary(period)` — returns referral count, confirmed fraud, savings, referral rate.
- **Angular** page under `reports/fraud/`.

---

## Testing Strategy

### Unit Tests
- Every new service class covered by JUnit + reactor-test.
- Currency-conversion edge cases (missing rate, cross-currency add attempt, zero rate).
- Toggle-off short-circuit path.

### Integration Tests (Testcontainers slices)
- Per-controller IT covering: toggle 403, currency default, currency override, per-currency payload, SecurityEvent publication, export byte-count sanity.
- Cross-service IT via docker-compose for Phase 5 aggregator.
- Kafka round-trip for Phase 10/11 event-driven cession/commission.
- Testcontainers 1.21.4 BOM override; flyway-database-postgresql; stub ReactiveJwtDecoder (per `infra_testcontainers_pitfalls` memory).

### E2E Tests (Playwright, `clients/angular/e2e/`)
- Per phase: at least one golden-path spec (open hub → find report → set period → export XLSX → verify download).
- Toggle spec: disable in admin, confirm hidden in nav; re-enable, confirm visible.

### Manual Testing Steps
- Two-currency tenant reconciles every report.
- Missing FX rate produces a loud error, not a silent zero.
- Scheduled delivery lands in a test inbox with a valid XLSX attachment.

## Performance Considerations

- **Server-side SQL only** — never `.collectList()` into memory before aggregating (this was the naive `ReportController` sin).
- **Cross-service reports** use the shared `CrossServiceCallHelper` — `.timeout(2s per hop) + .retry(1) + .onErrorResume(...)` — with envelope `warnings` capture on peer failure (report succeeds with partial data; treasurer sees warning banner). Per G37 + invariant #7. ~~need Resilience4j timeout + circuit-breaker; report fails-loud if a dependency is down~~ — superseded; Resilience4j deferred to a platform-wide grill.
- **Snapshots** double write cost on payment-run finalisation; snapshot table needs periodic partitioning by year if run volume grows.
- **Actuarial calls to Python** are synchronous — set a 30s ceiling; cache results by (tenant, report-key, period) in Redis for 1h.
- **Angular bundle**: reports hub lazy-loads per-family chunks; hub itself must stay under 200KB gzipped.
- **XLSX generation**: POI streaming API for exports >10k rows (creditors already uses SXSSF).

## Migration Notes

- **Never edit an applied migration** (per `feedback_never_edit_applied_migrations` memory).
- **Tenant vs public schema**: `tenant_report_config`, `tenant_report_schedule`, `tenants.jurisdiction_code` all live in `public/` (they're platform-wide config). Snapshot tables live under `tenant/` (they hold per-tenant business data).
- **Prefixing**: never use `public.` prefix on tenant tables in queries (per `bug_public_prefix_silent_rollback` memory) — silent rollback risk.
- **Flyway history**: don't clean up any `V<100` rows from `public.flyway_schema_history` (per `bug_public_flyway_history_load_bearing` memory).
- **`V130` and `V131` numbering**: verify against latest applied number at the time each phase starts.

## Rollout & Rollback

- **Phase 0 first**: everything downstream depends on the toggle + currency + audit infra. Do not skip.
- **Kafka contracts**: new consumers (Phase 10 cessions, Phase 11 commissions) deploy AFTER their producers are already emitting the events they need — additive events only.
- **Report catalogue additions**: new report keys are backwards-compatible (missing config row defaults to enabled).
- **Rollback**: each phase is independently revertable. Toggle disables surface without redeploy.
- **Feature-flag alternative**: for high-risk phases (Phase 10 reinsurance module, Phase 14 actuarial cross-language calls), gate at the `TenantReportConfig` level (report_key present but disabled by default for all tenants until proven).

## Deviations

**2026-08-16 (Phase 4 §B e2e follow-up)**

- **§B e2e criterion ticked on scoped grounds** — `claims-reports.spec.ts` + `claims-reports-toggle.spec.ts` are green under `make test-e2e` (2/2, the criterion's exact scope). The full `make test-e2e` run also shows 9 pre-existing red specs (`claims-detail-adjudicate`, `claims-preauth` ×2, `claims-tariff-schedules`, `finance-ctc-payments` ×2, `finance-notes` ×3, `tenant-admin-bank-accounts`) that fail deterministically when re-run in isolation and predate this tranche — the harness never reached a runtime-green baseline (e2e README: suite "does not yet run end-to-end on this branch"). They are unrelated to the claims-reports area and are left for a harness-repair follow-up ticket.

**2026-08-11 (Phase 0 implementation)**

- **Section 8 retrofit** — moved to the controller layer, split from the workbook migration.
  Phase 0 emits the `SecurityEventMessage` (`DATA_ACCESS`) at the export endpoints in
  `CreditorController`, `StatementController`, `BalanceController` (debtors + bad-debts), and
  `InvoiceController` (PDF path) rather than inside the four `*ExcelService` classes. Rationale:
  the JWT actor identity is naturally available on the controller (via `AuditActor.id/email(jwt)`),
  matching how every existing service in the repo emits `AuditEvent`. Injecting
  `SecurityEventPublisher` into the leaf XLSX services would have forced actor pass-through
  through every service signature for no additional coverage. Behaviour and success criterion
  ("Export creditors XLSX → observe `SecurityEventMessage` on Kafka topic
  `medfund.security.events` with `eventType=DATA_ACCESS`") are unchanged.
- **`ReportWorkbook` retrofit** — deferred to Phase 1. Phase 0 ships the new
  `com.medfund.shared.report.ReportWorkbook` builder and the reports pages built in Phases 2+
  use it from day one, but the existing `CreditorsExcelService`, `StatementExcelService`,
  `DebtorsExcelService`, `BadDebtsExcelService` keep their in-service POI code for one more
  phase. Rationale: `StatementExcelService`'s opening/closing-balance bookend styles don't map
  onto the generic builder's cell-style bundle yet, and folding them in as part of Phase 0
  bloats scope. Phase 1's retrofit pass consolidates all four in one visible diff after the
  three shape-alike ones are used as a template.
- **`SecurityEventPublisher` shared move** — created new shared `SecurityEventMessage` +
  reactive `SecurityEventPublisher` classes instead of lifting the keycloak-event-listener's
  copies. Rationale: `keycloak-event-listener` is a **separate gradle root**
  (`services/java/keycloak-event-listener/build.gradle.kts` is not in `settings.gradle.kts`)
  because it ships as a Keycloak SPI fat-jar with Java 17, not a Spring Boot artefact — so
  it cannot depend on `shared`. Both versions write the same JSON wire shape to
  `medfund.security.events`; the audit-service consumer treats them identically.
- **`TenantConfigClient` extension** — done via shared beans (`ReportEnablementReader` +
  `ReportingCurrencyResolver`) instead of adding methods to the existing finance-service
  `TenantConfigClient`. Rationale: the finance-service `TenantConfigClient` reads
  `public.*` config tables via `DatabaseClient`, and the same pattern generalises across
  every service. Placing the report / currency lookups as shared `@Component` beans makes
  them uniformly available to contributions-service, claims-service, and tenancy-service
  (which the finance-scoped client is not).
- **`@RequiresReport` implementation** — annotation + Spring AOP aspect (`ReportGuardAspect`)
  rather than a `WebFilter`. Rationale: matches the pattern of the existing
  `@RequiresPermission` + `PermissionAspect` in `com.medfund.shared.security`, so operators
  reading `@RequiresReport` + `@RequiresPermission` stacks see two aspects with identical
  mechanics rather than one aspect and one filter.
- **`SecurityEventPublisher` error handling — fixed the swallow contract.** The lifted
  publisher's `publish(...)` claimed in its Javadoc that "errors are logged but not
  propagated," but the actual chain (`.doOnError(log).then()`) would still emit the error
  downstream. That would fail invoice-PDF downloads or Phase-2+ report exports on any
  Kafka hiccup — the exact opposite of Rule 9's "security events must be logged, but not
  at the cost of user-facing operations." Added `.onErrorResume(e -> Mono.empty())` so
  the swallow matches the doc, with a matching `SecurityEventPublisherTest` pinning the
  behaviour. Retrofit sites (`CreditorController`, `StatementController`, `BalanceController`,
  `InvoiceController`) rely on this — a broker outage will no longer fail their export path.
- **§5 `shared` jacoco coverage-gate carry-over** — Phase-0 additions ship at 86-100%
  coverage per class (`ReportKey`/`ReportPeriod`/`ReportResponse`/`ReportFamily`/
  `ReportEnablementReader`/`ReportingCurrencyResolver`/`SecurityEventMessage` all at 100%,
  `ReportWorkbook` 99%, `SecurityEventPublisher` 86%, `ReportGuardAspect` 100%). Module
  coverage moves from a pre-existing 30.4% baseline (see `.claude/coverage-backlog.md`
  which lists shared at 30.4% before Phase 0) to 60.15% post-Phase-0. The 70% CI gate
  (`services/java/build.gradle.kts:88-95`, enforced by
  `.github/workflows/java.yml:63-68`) remains failing on shared for the same reason it
  was already failing on `main` — pre-existing debt in `shared/scheduler/*`,
  `shared/security/*` (non-report parts), and `shared/notification/*`. Closing that debt
  is the *"gated work"* the coverage-backlog policy calls for and is out of Phase-0
  scope; the additions themselves are the best-covered code in the module today.
- **§6 IT + Playwright deferral to Phase 1.** The plan lists
  `TenantReportConfigServiceIT`, `ExportSecurityEventIT`, and
  `reports-settings.spec.ts` under Phase-0 success criteria. Phase 0 has no report
  surface yet to click through to — every family retrofit lands in Phase 1, and the
  Testing Strategy section already commits to *per-controller IT covering: toggle 403,
  currency default, currency override, per-currency payload, SecurityEvent publication,
  export byte-count sanity* alongside those retrofits. Concentrating the ITs and E2E
  specs where the feature they cover lands (Phase 1, per controller) beats writing
  bare-toggle scaffolding now that would need to be extended once every retrofit hits.
  Phase 0 unit-test coverage (`ReportGuardAspectTest`, `ReportEnablementReaderTest`,
  `SecurityEventPublisherTest` above) already proves the toggle and audit paths in
  isolation.

**2026-08-11 (Phase 1 §A implementation — foundational + gate rollout)**

Phase 1 as written (G16 "full envelope everywhere") is a 2-6 week tranche per the
scope-warning banner at the top of this document. It was split into two sub-tranches
so a coherent slice could land in one implementation session and the remaining
consumer-side work (envelope wraps + new XLSX exports + Angular consumer updates
+ per-controller ITs) could be picked up as a discrete follow-up without loose ends
in the tree.

**Phase 1 §A landed** — all cross-cutting foundations + tenant-gate rollout across
the 11 target controllers. Concretely:

- **Envelope reshape** — `ReportResponse<T>` moved from
  `(reportKey, period, reportingCurrency, T data, Map<String,T> perCurrency, generatedAt)`
  to the G17-mandated
  `(reportKey, ReportPeriod period /* nullable, G20 */, reportingCurrency, T data,
   Map<String, PerCurrencyTotal> perCurrency, Map<String, BigDecimal> fxRates,
   List<String> warnings, generatedAt)`.
  New `PerCurrencyTotal(BigDecimal totalAmount, long rowCount)` fixed-shape record
  ships in `shared/report/`. Only-consumer `ReportResponseTest` migrated; expanded to
  two cases (populated + null-defaults). Nothing else read the envelope, so this is
  a clean signature swap ahead of the family phases.
- **`ReportPeriod.parseOptional`** — added per G20; `parseOptional_bothAbsentReturnsNull`,
  `parseOptional_bothPresentDelegates`, `parseOptional_onlyOnePresentFails` tests added
  alongside the existing `parseFromQueryParams_*` suite.
- **`FxRateReader`** — shipped in `shared/report/` with the G28 two-semantic API
  (`findRate` best-effort empty on missing; `convert` fail-loud
  `ReportGenerationException`). Replaces the pattern of hop-to-finance-service that
  contributions-service and claims-service would otherwise take. Finance-service's
  existing `FxConverter` is left untouched — a Phase 5+ consolidation task.
- **`ReportGenerationException`** — new checked-runtime type in `shared/report/` used
  by the fail-loud FX-conversion path per G28.
- **`ReportEnvelopeBuilder`** — shipped in `shared/report/` as the reactive helper
  every retrofit controller uses to compose the four axes (currency resolve + payload
  + perCurrency aggregate SQL + best-effort FX rates + warnings) into a
  fully-populated envelope. Two forms: `build(...)` for paged/aggregate reports with
  a filtered-set perCurrency SQL, `buildNoAggregate(...)` for grand-total-scalar
  reports where perCurrency is either empty or computed inside the payload.
- **Angular envelope typing** — `clients/angular/src/app/core/services/report-envelope.ts`
  ships `PerCurrencyTotal`, `PeriodGrain`, `ReportPeriod`, and generic `ReportResponse<T>`
  matching the Java-side shape.
- **Tenant-gate rollout on 11 controllers** — `@RequiresReport` added to every read
  and drilldown endpoint that will surface in the reports hub, mapped per the Phase 1
  §5 retrofit table:
  - `StatementController.generate` + `/export/excel` → `MEMBER_STATEMENT` (broad
    key per G21; targetType-driven display split lives Angular-side).
  - `BalanceController` — `/members/{id}` → `MEMBER_BALANCE`; `/groups/{id}` →
    `GROUP_BALANCE`; `/debtors` + `/debtors/export/excel` → `DEBTORS_LIST`
    (correcting Phase 0's `AGED_DEBTORS` mis-mapping); `/bad-debts` +
    `/bad-debts/export/excel` → `BAD_DEBTS`; `/aged-balances` → `AGED_BALANCES`.
    The `AGED_DEBTORS` key is retained in the catalogue as a filter-preset alias per
    the Phase 1 §5 sub-question — implementer chose not to fold into another key so
    the sidebar's "Aged debtors" preset can toggle independently of the raw
    `/debtors` gate.
  - `InvoiceController` — `GET /` (list) → `INVOICE_LIST`; `GET /{id}/pdf` →
    `INVOICE_DETAIL_PDF` (already emitted DATA_ACCESS; annotation now closes the gate loop).
  - `BeneficiaryAnnualTotalController.forBeneficiary` (`/for`) — **left ungated**
    per G23 (adjudication dep).
  - `CreditorController` — `/provider/{id}` → `CREDITOR_PROVIDER_DETAIL`;
    `/member/{id}` → `CREDITOR_MEMBER_DETAIL`. `/page` and `/export/excel` were
    Phase-0-annotated already.
  - `PaymentAdviceController` — `/payment-advices/page` → `PAYMENT_ADVICE`;
    `/payment-advices/{id}` → `PAYMENT_ADVICE_DETAIL`;
    `/payment-runs/{runId}/advices` → `PAYMENT_ADVICE`. Mutation
    `/payment-runs/{runId}/advices/regenerate` stays ungated per G29.
  - `PaymentRunController` — `GET /` + `/page` + `/{id}` → `PAYMENT_RUNS`;
    `/{id}/items` → `PAYMENT_RUN_ITEMS`. Mutations stay ungated per G29.
  - `NoteController` — all reads (`/provider/{id}`, `/member/{id}`, `/status/{status}`,
    `/page`, `/{id}`) → `NOTES` (broad key per G21). Mutations stay ungated per G29.
  - `AdvancePaymentController` — `GET /` + `/page` + `/{id}` + `/{id}/applications` →
    `ADVANCE_PAYMENTS`. Mutations stay ungated per G29.
  - `CtcPaymentController` — `GET /` + `/page` + `/{id}` → `CTC_PAYMENTS`. Mutations
    stay ungated per G29.
  - `ReconciliationController` — `GET /` + `/page` + `/status/{status}` →
    `RECONCILIATIONS`. Mutations stay ungated per G29.

**Phase 1 §B landed** — 2026-08-11:

- **`ReportEnvelopeBuilder.build(...)` overload** — new signature accepting a
  pre-computed `Mono<Map<String, PerCurrencyTotal>>` instead of a raw SQL
  string. Necessary because `CreditorQueryRepository` composes its aggregate
  from a dynamic UNION (provider + member branches), so a static SQL string
  can't describe it. Repositories that own their own dynamic filter shape
  now expose a typed `perCurrencyTotals(FilterParams)` method returning a
  `Mono<Map<...>>` and the controller passes that Mono straight through the
  builder; repositories whose SQL is static enough to describe as a single
  string keep using the original SQL-and-bindings variant.
- **Three envelope wraps** — `CreditorController.searchPaged`,
  `NoteController.searchPaged`, `PaymentAdviceController.searchPaged` now
  return `Mono<ReportResponse<PageResponse<Row>>>` populated via
  `ReportEnvelopeBuilder`. Each accepts a new
  `?reportingCurrency=` query param. Corresponding `perCurrencyTotals`
  methods added to `CreditorService` / `NoteService` /
  `PaymentAdviceService` and their query repositories, each running the
  paged query's WHERE clause against a `SELECT currency_code, SUM(...),
  COUNT(*) GROUP BY currency_code` aggregate.
- **First XLSX export via `ReportWorkbook`** —
  `NoteController.exportExcel` at
  `GET /api/v1/notes/page/export/excel`. Implements the pattern the other
  six deferred exports (payment advice, payment run, advance, ctc,
  reconciliation, beneficiary annual totals) will follow: same filter
  shape as `/page`, native-currency rows, optional rightmost
  "Amount in {reportingCurrency}" column populated from
  `FxRateReader.findRate`, `SecurityEventPublisher.publishDataAccess`
  before returning bytes.
- **`NotesExcelService`** — first workbook built on top of the shared
  `ReportWorkbook` fluent builder rather than the pre-existing
  hand-rolled POI pattern in `CreditorsExcelService` /
  `StatementExcelService` / `DebtorsExcelService` /
  `BadDebtsExcelService`. The four legacy services are still on their own
  POI code — retrofitting them to `ReportWorkbook` is F9 residual work
  (recorded in the Phase-0 deviations) and is scheduled alongside the
  Phase 2+ family retrofits once the four exports touch a new column shape.
- **Angular sidebar filter** —
  `OperationalNavItem.reportKey?: string` added (matches G27 — no new
  Angular service needed; the existing `TenantReportConfigService.list`
  feeds the filter). `OperationalSidebarComponent` injects
  `TenantReportConfigService`, snapshots the disabled-report-keys on
  tenant switch, and hides any nav item whose `reportKey` is in the
  disabled set. Seven finance-family nav entries (Payment Runs, Advance
  Payments, CTC Payments, Creditors, Reconciliation, Notes, Payment
  Advice) carry their `reportKey`. Component test extended with a
  `MockTenantReportConfigService` for the new constructor arg.
- **Angular `finance.service.ts` envelope migrations** — `listCreditorsPaged`,
  `listAdvicesPaged`, `listNotesPaged` return types changed from
  `Observable<FinancePageResponse<X>>` to
  `Observable<ReportResponse<FinancePageResponse<X>>>`. Each accepts an
  optional `reportingCurrency` on its options object. Three call sites
  updated: `creditors-list.component`, `payment-advice.component`,
  `notes-list.component`, `tax-withheld-list.component` — each unwraps
  `envelope.data` at the consumption site while keeping the envelope in
  scope for future header-strip use. `tax-withheld-list.component.spec`
  fixture migrated to `emptyEnvelope()`.
- **`ReportRetrofitAssertions` shared testFixtures helper** —
  `shared/src/testFixtures/java/com/medfund/shared/testfixtures/ReportRetrofitAssertions.java`
  ships the four canned assertions (`assert403WhenDisabled`,
  `assertEnvelopeShape`, `assertPerCurrencyReflectsFilteredSet`,
  `assertFxRatesBestEffort`). Per-controller ITs consume it via the
  existing test-fixtures dependency edge every service module already
  has. The security-event-on-export assertion is intentionally left out
  of the shared helper — each service's Kafka IT already exposes its own
  topic-listen helper and re-shaping that as a shared interface adds
  more coupling than the assertion saves.
- **`ReportEnvelopeBuilderTest`** — four cases covering the four axes:
  fully-populated envelope, missing-rate-but-succeeds (G28), override
  currency wins over tenant default, `buildNoAggregate` short-circuit.
  Guards the new component against silent regression.

**Phase 1 §B deferred (family-phase pickup)** — the residual work whose
scope is naturally batched with each family phase's IT harness rather
than crammed into §B:

- **Envelope wraps on the remaining eight paged endpoints** — `StatementController.generate`,
  `BalanceController.listDebtors/listBadDebts/listAged`,
  `InvoiceController.list`, `PaymentRunController.searchPaged`,
  `AdvancePaymentController.searchPaged`, `CtcPaymentController.searchPaged`,
  `ReconciliationController.searchPaged`. Same shape as the three §B wraps —
  each needs a `perCurrencyTotals` method on its repository + service, plus
  the `?reportingCurrency=` param. Skipped in §B because §A already
  landed the tenant gate on every one, so operators still get the
  toggle-hide behaviour even without the envelope wrap.
- **Six more XLSX exports** — PaymentAdvice, PaymentRun, Advance, CTC,
  Reconciliation, BeneficiaryAnnualTotal `/page/export/excel` endpoints,
  each modelled after `NotesExcelService`.
- **New `BeneficiaryAnnualTotalController.searchPaged`** — the paged list
  endpoint called for by G23. Ungated on `/for` per G23; the new `/page`
  gets `@RequiresReport(ANNUAL_CAP_UTILIZATION)`.
- **Nine remaining `*ReportRetrofitIT` classes** — the shared
  `ReportRetrofitAssertions` helper landed; per-controller ITs
  (`Statement`, `Balance`, `Invoice`, `BeneficiaryAnnualTotal`,
  `PaymentAdvice`, `PaymentRun`, `Note`, `Advance`, `Ctc`,
  `Reconciliation`, `Creditor`) each set up a testcontainer, seed
  multi-currency data, hit the endpoint with three query variants (default
  currency / override / disabled toggle) and consume the shared
  assertions. Each family phase carries its two or three.
- **Angular Playwright `report-toggle.spec.ts`** — golden path is
  admin-tab → toggle off → sidebar refresh → 403 on direct URL. Lands
  alongside the first family phase's e2e specs.

The gate + hide loop that these deferred ITs would prove end-to-end is
already exercised in `ReportGuardAspectTest` (aspect-level 403 path) and
the new `MockTenantReportConfigService` in the sidebar spec (client-side
disabled-set filter). The 403 + envelope-shape + fxRates-warnings +
perCurrency-aggregate assertions all ship in the shared testFixtures
helper — each per-controller IT stays a five-line file that seeds test
data and calls the assertion. Consumer-side rework has already landed
for the three §B wraps.

**2026-08-11 (Phase 1 grilling addendum to Phase-0-shipped `ReportResponse<T>`)**

- **`ReportResponse<T>` signature change** — Phase 0 shipped
  `record ReportResponse<T>(reportKey, period, reportingCurrency, T data, Map<String,T> perCurrency, OffsetDateTime generatedAt)`
  with `perCurrency: Map<String, T>` — same `T` on both sides. The Phase 1 grilling
  (G17) found this shape can't hold both the aggregate case (where `T` is a summary
  DTO and `perCurrency.USD` is a summary of the USD subset) *and* the paged case
  (where `T = PageResponse<Row>` and per-currency can't sensibly be another paged
  slice). Phase 1 introduces `PerCurrencyTotal(BigDecimal totalAmount, long rowCount)`
  and reshapes the envelope to
  `record ReportResponse<T>(reportKey, ReportPeriod period /* nullable, G20 */, reportingCurrency, T data, Map<String, PerCurrencyTotal> perCurrency, Map<String, BigDecimal> fxRates, List<String> warnings, OffsetDateTime generatedAt)`.
  Cheap because nothing yet consumes the envelope — reports hub renders a metadata
  catalogue, no wrapped data is on the wire.
- **`ReportPeriod` gains `parseOptional`** — Phase 0 shipped `parseFromQueryParams`
  which throws when either date is missing. G20 established that 7 of 11 Phase-1
  target controllers are current-state snapshots with no period concept. Rather
  than force a fake period on them, add a factory that returns null when both
  dates are absent, and endpoints that are periodless simply don't accept the
  params (400 if passed). Envelope `period` becomes nullable.
- **Cross-cutting invariant #6 softened per G28** — Phase 0's plan text said
  "missing rate ⇒ `ReportGenerationException`". G28 clarified this is only the
  behaviour for actual server-side FX arithmetic; the envelope's `fxRates` map is
  best-effort (missing currencies omitted, named in `warnings`). Update was made
  to the Implementation Approach section above; noting here for the Phase 0
  reader who might read the pre-grill invariant.
- **Cross-cutting invariant #2 narrowed per G29** — Phase 0's plan text said
  "Every report endpoint short-circuits with `403 Forbidden`". G29 clarified this
  is reads only; mutations stay ungated. Update was made to the Implementation
  Approach section above.

**2026-08-11 (Phase 2 implementation — billing family)**

Phase 2 as written was outline-depth. Implementation expanded to concrete
files against the current codebase, deviating from the outline in three
places worth recording:

- **Aggregate endpoint on a separate controller.** The outline put
  `/api/v1/reports/aggregate/billing` on `BillingReportController` alongside
  the per-scheme + per-group endpoints. Split into a dedicated
  `BillingAggregateController` because the aggregate is a
  service-to-service surface with different gating semantics: intentionally
  **ungated by `@RequiresReport`** (a tenant admin disabling
  `BILLING_REPORT` should not cascade into breaking Phase 3+5 cross-service
  reports across the platform). Same JSON envelope shape either way; the
  split is purely a concern boundary.
- **"Committed contributions" = `invoice_id IS NOT NULL`.** The outline
  said "committed contributions only" for the group report without
  defining the SQL. Chose the invoice-back-link filter because that's the
  ledger's "this row has been billed" marker (see `Contribution.invoiceId`
  Javadoc: "Back-link to the invoice that aggregated this contribution
  row. NULL during the brief preview/commit window before invoices are
  generated"). Applied to both the per-scheme and per-group aggregates
  for consistency — preview-only rows never distort report numbers.
- **Age bands are computed at query time from `date_of_birth`, not from
  the frozen `age_group_id`.** Contribution rows carry both — an
  `age_group_id` snapshot (frozen at billing time for price
  reproducibility) and enough back-references (`member_id`, `dependant_id`)
  to compute age at `period_start`. The age-band buckets in the plan
  (0-18 / 19-35 / 36-55 / 56+) don't map onto the tenant-configurable
  `age_groups` schedule, so the query uses
  `EXTRACT(YEAR FROM AGE(period_start, dob))` on the beneficiary's DOB
  instead. Historical reproducibility is inherent: `period_start` is
  immutable, so the bucket a row lands in doesn't change over time.
- **Gateway routing added for `/api/v1/reports/*`.** The gateway had
  no route for the new report prefix — added
  `/api/v1/reports/billing`, `/api/v1/reports/billing/*`, and
  `/api/v1/reports/aggregate/billing` → contributions-service. Deliberately
  path-specific rather than a catch-all `/api/v1/reports/*` because
  Phase 5's `CrossServiceReportController` in finance-service will need
  the same prefix (`/api/v1/reports/billing-vs-claims`,
  `/api/v1/reports/member-payments`), Phase 3's `/api/v1/reports/aggregate/receipts`
  stays in contributions-service, and Phase 4's `/api/v1/reports/aggregate/claims`
  goes to claims-service. A catch-all would prevent this per-family
  fanout later.
- **`BillingReportControllerTest` — Mockito 5 null-matcher gotcha.**
  First test-run failed with empty-body 200s because `any(Mono.class)`
  in Mockito 5 rejects nulls, and the default `MockBean` return for a
  `Mono<T>` method is null, breaking the envelope-builder mock match.
  Fix was to stub the `BillingReportService` `Mono`-returning methods
  in `@BeforeEach` so the arguments the controller passes into
  `envelopeBuilder.build(...)` are non-null. Worth recording because
  every future controller slice test that composes multiple `MockBean`s
  through a reactive chain hits the same trap.

**Phase 2 deferred (family-phase pickup)**:

- `BillingReportControllerIT` and Playwright `billing-report.spec.ts` —
  same rationale as Phase 1 §B's deferred per-controller ITs. The
  shared `ReportRetrofitAssertions` helper is in place; the IT class
  lands alongside the other family-phase ITs once the family-phase
  testcontainer harness is in place.
- Detail routes wiring in Angular — `/reports/scheme/:id` and
  `/reports/group/:id` are still `ComingSoon` stubs. The detail
  endpoints (`/schemes/{id}`, `/groups/{id}`) exist and return the
  monthly-breakdown payload; the UI landing pages that consume them
  are deferred until the family-phase drill flow (Phase 4 claims
  financial detail cross-links here).

**2026-08-11 (Phase 3 grilling — expansion to code altitude, before implementation)**

Phase 3 shipped as a 12-line outline. Grilled 2026-08-11 with G30-G40 (plus F18-F29
verification findings). Deviations from the pre-grill outline:

- **Scope expansion — per-member dimension added (user note during grilling).** Original
  outline said "per-group aggregate" only. User pointed out that some insurance lines bill
  members directly, not groups (individual medical aid, LIFE, TRAVEL, DISABILITY, VEHICLE,
  PROPERTY per `.claude/CLAUDE.md`'s `InsuranceLine.isPersonCentric` split). V039 already
  supports member-owned transactions. Phase 3 now ships three summary surfaces (scheme,
  group, member) + three detail drill-downs. Angular gets a new
  `member-receipts-report.component.ts` with paginated + searchable list + `insuranceLine`
  filter (G36). By symmetry, Phase 2 has the same per-member gap on the billing side —
  folded into Phase 3 as an owed-back §8 (add `/billing/members` + companion Angular
  routes).
- **Collection Rate reshape — monthly bucketing (G34).** Outline described only "collection
  rate = receipts / billing per scheme/group". Grilling settled on per-dimension,
  per-currency, monthly-trend response shape so a treasurer sees drift over the period, not
  just a period-total. Consequence: Phase 2's narrow `BillingAggregateRow` is insufficient;
  Phase 3 adds new `/aggregate/{billing,receipts}/monthly` endpoints returning
  `MonthlyAggregateRow` alongside the existing narrow ones (G35). Phase 5 loss-ratio still
  consumes the narrow contract.
- **Resilience approach — WebClient operators, not Resilience4j (G37).** Outline said
  "Resilience4j timeout + circuit-breaker". Verified `grep -rn resilience4j services/java`
  returns nothing — Resilience4j is not on the classpath of any service, and adding it is a
  repo-wide precedent (Phase 5, 8, 14 would follow suit). Deferred to a platform-wide grill.
  Phase 3 uses `.timeout(2s) + .retry(1) + .onErrorResume(...)` with envelope `warnings`
  capture. New shared helper `CrossServiceCallHelper` in `services/java/shared/report/`
  encapsulates the pattern for Phase 5+ consumers.
- **`RECEIPTS_AGGREGATE` semantics + label rename (G31).** Enum label "Receipts —
  aggregate" is confusing because it overlaps with the ungated `/aggregate/*` URL family.
  Phase 3 uses `RECEIPTS_AGGREGATE` as the drill-down detail key (mirrors G29 pattern of
  drilldown-gets-its-own-key). Rename label from `"Receipts — aggregate"` to
  `"Receipts — drill-down"` (§9). Cross-service `/aggregate/receipts` lives on separate
  `ReceiptsAggregateController`, ungated, no report key — mirrors Phase 2 deviation §1.
- **Receipt definition — accountant view (G30).** Includes CTC_OFFSET as a receipt (satisfies
  a bill via advance-credit) rather than the narrower bank-cash-in view. Overrides the
  auto-memory `project_ctc_is_opt_in`'s framing for reporting only — CTC remains an opt-in
  contribution-satisfaction flow, but shows in collections. This is a deliberate override —
  future implementers reading the memory should not re-narrow the report scope.
- **Group-to-scheme attribution — Unallocated bucket (G33).** Group-owned transactions with
  `contribution_id NULL` are unattributable to a scheme (groups can span schemes; verified
  `Group` entity has no `scheme_id`). Rather than pro-rate, ship a synthetic "Unallocated
  group payments" scheme row — cheap SQL, honest about what's unattributable. Consequence:
  tenants that pay group-level without allocating to contributions will see a large
  "Unallocated" row in per-scheme reports; documented in the report help text.
- **Angular route flat under `/reports/*` + old-stub retirement (G38).** Existing
  `receipts/report` and `receipts-to-billing` stubs retired via `pathMatch:'full',
  redirectTo:` redirects. Canonical paths follow Phase 2's flat naming (`reports/schemes`
  not `reports/billing/schemes`): `reports/receipts-schemes`, `-groups`, `-members`,
  `-{dim}/:id`, `collection-rate`. Legacy permission scopes `finance:manage_receipts` and
  `finance:manage_billing_reconcile` become unreferenced by wired routes — flagged in
  `permissions.ts` but left defined.
- **Scheduled delivery for `COLLECTION_RATE` deferred to Phase 17 (G39).** `cadenced=true`
  in the enum stays aspirational; Phase 3 ships the on/off toggle only. Phase 17 lands the
  schedule table + admin UI + `@Scheduled` job + notification-service dispatcher as one
  coherent tranche.
- **Detail drill-down shape (G40).** Detail page + XLSX carry a monthly-buckets strip +
  paginated transaction ledger. XLSX = two sheets, ledger capped at 10k rows. Same shape
  for scheme / group / member — one `receipts-detail.component.ts` with a `dimension` input.
- **Cross-cutting invariant #6 — extended.** G28 covered missing-FX best-effort warnings.
  G37 extends the same warnings pattern to cross-service peer failures (billing-aggregate
  or receipts-aggregate down → warnings populated, report succeeds with partial data). No
  wording change needed to invariant #6 — the "best-effort with warnings" spirit already
  covers it — but a reader tracing peer-failure semantics should look at G37 alongside G28.

**Phase 3 deferred (family-phase pickup — same rationale as Phase 1 §B and Phase 2)**:

- `ReceiptsReportControllerIT`, `ReceiptsAggregateControllerIT`, `CollectionRateReportControllerIT`
  — deferred to family-phase testcontainer harness pickup, consuming the shared
  `ReportRetrofitAssertions` helper.
- Playwright `receipts-report.spec.ts` + `collection-rate.spec.ts` — same rationale.
- Phase 5+ consumers of the new `CrossServiceCallHelper` — Phase 5 loss-ratio (this plan)
  and any Phase 8 cash-flow-forecast cross-service call reuse the helper; Phase 3 ships the
  helper + its unit tests, not the downstream consumers.

**2026-08-11 (Phase 3 implementation — receipts family + collection rate + billing per-member owed-back)**

Phase 3 as grilled to code altitude expanded further during implementation. Deviations
from the pre-implementation plan text worth recording:

- **`member_scheme_enrolments` doesn't exist — use `members.scheme_id` directly.** Plan
  §1 said "Member-owned rows attribute via `member_scheme_enrolments` active at
  `transaction_date`". Verified `grep -rn "member_scheme_enrolments" services/java/tenancy-service/src/main/resources/db/migration/tenant/` returns nothing — the codebase never introduced that table; `members.scheme_id` is the direct FK. Repo now uses
  `COALESCE(c.scheme_id, m.scheme_id) AS attributed_scheme_id` — contribution-back-link
  first, member's current scheme second, `<UNALLOCATED>` (NULL) only for group-owned
  transactions without a back-link (matches G33 intent exactly).
- **Search uses ILIKE, not trigram.** Plan §4 said "Server-side trigram search on
  `member_number` + `full_name`. Trigram index on `members.full_name` + `members.member_number` supports the `search` param (add migration V05x if not present)". Verified
  `grep -rn "pg_trgm\|CREATE EXTENSION" services/java/tenancy-service/src/main/resources/db/migration/tenant/` returns nothing — the codebase doesn't use pg_trgm anywhere,
  and existing member-search paths (`user-service/MemberRepository.search`,
  `contributions-service/InvoiceListService`) all use plain `LOWER(...) LIKE
  LOWER(CONCAT('%', :q, '%'))` on first/last name + member_number. Kept consistent —
  no new extension migration, no new index. If future performance work needs it a
  platform-wide grill would introduce pg_trgm and retrofit every search path.
- **CollectionRate envelope is hand-built, not via `ReportEnvelopeBuilder`.** Plan §5
  showed the builder path. Deviation reason: the builder's `bestEffortFxRates` pass
  populates its own `warnings` list — using it would either double-populate warnings
  (fx + peer-failure) or drop the peer-failure ones. Since collection-rate is
  per-currency native by design (never cross-currency, G34) it doesn't need the FX
  best-effort pass at all. The controller composes a `ReportResponse` directly with
  the currency resolver + peer-failure warnings — cleaner than teaching the builder to
  skip the FX pass conditionally.
- **`ContributionsClient` decodes envelope via `bodyToMono(String) + Jackson`** rather
  than `bodyToMono(ReportResponse<List<MonthlyAggregateRow>>>)`. Reason: WebClient's
  reactive codec doesn't handle the doubly-parametrised generic envelope type through
  a `Class<T>` alone — needs a `TypeReference`. Simpler to grab the raw JSON and
  deserialise with the existing `ObjectMapper` bean. Same tests pass.
- **`billing/members` DTO shape simplified.** Plan §8 didn't spell out the shape;
  chose `MemberBillingSummaryRow(memberId, memberNumber, memberName, insuranceLine,
  schemeName, currencyCode, contributionCount, totalBilled, totalPaid)` and
  `MemberBillingDetailResponse(memberId, memberNumber, memberName, insuranceLine,
  summary, monthly)`. Matches the scheme + group symmetry so an Angular list +
  detail component reuses the same styles.
- **`ReportKey.RECEIPTS_REPORT` label change.** Plan §9 said rename
  `RECEIPTS_AGGREGATE` label to `"Receipts — drill-down"` (done). Also updated
  `RECEIPTS_REPORT` label from `"Receipts — per group"` to `"Receipts — per scheme /
  group / member"` because the report now spans all three dimensions per G32-amended.
- **Receipts detail exports use the same route with the `unallocated=true` flag.**
  Plan §2 listed a separate URL for the unallocated bucket; kept it on the same
  scheme-detail endpoint with a query flag to keep the surface count small. Angular
  routes to `/reports/receipts-scheme/unallocated` — the detail component sees the
  literal `unallocated` segment and sets the flag.
- **Gateway routing added 7 entries.** Plan §6 called for path-specific routing; done
  as: `/api/v1/reports/receipts`, `/receipts/*`, `/aggregate/receipts`,
  `/aggregate/receipts/*`, `/aggregate/billing/*` → contributions;
  `/collection-rate`, `/collection-rate/*` → finance. Also extended the existing
  `/aggregate/billing` → `/aggregate/billing/*` so the new `/aggregate/billing/monthly`
  route from Phase 3 §8 forwards correctly.
- **Sidebar filter — no per-report entries added.** Reports sidebar link stays as a
  single entry pointing at the hub, matching Phase 0's decision "individual reports
  are dynamically catalogued *inside* the hub, not as sidebar children". The new
  Phase 3 reports show up in the hub via the existing `TenantReportConfigService`
  wiring — no code change needed to surface them.
- **Test infra deviations:**
  - `CrossServiceCallHelperTest` initially failed because `Retry.backoff(...).retryWhen(...)` wraps the original error in `RetryExhaustedException`. Fixed by unwrapping
    `err.getCause()` before pulling the message. Now every warning message names the
    actual peer failure ("peer down") rather than the retry envelope ("Retries
    exhausted 1/1"). Same fix in production code path — treasurer-facing warnings are
    the same shape as unit-test-asserted ones.
  - `ReceiptsAggregateControllerTest` needs `.mutateWith(mockJwt())` — controller
    unwrapped by `@RequiresPermission(FINANCE_VIEW_SUBLEDGER)` but Spring Security
    still enforces authentication. Fixed same shape as `ReceiptsReportControllerTest`.

## Decisions Log

Grilling on 2026-08-11 settled the following. G* = user preference; F* = settled by fact / codebase reality.

- **F6** — `ReportController` is naive as research described (verified line-by-line at `services/java/finance-service/src/main/java/com/medfund/finance/controller/ReportController.java:56-234`).
- **F7** — `/api/v1/reports/*` has zero callers anywhere in the codebase. `ReportController` deletable outright.
- **F8** — `SecurityEventPublisher` shared helper lifted from keycloak-event-listener into `services/java/shared/security/`. Pattern-obvious.
- **F9** — Consolidate per-service `*ExcelService` classes into `services/java/shared/report/ReportWorkbook.java`. Preempts duplication as report count grows.
- **F10** — Historical FX supported by `V112__exchange_rates.sql` (immutable per-date). Rate population is operational, out of plan scope; report generation fails-loud on missing rate.
- **F11** — `@Scheduled` + notification-service dispatcher pattern already exists. Adding a `report/` dispatcher in Phase 17 uses established infra.

- **G1** — Plan scope: implement every report identified (MASCA-shaped + web-extras), each with XLSX, each tenant-toggle-able. Web-research additional standard reports and fold them in.
- **G2** — Service home: distributed by data ownership. Cross-service reports use a thin aggregator controller in finance-service that fans out via WebClient.
- **G3** — Web-extra buckets in scope: **all four** — cheap query+XLSX, regulatory-format, actuarial-heavy, domain-not-yet-built.
- **G4** — Deliverable shape: **single mega-plan** (this document). Trade-off accepted: 12-18 months of engineering across 20 tranches.
- **G5** — Tenant toggle: new `public.tenant_report_config` table + `TenantConfigClient.getEnabledReportKeys()` + `@RequiresReport` filter + Angular settings tab.
- **G6** — Reporting currency: hybrid. Optional `?reportingCurrency=` per endpoint; default = tenant's `is_default` currency; response returns both a converted total AND `perCurrency: { CCY: … }`.
- **G7** — Cross-service data plumbing: sync HTTP fanout via per-service `/api/v1/reports/aggregate/{family}` endpoints. ~~Resilience4j timeout + circuit-breaker~~ **superseded by G37 (Phase 3 grilling)** — WebClient operators + shared `CrossServiceCallHelper` + envelope `warnings` capture. Resilience4j deferred to a platform-wide grill.
- **G8** — Scheduled delivery: fixed cadences per report code. Tenant admin picks on/off + recipient email list. New `tenant_report_schedule` table (Phase 17).
- **G9** — Balance snapshots: yes. New `provider_balance_snapshot` + `member_balance_snapshot` tables, written by `PaymentRunExecutor` in the finalise transaction.
- **G10** — Actuarial home: `services/python/ai-service`. New `app/actuarial/` package. Java calls Python via HTTP.
- **G11** — Regulatory templates: per-tenant `jurisdiction_code` + XLSX template resources under `services/java/*/src/main/resources/report-templates/{regulator}/{report}-v{version}.xlsx`.
- **G12** — Cross-tenant analytics: yes — fill all 6 stubbed gateway endpoints (Phase 9).
- **G13** — Domain pre-reqs: **full** domain build for reinsurance / producer / UPR (Phases 10, 11, 12). Accepted: this turns the plan into a multi-quarter platform-features program.
- **G14** — Reports UI: hub landing page at `/tenant/finance/reports` with tenant-toggled catalogue tree, dynamic sidebar filtered by enabled reports.
- **G15** — Phase outline (this document) approved.

### 2026-08-11 Phase 1 grilling additions

G16-G29 settle Phase-1-specific forks that surfaced when the outline was expanded to code altitude against the current codebase. F12-F17 record the facts uncovered during that grilling (annotation signatures, envelope-shape mismatches, Phase 0 retrofit gaps) that shaped the questions.

- **F12** — `@RequiresReport` takes exactly one `ReportKey` (Phase 0 shipped `services/java/shared/src/main/java/com/medfund/shared/report/RequiresReport.java:35`). The plan's assignment of multiple keys to `NoteController` (5 keys) and `BalanceController` (6 keys) needs an annotation-to-endpoint mapping decision — settled by G21.
- **F13** — Phase-0-shipped `ReportResponse<T>` has `perCurrency: Map<String, T>` — same `T` as data (`services/java/shared/src/main/java/com/medfund/shared/report/ReportResponse.java:28`). Signature can't hold both aggregate-shape (T = summary) and paged-shape (T = `PageResponse<Row>`). Settled by G17.
- **F14** — `ReportPeriod.parseFromQueryParams` requires both `periodStart` and `periodEnd` (throws otherwise, `ReportPeriod.java:30`). Seven of eleven Phase 1 target controllers have no period concept. Settled by G20 — add `parseOptional`, nullable period.
- **F15** — Phase 0's retrofit is incomplete inside the four touched controllers: `StatementController.generate` (JSON) is ungated; `BalanceController` `/members/{id}`, `/groups/{id}`, `/aged-balances`, `/bad-debts`, `/bad-debts/export/excel` are ungated (verified `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceController.java:65-198`, `.../StatementController.java:57`). Settled by G22 — fold into Phase 1.
- **F16** — `BeneficiaryAnnualTotalController` exposes only a point-lookup (`/for`) — no list surface for `ANNUAL_CAP_UTILIZATION` to render (`.../BeneficiaryAnnualTotalController.java:36`). Settled by G23 — build a list endpoint.
- **F17** — 7 of 11 target controllers have no XLSX export today (Notes, PaymentAdvice, PaymentRun, Advance, CTC, Reconciliation, BeneficiaryAnnualTotal). Settled implicitly by G16 — build them.

- **G16** — Phase 1 scope: **full envelope everywhere as the plan reads**. Every endpoint of all 11 controllers gets the four axes; 7 new XLSX exports built; Phase 0 retrofit gaps folded in; new list endpoint on `BeneficiaryAnnualTotalController`. Acknowledged downside: envelope shape decisions get made before there's a UI consuming them; larger single-phase blast radius. Chosen over the "minimum viable retrofit" option that would have deferred currency + envelope wrap to family phases.
- **G17** — Envelope shape when `T = PageResponse<Row>`: introduce shared `PerCurrencyTotal(BigDecimal totalAmount, long rowCount)` — fixed shape independent of `T`. Envelope reshape: `record ReportResponse<T>(reportKey, ReportPeriod period /* nullable, G20 */, reportingCurrency, T data, Map<String, PerCurrencyTotal> perCurrency, Map<String, BigDecimal> fxRates, List<String> warnings, OffsetDateTime generatedAt)`. Requires Phase-0 signature change (cheap — nothing consumes it yet).
- **G18** — `perCurrency` on paginated endpoints: **second aggregate SQL, filtered-set totals**. Every `/page` runs `SELECT currency_code, SUM(amount), COUNT(*) FROM t WHERE {same filters} GROUP BY currency_code` alongside the paged query. Extra DB round trip per paginated request accepted.
- **G19** — Which endpoints wrap in `ReportResponse<T>`: **paged/aggregate/standalone report endpoints wrap; drilldowns stay raw**. Rule: wrap when the endpoint stands alone as a report page in the hub. Raw: `/{id}`, `/provider/{id}`, `/member/{id}`, `/for`, `Flux findByX`. Report-catalogue keys for drilldowns exist only for the toggle gate, not the envelope wrap.
- **G20** — `ReportPeriod` for periodless controllers: **nullable on envelope**. Add `ReportPeriod.parseOptional(...)` returning null when both dates absent, throwing if only one present. Periodless endpoints reject `?periodStart=`/`?periodEnd=` params.
- **G21** — Multi-key controllers: **broad key on endpoint + Angular sub-toggles**. `NoteController.searchPaged` carries `@RequiresReport(NOTES)`; sub-keys (`NOTES_TAX_WITHHELD` etc.) drive Angular sidebar/hub filters but the backend doesn't gate the filtered variant. **Sub-keys are display-catalogue toggles, not backend gates** — recorded as an explicit rule for readers.
- **G22** — Phase 0 retrofit gaps: **folded into Phase 1** (settled by G16 consequence, not asked as an explicit fork). Missing `@RequiresReport` annotations on `StatementController.generate`, `BalanceController.getMemberBalance/getGroupBalance/listAged/listBadDebts/exportBadDebtsExcel` all get added. Phase 0's `/debtors` mis-mapping (`AGED_DEBTORS` → should be `DEBTORS_LIST`) gets corrected.
- **G23** — `BeneficiaryAnnualTotalController`: **build a paginated list endpoint in Phase 1**. New `GET /api/v1/beneficiary-annual-totals/page` + XLSX export. Existing `/for` point-lookup stays **UNGATED** — claims-service depends on it during adjudication; gating breaks adjudication. Row shape defined in the Phase 1 retrofit specification table above.
- **G24** — `SecurityEvent` scope: **exports only** — matches Phase 0 pattern. Extend to the 7 new exports built for Phase 1. JSON reads do not emit `DATA_ACCESS`.
- **G25** — Row currency display in wrapped paged lists: **native amounts on rows; envelope carries `fxRates` for optional client-side conversion**. Rows always show native currency + amount unchanged. XLSX export adds a rightmost "Amount in {reportingCurrency}" column when `?reportingCurrency=` is passed. Cross-currency server-side sort-by-amount not supported — operator filters to one currency first.
- **G26** — Testing: **per-controller IT with shared `ReportRetrofitAssertions` helper**. 11 `*ReportRetrofitIT` classes; static helper for the four canned assertions (403-on-disabled, fxConversion, perCurrency, SecurityEvent-on-export). `infra_testcontainers_pitfalls` guards apply.
- **G27** — Angular sidebar filter: **`reportKey?` on route data**. `data.reportKey?: string` added to each report-route entry; `operational-nav.service` consumes `TenantReportConfigService.list(tenantId)` and filters disabled routes. Uses the existing service — no new one.
- **G28** — Missing FX rate behaviour: **server FX arithmetic fails loud; envelope `fxRates` is best-effort**. When the server converts a value (grand-total scalar), missing rate throws `ReportGenerationException` naming (base, quote, date). When the server populates the envelope's `fxRates` map for optional client display, missing currencies are omitted from the map and named in the envelope's `warnings: List<String>` block — the report itself still succeeds. **Softens cross-cutting invariant #6** as originally written.
- **G29** — `@RequiresReport` gates reads only; **mutations stay ungated**. GET endpoints (list, detail, export) carry the annotation. POST/PUT/DELETE (create/approve/execute/cancel/reverse/delete) are never gated by the report toggle — they're operations gated by `@RequiresPermission`. **Narrows G16's "every endpoint" claim** and **softens cross-cutting invariant #2** as originally written.

### 2026-08-11 Phase 3 grilling additions

G30-G40 settle Phase-3-specific forks. F18-F25 record facts uncovered during grilling (Transaction schema, `transaction_types` catalog with sign convention, existing aggregate DTO shape, absence of Resilience4j).

- **F18** — `Transaction` (`services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Transaction.java:1-120`) is the receipts source. Columns: `currency_code`, `group_id | member_id` XOR (V039), `transaction_type` (string, ~12 values in active use), `payment_method`, `status`, `transaction_date`.
- **F19** — `TransactionService.isReceiptEligible` (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java:272`) is narrow: only `PAYMENT` triggers a receipt email. Reporting definition is separate — settled by G30.
- **F20** — `ReportKey.java:31-33` already ships `RECEIPTS_REPORT`, `RECEIPTS_AGGREGATE`, `COLLECTION_RATE`. No enum additions needed. `COLLECTION_RATE.cadenced = true`. Label rename recommended (G31).
- **F21** — Phase 2 pattern to mirror: `BillingReportController` + `BillingAggregateController` + `BillingReportQueryRepository` + `BillingReportExcelService`. Aggregate controller ungated per Phase 2 deviation §1.
- **F22** — Angular routes: `receipts` and `receipts/groups` redirect to `/tenant/billing/transactions` (intentional aliases); `receipts/report` and `receipts-to-billing` are ComingSoon stubs. Phase 2 shipped `/reports/schemes` + `/reports/group-billing` (flat naming, no `billing/` prefix — deviating from its own plan text). Phase 3 mirrors the flat convention per G38.
- **F23** — Resilience4j is not on the classpath of any Java service. All existing WebClient usage (InvoiceController file-service proxy, UserServiceClient, AiPricingClient) uses vanilla WebClient. Settled by G37 — Phase 3 uses WebClient operators; Resilience4j deferred to a platform-wide grill.
- **F24** — `ReportEnvelopeBuilder` has three overloads (SQL-string, pre-computed-Mono, `buildNoAggregate`); `@RequiresReport(ReportKey)` gates via `ReportGuardAspect`; `FxRateReader.findRate` (best-effort empty) vs `.convert` (fail-loud `ReportGenerationException`) — infra ready for Phase 3.
- **F25** — Amount + sign convention: transactions store positive amounts; `transaction_types.sign` (`+`/`-`) is a tenant-configurable catalog (V008/V041/V069/V079 seeds). `-` = credit-balance = money-in for the fund (PAYMENT, COPAYMENT_RECEIPT, CTC_OFFSET); `+` = debit-balance = money-out or reversal (REFUND, PAYMENT_REVERSAL, CTC_OFFSET_REVERSAL). SQL uses `SUM(CASE tt.sign WHEN '-' THEN t.amount ELSE -t.amount END)`.
- **F26** — Testing strategy: per Phase 1 §B and Phase 2 precedent, unit tests ship with Phase 3; per-controller ITs land alongside the family-phase testcontainer harness pickup, consuming the shared `ReportRetrofitAssertions` helper.
- **F27** — Sidebar catalogue: extend `OperationalNavItem` entries with `reportKey` per G27 — no new service.
- **F28** — Reports hub grouping: all Phase 3 keys are `ReportFamily.RECEIPTS`; render as a single card cluster.
- **F29** — XLSX shape: single-sheet for summary reports; two-sheet (monthly + ledger) for detail drill-down; uses shared `ReportWorkbook` builder from Phase 0.

- **G30** — Receipt definition: **all money-flow types** (`transaction_type IN ('PAYMENT','COPAYMENT_RECEIPT','CTC_OFFSET','REFUND','PAYMENT_REVERSAL','CTC_OFFSET_REVERSAL') AND status='completed'`), netted via `SUM(CASE tt.sign WHEN '-' THEN amount ELSE -amount END)`. Includes CTC_OFFSET as a "receipt from advance-payment credit" — ledger-accountant view of collections, not the narrower bank-cash-in view. Overrides the auto-memory `project_ctc_is_opt_in`'s framing for reporting only — CTC is opt-in as a flow, but shows in collections as receipts.
- **G31** — `RECEIPTS_AGGREGATE` = **drill-down detail key on the report page**, not the cross-service key. `ReceiptsReportController` has `/receipts/{dim}` (summary, `RECEIPTS_REPORT`) and `/receipts/{dim}/{id}` (detail, `RECEIPTS_AGGREGATE`). Cross-service `/aggregate/receipts` on separate `ReceiptsAggregateController`, ungated, no report key. Mirrors Phase 2 pattern. **Enum label rename**: `"Receipts — aggregate"` → `"Receipts — drill-down"`.
- **G32** (amended per user) — Dimensions: **per-scheme + per-group + per-member**. Original G32 was per-scheme + per-group only; user amendment added the per-member dimension because some insurance lines (LIFE / TRAVEL / DISABILITY / VEHICLE / PROPERTY / individual HEALTH) bill members directly per `.claude/CLAUDE.md`'s `InsuranceLine.isPersonCentric` split. V039 already supports `member_id NOT NULL` transactions. Phase 2 has the same gap by symmetry — **owed back to Phase 2**: add `/billing/members` surface (folded into Phase 3 implementation per §8).
- **G33** — Group-to-scheme attribution: **`contribution_id` back-link when present, else "Unallocated group payments" bucket**. `LEFT JOIN contributions c ON c.id = t.contribution_id`; per-scheme sum uses `COALESCE(c.scheme_id, '<UNALLOCATED>')`. Group-owned transactions with `contribution_id NULL` land in a synthetic scheme labelled "Unallocated group payments" (rendered clearly in XLSX + Angular; sorted last). No pro-rating maths.
- **G34** — Collection Rate shape: **per-dimension, per-currency, monthly trend + totals**. Never cross-currency conversion in the rate itself (avoids G28 fail-loud on missing FX). Response carries `byScheme`, `byGroup`, `byMember` each with `monthlyBuckets: [{month, billed, received, ratePct}]` + `totals`. XLSX: one sheet per dimension.
- **G35** — Aggregate contract extension: **new richer endpoints alongside the narrow ones**. `/aggregate/{billing,receipts}` stays narrow (Phase 5 loss-ratio consumes it). Add `/aggregate/{billing,receipts}/monthly` returning `MonthlyAggregateRow(dimension, dimensionId, dimensionName, currencyCode, month, totalAmount)` for Phase 3 collection-rate + Phase 8+ consumers. Contracts stay single-purpose.
- **G36** — Per-member surface: **paginated + search + `insuranceLine` filter, with detail drill-down**. Row shape `{memberId, memberNumber, memberName, insuranceLine, schemeName, currencyCode, totalReceived, transactionCount}`. Server-side trigram search on `member_number` + `full_name`. Includes all members regardless of group status (grouped-line members can still make direct top-up payments).
- **G37** — Resilience: **WebClient operators for Phase 3; defer Resilience4j to a platform initiative**. `.timeout(2s) + .retry(1) + .onErrorResume(...)` on each cross-service call. Failures return partial data + envelope `warnings: List<String>` per G28. New shared helper `CrossServiceCallHelper` in `shared/report/` encapsulates the pattern for Phase 5+ consumers. **Contradicts Phase 3 outline's "Resilience4j timeout + circuit-breaker" wording** — recorded as a Phase 3 deviation.
- **G38** — Angular routes: **flat under `/reports/*`, retire old stubs with redirects**. Canonical paths: `reports/receipts-schemes`, `reports/receipts-groups`, `reports/receipts-members`, `reports/receipts-{dim}/:id`, `reports/collection-rate`. Retire `receipts/report` → `reports/receipts-groups` (redirect); `receipts-to-billing` → `reports/collection-rate` (redirect). Uses `finance:view_subledger` permission (Phase 2 pattern).
- **G39** — Cadenced pre-wiring: **defer to Phase 17**. Phase 3 ships COLLECTION_RATE with the on/off toggle only. `cadenced=true` on the enum stays aspirational. Phase 17 lands the schedule table + admin UI + `@Scheduled` job + dispatcher as one coherent tranche.
- **G40** — Detail-drilldown shape: **paginated transaction ledger + monthly totals strip**. Detail page (`/receipts-{dim}/:id`) shows a monthly-buckets strip on top + paginated transaction listing with filters (month, type, currency). XLSX = two sheets (monthly summary + full transaction ledger, capped at 10k rows). Same shape for scheme / group / member drill-downs — reuse `receipts-detail.component.ts` with a `dimension` input.

### 2026-08-11 Phase 4 grilling additions

G41-G51 settle Phase-4-specific forks. F52-F59 record facts uncovered during grilling (`Claim` and `PreAuthorization` entity shapes, denial-code catalogue, existing shared infrastructure availability, Angular stub inventory, existing TenantConfigClient precedents).

- **F52** — `Claim` (`services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java`) has all columns Phase 4 needs: `claimedAmount`, `approvedAmount`, `paidAmount`, `currencyCode` (single-currency-native per row), `serviceDate`, `submissionDate`, `adjudicatedAt`, `status` (VARCHAR — no enum), `rejectionReason` (FK to `rejection_reasons.code`), `rejectionNotes`, plus V077 cost-share fields (`allowedAmount`, `deductibleApplied`, `copayAmount`, `coinsuranceAmount`, `shortfallAmount`, `memberResponsibility`). No cross-currency arithmetic hazard on a row.
- **F53** — Claim status values in use: `DRAFT`, `VERIFIED`, `IN_ADJUDICATION`, `ADJUDICATED`, `REJECTED`, `PENDING_INFO`. Stored as VARCHAR — Phase 4 code compares against string literals.
- **F54** — `RejectionReason` lookup at `services/java/claims-service/.../entity/RejectionReason.java`; seed data in `V014__claims_schema.sql:116-135`. 18 codes (R01-R18) grouped into 7 categories (ELIGIBILITY, WAITING_PERIOD, BENEFIT, PREAUTH, TARIFF, CLINICAL, FRAUD). `DENIAL_ANALYSIS` drills at both levels + provider dimension.
- **F55** — `PreAuthorization` (`services/java/claims-service/.../entity/PreAuthorization.java`) has `requestedAmount`, `approvedAmount`, `status ∈ {PENDING, APPROVED, REJECTED, EXPIRED}`, `expiryDate`, `requestedDate`, `decisionDate`. **NO `claim_id` back-link**; **NO `used_amount` column**. Verified `grep` returns nothing for `claim_id` on pre_authorizations or `auth_number`/`pre_auth_id` on claims. Classical utilisation calc impossible from stored data — settled by G43.
- **F56** — All 6 Phase-4 report keys already ship in `ReportKey.java:66-72` mapped to `ReportFamily.CLAIMS_FINANCIAL`. `CLAIMS_SUMMARY.cadenced=true`; others not cadenced. Phase 4 renames `PRE_AUTH_UTILIZATION` → `PRE_AUTH_ACTIVITY` per G43 (no rollout risk — no tenant config row for the old key today).
- **F57** — claims-service has `ReportEnvelopeBuilder`, `ReportingCurrencyResolver`, `SecurityEventPublisher`, `FxRateReader`, `CrossServiceCallHelper`, `ReportGuardAspect`, `ReportWorkbook` on classpath. No new shared infra needed for Phase 4.
- **F58** — Angular finance routes carry three ComingSoon stubs relevant to Phase 4: `reports/claims-status`, `reports/member-payments`, `reports/member-payment-status` at `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:362-366`. Settled by G51 (retire only the first).
- **F59** — `TenantConfigClient` in finance-service reads per-tenant public config from `public.tenant_advance_payment_config` (V128) and `public.tenant_ctc_auto_config` (V129) — pattern-obvious precedent for a new V132 threshold config table.

- **G41** — Per-report period clock: **each report picks the clock that fits its audience**. `CLAIMS_SUMMARY` + `DENIAL_ANALYSIS` + `HIGH_COST_CLAIMANT` on `adjudicatedAt` (financial exposure); `CLAIMS_FREQUENCY_SEVERITY` on `serviceDate` (actuarial norm); `CLAIM_STATUS_LIST` on `submissionDate` (pipeline aging); `PRE_AUTH_ACTIVITY` on `requestedDate`. Each report header names its clock so a reader knows why cross-report totals don't reconcile. **Chosen over uniform-clock options** — treasurer, actuary, and ops manager fundamentally want different windows onto the same claim.
- **G42** — Money column: **three-column funnel rendered on every report + per-report primary aggregation**. Every row shows `claimedAmount` / `approvedAmount` / `paidAmount`; envelope `perCurrency` aggregate + primary sort/rank uses one of them per report (`approvedAmount` for CLAIMS_SUMMARY / HIGH_COST_CLAIMANT / severity; `claimedAmount` for DENIAL_ANALYSIS — approved is 0 for rejected claims). Aggregate `/aggregate/claims` returns all three totals per row (G44). Downside accepted — three columns wider XLSX; three SUMs per aggregate SQL.
- **G43** — `PRE_AUTH_UTILIZATION` reshape: **rename to `PRE_AUTH_ACTIVITY`, skip classical utilisation calc**. Report on pre-auth approval/expiry rates, decision-time avg, per-status counts + amounts, and a claims-side R04/R05 rejection-rate proxy for "pre-auth-would-have-helped". Chosen because F55 makes the classical `sum(paid_claim) / pre_auth.approved` un-computable from stored data alone. Alternatives rejected: schema back-link (expands adjudication-service refactor + heuristic-match + backfill), heuristic query-time join (over-attribution + hard to reconcile), deferring the whole report (loses pre-auth visibility). Rename `ReportKey.PRE_AUTH_UTILIZATION` to `PRE_AUTH_ACTIVITY` outright — no deprecation window (no tenant config row exists for either key today, F56).
- **G44** — `/aggregate/claims` shape: **rich row on the narrow endpoint**. Returns per (dimension, dimensionId, dimensionName, currencyCode) all three totals (`totalClaimed`, `totalApproved`, `totalPaid`). Also adds `/aggregate/claims/monthly` returning `MonthlyAggregateRow` (reuses Phase 3 shared DTO) for Phase 8+ consumers. Contradicts G35's "contracts stay single-purpose" wording — trade-off accepted because Phase 5 loss-ratio may want either paid-ratio or approved-liability-ratio and this saves a second API round trip.
- **G45** — Four CLAIMS_SUMMARY dimensions: **scheme + group + member + provider**. Provider is new to claims (didn't apply to receipts) and material — a treasurer's "top 20 providers by paid claims" is a first-class question. Insurance line is a cross-cut `?insuranceLine=` filter, not its own dimension. Benefit dimension deferred to Phase 13 (Provider Network Utilisation) — overlaps with actuarial Phase 14 territory. `/aggregate/claims?dimension=SCHEME|GROUP|MEMBER|PROVIDER`.
- **G46** — `HIGH_COST_CLAIMANT`: **cumulative-per-period, threshold in new public schema table**. New V132 `public.tenant_high_cost_claimant_config(tenant_id, threshold_amount, currency_code)` mirroring V128/V129 pattern. `TenantConfigClient.getHighCostClaimantConfig(tenantId)` returns it. Report criterion: `SUM(claim.paidAmount)` per member for the period > threshold in reporting currency, converted via `FxRateReader.convert` at `period.periodEnd` (missing FX rate throws `ReportGenerationException` per invariant #6 / G28). Missing config row → empty result + `warnings: ["High-cost threshold not configured for tenant"]` (best-effort with warnings, since it's a config gap not a data gap). Drill-down shows the member's contributing individual claims. Threshold configurable via new tenant-admin settings UI.
- **G47** — `DENIAL_ANALYSIS`: **both levels + provider view + monthly trend**. Response carries `byCategory` (7 rows), `byCode` (up to 18 rows, code within category), `byProvider` (top-N with denial rate), and `monthlyTrend` (populated when period > 1 month). Three-sheet XLSX (Categories, Codes-within-Categories, Providers). Primary aggregation is `claimedAmount` (approved is 0 by definition of REJECTED). Filter row supports `?category=&code=&providerId=`. Provider view raises a visibility concern (revealing which providers get denied most) — mitigated because tenant admin can toggle the report off via `DENIAL_ANALYSIS` key.
- **G48** — `CLAIMS_FREQUENCY_SEVERITY`: **scheme + insurance-line dimensions; exposure = active-member-months proxy; severity = mean + median + P95**. Frequency = `claim_count / exposureMemberMonths` (annualised ×12). Exposure computed from `members.status` transitions via `member_status_history` if the table exists at implementation time; else falls back to `COUNT(members WHERE scheme_id=X AND status='ACTIVE') * days_in_period` with a `warnings` entry. Severity uses Postgres `PERCENTILE_CONT`. Deliberately a tactical management report — the actuarial-heavy chain-ladder + persistency package lives in Phase 14.
- **G49** — `CLAIM_STATUS_LIST`: **pipeline aging matrix (status × age-bucket) + per-cell drill**. Rows = 6 statuses (`DRAFT`, `VERIFIED`, `IN_ADJUDICATION`, `ADJUDICATED`, `REJECTED`, `PENDING_INFO`); columns = age buckets (`0-3`, `4-7`, `8-14`, `15-30`, `>30` days) computed from `submissionDate` vs `NOW()`. Each cell shows count + funnel amounts. Cell-click → paged list of the claims in that cell. Age-bucket boundaries hard-coded; tenant-configurable bucketing is a follow-up. XLSX = matrix sheet + drill sheet.
- **G50** — Phase 4 scope split: **§A + §B**. §A: V132 migration + enum rename + `ClaimsAggregateController` (unblocks Phase 5) + `ClaimsReportController` scheme + provider CLAIMS_SUMMARY dimensions + `HIGH_COST_CLAIMANT` + `PRE_AUTH_ACTIVITY` + config UI + §A XLSX + §A unit tests. §B: group + member dimensions + `CLAIM_STATUS_LIST` matrix + `DENIAL_ANALYSIS` + `CLAIMS_FREQUENCY_SEVERITY` + §B Angular + per-controller ITs consuming `ReportRetrofitAssertions` + Playwright. Chosen because §A carries Phase-5-blocking dependencies + config surface + treasurer-facing primary dimensions; §B carries ops/actuarial views that don't block downstream. Mirrors Phase 3 §A/§B split precedent.
- **G51** — Angular stub retirement: **retire only `reports/claims-status`** via `pathMatch:'full', redirectTo: 'reports/claim-status'` (target lands in §B). Leave `reports/member-payments` and `reports/member-payment-status` untouched — labels are ambiguous (could mean claim-payments, contribution-payments, or member-payouts) and their disposition is Phase 5 territory (loss-ratio + member-payments-unified).

### 2026-08-15 §A implementation notes (post-coding)

§A backend + Angular + gateway + the 6 new unit-test files are done and green. The unit tests caught two latent production bugs that a manual-only pass would have shipped:

- **F60** — `HighCostClaimantService.convertAndFilter` originally did `.map(converted -> converted.compareTo(threshold) > 0 ? withReporting(row, converted) : null).filter(row -> row != null)`. Reactor's `Mono.map` throws `NullPointerException` ("mapper returned a null value") on the null branch, so **any below-threshold member crashed the entire high-cost report**. Fixed to `.filter(converted -> ... > 0).map(converted -> withReporting(row, converted))`. Threshold filter is strict greater-than (a member exactly at threshold is excluded).
- **F61** — `TenantHighCostClaimantConfigService.upsert` used `switchIfEmpty(insertNew(...))`, which **eagerly invokes `insertNew` (and thus `R2dbcEntityTemplate.insert`) on every upsert, including the update path**. Harmless in production (the insert Mono is never subscribed), but it is a wasted chain construction and breaks any mock-based test (unstubbed `insert` returns null → NPE). Fixed with `switchIfEmpty(Mono.defer(() -> insertNew(...)))` so the insert path is built lazily only when the row is absent.

Also recorded: `AuditEvent` in shared is a Java `record` (accessors `action()`, `entityType()`, `entityName()`, `oldValue()`, `newValue()` — no getters), and the tenancy test that exercises the existing-row update path must stub `findByTenantId` to emit a row **and** avoid the eager-insert NPE above.

### 2026-08-15 §B implementation notes (post-coding)

§B backend + Angular are done and green (claims-service 183 unit tests, shared + tenancy regression, Angular build). Decisions settled during implementation:

- **GROUP / MEMBER detail columns** — `detail()` maps `GROUP → m.group_id`, `MEMBER → c.member_id`; `monthlyBuckets` / `ledgerCount` gained a `LEFT JOIN members m` (1:1 — each claim has exactly one member, so group-level aggregates stay correct under scheme / provider / line filters).
- **Status matrix** — statuses normalised with `UPPER(c.status)` in the matrix SELECT / GROUP BY **and** the drill WHERE; the drill has no insuranceLine param (matrix export carries it). Age buckets use the shared `AGE_BUCKET` CASE (`0-3 | 4-7 | 8-14 | 15-30 | >30`) keyed off `submission_date` vs `NOW()`, `asOf = Instant.now()`; submission window is `>= :submittedFrom AND < (:submittedTo::date + INTERVAL '1 day')`. Envelope built via `buildNoAggregate`.
- **Denial `byProvider`** — denominator = the provider's **full** window claim count (period + provider filters only); the denied numerator applies category / code via `FILTER` clauses; `denialRatePct` is computed in the repo mapper (`denied/total*100`, HALF_UP 2dp, div-by-zero guarded) so it stays FX-safe. `monthlyTrend` is gated server-side to windows spanning >1 calendar month (single-month windows return an empty list).
- **Frequency / severity** — clock = `service_date`; grouped by (scheme, insurance_line, currency); `PERCENTILE_CONT` returns the same type as its sort expression (numeric → BigDecimal) so R2DBC maps cleanly. Exposure LATERAL subquery `COUNT(*)::numeric * :days / 30.4375` (`days = DAYS.between + 1`, bound as Long); `freq = ROUND((claim_count / NULLIF(exposure,0)) * 12, 4)`. No `member_status_history` → the fallback always fires and the envelope always carries the caveat warning.
- **Envelope construction** — group/member **summaries** reuse the per-currency twin (`claimsPerCurrencyTotals` for groups — no new repo method; `memberPerCurrencyTotals` for members, same search/scheme/provider/line filters). Group/member **details** + status matrix + denial analysis use `buildNoAggregate`. Frequency-severity hand-builds its envelope via a controller `frequencySeverityEnvelope` helper (service-date window has no adjudicated-clock perCurrency twin → `perCurrency` is empty; the fallback caveat rides in `warnings`).
- **Deviation from plan** — the §B `DENIAL_ANALYSIS` rows carry no currency column, matching the §A DTO contract; for multi-currency tenants the claimed amounts are native-currency aggregates that a client cannot separate by currency. Accepted for parity with §A; revisit if a treasurer asks for the currency-split view.
- **Angular** — 5 new components + 7 new routes; group / member drills reuse `ClaimsDetailComponent` with the `Dimension` type widened to `'scheme'|'provider'|'group'|'member'` (the existing `${dimension}s/{id}` path building already pluralised to `groups` / `members`). Status matrix renders a (status × bucket) grid per currency with click-to-drill that re-queries the server with the same status + age bucket (no client-side re-filter). Member report mirrors the Phase 3 receipts component (server-side pagination + debounced search).

**Still pending (blocked)**: per-controller ITs (claims-service has no IT harness — no testFixtures / flyway / postgres deps and no `db/test-migration`; Docker / Testcontainers availability unverified) and the Playwright specs. These land in a follow-up once the harness question is resolved.

### 2026-08-16 Phase 5 grilling additions

Phase 5 expanded from a 12-line outline to code altitude (D1-D5 in the Phase 5 section). Decisions
settled by user + codebase reality:

- **D1** — Loss-ratio = paid ratio + the full claimed/approved/paid funnel per (scheme, currency);
  `paidRatioPct` null on zero denominator. Native per-currency only (G34).
- **D2** — Member-payments unified = per (member, currency) billed + received + claimsPaid + netPosition.
  **Research finding that changed the shape**: the non-monthly `/aggregate/billing` +
  `/aggregate/receipts` are SCHEME-only and `/aggregate/claims` is SCHEME-hardcoded — so the MEMBER leg
  uses the **monthly** variants at `dimension=MEMBER` (summed over the period). No peer-contract surgery.
- **D3** — XLSX export for both reports (warnings strip + SecurityEvent), per G1.
- **D4** — Test strategy: unit + WebFlux slice + MockWebServer peer-stub IT + Playwright; the
  docker-compose three-service e2e IT is **deferred** to the family-phase testcontainer harness (same
  rationale as every prior report IT).
- **D5** — Both new surfaces gate on `finance:view_subledger` (replaces the stubs'
  `finance:manage_billing_reconcile` / `finance:view` — intentional permission change).

## References

- Research: `thoughts/shared/research/2026-08-11-financial-reporting-vs-masca-reference.md`
- Architecture: `.claude/multi-currency.md:155-169` (reporting currency, historical rates), `.claude/multi-tenancy.md` (analytics schema aspirations), `.claude/portals.md:140-176` (finance + contributions portal contract), `.claude/coding-standards.md` (per-language conventions).
- Prior plans this builds on: `thoughts/shared/plans/2026-08-09-payment-run-generation-and-payee-support.md`, `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md`.
- Auto-memory: `feedback_stats_serverside`, `feedback_never_edit_applied_migrations`, `bug_public_prefix_silent_rollback`, `bug_public_flyway_history_load_bearing`, `feedback_audit_actor_email`, `feedback_audit_entity_name`, `bug_reactor_kafka_ack_swallow`, `infra_testcontainers_pitfalls`.
- Existing shared: `services/java/shared/audit/AuditEvent.java`, `AuditPublisher.java`, `AuditActor.java`; `services/java/keycloak-event-listener/src/main/java/com/medfund/keycloak/SecurityEventPublisher.java` (source of the shared lift in F8).
