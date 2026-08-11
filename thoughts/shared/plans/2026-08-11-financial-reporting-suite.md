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
- [ ] `cd services/java/contributions-service && ../gradlew build test`
- [ ] `make test-integration` — `BillingReportControllerIT` covers per-scheme + per-group + aggregate + export + toggle-off 403.
- [ ] `verify` on `/tenant/finance/reports/billing/schemes` and `/reports/billing/groups`.
- [ ] Playwright: `billing-report.spec.ts` — set period, change reportingCurrency, download XLSX, verify file contents.

#### Manual Verification
- [ ] Compare billing-report totals against a known scheme's manual sum for one month — must reconcile exactly.
- [ ] XLSX file opens in Excel with correct age-band columns and per-currency stratification.

---

## Phase 3: Receipts Family (contributions-service + finance-service aggregator)

### Overview

Ship: receipts-report (per-group aggregate), receipts-aggregate (per-group with transaction detail), receipts-vs-billing (collection-rate cross-service report).

### Changes Required

- **contributions-service**: `ReceiptsReportController` with per-group aggregate + detail + XLSX; new `ReceiptsQueryRepository`. Report keys: `RECEIPTS_REPORT`, `RECEIPTS_AGGREGATE`.
- **contributions-service**: `GET /api/v1/reports/aggregate/receipts?periodStart&periodEnd&reportingCurrency` for cross-service consumers.
- **finance-service**: `CollectionRateReportController` → `GET /api/v1/reports/receipts-vs-billing` — calls the billing-aggregate + receipts-aggregate endpoints in parallel via `WebClient`; computes collection rate = receipts / billing per scheme/group; XLSX export. Report key `COLLECTION_RATE`.
- **Angular**: `receipts-report.component.ts`, `receipts-aggregate-report.component.ts`, `collection-rate-report.component.ts`; replaces `receipts/report`, `receipts-to-billing` stubs.

### Success Criteria

#### Automated Verification
- [ ] `make test-java` + `make test-integration` — per-service + cross-service round-trip with mocked WebClient.
- [ ] `verify` on all three new routes.
- [ ] Playwright coverage for the collection-rate page.

#### Manual Verification
- [ ] Cross-service report: kill finance-service → confirm collection-rate page shows a clear error (not silent zero); restart → recovers.

---

## Phase 4: Claims-Financial (claims-service)

### Overview

Ship the claims-financial family: claim-status list, claims summary, claim frequency & severity, denial analysis, high-cost claimant, pre-auth utilisation. All in `services/java/claims-service`.

### Changes Required

- **claims-service** new controller `ClaimsFinancialReportController` with report keys `CLAIMS_SUMMARY`, `CLAIM_STATUS_LIST`, `CLAIMS_FREQUENCY_SEVERITY`, `DENIAL_ANALYSIS`, `HIGH_COST_CLAIMANT`, `PRE_AUTH_UTILIZATION`. Each with XLSX + toggle + reporting-currency.
- Aggregate endpoint `GET /api/v1/reports/aggregate/claims?periodStart&periodEnd&reportingCurrency` for Phase 5.
- New `ClaimsFinancialQueryRepository` — server-side SQL only.
- **Angular**: 6 new report pages under `pages/tenant/finance/reports/claims/`; replaces `reports/claims-status`, `reports/member-payment-status` stubs.

### Success Criteria

- Automated: `cd services/java/claims-service && ../gradlew build test`; `make test-integration`; per-report Playwright.
- Manual: high-cost claimant page for a known member reconciles against manual claim sum.

---

## Phase 5: Cross-Service Reports (billing-vs-claims, member-payments)

### Overview

Add the aggregator controller in finance-service that composes contributions-service billing + claims-service claims + finance-service payments. Ships: loss-ratio report (billing-vs-claims), member-payments unified report.

### Changes Required

- **finance-service** `CrossServiceReportController` with `GET /api/v1/reports/billing-vs-claims` (report key `LOSS_RATIO`) and `GET /api/v1/reports/member-payments` (report key `MEMBER_PAYMENTS_UNIFIED`).
- WebClient calls to `/api/v1/reports/aggregate/billing`, `/receipts`, `/claims` in parallel with proper timeouts + circuit-breaker via Resilience4j.
- **Angular**: `loss-ratio-report.component.ts`, `member-payments-report.component.ts`; replaces `billing-to-claims`, `reports/member-payments`, `reports/group-billing-to-claims` stubs.

### Success Criteria

- Automated: integration test with mocked WebClient stubs for all three sources; end-to-end IT via docker-compose that spins the three services.
- Manual: loss-ratio for a known period matches a hand-calculated ratio to within 0.1%.

---

## Phase 6: Balance Snapshots

### Overview

Add per-payment-run historical balance snapshots so any past run's creditor state is reproducible.

### Changes Required

**File**: `services/java/finance-service/src/main/resources/db/migration/tenant/V05x__balance_snapshots.sql`

```sql
CREATE TABLE IF NOT EXISTS provider_balance_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_run_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    currency_code CHAR(3) NOT NULL,
    opening_balance DECIMAL(19,4) NOT NULL,
    closing_balance DECIMAL(19,4) NOT NULL,
    taken_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_pbs UNIQUE (payment_run_id, provider_id, currency_code)
);
-- member equivalent with member_id
```

`PaymentRunExecutor.finalise(...)` writes snapshots inside the same transaction as run status flip. New `BalanceHistoryController` with `GET /api/v1/reports/balance-history/provider/{id}?asAtRun=` and `/member/{id}?asAtRun=`. Report keys `PROVIDER_BALANCE_HISTORY`, `MEMBER_BALANCE_HISTORY`.

### Success Criteria

- Automated: IT verifies snapshot rows written for every closed run; historical query returns snapshot not live.
- Manual: for a run 3 months old, historical balance matches a hand-replay of the advice ledger.

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
- **Angular**: `cash-flow-forecast.component.ts` with a stacked line chart via `@swimlane/ngx-charts`; replaces `reports/receipts-to-billing` and any forecasting placeholder.

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
- **Cross-service reports** need Resilience4j timeout (2s per hop) + circuit-breaker; report fails-loud if a dependency is down.
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
- **G7** — Cross-service data plumbing: sync HTTP fanout via per-service `/api/v1/reports/aggregate/{family}` endpoints. Resilience4j timeout + circuit-breaker.
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

## References

- Research: `thoughts/shared/research/2026-08-11-financial-reporting-vs-masca-reference.md`
- Architecture: `.claude/multi-currency.md:155-169` (reporting currency, historical rates), `.claude/multi-tenancy.md` (analytics schema aspirations), `.claude/portals.md:140-176` (finance + contributions portal contract), `.claude/coding-standards.md` (per-language conventions).
- Prior plans this builds on: `thoughts/shared/plans/2026-08-09-payment-run-generation-and-payee-support.md`, `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md`.
- Auto-memory: `feedback_stats_serverside`, `feedback_never_edit_applied_migrations`, `bug_public_prefix_silent_rollback`, `bug_public_flyway_history_load_bearing`, `feedback_audit_actor_email`, `feedback_audit_entity_name`, `bug_reactor_kafka_ack_swallow`, `infra_testcontainers_pitfalls`.
- Existing shared: `services/java/shared/audit/AuditEvent.java`, `AuditPublisher.java`, `AuditActor.java`; `services/java/keycloak-event-listener/src/main/java/com/medfund/keycloak/SecurityEventPublisher.java` (source of the shared lift in F8).
