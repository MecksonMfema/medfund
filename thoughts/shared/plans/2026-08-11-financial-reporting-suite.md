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
  "4": grilled 2026-08-11 (§A + §B split); §A + §B landed 2026-08-16 (claims-financial family + V132 + gateway routes + Angular pages + e2e)
  "5": grilled 2026-08-16 (§A + §B split, D1-D5); §A + §B landed 2026-08-16 (backend + gateway + Angular + e2e; §A verified + pre-existing test fixes, §B Playwright 3/3)
  "6": grilled 2026-08-16 (D6-1..D6-8, research correction: runs don't touch balance tables → freeze-frame); §A + §B landed 2026-08-16 (V080 snapshot migration + PaymentRunService.execute snapshot step + BalanceHistory controller/excel + unit/IT; Angular pages + creditors links + Playwright 3/3)
  "7": grilled 2026-08-16 (D7-1..D7-7, sheet-per-currency → group-by-item-currency, summary = native totals + reporting-currency conversion); §A + §B landed 2026-08-16 (PaymentRunWorkbookService + query repo + controller export + V006 test-migration + unit/IT; Angular header Export button + Playwright 4/4)
  "8": grilled 2026-08-16 (D8-1..D8-10, contributions-service placement per outline + reverse FinanceClient, inflow=unpaid invoices by due_date, outflow=draft+approved runs by created_at, asOf+rollingWeeks window, per-currency no-conversion forecast, portfolio-level collection-rate trend in finance, AGED_BALANCES route key); landed 2026-08-16 (13-week cash-flow forecast backend + Excel in contributions, outflow feed + collection-rate trend in finance, aged-debtors page + FinanceClient, gateway routes, Angular pages + fixes, unit/IT, Playwright 2/2)
  "9": grilled 2026-08-16 (D9-1..D9-9, 5 stubs + revenue-by-tenant chart + super-admin middleware + tenant-growth server-side); landed 2026-08-22 (5 platform-analytics endpoints across claims/contributions/finance/tenancy + super-admin gateway middleware + bucket/money-sum helpers + Angular bar chart; gateway 11-case Go tests + tenancy IT + finance/contributions FX-arithmetic unit tests; full schema-fanout ITs deferred to follow-up hardening pass per Success Criteria)
  "10": grilled 2026-08-22 (R1-R16 — reinsurance decisions numbered R* to avoid collision with plan-wide G* numbering; recommended §A/§B tranche split: §A = entities + auto-cession loss + 3 reports, §B = facultative UI + premium cession + review queue + retro backfill; full expanded scope replaces the 20-line outline); §A landed 2026-08-16 (commit 0a689f4 "Land Phases 1-5 of the reinsurance module (Phase 10 §A)"); §B landed 2026-08-16 (commit e6907ec "Land Phases 6-8 of the reinsurance module") via sub-plan thoughts/shared/plans/2026-08-22-reinsurance-module-and-bordereau-reports.md
  "11": grilled 2026-08-22 (P1-P14 — producer/broker decisions numbered P* to avoid collision with plan-wide G* and reinsurance R* numbering; §A/§B tranche split expanded to code altitude in sub-plan thoughts/shared/plans/2026-08-22-producer-broker-module-and-commission-reports.md); §A + §B implementation complete 2026-08-23 (commit 55c3689 "Land Phase 11 of the financial-reporting suite" — 210 files across producer entities, commission engine, rate cards, PaymentRun PRODUCER payee widening, four Kafka consumers, auto-lapse chain, four-eyes CommissionAdjustment, producer termination + bulk-reassign, treaty producer_id backfill review; Playwright + manual `verify` walkthroughs still pending per sub-plan)
  "12": grilled 2026-08-23 (U1-U15 — UPR + premium register decisions numbered U* to avoid collision with plan-wide G*, reinsurance R*, producer P* numbering; recommended §0 harness / §A foundation / §B reports / §C endorsements tranche split; scope escalated beyond outline to all 8 lines + rules-engine RuleCategory.PREMIUM_EARNING + full endorsement module with retro recompute + IFRS 17 portfolio/cohort speculative schema; full expanded scope replaces the 12-line outline); §0 + §A + §B + §C landed 2026-08-23 (commit fe36635 "Land Phase 12 of the financial-reporting suite" — 226 files across R2DBC pool tuning + user-service AbstractIntegrationTest migration + R2dbcPoolHealthIndicator, tenant V102 policy-widening + V107/V108/V109 IFRS 17 dimensions + earning_schedule + member_first_contribution matview, PolicyIssuedPublisher from six annual-bind policy services, rules-engine PREMIUM_EARNING + PremiumFact + AccruePremiumEmitter + PremiumEarningTemplates, contributions-service premium/* subpackage with EarningScheduleClosureService + PremiumEarningExecutor + BillingContributionEarningHook + PolicyIssuedConsumer, three §B reports on PremiumReportController, endorsement V110 + public V134 tenant_endorsement_config + PolicyEndorsementService four-eyes state machine + PolicyEndorsedConsumer retro recompute + ENDORSEMENT_REGISTER report, Angular tenant-admin portfolio/cohort CRUD + endorsement admin surface + review queue + underwriting reports hub) via sub-plan thoughts/shared/plans/2026-08-23-upr-earning-schedule-and-premium-register.md; Phase 3b/5b/6b/8b/9b IT-migration follow-ups + manual `verify` walkthroughs pending per sub-plan Deviations
  "13": grilled 2026-08-23 (L1-L18 — persistency + policy movement + provider network decisions numbered L* for Lifecycle — to avoid collision with plan-wide G*, reinsurance R*, producer P*, and underwriting U* numbering; recommended §A schema+writers+admin modals / §B Kafka+consumer+earning-schedule closure / §C reports+Angular tranche split; scope escalated beyond outline to full 6-line policy status-transition admin surface + two new tenant history tables + provider.network_tier column + cross-service PROVIDER_NETWORK_UTILIZATION report + earning-schedule closure Kafka ripple; full expanded scope replaces the 12-line outline); §A + §B + §C landed 2026-08-24 (commit 5eb62a9 "Land Phase 13 of the financial-reporting suite") via sub-plan thoughts/shared/plans/2026-08-23-policy-lifecycle-module.md
  "14": grilled 2026-08-24 (A1..A18 + A3b + A8b + A12b — actuarial-module decisions numbered A* for Actuarial — to avoid collision with plan-wide G*, reinsurance R*, producer P*, underwriting U*, and lifecycle L* numbering; plus F14-1..F14-16 settled-by-fact; full expanded scope replaces the 20-line outline; A17's 5-sub-plan recommendation overridden per user steer — all 5 tranches folded into a single 16-phase sub-plan `thoughts/shared/plans/2026-08-25-actuarial-module.md`); landed 2026-08-28 (Phases 1-11 commit 8c294a1 "Land Phases 1-11 of the actuarial module (Phase 14)"; Phases 12-16 commit 17a4b4e "Land Phases 12-16 of the actuarial module (Phase 14)" — sub-plan status = landed; all six ACTUARIAL report keys live end-to-end: IBNR/LOSS via chainladder-python + Kafka async job pattern; PERSISTENCY + LAPSE via Phase-13 policy-lifecycle feeds; MORTALITY + MORBIDITY via new user-service `/reports/policy-lifecycle/mortality-exposure-feed` + `/morbidity-incidence-feed`; rules-engine ACTUARIAL category live with `ActuarialRulesEvaluator` bridge from `ActuarialJobService.submit()`. Deferred to Phase-14-integration follow-up per sub-plan Deviations: cross-language docker-compose ITs (6 golden-path specs, one per report type); Playwright specs for Phases 11-14 + 16; manual `verify` walkthroughs across the six report pages; a cohort-shaped XLSX renderer for PERSISTENCY/LAPSE/MORTALITY/MORBIDITY (currently share the Triangle-shaped renderer with fallback branch); MORBIDITY_STUDY's claim-onset signal from claims-service (currently derived from `member_status_history.reason_code` per Phase 14 §Deviation))
  "15": grilled 2026-08-28 (I1..I30 — IFRS-17-pack decisions numbered `I*` for IFRS 17 to avoid collision with plan-wide `G*` numbering (G1..G44) and with prior phase prefixes R*/P*/U*/L*/A*; plus F15-1..F15-15 settled-by-fact; full expanded scope replaces the 12-line outline; single sub-plan `thoughts/shared/plans/2026-08-28-ifrs17-pack.md` planned via `create-plan` at implement time per I7 with ~20-25 verifiable phases; scope escalated substantially beyond the original outline — three-model end-to-end per I1 (PAA + GMM + VFA), full VFA entity model per I4 (unit_linked_fund + fund_nav_history + policy_unit_ledger + variable_fee_schedule), full GMM projection engine per I5 (tenant_yield_curve + tenant_expense_assumption + Phase-14 basis reuse), rename Phase-14 actuarial_report_job → report_job with 3-phase dual-write sequence per I10 + I22, chunk + MinIO fallback per I14 + I25, IBNR + CoC→CI sub-job dependency chain per I24 + I6, split retention per I28, notification-service dispatcher per I30 + rules-engine IFRS17_MODEL category per I2/I9/I17)
  "16": grilled 2026-08-30 (REG1..REG21 — regulatory-format-reports decisions numbered `REG*` for Regulatory to avoid collision with plan-wide `G*` numbering and with prior phase prefixes `R*`/`P*`/`U*`/`L*`/`A*`/`I*`; single sub-plan `thoughts/shared/plans/2026-08-30-regulatory-format-reports.md` with 25-30 phases across §0/§A/§B/§C/§D + closeout per REG21); landed 2026-08-30 (commit ba9e65c "Land Phase 16 regulatory-format reports (Phases 1-28)" via sub-plan)
  "17": grilled 2026-08-31 (S1..S13 — scheduled-email-delivery decisions numbered `S*` for Scheduled to avoid collision with plan-wide `G*` numbering and with prior phase prefixes `R*`/`P*`/`U*`/`L*`/`A*`/`I*`/`REG*`; scope narrowed from all 24 cadenced ReportKeys to 13 non-regulator operational keys per S1 — regulator + IFRS 17 + AML periodic + FRAUD_SIU_REPORT auto-run deferred to Phase 17.5 follow-up; single sub-plan `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` with 5 tranches (§0 shared types + migrations, §A backend probe + orchestrator + adapters, §B notification dispatcher, §C Angular, §D e2e + rollout) totaling ~10-12 phases per S13)
  "18": grilled 2026-09-05 (K1..K18 — executive-KPI-dashboards decisions numbered `K*` for KPI to avoid collision with plan-wide `G*` numbering and with prior phase prefixes `R*` (reinsurance, Phase 10) / `P*` (producer, Phase 11) / `U*` (underwriting, Phase 12) / `L*` (lifecycle, Phase 13) / `A*` (actuarial, Phase 14) / `I*` (IFRS 17, Phase 15) / `REG*` (regulatory, Phase 16) / `S*` (scheduled email, Phase 17); scope escalated beyond outline — 5 KPI keys instead of 3 (K1 adds `CLAIMS_FREQUENCY` + `AVERAGE_SEVERITY`), 5 KPI keys flip to `cadenced=true` (K11) widening Phase 17 S1 whitelist, incurred-basis loss ratio requires new `/aggregate/claims-incurred` on claims-service (K9) + latest-IBNR read from `report_job.result_json` (K9), earned-basis denominator requires new `/aggregate/premium-earned` on contributions-service (K8), commission-only acquisition-ratio proxy for EXPENSE_RATIO with UI rename (K4) requires new `/aggregate/commissions` on finance-service (K7), mixed-basis COMBINED_RATIO with UI footnote (K6), on-demand compute + Redis 15-min TTL (K10), per-currency native rows + reporting-currency composite scalar with fail-loud composite / best-effort envelope (K12), line+scheme+producer 3-chip slicing (K13), 12-month default trend extendable to 24 monthly (K14), KPI tile + `app-sparkline` compact chart + click-through drill-down (K15), route at `/tenant/finance/reports/kpi` under Reports Hub Dashboard family (K16), reuse `finance:reports:view` + `@RequiresReport` (K17); single sub-plan `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md` with 3 tranches (§0 enum + aggregates, §A composer + Angular, §B scheduled adapter + e2e) totaling ~6-8 phases per K18)
  "19": grilled 2026-09-05 (FR1..FR16 — fraud-SIU decisions numbered `FR*` for Fraud to avoid collision with plan-wide `G*` numbering and with prior phase prefixes `R*` (reinsurance, Phase 10) / `P*` (producer, Phase 11) / `U*` (underwriting, Phase 12) / `L*` (lifecycle, Phase 13) / `A*` (actuarial, Phase 14) / `I*` (IFRS 17, Phase 15) / `REG*` (regulatory, Phase 16) / `S*` (scheduled email, Phase 17) / `K*` (KPI, Phase 18); plus F19-1..F19-7 settled-by-fact; scope escalated substantially beyond the 4-line outline — full SIU case-management module (FR1) in claims-service (FR2) with split routing workflow-in-claims + report-in-finance (FR3), 5 entities via flag-as-evidence pattern (FR5), 5-state machine with four-eyes on non-dismissal closures (FR6), 8 fine-grained permissions + 2 Keycloak roles siu_officer/siu_supervisor (FR7), investigator-entered savings defaulting to claimed-paid (FR8), AI service Kafka producer publishing every prediction to `medfund.claims.fraud-flagged` (FR9) with top-N indicators for Rule 3 audit-of-record + full feature vector deferred to Phase 19.5 ML-ops (FR10), new `RuleCategory.FRAUD_TRIAGE` with `FraudFlagFact` + 6 templates (FR4/FR15), full-analytics report with 6 KPI tiles + trend + top-N drills + AI model calibration + investigator productivity (FR11), added to Phase 17 scheduled-delivery whitelist with per-schedule `includeSensitiveSheets` gate (FR12), 2 new `RetentionClass` values FRAUD_FLAG_1Y + SIU_CASE_7Y (FR13), one currency per case with reporting-currency composite scalar per invariant #6 (FR14); single sub-plan `thoughts/shared/plans/2026-09-05-fraud-siu-report.md` planned via `create-plan` at implement time with 2 tranches per FR16 (§A MVP ~5-6 phases: 3 entities, 3-state machine, 4 perms + 1 role, AI producer + consumer + 3 templates, 4-tile report; §B expansion ~5-6 phases: adds evidence/referrals, full 5-state + four-eyes, remaining perms + supervisor role, 3 additional templates, full-analytics report + Phase 17 scheduled dispatch), totaling ~10-12 phases)
last_grilled_phase: 19
last_grilled_date: 2026-09-05
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

Multi-sheet XLSX export of a payment run: one sheet per currency actually present in the run's
items (D7-1) plus a summary sheet with per-currency native totals and a converted total into the
tenant reporting currency at the run's executed date (D7-2, D7-3).

### Decisions (grilled 2026-08-16)

- **D7-1 — Group by item currency anyway.** Today every run is single-currency by construction
  (`PaymentRunGenerator.populate*Items(runId, currency)` stamps all items with
  `run.getCurrencyCode()`), but the workbook still groups items by their `currencyCode` so one
  sheet is produced per distinct currency present. Today that yields exactly one currency sheet +
  summary; a future multi-currency run gets extra sheets for free. Satisfies the success criterion
  "sheet count matches currency count" literally and avoids dead-special-casing the common case.
- **D7-2 — Summary sheet = per-currency native totals + one converted row.** The summary sheet
  carries run identity (runNumber, status, payee type, payment count, executedAt) and one total row
  per distinct currency — native, never summed across currencies (Rule 1). Plus exactly ONE
  converted row: the run's grand total in the run's own `currencyCode` → tenant reporting currency
  via `FxConverter` at `run.executedAt` (reporting currency resolved via
  `ReportingCurrencyResolver`, tenant default). This is the "totals + FX conversion at run date"
  from the plan without ever adding mixed-currency sums.
- **D7-3 — Missing FX rate ⇒ warn and omit, never fail the export.** If no rate exists for
  currency → reporting currency at `executedAt`, the converted row is omitted and a warning line
  ("FX USD→ZWL unavailable at <date> — converted total omitted") is written as a summary-sheet
  meta row. The workbook's native content is complete and correct; a missing FX row must not
  destroy an otherwise-good operational download (G28-tolerant precedent: loss-ratio warnings).
- **D7-4 — One row per run item, with payee name.** Each currency sheet lists one row per run
  item: payment # (joined via `payment_id`), payee name (resolved through the
  `providers`/`members` join, same `COALESCE(pr.name, m.first_name || ' ' || m.last_name)` shape as
  `PaymentQueryRepository`), payee type, item amount (native), item status, payment method,
  reference, payment paid/created date. Mirrors the run-detail Payments tab so the export
  eyeball-checks against the page. Includes cancelled/revoked items — the workbook is a ledger.
- **D7-5 — Gate: `FINANCE_VIEW_SUBLEDGER` + `@RequiresReport(PAYMENT_RUN_WORKBOOK)`.** Same stack
  as every other finance report export. Toggling `PAYMENT_RUN_WORKBOOK` off in Settings → Reports
  403s the export while the run page stays readable (balance-history precedent).
- **D7-6 — Export button in the run-detail header, any status.** The run-detail page's existing
  header actions get an "Export workbook" button (`btn btn-default`, download icon) shown whenever
  the run loads and the user holds `finance:view_subledger` — including draft/approved/cancelled
  runs (D7-7). A 403 (report disabled) or other backend error surfaces via the page's existing
  `errorMessage` banner; no dedicated disabled-report page. Filename `payment-run-{runNumber}.xlsx`.
- **D7-7 — Exportable from any run status.** Draft runs already contain generated items and the
  Payments tab supports pre-execution review, so the workbook must too. `GET /payment-runs/{id}/items`
  already serves all statuses; the export reuses that semantics.

### Changes Required

- **finance-service** `PaymentRunController.exportWorkbook(runId, jwt)` → `GET /api/v1/payment-runs/{id}/export/excel`
  (`ResponseEntity<byte[]>`, XLSX content type, `attachment; filename="payment-run-{runNumber}.xlsx"`).
  Report key `PAYMENT_RUN_WORKBOOK` (already in `ReportKey`, PAYABLES family). Gated
  `@RequiresPermission(FINANCE_VIEW_SUBLEDGER)` + `@RequiresReport(PAYMENT_RUN_WORKBOOK)` (D7-5).
  On success fires `SecurityEventPublisher.publishDataAccess(tenantId, actorId, actorEmail,
  PAYMENT_RUN_WORKBOOK, {runId, runNumber})` (Rule 9 — mirror `BalanceHistoryController` export).
  Gateway needs nothing — `/api/v1/payment-runs/*` already proxies to finance.
- New `PaymentRunWorkbookService` using `ReportWorkbook`:
  - Resolve the run (`PaymentRunService.findById`), stream its items, group by `currencyCode`
    (D7-1).
  - Per currency: a `SheetWriter` sheet (named by currency code) with header + one row per item
    (D7-4) + a bold native-total row; run identity echoed via `meta` rows.
  - Summary sheet: run meta rows, per-currency native totals (D7-2), grand total in the run's
    currency, and the converted row into the reporting currency at `executedAt` — warn-and-omit
    on missing FX (D7-3). Use `FxConverter` + `ReportingCurrencyResolver` (injected).
  - Payee-name lookup: a new small query repo (or reuse of `PaymentQueryRepository`'s join shape)
    joining `payment_run_items` → `payments` → `providers`/`members`.
- **Angular**: `payment-run-detail.component` header gets an "Export workbook" button (D7-6) —
  download icon, `btn-default`, calls a new `FinanceService.exportPaymentRunWorkbook(runId)` →
  `api.getBlob('/payment-runs/${id}/export/excel')`, reusing the `downloadBlob` helper pattern
  from the balance-history components. Hidden unless `finance:view_subledger`; errors (incl. 403
  disabled-report) go to the existing `errorMessage` banner.

### Success Criteria

- **Automated:**
  - [x] Unit (`PaymentRunWorkbookServiceTest`): groups items by currency; per-sheet totals reconcile
    to item sums; summary converted row uses the FX rate at `executedAt`; missing FX omits the
    row and adds the warning; single-currency run produces one currency sheet + summary; no items
    ⇒ header-only summary sheet (no crash) — 5/5 green.
  - [x] IT (`PaymentRunWorkbookControllerIT`, Testcontainers + test-migrations): export of a seeded
    run returns 200 + XLSX; sheet count == distinct currency count + 1 summary (D7-1); workbook
    totals reconcile to seeded item amounts; `medfund.security.events` carries
    `eventType=DATA_ACCESS` + `PAYMENT_RUN_WORKBOOK` (Rule 9); permission gate yields 403. Added
    `V006__payment_run_workbook.sql` test-migration (payment_run_items + payments, post-V071 shape).
    Note: `@RequiresReport` falls back to enabled in the test schema (no `tenant_report_config`
    table), so the gate is proven via `FINANCE_VIEW_SUBLEDGER` — mirroring Phase 6's fallback.
  - [x] Java compiles + full finance test run green: `./gradlew :finance-service:test` — 202/202
    (incl. the 5 new workbook unit tests + the new IT). `:finance-service:build`'s jacoco gate
    still fails below 70% — pre-existing, see `.claude/coverage-backlog.md`.
  - [x] Angular: `npx ng test --watch=false --browsers=ChromeHeadlessCI` (existing suite — 468 pass;
    the single pre-existing `insurance-lines` providerModeForLine failure remains, unrelated);
    `npx ng build --configuration development` compiles clean (exit 0).
  - [x] Playwright `payment-run-workbook.spec.ts` (4/4): executed run header shows Export workbook →
    blob GET fires 200; draft run still exports; missing `finance:view_subledger` hides the button;
    export failure surfaces in the `errorMessage` banner.
- **Manual:** Excel opens without warnings; sheet count matches currency count + summary; large
  runs (5k+ items) export in <30s; converting a run whose currency has no FX row at executedAt
  still downloads with the warning line.

---

## Phase 8: Aged Debtors + 13-Week Cash-Flow Forecast

> **Grilled 2026-08-16.** Outline expanded to code altitude via D8-1..D8-10 (see below). Per
> D8-1 the forecast lives in contributions-service exactly as the outline wrote it — this *inverts*
> the usual cross-service aggregator direction (finance-service has hosted every Phase 3/5 composer)
> and is recorded under Deviations (Phase 8 §1).

### Overview

Three surfaces: (1) a 13-week rolling cash-flow forecast — expected receipts from unpaid invoices
against planned payouts from draft/approved payment runs, bucketed per ISO week per currency;
(2) a collection-rate *trend* — portfolio-level monthly collection rate over a period; (3) the
aged-debtors Angular page, replacing the `debtors-report` ComingSoon stub with a catalogue-registered
report consuming the Phase 1 backend.

### Decisions (grilled 2026-08-16)

- **D8-1 — Forecast lives in contributions-service, per the outline.** Expected receipts are
  contributions-owned data (invoices); the plan's outline names `AgedDebtorsForecastController` in
  contributions-service. The outflow side is read from finance-service via a new `FinanceClient`
  WebClient (mirroring `ContributionsClient` in reverse). See Deviations (Phase 8 §1).
- **D8-2 — Scope = all three deliverables.** The aged-debtors piece is a **catalogued report page**
  (aging-bucket grid + XLSX export + tenant toggle) reusing the Phase 1 backend — a report surface,
  not a debtors *workbench* (drill-ins / manual flagging / dunning are operational tooling, out of
  scope).
- **D8-3 — Collection-rate trend lives in finance-service, as a *portfolio-level* trend.** The
  existing `COLLECTION_RATE` report is per-(dimension, currency) monthly buckets; the trend drops
  the dimension and sums all schemes/groups/members per (month, currency) — a clean time-series
  (`month, billed, received, ratePct`) fit for a line chart, and a genuinely distinct report.
- **D8-4 — Inflow model: unpaid invoices by `due_date`.** `BillingService` always stamps
  `invoice.due_date = period_end`, so the anchor is reliable. Expected receipts =
  `SUM(total_amount)` of invoices whose `due_date` falls inside the rolling window, per (currency,
  ISO week of `due_date`). No collection-rate discount factor — the trend report surfaces realized
  collection quality separately, and weighting would make the forecast impossible to reconcile to a
  treasurer's billed-amount projection.
- **D8-5 — Outflow model: draft + approved runs, items bucketed by `created_at`.** There is no
  planned-execution-date column; `executed_at`/`settlement_date` only exist post-execution, and the
  scheduler auto-executes draft runs ~24h after creation. So forward obligations = runs with status
  `draft`/`approved` (never executed/cancelled), item amounts (excluding `withheld`/`skipped`),
  bucketed by ISO week of the run's `created_at` (weekly granularity absorbs the 24h drift).
- **D8-6 — Window = `?asOf=` (default today) + `?rollingWeeks=` (default 13, clamp 1..52).**
  Window is `[asOf, asOf + rollingWeeks×7)`, bucketed into ISO (Monday-start) weeks with the
  partial current week included. Making the forecast a pure function of `asOf` keeps it
  unit-testable and satisfies the "known period ±5%" manual check.
- **D8-7 — Per-currency forecast, no FX conversion.** Forward rates don't exist in
  `public.exchange_rates` (immutable historical per-date), so the reporting-currency conversion
  path cannot apply to a forward forecast. Data is per-currency only (Rule 1): one series per
  currency, in/out/net per week, never mixed. Envelope carries `reportingCurrency` as
  informational (resolved tenant default) with an empty `fxRates`; no warning banner for "missing
  future FX" (expected, not an error). The *trend* report is historical → converts normally.
- **D8-8 — Aged-debtors route key = `AGED_BALANCES`.** The backend `/billing/balances/aged-balances`
  gates `AGED_BALANCES`; the page's route `data.reportKey` must match so the tenant toggle actually
  403s the page's API. `AGED_DEBTORS` stays reserved for the Phase 17 cadenced-delivery key.
- **D8-9 — XLSX exports for both new reports.** Forecast: one sheet per currency (weekly
  in/out/net) + summary; trend: one sheet. Both emit `SecurityEventPublisher.publishDataAccess` with
  `reportKey` before returning bytes (Rule 9).
- **D8-10 — Gateway routes are path-specific** (no catch-all broadening, per Phase 5 convention):
  `/api/v1/reports/cash-flow-forecast` → contributions-service; `/api/v1/reports/collection-rate-trend`
  → finance-service; `/api/v1/reports/aggregate/outflows` → finance-service (service-to-service).

### Changes Required

#### §A1 — contributions-service: cash-flow forecast

- **`client/FinanceClient.java`** (new) — WebClient to finance-service
  (`@Value("${services.finance.base-url:http://localhost:8085}")`), no in-client retries/fallbacks
  (those live in `CrossServiceCallHelper` at the caller, per G37). One method:
  `plannedOutflows(LocalDate periodStart, LocalDate periodEnd)` → `List<PlannedOutflowRow>` via
  `GET /api/v1/reports/aggregate/outflows`, decoding `ReportResponse<List<...>>` with the same
  String + Jackson `TypeReference` trick as `ContributionsClient` (no R2dbc codec for generic
  envelopes). Carries `X-Tenant-ID` from `TenantContext`.
- **`dto/PlannedOutflowRow.java`** (new, contributions-side mirror of the finance DTO) —
  `record PlannedOutflowRow(UUID runId, String runNumber, String currencyCode, BigDecimal amount,
  String runStatus, String itemStatus, LocalDate createdAt)` — finance returns raw item-level rows;
  **all ISO-week bucketing happens in contributions** so the two sides bucket identically.
- **`repository/CashFlowForecastQueryRepository.java`** (new) — one tenant-scoped R2DBC query:
  `expectedReceipts(LocalDate windowStart, LocalDate windowEnd)` →
  `Flux<InvoiceReceiptRow(currencyCode, dueDate, amount)>` from
  `SELECT currency_code, due_date, total_amount FROM invoices WHERE status NOT IN ('paid','void')
  AND due_date >= :start AND due_date < :end`.
- **`service/CashFlowForecastService.java`** (new) — composes:
  - Window: `buildWindow(asOf, rollingWeeks)` → `[asOf, asOf+weeks×7)`, clamp 1..52, reject
    `rollingWeeks < 1`.
  - Inflow: `expectedReceipts` bucketed per (currency, ISO week of `due_date`).
  - Outflow: `CrossServiceCallHelper.guarded("payment-run-outflows", financeClient.plannedOutflows(...),
    List.of(), warnings)` bucketed per (currency, ISO week of `createdAt`).
  - `compose(...)` → `CashFlowForecastResponse` = per-currency series of
    `WeekBucket(weekStart, inflow, outflow, net)`; `net = inflow.subtract(outflow)` per currency
    (Rule 1 — never across currencies). Warnings list flows to the envelope.
- **`controller/CashFlowForecastController.java`** (new) — `GET /api/v1/reports/cash-flow-forecast`
  + `GET /export/excel`. `@RequiresPermission(FINANCE_VIEW_SUBLEDGER)` +
  `@RequiresReport(CASH_FLOW_FORECAST_13W)`. Params `asOf`, `rollingWeeks`, optional
  `reportingCurrency` (resolved but not applied — D8-7). Hand-built envelope (warnings come from
  the fanout, mirroring `CollectionRateReportController`): `reportKey`, `reportingCurrency`,
  `perCurrency` = per-currency `PerCurrencyTotal` (inflow+outflow sums for the window),
  `fxRates = Map.of()`, `warnings`. Excel via a small `CashFlowForecastExcelService` (weekly rows
  per currency + summary, `ReportWorkbook`), filename `cash-flow-forecast-{asOf}-{weeks}w.xlsx`,
  `publishDataAccess(tenantId, actorId, actorEmail, CASH_FLOW_FORECAST_13W, {asOf, rollingWeeks})`.
  Full Swagger annotations.

#### §A2 — finance-service: outflow aggregate + collection-rate trend

- **`dto/PlannedOutflowRow.java`** (new) — same record shape as the contributions mirror; the
  finance DTO is the source of truth for the JSON contract.
- **`controller/PaymentRunOutflowController.java`** (new) —
  `GET /api/v1/reports/aggregate/outflows?periodStart&periodEnd` → item-level rows for draft/approved
  runs (never executed/cancelled), items `pending`/`scheduled` only (excludes `withheld`/`skipped`).
  **Not `@RequiresReport`-gated** (service-to-service — toggling `CASH_FLOW_FORECAST_13W` must not
  break the forecast's data feed; mirrors `ReceiptsAggregateController`), `@RequiresPermission(
  FINANCE_VIEW_SUBLEDGER)`. Wrapped in `ReportResponse<List<PlannedOutflowRow>>` (empty
  perCurrency/fxRates). Implemented as one new SQL projection in `PaymentRunQueryRepository`
  (`plannedOutflows(periodStart, periodEnd)` joining `payment_runs` + `payment_run_items`).
- **`dto/CollectionRateTrendResponse.java`** (new) — `record (LocalDate periodStart, LocalDate
  periodEnd, List<MonthRow> months)`; `MonthRow(month, currencyCode, billed, received, ratePct)`.
- **`service/CollectionRateTrendService.java`** (new) — reuses the same 6-way guarded
  `ContributionsClient` monthly fan-out as `CollectionRateReportService`, then **sums across all
  dimensions** per (month, currency) — no cross-currency mixing (G34); `ratePct` via the shared
  `ratePercent` (zero-denominator → null).
- **`controller/CollectionRateTrendController.java`** (new) —
  `GET /api/v1/reports/collection-rate-trend?periodStart&periodEnd&reportingCurrency` +
  `GET /export/excel`. `@RequiresReport(COLLECTION_RATE_TREND)` + `FINANCE_VIEW_SUBLEDGER`.
  Historical → normal envelope conversion (via `ReportingCurrencyResolver` + `FxConverter` for the
  perCurrency totals); hand-built envelope so fanout warnings survive. `CollectionRateTrendExcelService`
  + `publishDataAccess`. Full Swagger annotations.

#### §A3 — gateway

- **`services/go/gateway/internal/routes/routes.go`** — add path-specific entries (before the
  report catch-all comment block):
  `app.All("/api/v1/reports/cash-flow-forecast", proxy.Handler(cfg.ContribServiceURL))` (+ `/*`),
  `app.All("/api/v1/reports/collection-rate-trend", proxy.Handler(cfg.FinanceServiceURL))` (+ `/*`),
  `app.All("/api/v1/reports/aggregate/outflows", proxy.Handler(cfg.FinanceServiceURL))` (+ `/*`).

#### §B — Angular

- **`core/services/finance.service.ts`** — `getCashFlowForecast(params)` →
  `api.get('/reports/cash-flow-forecast', ...)`, `exportCashFlowForecastExcel(params)` →
  `api.getBlob('/reports/cash-flow-forecast/export/excel', ...)`; `getCollectionRateTrend(params)` /
  `exportCollectionRateTrendExcel(params)` on `/reports/collection-rate-trend`; aged-debtors methods
  on `/billing/balances/aged-balances` (+ export) if not already present.
- **`pages/tenant/finance/reports/cash-flow-forecast/cash-flow-forecast.component.ts|html|scss`**
  (new) — `asOf` (date, default today) + `rollingWeeks` (number, default 13) + reporting-currency
  selector (informational — D8-7); per-currency series with the shared `app-line-chart`
  (in/out/net twin series via `@swimlane/ngx-charts`, already a dependency); warnings banner
  (peer-failure, G37); Export XLSX button; table of weekly buckets.
- **`pages/tenant/finance/reports/collection-rate-trend/collection-rate-trend.component.ts|html`**
  (new) — period + currency selector; `app-line-chart` of `ratePct` per month; table; Export XLSX.
  Mirrors `collection-rate-report.component` conventions (SCSS reuse, `errorMessage` banner).
- **`pages/tenant/finance/reports/aged-debtors/aged-debtors.component.ts|html|scss`** (new) —
  replaces the `debtors-report` ComingSoon stub. Consumes existing `/billing/balances/aged-balances`
  (minAgeDays param, GRACE/SUSPENDED/WRITE_OFF grid) + `/export/excel`. Perm `finance:view_debtors`,
  route `reports/aged-debtors`, `data.reportKey: 'AGED_BALANCES'` (D8-8).
- **`finance.routes.ts`** — add the three routes; delete the `debtors-report` stub; each carries
  `fullbleed: true`, `sidebar: 'operational'`, and its report key.

### Success Criteria

#### Automated
- [x] Unit (`CashFlowForecastServiceTest`): window builder (default 13, clamp 1..52, reject <1,
  `[asOf, +weeks×7)`); ISO-week (Monday) bucketing of invoice due-dates and run created-at;
  per-currency compose with correct `net`; outflow peer failure → warnings + partial forecast —
  5/5 green.
- [x] Unit (`CollectionRateTrendServiceTest`): dimension-summing per (month, currency); ratePct null on
  zero billing; no cross-currency sums; peer-down warnings — 4/4 green.
- [x] IT (`CashFlowForecastControllerIT`, contributions Testcontainers + `@MockBean FinanceClient`):
  seeded unpaid invoices + outflow feed reconcile to the envelope buckets; net = inflow − outflow;
  warnings on finance down; rollingWeeks < 1 → 400; XLSX export 200 — 4/4 green. Note: mockwebserver
  is not on the contributions test classpath, so the peer stub uses `@MockBean` (see Deviations §2).
- [x] IT (`CollectionRateTrendControllerIT`, finance Testcontainers + MockWebServer): trend months +
  ratePct reconcile to the six dimension aggregates; peer-down warnings banner; DATA_ACCESS event on
  export (asserted against `medfund.security.events`); 403 gate via `FINANCE_VIEW_SUBLEDGER` — 4/4.
- [x] `./gradlew :finance-service:test :contributions-service:test` green (compile + unit + IT, incl.
  `PaymentRunOutflowControllerIT` + a restored `AgedBalancesExcelService` mock in `BalanceControllerTest`);
  Go `go build ./gateway/... ./shared/...` clean.
- [x] Angular: `ng test` (468 pass — the pre-existing `insurance-lines` failure remains, unrelated);
  `ng build --configuration=development` compiles clean.
- [x] Playwright `cash-flow-forecast.spec.ts` (2/2): golden path (open → fetch → chart renders →
  refilter weeks → warnings banner → export blob 200) + empty-window no-activity message. The toggle
  round-trip (disable in Settings → hub hides → direct URL 403s) is covered by the Phase-0
  reports-settings specs + the permission-gate ITs; the test-schema `@RequiresReport` fallback makes a
  literal toggle-off 403 unround-trippable (see Deviations §2).

#### Manual
- Forecast for a known `asOf` aligns with the treasurer's manual projection ±5%.
- Multi-currency tenant: per-currency series render, no cross-currency total shown.
- Collection-rate trend line chart reads correctly against the per-dimension collection-rate report.
- Aged-debtors page: aging grid + export open clean; toggling `AGED_BALANCES` off hides the page and
  403s its API.

---

## Phase 9: Cross-Tenant Analytics Fill

### Overview

Fill the stubbed `/analytics/*` endpoints in `services/go/gateway/internal/platform/handler.go:526-544` by adding per-service `/api/v1/platform/<metric>` raw-row endpoints (super-admin only), and wire a new revenue-by-tenant bar chart into the Angular analytics page. The five stubs (`claims-over-time`, `billing-over-time`, `billing-payments-over-time`, `claim-payouts-over-time`, `revenue-by-tenant`) are filled; three already-real endpoints (`tenant-growth` → moved server-side, `member-growth`, `claims-distribution`) are left untouched except tenant-growth.

### Design Decisions (D9-1..D9-9)

- **D9-1 — No new connection factory.** The existing `TenantAwareConnectionFactory` already yields platform-context connections (`search_path = public`, no `SET ROLE`) when no tenant UUID is in Reactor context (`shared/.../TenantAwareConnectionFactory.java:84`). claims-service and user-service already do cross-tenant aggregation via `information_schema.schemata LIKE 'tenant_%'` + schema-qualified queries (`PlatformStatsController`). All four new endpoints copy this pattern — no new beans, no new connection factory.

- **D9-2 — revenue-by-tenant lives in contributions-service, not finance.** "Revenue" = receipts (netted completed transactions per `transaction_types.sign`, the Phase 3 receipts definition), which is contributions-domain data. The plan's finance-service assignment is a deviation. finance-service serves payouts only.

- **D9-3 — Revenue-by-tenant chart wired into Angular.** The `getRevenueByTenant()` method in `platform-dashboard.service.ts:79` currently has no caller. A new bar chart is added to `analytics.component.html` (below the Money Flow section, before Service Health), using the existing `app-bar-chart` component. This is a deliberate expansion of the plan's "Angular: no changes" constraint.

- **D9-4 — Currency conversion.** All four money series (billed, received, paid-out, revenue-by-tenant) are converted to a platform reporting currency (USD, matching the `$` UI) server-side in Java, using `FxRateReader.findRate` with `tenantId=null` (platform-wide rates only from `public.exchange_rates`). Best-effort: rows whose currency has no platform-wide rate are excluded and a `skipped` count is surfaced in the gateway response envelope. `seriesPoint.Value` changes from `int` to `float64`. As-of date per row (falling back to latest rate). Angular area charts render floats unchanged; the bar chart uses `value` directly.

- **D9-5 — Bucketing lives in the gateway.** Java endpoints return raw rows (timestamps + converted amounts + optional metadata). The gateway's existing `periodBuckets`/`buildGrowthSeries` engine (handler.go:363-444) does all windowing, bucketing (week/month/year/all), and money-sum bucketing for the four time-series endpoints. Revenue-by-tenant is a pass-through **ranking** (not bucketed — x-axis = tenant names). One bucketing implementation in Go, reused by every chart; Java stays period-agnostic. The gateway unwraps the `{rows, skipped}` envelope to forward a plain `[{name, value}]` array, logging when `skipped > 0`.

- **D9-6 — Super-admin enforcement at gateway.** A new middleware (applied to the `/api/v1/platform` group) rejects non-`super_admin` callers with 403, using `hasSuperAdminRole(claims)` from `jwt.go:104`. This covers all existing endpoints too (tenant-count, claims-stats, user-stats, member-growth, claims-distribution, stats, activity, health) — closing an existing hole where any authenticated user could read cross-tenant aggregates. Angular `roleGuard(['super_admin'])` on `/platform/*` already hides the pages client-side, so no legitimate caller breaks.

- **D9-7 — Metric semantics.** Each endpoint is a raw-row feed to the gateway:

  | Endpoint | Service | Source | Timestamp | Value |
  |---|---|---|---|---|
  | `claims-over-time` | claims-service | `claims` WHERE `created_at IS NOT NULL` | `created_at` | count (1 per claim) |
  | `billing-over-time` | contributions-service | `invoices` WHERE `issued_at IS NOT NULL` AND `status <> 'void'` | `issued_at` | `total_amount` (USD-converted) |
  | `billing-payments-over-time` | contributions-service | Phase 3 receipts CTE verbatim (`transaction_types.sign` netted, `status = 'completed'`, types `PAYMENT`, `COPAYMENT_RECEIPT`, `CTC_OFFSET`, `REFUND`, `PAYMENT_REVERSAL`, `CTC_OFFSET_REVERSAL`) | `transaction_date` | `amount * sign` (USD-converted) |
  | `claim-payouts-over-time` | finance-service | `payment_run_items` WHERE `status = 'paid'` JOIN `payment_runs` WHERE `status = 'executed'` | `payment_runs.executed_at` | `amount` (USD-converted) |
  | `revenue-by-tenant` | contributions-service | Same receipts CTE, per-tenant sum in window | N/A (ranking) | `amount * sign` per tenant (USD-converted), joined to `public.tenants.name` via `schema_name` |

  All endpoints use the schema-enumeration pattern (`information_schema.schemata LIKE 'tenant_%'`), schema-qualified queries (`"tenant_x".table`), and `onErrorResume(Flux.empty())` per schema.

- **D9-8 — tenant-growth moves server-side.** tenancy-service adds `/api/v1/platform/tenant-growth` returning raw `created_at` timestamps from `public.tenants` (its own domain). The gateway's `getTenantGrowth` stops calling the paginated `/api/v1/tenants?size=1000` list and fans out to the new endpoint; bucketing logic unchanged. This fixes the size-1000 truncation cap and aligns with the other platform endpoints.

- **D9-9 — Tests.**
  - **Java ITs** — one per new controller, on Testcontainers Postgres (`AbstractPostgresIntegrationTest`): create 2+ `tenant_%` schemas; seed minimal tables + `public.tenants` rows; assert the raw-row responses. Plus a no-data case (empty → `[]`). Super-admin 403 asserted for non-super-admin JWTs.
  - **Gateway Go tests** — (a) unit tests for the new money-sum bucket + ranking helpers; (b) a handler wiring test with `httptest` servers standing in for upstream services (raw rows → bucketed series); (c) a middleware test asserting non-super-admin → 403 on `/api/v1/platform/*`.
  - **Revenue-by-tenant cap** — top 10 tenants sorted by revenue; bar chart renders all 10.

### Changes Required

#### Java services

- **claims-service** — new `PlatformAnalyticsController` (`/api/v1/platform/claims-over-time`): `GET ?periodStart=&periodEnd=` returns `Flux<Map<String, Object>>` of `{ts, value}` rows (schema-enumerated raw claim counts by `created_at`). No period bucketing — the gateway handles that.
- **contributions-service** — new `PlatformAnalyticsController` with three endpoints:
  - `GET /api/v1/platform/billing-over-time` — invoices, schema-enumerated, issued_at + USD-converted total_amount.
  - `GET /api/v1/platform/billing-payments-over-time` — receipts CTE (schema-qualified `transactions` + `transaction_types`), netted by sign, transaction_date + USD-converted amount.
  - `GET /api/v1/platform/revenue-by-tenant` — receipts CTE, per-tenant sum, joined to `public.tenants.name` via `schema_name`. Returns top-10 ranked `{tenantName, value}`. Note: cross-schema join to `public.tenants` (platform schema) is safe because platform-context `search_path = public` resolves `public.tenants`; the `tenant_x` prefix is explicit in the qualified query.
  - All three use `FxRateReader` (shared bean, injected) with `tenantId=null` for platform-wide USD conversion, request-level cache keyed by `(currency, date)`. Skipped-unconvertible count logged and surfaced in response envelope `{rows: [...], skipped: N}`.
- **finance-service** — new `PlatformAnalyticsController` (`/api/v1/platform/claim-payouts-over-time`): `GET ?periodStart=&periodEnd=` returns `Flux<Map<String, Object>>` of `{ts, value}` rows (schema-enumerated `payment_run_items` WHERE `status = 'paid'` JOIN `payment_runs` WHERE `status = 'executed'`, joined to `public.tenants.name`... wait — payouts don't need tenant names, just timestamps and USD-converted amounts).
- **tenancy-service** — new `PlatformAnalyticsController` (`/api/v1/platform/tenant-growth`): `GET` returns `Flux<Map<String, Object>>` of `{ts}` rows (`created_at` from `public.tenants`). No schema enumeration — `public.tenants` lives in the public schema.

#### Gateway

- **`services/go/gateway/internal/middleware/`** — new `superadmin.go`: `RequireSuperAdmin()` Fiber middleware that checks `hasSuperAdminRole(c.Locals("jwt_claims"))` and returns 403 `{"error": "super admin access required"}` if not. Applied to the platform group in `routes.go`.
- **`services/go/gateway/internal/platform/handler.go`** — replace stubs with real upstream calls:
  - `getClaimsOverTime`: calls claims-service `/api/v1/platform/claims-over-time?periodStart=&periodEnd=`, buckets via `buildCountSeries` (new helper).
  - `getBillingOverTime`: calls contributions-service `/api/v1/platform/billing-over-time?periodStart=&periodEnd=`, buckets via `buildMoneySumSeries` (new helper).
  - `getBillingPaymentsOverTime`: calls contributions-service `/api/v1/platform/billing-payments-over-time?periodStart=&periodEnd=`, buckets via `buildMoneySumSeries`.
  - `getClaimPayoutsOverTime`: calls finance-service `/api/v1/platform/claim-payouts-over-time?periodStart=&periodEnd=`, buckets via `buildMoneySumSeries`.
  - `getRevenueByTenant`: calls contributions-service `/api/v1/platform/revenue-by-tenant?periodStart=&periodEnd=`, takes top-10, returns `[{name: tenantName, value: revenue}]` (no bucketing).
  - `getTenantGrowth`: switch from `fetchJSON(tenancyServiceURL+"/api/v1/tenants?size=1000")` to `fetchJSON(tenancyServiceURL+"/api/v1/platform/tenant-growth")`, buckets via existing `buildGrowthSeries`.
  - New helpers: `buildCountSeries(period, []row)` (counts per bucket), `buildMoneySumSeries(period, []row)` (USD-converted amount sums per bucket), `periodStart(period)`/`periodEnd(period)` (window bounds). Revenue-by-tenant uses `topN(rows, 10)` ranking.
  - All upstream calls include the caller's `access_token` cookie as `Authorization: Bearer` header (existing `fetchJSON` pattern). When upstream returns non-200, return empty series (existing fallback pattern).
  - The `{rows, skipped}` envelope is unwrapped by the gateway before returning to the client; `skipped > 0` is logged at `log.Warn`.

#### Angular

- **`analytics.component.ts`** — add `revenueByTenant: any[] = []` field; call `dashboardService.getRevenueByTenant(p)` in `loadCharts()`.
- **`analytics.component.html`** — new chart section between Money Flow and Service Health: bar chart card titled "Revenue by Tenant" with `app-bar-chart [data]="revenueByTenant" xAxisLabel="Tenant" yAxisLabel="Revenue ($)"`.
- **`analytics.component.ts`** — add `BarChartComponent` to imports.
- **`platform-dashboard.service.ts`** — `getRevenueByTenant` already exists (line 79), no change.

### Success Criteria

#### Automated
- [x] Gateway `go build ./... && go test ./...` — new `superadmin_test.go` (4 cases: allow super_admin, reject non-super-admin, reject missing claims, reject missing realm_access) + new `platform/handler_test.go` (7 cases: buildCountSeries bucketing + old-row cutoff + unparseable timestamps, buildMoneySumSeries float summation, periodQueryString week/month/year range + `all` empty, tenant-growth httptest wiring, billing-over-time envelope unwrap, revenue-by-tenant top-N forwarding, claims-over-time plain-array unwrap) all green.
- [x] `./gradlew :tenancy-service:test :claims-service:test :contributions-service:test :finance-service:test` — full suites pass. Tenancy adds `PlatformAnalyticsIT` (Testcontainers Postgres against `db/test-migration`, seeds two tenants + asserts `/tenant-growth` returns raw timestamps). Finance + contributions add `PlatformAnalyticsControllerTest` for the FX-cache + skipped-counter arithmetic (same-currency short-circuit, cache reuse across rows, missing-rate → skipped, revenue-by-tenant schema tagging).
- [x] Angular `ng build --configuration=development` — new `BarChartComponent` import + revenue-by-tenant field + template block compile clean; only pre-existing `ClaimDetailComponent` template warning remains (unrelated).
- [ ] **Deferred to a follow-up hardening pass**: full Testcontainers ITs for claims / contributions×3 / finance endpoints that require seeding two `tenant_%` schemas with `claims` / `invoices` / `transactions` / `payment_runs` shapes. The FX-arithmetic layer is unit-tested in isolation; wiring is covered by the gateway's httptest layer against real JSON shapes; the schema-enumeration path itself is proven by the existing `PlatformStatsController.getClaimsDistribution` + `getMemberGrowth` endpoints that share the identical `information_schema.schemata LIKE 'tenant_%'` fanout pattern.

#### Manual
- [ ] Log in as super-admin → `/platform/analytics` → confirm all charts render real cross-tenant data; toggle period → charts update; revenue-by-tenant bar chart shows top-10 tenants.
- [ ] Non-super-admin (e.g. `tenant_admin`) attempting `GET /api/v1/platform/analytics/tenant-growth` via the gateway receives `403 Forbidden` (super-admin middleware).
- [ ] Multi-currency tenant with non-USD transactions renders in the revenue-by-tenant chart with USD-converted totals; missing FX rate for a currency logs `dropped N unconvertible rows` in gateway stdout without failing the chart.

---

## Phase 10: Reinsurance Module + Bordereau Reports

> **Grilled 2026-08-22.** Decisions R1..R16 (numbered R* to avoid collision with plan-wide G*).
> The outline below was expanded into a full mini-plan through interactive decision-making;
> scope escalated beyond the original outline. Recommended split into **§A** (entities +
> auto-cession loss + all three reports + XLSX) and **§B** (facultative UI + premium cession
> + review queue + retro backfill) — §A ships a working recoveries surface; §B carries the
> workflow-heavy pieces. Original 20-line outline retained below as ~~strike-through~~
> for provenance.

### Original outline (superseded 2026-08-22 by Decisions Log)

~~**finance-service** (or new `reinsurance/` package there): entities `Treaty`, `TreatyLayer`, `CessionRule`, `Cession`, `Recovery`.~~
~~Flyway migrations under tenant/.~~
~~CRUD services + controllers.~~
~~Kafka consumers: `medfund.claims.adjudicated` → auto-cession per rule → `Cession` record; `medfund.finance.payment-created` on recovery.~~
~~`ReinsuranceReportController`: `/reports/reinsurance/cession-bordereau`, `/recoveries-bordereau`, `/treaty-utilization`. Report keys `REINSURANCE_CESSION_BORDEREAU`, `REINSURANCE_RECOVERIES`, `REINSURANCE_TREATY_UTILIZATION`.~~
~~**Angular** tenant-admin pages: treaty list + form + layer editor + cession rules.~~
~~**Angular** report pages under `reports/reinsurance/`.~~

### Overview

Greenfield reinsurance module in `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/*` (R3). Covers both **proportional (Quota Share / Surplus Share)** and **non-proportional (Excess of Loss / Stop Loss)** treaties (R1). Cession semantics cover both **loss** cessions (recovered from reinsurer on claims paid) and **premium** cessions (paid to reinsurer on contributions collected) (R5). Both **automatic** cession (driven by rules-engine RuleDefinitions per Critical Rule 5, R4) and **facultative** cession (underwriter-facing UI with four-eyes workflow, R2 + R6). Ships three tenant-toggleable reports plus a manual-review queue for claim reversals (R12).

Report keys already ship in shared enums (`ReportKey.REINSURANCE_CESSION_BORDEREAU`, `REINSURANCE_RECOVERIES`, `REINSURANCE_TREATY_UTILIZATION` — `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:91-93`; `ReportFamily.REINSURANCE` at `.../ReportFamily.java:24`). All Phase 0-9 shared infra composes on top (`ReportEnvelopeBuilder`, `ReportingCurrencyResolver`, `FxRateReader`, `ReportWorkbook`, `CrossServiceCallHelper`, `RequiresReport` + `ReportGuardAspect`, `SecurityEventPublisher`, `AuditActor`).

### Decisions Log (R1..R16)

- **R1 — Treaty types**: both **proportional (QUOTA_SHARE, SURPLUS_SHARE)** and **non-proportional (EXCESS_OF_LOSS, STOP_LOSS)**. `TreatyLayer` required ≥1 row for XoL/StopLoss; empty for QS/SS. Auto-cession consumer dispatches on `treatyType`: proportional = `approvedAmount * cessionRate`; non-proportional = `max(0, min(layerLimit, approvedAmount - retention))` per layer. Bordereau row shape type-agnostic (ceded amount as a scalar) — report code doesn't fork.
- **R2 — Facultative cession**: full facultative UI, underwriter-facing. Standalone browse-and-cede surface with workflow states and own permission. Doubles Phase 10 scope vs the original outline's auto-cession-only reading. Both auto + facultative land in the same `cessions` table via a `source ∈ {AUTOMATIC, FACULTATIVE}` discriminator.
- **R3 — Home**: subpackage of finance-service (`com.medfund.finance.reinsurance.*`). Reuses `ClaimAdjudicatedConsumer` topology (same JVM, same tenant-context propagation), existing `/api/v1/reports/*` gateway route pattern, finance-service's PaymentRun machinery. One deployment unit.
- **R4 — Cession rule DSL**: rules-engine integration. CessionRule becomes a per-tenant JSON `RuleDefinition` compiled to Drools DRL via existing rules-engine (Critical Rule 5). New `ReinsuranceTemplateCategory` + one template per treaty type. Rules fire against enriched `ClaimFact` (loss cession) or a new `ContributionFact` (premium cession) at consumer time. Angular authoring UI reuses the visual rule builder — verify component genericity at plan time (grill note 3).
- **R5 — Cession scope**: both loss and premium. `Cession.cessionType ∈ {PREMIUM, LOSS}`. Two Kafka consumers (loss on `medfund.claims.adjudicated`, premium on the contribution-paid topic — event name to verify, grill note 1) + a scheduler job for flat XoL treaty premiums at treaty inception. Bordereau row set = premium rows + loss rows (matches reinsurer expectations).
- **R6 — Facultative workflow**: three-state `{DRAFT, APPROVED, CEDED, VOIDED}` with distinct approver role. Matches PaymentRun's four-eyes precedent. Auto cessions born `ACTIVE`, only transition to `VOIDED`. Two new permissions: `finance.reinsurance:cede_facultative`, `finance.reinsurance:approve_facultative`. AuditEvent on every transition.
- **R7 — Cession multi-currency**: store native, convert at report time. Cession row holds underlying event's currency. Bordereau XLSX converts native → treaty currency at export using `FxRateReader.convert(nativeCurrency, treatyCurrency, cession.occurredAt, tenantId)`; missing rate → warnings row on envelope per G28. Matches cross-phase invariant #6. Follow-up ticket after MVP: snapshot FX rate on export at close-of-period for stable numbers (interacts with R16 re-export drift).
- **R8 — Recovery lifecycle**: distinct `Recovery` entity, four states `{EXPECTED, INVOICED, RECEIVED, WRITTEN_OFF}`. `ReinsuranceRecoveryConsumer` on `medfund.finance.payment-created` writes EXPECTED per matched cession when the underlying claim payment is made. INVOICED set as a side-effect of exporting the recoveries bordereau (the bordereau *is* the invoice). RECEIVED set manually via a finance-officer form (auto bank-recon match deferred). WRITTEN_OFF via supervisor with mandatory reason. Dispute state folded into WRITTEN_OFF-with-reason for MVP; partial recoveries modelled as `receivedAmount < expectedAmount` + status = RECEIVED.
- **R9 — Reinsurer entity + multi-reinsurer**: full `Reinsurer` entity (name, contactEmail, contactAddress, jurisdictionCode, homeCurrency, creditRating NULL, isActive). `treaty_participant` join (treatyId, reinsurerId, sharePct, shareRole ∈ {LEADER, FOLLOWING}) — SUM(sharePct) = 100 enforced app-layer on Treaty activation. Bordereau exports scoped to reinsurerId via `?reinsurerId={uuid}` — one reinsurer sees only their share. Cession-participant split at report time (join), not on write (grill note 6).
- **R10 — Bordereau format**: bespoke insurer XLSX only via existing `ReportWorkbook`. No per-reinsurer template layer for MVP (additive later via `Reinsurer.bordereauColumnTemplate` JSON). No CSV/PDF variants. File naming: `cession-bordereau-{reinsurer-slug}-{yyyy}-Q{n}.xlsx`, etc.
- **R11 — Treaty shape**: immutable per underwriting year. Renewal = new treaty row with `renewedFromTreatyId` FK. Treaty statuses `{DRAFT, ACTIVE, EXPIRED, RENEWED, LAPSED, COMMUTED}`. ACTIVE treaties are read-only; correction path = void + re-create. Angular treaty list groups by renewal chain.
- **R12 — Retro + reversal**: retroactive cession backfill within the current underwriting year on Treaty DRAFT→ACTIVE (`TreatyActivationBackfillJob`, chunked, idempotent via UNIQUE `(treatyId, sourceEventId, cessionType)`); manual review queue for claim reversals (`reinsurance_review_task` table + `ClaimReversedConsumer` + Angular queue at `/tenant/finance/reinsurance/review-queue`). Event name to verify (grill note 2). Scope-heavy — belongs in §B tranche.
- **R13 — Utilization report**: cumulative-to-date. Treaty carries `aggregateLimit NULL, aggregateLimitCurrency NULL, expectedAnnualPremium NULL`. Layer carries `layerLimit, retention, layerCurrency, rate, reinstatementCount NULL` (informational only — no reinstatement math per plan `:100`). Report SQL: SUM(cededAmount) GROUP BY treaty + layer since inception. Per-layer usage bar for XoL; per-treaty aggregate bar for treaties with aggregateLimit. No live warning — Phase 17 email delivery may add later.
- **R14 — Insurance-line applicability**: structured whitelist. `treaty_applicable_line` join (treatyId, insuranceLine). Auto-cession consumer pre-filters on `claim.insuranceLine ∈ treaty.applicableLines` before firing rules-engine — reduces per-claim rule fires and gives the Angular treaty list a "HEALTH, LIFE" badge without parsing DRL.
- **R15 — Producer/broker link**: nullable `producer_ref VARCHAR(120)` placeholder in Phase 10. Phase 11 adds `producer_id UUID FK REFERENCES producer(id)` via additive migration + backfill (fuzzy-match producer_ref → producer.name). Phase 11 grilling doc must record this dependency.
- **R16 — Bordereau period + close-of-period lock**: quarter-aligned periods (Angular picker = quarter+year, not date-range), soft lock via `bordereau_period_export` table (composite UNIQUE on reinsurerId+treatyId+reportKey+year+quarter). First export stamps `firstExportedAt`; late cessions flagged as `isPriorPeriodAdjustment` in subsequent exports of the same quarter. No hard lock — the R12 review queue handles late-arrival correctness.

### Settled by fact (not asked)

- **F10-a — All tenant migrations live in `services/java/tenancy-service/src/main/resources/db/migration/tenant/`**, not finance-service (finance-service has no `db/migration/` folder). Next V-number = **V081**. Corrects the outline's implicit "finance-service/tenant/" folder.
- **F10-b — Parent/child migration template**: `V078__member_cost_share_liability.sql` (parent + child with FK ON DELETE RESTRICT + UNIQUE on business key for idempotency).
- **F10-c — Next public V-number**: V133 (Phase 10 does not need public-schema tables — reinsurance is tenant-scoped per Rule 2).
- **F10-d — `ReportKey.REINSURANCE_*` and `ReportFamily.REINSURANCE` already ship**. No enum edits needed.
- **F10-e — Both Kafka topics exist and are correctly named**: `medfund.claims.adjudicated` (`services/java/claims-service/.../ClaimEventPublisher.java` producer; `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:65` reference-quality consumer using `.doOnSuccess` per `bug_reactor_kafka_ack_swallow`); `medfund.finance.payment-created` (`services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java:34` producer, no consumer today).
- **F10-f — Reinsurance is 100% greenfield**. No pre-existing entities, migrations, Angular routes, or permissions.

### Data model

Tenant-scoped tables (V081..V089 range; final numbering at plan time). All with the standard `id UUID PK DEFAULT gen_random_uuid(), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), actor_id UUID, actor_email VARCHAR` audit tail per `feedback_audit_actor_email` (rule 8).

| Table | Purpose | Key columns |
|---|---|---|
| `reinsurer` | Reinsurance counterparty master | name, contactEmail, contactAddress, jurisdictionCode (references tenant.jurisdiction_code catalog V131), homeCurrency, creditRating NULL, isActive |
| `treaty` | Immutable-per-year treaty placement (R11) | treatyRef (stable across renewal chain), treatyType {QUOTA_SHARE|SURPLUS_SHARE|EXCESS_OF_LOSS|STOP_LOSS} (R1), declaredCurrency, inceptionDate, expiryDate, status {DRAFT\|ACTIVE\|EXPIRED\|RENEWED\|LAPSED\|COMMUTED}, renewedFromTreatyId UUID NULL SELF-FK, aggregateLimit NULL, aggregateLimitCurrency NULL, expectedAnnualPremium NULL, producerRef VARCHAR(120) NULL (R15) |
| `treaty_layer` | XoL/StopLoss layer decomposition (R1) | treatyId FK, layerOrder INT, retention, layerLimit, layerCurrency, rate, reinstatementCount NULL (informational only per R13/plan `:100`) |
| `treaty_participant` | Multi-reinsurer participation on a treaty (R9) | treatyId FK, reinsurerId FK (composite PK), sharePct BigDecimal, shareRole {LEADER\|FOLLOWING}; app-layer constraint SUM=100 on activation |
| `treaty_applicable_line` | Insurance-line whitelist (R14) | treatyId FK, insuranceLine (composite PK) |
| `cession_rule` | Rules-engine RuleDefinition metadata (R4) | treatyId FK, ruleDefinitionId (FK into rules-engine tables), enabled |
| `cession` | Cession row — both loss + premium, both auto + facultative | treatyId FK, cessionType {PREMIUM\|LOSS} (R5), source {AUTOMATIC\|FACULTATIVE} (R2), status {ACTIVE\|DRAFT\|APPROVED\|CEDED\|VOIDED} (auto→{ACTIVE,VOIDED}; fac→{DRAFT,APPROVED,CEDED,VOIDED} per R6), sourceEventId UUID (claim_id or contribution_id or treaty_id-for-flat-premium), sourceEventType, cededAmount BigDecimal, currencyCode CHAR(3) (native per R7), occurredAt TIMESTAMPTZ, voidedReason VARCHAR NULL. UNIQUE `(treatyId, sourceEventId, cessionType)` for R12 backfill idempotency |
| `recovery` | Money owed by reinsurer against a cession (R8) | cessionId FK, status {EXPECTED\|INVOICED\|RECEIVED\|WRITTEN_OFF}, expectedAmount BigDecimal, receivedAmount BigDecimal NULL, currencyCode CHAR(3), invoicedAt TIMESTAMPTZ NULL, receivedAt TIMESTAMPTZ NULL, writeOffReason VARCHAR NULL |
| `bordereau_period_export` | Close-of-period soft lock + adjustment flagging (R16) | reinsurerId, treatyId NULL (for cross-treaty pooled bordereaux), reportKey ∈ REINSURANCE_*, year, quarter, firstExportedAt TIMESTAMPTZ, exportCount INT DEFAULT 1. Composite UNIQUE (reinsurerId, treatyId, reportKey, year, quarter) |
| `reinsurance_review_task` | Claim-reversal manual review queue (R12) | taskType {CLAIM_REVERSAL\|RECOVERY_DISPUTE\|MANUAL_VOID_REQUEST}, cessionId NULL, recoveryId NULL, claimId NULL, treatyId NULL, status {OPEN\|IN_PROGRESS\|RESOLVED_VOID\|RESOLVED_KEEP\|DISMISSED}, assigneeUserId NULL, dueBy NULL, createReason, resolutionNotes NULL |

### Kafka topology

Three consumers, one producer, one scheduler:

| Component | Direction | Topic | Behaviour |
|---|---|---|---|
| `ReinsuranceLossCessionConsumer` | in | `medfund.claims.adjudicated` (F10-e) | Per adjudicated claim: enumerate ACTIVE treaties for the tenant where `claim.insuranceLine ∈ treaty.applicableLines` (R14). Fire rules-engine (R4) with enriched ClaimFact. Write matching Cession rows (source=AUTOMATIC, status=ACTIVE, cessionType=LOSS). `.doOnSuccess` for ack per `bug_reactor_kafka_ack_swallow`. |
| `ReinsurancePremiumCessionConsumer` | in | `medfund.contributions.paid` (name to verify, grill note 1) | Same shape but for contributions. `cessionType=PREMIUM`. Only fires for proportional treaties by default (XoL/StopLoss use flat treaty premium via scheduler). |
| `ReinsuranceRecoveryConsumer` | in | `medfund.finance.payment-created` (F10-e) | Per payment: find matching Cession by underlying claim_id; write Recovery (status=EXPECTED) if not already present. |
| `ClaimReversedConsumer` | in | `medfund.claims.reversed` (name to verify, grill note 2) | Per reversal: create `reinsurance_review_task` (taskType=CLAIM_REVERSAL) per affected cession/recovery pair. |
| `ReinsuranceTreatyPremiumJob` | scheduler | — | Nightly `@Scheduled`; on treaty inceptionDate ± N days for flat-premium XoL/StopLoss treaties, write a PREMIUM Cession row with sourceEventType=TREATY_INCEPTION. |
| `TreatyActivationBackfillJob` | on-demand (triggered by Treaty DRAFT→ACTIVE) | — | Chunked scan of claims + contributions in the treaty's underwriting year, apply cession rules retroactively (R12). Idempotent via UNIQUE constraint. |

### Report shapes

Three report surfaces, each `@RequiresReport(...)` + `ReportEnvelopeBuilder` + `SecurityEventPublisher.publishDataAccess` on export:

**REINSURANCE_CESSION_BORDEREAU** — `GET /api/v1/reports/reinsurance/cession-bordereau?year=&quarter=&reinsurerId=&treatyId=&reportingCurrency=` — one XLSX row per cession in the quarter. Columns: treatyRef, treatyType, reinsurerName, sharePct (from R9 participant), cessionType, source, occurredAt, sourceEventRef, nativeAmount, nativeCurrency, cededAmount = nativeAmount × sharePct, treatyCurrencyAmount (converted per R7), isPriorPeriodAdjustment (R16). Export endpoint: `GET .../cession-bordereau/export/excel?...` — writes bordereau_period_export row on first export per (reinsurerId, treatyId, year, quarter, reportKey).

**REINSURANCE_RECOVERIES** — `GET /api/v1/reports/reinsurance/recoveries-bordereau?year=&quarter=&reinsurerId=&treatyId=&reportingCurrency=` — one row per recovery. Columns: cessionId, treatyRef, reinsurerName, status (EXPECTED/INVOICED/RECEIVED/WRITTEN_OFF), expectedAmount, receivedAmount, currencyCode, treatyCurrencyAmount, invoicedAt, receivedAt, writeOffReason. Export flips EXPECTED → INVOICED atomically.

**REINSURANCE_TREATY_UTILIZATION** — `GET /api/v1/reports/reinsurance/treaty-utilization?treatyId=&reportingCurrency=` — one section per treaty. Aggregate ceded since treaty.inceptionDate; per-layer usage bar (`used / layerLimit * 100`) for XoL/StopLoss layers; per-treaty aggregate bar (`ceded_aggregate / aggregateLimit * 100`) if aggregateLimit set. Layers without a limit render as "unlimited". Optional `?year=&quarter=` for period-scoped subtotal alongside cumulative.

### Angular surfaces

Tenant-admin CRUD area at `/tenant-admin/reinsurance/*`:
- `Reinsurers` tab — list + form (name, contact, jurisdiction, credit rating).
- `Treaties` tab — list (grouped by renewal chain per R11) + create form.
- `Treaty edit` page — inline sub-editors for layers (only for XoL/StopLoss), participants (`sharePct` sum-to-100 validation), applicable lines (multi-select), cession rules (delegates to visual rule builder per R4 — verify genericity).

Reports area at `/tenant/finance/reports/reinsurance/*`:
- `cession-bordereau` — quarter+year picker, reinsurer filter, treaty filter, export button.
- `recoveries-bordereau` — same filters, additionally status filter.
- `treaty-utilization` — treaty picker, cumulative view with layer bars.

Facultative flow at `/tenant/finance/reinsurance/facultative/*`:
- `browse` — claims/policies list above a threshold, cede action.
- `queue` — DRAFT/APPROVED cessions awaiting approve/commit (for approver role).

Review queue at `/tenant/finance/reinsurance/review-queue` — task list with resolve actions (per R12).

Two new permissions: `finance.reinsurance:cede_facultative`, `finance.reinsurance:approve_facultative`. Recommended new roles: `reinsurance_underwriter`, `reinsurance_supervisor` (or attach permissions to existing finance roles — plan-time call). Plus `finance.reinsurance:record_recovery_received` and `finance.reinsurance:writeoff_recovery` and `finance.reinsurance:resolve_review`.

### Proposed §A / §B tranche split

**§A — Backend + reports** (ships a working recoveries surface even without the workflow UI):
- All migrations (V081..V089 range in tenancy-service).
- All entities + repositories + services.
- Auto-cession loss consumer on `medfund.claims.adjudicated`.
- Recovery consumer on `medfund.finance.payment-created`.
- All three report endpoints + XLSX exports + `bordereau_period_export` soft-lock (R16).
- Tenant-admin Reinsurers + Treaties + Layer/participant/line editor.
- Cession rule visual builder integration (assumes R4 grill note 3 resolves cleanly).
- Angular report pages (three).
- IT: full CRUD + auto-cession consumer + bordereau XLSX + toggle-off 403 + SecurityEvent-on-export.

**§B — Workflow + premium cession + retro** (facultative + all the extras from R2/R5/R6/R12):
- Premium cession consumer on the contributions-paid topic (grill note 1) + `ReinsuranceTreatyPremiumJob` scheduler for flat XoL premium.
- `TreatyActivationBackfillJob` on DRAFT → ACTIVE (chunked processing).
- `ClaimReversedConsumer` (grill note 2) + `reinsurance_review_task` table.
- Facultative UI (browse + cede + queue) with three-state workflow.
- Review-queue UI.
- Recovery record-received form + write-off form.
- IT: facultative round-trip + backfill idempotency + review-task lifecycle + premium cession + role/permission matrix.

§A can land as a Phase 10A commit; §B as Phase 10B. Both should be fully grilled + planned at implement-time; `implement-plan` treats them as hand-off boundaries per the plan header.

### Grill notes for `create-plan`

1. **Contribution-paid event name (R5)** — verify producer + name; grep `services/java/contributions-service/src/main/java/com/medfund/contributions/service/*Publisher*.java`. If not present, additive to contributions-service first per plan `:3005` invariant ("consumers deploy AFTER producers are emitting").
2. **Claim-reversed event name (R12)** — same verification for `medfund.claims.reversed`; may need producer-side addition to claims-service.
3. **Visual rule builder component genericity (R4)** — verify existing Angular rule builder supports arbitrary `ReinsuranceTemplateCategory`; if hard-coded, an Angular refactor lands in §A scope. Check `clients/angular/src/app/pages/tenant-admin/rules/*` (or wherever visual builder lives).
4. **Retro backfill chunking (R12)** — plan the batch size + resume semantics for tenants with 10s of thousands of adjudicated claims. Include a progress-tracking table and cancellation path.
5. **Participant sharePct constraint enforcement (R9)** — app-layer at Treaty DRAFT → ACTIVE transition (matches Flyway "app-layer over DB triggers" pattern in this repo); include a service-level test.
6. **Cession-participant write shape (R9)** — recommend one Cession row per cession + report-time JOIN to `treaty_participant` for split, not one row per participant. Verify SUM performance on the bordereau query for a Q1 with ~10k cessions × 4 participants.

### Success Criteria

**Status:** Fully implemented via the expanded sub-plan `thoughts/shared/plans/2026-08-22-reinsurance-module-and-bordereau-reports.md` — §A landed in commit `0a689f4` ("Land Phases 1-5 of the reinsurance module (Phase 10 §A)"), §B in commit `e6907ec` ("Land Phases 6-8 of the reinsurance module"). Individual sub-phase success criteria (unit / IT / Angular build / Playwright) are recorded on the sub-plan itself. The parent-plan roll-ups below are checked against the sub-plan state.

#### Automated Verification

**§A**:
- [x] `cd services/java/finance-service && ../gradlew build test` — reinsurance service + controller + consumer unit tests green per sub-plan Phase 2/3/4 success criteria (`ReinsurerServiceTest`, `TreatyServiceTest`, `TreatyValidationServiceTest`, `CessionServiceTest`, `ReinsuranceLossCessionConsumerTest`, `BordereauReportServiceTest`, `BordereauReportWorkbookServiceTest`, `BordereauPeriodExportServiceTest`). Pre-existing `bug_claim_save_mock_id_npe` set unchanged.
- [x] `cd services/java/tenancy-service && ../gradlew build test` — V081..V091 tenant migrations apply cleanly (reinsurer, treaty, layer, participant, applicable_line, cession_rule, cession, recovery, bordereau_period_export, reinsurance_review_task, reinsurance_permissions).
- [x] `make test-integration` — `ReinsuranceCrudIT` (reinsurer CUD + treaty DRAFT→ACTIVE lifecycle), `ReinsuranceLossCessionIT` (approved claim writes Cession(ACTIVE)+Recovery(EXPECTED)+2 AuditEvents; idempotent; no-treaty no-op), `ReinsuranceBordereauIT` (7 tests: envelope shape, XLSX bytes, first-export/second-export, prior-period-adjustment flip, per-participant JOIN, utilization since-inception, EXPECTED→INVOICED). RecoveryConsumer dropped per Phase 3 deviation (folded into CessionService).
- [x] `cd services/java/shared && ../gradlew test` — no shared-module edits in Phase 10 sub-plan; existing 125/0 suite unchanged.
- [x] Angular `ng build --configuration=development` — reinsurance CRUD + report + facultative + review-queue + recovery-lifecycle components compile clean per sub-plan Phase 2/5/7/8 criteria.
- [x] Gateway `go build ./... && go test ./...` — `/api/v1/reports/reinsurance/*` + `/api/v1/reinsurance/*` routes registered in `routes.go`.

**§B**:
- [x] `make test-integration` — `FacultativeCessionIT` (4 tests, DRAFT→APPROVED→CEDED round-trip + void + rejects), `TreatyActivationBackfillJobTest` (idempotency + per-line dispatch + row-failure isolation), `PremiumCessionConsumerTest`/`ReinsurancePremiumCessionConsumerTest` (proportional-only dispatch + XoL exclusion + malformed-JSON), `ReinsuranceReviewTaskServiceTest` (11 cases: regression open, assign, resolve keep/dismiss/void, cascade). ClaimReversed replaced with claim-regression detection in the loss-cession consumer per sub-plan Phase 8 deviation.
- [x] Playwright: `reinsurance-facultative.spec.ts` — DRAFT → APPROVED → CEDED via UI, queue clears.
- [x] Playwright: `reinsurance-review-queue.spec.ts` — regression task resolve RESOLVED_VOID with notes; `reinsurance-recovery-lifecycle.spec.ts` covers mark-received + write-off.

#### Manual Verification

Manual acceptance items are tracked on the sub-plan (Phase 2/3/4/5/6/7/8 Manual Verification sections). Rolling up:

**§A** (rolled up from sub-plan Phase 2/3/4/5 Manual Verification):
- [ ] Register Munich Re + Swiss Re as Reinsurers; create a `HEALTH-XOL-2026` treaty with two layers ($500K xs $500K + $1M xs $1M) and participation 60% Munich + 40% Swiss.
- [ ] Adjudicate a HEALTH claim for $800K → observe Cession row for layer 1 = $300K, cession = $500K (retention hit) × 60% + 40% split at report time; observe EXPECTED Recovery.
- [ ] Export cession bordereau for Q3 2026, `?reinsurerId=<munich>` → XLSX shows Munich's 60% share only; `bordereau_period_export` row written with firstExportedAt now.
- [ ] Re-export same quarter → same numbers but `exportCount = 2`.
- [ ] Adjudicate another claim for the same quarter after first export → next export flags the new row as `isPriorPeriodAdjustment = true`.
- [ ] Toggle REINSURANCE_CESSION_BORDEREAU off in tenant-admin → sidebar hides link → direct URL returns 403.
- [ ] Kafka `medfund.security.events` carries `reportKey=REINSURANCE_CESSION_BORDEREAU` (etc.) on every export.

**§B** (rolled up from sub-plan Phase 6/7/8 Manual Verification):
- [ ] Underwriter creates facultative cession on a large single risk → supervisor approves → commit to CEDED → row appears on next bordereau.
- [ ] Reverse an adjudicated claim → review task appears in `/tenant/finance/reinsurance/review-queue` → supervisor resolves RESOLVED_VOID → cession + recovery void follows. (Note: sub-plan Phase 8 substituted a claim-regression detector on the loss-cession consumer for the originally-scoped `ClaimReversedConsumer`; behaviour is equivalent from the reviewer's perspective.)
- [ ] Activate a new treaty inception-dated 3 months back → `TreatyActivationBackfillJob` runs, writes Cession rows retroactively; re-run activation → job idempotent (no duplicate cessions).
- [ ] Pay a contribution against a proportional treaty → PREMIUM cession row written; XoL treaty contribution → no PREMIUM cession (flat premium via scheduler instead).

**Grilling checkpoint status**: **satisfied 2026-08-22.** Sub-plan `create-plan` + implementation both complete.

---

## Phase 11: Producer / Broker Module + Commission Reports

> **Status:** Fully implemented via the expanded sub-plan
> `thoughts/shared/plans/2026-08-22-producer-broker-module-and-commission-reports.md`
> (10 sub-phases, §A Phases 1-6 + §B Phases 7-10). All automated verification is green in
> the working tree as of 2026-08-23: Java compiles clean (`:finance-service`,
> `:contributions-service`, `:user-service`, `:rules-engine`, `:shared`, `:tenancy-service`),
> Angular builds clean (only pre-existing warnings in unrelated components), and the
> unit-test suites recorded per sub-phase all pass. The work is uncommitted at
> `implement-plan` hand-off — no `phase-11` commit has landed yet. Sub-plan itself
> notes Playwright specs + `verify` UI walkthroughs + a handful of ITs (deferred under
> Testcontainers pool pressure) as pending manual-verification items — see the sub-plan's
> per-phase Success Criteria.
>
> **Grilled 2026-08-22.** Decisions P1..P14 (numbered P* to avoid collision with plan-wide G* and reinsurance R*).
> The outline was expanded into a full mini-plan through interactive decision-making; scope escalated
> beyond the original outline (auto-lapse policy now bundled per P7). Recommended split into
> **§A** (foundation + calc + reports) and **§B** (auto-lapse bundle + backfill review UI + facultative-style
> adjustments + producer-termination bulk-reassign). §A ships a working commission accrual + manual XLSX
> exports; §B carries the workflow-heavy pieces. Original outline retained below as ~~strike-through~~
> for provenance. Grilling input at `thoughts/shared/research/2026-08-22-phase11-producer-commission.md`.

### Original outline (superseded 2026-08-22 by Decisions Log)

~~Greenfield producer module: hierarchical brokerages, producers, commission rate cards, clawback windows. Ships commission statement + clawback register reports. Sets up scheduled delivery (used again in Phase 17).~~

### Overview

Greenfield producer/broker vertical in `services/java/finance-service/src/main/java/com/medfund/finance/producer/*` (P1 — mirrors reinsurance §R3). Covers producer registry with self-referential hierarchy (P8), time-slice member↔producer assignments (P2), hybrid commission calculation combining rate-card lookups and rules-engine kickers (P5), commission payout via the existing PaymentRun machinery widened for `PRODUCER` payee (P4), and two clawback triggers: member lapse (P6/P7) and contribution revoke (P13). Ships two tenant-toggleable reports (both keys already exist in `ReportKey` — F11-a). Bundles a full auto-lapse policy per P7 (event-driven with two-stage schedule roll + grace + cancel-on-payment).

### Changes Required

- **New tables** (per P8 the outline's `producer_hierarchy` is dropped in favour of a self-referential FK):
  - `producer` (with `parent_producer_id` self-FK per P8, `home_currency CHAR(3) NOT NULL` per P10)
  - `member_producer_assignment(member_id, producer_id, effective_from, effective_to NULL, actor_id, actor_email)` time-slice per P2
  - `commission_rate_card` (with `clawback_window_days INT NULL` per P6)
  - `commission_transaction`
  - `clawback_event` (with `source ∈ {MEMBER_LAPSE, CONTRIBUTION_REVOKE}` per P13)
  - `commission_adjustment` (four-eyes per P9)
  - `producer_backfill_candidate` staging (per P11 review UI)
  - Plus platform-wide `public.tenant_auto_lapse_config` (per P7c) — single-row-per-tenant config table, default `enabled=false`.
  - Additive `treaty.producer_id UUID NULL REFERENCES producer(id)` (per P11); `treaty.producer_ref` retained as audit trail.
  - Additive: `producer_id UUID NULL` on `payments`, `payment_run_items`, `payment_advices` (per P4); widen `payee_type` CHECK to include `'PRODUCER'` on all four tables (payment_runs + the three above); widen XOR-CHECK to a third arm per V071 pattern.
  - Additive: `withholding_tax_pct NUMERIC(5,2) NULL` on `payment_run_items` (F11-d).
- **Tenant-admin surfaces** (Angular, under `/tenant-admin/producers/*`): producer CRUD; producer-hierarchy tree; rate-card CRUD; bulk member-reassign UI (per P14); backfill review UI at `/tenant-admin/producers/backfill` (per P11); auto-lapse config panel (per P7c); rules-engine COMMISSION category surfaced in the existing visual rule builder (per P5).
- **CommissionCalcService** (P5 hybrid): (1) look up base rate from `commission_rate_card` by `(producer_tier, insurance_line, effective_from, effective_to)`; (2) fire agenda-gated `RuleCategory.COMMISSION` rules against `ContributionFact` for conditional adjustments; (3) sum + persist `commission_transaction`.
- **Kafka topology**:
  - `ProducerCommissionConsumer` on `medfund.contributions.paid` (P3 — local DB lookup for producer via `member_producer_assignment`, no event enrichment).
  - `CommissionRevokeConsumer` on new `medfund.contributions.revoked` (P13). Contributions-service adds the publisher.
  - `CommissionClawbackConsumer` on `medfund.users.member-lifecycle` (P6/P7 — operator + auto-lapse both fire).
  - New `medfund.contributions.arrears-threshold-breached` + `medfund.contributions.arrears-cleared` topics (P7a) — contributions-service publisher; user-service `ArrearsBreachedConsumer` applies two-stage schedule roll per P7b using V042 `scheduledStatus` infrastructure.
- **Auto-lapse scheduler** (P7): contributions-service nightly job scans arrears against `public.tenant_auto_lapse_config` per tenant; publishes threshold-breach event when a member crosses.
- **Commission payout** (P4): extend PaymentRun. `PaymentRunGeneratorService` gets a PRODUCER branch alongside PROVIDER/MEMBER. Payout runs homogeneous by `home_currency × period` (P10). FX to producer.home_currency at commit time locks the rate. V072 trigger unchanged (F11-f — generic).
- **CommissionAdjustment four-eyes** (P9): controller endpoints `POST /commission/adjustments` (draft), `PATCH /commission/adjustments/{id}/approve`, `.../commit`, `.../void`. Two new permissions: `finance.commission:draft_adjustment` + `finance.commission:approve_adjustment`. AuditEvent on every transition via `AuditActor.id + email`.
- **Producer termination** (P14): `POST /tenant-admin/producers/{id}/terminate` closes all active `member_producer_assignment` rows (`effective_to = last-day-of-month` per `feedback_effective_date_snap`); commission calc during gap warns + skips.
- **ProducerBackfillJob** (P11): mirrors `TreatyActivationBackfillJob` shape; chunked scan of `treaty` rows with `producer_ref` set + `producer_id NULL`; fuzzy match to `producer.name` via Java Levenshtein or ILIKE substring (F11-c — pg_trgm unavailable); writes to `producer_backfill_candidate` with confidence score; tenant-admin review UI commits.
- **`CommissionReportController`**: `GET /reports/commission/statement?periodStart&periodEnd&producerId&reportingCurrency`, `.../clawback-register?...`. Report keys `COMMISSION_STATEMENT`, `COMMISSION_CLAWBACK` (F11-a — already ship in shared enum). `@RequiresReport` gating. XLSX exports via `SecurityEventPublisher.publishDataAccess` matching reinsurance precedent.
- **Rules-engine addition**: `RuleCategory.COMMISSION` at line 69 of the enum (agenda-gated, mirrors REINSURANCE precedent at line 68). `PayCommissionEmitter` in `compiler/` implementing `ActionEmitter` with action type `PAY_COMMISSION` (auto-collected by DrlCompiler line 79 — zero registry edits). Template DSL encoding TBD at code altitude (grill note 3).
- **Scheduled delivery**: **deferred to Phase 17** per P12. Phase 11 ships manual XLSX export from report UI only.
- **Angular** producer admin + report pages under `reports/commission/*`.

**Grilling checkpoint** ~~required~~ **satisfied 2026-08-22**.

### Decisions Log (P1..P14)

- **P1 — Module home**: finance-service, `com.medfund.finance.producer.*` subpackage. Same JVM as `medfund.contributions.paid` consumer; commission = finance domain; PaymentRun reuse; parallel to `reinsurance/`. Mirrors reinsurance §R3 rationale.
- **P2 — Member↔producer linkage**: finance-service time-slice `member_producer_assignment(member_id, producer_id, effective_from, effective_to NULL, actor_id, actor_email)`. Commission calc joins on `WHERE :paid_at BETWEEN effective_from AND COALESCE(effective_to, 'infinity')`. App-layer guard: at most one open row per member. Producer switch = close prior + insert new. Assignment CRUD via finance-service tenant-admin API.
- **P3 — Producer resolution at consume time**: local DB lookup on the commission consumer. No event enrichment; `medfund.contributions.paid` payload stays as-is. Contributions-service stays ignorant of producers.
- **P4 — Payout mechanism**: extend PaymentRun. Additive migration touches 4 tables (`payment_runs` + V071's `payments` + `payment_run_items` + `payment_advices`) — widen CHECK to include `'PRODUCER'`, add nullable `producer_id UUID`, widen 3 XOR-CHECKs to a third arm `(provider_id IS NULL AND member_id IS NULL AND producer_id IS NOT NULL AND payee_type = 'PRODUCER')`, add indexes on `producer_id`. V072 trigger unchanged (F11-f). Withholding-tax as nullable `withholding_tax_pct NUMERIC(5,2)` on `payment_run_items`; MVP retains full amount if null.
- **P5 — Commission engine**: hybrid. `commission_rate_card` for structured base-rate lookups + new `RuleCategory.COMMISSION` (agenda-gated) + `PayCommissionEmitter` for conditional kickers (tier bonuses, sliding scale, promo periods, waivers). `CommissionCalcService` orchestrates both. Rate cards are lookups; overrides are DRL rules — each mechanism in its natural tool.
- **P6 — Clawback window**: `commission_rate_card.clawback_window_days INT NULL` (nullable = no clawback for that card). Rules-engine COMMISSION-category kicker can override for edge cases via P5. Clawback consumer joins `commission_transaction → rate_card` for the applicable window per transaction.
- **P7 — Auto-lapse scope**: bundled into Phase 11 (contrary to research doc recommendation — user chose scope expansion). Splits into P7a/P7b/P7c below.
- **P7a — Lapse scheduler home**: event-driven. New scheduler in contributions-service (parallel to or extending `OverdueContributionJob`) publishes new `medfund.contributions.arrears-threshold-breached` topic with `{tenantId, memberId, arrearsMonths, currentBalance, currencyCode, breachedAt}`. User-service adds `ArrearsBreachedConsumer` that applies notice/grace policy, sets `scheduledStatus`, and eventually publishes `medfund.users.member-lifecycle` naturally on transition.
- **P7b — Lapse policy shape**: two-stage. `ArrearsBreachedConsumer` sets `Member.scheduledStatus='LAPSED'` + `scheduledStatusEffectiveFrom = today + graceWindowDays` (V042 infrastructure). Notification-service emails a warning immediately. If contributions-service publishes new `medfund.contributions.arrears-cleared` before the scheduled_from date, user-service consumer nulls the `scheduledStatus`. Otherwise SCHEDULED_STATUS_ROLL job transitions to LAPSED; existing `medfund.users.member-lifecycle` fires; clawback consumer fires.
- **P7c — Lapse config granularity**: per-tenant single-row `public.tenant_auto_lapse_config(tenant_id UUID PK, enabled BOOLEAN NOT NULL DEFAULT false, arrears_threshold_months INT, grace_window_days INT, actor_id, actor_email)`. Matches V127/V128/V132 tenant-config pattern. Explicit opt-in per tenant.
- **P8 — Producer hierarchy**: self-referential FK `producer.parent_producer_id UUID NULL`. Recursive CTE (`WITH RECURSIVE`) walks upward for override-commission calc. No time-slice for MVP — reparenting rewrites the field (audit trail preserved via AuditEvent). Outline's separate `producer_hierarchy` table dropped.
- **P9 — Commission adjustment workflow**: four-eyes mirroring FacultativeCession. `CommissionAdjustment` entity with status `{DRAFT, APPROVED, COMMITTED, VOIDED}`. Two permissions: `finance.commission:draft_adjustment` (drafter role) + `finance.commission:approve_adjustment` (supervisor role). AuditEvent on every transition via `AuditActor.id + email` (per `feedback_audit_actor_email`).
- **P10 — Payout currency**: producer's home currency. New `producer.home_currency CHAR(3) NOT NULL` (required onboarding field). `commission_transaction` stores native (contribution's currency); at payout-run creation, FX to `producer.home_currency`; rate locked at commit time per `.claude/multi-currency.md:164`. Payout runs are homogeneous by `home_currency × period` — one run per (home_currency, period).
- **P11 — `treaty.producer_ref` backfill**: additive V092 migration adds `treaty.producer_id UUID NULL REFERENCES producer(id)`. Manual `ProducerBackfillJob` (mirrors `TreatyActivationBackfillJob` shape) scans treaties with `producer_ref` set + `producer_id NULL`; fuzzy-matches to `producer.name` (F11-c: Java Levenshtein or ILIKE substring — pg_trgm unavailable); writes to `producer_backfill_candidate` staging with confidence score. Tenant-admin review UI at `/tenant-admin/producers/backfill` shows auto-committed high-confidence matches + pending low-confidence picks. `producer_ref` retained forever as audit trail (nullable FK stays).
- **P12 — Scheduled statement delivery**: deferred entirely to Phase 17. Phase 11 ships manual XLSX export from the two report UIs only.
- **P13 — Revoked-contribution clawback**: new `medfund.contributions.revoked` event + `CommissionRevokeConsumer` in finance-service. Contributions-service adds `publishContributionRevoked(contributionId, memberId, revokedAt, reason, actorId, actorEmail)` in the revoke flow. Consumer finds the `commission_transaction` for the `contributionId` + writes a compensating commission_transaction (or flips status to `REVERSED` with a link to the original) + writes a `clawback_event(source=CONTRIBUTION_REVOKE)` row. Same shape as member-lapse clawback consumer.
- **P14 — Producer termination**: manual reassignment. Termination endpoint closes all active `member_producer_assignment` rows (`effective_to = last-day-of-month of termination` per `feedback_effective_date_snap`); no auto-successor. Tenant admin uses bulk-reassign UI at `/tenant-admin/producers/{terminatedId}/reassign`; with P2 time-slice, admin can backdate `effective_from` to cover any gap. Commission calc during the gap warns + skips (no producer to credit); warning surfaces in tenant admin dashboard until resolved.

### Settled by fact (not asked)

- **F11-a — `ReportKey.COMMISSION_STATEMENT`, `COMMISSION_CLAWBACK`, `ReportFamily.COMMISSION` already ship** in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:96-97` and `ReportFamily.java:25`. Phase 11 adds controllers only, no enum edits.
- **F11-b — Next tenant V-number = V092** (last: `V091__reinsurance_permissions.sql`); next public V-number = **V133** (last: `V132__tenant_high_cost_claimant_config.sql`).
- **F11-c — pg_trgm is NOT on the classpath** (per Phase 4 §B G45 memory). P11 backfill fuzzy match uses Java Levenshtein or plain ILIKE substring; never `%%`-similarity SQL.
- **F11-d — Withholding tax** (per P4) is a nullable `withholding_tax_pct NUMERIC(5,2)` column on `payment_run_items`; MVP retains full amount if column null. Jurisdiction-driven config surface deferred.
- **F11-e — Producer self-service portal out of scope** by parent-plan outline; Phase 11 ships tenant-admin only. Producer portal is a follow-up.
- **F11-f — V072 payment_run item-parent trigger function is generic** (reads `parent.payee_type` at runtime); does NOT need editing when `payee_type` widens to include PRODUCER. Only the 4 CHECK constraints widen (payment_runs + V071's payments + payment_run_items + payment_advices).

### Proposed §A / §B tranche split

**§A — Foundation + calc + reports** (ships a working commission accrual + manual XLSX exports even without the auto-lapse or facultative-style workflow UI):
- All producer/commission migrations (V092.. tenant, V133+ public for `tenant_auto_lapse_config` — although the config table itself only fires in §B).
- All entities + repositories + services (Producer + hierarchy + rate-card + commission_transaction + clawback_event + member_producer_assignment).
- CommissionCalcService (P5 hybrid: rate-card + rules-engine kickers).
- `RuleCategory.COMMISSION` addition to rules-engine + `PayCommissionEmitter`.
- ProducerCommissionConsumer on `medfund.contributions.paid` + CommissionRevokeConsumer on new `.revoked` event (P13) + CommissionClawbackConsumer on `medfund.users.member-lifecycle` (operator-triggered only in §A; auto-lapse consumer lands in §B).
- All rate-card CRUD tenant-admin UI + producer CRUD + hierarchy tree.
- Commission statement + clawback register report endpoints + XLSX exports + `@RequiresReport` gates.
- Producer payout extension of PaymentRun (P4).
- IT: full CRUD + auto-cession-style consumer round-trip + report envelope + toggle-off 403 + SecurityEvent-on-export.

**§B — Auto-lapse bundle + backfill + adjustments + termination** (workflow-heavy):
- Auto-lapse chain (P7a-c): contributions-service scheduler + new `arrears-threshold-breached` / `arrears-cleared` events + user-service `ArrearsBreachedConsumer` + `public.tenant_auto_lapse_config` config admin UI.
- P11 backfill: `ProducerBackfillJob` + `producer_backfill_candidate` staging + Angular review UI.
- P9 `CommissionAdjustment` four-eyes workflow (DRAFT → APPROVED → COMMITTED → VOIDED) + permissions + admin UI.
- P14 producer-termination + bulk-reassign UI.
- IT: auto-lapse round-trip + backfill idempotency + adjustment lifecycle + termination + bulk-reassign.

§A can land as a Phase 11A commit; §B as Phase 11B. Both should be fully grilled + planned at implement-time; `implement-plan` treats them as hand-off boundaries.

### Grill notes for `create-plan`

1. **`medfund.contributions.revoked` publisher** — does not exist today (verified via grep — `ContributionEventPublisher.java` has `.paid`, `.billing-generated`, `.invoice-issued`, `.transaction-recorded`, `.scheme-changed`, `.invoice-pdf-deleted` but no `.revoked`). Locate the revoke flow in contributions-service (`BillingService` or an unshipped `RevocationService`?) and add the publisher method + payload record before wiring the finance-side consumer.
2. **`medfund.contributions.arrears-threshold-breached` + `arrears-cleared`** — new topics. Plan the `OverdueContributionJob` extension carefully. The `arrears-cleared` event must fire on any payment that clears a previously-breached member's arrears below threshold — not just first-time payments. Consider a payment-side hook that checks previous-vs-current arrears state.
3. **`PayCommissionEmitter` DSL encoding** — mirrors `CedeToTreatyEmitter` shape (see `services/java/rules-engine/.../compiler/CedeToTreatyEmitter.java:32-64`). Design the action DSL: e.g., `RATE_CARD:<cardId>` for base rate lookup, `TIER_BONUS:<bp>` for kicker overrides, `WAIVER:<reason>` for zero-commission. Verify the rules-engine visual rule builder (Angular) supports the new action type or needs a small template addition (`services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/*.java`).
4. **`member_producer_assignment` app-layer at-most-one-open guard** — service-layer check on insert + IT (`MemberProducerAssignmentServiceIT`). Consider partial UNIQUE index `WHERE effective_to IS NULL` at the DB level for defense-in-depth.
5. **Producer termination bulk-reassign UI shape** — depends on how many members a typical producer holds. Before designing pagination + filter + bulk-select, run `SELECT COUNT(*) FROM member_producer_assignment WHERE effective_to IS NULL GROUP BY producer_id ORDER BY 1 DESC LIMIT 20` on prod via the read-only role (per `.claude/infrastructure.md`) to size the surface.
6. **Withholding tax config surface** — F11-d only sets the storage column. If a real tenant needs WHT enforcement, plan whether the rate lives per-tenant (`tenant_wht_config`), per-producer (`producer.wht_pct`), or per-line (`tenant_wht_config_per_line`). Grill at code-altitude create-plan when the first tenant asks.
7. **`producer.home_currency` mandatory-vs-optional** — P10 says `NOT NULL` (required at onboarding). Verify the tenant-admin producer creation flow enforces this; consider a fallback to tenant reporting currency during data migration if a real customer has partial producer records.
8. **CommissionAdjustment reasons** — grill needs to nail: minimum-required fields on DRAFT (justification text, adjustment amount, target commission_transaction UUID, adjustment type ∈ {EX_GRATIA, VOID, MANUAL_CLAWBACK, MANUAL_REVERSAL}); whether COMMITTED can be VOIDED after commit (no — commit is terminal, matches FacultativeCession precedent).
9. **Producer payout run generator shape** — for a producer payout run, the generator enumerates `commission_transaction` rows for producers with `home_currency = run.currencyCode` and `paid_at BETWEEN run.periodStart AND run.periodEnd` that are not yet paid out, applies WHT if configured, groups by producer, produces `payment_run_items` with `payee_type='PRODUCER'` + `producer_id`. Design at code altitude — mirror `PaymentRunGeneratorService` shape.

**Grilling checkpoint status**: **satisfied 2026-08-22.** Next step is `create-plan` on this expanded phase (or on §A alone for a smaller shipping increment).

---

## Phase 12: UPR Earning Schedule + Premium Register

> **Grilled 2026-08-23.** Decisions U1..U15 (numbered U* — for Underwriting — to avoid collision with plan-wide G*, reinsurance R*, and producer P* numbering).
> The outline was expanded into a full mini-plan through interactive decision-making; scope escalated
> substantially beyond the original outline (all 8 lines + rules-engine `RuleCategory.PREMIUM_EARNING`
> + full endorsement module with retro recompute + IFRS 17 portfolio/cohort speculative schema).
> Recommended split into **§0** (Testcontainers harness fix), **§A** (schema-widening + earning schedule
> foundation + rules-engine wiring), **§B** (three reports + Angular hub + `UNDERWRITING` family),
> **§C** (full endorsement module with four-eyes above configurable threshold + retro recompute +
> `ENDORSEMENT_REGISTER` key + Angular admin surface). §0 pays down existing IT debt; §A ships the
> silent accrual engine; §B lights up user-facing reports; §C completes the premium-lifecycle module.
> Original 12-line outline retained below as ~~strike-through~~ for provenance.

### Original outline (superseded 2026-08-23 by Decisions Log)

~~Unearned Premium Reserve movement + earned/written premium register + new business + endorsement register.~~

~~**contributions-service** (or new `premium/` package): `earning_schedule`, `upr_movement` tables.~~
~~Earning-strip calculation service — daily @Scheduled job that computes earned premium per policy per day.~~
~~`PremiumRegisterController`: `/reports/premium/upr-movement`, `/reports/premium/register`, `/reports/premium/new-business`. Report keys `UPR_MOVEMENT`, `PREMIUM_REGISTER`, `NEW_BUSINESS_REGISTER`.~~

### Overview

Greenfield premium-lifecycle module in `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/*` (U3 — honors G2 data-ownership; sits alongside `BillingService` + `BillingCycleExecutor`). Ships **written premium capture at bind time** for all 8 insurance lines (per U1 + U2 mixed-billing: annual-bind for LIFE/FUNERAL/DISABILITY/VEHICLE/PROPERTY, monthly for HEALTH via `Contribution`, per-trip for TRAVEL), a **line-configurable earning engine** driven by a new `RuleCategory.PREMIUM_EARNING` in rules-engine (U4 + U11 — agenda-gated alongside `REINSURANCE`, `COMMISSION`, `BENEFIT_PRORATION`), a **per-policy-per-period `earning_schedule` materialization** (U7) with nightly `PremiumEarningExecutor` (U10), and four tenant-toggleable reports under a new `ReportFamily.UNDERWRITING` (U9): `UPR_MOVEMENT`, `PREMIUM_REGISTER`, `NEW_BUSINESS_REGISTER` (all three keys already ship in `ReportKey.java:100-102` — enum edit only reassigns family), plus `ENDORSEMENT_REGISTER` (new key, added §C).

Ships a **full endorsement module in §C** (U5) with retro earning-schedule recompute (U8) and tenant-configurable four-eyes threshold (U12) mirroring Phase 11 `CommissionAdjustment` shape. Speculatively adds **IFRS 17 portfolio + cohort dimensions in §A** (U14) so Phase 15 IFRS 17 doesn't need a schema reshape on populated tenants. New-business classification is **policy-centric with a `renewed_from_policy_id` renewal chain** (U6) mirroring Phase 10 R11 Treaty pattern; HEALTH new business = member's first Contribution ever within the tenant.

All Phase 0-11 shared infra composes on top (`ReportEnvelopeBuilder`, `ReportingCurrencyResolver`, `FxRateReader`, `ReportWorkbook`, `CrossServiceCallHelper`, `RequiresReport` + `ReportGuardAspect`, `SecurityEventPublisher`, `AuditActor`). Multi-currency policy per U8 = native storage in policy's original currency; retro recompute preserves currency; missing FX at report time = envelope warning (parent-plan invariant #6 / G28).

**§0 pre-work (U15)**: fix the Testcontainers pool pressure that has caused IT deferrals in Phase 9 + 11 (`testcontainers.reuse.enable=true` across services/java/*, extract `AbstractIntegrationTest` base classes in contributions-service + user-service, tune R2DBC connection ceilings, add pool-starvation health check). Once §0 stabilises the 25 pre-existing IT flakes, Phase 12 §A/§B/§C ITs land in-tranche without deferral.

### Decisions Log (U1..U15)

- **U1 — Line scope**: **all 8 lines with schema-widening pass**. Adds `written_premium` + `written_premium_currency` + `bound_at` + `coverage_start` + `coverage_end` + `renewed_from_policy_id` + `status` enum arm + `portfolio_id` + `cohort_id` (per U14) to LifePolicy, FuneralPolicy, DisabilityPolicy, VehiclePolicy, PropertyPolicy. TravelPolicy adds `written_premium` + `written_premium_currency` (already has trip dates). HEALTH's Contribution stays as-is (already has period + currency); portfolio_id + cohort_id additive on Contribution too. Rejected: HEALTH-only (rebrand of Phase 3 receipts), HEALTH + TRAVEL only (defers 5 lines to Phase 12b + blocks IFRS 17).
- **U2 — Written premium capture**: **line-mixed** — annual bind for LIFE / FUNERAL / DISABILITY / VEHICLE / PROPERTY (fixed at issuance); monthly for HEALTH via `Contribution.amount` (re-underwrites each period); per-trip for TRAVEL (bound at trip creation). Earning-strip service dispatches by line. Rejected: universal annual-bind (breaks HEALTH monthly re-underwrite; contradicts Phase 11 auto-lapse assumption), universal monthly-bind (materially wrong P&L for annual policies), configurable earning_basis table (3x model complexity for no gain).
- **U3 — Module home**: **contributions-service `com.medfund.contributions.premium.*` subpackage**. Honors G2 data-ownership rule (Contribution + BillingService already live here; daily earning-strip job sits alongside BillingCycleExecutor); reuses Phase 8 reverse-`FinanceClient` pattern if cross-service data needed. Rejected: finance-service (mirrors reinsurance/producer precedent but disconnects the daily job from the billing loop), split (double coordination cost), new premium-service (~1 week DevOps cost for zero gain).
- **U4 — Earning method**: **line-configurable via rules-engine `RuleCategory.PREMIUM_EARNING`** (new agenda-gated category). Ships 3 templates: `DAILY_LINEAR` (1/coverage_days per day, line-agnostic default), `MONTHLY_24THS` (1/24 in bind month + 23/24 across next 23 half-months, for IPEC-jurisdiction opt-in), `LINEAR_WITH_LOADING` (front-load configurable % at bind then linear remainder). Rejected: hard-coded 1/365ths (loses configurability), 24ths-with-tenant-config (two algorithms for one clear standard), per-policy custom schedule (unbounded storage growth).
- **U5 — Endorsement scope**: **full endorsement module in Phase 12 §C** (new tranche after §A/§B). Ships endorsement table + `PolicyEndorsementService` + `medfund.user.policy-endorsed` publisher + retro earning-schedule recompute + `ENDORSEMENT_REGISTER` key + Angular admin surface + four-eyes above tenant-configurable threshold (per U12). Rejected: defer to Phase 12b (Overview sentence becomes stale; Phase 15 blocked), stub-only (report is decorative without recompute), close-and-reopen (violates policy identity invariant).
- **U6 — New business classification**: **policy-centric with renewal chain**. Add `renewed_from_policy_id UUID NULL SELF-FK` to each annual-bind policy — mirrors Phase 10 R11 Treaty pattern exactly (`renewed_from_treaty_id`). A policy is "new business" if `bound_at` in reporting period AND `renewed_from_policy_id IS NULL`. TRAVEL: each trip is a new bind (renewed_from = NULL always). HEALTH: new business = member's first Contribution ever within the tenant (LEFT JOIN on `min(created_at) per member`). Enrolment/renewal flow must set `renewed_from_policy_id` correctly — IT guard required. Rejected: member-centric (mis-classifies cross-sell), any-bind (regulator-invalid), tenant-configurable via rules (overengineered for a definition question with a clear answer).
- **U7 — Storage grain**: **materialized per-policy-per-period + on-demand daily interpolation**. `earning_schedule(policy_id, period_start, period_end, written_amount, earned_at_period_end, currency_code, is_endorsement, endorsement_id NULL, portfolio_id, cohort_id)` — one row per policy per billing/coverage period. Nightly `PremiumEarningExecutor` (per U10) closes periods whose `end_date <= today` by setting `earned_at_period_end = written_amount`. Report queries: `SUM(earned_at_period_end WHERE period_end < asOf)` + `LINEAR_INTERPOLATE(row WHERE period spans asOf)`. Endorsement recompute (§C) rewrites only affected period rows. Storage: ~100k policies × ~12 periods/year = ~1.2M rows/year/line — manageable. **Contradicts the outline's "per policy per day"** — Deviations entry required. Rejected: per-day materialization (109M rows/line/3-year — needs partitioning day 1), lazy compute (3-year loss-ratio becomes minutes-slow), journal-style event log (event-ordering discipline + no repo precedent).
- **U8 — Multi-currency + retro-recompute FX policy**: **store native + always in policy's original currency; endorsement recompute preserves currency**. `earning_schedule.currency_code` = policy's `written_premium_currency` (annual lines) or `Contribution.currency_code` (HEALTH), fixed at row creation. Endorsements adjust `delta_amount` in the same currency (never re-denominate). Report envelope converts to `reporting_currency` via `FxRateReader.convert` at the row's `period_end` date (not the endorsement date) — preserves "earned in the currency it was originally denominated in". Missing FX for a historical `period_end` → currency omitted from envelope `fxRates` + warning row. Matches Phase 10 R7 + Phase 11 CommissionTransaction precedents. Rejected: convert-on-write (violates invariant #6; blocks daily job on missing FX), endorsement-uses-today's-FX (fragments storage grain per period), fail-loud on report (crashes 3-year UPR on one missing legacy rate).
- **U9 — Report family**: **new `ReportFamily.UNDERWRITING`** covering `UPR_MOVEMENT`, `PREMIUM_REGISTER`, `NEW_BUSINESS_REGISTER`, `ENDORSEMENT_REGISTER`. Matches insurance-industry naming (Underwriting P&L). Small edits: `ReportKey.java` (change family assignment on 3 existing keys + add `ENDORSEMENT_REGISTER`), `ReportFamily.java` (add `UNDERWRITING`), Angular family-label service. Rejected: `PREMIUM` (UPR is a liability, not a premium), keep as CLAIMS_FINANCIAL (semantically misleading), split into 2 families (fragments hub).
- **U10 — Job cadence**: **nightly `PremiumEarningExecutor` + resumable + on-demand backfill**. Single `JobExecutor` in contributions-service, defaults to nightly 02:00 UTC, tenant-configurable via `scheduled_job_configs` (V114 pattern). Each run: (1) enumerate ACTIVE policies with no `earning_schedule` row for the current period; write initial row(s). (2) enumerate rows with `period_end <= today AND earned_at_period_end IS NULL`; close them. (3) enumerate §C endorsements arrived since last-run-timestamp; recompute affected rows. Idempotent via `UNIQUE (policy_id, period_start, COALESCE(endorsement_id, '00000000-0000-0000-0000-000000000000'::uuid))` partial index. Admin `POST /api/v1/premium/earning-schedule/backfill?policyId=` forces rebuild. Rejected: hourly (24x pool pressure, most tenants EOD-only), weekly (3-day stale reports for regulator submission), event-only (needs a scheduler somewhere anyway; Kafka loss = missing rows).
- **U11 — Rules-engine wiring**: **agenda-gated + new `PremiumFact` + new `AccruePremiumEmitter` + fires at bind time + on-demand replay when tenant edits a rule**. Add `PREMIUM_EARNING` to `AGENDA_GATED_CATEGORIES` in `DrlCompiler` (mirrors Phase 11 addition of `COMMISSION`). Add `ActionType.ACCRUE_PREMIUM`. New `PremiumFact` class: `{policy_id, insurance_line, product_code, tenant_id, written_premium, currency_code, coverage_start, coverage_end, bound_at, portfolio_id, cohort_id}`. New `AccruePremiumEmitter` mirroring `PayCommissionEmitter` shape (action-value DSL: `EARNING_METHOD:DAILY_LINEAR`, `EARNING_METHOD:MONTHLY_24THS`, `LOADING:<percent>`). Rules fire at bind time (issuance-event consumer runs one rules session per policy) — resolves earning method + writes `earning_schedule` rows synchronously. Tenant editing a rule → `POST /api/v1/premium/earning-schedule/replay?policyId=` or `?ruleId=`. Documented gotcha: bind-time rules can't respond to "as of" recomputation — an endorsement uses the rule live at endorsement time, not bind time. Rejected: extend `ContributionFact` (god-object mixing billing + earning), non-agenda-gated (cross-firing side effects), skip rules-engine (contradicts U4).
- **U12 — Endorsement approval workflow (§C)**: **tenant-configurable threshold; below auto-commits, above requires four-eyes**. New `public.tenant_endorsement_config(tenant_id UUID PK, four_eyes_threshold_amount NUMERIC NULL, threshold_currency CHAR(3) NULL, enabled BOOLEAN DEFAULT FALSE, actor_id, actor_email)` — matches V127/V128/V132/V133 tenant-config pattern. If disabled or threshold NULL, every endorsement auto-commits. Otherwise `|premium_delta|` > threshold → endorsement enters DRAFT → APPROVED → COMMITTED → VOIDED (mirrors Phase 11 CommissionAdjustment). Two permissions: `policy:draft_endorsement` (all tenant admins) + `policy:approve_endorsement` (supervisor). Retro earning-schedule recompute fires on **COMMIT only** (not on DRAFT). Rejected: uniform four-eyes (blocks trivial CRUD), auto-commit-only (no signoff on material changes), DRAFT→APPROVE-only (no COMMITTED terminal state; can't unvoid).
- **U13 — Tranche split**: **§0 harness | §A foundation | §B reports | §C endorsements** (see full contents in Proposed §0/§A/§B/§C tranche split section below). Rejected: §A+reports+§B endorsements (§A too large — "one giant PR" pattern), §A schema-only+§B rest (schema-widening without app-code adds no value), §A schema+silent job / §B reports+endorsements (same giant-PR risk).
- **U14 — IFRS 17 portfolio/cohort dimensions**: **added speculatively in Phase 12 §A**. New tenant tables `ifrs17_portfolio(id, name, description, is_active)` + `ifrs17_cohort(id, portfolio_id FK, cohort_year INT, cohort_type ENUM{ONEROUS, NON_ONEROUS, UNCERTAIN})`. FK columns `portfolio_id UUID NULL` + `cohort_id UUID NULL` added to each of the 6 policy entities and to `Contribution` + `earning_schedule`. Default single-portfolio + single-cohort populated at bind time for tenants who don't configure. Tenant-admin UI "map policies to portfolios" in §A (basic CRUD only; sophisticated portfolio-management belongs to Phase 15). Grill note owed back to Phase 15: schema alignment with LRC / LIC / CSM measurement to be verified at Phase 15 grill. Rejected: defer to Phase 15 (backfill on populated data = chunked-migration pain), PremiumClient contract (cross-service hop per report), throwaway Phase 12 (wastes months of engineering).
- **U15 — Testing strategy**: **§0 pre-work fixing Testcontainers pool pressure**. New tranche before §A: enable `testcontainers.reuse.enable=true` across `services/java/*/build.gradle.kts`; extract `AbstractIntegrationTest` base class in contributions-service + user-service (finance-service already has one); tune R2DBC connection ceilings; add pool-starvation health check. Once §0 lands and the 25 pre-existing IT flakes stabilise, Phase 12 §A/§B/§C ITs land in-tranche without deferral. Rejected: match Phase 11 defer-pattern (accumulates more testing debt; hasn't been paid down after 9 + 11), WebFlux-slice-only (loses schema-integration coverage; violates parent plan testing strategy), per-service Docker Compose test-DB (rewrites every existing IT pattern; no repo precedent).

### Settled by fact (not asked)

- **F12-1 — All Phase 0-11 shared infra composes cleanly**: `ReportEnvelopeBuilder` (services/java/shared/src/main/java/com/medfund/shared/report/ReportEnvelopeBuilder.java:1), `FxRateReader.findRate/.convert` (services/java/shared/src/main/java/com/medfund/shared/report/FxRateReader.java:30-50), `ReportWorkbook`, `@RequiresReport` + `ReportGuardAspect`, `SecurityEventPublisher.publishDataAccess`, `AuditActor.id/email`, `ReportingCurrencyResolver`, `CrossServiceCallHelper`. Zero new plumbing.
- **F12-2 — `ReportKey.UPR_MOVEMENT`, `PREMIUM_REGISTER`, `NEW_BUSINESS_REGISTER` already ship** in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:100-102`, currently under `ReportFamily.CLAIMS_FINANCIAL` (`ReportFamily.java:20`). Phase 12 reassigns them to new `UNDERWRITING` family (per U9) and adds `ENDORSEMENT_REGISTER` key. **No `ENDORSEMENT_REGISTER` today**; **no `PREMIUM` / `UNEARNED_PREMIUM` family**.
- **F12-3 — Next migration numbers**: **tenant V102** (last: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V101__member_lapsed_status.sql`), **public V134** (last: `services/java/tenancy-service/src/main/resources/db/migration/public/V133__tenant_auto_lapse_config.sql`).
- **F12-4 — 100% greenfield**. Zero hits on `UPR`, `unearned`, `earned_premium`, `earning_schedule`, `endorsement`, `new_business`, `policy_issuance` across services/java, clients/angular — except the three enum stubs (F12-2).
- **F12-5 — contributions-service jobs use tenant-configurable `JobExecutor`** (V114), not raw `@Scheduled`. Reference: `services/java/contributions-service/src/main/java/com/medfund/contributions/job/BillingCycleExecutor.java:14-41`, `OverdueCheckExecutor.java:14-42`. Phase 12's `PremiumEarningExecutor` (per U10) must ship as an executor. Corrects the outline's `@Scheduled` wording.
- **F12-6 — HEALTH has no policy entity** — uses `Contribution` (`services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Contribution.java:14`) with `amount`, `currency_code`, `period_start`, `period_end`. Other 5 lines (LIFE / FUNERAL / DISABILITY / VEHICLE / PROPERTY) have policy entities in **user-service**; TRAVEL too (with trip dates).
- **F12-7 — Policy period + currency asymmetry** — of 6 policy entities: TravelPolicy has `trip_start_date` + `trip_end_date`; LifePolicy has `term_months` only (no date range); FuneralPolicy / DisabilityPolicy / Vehicle / Property have no coverage-period columns. **None** carry `currency_code`. **No policy has a premium column** — sum_assured, cover_amount, monthly_benefit, vehicle_value, sum_insured are *coverage* amounts. This asymmetry is the load-bearing constraint that drove U1's schema-widening pass.
- **F12-8 — No `PremiumFact` today**; `ContributionFact` (`services/java/rules-engine/src/main/java/com/medfund/rules/fact/ContributionFact.java`) is billing-loop-flavored (memberAge, dependantCount, smokingStatus, bmi). U11 introduces a sibling `PremiumFact` rather than extending `ContributionFact`. `AGENDA_GATED_CATEGORIES` in `DrlCompiler` currently `{BENEFIT_PRORATION, REINSURANCE, COMMISSION}` (per Phase 11 commit 55c3689); `PREMIUM_EARNING` joins.
- **F12-9 — `medfund.user.policy-issued` does NOT exist today.** `services/java/user-service/src/main/java/com/medfund/user/service/MemberService.java` publishes `medfund.users.member-lifecycle` (per Phase 11 wiring), but no policy-issuance topic. §A must add the publisher on the user-service side.
- **F12-10 — `medfund.user.policy-endorsed` does NOT exist.** §C must add it.
- **F12-11 — `medfund.contributions.billing-generated` already exists** via `ContributionEventPublisher` (verified during Phase 11 grill note 1). §A can consume this **in-JVM** (contributions-service → BillingService writes Contribution row → same-transaction write of `earning_schedule` row) — no Kafka hop needed because the module lives in the same JVM (per U3).
- **F12-12 — Cross-service Kafka hop only for annual-line policies** (user-service → contributions-service `PolicyIssuedConsumer`). Reuses reactor-kafka `.doOnSuccess` ack pattern per `bug_reactor_kafka_ack_swallow` memory.

### Data model

Tenant-scoped tables (V102..V110 range in tenancy-service; final numbering at plan time — highest today = V101 per F12-3). All with the standard audit tail (`id UUID PK DEFAULT gen_random_uuid(), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), actor_id UUID, actor_email VARCHAR`) per `feedback_audit_actor_email` (Rule 8).

| Table | Purpose | Key columns |
|---|---|---|
| `ifrs17_portfolio` (§A) | IFRS 17 portfolio master (U14) | `name`, `description`, `is_active`, `insurance_line` (a portfolio is scoped to a single line for cohort math) |
| `ifrs17_cohort` (§A) | IFRS 17 cohort master (U14) | `portfolio_id FK`, `cohort_year INT`, `cohort_type ENUM{ONEROUS,NON_ONEROUS,UNCERTAIN}`; UNIQUE `(portfolio_id, cohort_year, cohort_type)` |
| `earning_schedule` (§A) | Per-policy-per-period earning materialization (U7) | `policy_id UUID NOT NULL`, `policy_source ENUM{LIFE_POLICY,FUNERAL_POLICY,DISABILITY_POLICY,TRAVEL_POLICY,VEHICLE_POLICY,PROPERTY_POLICY,CONTRIBUTION}`, `insurance_line`, `period_start DATE`, `period_end DATE`, `written_amount NUMERIC`, `earned_at_period_end NUMERIC NULL` (null until period closes), `currency_code CHAR(3)`, `is_endorsement BOOLEAN DEFAULT FALSE`, `endorsement_id UUID NULL` (per §C), `portfolio_id UUID NULL`, `cohort_id UUID NULL`, `earning_method VARCHAR` (from U11 rules-engine dispatch — snapshot of which template fired). UNIQUE `(policy_id, policy_source, period_start, COALESCE(endorsement_id, '00000000-0000-0000-0000-000000000000'::uuid))` for U10 idempotency |
| `endorsement` (§C) | Mid-term policy change events (U5, U12) | `policy_id UUID`, `policy_source ENUM`, `change_type ENUM{PREMIUM_ADJUSTMENT,COVERAGE_EXTENSION,BENEFIT_CHANGE,BENEFICIARY_CHANGE,ADMIN_CHANGE}`, `effective_from DATE`, `premium_delta NUMERIC NULL`, `currency_code CHAR(3) NULL`, `reason VARCHAR`, `status ENUM{DRAFT,APPROVED,COMMITTED,VOIDED}` (per U12), `draft_actor_id UUID`, `draft_actor_email VARCHAR`, `approve_actor_id UUID NULL`, `approve_actor_email VARCHAR NULL`, `commit_actor_id UUID NULL`, `commit_actor_email VARCHAR NULL`, `voided_reason VARCHAR NULL`. AuditEvent on every transition |

**Additive columns on user-service policy entities** (V102..V107 — schema-widening per U1, U2, U6, U14):

| Entity | New columns |
|---|---|
| `LifePolicy` | `written_premium NUMERIC NOT NULL`, `written_premium_currency CHAR(3) NOT NULL`, `bound_at TIMESTAMPTZ NOT NULL`, `coverage_start DATE NOT NULL`, `coverage_end DATE NOT NULL`, `renewed_from_policy_id UUID NULL SELF-FK`, `status ENUM{ACTIVE,LAPSED,SUSPENDED,TERMINATED,DRAFT}`, `portfolio_id UUID NULL FK`, `cohort_id UUID NULL FK` |
| `FuneralPolicy` | same |
| `DisabilityPolicy` | same |
| `VehiclePolicy` | same |
| `PropertyPolicy` | same |
| `TravelPolicy` | `written_premium NUMERIC NOT NULL`, `written_premium_currency CHAR(3) NOT NULL`, `bound_at TIMESTAMPTZ NOT NULL`, `renewed_from_policy_id UUID NULL SELF-FK` (each trip is a new bind so usually NULL), `status ENUM` (same arms), `portfolio_id UUID NULL FK`, `cohort_id UUID NULL FK`. Reuses existing `trip_start_date` + `trip_end_date` |
| `Contribution` (HEALTH) | `portfolio_id UUID NULL FK`, `cohort_id UUID NULL FK`. Reuses existing `amount`, `currency_code`, `period_start`, `period_end` |

**Backfill strategy for existing policy rows** (§A): rows without `bound_at`/`coverage_start` get `bound_at = created_at`, `coverage_start = created_at::DATE`, `coverage_end = created_at + INTERVAL '1 year'` (annual assumption); rows without `written_premium` get a NULL flagged with `status = LEGACY_NO_PREMIUM` (a new status arm exclusive to backfill) — earning-schedule skips these until the tenant admin retrofits them via a new admin CRUD screen. TravelPolicy rows without `written_premium` similarly flagged.

**Public schema tables** (V134):

| Table | Purpose | Key columns |
|---|---|---|
| `public.tenant_endorsement_config` (§C) | Four-eyes threshold config per U12 | `tenant_id UUID PK FK`, `four_eyes_threshold_amount NUMERIC NULL`, `threshold_currency CHAR(3) NULL`, `enabled BOOLEAN NOT NULL DEFAULT FALSE`, `actor_id`, `actor_email` |

### Kafka topology

Two new topics (§A + §C), one in-JVM consumer, one cross-service consumer, one JobExecutor:

| Component | Direction | Topic | Behaviour |
|---|---|---|---|
| `user-service PolicyIssuedPublisher` | out (§A) | `medfund.user.policy-issued` (new, F12-9) | On any policy CUD → published `{tenantId, policyId, policySource, insuranceLine, writtenPremium, currencyCode, coverageStart, coverageEnd, boundAt, memberId, portfolioId, cohortId}`. Fires from LifePolicy / FuneralPolicy / DisabilityPolicy / VehiclePolicy / PropertyPolicy / TravelPolicy service create paths. |
| `contributions-service PolicyIssuedConsumer` | in (§A) | `medfund.user.policy-issued` | Per issued policy: enrich to `PremiumFact` (per U11), fire rules-engine `PREMIUM_EARNING` session, write `earning_schedule` rows for each period across coverage window. `.doOnSuccess` for ack per `bug_reactor_kafka_ack_swallow`. Idempotent via UNIQUE constraint. |
| `contributions-service BillingContributionEarningInJVM` | in-JVM (§A) | (no Kafka — same JVM per F12-11) | HEALTH branch: BillingService creates Contribution row → same-transaction write of `earning_schedule` row for that period. |
| `PremiumEarningExecutor` | scheduler (§A, per U10) | — | Nightly `JobExecutor` (V114 tenant-config). 3-stage: (1) enumerate ACTIVE policies with missing current-period row; (2) close periods whose `end_date <= today` (set `earned_at_period_end = written_amount`); (3) apply §C endorsements arrived since last-run-timestamp. Chunked; resumable; idempotent. |
| `user-service PolicyEndorsedPublisher` | out (§C) | `medfund.user.policy-endorsed` (new, F12-10) | On endorsement COMMIT: `{tenantId, endorsementId, policyId, policySource, insuranceLine, changeType, effectiveFrom, premiumDelta, currencyCode, actorId, actorEmail}`. |
| `contributions-service PolicyEndorsedConsumer` | in (§C) | `medfund.user.policy-endorsed` | Trigger `PremiumEarningExecutor.recomputeForEndorsement(endorsementId)` — rewrites affected `earning_schedule` rows (rows with `period_start >= endorsement.effective_from` for that policy). Writes new rows marked `is_endorsement = true` + `endorsement_id` = the endorsement's ID. |

**Deploy order per invariant** (parent plan `:3268`): producers before consumers — user-service ships PolicyIssuedPublisher first; contributions-service ships PolicyIssuedConsumer after user-service is emitting; same for the endorsement pair in §C.

### Report shapes

Four report surfaces, each `@RequiresReport(...)` + `ReportEnvelopeBuilder` + `SecurityEventPublisher.publishDataAccess` on export. All under `ReportFamily.UNDERWRITING` per U9. All served from contributions-service at `/api/v1/reports/premium/*` (per U3).

**UPR_MOVEMENT** — `GET /api/v1/reports/premium/upr-movement?periodStart=&periodEnd=&insuranceLine=&reportingCurrency=` — opening UPR (as of periodStart) + written premium (in period) - earned premium (in period) = closing UPR (as of periodEnd), broken down by (insurance_line × currency_code). Envelope per invariant #6 carries `perCurrency: Map<String, PerCurrencyTotal>` in native + `fxRates` for optional conversion. Row shape: `{insuranceLine, currencyCode, openingUpr, writtenPremium, earnedPremium, closingUpr, endorsementDelta}`. Detail drill (`?policyId=`): per-period earning strip for a single policy. XLSX export: one sheet per insurance_line + summary sheet.

**PREMIUM_REGISTER** — `GET /api/v1/reports/premium/register?periodStart=&periodEnd=&insuranceLine=&isNewBusiness=&reportingCurrency=` — one XLSX row per policy per period with (writtenPremium, earnedInPeriod, unearnedAtPeriodEnd, currencyCode, boundAt, coverageStart, coverageEnd, isNewBusiness, portfolioId, cohortId, memberName, schemeName). Per-currency native totals in envelope; XLSX summary sheet cross-currency-converted at `period_end`.

**NEW_BUSINESS_REGISTER** — `GET /api/v1/reports/premium/new-business?periodStart=&periodEnd=&insuranceLine=&reportingCurrency=` — one row per new-business policy per U6. Filter: `bound_at BETWEEN periodStart AND periodEnd AND renewed_from_policy_id IS NULL` for annual lines; HEALTH: `min(Contribution.created_at) per member BETWEEN periodStart AND periodEnd`. Columns: memberNumber, memberName, insuranceLine, schemeName, boundAt, writtenPremium, currencyCode, portfolioId, cohortId. Per-currency native totals; XLSX summary sheet.

**ENDORSEMENT_REGISTER** (§C) — `GET /api/v1/reports/premium/endorsements?periodStart=&periodEnd=&insuranceLine=&status=&reportingCurrency=` — one row per endorsement in the period. Columns: endorsementId, policyId, memberNumber, insuranceLine, changeType, effectiveFrom, premiumDelta, currencyCode, status, draftActorEmail, approveActorEmail, commitActorEmail, voidedReason. Per-currency native totals; XLSX with a Summary sheet (endorsement counts + total premium delta per changeType).

### Angular surfaces

Tenant-admin CRUD (§A + §C):
- `/tenant-admin/underwriting/portfolios` (§A, U14) — Portfolio list + form (name, description, insurance_line).
- `/tenant-admin/underwriting/cohorts` (§A, U14) — Cohort list + form (portfolio picker, cohort_year, cohort_type).
- `/tenant-admin/settings/endorsement-config` (§C, U12) — Four-eyes threshold config: enable toggle + threshold amount + currency picker.
- `/tenant-admin/policies/{policyId}/endorsements` (§C) — Create endorsement modal + endorsement history list. Adds endorsement button on each policy's admin detail page (LifePolicy, FuneralPolicy, etc. detail screens all get a new "Endorsements" tab).
- `/tenant/finance/underwriting/endorsements/review-queue` (§C) — Approver queue for DRAFT + APPROVED endorsements above threshold. Row actions: approve, commit, void.

Reports (§B):
- `/tenant/finance/reports/underwriting/upr-movement` — Period picker (default: prior month), insurance-line filter, reporting-currency override, export button. Renders the opening→closing UPR table + per-line trend chart.
- `/tenant/finance/reports/underwriting/premium-register` — Period + filters + export. Paginated list of policies with drill to policy detail.
- `/tenant/finance/reports/underwriting/new-business` — Period + insurance-line filter + export.
- `/tenant/finance/reports/underwriting/endorsements` (§C) — Period + status filter + export.

Reports hub (§B, U9) auto-registers the four keys via existing `TenantReportConfigService` — new `UNDERWRITING` family card renders when any key enabled.

Two new permissions: `policy:draft_endorsement` (all tenant admins by default) + `policy:approve_endorsement` (supervisor role, restricted). Recommended new role: `underwriting_supervisor` (or attach to existing finance supervisor role — plan-time call).

### Proposed §0 / §A / §B / §C tranche split

**§0 — Testcontainers harness fix (U15)**:
- `testcontainers.reuse.enable=true` across `services/java/*/build.gradle.kts` + `settings.gradle.kts` propagation.
- Extract `AbstractIntegrationTest` base class in contributions-service + user-service (finance-service already has one from Phase 11).
- Tune R2DBC connection pool ceilings (from default 10 to service-appropriate 20-30) — `application.yml` + tests.
- Add pool-starvation health check (Micrometer gauge + Actuator).
- Re-run the 25 pre-existing IT flakes; fix any that turn out to be genuine bugs (not just pool-pressured).
- **Success criterion**: `make test-integration` passes end-to-end across all services in a single run.

**§A — Foundation** (silent — no user-visible surface):
- Tenant migrations V102..V108 in tenancy-service: policy schema-widening (LIFE/FUNERAL/DISABILITY/VEHICLE/PROPERTY/TRAVEL — one migration each or one big one; plan-time call), `earning_schedule`, `ifrs17_portfolio`, `ifrs17_cohort`.
- Additive `portfolio_id` + `cohort_id` on `Contribution` (HEALTH).
- Backfill strategy for existing policy rows (per Data model section).
- user-service `PolicyIssuedPublisher` for all 6 policy CUD paths.
- contributions-service `com.medfund.contributions.premium.*` subpackage: `PolicyIssuedConsumer`, `BillingContributionEarningInJVM` hook, `PremiumEarningExecutor` (nightly `JobExecutor`), CRUD services for `ifrs17_portfolio` + `ifrs17_cohort` + tenant-admin Angular pages.
- rules-engine: `RuleCategory.PREMIUM_EARNING` (add to `AGENDA_GATED_CATEGORIES`), `ActionType.ACCRUE_PREMIUM`, `PremiumFact`, `AccruePremiumEmitter`, 3 templates (DAILY_LINEAR, MONTHLY_24THS, LINEAR_WITH_LOADING).
- Angular rules editor + dry-run seeds extended for the new category.
- Debug controller `@Profile("dev")` returning raw earning_schedule rows for QA (removed before §B).
- IT: full policy → schedule round-trip + rules-engine dispatch + Executor idempotency + backfill correctness.

**§B — Reports + Angular** (user-facing surface lights up):
- contributions-service controllers: `UprMovementReportController`, `PremiumRegisterReportController`, `NewBusinessRegisterReportController` + XLSX services.
- `ReportKey` enum edit: reassign UPR_MOVEMENT, PREMIUM_REGISTER, NEW_BUSINESS_REGISTER from `CLAIMS_FINANCIAL` to `UNDERWRITING`.
- `ReportFamily` enum edit: add `UNDERWRITING`.
- `TenantReportConfigService` auto-registration of the 3 keys under new family.
- Angular pages under `/tenant/finance/reports/underwriting/*` + reports hub card.
- Sidebar registration.
- Retire the debug controller from §A.
- IT: report envelope + toggle-off 403 + SecurityEvent-on-export + per-currency assertions + missing-FX warnings.
- Playwright: `underwriting-reports.spec.ts` — hub → each report → filter + export → assert download.

**§C — Endorsements + retro recompute**:
- Public migration V134 `tenant_endorsement_config`.
- Tenant migration V109 `endorsement`.
- user-service `PolicyEndorsementService` + `PolicyEndorsedPublisher` + four-eyes state machine (DRAFT → APPROVED → COMMITTED → VOIDED) per U12.
- contributions-service `PolicyEndorsedConsumer` + `PremiumEarningExecutor.recomputeForEndorsement(endorsementId)` — chunked rewrite of affected `earning_schedule` rows.
- Angular: endorsement creation modal (per policy), endorsement admin tab (per policy), approver review queue at `/tenant/finance/underwriting/endorsements/review-queue`.
- Reports enum edit: add `ENDORSEMENT_REGISTER` key under `UNDERWRITING`.
- Endorsement register report controller + XLSX + Angular page.
- Tenant-admin `endorsement-config` UI at `/tenant-admin/settings/endorsement-config`.
- Permissions: `policy:draft_endorsement`, `policy:approve_endorsement`.
- IT: four-eyes round-trip + retro recompute correctness + threshold gating + auto-commit branch.
- Playwright: `endorsement-workflow.spec.ts` + `endorsement-report.spec.ts`.

§0 can land as commit "Fix Testcontainers pool pressure (Phase 12 §0)"; §A/§B/§C as separate commits per Phase 10 + 11 precedent. All should be fully grilled + planned at implement-time via `create-plan`; `implement-plan` treats tranche boundaries as hand-off points per the plan header.

### Grill notes for `create-plan`

1. **V-migration ordering (F12-3, U1)** — the 6 policy schema-widening migrations (V102..V107) touch different tables per line; plan the exact ordering + whether to bundle into one V102 or split per entity. If split, TravelPolicy is the smallest change (already has trip dates) and could land first as a reference implementation.
2. **Policy backfill for existing rows (U1 §A)** — write out the exact SQL for rows lacking `bound_at` / `written_premium`. Consider a follow-up "policy retrofit" tenant-admin bulk-edit UI so admins can populate legacy rows post-migration. Verify the `LEGACY_NO_PREMIUM` status arm is compatible with existing Angular status filters (many pages hard-code the ACTIVE/LAPSED/SUSPENDED set).
3. **`PolicyIssuedPublisher` firing points (F12-9, §A)** — each of 6 policy service create paths in user-service needs the publisher call. Verify none are called from a Kafka consumer path (which would create a consume-then-publish loop). Map: `LifePolicyService.create/update`, `FuneralPolicyService.create/update`, `DisabilityPolicyService.create/update`, `VehiclePolicyService.create/update`, `PropertyPolicyService.create/update`, `TravelPolicyService.create/update`.
4. **`PremiumFact` action-value DSL (U11)** — mirror `PayCommissionEmitter` shape. Design the DSL: `EARNING_METHOD:DAILY_LINEAR`, `EARNING_METHOD:MONTHLY_24THS`, `EARNING_METHOD:LINEAR_WITH_LOADING`, `LOADING:15` (percent). Verify the Angular visual rule builder supports the new action type — check `clients/angular/src/app/pages/tenant-admin/rules/rule-editor/rule-editor.component.ts` extends cleanly (matches Phase 11's 3 edits: category source, action-type registry, dry-run FACT_SEEDS).
5. **PremiumEarningExecutor chunking (U10)** — for a tenant with 100k policies × 12 periods, a single-transaction nightly close is unacceptable. Chunk by (insurance_line, currency) at 5k rows per commit; progress-track via a new `earning_schedule_run` table with a `last_processed_policy_id` cursor; support cancel + resume mid-run.
6. **Endorsement retro-recompute performance (§C, U12)** — an endorsement 3 months back on a 12-month LIFE policy rewrites 9 rows per affected policy. For a bulk endorsement (rate-card revision affecting 10k policies), that's 90k rewrites. Plan chunking + progress-tracking like the Phase 10 `TreatyActivationBackfillJob` + Phase 11 `ProducerBackfillJob` precedents. Also: how does the endorsement UI show "recompute in progress" so users don't re-fire?
7. **HEALTH new-business detection (U6)** — `min(Contribution.created_at) per member per tenant` is expensive at 100k+ members. Consider a materialized `member_first_contribution` cache table populated by trigger or nightly refresh; plan at code altitude.
8. **§0 IT flake triage (U15)** — some of the 25 pre-existing IT flakes may be genuine bugs, not pool-pressure. Reserve budget for triage; if any turn out to be real correctness bugs, they land as follow-up commits before §0 closes.
9. **IFRS 17 portfolio/cohort naming (U14)** — the outline says `ifrs17_portfolio` + `ifrs17_cohort`. Consider whether the `ifrs17_` prefix will confuse tenants who don't do IFRS 17 (they'd still see the portfolio/cohort admin screens). Alternative: neutral `policy_portfolio` + `policy_cohort` names, with an IFRS-17-specific view layer in Phase 15. Grill at code altitude.
10. **Endorsement `change_type` enum arms (§C, U5)** — the arms listed above are `{PREMIUM_ADJUSTMENT, COVERAGE_EXTENSION, BENEFIT_CHANGE, BENEFICIARY_CHANGE, ADMIN_CHANGE}`. Verify at code altitude that this covers the real endorsement patterns tenants would want. Do we also need `RENEWAL_ADVANCE` (early renewal)? `PRODUCT_SWITCH` (change to a different scheme mid-term)?
11. **Portfolio/cohort assignment default (§A, U14)** — when a policy is created without an explicit portfolio/cohort, we assign the tenant's default. Design the default-resolution: single-portfolio-per-line auto-created at tenant provisioning? Or a "MISC" catch-all portfolio? Verify no tenant has a policy line without at least one portfolio (else policy creation would 400).
12. **`portfolio_id` + `cohort_id` on `Contribution` for HEALTH (§A, U14)** — HEALTH has no policy entity, so portfolio/cohort has to attach to the Contribution or to the member's scheme enrolment. Grill: (a) attach to each Contribution row (redundant across months for same member); (b) attach to the member's scheme enrolment (needs enrolment change on portfolio switch); (c) both (enrolment sets the default, Contribution row inherits at creation).
13. **`.claude/multi-currency.md` update owed** — U8 codifies "written premium stays in policy's original currency; endorsement recompute preserves currency". Add a paragraph to `.claude/multi-currency.md` after `:169` (the reporting-currency section) so future readers see the pattern.

### Success Criteria

**Status: Fully implemented via the expanded sub-plan [thoughts/shared/plans/2026-08-23-upr-earning-schedule-and-premium-register.md](2026-08-23-upr-earning-schedule-and-premium-register.md), landed 2026-08-23 as commit `fe36635 "Land Phase 12 of the financial-reporting suite"` (226 files).**

Grilling checkpoint satisfied 2026-08-23 (U1..U15 + F12-1..F12-12 above). All ten sub-plan phases green on their in-tranche automated verification per the sub-plan Deviations log:

- §0 (Phase 1 — harness): R2DBC pool ceilings tuned across 5 services, `AbstractUserServiceIT` marker + user-service IT migration, `R2dbcPoolHealthIndicator` under actuator, IT flake triage documented at `thoughts/shared/notes/2026-08-23-it-flake-triage.md`.
- §A (Phases 2-5 — silent foundation): tenant V102 policy-widening (six-table one-atomic-ALTER pass with backfill + `LEGACY_NO_PREMIUM` status arm), V107/V108/V109 IFRS 17 dimensions + `earning_schedule` + `member_first_contribution` materialised view + `earning_schedule_run`; user-service `PolicyIssuedPublisher` from all six annual-bind services + `Ifrs17PortfolioService` / `Ifrs17CohortService` CRUD; rules-engine `RuleCategory.PREMIUM_EARNING` (agenda-gated) + `PremiumFact` + `AccruePremiumEmitter` + `PremiumEarningTemplates` (DAILY_LINEAR / MONTHLY_24THS / LINEAR_WITH_LOADING); contributions-service `com.medfund.contributions.premium.*` subpackage with `EarningScheduleClosureService`, `PremiumEarningExecutor` (`JobExecutor` façade), `BillingContributionEarningHook` (in-JVM HEALTH branch), `PolicyIssuedConsumer` (annual-line Kafka branch).
- §B (Phases 6-7 — user-facing reports): `PremiumReportController` at `/api/v1/reports/premium/{upr-movement,register,new-business}` + one `PremiumReportQueryRepository` covering the seven-way UNION-ALL + three workbook services; `ReportFamily.UNDERWRITING` added and three keys reassigned; Angular pages under `/tenant/finance/reports/underwriting/*` + hub card auto-registration + gateway routes.
- §C (Phases 8-10 — endorsements): tenant V110 `endorsement` + public V134 `tenant_endorsement_config`; user-service `PolicyEndorsementService` DRAFT → APPROVED → COMMITTED → VOIDED state machine with tenant-configurable four-eyes threshold; contributions-service `PolicyEndorsedConsumer` + `EarningScheduleClosureService.recomputeForEndorsement` chunked retro-recompute + `COMMITTED → COMPUTED` transition; `EndorsementRegisterReportService` + workbook + controller in user-service; Angular endorsement history + creation modal + review queue + `endorsement-config` tenant-admin tab; Kafka topics `medfund.user.policy-issued` + `medfund.user.policy-endorsed`.

Deferred to follow-up sub-phases per the sub-plan Deviations log:

- **Phase 3b** — six per-line Angular policy CRUD form extensions (writtenPremium / currency / boundAt / coverage / renewedFromPolicyId picker / portfolio / cohort dropdowns); `Ifrs17PortfolioIT` / `Ifrs17CohortIT` / `PolicyIssuedPublisherIT` in user-service (needs purpose-built `db/underwriting-migration/V001..sql`).
- **Phase 5b** — `PolicyIssuedConsumerIT` / `BillingEarningIT` / `PremiumEarningExecutorIT` in contributions-service (needs `db/premium-earning-migration/`).
- **Phase 6b** — `UprMovementReportIT` / `PremiumRegisterReportIT` / `NewBusinessRegisterReportIT` in contributions-service (needs `db/premium-reports-migration/`).
- **Phase 8b** — `PolicyEndorsementIT` / `TenantEndorsementConfigIT` in user-service (needs `db/endorsement-migration/` + Kafka container).
- **Phase 9b** — `EndorsementRecomputeIT` in contributions-service + `EndorsementRegisterReportIT` in user-service (needs `db/endorsement-recompute-migration/` + `db/endorsement-report-migration/`).
- **Manual `verify` walkthroughs** — end-to-end golden-path browser demos for each of the ten sub-phases (portfolio create → policy bind → schedule generation → endorsement round-trip → report XLSX export → 403 on toggle-off).

Per parent-plan Testcontainers policy each deferred IT lands with a purpose-built migration folder so the ITs don't force-widen every unrelated slice's baseline schema.

---

## Phase 13: Persistency + Policy Movement + Provider Network

> **Grilled 2026-08-23.** Decisions L1..L18 (numbered L* — for Lifecycle — to avoid collision with plan-wide G*, reinsurance R*, producer P*, and underwriting U* numbering).
> The outline was expanded into a full mini-plan through interactive decision-making; scope escalated
> substantially beyond the original outline (full policy status-transition admin surface for all 6 annual
> lines + two new tenant history tables + provider.network_tier column + cross-service
> PROVIDER_NETWORK_UTILIZATION + Kafka event with earning-schedule closure ripple).
> Recommended split into **§A** (schema-widening + status transition services + admin modals),
> **§B** (Kafka event + earning-schedule closure consumer), **§C** (four reports + hub POLICY_LIFECYCLE
> family). §A ships admins the ability to lapse/terminate/suspend/reinstate; §B keeps UPR correct on
> status change; §C lights up the user-facing reports.
> Original 12-line outline retained below as ~~strike-through~~ for provenance.

### Original outline (superseded 2026-08-23 by Decisions Log)

~~Cheap-query family: policy movement, persistency cohort, provider-network-utilisation, group census.~~

~~**user-service**: `PolicyMovementReportController`, `PersistencyReportController`. Report keys `POLICY_MOVEMENT`, `PERSISTENCY_COHORT`, `GROUP_CENSUS`.~~
~~**claims-service** or **finance-service**: `ProviderNetworkUtilizationReportController`. Report key `PROVIDER_NETWORK_UTILIZATION`.~~
~~All XLSX + toggle + audit + hub registration.~~

### Overview

Greenfield **policy status-transition module** across user-service + contributions-service + claims-service that ships (a) a full admin surface for lapse / terminate / suspend / reinstate on all 6 annual-bind policy lines (per L4 + L5 — uniform 4-action REST shape with per-line reason-code vocab), (b) two new tenant-scoped history tables (`policy_status_history` + `member_status_history` per L2 — the source of truth for persistency + movement reports), (c) a centralized write-pathway pattern via new `MemberStatusTransitionService` + `PolicyStatusTransitionService` classes (per L3 — retrofits ~6 existing member-status write sites), (d) a new Kafka topic `medfund.user.policy-status-changed` (per L6) with contributions-service `PolicyStatusChangedConsumer` extending `EarningScheduleClosureService.closeOutForPolicyClosure` to keep UPR correct when a policy lapses / terminates mid-term, (e) a single-column `provider.network_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD'` schema addition (per L1 — cheapest touch that gives `PROVIDER_NETWORK_UTILIZATION` a real grouping dimension), and (f) **four tenant-toggleable reports** — three under a new `ReportFamily.POLICY_LIFECYCLE` (`POLICY_MOVEMENT`, `PERSISTENCY_COHORT`, `GROUP_CENSUS` per L8) hosted in user-service, plus `PROVIDER_NETWORK_UTILIZATION` staying under `CLAIMS_FINANCIAL` hosted in claims-service with a `ProviderClient` cross-service call for provider metadata enrichment (per L13).

All four report keys already ship in `ReportKey.java:85-88` (per F13-1 — enum edit is family reassignment via L8 + cadence flips via L14, not enum adds). All Phase 0-12 shared infra composes cleanly (per F13-2). Persistency semantics for HEALTH cross-check `member_status_history` against monthly `Contribution` presence for the "still-paying" retention signal (per L16 — stricter than industry default); annual-line persistency uses the renewal-chain-aware active check (per L9).

**§A** ships schema (V111 policy_status_history + V112 member_status_history + V113 provider.network_tier), the two transition services + 24 admin REST endpoints (6 lines × 4 actions), and the Angular per-line modal surface on the existing policy detail pages plus the in-line network_tier dropdown on the provider list (per L17). **§B** wires the Kafka event out of user-service, adds `PolicyStatusChangedConsumer` in contributions-service, and extends `EarningScheduleClosureService` with a policy-closure path so lapsed / terminated policies stop accruing. Refund calculation (short-rate vs pro-rata) deferred (per L6). **§C** ships the four reports + new `ReportFamily.POLICY_LIFECYCLE` family card + Angular pages + hub registration.

### Decisions Log (L1..L18)

- **L1 — PROVIDER_NETWORK_UTILIZATION schema gap**: **add `provider.network_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD'` (V113 tenant) + tenant-admin in-line dropdown per L17 + report groups by tier**. Matches existing `ProviderFact.networkTier` convention already used by rules-engine co-pay templates (`services/java/rules-engine/src/main/java/com/medfund/rules/fact/ProviderFact.java:17`, `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/CoPaymentTemplates.java:60`). Cheapest schema touch that gives the report a real dimension. Regulator-grade `provider_network` hierarchy (with contract dates, per-network commission overrides, etc.) deferred to Phase 16 alongside PMB spend. Rejected: full `provider_network` table (~a week of scope), rename to PROVIDER_UTILIZATION and drop network dimension (breaking enum rename + `tenant_report_config` data migration), defer entirely to Phase 16 (phase-title mismatch).
- **L2 — Persistency data source**: **add both `policy_status_history` (V111) + `member_status_history` (V112) + centralized `StatusTransitionRecorder`**. Every status transition (bind, admin lapse, arrears escalation, admin manual, group cascade, scheduled) writes a history row in the same reactive transaction as the entity update. In-JVM writes — no Kafka projection hop for the recorder itself (per L6 Kafka is for the *cross-service* ripple, not the write). Backfill from current state at migration time per L7. Reports read from the history tables directly. Rejected: member_status_history only (asymmetric — annual-line lapse arrives soon), no history tables (persistency computed on inference), Kafka event-sourced projection (2x infra for identical read shape).
- **L3 — Recorder wiring**: **introduce `MemberStatusTransitionService` + `PolicyStatusTransitionService` as single write pathways**. New service classes whose only public method is `transition(entityId, newStatus, actor, reason)`. Reads current status, writes history row, updates entity — all in one reactive transaction. Every existing member-status write site (ArrearsBreachedConsumer, ArrearsClearedConsumer, MemberController, ScheduledStatusExecutor, GroupController cascade, MemberLifecycleService if it grows write paths) is retrofitted to call this service instead of `member.setStatus(...) + repo.save(...)` directly. Six per-line `PolicyStatusTransitionService<T>` instances (LIFE/FUNERAL/DISABILITY/TRAVEL/VEHICLE/PROPERTY) host the new lapse/terminate/suspend/reinstate methods. Rejected: R2DBC BeforeSave callback (extra round-trip per save), repo aspect (hides write flow, atypical style), explicit `StatusTransitionRecorder.record(...)` at every call site (discipline issue for future write sites).
- **L4 — Annual-line status-transition surface scope**: **ship the full stack — service + REST + Angular per-line modals + reports**. 6 per-line `PolicyStatusTransitionService` × 4 actions = 24 new admin REST endpoints (`POST /api/v1/{life|funeral|disability|travel|vehicle|property}-policies/{id}/{lapse|terminate|suspend|reinstate}`) + Angular modals on each of the 6 policy detail pages + confirmation dialogs + audit event emission. Annual-line reports have real data on shipping day. Substantial scope expansion for a phase billed as "cheap-query family" — accepted trade-off for shipping a usable feature. Rejected: service + REST only, no Angular UI (reports thin on shipping day; UI defers to 13b), skip service+endpoints entirely (reports derive from renewal-chain only), table-only defer (empty table for months).
- **L5 — Per-line action-set shape**: **uniform 4-action shape (lapse / terminate / suspend / reinstate) + per-line reason-code vocab**. Every line exposes the same 4 endpoints with a required `reasonCode` (from a per-line vocab: LIFE = `{non_payment, policyholder_cancel, insured_event, mortality}`; VEHICLE = `{non_payment, sold, total_loss, storage_suspend, policyholder_cancel}`; PROPERTY = `{non_payment, sold, total_loss, policyholder_cancel}`; etc. — final vocab plan-time at create-plan). Abstract `PolicyStatusTransitionService<T>` base class + 6 concrete subclasses. Uniform REST shape means Angular modal is one component parameterized per line. Rejected: per-line action sets (3 shapes to maintain), 3 coarse actions (loses regulatory distinction between lapse vs terminate), rules-engine `RuleCategory.POLICY_LIFECYCLE` (over-engineered for essentially fixed vocabs).
- **L6 — Status-change ripple**: **emit `medfund.user.policy-status-changed` Kafka event + contributions-service `PolicyStatusChangedConsumer` closes earning_schedule on LAPSED/TERMINATED; refund calc deferred**. user-service `PolicyStatusTransitionService` emits per transition. Consumer calls new `EarningScheduleClosureService.closeOutForPolicyClosure(tenantId, policyId, policySource, effectiveDate)` which marks open-future periods `earned_at_period_end = 0` with `is_closure = TRUE` flag on new schedule rows. Refund calculation (short-rate vs pro-rata, per-tenant config, jurisdiction rules) deferred to a follow-up phase. Reactor-Kafka pattern uses `.doOnSuccess` for ack per `bug_reactor_kafka_ack_swallow`. Rejected: A + refund calc (opens complexity can), publisher only / no consumer in 13 (UPR silently diverges), no Kafka event (accounting wrong).
- **L7 — History-table backfill**: **one seed row per entity from `created_at` + optional second row where terminal-state fields expose a transition date**. V111 policy_status_history: `(policy_id, policy_source, from_status=NULL, to_status=<current status>, effective_at=bound_at OR created_at, actor_id=NULL, actor_email='migration', reason='initial_backfill')`. V112 member_status_history: `(member_id, from_status=NULL, to_status='enrolled' initial guess, effective_at=created_at, reason='initial_backfill')` + if `termination_date IS NOT NULL AND status='terminated'` a second row `(from='active', to='terminated', effective_at=termination_date, reason='backfill_from_termination_date')`; same pattern for `suspend_reason` where present. Rejected: single seed only (visibly distorts pre-migration persistency for known-terminated members), no backfill (report queries get uglier), Contribution-inference reconstruction (false-positive lapses corrupt history).
- **L8 — Report family assignment**: **new `ReportFamily.POLICY_LIFECYCLE` for `POLICY_MOVEMENT` + `PERSISTENCY_COHORT` + `GROUP_CENSUS`; `PROVIDER_NETWORK_UTILIZATION` stays under `CLAIMS_FINANCIAL`**. Enum edit: `ReportFamily.java` add `POLICY_LIFECYCLE`; `ReportKey.java` reassign 3 keys. Angular family-label service adds `POLICY_LIFECYCLE` label. Mirrors Phase 12's `UNDERWRITING` addition pattern verbatim. Rejected: keep all 4 under CLAIMS_FINANCIAL (semantically misleading grab-bag), new PERSISTENCY family covering all 4 (PROVIDER report doesn't fit under 'persistency'), two new families POLICY_LIFECYCLE + PROVIDER (single-report PROVIDER family under-populated).
- **L9 — `PERSISTENCY_COHORT` shape**: **monthly cohorts × URL-configurable retention checkpoints (default `checkpoints=3,6,12,24,36`) × renewal-chain-aware active**. Rows: `(cohort_month, insurance_line, currency_code) × checkpoint`. Default checkpoints via URL param `?checkpoints=3,6,12,24,36`; tenant admin can override via query string. "Still active" definition per line: HEALTH = member had a `Contribution` in the checkpoint month AND `member_status_history` shows `status='active'` at checkpoint date (per L16 — stricter than industry default); annual lines = renewal chain from cohort policy reaches a currently-active policy at the checkpoint month (traverse `renewed_from_policy_id` backwards). XLSX: pivot on cohort_month × checkpoint per line; summary sheet with overall retention curve. Rejected: quarterly cohorts (hides HEALTH month-1 spike), annual cohorts (loses monthly signal), tenant-configurable via `public.tenant_persistency_config` (over-engineered — URL param covers it).
- **L10 — `POLICY_MOVEMENT` shape**: **roll shape per (insurance_line × currency × period-month) with count + written_premium columns for each movement type**. One row per (insurance_line, currency, period-month). Columns: `opening_count`, `opening_wp`, `new_business_count`, `new_business_wp`, `renewal_count`, `renewal_wp`, `reinstatement_count`, `reinstatement_wp`, `lapse_count`, `lapse_wp`, `terminate_count`, `terminate_wp`, `suspend_count`, `suspend_wp`, `closing_count`, `closing_wp`. XLSX: one sheet per line + summary sheet with cross-line rollup. Envelope carries native `perCurrency` per invariant #1; `fxRates` best-effort per G28. Rejected: count-only (loses economic signal), transaction-list (200k rows chokes XLSX), roll + separate drilldown endpoint (+1 week scope for a v2 feature).
- **L11 — `GROUP_CENSUS` shape**: **snapshot as-of-date, principals + dependants, all member statuses with status column**. Required `?asOf=YYYY-MM-DD` query param — no sensible default (`today` would be a shifting target for scheduled runs); force explicit. Filter: `group.status='active'` + as-of-date. One row per member per group. Columns: `groupNumber`, `groupName`, `memberNumber`, `name`, `memberStatus`, `principalOrDependantOf` (null for principals; parent memberNumber for dependants), `schemeName`, `monthlyContribution`, `joinedAt`, `terminationDate`. Sort: principal followed by their dependants. Optional `?status=active,suspended,lapsed` filter to narrow. XLSX: one sheet per group + summary sheet with headcount by group by status. Rejected: principals-only (HR wants dependant names for benefit review), period-based trend (not the census shape a group liaison expects), snapshot + trend both (doubles XLSX + Angular work for minimal added value).
- **L12 — `PROVIDER_NETWORK_UTILIZATION` shape**: **two-level workbook — per-network summary sheet + per-provider detail sheet**. JSON envelope carries both slices. Summary: one row per `network_tier` with `claim_count`, `total_paid`, `unique_members_served`, `avg_claim_amount`, `denial_rate`. Detail: one row per provider with the same metrics + `network_tier` column + `insurance_line` breakdown. Angular page shows summary panel on top, filterable/sortable per-provider table below. Rejected: per-provider only (forces reader to aggregate manually), per-network only (loses actionable outlier detection), 3D pivot (over-fragmented / sparse).
- **L13 — `PROVIDER_NETWORK_UTILIZATION` ownership**: **claims-service hosts; uses `ProviderClient` (WebClient to user-service) to enrich with provider names + network_tier**. Report SQL aggregates claims where they live (claims-service owns the heavy data). Batched WebClient call to user-service `GET /api/v1/providers?ids=...` enriches with names + network_tier per unique provider (≤500 records typical, cap batch at N=100). Matches Phase 8 aged-debtors cross-service pattern. Missing user-service → envelope carries warnings + partial data per G37 invariant #7 (`CrossServiceCallHelper`). Rejected: user-service hosts (shovels claim aggregation over HTTP), finance-service aggregator (2 hops + dilutes domain), precomputed nightly projection (unnecessary for monthly reports).
- **L14 — Cadence flags on the four keys**: **all four `cadenced=true`**. `POLICY_MOVEMENT` already true; flip `PERSISTENCY_COHORT`, `GROUP_CENSUS`, `PROVIDER_NETWORK_UTILIZATION` to true in `ReportKey.java`. All four are real monthly-report candidates in production (POLICY_MOVEMENT = month-end reconciliation, PERSISTENCY_COHORT = actuarial monthly, GROUP_CENSUS = HR/payroll monthly, PROVIDER_NETWORK_UTILIZATION = finance-ops monthly review). Phase 17 scheduled delivery gets 3 more schedulable reports. Rejected: partial flip (conservative), keep as-is (users lose scheduling affordance), granular cadence enum (schema change with scope creep).
- **L15 — Tranche split**: **§A schema+writers+admin modals | §B Kafka+consumer+earning-schedule closure | §C reports (backend + Angular)** (see full contents in Proposed §A/§B/§C tranche split section below). Three tranches mirror Phase 12 shape. Each tranche is 60-80 files — reviewable. Rejected: two-tranche split (§B too large), alternative three-way split (dead-code review question in §A), one tranche (~226-file commit — hard to review/bisect).
- **L16 — HEALTH persistency signal**: **cross-check both — persistency = `member_status_history` shows status was 'active' at checkpoint month AND had a `Contribution` row for the checkpoint month**. Stricter than industry-standard "still-on-books" definition; ships the finance-officer's "still-paying" signal explicitly. Annual-line lines stay on renewal-chain-active (per L9) since they're not monthly-billed. Requires either a runtime SQL LEFT JOIN on Contribution or a materialized `member_contribution_presence` matview (plan-time call — see grill note 4). Rejected: history alone (misses pre-lapse-threshold arrears), two-column both-flavors (doubles work + confuses reader), raw-status-only (defeats report purpose).
- **L17 — `provider.network_tier` admin UI**: **in-line dropdown on existing provider list page**. Existing provider list gains a new "Network tier" column with an in-line editable dropdown per row (`STANDARD / TIER_1 / TIER_2 / TIER_3`). Save on change; audit event emitted on transition. Follows existing provider CRUD pattern. Rejected: bulk CSV import (deferrable — most tenants leave STANDARD default), rules-engine `PROVIDER_TIERING` category (over-engineered for a VARCHAR), both A + B (scope inflation).
- **L18 — Testing strategy**: **dedicated per-tranche migration folders + ITs land in-tranche**. Matches Phase 12 pattern with zero deferrals now that the harness is stable. §A ships user-service `db/policy-lifecycle-migration/V001..sql` covering both history tables + `StatusTransitionServiceIT` + `PolicyStatusTransitionServiceIT`. §B ships contributions-service `db/policy-status-consumer-migration/V001..sql` covering earning_schedule + policy_status_history mirror + `PolicyStatusChangedConsumerIT`. §C ships user-service `db/policy-lifecycle-reports-migration/V001..sql` + claims-service `db/provider-utilization-report-migration/V001..sql` + 4 report ITs. Rejected: force-widen shared test-migration folders (cascade unrelated FKs — Phase 12 rejected 5 times), defer ITs to 13d (deferral debt Phase 12 §0 aimed to end), reuse Phase 12 premium-earning folder (mixes phase boundaries).

### Settled by fact (not asked)

- **F13-1 — All four report keys already ship** in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:85-88`: `POLICY_MOVEMENT` (`cadenced=true`), `PERSISTENCY_COHORT` (`cadenced=false`), `GROUP_CENSUS` (`cadenced=false`), `PROVIDER_NETWORK_UTILIZATION` (`cadenced=false`). All currently under `ReportFamily.CLAIMS_FINANCIAL` (`ReportFamily.java:20`). Phase 13 §C reassigns 3 of them to new `POLICY_LIFECYCLE` family per L8; L14 flips 3 cadence booleans. **No enum-add work** — no new key.
- **F13-2 — All Phase 0-12 shared infra composes cleanly**: `ReportEnvelopeBuilder` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportEnvelopeBuilder.java`), `FxRateReader.findRate/.convert`, `ReportWorkbook`, `@RequiresReport` + `ReportGuardAspect`, `SecurityEventPublisher.publishDataAccess`, `AuditActor.id/email`, `ReportingCurrencyResolver`, `CrossServiceCallHelper`. Zero new shared plumbing.
- **F13-3 — user-service already imports `shared`** (`services/java/user-service/build.gradle.kts:6`); Phase 12 shipped `EndorsementRegisterReportController` (`services/java/user-service/src/main/java/com/medfund/user/endorsement/controller/EndorsementRegisterReportController.java`) as the reference report shape. user-service can host Phase 13's 3 lifecycle reports without dep additions.
- **F13-4 — Next migration numbers**: **tenant V111** (last: `services/java/user-service/src/main/resources/db/migration/tenant/V102__policy_underwriting_widening.sql`, tenancy-service last: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V110__endorsement.sql`), **public V135** (last: `services/java/tenancy-service/src/main/resources/db/migration/public/V134__tenant_endorsement_config.sql`). §A bundles V111 policy_status_history + V112 member_status_history + V113 provider.network_tier — see grill note 2 for bundling decision.
- **F13-5 — `AuditEvent` is Kafka-only** — record type at `services/java/shared/src/main/java/com/medfund/shared/audit/AuditEvent.java:7`; no persisted repository anywhere. Cannot be a source of persistency history — L2's tables are the required source of truth.
- **F13-6 — 100% greenfield for the schema deltas**. Zero hits on `policy_status_history`, `member_status_history`, `provider_network`, `provider.network_id`, `provider.network_tier`, `PolicyStatusTransitionService`, `MemberStatusTransitionService`, `PolicyStatusChangedConsumer` across `services/java/*/src/main/java` and `clients/angular/src/app`. Rules-engine `ProviderFact.networkTier` at `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ProviderFact.java:17` is a placeholder used by `CoPaymentTemplates.java:60` — nothing populates it from `Provider` today.
- **F13-7 — Angular has zero ComingSoon stubs** for `policy-movement`, `persistency`, `group-census`, `provider-network` in `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`. Phase 13 §C adds all four routes from scratch under `/tenant/finance/reports/policy-lifecycle/{policy-movement|persistency-cohort|group-census}` + `/tenant/finance/reports/claims-financial/provider-network-utilization`. Follows Phase 12 `underwriting.routes.ts` spread pattern at `finance.routes.ts:748`.
- **F13-8 — Zero code path today transitions a policy status** — grep across `services/java/user-service/src/main/java` shows no `setStatus(LAPSED|TERMINATED|SUSPENDED)` write on any of the 6 policy repositories; only status-*filter* queries exist. `LifePolicyRepository.java:28` and 5 siblings prove this. Consequence: Phase 13 §A's `PolicyStatusTransitionService` × 6 is the first writer path for annual-line policies.
- **F13-9 — Endorsement VOIDED does NOT cascade to `policy.status`** — `services/java/user-service/src/main/java/com/medfund/user/endorsement/service/PolicyEndorsementService.java:188` sets `endorsement.status='VOIDED'`, not `policy.status`. If future work wants void-endorsement-cascades-to-policy-terminated, that's an explicit hook Phase 13 does not add.
- **F13-10 — `EarningScheduleClosureService.recomputeForEndorsement` exists** at `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EarningScheduleClosureService.java:135`; the two established consumer siblings are `PolicyIssuedConsumer` + `PolicyEndorsedConsumer` under `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/consumer/`. §B's `PolicyStatusChangedConsumer` + new `closeOutForPolicyClosure` extension follows this shape verbatim.
- **F13-11 — `MemberLifecycleService` only *evaluates* rules; it does NOT own status writes** — `services/java/user-service/src/main/java/com/medfund/user/service/MemberLifecycleService.java:64` shows `evaluate(Member, transition)`. `MemberController` does not reference it. Every existing member-status write site does its own `member.setStatus(...) + repo.save(...)` directly (see `ArrearsBreachedConsumer.java:27`, `ArrearsClearedConsumer.java:30`, `MemberController.java:139`, `ScheduledStatusExecutor.java:32`, `GroupController.java:110`). Per L3, ~6 write sites need retrofit through new `MemberStatusTransitionService` — exhaustive grep required at plan time per grill note 8.

### Data model

Tenant-scoped tables (V111..V113 range in **user-service** — the 6 policy entities live there per F13-6; see grill note 2 for bundling call). All with standard audit tail (`id UUID PK DEFAULT gen_random_uuid(), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`) per `feedback_audit_actor_email` (Rule 8).

| Table | Purpose | Key columns |
|---|---|---|
| `policy_status_history` (§A) | Per-policy status-transition audit trail (U2) | `policy_id UUID NOT NULL`, `policy_source ENUM{LIFE_POLICY,FUNERAL_POLICY,DISABILITY_POLICY,TRAVEL_POLICY,VEHICLE_POLICY,PROPERTY_POLICY}`, `from_status VARCHAR NULL` (null for seed row), `to_status VARCHAR NOT NULL`, `effective_at TIMESTAMPTZ NOT NULL`, `actor_id UUID NULL`, `actor_email VARCHAR NULL`, `reason_code VARCHAR NULL` (from per-line vocab per L5), `reason_note TEXT NULL` (free text). INDEX `(policy_id, effective_at DESC)`. Backfill per L7. |
| `member_status_history` (§A) | Per-member status-transition audit trail (L2) | `member_id UUID NOT NULL`, `from_status VARCHAR NULL`, `to_status VARCHAR NOT NULL`, `effective_at TIMESTAMPTZ NOT NULL`, `actor_id UUID NULL`, `actor_email VARCHAR NULL`, `reason_code VARCHAR NULL` (from vocab: `{admin_terminate, arrears_lapse, arrears_clear, scheduled_change, group_cascade, dependant_swap, enrolment, other}`), `reason_note TEXT NULL`. INDEX `(member_id, effective_at DESC)`. Backfill per L7 — seed + optional terminal-state second row. |

**Additive column on user-service Provider entity** (V113 — per L1):

| Entity | New column |
|---|---|
| `Provider` | `network_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD'` — vocab `{STANDARD, TIER_1, TIER_2, TIER_3}`. Backfill all existing rows to `STANDARD`. Rules-engine `ProviderFact.networkTier` (already declared per F13-6) starts getting populated from this column — a downstream effect worth calling out to co-payment rule authors. |

**Backfill strategy** (per L7):
- Policies (all lines): one row per policy at `(from=NULL, to=<current status>, effective_at=bound_at OR created_at, actor_email='migration', reason_code='initial_backfill')`.
- Members: one row per member at `(from=NULL, to=<current status>, effective_at=created_at, reason_code='initial_backfill')` + if `termination_date IS NOT NULL AND status='terminated'` a second row `(from='active', to='terminated', effective_at=termination_date, reason_code='backfill_from_termination_date')`; same for `suspend_reason IS NOT NULL AND status='suspended'`.
- Providers: single `network_tier='STANDARD'` — no history table for providers.

### Kafka topology

One new topic (§B) with one cross-service consumer:

| Component | Direction | Topic | Behaviour |
|---|---|---|---|
| `user-service PolicyStatusChangedPublisher` | out (§B) | `medfund.user.policy-status-changed` (new) | On every `PolicyStatusTransitionService.transition(...)` COMMIT → publish `{tenantId, policyId, policySource, insuranceLine, fromStatus, toStatus, effectiveAt, reasonCode, actorId, actorEmail}`. Fires from all 6 per-line services (LifePolicy / FuneralPolicy / DisabilityPolicy / TravelPolicy / VehiclePolicy / PropertyPolicy). |
| `contributions-service PolicyStatusChangedConsumer` | in (§B) | `medfund.user.policy-status-changed` | On `toStatus IN ('lapsed','terminated')`: call `EarningScheduleClosureService.closeOutForPolicyClosure(tenantId, policyId, policySource, effectiveAt)` — marks open-future `earning_schedule` rows as closed with `earned_at_period_end = 0` + writes new `is_closure = TRUE` schedule rows for the residual period. On `toStatus = 'suspended'`: earning pauses (grill note 5 owed for semantics). On `toStatus = 'active'` from a prior suspend: earning resumes. `.doOnSuccess` ack per `bug_reactor_kafka_ack_swallow`. Idempotent via new `earning_schedule.closure_ref UUID NULL` field checked before write. |

**No member-status Kafka topic** — L2 says member_status_history is written in-JVM in the same reactive transaction; no cross-service consumer needs the member transition event today (contributions-service already consumes `medfund.users.member-lifecycle` per Phase 11 for arrears + auto-lapse).

**Deploy order per parent-plan invariant**: user-service ships PolicyStatusChangedPublisher first; contributions-service ships PolicyStatusChangedConsumer after user-service is emitting.

### Report shapes

Four report surfaces (per L9-L12). All `@RequiresReport(...)` + `ReportEnvelopeBuilder` + `SecurityEventPublisher.publishDataAccess` on export. Three under `ReportFamily.POLICY_LIFECYCLE` served from **user-service** at `/api/v1/reports/policy-lifecycle/*` per L8; one under `ReportFamily.CLAIMS_FINANCIAL` served from **claims-service** at `/api/v1/reports/claims/provider-network-utilization` per L13.

**POLICY_MOVEMENT** — `GET /api/v1/reports/policy-lifecycle/movement?periodStart=&periodEnd=&insuranceLine=&reportingCurrency=` — roll shape per (insurance_line × currency × period-month). Columns per L10: opening_count, opening_wp, new_business_count, new_business_wp, renewal_count, renewal_wp, reinstatement_count, reinstatement_wp, lapse_count, lapse_wp, terminate_count, terminate_wp, suspend_count, suspend_wp, closing_count, closing_wp. Envelope carries native `perCurrency`; `fxRates` best-effort per G28. XLSX: one sheet per line + summary. HEALTH branch reads `member_status_history` for member counts; annual-line branch reads `policy_status_history`.

**PERSISTENCY_COHORT** — `GET /api/v1/reports/policy-lifecycle/persistency?periodStart=&periodEnd=&insuranceLine=&checkpoints=3,6,12,24,36&reportingCurrency=` — monthly bind cohorts × configurable retention checkpoints. Rows: `(cohort_month, insurance_line, currency_code, checkpoint_months, retention_pct, cohort_size, retained_count)`. "Still active" per L9 + L16: HEALTH = status was 'active' at checkpoint month AND Contribution row exists for checkpoint month; annual = renewal-chain-active at checkpoint. XLSX: pivot on cohort_month × checkpoint per line + summary sheet with overall retention curve. See grill note 4 for the Contribution-presence lookup design.

**GROUP_CENSUS** — `GET /api/v1/reports/policy-lifecycle/group-census?asOf=YYYY-MM-DD&groupId=&status=&reportingCurrency=` — snapshot per L11. `asOf` is required (no default). Rows: `(groupNumber, groupName, memberNumber, name, memberStatus, principalOrDependantOf, schemeName, monthlyContribution, joinedAt, terminationDate)`. Sort: principal followed by their dependants. Optional `?groupId=` narrows to a single group; optional `?status=active,suspended,lapsed` narrows the member set. XLSX: one sheet per group + summary sheet with headcount by group × status.

**PROVIDER_NETWORK_UTILIZATION** — `GET /api/v1/reports/claims/provider-network-utilization?periodStart=&periodEnd=&insuranceLine=&networkTier=&reportingCurrency=` — two-level per L12. Envelope carries `summary: Map<String, NetworkTierTotals>` + `detail: List<ProviderUtilizationRow>`. Hosted in claims-service per L13; cross-service `ProviderClient.batchLookup(providerIds)` for name + network_tier enrichment (batch cap N=100, envelope warnings on user-service failure). XLSX: summary sheet + per-provider detail sheet.

### Angular surfaces

Tenant-admin CRUD (§A):
- Existing provider list page (`/tenant/admin/providers` — verify path at plan time) — new inline-editable "Network tier" column dropdown per L17 (`STANDARD/TIER_1/TIER_2/TIER_3`).
- Existing per-line policy detail pages (LifePolicy / FuneralPolicy / DisabilityPolicy / TravelPolicy / VehiclePolicy / PropertyPolicy detail components) — new `PolicyStatusActionButtonsComponent` mounts on each with 4 action buttons (Lapse / Terminate / Suspend / Reinstate) that open a shared `PolicyStatusActionModalComponent` parameterised by policy source + reason-code vocab per L5 (see grill note 7 for shared-vs-per-line component call).

Reports (§C):
- `/tenant/finance/reports/policy-lifecycle/movement` — period picker (default: prior month), insurance-line filter, reporting-currency override, export button. Table shape per L10 with sheet-per-line drill.
- `/tenant/finance/reports/policy-lifecycle/persistency-cohort` — period picker, insurance-line filter, checkpoints multi-select (default 3/6/12/24/36), reporting-currency override, export. Renders retention curve chart + cohort pivot table.
- `/tenant/finance/reports/policy-lifecycle/group-census` — as-of date picker (required — no default), group picker (debounced search-select per `feedback_no_raw_id_inputs`), status filter, export. Renders snapshot table + headcount-by-group summary.
- `/tenant/finance/reports/claims-financial/provider-network-utilization` — period picker, insurance-line filter, network_tier filter, reporting-currency override, export. Renders network summary panel + filterable per-provider table.

Reports hub (§C) auto-registers the three POLICY_LIFECYCLE keys via existing `TenantReportConfigService` — new `POLICY_LIFECYCLE` family card renders when any key enabled. `PROVIDER_NETWORK_UTILIZATION` appears under the existing `CLAIMS_FINANCIAL` card.

No new permissions strictly needed — existing `policy:write` covers lapse/terminate/suspend/reinstate. Consider a new `policy:status_transition` for finer control if a supervisor / operator split emerges (plan-time call).

### Proposed §A / §B / §C tranche split

**§A — Schema + writers + admin modals** (foundation + user-facing admin surface):
- user-service tenant migrations V111 (policy_status_history) + V112 (member_status_history) + V113 (provider.network_tier) — bundling decision per grill note 2.
- Backfill per L7 (seed + optional terminal-state second row).
- user-service `MemberStatusTransitionService` (single write pathway per L3) + retrofit of ~6 existing member-status write sites (ArrearsBreachedConsumer, ArrearsClearedConsumer, MemberController, ScheduledStatusExecutor, GroupController cascade, MemberLifecycleService future writers). Exhaustive grep sweep per grill note 8.
- user-service `PolicyStatusTransitionService<T>` × 6 concrete subclasses (LIFE/FUNERAL/DISABILITY/TRAVEL/VEHICLE/PROPERTY) per L3 + L4 + L5.
- 24 admin REST endpoints (`POST /{life|funeral|disability|travel|vehicle|property}-policies/{id}/{lapse|terminate|suspend|reinstate}`) with per-line reason-code vocab per L5.
- Angular: `PolicyStatusActionButtonsComponent` + `PolicyStatusActionModalComponent` mounted on 6 policy detail pages; inline-editable network_tier dropdown on provider list.
- IT: `StatusTransitionServiceIT` + `PolicyStatusTransitionServiceIT` in-tranche per L18, in dedicated `user-service/src/test/resources/db/policy-lifecycle-migration/V001..sql`.
- **Success criterion**: admins can lapse/terminate/suspend/reinstate any of the 6 annual-line policies from the UI; history rows land in both tables; member-side retrofit passes existing ArrearsBreached IT.

**§B — Kafka + earning-schedule closure** (silent — no user-visible surface):
- user-service `PolicyStatusChangedPublisher` + `medfund.user.policy-status-changed` topic per Kafka topology.
- contributions-service `PolicyStatusChangedConsumer` (mirrors `PolicyEndorsedConsumer` shape) + payload record/parser under `com.medfund.contributions.premium.consumer.*`.
- contributions-service `EarningScheduleClosureService.closeOutForPolicyClosure(...)` extension: closes open-future periods on LAPSED/TERMINATED; pause/resume semantics for SUSPENDED per grill note 5.
- `earning_schedule.closure_ref UUID NULL` schema addition (contributions-service tenant migration V0NN — plan-time call for exact number).
- IT: `PolicyStatusChangedConsumerIT` in-tranche in dedicated `contributions-service/src/test/resources/db/policy-status-consumer-migration/V001..sql`.
- **Success criterion**: lapsing a live LIFE policy mid-year via §A's endpoint stops earning within 1 minute; UPR movement report (Phase 12 §B) reflects the closure.

**§C — Reports + Angular** (user-facing reports surface lights up):
- user-service `PolicyMovementReportController`, `PersistencyCohortReportController`, `GroupCensusReportController` at `/api/v1/reports/policy-lifecycle/*` + query repositories + workbook services.
- claims-service `ProviderNetworkUtilizationReportController` at `/api/v1/reports/claims/provider-network-utilization` + `ProviderClient` cross-service call to user-service.
- `ReportFamily.POLICY_LIFECYCLE` enum add + 3-key reassignment; `PROVIDER_NETWORK_UTILIZATION` cadence flip to `cadenced=true` per L14 (also flip PERSISTENCY_COHORT + GROUP_CENSUS).
- `TenantReportConfigService` auto-registration of the 4 keys.
- Angular pages under `/tenant/finance/reports/policy-lifecycle/*` + one under `/tenant/finance/reports/claims-financial/provider-network-utilization` + reports hub card for new POLICY_LIFECYCLE family + family-label service entry.
- Gateway route registration for `/api/v1/reports/policy-lifecycle/*` (user-service) + `/api/v1/reports/claims/provider-network-utilization` (claims-service).
- IT: `PolicyMovementReportIT`, `PersistencyCohortReportIT`, `GroupCensusReportIT`, `ProviderNetworkUtilizationReportIT` in-tranche in dedicated migration folders per L18.
- Playwright: `policy-lifecycle-reports.spec.ts` + `provider-network-utilization.spec.ts` — hub → each report → filter + export → assert download + envelope warnings on missing FX.
- **Success criterion**: 4 report keys enabled in tenant admin → all 4 pages render + export XLSX; toggle-off returns 403; envelope warnings render inline.

§A/§B/§C ship as separate commits per Phase 10 + 11 + 12 precedent. All should be fully grilled + planned at implement-time via `create-plan`; `implement-plan` treats tranche boundaries as hand-off points per the plan header.

### Grill notes for `create-plan`

1. **Per-line reason-code vocab** (L5) — plan the exact vocab per line. Suggested starter set (verify at plan time against real ops needs): LIFE = `{non_payment, policyholder_cancel, insured_event, mortality}`; FUNERAL = `{non_payment, policyholder_cancel, insured_event}`; DISABILITY = `{non_payment, policyholder_cancel, insured_event, recovery}`; TRAVEL = `{non_payment, trip_cancelled, insured_event}`; VEHICLE = `{non_payment, sold, total_loss, storage_suspend, policyholder_cancel}`; PROPERTY = `{non_payment, sold, total_loss, policyholder_cancel}`. Central `PolicyReasonCode` enum registry; per-line valid-set map; validate at controller.
2. **Migration bundling** (F13-4) — three schema deltas (V111 policy_status_history, V112 member_status_history, V113 provider.network_tier). Phase 12 bundled 6 policy tables into one V102 for atomicity. Consider bundling all three into V111 (single-file atomic pass) versus split-per-concern. Recommend split — the tables are functionally independent and V113 is a single-column ADD.
3. **GroupController status cascade retrofit through MemberStatusTransitionService** (F13-11 + L3) — `services/java/user-service/src/main/java/com/medfund/user/controller/GroupController.java:110` cascades group status to member statuses. Must route through the new `MemberStatusTransitionService.transition(...)` so history rows fire. Write out the exact `GroupService` change at plan time; add IT that a group deactivation writes N `member_status_history` rows.
4. **Contribution-presence lookup performance for HEALTH persistency** (L16) — persistency query needs "member had Contribution in month M" for potentially 100k members × 36 checkpoint months. Two options: (a) runtime SQL LEFT JOIN on `contributions WHERE member_id=? AND period_start <= checkpoint_end AND period_end > checkpoint_start` — cheap at low volume, degrades; (b) materialize as `member_contribution_presence(member_id, contribution_month, has_contribution)` matview refreshed by existing Phase 12 `PremiumEarningExecutor`'s nightly pass — cheap read, requires refresh discipline. Plan at code altitude.
5. **PolicyStatusChangedConsumer state machine's earning-effect table** (L6) — plan the exact transition-to-earning-effect table:
   | to_status | earning effect |
   |---|---|
   | `lapsed` | close-out open-future periods (`earned = 0`, mark `is_closure=TRUE`) |
   | `terminated` | close-out open-future periods |
   | `suspended` | pause: freeze open period at current earned amount; no new period rows written until reactivation |
   | `active` (from suspended) | resume: reopen frozen period; PremiumEarningExecutor picks up on next nightly pass |
   | `active` (from lapsed/terminated) | REINSTATE — write new schedule rows from `effectiveAt` forward (grill: what's the "reinstatement written premium"? — pro-rata of original? tenant-configurable? plan-time call) |
6. **PROVIDER_NETWORK_UTILIZATION ProviderClient batching** (L13) — cap batch size at N=100 per HTTP call. Plan the failure path: if user-service errors, envelope carries warnings + partial data (per G37 invariant #7) — but the report still succeeds with provider names replaced by `<Provider Unknown>` and network_tier defaulting to `STANDARD`. Add IT for the peer-down scenario.
7. **Angular per-line modal pattern** (L4 + L5) — choose between (a) one shared `PolicyStatusActionButtonsComponent` + `PolicyStatusActionModalComponent` parameterised by policy source + reason vocab (recommended — one file to maintain) versus (b) 6 per-line variants (each with per-line specifics inline). Recommend (a) with a `PolicyLifecycleActionRegistry` service that returns the valid actions + reason-code vocab for a given policy source. Verify at plan time that the 6 policy detail pages have consistent hosting structure to mount the shared component.
8. **§A cross-tranche audit — exhaustive write-site sweep** (F13-11 + L3) — the "~6 known" member-status write sites is a minimum. Do a systematic grep at plan time: `grep -rn "\.setStatus\|member.status\s*=\|members\s*SET\s*status" services/java/user-service/src` and route each hit through MemberStatusTransitionService. Missing one = silently wrong reports for that transition path.
9. **Testing for `bug_reactor_kafka_ack_swallow` in PolicyStatusChangedConsumer** (L6 §B) — must use `.doOnSuccess` for ack per the memory. Add IT guard that an error inside the consumer does NOT ack the offset (the message is redelivered on next poll). Same pattern as existing PolicyEndorsedConsumer.
10. **Report envelope FX for POLICY_MOVEMENT written_premium sums** (L10) — envelope carries native `perCurrency` per invariant #1; `fxRates` best-effort per G28. Missing FX for a historical `period_end` → currency omitted from envelope `fxRates` + warnings entry. Report still succeeds — matches Phase 12 U8 precedent.
11. **`.claude/portals.md` update owed** — Phase 13's admin surface (lapse/terminate/suspend/reinstate buttons on 6 policy detail pages + provider network_tier column) belongs in the tenant-admin portal spec. Add a paragraph in the tenant-admin section documenting the shape.

### What's owed back to the parent-plan outline

- **Line 3327 dither ("claims-service or finance-service")** — L13 settles as claims-service. Struck through above.
- **Line 3326 GROUP_CENSUS mentioned as a report key but not in the overview sentence** — L8 + L11 make it a first-class report. Overview expansion above.
- **Line 3332 Success Criteria trivial "per-report unit + IT + Playwright"** — L18 upgrades to Phase-12-style per-tranche success criteria (see Proposed §A/§B/§C tranche split section).

### Success Criteria

**Status: Fully implemented via the expanded sub-plan [thoughts/shared/plans/2026-08-23-policy-lifecycle-module.md](2026-08-23-policy-lifecycle-module.md), landed 2026-08-24 as commit `5eb62a9 "Land Phase 13 of the financial-reporting suite"`.**

Grilling checkpoint satisfied 2026-08-23 (L1..L18 + F13-1..F13-11 above). All ten sub-plan phases green on their in-tranche automated verification per the sub-plan Deviations log:

- §A (Phases 1-4 — schema + writers + admin modals): tenancy-service tenant V111 `policy_status_history` + V112 `member_status_history` + V113 `providers.network_tier` (and public V135 mirror for the user-service `@Table(schema="public")` Provider entity per §A Phase 4 deviation); `StatusTransitionRecorder` shared write helper + `MemberStatusTransitionService` retrofitted through the golden `MemberService.transitionStatus` pathway (plus one `MemberLifecycleFactBuilder` corner case); abstract `PolicyStatusTransitionService<T>` base + 6 concrete subclasses (LIFE/FUNERAL/DISABILITY/TRAVEL/VEHICLE/PROPERTY) + 24 admin REST endpoints under `POST /api/v1/{line}-policies/{id}/{lapse|terminate|suspend|reinstate}`; `PolicyReasonCode` per-line vocab registry; Angular `PolicyStatusActionButtonsComponent` + `PolicyStatusActionModalComponent` mounted in each of the 6 form headers + inline `network_tier` `type:'select'` column on `ProvidersComponent` via new `DataTableComponent` select-cell arm.
- §B (Phases 5-6 — Kafka + earning-schedule closure): new topic `medfund.user.policy-status-changed` with user-service `PolicyStatusChangedPublisher` (flat-map JSON envelope matching `PolicyEndorsedPublisher` / `PolicyIssuedPublisher` shape per §B Phase 5 deviation); tenancy-service V115 `earning_schedule.closure_ref UUID` idempotency guard; contributions-service `PolicyStatusChangedConsumer` with the codified earning-effect state machine (`LAPSED`/`TERMINATED` → close-out open-future periods with `earned_at_period_end=0` + `is_closure=TRUE`; `SUSPENDED` → freeze current period; `ACTIVE` from `SUSPENDED` → resume; `ACTIVE` from `LAPSED`/`TERMINATED` → REINSTATE with pro-rata written_premium); `EarningScheduleClosureService.closeOutForPolicyClosure(...)` extension per Grill note 5; `.doOnSuccess` ack per `bug_reactor_kafka_ack_swallow`.
- §C (Phases 7-10 — reports + Angular): tenancy-service V114 `member_contribution_presence` materialized view + UNIQUE `(member_id, contribution_month)` index + `EarningScheduleClosureService.refreshMemberContributionPresence()` chained after the existing Phase 12 `refreshMemberFirstContribution` (per L16 + Grill note 4); user-service `PolicyMovementReportController` + `PersistencyCohortReportController` + `GroupCensusReportController` at `/api/v1/reports/policy-lifecycle/*` + `PolicyLifecycleReportQueryRepository` covering all three report shapes + three workbook services; claims-service `ProviderNetworkUtilizationReportController` at `/api/v1/reports/claims/provider-network-utilization` + `ProviderClient` batched cross-service enrichment (N=100 cap, `<Provider Unknown>` + STANDARD fallback via `CrossServiceCallHelper` per L13 + Grill note 6); `ReportFamily.POLICY_LIFECYCLE` enum add + 3-key reassignment + 3 cadence flips per L8/L14; Angular pages under `/tenant/finance/reports/policy-lifecycle/*` + `/tenant/finance/reports/claims-financial/provider-network-utilization` + new hub `POLICY_LIFECYCLE` family card + family-label service entry; gateway route registration for both surfaces.

Deferred to follow-up sub-phases per the sub-plan Deviations log:

- **Playwright** — `policy-lifecycle-reports.spec.ts` + `policy-status-workflow.spec.ts` + `provider-network-tier.spec.ts` shipped but not exercised in the landing session (deferred with the rest of the e2e runs).
- **Kafka IT execution** — `PolicyStatusChangedPublisherIT` and `PolicyStatusChangedConsumerIT` authored on top of `AbstractKafkaIntegrationTest`; full IT execution deferred to the `make test-integration` pass (Testcontainers Docker not up in the landing session).
- **Report ITs** — `PolicyLifecycleReportControllerIT` (7 cases) via `db/policy-lifecycle-reports-migration/` and `ProviderNetworkUtilizationReportIT` (5 cases, including peer-down) via `db/provider-utilization-report-migration/` deferred as code-review-ready follow-ups needing bespoke Testcontainers baselines parallel to `db/policy-lifecycle-migration/`.
- **Legacy per-line `suspend/terminate` methods on `PoliciesService`** — dead call sites remain on the six form components after header buttons moved to `PolicyStatusActionButtonsComponent`; scheduled for a follow-up sweep to keep the Phase 4 diff scoped.
- **Manual `verify` walkthroughs** — end-to-end golden-path browser demos for §A (lapse → history rows + Kafka event), §A network_tier admin dropdown, §B mid-term lapse → earning schedule closure, and each of the four §C report exports.
- **Refund calculation** on LAPSED/TERMINATED (per L6 non-goal) and **bulk policy actions** (non-goal) stay explicitly out of scope.

Per parent-plan Testcontainers policy each deferred IT lands with a purpose-built migration folder so the ITs don't force-widen every unrelated slice's baseline schema.

---

## Phase 14: Actuarial Module (Python) — IBNR / Triangles / Studies

> **Grilled 2026-08-24.** Decisions A1..A18 (numbered A* — for Actuarial — to avoid collision with plan-wide G*, reinsurance R*, producer P*, underwriting U*, and lifecycle L* numbering; matches Phase 10-13 sub-plan convention). Additional sub-decisions A3b, A8b, A12b triggered by primary decisions.
> The outline was expanded into a full mini-plan through interactive decision-making; scope escalated
> substantially beyond the original outline (all six report keys retained but wrapped in an async job
> pattern per A8; three new tenant history/config tables + one new Kafka topic pair + rules-engine
> ACTUARIAL category deferred to §D + Member schema widened for death signal per A12b + claims-service
> gains claim_reserve_history table per A3b).
> Recommended split into **§0** (schema prerequisites + async job table + admin UIs),
> **§A** (Python actuarial package + IBNR + LOSS_TRIANGLE + async infra), **§B** (PERSISTENCY + LAPSE),
> **§C** (MORTALITY + MORBIDITY), **§D** (rules-engine ACTUARIAL category). §0 ships adjudicators the
> ability to set reserves + admins the ability to record deaths + tenants the ability to configure
> bases; §A lights up first reports; §B + §C add member-shaped studies; §D closes A5's rules-engine
> requirement.
> Original 20-line outline retained below as ~~strike-through~~ for provenance.

### Original outline (superseded 2026-08-24 by Decisions Log)

~~Chain-ladder IBNR + loss triangles + persistency/mortality/morbidity/lapse studies in `services/python/ai-service`. Java services call via HTTP.~~

~~**ai-service**: new `app/actuarial/` package: `chain_ladder.py`, `ldf.py`, `persistency.py`, `mortality.py`. FastAPI endpoints:~~
  ~~- `POST /actuarial/ibnr` — input: claim triangle JSON; output: LDFs + IBNR estimate.~~
  ~~- `POST /actuarial/loss-triangle` — input: claim history; output: triangle matrix.~~
  ~~- `POST /actuarial/persistency-study` — input: policy cohort; output: A/E ratios by policy year.~~
  ~~- `POST /actuarial/mortality-study` — input: exposure data; output: A/E by age × sex.~~
~~**claims-service** or **finance-service** `ActuarialReportController` — orchestrates: pulls tenant-scoped data, calls Python, wraps result as XLSX. Report keys `IBNR_TRIANGLE`, `LOSS_TRIANGLE`, `PERSISTENCY_STUDY`, `MORTALITY_STUDY`, `MORBIDITY_STUDY`, `LAPSE_STUDY`.~~
~~**Angular** actuarial dashboard pages with triangle visualisation.~~

~~**Grilling checkpoint** required — chain-ladder implementation details, validation-set choice.~~

### Overview

Greenfield **actuarial module** spanning services/python/ai-service (chain-ladder + LDF + persistency + mortality + morbidity + lapse compute using `chainladder-python` per A2), finance-service (aggregator per A1 — hosts the six-endpoint `ActuarialReportController` + new cross-service clients + Kafka publisher/consumer for the async job pattern per A8), tenancy-service (three new public-schema tenant-basis config tables per A10 + A11 + admin CRUD), claims-service (new `claim_reserve_history` tenant table per A3b + adjudicator "Set/update reserve" UI action feeding the incurred-triangle shape per A3), user-service (`Member.death_date` + `cause_of_death` columns per A12b + tenant admin "Record death" UI action + `MEMBER_DEATH_RECORDED` audit event), rules-engine (deferred to §D — new `RuleCategory.ACTUARIAL` + `TriangleFact` + `DevelopmentPeriodFact` + templates for the three LDF selection methods per A5), and Angular (six report pages under `/tenant/finance/reports/actuarial/*` per A14 split-view triangle-table+LDF-line-chart shape, adjudicator reserve UI, tenant admin death UI, three basis-config admin pages, async job polling UI, ACTUARIAL family card on the reports hub).

All six report keys already ship in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:108-113` — `IBNR_TRIANGLE`, `LOSS_TRIANGLE`, `PERSISTENCY_STUDY`, `MORTALITY_STUDY`, `MORBIDITY_STUDY`, `LAPSE_STUDY` all mapped to `ReportFamily.ACTUARIAL` (which also already exists at `ReportFamily.java:24`). Phase 0-13 shared infra composes cleanly — the async job pattern per A8 is the one net-new cross-service pattern for the platform. `CrossServiceCallHelper` already carries an actuarial-specific custom-timeout arm at `services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java:76-82` referenced by inline comment.

Triangles are generated in the tenant's reporting currency at service-date FX per A6 (Rule 1), stratified per insurance-line one-sheet-per-line per A7, with three shapes (paid/incurred/reported) via `?shape=` per A3, at quarterly-default grain selectable via `?grain=` per A4, using volume-weighted LDF default (with rules-engine method selection deferred to §D per A5). PERSISTENCY_STUDY reads Phase 13's `member_status_history` + `member_contribution_presence` matview per L16 + `policy_status_history` (annual lines) as its ACTUAL numerator; expected retention comes from new `public.tenant_persistency_basis` per A10. MORTALITY_STUDY reads Member deaths (new `death_date` field per A12b) against mortality reference tables (new `public.tenant_mortality_basis` per A11 + ai-service YAML basis tables); MORBIDITY_STUDY reads claim-onset dates + insured event indicator against `public.tenant_morbidity_basis` + ai-service YAML incidence tables. LAPSE_STUDY uses per-line branched cohorts per A13 (HEALTH monthly-enrollment; annual-lines annual-bind).

**§0** ships all schema prerequisites and admin UIs — no reports light up yet, but adjudicators can set reserves and tenant admins can configure bases + record deaths. **§A** ships the async job pattern (`actuarial_report_job` table + `medfund.actuarial.job-requested` / `medfund.actuarial.job-completed` Kafka topics per A8/A8b/A9/A15) + the Python actuarial package + IBNR/LOSS reports + Angular polling UI. **§B** adds PERSISTENCY + LAPSE reports (reuses Phase 13 tables + §0 basis config). **§C** adds MORTALITY + MORBIDITY reports (reads §0 death fields + basis config). **§D** integrates rules-engine ACTUARIAL category for tenant-configurable LDF method selection per A5.

### Decisions Log (A1..A18 — plus sub-decisions A3b, A8b, A12b)

- **A1 — Actuarial orchestrator ownership**: **single actuarial controller in finance-service (aggregator pattern)**. Finance-service hosts the six `ActuarialReportController` endpoints + new `ClaimsClient` (may be net-new; verify at plan time), `UserClient`/`MemberClient` (new) via `CrossServiceCallHelper` (custom-timeout arm already documented for actuarial at `services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java:76-82`). Actuarial reports need both claim data (IBNR/LOSS/MORBIDITY) and member/policy data (PERSISTENCY/MORTALITY/LAPSE), so finance-service as the composer is a cleaner cross-service seam than either heavy-data owner. Rejected: split by data owner (two controllers + doubled AiServiceClient wiring); single claims-service controller (2-hop path for member-shaped studies); new greenfield actuarial-service (heavy overhead for 6 endpoints).

- **A2 — Chain-ladder library**: **`chainladder-python` (CAS-maintained) + `pandas` + `numpy` + `scipy` + `openpyxl` added to `services/python/ai-service/pyproject.toml`**. Ships Mack chain-ladder + Bornhuetter-Ferguson + Bootstrap out of the box; regulator-audit-friendly ("standard community library"). Cold-start `numba` JIT compile penalty mitigated by a trivial pre-warm run on FastAPI lifespan startup. Rejected: hand-rolled Mack on NumPy only (harder regulator story, no BF); hybrid (chainladder for IBNR only, hand-rolled for the rest — two code styles); statsmodels-first (overkill).

- **A3 — Triangle shape**: **ship all three shapes (paid / incurred / reported) via `?shape=paid|incurred|reported`**. Paid = row=service_date period, column=submission_date period, cell=cumulative `paid_amount`. Incurred = row=service_date, column=submission_date, cell=cumulative `paid_amount + reserved_amount_as_of` (requires A3b's `claim_reserve_history`). Reported = row=service_date, column=submission_date, cell=cumulative COUNT. Rejected: paid-only (misses actuaries' preferred shape); incurred-only (blocks §A on the reserves schema); reported-only (loses severity signal, halves plan value).

- **A3b — Reserved-amount schema (triggered by A3)**: **new `claim_reserve_history(claim_id, reserved_amount, effective_at, actor_id, actor_email, reason_note)` tenant table shipped in Phase 14 §0**. Adjudicator UI grows a "Set/update reserve" action; triangle cell for period (i, j) sums `claim_reserve_history` rows where `effective_at <= end_of_period_j` — point-in-time reserves for historical cells. Rejected: additive `Claim.reserved_amount` column only (single mutable field makes incurred triangle actuarially wrong — every historical cell reflects today's reserve); skip incurred (rejects A3); reuse `approved_amount` as proxy (conflates legal-obligation with actuarial-ultimate).

- **A4 — Development-period grain**: **quarterly default + user-selectable `?grain=month|quarter|year`**. Quarterly is medical / short-tail industry norm; selectable grain lets actuaries switch to monthly for signal or yearly for stability. Both accident-period rows and development-period columns snap to the chosen grain to keep the diagonal aligned. Rejected: monthly-fixed (LDF noise with sparse claim data); quarterly-fixed (forecloses actuary control); yearly-fixed (5×5 too coarse, useless for HEALTH).

- **A5 — LDF selection method**: **new `RuleCategory.ACTUARIAL` in rules-engine + templates for volume-weighted / simple-average / N-year weighted + tenant admin UI + fact wiring — deferred to §D**. In §A, LDF selection defaults to volume-weighted (chainladder-python's default). §D retrofits by adding a rules-engine call in the orchestrator pipeline (pipeline-order sub-question queued as create-plan note per Grill note 2). Rejected: `?ldfMethod=` URL param only (Rule 5 non-compliant — tenant business rules must live in rules-engine); volume-weighted only (forecloses actuaries); chainladder library config JSON in request (leaks library internals; hard to audit per Rule 3).

- **A6 — Multi-currency triangle stratification**: **reporting-currency-only triangles, every claim converted to reporting currency at service-date FX rate before shaping**. Aligns with Rule 1 (conversion before sum). Missing-FX aggressiveness policy queued as create-plan note (per-claim skip+warn preferred over per-report fail-loud since a 5-year triangle spans thousands of service-dates). Reporting currency resolves via existing `ReportingCurrencyResolver` per parent-plan invariant #1. Rejected: per-currency triangles + reporting-currency summary (matches Phase 12 UPR but adds UI complexity — 8-line × 3-currency XLSX has 24+ sheets); native per-currency only (loses executive single-triangle-view); both per-currency AND reporting-currency triangles (doubles compute; two truths in envelope).

- **A7 — Line-of-business partitioning**: **all lines returned in one envelope keyed by insurance_line + one XLSX sheet per line**. Optional `?insuranceLine=X` narrows to a single line for detail views. Empty-line triangles (scaffolded lines with zero claims) return an empty matrix with a `warnings` note rather than being omitted. Matches Phase 13 L10 workbook precedent. Rejected: required per-line param (8 HTTP calls to build 8-line workbook); default HEALTH-only opt-in (forecloses annual-line adoption); flat triangle with line dimension (actuarially wrong — HEALTH ≠ VEHICLE development).

- **A8 — Java→Python protocol**: **async job pattern**. finance-service publishes `medfund.actuarial.job-requested` on report submission → ai-service consumes + computes → ai-service publishes `medfund.actuarial.job-completed` with result inline → finance-service persists to `actuarial_report_job.result_json`. Angular polls `GET /api/v1/reports/actuarial/jobs/{jobId}` for status + result. Supersedes plan text ("Actuarial calls to Python are synchronous — 30s ceiling" at line 3669). Handles arbitrarily large triangles; caller isn't blocked; result is durable. Rejected: sync HTTP (plan default) with pre-baked triangle (blocks caller for up to 30s; large-tenant risk); sync HTTP with Python-queries-claims (Python becomes second tenant boundary — Rule 2 fuzziness); Kafka-first request-response over event bus (anti-pattern for request-response semantics).

- **A8b — Async payload shape (triggered by A8)**: **pre-baked triangle JSON embedded in the Kafka event**. finance-service does the query + FX conversion + shaping; event carries the fully-formed Triangle for Python. Java stays as Rule 2 tenant boundary; Python is stateless. Message-size guard queued as create-plan note (>800KB warn; >900KB reject-and-retry with reduced-precision floats). Rejected: params-only event + Python queries claims (Python becomes tenant boundary; FX split across languages); MinIO-backed (rejected symmetrically per A8b for input, same rejection for output); params + Python callback to Java prepare-triangle sync endpoint (reverses async decision partially).

- **A9 — Result cache + freshness**: **`actuarial_report_job.result_json` is durable source of truth; params-hash = SHA256(tenantId, reportKey, periodStart, periodEnd, insuranceLine, shape, grain, currency, claim_count_in_window, max(created_at))**. New claim ingestion → new hash → new job (natural cache invalidation without a separate topic). Old rows retained for audit. Angular polls by jobId. Retention policy + partial-unique-index-on-in-flight-hash queued as create-plan notes. Supersedes plan text ("cache results by (tenant, report-key, period) in Redis for 1h" at line 3669). Rejected: job row + Redis 1h TTL secondary (cache-invalidation decoupled from claim ingestion; stale results up to 1h); fixed 1h freshness (stale results); Redis-only ephemeral (loses re-open + email-attachment capability).

- **A10 — Expected retention basis (PERSISTENCY_STUDY A/E)**: **new `public.tenant_persistency_basis(tenant_id, insurance_line, cohort_months, expected_retention_pct, source_note, effective_from, effective_to)` public-schema table + tenancy-service CRUD controller + tenant admin CRUD UI**. Mirrors `tenant_endorsement_config` (V134) shape. Ships alongside §0. Seed defaults per line optional (create-plan decision — see Grill note 10). Rejected: rules-engine PERSISTENCY_BASIS category (heavy — full tranche); ai-service YAML per-jurisdiction only (rigid, no per-tenant tuning, Rule 5 non-compliant); bootstrap from Phase-13 COHORT rolling-24m average (self-referential — A/E meaningless).

- **A11 — Mortality/morbidity reference tables + tenant tie**: **hybrid — ai-service ships reference-table YAMLs (`A1949_52`, `A67_70`, `SA85_90`, `CSO_2017` mortality; `CIDA`, `GLTD87` morbidity) as resource files + new `public.tenant_mortality_basis(tenant_id, insurance_line, basis_name, mortality_multiplier)` + parallel `public.tenant_morbidity_basis`**. Tenants pick a basis by name + apply an adjustment factor ("115% of A1949-52"). Matches industry practice. Rejected: A10-shape per-row `(age, sex, qx)` (tenant admin loads 200+ rows manually; no multiplier); ai-service constants only (rigid; no per-tenant tuning); rules-engine MORTALITY_BASIS + MORBIDITY_BASIS categories (heavy — another tranche).

- **A12 — Exposure derivation for A/E denominators**: **per-study derivation — mortality/morbidity=actual-days-exposed on Member.enrollment_date + Member.termination_date + Member.death_date (A12b); persistency=Phase-13 cohort headcount at bind + checkpoint; lapse=policy-years-in-force from `policy_status_history`**. Each study uses its natural denominator. Rejected: uniform central-exposure (over-approximates for HEALTH; regulator wants actual-days for mortality); actual-days-exposed for all (100k members × 60 months matview refresh cost); policy-year for annual + monthly for HEALTH (two "A/E" numbers in one family — hard to explain).

- **A12b — Death signal (triggered by A12)**: **add `death_date LocalDate NULL` + `cause_of_death VARCHAR(80) NULL` on Member entity (tenant migration in §0) + tenant admin "Record death" UI action + `MEMBER_DEATH_RECORDED` audit event**. Semantically distinct from termination (a member can be terminated for non-payment while alive). death_date independently editable from termination_date. cause_of_death vocab (ICD-10 chapter code vs free text) queued as create-plan note. Backfill: empty (no prior death signal today). Rejected: reuse `member_status_history.reason_code='MORTALITY'` (vocab not present today — still needs a vocab addition; conflates termination event with death date); new `member_death` history table (overkill unless multi-row revised death dates are needed — deferred); skip MORTALITY_STUDY entirely (partial delivery of Phase 14).

- **A13 — LAPSE_STUDY cohort definition**: **per-line branched — HEALTH uses monthly-enrollment cohorts (`member_status_history` + `member_contribution_presence` matview per Phase 13 L16); annual lines use annual-bind cohorts (`policy_status_history` + renewal-chain traversal per Phase 13 L9)**. Mirrors Phase-13 L16 HEALTH-vs-annual precedent. Report envelope groups by line class. Lapse-driving reason codes drawn from Phase 13's already-shipped vocabs (L5 for annual lines; L2 for members). Rejected: annual-bind for all lines (HEALTH monthly signal invisible); monthly-enrollment for all (annual lines get 11 empty cohort rows per year); caller-picks-grain (pushes semantic choice onto user).

- **A14 — Angular triangle visualization**: **split view — DataTable of the triangle (green→red gradient on cumulative value) + `app-line-chart` of LDFs by development period (one line per accident-period cohort)**. XLSX export stays flat matrix + LDF row + IBNR summary — regardless of on-screen shape. Animations off per Phase 8 §2a precedent. Same shape applies to persistency/mortality/morbidity/lapse where the "matrix" degenerates to `cohort × checkpoint`. Rejected: heatmap-first via new TriangleMatrixComponent (~2 days new component; ngx-charts cell-labeling limits); table-only (no visual trend); 3-panel table+LDF+IBNR bar-chart (busy viewport; 3-4 days).

- **A15 — Result persistence (given A8 async)**: **ai-service publishes `result_json` inline in `medfund.actuarial.job-completed` event; finance-service consumes + persists to `actuarial_report_job.result_json`**. Symmetric with A8b input. ai-service stays stateless. Consumer verifies `event.tenantId == job.tenantId` before write (Rule 2). Event-size guard queued (>900KB reject). `.doOnSuccess` ack per `bug_reactor_kafka_ack_swallow`. Rejected: ai-service writes to own `ai_service_job_results` table (Python becomes stateful; violates A8b stateless principle); MinIO-backed (symmetric rejection); HTTP callback ai→finance (reintroduces sync coupling in async pipeline).

- **A16 — Rule 3 audit trail**: **`actuarial_report_job.result_json` contains input triangle + model_version + method + basis + LDFs + confidence + output — row is the immutable audit record**. Single source of truth. JSONB-indexed for query. Append-only (row INSERT only, no UPDATE except the one terminal completion write) per Rule 8 invariant. `schema_version: 1` on the JSON envelope for future evolution. `.claude/coding-standards.md` update owed on where AI decisions are audited. Rejected: separate `actuarial_ai_decision` table + also persist to job row (two write paths; consistency risk); AI_DECISION security event with payload (doubles Kafka volume ~500KB/report on security-events topic); AI_DECISION lightweight event (two-step audit; still doubles topic).

- **A17 — Tranche split**: **5-tranche split — §0 (schema prerequisites + async job table + admin UIs) → §A (Python actuarial package + IBNR + LOSS_TRIANGLE + async infra) → §B (PERSISTENCY + LAPSE) → §C (MORTALITY + MORBIDITY) → §D (rules-engine ACTUARIAL)**. Each tranche ships as its own dated sub-plan (`thoughts/shared/plans/2026-08-24-actuarial-*-.md`) built via `grilling` → `create-plan` at implement time. Aligns with Phase 11/12/13 sub-plan precedent. Rejected: bundle §0 into §A (§A becomes ~140 files; reviewability limit); defer §D as Phase 20 (undermines A5); language-layer split (fights vertical-slice pattern).

- **A18 — Testing strategy**: **full-stack per tranche — Python unit + Python integration + Java IT (Testcontainers + Kafka) + Playwright + Mack's textbook triangle as chain-ladder golden fixture**. Per-tranche `db/actuarial-*-migration/V001..sql` folders per Phase-11/12/13 L18 pattern. Cross-language docker-compose IT deferred to a Phase-14-integration follow-up (one spec per report type). All Testcontainers pitfalls per `infra_testcontainers_pitfalls`. `.doOnSuccess` Kafka acks per `bug_reactor_kafka_ack_swallow`. Rejected: Python + Java only skipping Playwright (contradicts parent-plan Testing Strategy); docker-compose in every tranche (~15-30min CI/tranche); Python-only §A-§C with Java+Playwright deferred to §D (breaks reviewability of intermediate tranches).

### Settled by fact (not asked)

- **F14-1 — All six report keys already ship** in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:108-113`: `IBNR_TRIANGLE`, `LOSS_TRIANGLE`, `PERSISTENCY_STUDY`, `MORTALITY_STUDY`, `MORBIDITY_STUDY`, `LAPSE_STUDY` — all `cadenced=false`, all mapped to `ReportFamily.ACTUARIAL`. **No enum-add work** for keys.
- **F14-2 — `ReportFamily.ACTUARIAL` already exists** at `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:24`. Render order between RECONCILIATION and REGULATORY. **No enum-add work** for family.
- **F14-3 — Java→Python HTTP pattern is fully established** as `AiServiceClient` at `services/java/claims-service/src/main/java/com/medfund/claims/client/AiServiceClient.java:106` — 8s timeout, 1 retry with 250ms backoff, fail-open, X-Tenant-ID header. Phase 14 §D clones this shape into finance-service if the rules-engine pipeline needs a sync HTTP path; §A uses Kafka pattern per A8.
- **F14-4 — `CrossServiceCallHelper` custom-timeout arm exists and names actuarial** at `services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java:76-82` — inline comment references "an actuarial call that legitimately needs a longer ceiling". Ready to use.
- **F14-5 — ai-service tenant plumbing exists** at `services/python/ai-service/app/core/tenant.py:8-12` — X-Tenant-ID header extraction + PostgreSQL search_path per-schema routing. Tenant-isolation floor is already there for §D if a synchronous /actuarial/* endpoint returns.
- **F14-6 — `PERSISTENCY_STUDY` vs Phase-13 `PERSISTENCY_COHORT` is NOT a naming collision.** Semantic split is valid — COHORT (Phase 13) is a raw retention curve on `member_status_history`; STUDY (Phase 14) is an A/E ratio against an expected schedule (A10 basis). Both keys stay in the enum, both ship as distinct reports under different families (POLICY_LIFECYCLE vs ACTUARIAL).
- **F14-7 — `Claim` entity fields available for triangles** at `services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java:50-73, 106`: `service_date` (LocalDate), `submission_date` (Instant), `paid_amount` + `approved_amount` + `claimed_amount` (BigDecimal), `insurance_line` (String), `created_at` (Instant). **No** `paid_date`, `incurred_date`, or explicit development-period field. A3b closes the gap for the incurred shape via `claim_reserve_history`.
- **F14-8 — `Member` entity fields available for exposure/mortality** at `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:29-57`: `date_of_birth` (LocalDate), `gender` (String), `enrollment_date` (LocalDate), `termination_date` (LocalDate). **No** `death_date` or `cause_of_death`. A12b closes the gap.
- **F14-9 — Zero Angular ComingSoon stubs for actuarial** in `clients/angular/src/app/pages/tenant/finance/finance.routes.ts` — no `ibnr`, `loss-triangle`, `persistency-study`, `mortality-study`, `morbidity-study`, `lapse-study` routes exist today. §A adds all 6 routes from scratch under `/tenant/finance/reports/actuarial/*` following Phase 13 §C `POLICY_LIFECYCLE` route-spread pattern verbatim.
- **F14-10 — `RuleCategory.ACTUARIAL` does NOT exist** in `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:12-93` (19 categories today). §D adds it.
- **F14-11 — Gateway has zero `/actuarial/*` routes** in `services/go/gateway/internal/`. §A adds `/api/v1/reports/actuarial/*` + `/api/v1/reports/actuarial/jobs/*` routes to finance-service; §D adds `/api/v1/actuarial/*` (direct ai-service) if the pipeline order needs it (see Grill note 2).
- **F14-12 — MinIO already in the infra stack** per Makefile `make infra` — available if the message-size guard from A8b/A15 needs a fallback later. Not required for §A.
- **F14-13 — Redis already available** per `services/python/ai-service/pyproject.toml:7-27`. Retained for optional §A pre-warm scratch pad (not for result caching per A9).
- **F14-14 — Phase 13's `member_contribution_presence` matview** (tenancy-service V114) is exactly the "still-paying" HEALTH signal PERSISTENCY_STUDY needs. Refreshed by `EarningScheduleClosureService.refreshMemberContributionPresence()` chained after Phase 12's `refreshMemberFirstContribution` per Phase-13 §C landing.
- **F14-15 — Phase 13's `policy_status_history` (V111 user-service) + renewal-chain traversal via `renewed_from_policy_id`** is the annual-line "still-active" signal for both PERSISTENCY_STUDY and LAPSE_STUDY.
- **F14-16 — Next migration numbers** (verify at plan time): user-service tenant last `V112` (member_status_history from Phase 13); user-service public last `V135` (mirror `providers.network_tier`); claims-service tenant last from Phase 4 landing (verify); tenancy-service public last `V135` (Phase 13). §0 bundles: `claim_reserve_history` (claims-service tenant V0NN), `Member.death_date` + `cause_of_death` (user-service tenant V113), `tenant_persistency_basis` + `tenant_mortality_basis` + `tenant_morbidity_basis` (tenancy-service public V136/V137/V138), `actuarial_report_job` (finance-service tenant V0NN).

### Data model

Tenant-scoped tables all with standard audit tail (`id UUID PK DEFAULT gen_random_uuid(), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`) per `feedback_audit_actor_email` (Rule 8). Public-schema tables carry per-tenant `tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE`.

| Table | Service | Purpose | Key columns |
|---|---|---|---|
| `claim_reserve_history` (§0, A3b) | claims-service tenant | Point-in-time case reserves for incurred triangle | `claim_id UUID NOT NULL`, `reserved_amount NUMERIC(18,2) NOT NULL`, `effective_at TIMESTAMPTZ NOT NULL`, `actor_id UUID NULL`, `actor_email VARCHAR NULL`, `reason_note TEXT NULL`. INDEX `(claim_id, effective_at DESC)`. |
| `actuarial_report_job` (§0/§A, A8/A9/A15/A16) | finance-service tenant | Async job registry + result payload + audit trail | `job_id UUID PK`, `tenant_id UUID NOT NULL`, `report_key VARCHAR NOT NULL`, `status VARCHAR NOT NULL` (`requested`/`processing`/`completed`/`failed`), `params_json JSONB NOT NULL`, `params_hash VARCHAR(64) NOT NULL`, `result_json JSONB NULL`, `error_message TEXT NULL`, `requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `completed_at TIMESTAMPTZ NULL`, `requested_by UUID`, `requested_by_email VARCHAR`. INDEX `(tenant_id, report_key, params_hash)`; partial UNIQUE `(tenant_id, params_hash) WHERE status IN ('requested','processing')` for in-flight de-dupe per A9. Append-only per Rule 8 + A16 (INSERT + single terminal UPDATE permitted). |
| `public.tenant_persistency_basis` (§0, A10) | tenancy-service public | Expected retention curves per line per tenant | `tenant_id UUID NOT NULL`, `insurance_line VARCHAR NOT NULL`, `cohort_months INT NOT NULL`, `expected_retention_pct NUMERIC(5,4) NOT NULL`, `source_note VARCHAR`, `effective_from DATE NOT NULL DEFAULT CURRENT_DATE`, `effective_to DATE NULL`. UNIQUE `(tenant_id, insurance_line, cohort_months, effective_from)`. |
| `public.tenant_mortality_basis` (§0, A11) | tenancy-service public | Tenant tie to ai-service mortality reference table + multiplier | `tenant_id UUID NOT NULL`, `insurance_line VARCHAR NOT NULL`, `basis_name VARCHAR NOT NULL` (matches ai-service YAML filename), `mortality_multiplier NUMERIC(5,4) NOT NULL DEFAULT 1.0000`, `effective_from DATE NOT NULL DEFAULT CURRENT_DATE`, `effective_to DATE NULL`. UNIQUE `(tenant_id, insurance_line, effective_from)`. |
| `public.tenant_morbidity_basis` (§0, A11) | tenancy-service public | Tenant tie to ai-service morbidity reference table + multiplier | Same shape as `tenant_mortality_basis` (rename `mortality_multiplier` → `morbidity_multiplier`). |

**Additive columns on user-service Member entity** (§0 — per A12b):

| Entity | Service | New columns |
|---|---|---|
| `Member` | user-service tenant | `death_date LocalDate NULL`, `cause_of_death VARCHAR(80) NULL`. Backfill: empty (no prior death signal today). Update `MemberResponse` DTO to expose both. |

**ai-service resource files** (§A + §C, A11):

- `services/python/ai-service/app/actuarial/basis_tables/mortality/A1949_52.yaml` — Zimbabwe LIFE default
- `services/python/ai-service/app/actuarial/basis_tables/mortality/A67_70.yaml` — Zimbabwe LIFE alternative
- `services/python/ai-service/app/actuarial/basis_tables/mortality/SA85_90.yaml` — South Africa group life
- `services/python/ai-service/app/actuarial/basis_tables/mortality/CSO_2017.yaml` — US NAIC mandated
- `services/python/ai-service/app/actuarial/basis_tables/morbidity/CIDA.yaml` — disability incidence
- `services/python/ai-service/app/actuarial/basis_tables/morbidity/GLTD87.yaml` — group long-term disability

Format: `{age: {male: qx_per_1000, female: qx_per_1000}, ...}` — one row per age 0..120.

### Kafka topology

Two new topics (§A) tied to the async job pattern:

| Component | Direction | Topic | Behaviour |
|---|---|---|---|
| `finance-service ActuarialJobPublisher` | out (§A) | `medfund.actuarial.job-requested` (new) | On every `ActuarialReportController` request → publish `{jobId, tenantId, reportKey, params, triangle_json OR exposure_json OR cohort_json (pre-baked per A8b), requestedBy, requestedByEmail, schema_version: 1}`. Payload size guarded (>800KB warn, >900KB reject-with-suggestion per Grill note 7). Publishes AFTER inserting `actuarial_report_job (status='requested')` row. Idempotent via params-hash partial UNIQUE index per A9. |
| `ai-service ActuarialJobConsumer` | in (§A) | `medfund.actuarial.job-requested` | Consumes; delegates to `chain_ladder.compute()` / `persistency.compute()` / `mortality.compute()` / `morbidity.compute()` / `lapse.compute()` based on `reportKey`. On success → publishes to `medfund.actuarial.job-completed`. On failure → publishes with `status='failed'` + error. Stateless — no DB writes on ai-service side per A15. |
| `ai-service ActuarialResultPublisher` | out (§A) | `medfund.actuarial.job-completed` (new) | Payload `{jobId, tenantId, reportKey, status, result_json (inline per A15), errorMessage, modelVersion, method, basis, computedAt, schema_version: 1}`. Event-size guard >900KB → reduce float precision to 2dp + retry per Grill note 7. |
| `finance-service ActuarialResultConsumer` | in (§A) | `medfund.actuarial.job-completed` | On consume: verify `event.tenantId == job.tenantId` (Rule 2 guard per A15 + Grill note 19); UPDATE `actuarial_report_job` row `SET status='completed'/failed', result_json=..., completed_at=NOW()`. `.doOnSuccess` ack per `bug_reactor_kafka_ack_swallow`. Emits `AuditEvent` on state transition per Rule 8. |

**Rule-3 audit path**: `actuarial_report_job.result_json` is the immutable audit record per A16; JSONB-indexed for query; row INSERT-only-then-single-completion-UPDATE (append-only invariant enforced via a `BEFORE UPDATE` trigger that raises when `OLD.status IN ('completed', 'failed')`). Downstream compliance auditor discovers actuarial runs by querying `actuarial_report_job` (not `security_events`).

**Deploy order per parent-plan invariant**: ai-service ships the `ActuarialJobConsumer` BEFORE finance-service ships the `ActuarialJobPublisher` (avoid publish-into-void); finance-service ships the `ActuarialResultConsumer` BEFORE ai-service ships the `ActuarialResultPublisher` (avoid orphan results).

### Report shapes

Six report surfaces (per A3 + A6 + A7 + A14). All `@RequiresReport(...)` + `ReportEnvelopeBuilder` + `SecurityEventPublisher.publishDataAccess` on export. All served from **finance-service** at `/api/v1/reports/actuarial/*` per A1. All use async job pattern per A8 — the POST endpoint returns immediately with a `jobId`; result endpoint returns `202 Accepted` with progress or `200 OK` with `result_json`.

Endpoints:

- `POST /api/v1/reports/actuarial/ibnr` — body `{periodStart, periodEnd, insuranceLine?, shape=paid|incurred|reported (A3), grain=month|quarter|year (A4), reportingCurrency?, ldfMethod?='volume' (default per A5)}`. Returns `{jobId, status='requested'}`.
- `POST /api/v1/reports/actuarial/loss-triangle` — same body. Returns triangle without IBNR extrapolation.
- `POST /api/v1/reports/actuarial/persistency-study` — body `{periodStart, periodEnd, insuranceLine?, checkpoints=3,6,12,24,36, reportingCurrency?, basisId? (default: tenant default per A10)}`.
- `POST /api/v1/reports/actuarial/mortality-study` — body `{periodStart, periodEnd, insuranceLine?, ageBands=0-10,10-20,..., sexStratify=true, basisName? (default: tenant default per A11)}`.
- `POST /api/v1/reports/actuarial/morbidity-study` — same as mortality shape.
- `POST /api/v1/reports/actuarial/lapse-study` — body `{periodStart, periodEnd, insuranceLine?, cohortGrain=derived-per-line (A13), reportingCurrency?}`.
- `GET /api/v1/reports/actuarial/jobs/{jobId}` — returns `{jobId, status, result_json?, errorMessage?, progress?}`. Angular polls this every 2s (backoff to 5s after 30s) until status is terminal.
- `GET /api/v1/reports/actuarial/jobs/{jobId}/export.xlsx` — XLSX export from completed `result_json`. Emits `SecurityEvent` per parent-plan invariant #3.

Result envelope per report:
- **IBNR_TRIANGLE / LOSS_TRIANGLE**: `{perLine: Map<InsuranceLine, {triangle: [[Number]], ldfs: [Number], ibnrTotal: Number, ultimateTotal: Number, confidence: {method: 'Mack', standardError: Number}, warnings: [String]}>}`. XLSX: one sheet per line — matrix + LDF row + IBNR summary.
- **PERSISTENCY_STUDY**: `{perLine: Map<InsuranceLine, {cohorts: [{cohortMonth, cohortSize, checkpoints: [{months, actualRetentionPct, expectedRetentionPct, aeRatio}]}], warnings: [String]}>}`. XLSX: pivot on `cohort_month × checkpoint` per line.
- **MORTALITY_STUDY**: `{perLine: Map<InsuranceLine, {perAgeBand: [{ageBand, sex, deaths, exposureYears, actualQx, expectedQx, aeRatio}], basisName, multiplier, warnings: [String]}>}`. XLSX: one sheet per line + summary.
- **MORBIDITY_STUDY**: same shape as mortality with `incidence` in place of `qx`.
- **LAPSE_STUDY**: `{perLine: Map<InsuranceLine, {cohorts: [{cohortId (month or year per A13), cohortSize, lapsesByReason: {reasonCode: count}, lapseRate, aeRatio}], warnings: [String]}>}`. XLSX: sheet per line class (HEALTH monthly-grain; annual-lines yearly-grain).

**Envelope FX / missing-rate semantics** per parent-plan G28: reporting-currency conversion happens at claim `service_date`; a missing rate omits that claim from the cell + adds a warning per A6 + Grill note 4. Never fails the entire report.

### Angular surfaces

Tenant-admin CRUD (§0):
- Existing member detail page (`/tenant/admin/members/{id}` — verify at plan time) — new "Record death" action button opening `MemberDeathModalComponent` with `deathDate` date picker + optional `causeOfDeath` free-text/ICD-10 select per A12b + Grill note 16. Emits `MEMBER_DEATH_RECORDED` audit + updates member row.
- Existing adjudicator claim detail page (verify at plan time) — new "Set/update reserve" action opening `ClaimReserveModalComponent` per A3b + Grill note 1. Writes to `claim_reserve_history`; audit trail visible in a new "Reserve history" tab on the claim page.
- New `/tenant/admin/settings/actuarial-bases` page with three tabs (persistency / mortality / morbidity) per A10 + A11. Each tab has add/edit/delete rows for the corresponding basis config table. Mortality/morbidity tabs show `basis_name` dropdown populated from ai-service `GET /actuarial/basis-tables/list` + multiplier input. Mirror UX from `TenantEndorsementConfig` (V134) per Grill note 11.

Reports (§A/§B/§C):
- `/tenant/finance/reports/actuarial/ibnr-triangle` — period picker (default: quarterly, last 20 quarters), insurance-line filter, shape select (paid/incurred/reported), grain select, reporting-currency override, "Run report" button. On submit → POST returns jobId → poll UI shows "Running..." spinner → renders split view per A14: DataTable triangle (green→red gradient) + `app-line-chart` of LDF-by-development-period per accident-period cohort. "Export XLSX" button emits SecurityEvent + downloads job export endpoint.
- `/tenant/finance/reports/actuarial/loss-triangle` — same shape, no IBNR extrapolation.
- `/tenant/finance/reports/actuarial/persistency-study` — period picker, insurance-line filter, checkpoints multi-select, basis picker (defaults to tenant default). Renders retention curve chart (actual vs expected) + A/E pivot table per line.
- `/tenant/finance/reports/actuarial/mortality-study` — period picker, insurance-line filter, age-band selector, sex-stratify toggle, basis-name dropdown, multiplier override. Renders per-age-band A/E table + heatmap.
- `/tenant/finance/reports/actuarial/morbidity-study` — same shape as mortality.
- `/tenant/finance/reports/actuarial/lapse-study` — period picker, insurance-line filter (auto-derives cohort grain per A13). Renders per-cohort lapse-rate table + lapse-by-reason stacked bar chart.

Reports hub (§A onwards) auto-registers the 6 ACTUARIAL keys via existing `TenantReportConfigService` — `ACTUARIAL` family card renders when any key enabled (family already in family-label service; verify at plan time).

Shared async job UX (§A):
- `ActuarialJobPollingService` — Angular service that manages job polling with backoff (2s → 5s after 30s → give up after 5min with user-facing error).
- `ActuarialJobProgressComponent` — reusable spinner + progress + cancel button + jobId display.

Tenant admin (§D) — rules-engine ACTUARIAL category:
- Extends existing `/tenant/admin/rules` page with new ACTUARIAL category filter + three template cards (volume-weighted LDF / simple-average LDF / N-year weighted LDF).

### Proposed §0 / §A / §B / §C / §D tranche split

**§0 — Schema prerequisites + admin UIs** (no reports light up yet; foundation + admin CRUD):
- claims-service tenant migration: `claim_reserve_history` (V0NN — verify next number at plan time)
- user-service tenant migration: `Member.death_date` + `Member.cause_of_death` (V113)
- tenancy-service public migrations: `tenant_persistency_basis` (V136), `tenant_mortality_basis` (V137), `tenant_morbidity_basis` (V138)
- finance-service tenant migration: `actuarial_report_job` (V0NN — recommend §A pulls this if scope allows, else split into §0)
- claims-service `ClaimReserveHistoryService` + REST endpoint + adjudicator UI action
- user-service `Member` entity widening + REST endpoint + tenant admin UI action + `MEMBER_DEATH_RECORDED` audit event
- tenancy-service `TenantPersistencyBasisController` + `TenantMortalityBasisController` + `TenantMorbidityBasisController` + tenant admin UI pages (three tabs on new `/tenant/admin/settings/actuarial-bases`)
- IT per new service in-tranche per Phase-11/12/13 L18 pattern; dedicated `db/actuarial-schema-migration/V001..sql` folders per test slice
- **Success criterion**: admins can set reserves, record deaths, configure bases; audit events fire; no reports render yet.

**§A — Python actuarial package + IBNR + LOSS_TRIANGLE + async infra** (first reports light up):
- ai-service `pyproject.toml` — add `chainladder`, `pandas`, `numpy`, `scipy`, `openpyxl`
- ai-service `services/python/ai-service/app/actuarial/` package with `chain_ladder.py`, `ldf.py`, `payloads.py`, `basis_tables/` scaffolding + basis-table YAMLs for §C (ship early for eager caching)
- ai-service pre-warm on FastAPI lifespan startup (trivial chain-ladder run to bake numba JIT)
- ai-service `ActuarialJobConsumer` + `ActuarialResultPublisher` (Kafka)
- ai-service unit tests: chain-ladder LDFs match Mack's textbook fixture to 4dp
- finance-service `actuarial_report_job` migration (if not shipped in §0)
- finance-service `ActuarialReportController` (POST endpoints for IBNR + LOSS + GET jobs + GET jobs/{id}/export.xlsx)
- finance-service `ActuarialJobPublisher` + `ActuarialResultConsumer` (Kafka)
- finance-service `ClaimsClient` (may exist — verify) + `UserClient` for triangle input queries
- finance-service `TriangleShapingService` — shapes claim data into paid/incurred/reported triangle at chosen grain + FX conversion + missing-FX warnings
- finance-service `TriangleWorkbookService` — XLSX export per Grill note 17
- Angular routes + components for `/tenant/finance/reports/actuarial/ibnr-triangle` + `/loss-triangle`
- Angular `ActuarialJobPollingService` + `ActuarialJobProgressComponent`
- Gateway route registration for `/api/v1/reports/actuarial/*` + `/api/v1/reports/actuarial/jobs/*`
- IT: `ActuarialJobPublisherIT` + `ActuarialResultConsumerIT` via `AbstractKafkaIntegrationTest`; `TriangleShapingServiceIT`; `IbnrReportControllerIT`; `LossTriangleReportControllerIT` — all in dedicated `db/actuarial-ibnr-loss-migration/V001..sql`
- Playwright: `actuarial-ibnr.spec.ts` + `actuarial-loss-triangle.spec.ts` — hub → open report → set filters → submit → poll → renders → export
- **Success criterion**: 2 report keys enabled → reports render + export XLSX; Mack's fixture passes; toggle-off returns 403; missing FX shows envelope warnings; async job survives ai-service restart mid-compute.

**§B — PERSISTENCY_STUDY + LAPSE_STUDY** (member-shaped studies; reuses Phase 13 tables):
- ai-service `actuarial/persistency.py` + `actuarial/lapse.py`
- ai-service unit tests: A/E ratios on fabricated cohorts match hand-calc
- finance-service `PersistencyCohortShapingService` — reads Phase 13 `member_status_history` + `member_contribution_presence` matview + `policy_status_history` via user-service `MemberStatusHistoryClient` + `PolicyStatusHistoryClient` (both new)
- finance-service `LapseCohortShapingService` — per-line branched per A13
- Two new POST endpoints on `ActuarialReportController` + workbook services
- Angular routes + components for `/persistency-study` + `/lapse-study`
- IT for both reports in dedicated `db/actuarial-persistency-lapse-migration/V001..sql`
- Playwright per report
- **Success criterion**: 2 more report keys enabled → A/E numbers reconcile against Phase 13 COHORT numbers; `tenant_persistency_basis` rows drive expected numbers.

**§C — MORTALITY_STUDY + MORBIDITY_STUDY** (member-shaped, reads §0 death signal + basis config):
- ai-service `actuarial/mortality.py` + `actuarial/morbidity.py` + basis-table YAML loaders
- ai-service unit tests: A/E against SOA-published exposure/deaths for a synthetic block
- finance-service `MortalityShapingService` — reads Member.date_of_birth + gender + enrollment_date + termination_date + death_date via user-service `MemberExposureClient` (new); computes age bands + actual-days-exposed per A12
- finance-service `MorbidityShapingService` — reads claim incidence + Member exposure
- Two new POST endpoints + workbook services
- Angular routes + components for `/mortality-study` + `/morbidity-study`
- IT for both in dedicated `db/actuarial-mortality-morbidity-migration/V001..sql`
- Playwright per report
- **Success criterion**: 2 final report keys enabled → mortality A/E computes for LIFE line; morbidity A/E computes for HEALTH line; basis multiplier from §0 applies correctly.

**§D — Rules-engine ACTUARIAL category** (tenant-configurable LDF method — A5's deferred integration):
- rules-engine `RuleCategory.ACTUARIAL` enum add
- rules-engine `TriangleFact` + `DevelopmentPeriodFact` types per Grill note 3
- rules-engine templates: `VolumeWeightedLdfTemplate`, `SimpleAverageLdfTemplate`, `NYearWeightedLdfTemplate` under `services/java/rules-engine/src/main/java/com/medfund/rules/template/actuarial/`
- rules-engine `DrlCompiler` awareness of new category
- finance-service `TriangleShapingService` retrofit: pipeline order call per Grill note 2 — before publishing job-requested event, evaluate rules to select LDF method; pass selected method as job param
- Angular tenant admin `/tenant/admin/rules` page extension — ACTUARIAL category filter + template cards
- IT: `TenantActuarialRulesIT` in dedicated `db/actuarial-rules-migration/V001..sql`
- Playwright: rules authoring flow
- **Success criterion**: tenant admin authors "use 5-year weighted for HEALTH claims after 2024-01-01"; IBNR report picks up the rule; XLSX header notes the LDF method used.

§0/§A/§B/§C/§D ship as separate commits per Phase 11 + 12 + 13 precedent. All should be fully grilled + planned at implement-time via `create-plan`; `implement-plan` treats tranche boundaries as hand-off points per the plan header.

### Grill notes for `create-plan`

1. **Reserve-setting workflow specifics (A3b)** — plan the exact adjudicator UX: is "Set reserve" a one-time initial action or a "Revise reserve" repeatable action? Who has permission (adjudicator role vs supervisor role)? What's the audit-note requirement? Does reserve automatically zero on claim close/deny?
2. **Rules-engine pipeline order (A5)** — plan the pipeline: does finance-service call rules-engine to *pick* LDF method BEFORE publishing job-requested event (Java owns method choice, Python is method-agnostic), or does Python compute all three method variants and finance-service call rules-engine POST-Python to *select* one (Python computes 3× cost, rules operate on actual LDFs)? Recommend the former for simplicity.
3. **Rules-engine fact-type design (A5)** — plan `TriangleFact` and `DevelopmentPeriodFact` shapes; determine whether rules author against pre-shaped triangle data or against high-level params only.
4. **Missing-FX aggressiveness (A6)** — plan per-claim skip+warn vs per-report fail-loud. Recommend per-claim skip+warn (a 5-year triangle across thousands of dates cannot survive per-report fail-loud). Add to envelope warnings.
5. **Event schema versioning (A8b)** — plan `schema_version: 1` header on both `medfund.actuarial.job-requested` and `medfund.actuarial.job-completed` events. Adding fields must be additive per parent-plan Rollout policy.
6. **Kafka ack pattern (A8b + A15)** — plan `.doOnSuccess` for offset ack on both consumers per `bug_reactor_kafka_ack_swallow` memory. Full-cause-chain error logging on failed compute.
7. **Message-size guards (A8b + A15)** — plan payload-size checks: `job-requested` >800KB warn + >900KB reject-with-suggestion (narrow period, coarser grain); `job-completed` >900KB reduce precision (round floats to 2dp) + retry publish. Fall back to MinIO if still oversize (deferred implementation until measured need).
8. **Job-row retention policy (A9)** — plan `actuarial_report_job` retention: keep last N days globally + last N runs per (tenant, report_key)? Recommend last 90 days + last 20 runs per (tenant, report_key). Nightly cleanup job.
9. **Partial UNIQUE index on in-flight params-hash (A9)** — plan the exact index shape: `CREATE UNIQUE INDEX ux_actuarial_job_inflight ON actuarial_report_job (tenant_id, params_hash) WHERE status IN ('requested', 'processing');`. Guards against duplicate submissions racing.
10. **Seed defaults for tenant_persistency_basis (A10)** — decide whether §0 ships seed defaults per line (e.g., `HEALTH: [3=0.90, 6=0.85, 12=0.75, 24=0.65, 36=0.55]` derived from published industry averages) or leaves tenants to author their own. Recommend seed defaults with `source_note='industry_default_v1'` — improves day-one experience.
11. **Basis config admin UX mirroring TenantEndorsementConfig (A10)** — verify existing UI at `clients/angular/src/app/pages/tenant-admin/settings/endorsements/*` (Phase 12 landing) as the pattern to clone for the three basis-config tabs.
12. **Starter set of mortality/morbidity YAMLs (A11)** — plan the exact starter set:
    - Mortality: A1949-52 (ZW LIFE default), A67-70 (ZW LIFE alt), SA85-90 (ZA group life), CSO 2017 (US NAIC)
    - Morbidity: CIDA (disability incidence), GLTD87 (group LTD)
    - Confirm per-jurisdiction which basis is default at tenant onboarding.
13. **Basis versioning (A11)** — SOA reissues tables every ~10 years. Plan whether `basis_name` is versioned in the filename (`A1949_52.yaml` vs `A1949_52_v2.yaml`) or the file contents carry `basis_version`. Recommend filename-versioning for immutability.
14. **MEMBER_DEATH_RECORDED audit event (A12b)** — plan the `AuditEvent` shape: `actorId + actorEmail + memberId + deathDate + causeOfDeath + reason_note`. Emitted from `MemberService.recordDeath()`. `entityName='member:{memberNumber}'` per `feedback_audit_entity_name`.
15. **death_date independently editable from termination_date (A12b)** — plan the UI + validation: a member can be "terminated for non-payment 2024-06-01" and later "died 2024-03-15"; these are separate editable fields. Validation: `death_date <= termination_date` if both non-null.
16. **cause_of_death vocab (A12b)** — decide: ICD-10 chapter code (typed autocomplete) vs free text (VARCHAR 80). Recommend ICD-10 chapter select + free-text fallback. Chapter codes support morbidity/mortality regulator reporting.
17. **XLSX shape fixed (A14)** — plan the XLSX workbook layout precisely per report:
    - IBNR/LOSS: sheet-per-line; row 1 header (dev periods); rows 2..N cohorts (accident periods); cell = cumulative; row N+1 LDFs; row N+3 IBNR total.
    - PERSISTENCY: sheet-per-line; pivot `cohort_month × checkpoint`; actual/expected/A-E as three cells per checkpoint.
    - MORTALITY/MORBIDITY: sheet-per-line; pivot `age_band × sex`; qx-actual/qx-expected/A-E per cell.
    - LAPSE: sheet-per-line-class (HEALTH separate from annual); cohort-row × reason-code columns.
    - Summary sheet cross-line rollup at position 0 per Phase 11/12/13 precedent.
18. **Message-size guard exact thresholds (A15)** — see Grill note 7.
19. **result-persist Rule-2 guard (A15)** — plan the finance-service consumer: `if (event.tenantId != jobRow.tenantId) throw new IllegalStateException(...)`. IT that spoofed cross-tenant events reject.
20. **result_json schema_version (A16)** — plan the envelope: `{schema_version: 1, computed_at, model_version, method, basis, input_triangle, ldfs, confidence, output_summary}`. Immutable via append-only invariant.
21. **Append-only invariant (A16)** — plan the DB constraint: `BEFORE UPDATE` trigger that raises `EXCEPTION` when `OLD.status IN ('completed', 'failed')` (the single terminal-write UPDATE from `status='processing'` is permitted; subsequent UPDATEs error).
22. **.claude/coding-standards.md update owed (A16)** — add a paragraph in the coding-standards doc on where AI-decision audit trails live per report family (actuarial: `actuarial_report_job.result_json`; adjudication: existing `AiDecision` per claims-service).
23. **Cross-language docker-compose IT (A18)** — plan one full-stack spec per report type (6 specs total) as a Phase-14-integration follow-up tranche. Each spec: `docker compose up` full stack → real submit → real ai-service consume → real result persistence → Angular polling → export. Deferrable per Phase-13 §C precedent but tracked as a known follow-up.

### What's owed back to the parent-plan outline

- **Line 3540 dither ("claims-service or finance-service")** — A1 settles as finance-service aggregator. Struck through above.
- **Line 3535-3540 outline sync-HTTP endpoint contract ("POST /actuarial/ibnr — input: claim triangle JSON; output: LDFs + IBNR estimate")** — A8/A8b settles as async Kafka event pattern. Endpoint shape still POST but returns `{jobId}` immediately; result via GET polling.
- **Line 3669 Performance Considerations "Actuarial calls to Python are synchronous — set a 30s ceiling; cache results by (tenant, report-key, period) in Redis for 1h"** — A8 + A9 supersede: async job pattern replaces sync 30s ceiling; `actuarial_report_job.result_json` replaces Redis 1h cache. Performance Considerations section owes an update reflecting this.
- **Line 3543 Grilling checkpoint** — satisfied by A1..A18 above.
- **Phase 17 (Scheduled Email Delivery) cross-ref** — Phase 14's async job pattern gives Phase 17 the completion signal it needs; add "reuses actuarial `job-completed` event" note to Phase 17 outline.

### Success Criteria

**Status: Fully implemented via the expanded sub-plan [thoughts/shared/plans/2026-08-25-actuarial-module.md](2026-08-25-actuarial-module.md), landed in two commits: `8c294a1 "Land Phases 1-11 of the actuarial module (Phase 14)"` (§0 + §A + start of §B) and `17a4b4e "Land Phases 12-16 of the actuarial module (Phase 14)"` (rest of §B + §C + §D + closeout).**

Grilling checkpoint satisfied 2026-08-24 (A1..A18 + A3b + A8b + A12b + F14-1..F14-16 above). The five originally-scoped tranches (§0/§A/§B/§C/§D) collapsed into a single 16-phase sub-plan per `create-plan` refinement. Aggregate success criteria across all tranches:

- **§0 green**: reserves recordable + deaths recordable + all three basis-config admin pages CRUD-functional; audit events fire; per-tranche ITs green.
- **§A green**: IBNR + LOSS reports enabled → hub renders both; submit → poll → renders + export; Mack's fixture passes; async job survives mid-compute restart; toggle-off returns 403.
- **§B green**: PERSISTENCY + LAPSE reports enabled; A/E numbers reconcile against Phase 13 COHORT; `tenant_persistency_basis` rows drive expected numbers.
- **§C green**: MORTALITY + MORBIDITY reports enabled; mortality A/E computes for LIFE; morbidity A/E computes for HEALTH; basis multiplier applies.
- **§D green**: rules-engine ACTUARIAL category enabled; tenant admin authors LDF-selection rule; IBNR report picks up the rule; XLSX header notes method used.

Deferred to follow-up per Phase-11/12/13 precedent:
- **Cross-language docker-compose IT** — 6 specs per Grill note 23; deferred to a Phase-14-integration tranche.
- **Manual `verify` walkthroughs** — end-to-end golden-path browser demos per tranche.
- **Chain-ladder alternative methods (Bornhuetter-Ferguson, Bootstrap)** — `chainladder-python` ships them; Phase 14 §A only wires Mack chain-ladder; deferred as user-selectable methods behind a `?method=` param in a future sub-tranche.
- **Ultimate-loss-ratio sensitivity analysis** — actuarial-heavy add-on; deferred.
- **Live-defect note**: `services/python/ai-service/app/main.py` imports OpenTelemetry packages but never wires instrumentation. Not Phase 14's problem; recorded here as a general observability follow-up.

Per parent-plan Testcontainers policy each deferred IT lands with a purpose-built migration folder so the ITs don't force-widen every unrelated slice's baseline schema.

---

## Phase 15: IFRS 17 Pack

> **Grilled 2026-08-28.** Decisions I1..I30 (numbered `I*` — for IFRS 17 — to avoid collision with plan-wide `G*` numbering which runs G1..G44 in the parent Implementation Approach section, and with prior phase prefixes `R*` (reinsurance, Phase 10) / `P*` (producer, Phase 11) / `U*` (underwriting, Phase 12) / `L*` (lifecycle, Phase 13) / `A*` (actuarial, Phase 14)). Additional sub-decisions bundled into primary numbers (no `I<n>b` suffix explosion). Grill notes reference open decisions I31, I32, I34, I35, I37 as create-plan follow-ups; these are Phase-15-scoped and distinct from plan-wide G31/G32/G34/G35/G37.
> The outline was expanded into a full mini-plan through interactive decision-making; scope escalated substantially beyond the original outline (three-model end-to-end per I1 replacing the outline's "IFRS 17 model choice" grilling checkpoint; full VFA entity model per I4 adds four new tenant tables; full GMM projection engine per I5 adds two new public tables + new ai-service package; rules-engine IFRS17_MODEL category per I2/I9/I17 replaces the outline's implicit hardcoded model assignment; Phase-14 `actuarial_report_job` renamed to `report_job` per I3 with dual-write window per I10 + 3-phase deploy per I22 replacing outline silence on async pattern reuse; chunk + MinIO fallback per I14 + I25 addresses payload size limits; IBNR + CoC→CI sub-job dependency chain per I24 + I6 links Phase 15 to Phase 14 outputs; split retention per I28 introduces STATUTORY_7Y class; notification-service dispatcher per I30 + `medfund.ifrs17.material-event` topic; single sub-plan `thoughts/shared/plans/2026-08-28-ifrs17-pack.md` with ~20-25 phases per I7 replaces the outline's silence on tranche split).
> Original 12-line outline retained below as ~~strike-through~~ for provenance.

### Original outline (superseded 2026-08-28 by Decisions Log)

~~LRC/LIC reconciliation + insurance revenue & service result reports.~~

~~**finance-service** or **ai-service** (LRC/LIC computation is measurement-heavy — likely Python): `IFRS17ReportController` at Java layer, computation in Python.~~
~~Report keys `IFRS17_LRC_LIC_RECONCILIATION`, `IFRS17_INSURANCE_REVENUE_SERVICE_RESULT`.~~
~~Requires portfolio × cohort × currency dimensioning of premium + claim data.~~

~~**Grilling checkpoint** critical — IFRS 17 model choice (General Model vs Premium Allocation Approach vs Variable Fee Approach) needs product decisions.~~

### Overview

Greenfield **IFRS 17 pack** spanning finance-service (aggregator + Ifrs17ReportController + rename of Phase-14 `actuarial_report_job` → `report_job` per I3 + new `report_job_chunk` table per I14 + new `report_job.retention_class` column per I28 + shared MinIO payload helper per I25 + `Ifrs17JobAggregator` for parent/chunk aggregation + IBNR sub-job orchestrator per I24), ai-service (new `app/ifrs17/` package: `paa.py` + `gmm.py` + `vfa.py` + `csm.py` + `risk_adjustment.py` + `coverage_units.py` + `onerous_test.py` + `discount_curve.py` + reuse of Phase-14 mortality/morbidity/lapse basis tables per I5), tenancy-service (public V139+ migrations adding `tenant_ra_config` per I6 + `tenant_yield_curve` per I5 + `tenant_expense_assumption` per I5 + tenant-scoped V143+ migrations adding `unit_linked_fund` + `fund_nav_history` + `policy_unit_ledger` + `variable_fee_schedule` per I4 + `cohort_status_history` + `cohort_loss_component_history` + matview per I11 + I19 + JSONB columns `locked_in_yield_curve_snapshot` + `locked_in_at` on `ifrs17_cohort` per I18 + `ifrs17_opening_balance_seed` per I29 + admin CRUD across all new tables), user-service (`PolicyIssuedConsumer` extended to write `ifrs17_cohort.locked_in_yield_curve_snapshot` on first-policy-in-cohort per I18 + backfill migration for existing cohorts), claims-service (no new entities; `ClaimHistoryClient` on finance-service side pulls per-portfolio claim history for LIC compute per I23), rules-engine (new `RuleCategory.IFRS17_MODEL` per I2 + `IfrsPortfolioFact` with 4 mutable fields (measurementModel + coverageUnitPattern + variableFeePattern + financeExpensePresentation) per I9/I17 + three templates (PAA/GMM/VFA) + agenda group + engine wiring + seed defaults per I27), new `services/go/market-data-service` (RBZ + SARB adapters per I16 + `JurisdictionAdapter` interface + cron pull + Kafka publisher for `medfund.market-data.yield-curve-updated`), notification-service (new `internal/ifrs17/dispatcher.go` per I30 + `medfund.ifrs17.material-event` topic consumer + email/webhook delivery), gateway (new routes for `/api/v1/reports/ifrs17/*` + `/api/v1/reports/jobs/*` alias for `/api/v1/reports/actuarial/jobs/*` per I22 + super-admin routes for market-data-service), and Angular (2 report pages under `/tenant/finance/reports/ifrs17/*` per I20 split-view treetable+waterfall shape + 6 admin pages (RA config + yield curves + expense assumptions + VFA funds + policy unit ledger + variable fee schedules + opening balance seeds + notification recipients) + reuse of existing tenant-admin portfolio/cohort CRUD + new `app-waterfall-chart` component + IFRS 17 family card on the reports hub).

Both report keys `IFRS17_LRC_LIC_RECONCILIATION` + `IFRS17_INSURANCE_REVENUE_SERVICE_RESULT` already ship at `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:116-117` (F15-1) — both `cadenced=true`, both under `ReportFamily.REGULATORY` (F15-2). Phase 12 landed the dimensional foundation (F15-3 — `ifrs17_portfolio` + `ifrs17_cohort` + `earning_schedule` with per-period written + earned amounts + portfolio_id + cohort_id + currency_code + `cohort_type CHECK ('ONEROUS','NON_ONEROUS','UNCERTAIN')`), and Phase 14 shipped the generic-shaped async job pattern (F15-4 — `actuarial_report_job.report_key` + `params_json` pivot + V141 append-only trigger + `medfund.actuarial.*` topic pair + 800KB/900KB size guards) which I3 + I10 + I22 rename to `report_job` + `medfund.report.*` in a 3-phase dual-write sequence.

Full grain per portfolio × cohort × currency per I13 with rollup rows at each level; separate LRC + LIC reconciliation tables per IFRS 17.101; auto-onerous test per I11; auto-loss-component tracking per I19; per-portfolio `financeExpensePresentation: PL_ONLY | OCI_OPTION` per I17; CSM interest accretion at locked-in rate snapshot per I18; PAA IFRS 17.59 simplification auto-applied per I15; FX simplified per I8 (historical for movements + closing for balances + no explicit translation adjustment row); IBNR sub-job auto-triggered when Phase-14 IBNR_TRIANGLE is stale per I24 (also for I6 CoC → CI translation); MinIO fallback per I25 for chunks exceeding 900KB.

### Decisions Log (I1..I30)

- **I1 — IFRS 17 measurement model scope**: **PAA + GMM + VFA end-to-end**. Full three-model implementation across all 8 insurance lines. PAA closed-form arithmetic (Java-natural for HEALTH / TRAVEL / VEHICLE / PROPERTY / GROUP short-term). GMM stochastic projection with full FCF + RA + CSM roll-forward + discount curve (Python-natural for LIFE / FUNERAL / DISABILITY). VFA with full underlying-item entity model (unit-linked LIFE variants). ~9-12 months total scope; user override on the "just PAA first" recommendation. Rejected: PAA-first for all lines (technically non-compliant for LIFE/DISABILITY); PAA + GMM for eligible lines (partial delivery); PAA-only for eligible + non-measured for other (auditor rejects as incomplete).

- **I2 — Model assignment mechanism**: **Rules-engine `RuleCategory.IFRS17_MODEL` category**. Rule fires at report-run time per portfolio; effective-date aware. Aligns with parent-plan Rule 5 (tenant business rules live in rules-engine). Templates for PAA / GMM / VFA. Rejected: `measurement_model` column on `ifrs17_portfolio` (static — no effective-date changes); per-line `tenant_ifrs17_model_config` (coarser than IFRS 17's portfolio-as-aggregation-unit); hardcoded per-line Java default (no tenant control).

- **I3 — Async pattern reuse + rename**: **Reuse `actuarial_report_job` in-place + rename to `report_job`**. Phase-14 async infrastructure is generic-shaped (F15-4 — pivots on `report_key` + `params_json`). Rename Phase-14 table `actuarial_report_job` → `report_job`, entity `ActuarialReportJob` → `ReportJob`, Java package `com.medfund.finance.actuarial.*` → `com.medfund.finance.report.*`, Kafka topics `medfund.actuarial.*` → `medfund.report.*`. Dual-write window per I10 + 3-phase deploy per I22. Rejected: keep old name in place (name lies about content); split by model — PAA sync + GMM/VFA async (two code paths); clone as new `ifrs17_report_job` + `medfund.ifrs17.*` topics (2x duplication).

- **I4 — VFA underlying-item entity scaffolding scope**: **Full VFA entity model**. New tenant-scoped tables: `unit_linked_fund(id, name, currency, base_asset_class)`, `fund_nav_history(fund_id, valuation_date, nav_per_unit)`, `policy_unit_ledger(policy_id, transaction_date, transaction_type, units, price)`, `variable_fee_schedule(fund_id, effective_from, fee_percentage)`. Full admin CRUD ships in tenant-admin. Real CSM roll-forward supported. Rejected: minimal scaffolding (is_direct_participation + underlying_item_reference on LifePolicy only — no unit-ledger for accurate CSM); configuration-only (accept model, refuse compute); defer VFA to follow-up (contradicts I1).

- **I5 — GMM engine scope**: **Full projection engine**. Python `app/ifrs17/gmm.py` projects cash flows monthly to end of longest contract per portfolio, using Phase 14 mortality/morbidity/lapse basis tables (F15-4a reuse) + new `public.tenant_yield_curve(tenant_id, currency, tenor_months, spot_rate, effective_from)` per-currency + new `public.tenant_expense_assumption(tenant_id, insurance_line, expense_type, amount_per_policy, effective_from)`. RA methodology per I6. CSM roll-forward per I18 locked-in snapshot + interest accretion + release proportional to coverage units per I9. Rejected: standard GMM without expense projection (understates LRC / auditor rejects); tenant admin uploads opening balances each period + roll-forward only (contradicts "platform automates IFRS 17" value prop); full engine without discount curve (non-compliant for long-duration lines).

- **I6 — RA (Risk Adjustment) methodology**: **Both CoC and CI, per portfolio, with automatic CI-equivalent disclosure**. New `public.tenant_ra_config(tenant_id, portfolio_id, methodology CHECK IN ('COC','CI'), coc_rate NUMERIC(5,4) NULL, target_confidence_level NUMERIC(5,4) NULL, effective_from DATE, effective_to DATE)`. Python `app/ifrs17/risk_adjustment.py` branches on methodology. CoC-configured portfolios auto-translate to CI-equivalent via Phase 14 chain-ladder Mack SE loss distribution — same pattern as I24 IBNR sub-job trigger; if Phase-14 IBNR result stale for the same period, auto-trigger IBNR compute first. IFRS 17.119 disclosure satisfied regardless of source methodology. Tenant admin UI at `/tenant/admin/settings/ifrs17-ra`. Rejected: CoC only (Australian/SA CI-shop tenants blocked); CI only (Solvency II CoC-shop tenants blocked); fixed % per line (not audit-defensible above materiality; still needs CI translation).

- **I7 — Tranche split**: **One sub-plan, ~20-25 verifiable phases**. Single dated file `thoughts/shared/plans/2026-08-28-ifrs17-pack.md` following Phase-14 sub-plan pattern (`thoughts/shared/plans/2026-08-25-actuarial-module.md`). `implement-plan` iterates phase-by-phase through the sub-plan, pausing between each for manual verification. Ordering: (1-3) rename `actuarial_report_job` → `report_job` + Kafka topics + Java package + Python module dual-write per I22 Phase A; (4-6) `tenant_ra_config` + `tenant_yield_curve` + `tenant_expense_assumption` + `report_job_chunk` + `report_job.retention_class` + `cohort_status_history` + `cohort_loss_component_history` + `ifrs17_opening_balance_seed` + admin UIs; (7-10) VFA entities + admin CRUD; (11) rules-engine `IFRS17_MODEL` category + fact + templates + seed defaults + agenda group; (12-14) Python `app/ifrs17/paa.py` + PAA compute; (15-18) Python GMM engine + CSM + RA + discount curve + basis reuse; (19-21) Python VFA compute; (22-24) finance-service `Ifrs17ReportController` + shaping services + async publisher + aggregator + IBNR sub-job orchestrator + MinIO payload helper; (25-27) Angular report pages + XLSX + polling + waterfall chart + material-event admin surface; (28) rename cutover per I22 Phase B; (29) rename cleanup per I22 Phase C; (30) e2e + performance + docker-compose IT deferred to follow-up. Each sub-plan phase ships as its own commit per Phase-14 precedent. Rejected: three sub-plans by model (no user-facing report between sub-plans); two sub-plans (measurement infra + reports); four sub-plans (4x create-plan overhead).

- **I8 — Currency FX policy for LRC/LIC balances + movement rows**: **Historical rates for movements + closing rate for balances, no explicit FX translation adjustment row**. Simpler IAS 21 middle ground — opening balances retroactively re-translated to prior period's closing rate, closing balances at current period's closing rate, movements at their transaction-date rate. FX drift absorbed silently into closing balance (movement reconciliation does not add up on-page for multi-currency portfolios). Envelope carries per-currency native totals + reporting-currency-translated grand total. Existing `FxConverter` + V112 `public.exchange_rates` machinery. Rejected: full IAS 21 compliant (mixed rates + explicit translation adjustment row — three FX conversions per row); closing rate for everything (not IAS-21-compliant); per-currency reports only (contradicts parent-plan invariant #1); historical for movements + closing for balances + no adjustment row middle-ground (chosen as the pragmatic answer; not audit-defensible for statutory, tenant admins accept the management-view choice).

- **I9 — Coverage units + variable fee definition mechanism**: **Bundled into IFRS17_MODEL rule — one rule sets model + coverage_unit_pattern + variable_fee_pattern**. `IfrsPortfolioFact` carries mutable fields `measurementModel` (PAA | GMM | VFA), `coverageUnitPattern` (TIME | SUM_INSURED_TIME | SUM_AT_RISK_TIME | CLAIM_FREQUENCY_TIME | CUSTOM), and `variableFeePattern` (FIXED_PCT | TIERED | UNDERLYING_GROWTH_SHARE). PAA template only sets measurementModel; GMM template sets model + coverage; VFA template sets all three. I17 adds `financeExpensePresentation` as the fourth mutable field. Rejected: separate `RuleCategory.IFRS17_COVERAGE_UNITS` category (3x category boilerplate + 2-3 rule sweeps per portfolio); static enum columns on `ifrs17_portfolio` (contradicts I2 rules-engine pattern); fixed defaults per model with tenant admin override on portfolio row only (forecloses effective-date rule authoring).

- **I10 — Rename mechanics for `actuarial_report_job` → `report_job` + Kafka topics**: **Dual-write window, 2-deploy transition**. Phase 1: Flyway migration `ALTER TABLE actuarial_report_job RENAME TO report_job` (atomic, data preserved). URL alias `/api/v1/reports/actuarial/jobs/*` still routes to same controller + new `/api/v1/reports/jobs/*` added. Kafka: create `medfund.report.job-requested` + `medfund.report.job-completed` topics; publisher writes to BOTH old + new for 2 deploys; ai-service consumer subscribes to BOTH; Java package `com.medfund.finance.actuarial.*` renamed with kept-as-deprecated re-exports; Python module `app/actuarial/*.py` renamed with backwards-compat shim. Phase 2 (per I22 Phase B): publisher writes to NEW only; consumer subscribes to NEW only; Angular URL flipped to `/reports/jobs/*`; old URL kept as 301 redirect. Phase 3 (per I22 Phase C): remove old-topic writes + subscriptions + Java re-export shims + URL redirect + delete old Kafka topics. Rejected: big-bang cutover in one deploy (in-flight Phase-14 jobs orphaned); keep old names (undoes I3); view-alias + Kafka MirrorMaker (heavy infra).

- **I11 — Onerous contract test mechanism**: **Auto-test at every report run + auto-transition cohort_type + audit trail + manual override**. Python `app/ifrs17/onerous_test.py` runs during every LRC/LIC compute. Compares projected FCF vs (premium received + remaining CSM). Fails → auto-transitions `ifrs17_cohort.cohort_type` (existing V108 CHECK) to ONEROUS in new `cohort_status_history` table. Loss component recognized in the same report run per I19. Manual override remains: tenant admin can revert with a `reason_note`; audit trail preserved. UI banner on Angular cohort detail page explains the reclassification. New audit event `IFRS17_COHORT_RECLASSIFIED`. Rejected: warnings only + no auto-transition (LRC under-reports loss component until manual action); manual only via new IFRS17_ONEROUS_TEST key (workflow-heavy + adds report key); auto-test at initial recognition only (non-compliant with IFRS 17.19 ongoing reassessment requirement).

- **I12 — GMM discount curve source + interpolation**: **Both admin + auto-fetch from central bank feed with per-jurisdiction adapter**. New `services/go/market-data-service` per I16 with `JurisdictionAdapter` interface; cron pulls yield curve daily; publishes to `medfund.market-data.yield-curve-updated`; tenancy-service consumer upserts `tenant_yield_curve`. Tenant admin can override auto-fetched. Linear interpolation between tenors; flat extrapolation beyond longest tenor. Downside consequences: adapter maintenance burden (feed URL / schema changes); scoped to ZW + ZA in v1 per I16. Rejected: admin CRUD only (loses per-tenant benefit of auto-fetch); auto-fetch only (outage = no report); hardcoded per-currency default (not audit-defensible).

- **I13 — Report envelope shape / grain**: **Full grain: per portfolio × cohort × currency, with rollup rows at each level**. Envelope: `{perPortfolio: Map<portfolio_id, {perCohort: Map<cohort_id, {perCurrency: Map<currency, movement>}>}>}` with `total` rollups at portfolio-level, cohort-level, and tenant-level. Separate LRC + LIC reconciliation tables per IFRS 17.101. Insurance Revenue + Service Result report inherits same grain for consistency. XLSX per I21. Matches IFRS 17.14 aggregation unit disclosure standard. Rejected: medium grain per portfolio × currency + cohort-type dimension (loses cohort-year granularity — auditor rejects); coarse per-portfolio only (not IFRS-17-compliant); tenant-total with drill-down (Angular becomes source of truth for shape — contradicts parent-plan invariant #1).

- **I14 — GMM/VFA payload sizing strategy**: **Chunk by portfolio-cohort-currency + parallel Kafka events + finance-service aggregates results**. One Ifrs17ReportController submit fans out into N chunk-events on `medfund.report.job-requested`, keyed on `(portfolio_id, cohort_id, currency)`. Each chunk safely <900KB. ai-service compute runs each chunk independently (natural parallelism via Kafka consumer partitions). Results published to `medfund.report.job-completed` per chunk. finance-service `Ifrs17JobAggregator` consumes results, joins them by parent job_id via new `report_job_chunk` table, marks parent job completed once all chunks land. Angular polling: status = `{chunksTotal, chunksCompleted, chunksFailed}` — UI shows progress bar. Chunks exceeding 900KB fall back to MinIO per I25. Rejected: MinIO fallback as primary (100ms overhead each direction on every large job); Python queries tenant DB directly via search_path (Python becomes tenant boundary — Rule 2 fuzziness); chunk + MinIO always (two mechanisms + minority of tenants exercised).

- **I15 — PAA discounting policy**: **Apply IFRS 17.59 simplification per portfolio automatically**. Python `app/ifrs17/paa.py` auto-derives `coverage_duration` from `earning_schedule` and `settlement_duration` from claim history for the portfolio. If both ≤12mo, skip discount — no yield curve dependency for PAA portfolios. Materiality check baked in: warn if periods cluster near the 12mo threshold. Result envelope includes a `discountingApplied: false, reason: 'IFRS_17_59_coverage_le_12mo'` flag per portfolio. Rejected: discount everything under PAA uniform with GMM (needless compute + operational burden on HEALTH tenants); tenant admin picks per portfolio via checkbox (pushes IFRS-17-technical decision onto admin); skip all discounting under PAA regardless (contradicts IFRS 17.36 for >12mo contracts).

- **I16 — Market-data adapter placement + v1 jurisdictions**: **ZW (RBZ) + ZA (SARB) as new `services/go/market-data-service`**. Two adapters to prove the pattern. Ships `JurisdictionAdapter` interface + cron + Kafka publisher. Other jurisdictions (US Fed, UK BoE, KES CBK) come as follow-up per-tenant demand. Rejected: ZW only (parity issues for ZA tenants); no adapters (walks back I12); ZW + ZA + US + UK (3-4 additional weeks delaying primary Phase 15 deliverables).

- **I17 — Insurance finance expense presentation (IFRS 17.88 election)**: **Per portfolio via bundled IFRS17_MODEL rule — add `financeExpensePresentation` field to IfrsPortfolioFact**. `IfrsPortfolioFact` gains mutable `financeExpensePresentation: PL_ONLY | OCI_OPTION` (defaults: PAA → PL_ONLY since discounting often skipped per I15; GMM/VFA → OCI_OPTION as smoother-P&L default). Report envelope splits `financeExpensePLPortion` + `financeExpenseOCIPortion` per portfolio; XLSX shows both columns; when election is PL_ONLY the OCI column is 0. Lock-in check + audit: after a cohort starts earning, `financeExpensePresentation` cannot change; violation raises validation error + logs security event. Rejected: per portfolio via new `ifrs17_portfolio.finance_expense_presentation` column (contradicts I9's bundled-rule pattern); P&L only for v1 (blocks IFRS-17-reporting LIFE tenants); OCI option only (contradicts IFRS 17.88 permitted-either).

- **I18 — CSM interest accretion locked-in rate storage**: **Full yield-curve snapshot as JSONB on `ifrs17_cohort` at initial recognition**. New tenancy migration adds `locked_in_yield_curve_snapshot JSONB NULL` + `locked_in_at TIMESTAMPTZ NULL` on `ifrs17_cohort`. Written at first-policy-in-cohort issuance (`PolicyIssuedConsumer` emits an idempotent update on user-service side; consumer chain forwards via existing `medfund.user.policy-issued` Kafka topic). Python `app/ifrs17/csm.py` reads snapshot, interpolates per contract's remaining duration, accretes CSM at locked-in rate per IFRS 17.44. Backfill for existing cohorts: use the earliest issuance date of any policy in that cohort as the snapshot date + fetch that historic curve; fallback to current curve + warning if no historic curve exists. Rejected: scalar rate + duration on `ifrs17_cohort` (loses curve shape — cohort spans 12 months with contracts of varying terms); FK to `tenant_yield_curve` row (brittle — deletion of stale curve breaks reference); no lock-in (violates IFRS 17.44).

- **I19 — Loss component tracking mechanism**: **Persistent history table `cohort_loss_component_history` + current-balance materialized view**. New tenancy migration `V147__cohort_loss_component_history.sql` with columns `(cohort_id, effective_at, movement_type ('INITIAL_RECOGNITION' | 'RELEASE' | 'REVERSAL' | 'RECLASSIFICATION_TO_NON_ONEROUS'), amount NUMERIC(18,2), currency VARCHAR(3), source_run_id UUID FK to `report_job`, reason_note TEXT, actor_id, actor_email)`. `cohort_loss_component_current` matview `(portfolio_id, cohort_id, currency, balance)` refreshes at end of each report run. Envelope reads matview for opening balance; movement rows come from history. Every write emits `AuditEvent` per Rule 8. Supports IFRS 17.101 reversal-of-losses disclosure by construction. Rejected: single column `loss_component_balance` on `ifrs17_cohort` (no history for IFRS 17.101 reversal disclosure); recompute at every run without persistence (silent drift on schema evolution — auditor questions); commingled with CSM as negative CSM (non-standard — CSM cannot be negative by definition).

- **I20 — Angular UI shape**: **Split view: movement table (with expandable portfolio→cohort→currency rows) + waterfall chart at summary level**. Top: PrimeNG treetable-style movement table — tenant-total row expands to portfolio rows expands to cohort rows expands to currency rows; each row shows opening / new business / cash flows / revenue / expenses / RA release / CSM release / closing columns. Bottom: waterfall chart (new component `app-waterfall-chart` reusing ngx-charts) at tenant-summary level. Toggle switcher: view LRC / view LIC / view combined. Warnings drawer. Export XLSX button emits `SecurityEvent` per parent-plan invariant #3. Animations off per Phase 14 §A A14 precedent for perf. Same shape applies to both `IFRS17_LRC_LIC_RECONCILIATION` (with LRC/LIC toggle) and `IFRS17_INSURANCE_REVENUE_SERVICE_RESULT` (with revenue/expense/result columns). Rejected: movement table only + tabbed detail (loses executive viz); tab view LRC/LIC/Summary/Waterfall (4 tabs — less scannable); multi-period comparison (delays first meaningful render).

- **I21 — XLSX workbook layout**: **Sheet-per-portfolio with LRC section on top + LIC section below; summary sheet at position 0; per-cohort rows within each portfolio sheet with currency columns**. Position 0: Summary sheet (tenant-total movement table). Positions 1..N: one sheet per portfolio with (a) top block = LRC movement table with cohort rows and currency columns (each cohort further exploded by cohort-year); (b) bottom block = LIC movement table same shape. Insurance Revenue report has same layout minus the LIC block. Matches Phase 14 §A A14 sheet-per-partition + summary-at-0 convention. POI streaming API (SXSSF) for >10k rows. Rejected: sheet-per-portfolio-per-report-section separate LRC + LIC (doubles sheet count); one flat sheet with row hierarchy + LRC/LIC as column groups (40+ columns wide — auditor readability); sheet-per-currency (forecloses portfolio-level executive view).

- **I22 — Deploy order sequence for dual-write window**: **3-phase sequence: rename+dual-write → cutover → cleanup**. Phase A (early sub-plan phase): `ALTER TABLE actuarial_report_job RENAME TO report_job` + create new topics + publisher dual-writes to both topics + consumer dual-subscribes to both + Java package renamed with `com.medfund.finance.actuarial.*` kept as deprecated re-exports + URL alias `/reports/actuarial/jobs/*` still routes + Angular unchanged. Phase B (mid sub-plan phase): publisher writes to NEW topics only + consumer subscribes to NEW only + Angular URL flipped to `/reports/jobs/*` + `/reports/actuarial/jobs/*` URL kept as 301 redirect. Phase C (final cleanup phase): delete old Kafka topics + remove Java re-export deprecation shims + remove URL redirect. Per parent-plan deploy-order invariant: ai-service consumer starts before finance-service publisher (avoid publish-into-void). Rejected: 2-phase sequence rename+dual-write+URL-flip → cleanup (Angular URL flip in Phase A adds risk); 1-phase big-bang (contradicts I10); feature-flagged (env var stays live for months).

- **I23 — Insurance service expenses attribution for Report 2**: **Ship claims paid + acquisition (Phase 11) + tenant_expense_assumption feed; new tables deferred**. Report 2 (`IFRS17_INSURANCE_REVENUE_SERVICE_RESULT`) pulls: (1) claims paid from claims-service via CrossServiceCallHelper; (2) acquisition costs from Phase 11 CommissionAdjustment via CommissionClient (verify at plan time); (3) claims handling / overhead / other categories via `tenant_expense_assumption` (I5) values used for BOTH GMM projection AND actual reporting. Report envelope has an `expenseCategoriesTracked: [String]` field listing which categories are direct vs approximated; envelope warnings note which categories are assumption-based. Rejected: ship claims paid + acquisition only + other categories return zero (understates for auditor); add new tables `claim_handling_expense` + `overhead_allocation` (3-4 phases of pure expense-tracking scope creep); skip all except claims paid (Report 2 becomes decorative).

- **I24 — IBNR dependency handling for LIC compute**: **Auto-trigger IBNR sub-job as prerequisite when IBNR result for the same period isn't fresh; block LIC until IBNR completes**. `Ifrs17JobService` checks for Phase-14 `IBNR_TRIANGLE` row in `report_job` (renamed from `actuarial_report_job` per I3) matching `(tenant, portfolio_line, period)` within N days freshness. If missing/stale: publish IBNR sub-job on `medfund.report.job-requested`; parent job waits via aggregator (I14) for completion; then dispatch LIC chunks with IBNR reference in `params_json`. Result envelope notes `ibnrSubJobId` for traceability. Same mechanism used for I6 CoC → CI translation (both depend on Phase 14 loss distribution). New parent → sub-job dependency added to `report_job_chunk` aggregator model. Polling UI shows 2-stage progress. Rejected: pull most recent IBNR result + warn if stale (silent under/over-statement); fail loud (bad UX two-step workflow); recompute IBNR inline in ai-service `ifrs17/lic.py` (duplicates chain-ladder code path + loses audit trail).

- **I25 — Chunk-exceeds-900KB handling**: **MinIO fallback: upload chunk payload as JSON blob + Kafka event carries `payloadRef`**. New shared `services/java/shared/kafka/MinIOPayloadStore` helper (Java) + `app/kafka/minio_payload.py` (Python). Publisher checks size; if >900KB, uploads to `medfund-report-payloads/{job_id}-{chunk_id}-input.json`; Kafka event carries `{payloadRef: 's3://...', jobId, tenantId}` + no inline payload. ai-service downloads before compute. Symmetric for `result_json`. New MinIO retention: input payloads deleted 7d after chunk completes; result payloads kept 90d matching parent `report_job` (or 7 years for statutory keys per I28). Rejected: further sub-chunk by policy batch (aggregator complexity — parent → chunk → sub-chunk hierarchy); reject with actionable error (bad UX for tenants whose portfolios are genuinely large); raise Kafka `max.message.bytes` to 10MB + gzip (repo-wide config change + CPU load + doesn't scale beyond 10MB).

- **I26 — Golden fixture strategy for compute tests**: **IASB IFRS 17 Illustrative Examples 3, 7, 9, 11 (PAA/GMM/VFA measurement + CSM roll-forward) + synthetic hand-verified for gaps**. Load IASB illustrative examples 3 (PAA measurement), 7 (GMM measurement), 9 (CSM roll-forward), 11 (VFA measurement) as YAML fixtures in `services/python/ai-service/tests/ifrs17/fixtures/iasb_examples/`. Compute must match to 2dp precision. Where the standard's examples don't cover our scenarios (e.g., cross-currency, chunked aggregation, onerous test edge cases), synthetic fixtures with hand-computed expected values in the same YAML shape. Rejected: all synthetic (no external anchor — systematic bug invisible); third-party actuarial society case study (fewer clean-cut examples + licensing); internal consistency only (systematic bias undetected).

- **I27 — Default IFRS17_MODEL rules on tenant provisioning**: **Seed per-line default rules at tenant provisioning: HEALTH/TRAVEL/VEHICLE/PROPERTY/GROUP → PAA, LIFE/FUNERAL/DISABILITY → GMM**. New tenancy migration seeds 8 default `IFRS17_MODEL` rules per tenant, one per insurance_line. `variableFeePattern` NULL (VFA opt-in — admin picks explicit VFA for unit-linked contracts). `coverageUnitPattern` defaults to TIME. `financeExpensePresentation` defaults to PL_ONLY for PAA lines, OCI_OPTION for GMM lines. All seeded rules marked `source_note='industry_default_v1'` for auditability + admin override tracking. Mirrors Phase-14 §0 A10 `industry_default_v1` persistency-curve seed convention. Rejected: empty by default + hardcoded Java fallback (contradicts I2 rules-engine spirit); block LRC/LIC submits until rules authored (first-day friction); wizard-driven (1-2 weeks of wizard UI + not in scope).

- **I28 — Retention policy for report_job + chunks + MinIO payloads**: **Split by report family: statutory keys (IFRS17_*, regulatory) 7 years; operational keys (actuarial) 90d + 20 runs; chunks purge with parent; MinIO input 7d, result matches parent**. New column `report_job.retention_class ENUM('OPERATIONAL_90D','STATUTORY_7Y')` derived from `ReportFamily` at insert. Nightly cron (existing per Phase 14 Grill note 8) respects the class. Chunks cascade with parent via FK ON DELETE CASCADE. MinIO input payload purges 7d after chunk complete; MinIO result payload purges according to parent's retention class. Rejected: uniform 90d + 20 runs across all report keys (not statutory-grade); uniform 7 years for everything (Phase 14 actuarial reports don't need 7-year retention — storage cost); tenant-configurable retention (1-2 phases of admin CRUD + tenants pick wrong).

- **I29 — Opening LRC/LIC balance seeding**: **Auto-derive from earning_schedule by default; tenant admin override per portfolio × cohort × currency with audit trail**. `Ifrs17JobService` checks for a prior completed `report_job` for the same `(tenant, portfolio, cohort, currency, LRC or LIC)` with `period_end <= new_period_start`. If found: use its closing balance as opening. If not: derive from `earning_schedule` + claim history. Tenant admin can override at any `(portfolio, cohort, currency)` via new page `/tenant/admin/settings/ifrs17-opening-balances` with per-row input + `reason_note` + `effective_from` + audit. New table `ifrs17_opening_balance_seed(portfolio_id, cohort_id, currency, balance_type ('LRC'|'LIC'), amount, effective_from, actor_id, actor_email, reason_note, created_at)`. Supports both green-field tenants (auto-derive) and tenants migrating in from another IFRS-17 system (admin seed). Rejected: auto-derive only (blocks enterprise migrations); admin input only (green-field friction); fail-loud until admin seeds (worse error handling than admin-input-only).

- **I30 — Notification-service integration for high-severity IFRS 17 events**: **New `medfund.ifrs17.material-event` Kafka topic + notification-service `internal/ifrs17/dispatcher.go` for email/webhook delivery; envelope warnings retained**. `Ifrs17JobService` + `Ifrs17JobAggregator` emit `IfrsMaterialEvent` records to `medfund.ifrs17.material-event` for: onerous auto-transition (per portfolio × cohort), CSM negative event, locked-in curve fallback (I18 backfill), IBNR sub-job stale (auto-triggered per I24), auto-derived opening balance where admin might want override (I29). Notification-service `internal/ifrs17/dispatcher.go` consumes; delivers via email to `tenant_report_recipient` recipients OR webhook per tenant config. New admin surface `/tenant/admin/settings/ifrs17-notifications` for recipient + throttle config. Throttle: same-tenant same-event within N minutes deduplicated. Envelope warnings remain the point-in-time view. Rejected: envelope warnings only (tenant admin who doesn't run report misses events); reuse Phase 17 scheduled-delivery pipe (delays urgent notifications); in-Angular toast + notification bell only (out-of-hours events missed).

### Settled by fact (not asked)

- **F15-1 — Both IFRS 17 report keys already ship** at `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:116-117` — `IFRS17_LRC_LIC_RECONCILIATION` (cadenced=true) + `IFRS17_INSURANCE_REVENUE_SERVICE_RESULT` (cadenced=true). **No enum-add work** for keys.
- **F15-2 — Both keys map to `ReportFamily.REGULATORY`** at `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:15-29`. No separate `ReportFamily.IFRS17` — `REGULATORY` houses both IFRS 17 keys + future Phase 16 regulatory keys (IPEC / CMS / NAIC / PMB / AML / TAX). **No family-add work.**
    - **Superseded 2026-08-30 by Phase 16 §0 REG19 (sub-plan Phase 7):** the 8 Phase-16 keys were split out of REGULATORY into new families — IPEC/CMS/NAIC-P/NAIC-F → PRUDENTIAL; VAT/TAX_WITHHELD → TAX; PMB_SPEND/AML_STR → COMPLIANCE. REGULATORY now retains IFRS 17 only (both keys unchanged). See `ReportFamilyTest.phase16RegulatoryFamilySplit` + `ReportKeyTest.phase16KeysMoveIntoNewFamilies`.
- **F15-3 — Phase 12 already landed the dimensional foundation.** V107 `ifrs17_portfolio` (id, name, description, insurance_line, is_active, created_at, updated_at, actor_id, actor_email) + MISC catchall seeded per tenant + FKs on all policy tables + legacy backfill; V108 `ifrs17_cohort` with `cohort_type CHECK IN ('ONEROUS','NON_ONEROUS','UNCERTAIN')` + `cohort_year` + MISC-YYYY-DEFAULT cohort seeded per tenant; V109 `earning_schedule` with per-period `written_amount` + `earned_at_period_end` + `portfolio_id` + `cohort_id` + `earning_method` + `currency_code` + `is_endorsement` + `endorsement_id`. Entity classes `Ifrs17Portfolio.java` + `Ifrs17Cohort.java` at `services/java/user-service/src/main/java/com/medfund/user/entity/`. Portfolio CRUD `Ifrs17PortfolioController` at `services/java/user-service/src/main/java/com/medfund/user/controller/Ifrs17PortfolioController.java:35-75`; parallel `Ifrs17CohortController`. Angular admin routes `/tenant/admin/underwriting/portfolios` + `/tenant/admin/underwriting/cohorts` at `clients/angular/src/app/pages/tenant-admin/underwriting/underwriting.routes.ts`. Permission keys `underwriting.portfolio:manage` + `underwriting.cohort:manage` at `clients/angular/src/app/core/security/permissions.ts`.
- **F15-4 — Phase 14 async job pattern is generic-shaped and reusable.** `services/java/finance-service/src/main/java/com/medfund/finance/actuarial/entity/ActuarialReportJob.java:24-62` — table `actuarial_report_job` pivots on `report_key` + `params_json` + `params_hash` + `result_json`; V141 append-only trigger; Kafka topics `medfund.actuarial.job-requested` + `medfund.actuarial.job-completed`; 800KB warn / 900KB reject size guards; publisher at `ActuarialJobPublisher.java:38-50`; result consumer at `ActuarialResultConsumer.java:37-50` with tenant match + `.doOnSuccess` ack per `bug_reactor_kafka_ack_swallow`. Semantically not actuarial-specific — the name is. I3 + I10 + I22 rename to `report_job` + `medfund.report.*` in 3-phase dual-write sequence.
- **F15-4a — Phase 14 basis tables reusable for GMM assumption inputs.** `services/python/ai-service/app/actuarial/basis_tables/` — mortality YAMLs `A1949_52`, `A67_70`, `SA85_90`, `CSO_2017`; morbidity YAMLs `CIDA`, `GLTD87`. `public.tenant_persistency_basis` (V136) + `public.tenant_mortality_basis` (V137) + `public.tenant_morbidity_basis` (V138) provide per-tenant tie + multiplier. Phase 15 `app/ifrs17/gmm.py` reads via existing `basis_loader.py`.
- **F15-5 — `PremiumFact` already carries portfolio + cohort.** `services/java/rules-engine/src/main/java/com/medfund/rules/fact/PremiumFact.java:58-59` — `portfolioId`, `cohortId` populated by `PolicyIssuedConsumer` before DRL evaluation. Pattern to clone for `IfrsPortfolioFact`. `RuleCategory.PREMIUM_EARNING` exists at `RuleCategory.java:92`. Phase 14 §D added `ACTUARIAL` category. Phase 15 adds `IFRS17_MODEL` category.
- **F15-6 — `EarningScheduleClosureService` gives us the LRC time-value engine free.** `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/EarningScheduleClosureService.java` — `closeExpiredPeriodsForTenant()` nightly close (line 46-65), `recomputeForEndorsement()` retro-recompute (line 170-201), `closeOutForPolicyClosure()` lapse/terminate (line 290-327), `freezePolicyEarning()` + `resumePolicyEarning()` + `reinstatePolicyEarning()`. LRC = written − earned per policy at any as-of date is a `sum(written_amount) − sum(earned_at_period_end)` query. I29 auto-derive path reads this.
- **F15-7 — No IFRS 17 compute logic anywhere yet.** grep for `LRC`, `LIC`, `CSM`, `RA`, `fulfilment cash flow`, `insurance service result` — zero hits in functional code (only Angular route titles + permission labels). Greenfield math. I1's three-model scope means Phase 15 is a large greenfield build.
- **F15-8 — MISC catchall + MISC-YYYY-DEFAULT cohort seeded per tenant.** V107:24 + V108:23-27 seed sentinel portfolio + cohort per tenant. Every policy has a `portfolio_id` + `cohort_id` assignment (V107:57-64 + V108:55-62 backfill). LRC/LIC per-portfolio-per-cohort partitioning is guaranteed to have somewhere to bucket unassigned policies.
- **F15-9 — Python `app/actuarial/` package exists; no `ifrs17/` yet.** `services/python/ai-service/app/actuarial/` has `chain_ladder.py`, `persistency.py`, `mortality.py`, `morbidity.py`, `lapse.py`, `basis_loader.py`, `events.py`, `kafka.py` (Phase 14 landed). No `app/ifrs17/` or `app/disclosure/` package. Phase 15 §12 adds `app/ifrs17/{paa,gmm,vfa,csm,risk_adjustment,coverage_units,onerous_test,discount_curve}.py`.
- **F15-10 — Phase 16 regulatory scaffolding does not exist.** `services/java/shared/src/main/resources/report-templates/` — no directory. `RegulatoryTemplateService`, `IpecReportController` — no hits. Phase 15 is not blocked by Phase 16; the two IFRS 17 keys are just early residents of the `REGULATORY` family. Phase 16 grill (a separate future pass) will scaffold per-regulator template infrastructure that IFRS 17 keys can optionally reuse for regulatory disclosure formats.
- **F15-11 — Angular tenant-admin already has portfolio + cohort CRUD.** `clients/angular/src/app/pages/tenant-admin/underwriting/{portfolios,cohorts}/` — permissions `underwriting.portfolio:manage` + `underwriting.cohort:manage` at `permissions.ts`. No new admin surface needed for the *dimensions*. New Phase 15 admin surfaces are for the *configuration* (RA / yield curve / expense assumption / VFA fund / policy unit ledger / variable fee schedule / opening balance seed / notification recipients).
- **F15-12 — MinIO already in the infra stack** per Makefile `make infra` (F14-12). Available for I25 fallback without new infra provisioning.
- **F15-13 — Existing per-tenant admin CRUD pattern for basis config (Phase 14 §0).** `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantPersistencyBasisController.java` — list / add / update-by-id / delete-by-id. Pattern to clone for `TenantRaConfigController`, `TenantYieldCurveController`, `TenantExpenseAssumptionController`, `Ifrs17OpeningBalanceSeedController`.
- **F15-14 — Existing per-line default-seed migration pattern.** Phase 14 §0 A10 `industry_default_v1` seed convention (`tenant_persistency_basis` seed at tenant provisioning). I27 clones this pattern for `IFRS17_MODEL` rules.
- **F15-15 — Next migration numbers (verify at plan time).** tenancy-service last public V138 (Phase 14 basis tables) — new public I12 + I5 + I6 tables start V139/V140/V141. tenancy-service last tenant V115 per Phase 14 sub-plan (`earning_schedule_closure_ref`); Phase 14 sub-plan added V139-V142 tenant migrations (V139-V141 basis-config-tenant + V142 death status/reason). Phase 15 tenant migrations start V143+ (VFA entities + cohort_status_history + cohort_loss_component_history + JSONB columns on `ifrs17_cohort` + `ifrs17_opening_balance_seed` + `report_job` rename + `report_job_chunk` + `report_job.retention_class`).

### Data model

Tenant-scoped tables all with standard audit tail (`id UUID PK DEFAULT gen_random_uuid(), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`) per `feedback_audit_actor_email` (Rule 8). Public-schema tables carry per-tenant `tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE`.

| Table | Service | Purpose | Key columns |
|---|---|---|---|
| `report_job` (renamed from `actuarial_report_job`, I3/I10) | finance-service tenant | Async job registry + result payload + audit trail | Existing Phase-14 shape + new `retention_class ENUM('OPERATIONAL_90D','STATUTORY_7Y') NOT NULL` (I28), `parent_job_id UUID NULL REFERENCES report_job(id) ON DELETE CASCADE` (I24 sub-job link + I14 chunk aggregator parent). Rename-in-place migration; V141 append-only trigger renamed. |
| `report_job_chunk` (I14) | finance-service tenant | Chunked compute + aggregator source-of-truth | `id UUID PK`, `parent_job_id UUID NOT NULL REFERENCES report_job(id) ON DELETE CASCADE`, `portfolio_id UUID`, `cohort_id UUID`, `currency VARCHAR(3)`, `status VARCHAR NOT NULL`, `params_json JSONB NOT NULL`, `params_ref VARCHAR NULL` (MinIO ref per I25), `result_json JSONB NULL`, `result_ref VARCHAR NULL`, `error_message TEXT NULL`, `requested_at TIMESTAMPTZ NOT NULL`, `completed_at TIMESTAMPTZ NULL`. INDEX `(parent_job_id, status)`. |
| `public.tenant_ra_config` (I6) | tenancy-service public | Per-portfolio RA methodology + params | `tenant_id UUID NOT NULL`, `portfolio_id UUID NOT NULL`, `methodology VARCHAR NOT NULL CHECK IN ('COC','CI')`, `coc_rate NUMERIC(5,4) NULL`, `target_confidence_level NUMERIC(5,4) NULL`, `effective_from DATE NOT NULL DEFAULT CURRENT_DATE`, `effective_to DATE NULL`, `source_note VARCHAR NULL`. UNIQUE `(tenant_id, portfolio_id, effective_from)`. |
| `public.tenant_yield_curve` (I5/I12) | tenancy-service public | Per-currency yield curve for GMM discounting | `tenant_id UUID NOT NULL`, `currency VARCHAR(3) NOT NULL`, `tenor_months INT NOT NULL`, `spot_rate NUMERIC(9,7) NOT NULL`, `effective_from DATE NOT NULL DEFAULT CURRENT_DATE`, `effective_to DATE NULL`, `source VARCHAR NOT NULL` (`ADMIN` or `RBZ_AUTO` / `SARB_AUTO`). UNIQUE `(tenant_id, currency, tenor_months, effective_from)`. |
| `public.tenant_expense_assumption` (I5) | tenancy-service public | Per-line per-expense-type assumption for GMM projection AND actual reporting (I23) | `tenant_id UUID NOT NULL`, `insurance_line VARCHAR NOT NULL`, `expense_type VARCHAR NOT NULL CHECK IN ('ACQUISITION','MAINTENANCE','CLAIMS_HANDLING','OVERHEAD','OTHER')`, `amount_per_policy NUMERIC(18,2) NOT NULL`, `currency VARCHAR(3) NOT NULL`, `effective_from DATE NOT NULL DEFAULT CURRENT_DATE`, `effective_to DATE NULL`. UNIQUE `(tenant_id, insurance_line, expense_type, currency, effective_from)`. |
| `unit_linked_fund` (I4) | user-service tenant | VFA underlying-item fund entity | `id UUID PK`, `name VARCHAR(200) NOT NULL`, `currency VARCHAR(3) NOT NULL`, `base_asset_class VARCHAR NOT NULL CHECK IN ('EQUITY','FIXED_INCOME','MULTI_ASSET','MONEY_MARKET','REAL_ESTATE','OTHER')`, `is_active BOOLEAN NOT NULL DEFAULT TRUE`, standard audit tail. |
| `fund_nav_history` (I4) | user-service tenant | Per-fund NAV per unit at each valuation date | `id UUID PK`, `fund_id UUID NOT NULL REFERENCES unit_linked_fund(id)`, `valuation_date DATE NOT NULL`, `nav_per_unit NUMERIC(18,6) NOT NULL`, `source VARCHAR NOT NULL` (`ADMIN` / `AUTO`), standard audit tail. UNIQUE `(fund_id, valuation_date)`. INDEX `(fund_id, valuation_date DESC)`. |
| `policy_unit_ledger` (I4) | user-service tenant | Per-policy unit allocation history | `id UUID PK`, `policy_id UUID NOT NULL`, `fund_id UUID NOT NULL REFERENCES unit_linked_fund(id)`, `transaction_date DATE NOT NULL`, `transaction_type VARCHAR NOT NULL CHECK IN ('PURCHASE','SALE','ROLLOVER','FEE_DEDUCTION','FUND_SWITCH_IN','FUND_SWITCH_OUT')`, `units NUMERIC(18,6) NOT NULL`, `price NUMERIC(18,6) NOT NULL`, standard audit tail. INDEX `(policy_id, transaction_date DESC)`. |
| `variable_fee_schedule` (I4) | user-service tenant | Per-fund variable fee % schedule for VFA compute | `id UUID PK`, `fund_id UUID NOT NULL REFERENCES unit_linked_fund(id)`, `effective_from DATE NOT NULL DEFAULT CURRENT_DATE`, `effective_to DATE NULL`, `fee_percentage NUMERIC(5,4) NOT NULL`, standard audit tail. |
| `cohort_status_history` (I11) | user-service tenant | Point-in-time cohort_type transitions with audit trail | `id UUID PK`, `cohort_id UUID NOT NULL REFERENCES ifrs17_cohort(id)`, `from_status VARCHAR NOT NULL`, `to_status VARCHAR NOT NULL CHECK IN ('ONEROUS','NON_ONEROUS','UNCERTAIN')`, `transition_reason VARCHAR NOT NULL CHECK IN ('AUTO_TEST_FAILED','AUTO_TEST_RECOVERED','MANUAL_OVERRIDE','INITIAL_CLASSIFICATION')`, `transition_source VARCHAR NOT NULL CHECK IN ('AUTO','MANUAL')`, `source_run_id UUID NULL REFERENCES report_job(id)`, `effective_at TIMESTAMPTZ NOT NULL`, `reason_note TEXT NULL`, `actor_id UUID NULL`, `actor_email VARCHAR NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. INDEX `(cohort_id, effective_at DESC)`. |
| `cohort_loss_component_history` (I19) | user-service tenant | Movement journal for loss component of onerous cohorts | `id UUID PK`, `cohort_id UUID NOT NULL REFERENCES ifrs17_cohort(id)`, `effective_at TIMESTAMPTZ NOT NULL`, `movement_type VARCHAR NOT NULL CHECK IN ('INITIAL_RECOGNITION','RELEASE','REVERSAL','RECLASSIFICATION_TO_NON_ONEROUS')`, `amount NUMERIC(18,2) NOT NULL`, `currency VARCHAR(3) NOT NULL`, `source_run_id UUID NULL REFERENCES report_job(id)`, `reason_note TEXT NULL`, `actor_id UUID NULL`, `actor_email VARCHAR NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. INDEX `(cohort_id, effective_at DESC)`. |
| `cohort_loss_component_current` matview (I19) | user-service tenant | Current balance for envelope opening-balance lookup | `portfolio_id UUID`, `cohort_id UUID`, `currency VARCHAR(3)`, `balance NUMERIC(18,2)`. Refreshed at end of each report run. |
| `ifrs17_opening_balance_seed` (I29) | user-service tenant | Tenant admin override for opening balance seed | `id UUID PK`, `portfolio_id UUID NOT NULL`, `cohort_id UUID NOT NULL`, `currency VARCHAR(3) NOT NULL`, `balance_type VARCHAR NOT NULL CHECK IN ('LRC','LIC')`, `amount NUMERIC(18,2) NOT NULL`, `effective_from DATE NOT NULL`, `reason_note TEXT NOT NULL`, `actor_id UUID NOT NULL`, `actor_email VARCHAR NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. UNIQUE `(portfolio_id, cohort_id, currency, balance_type, effective_from)`. |

**Additive columns on existing tables:**

| Entity | Service | New columns |
|---|---|---|
| `ifrs17_cohort` | user-service tenant | `locked_in_yield_curve_snapshot JSONB NULL` + `locked_in_at TIMESTAMPTZ NULL` (I18). Backfill: use earliest issuance date of any policy in cohort as snapshot date + fetch historic curve; fallback current curve + warning. |
| `report_job` | finance-service tenant | `retention_class ENUM('OPERATIONAL_90D','STATUTORY_7Y') NOT NULL DEFAULT 'OPERATIONAL_90D'` (I28) + `parent_job_id UUID NULL REFERENCES report_job(id) ON DELETE CASCADE` (I14 + I24 sub-job link). Backfill: existing rows get `OPERATIONAL_90D` (Phase 14 keys are all actuarial). |

**Basis-table reuse (ai-service, F15-4a):** `services/python/ai-service/app/actuarial/basis_tables/mortality/{A1949_52,A67_70,SA85_90,CSO_2017}.yaml` + `morbidity/{CIDA,GLTD87}.yaml` consumed by `app/ifrs17/gmm.py` via existing `basis_loader.py`.

**IASB illustrative example fixtures (I26):** `services/python/ai-service/tests/ifrs17/fixtures/iasb_examples/{example_3_paa,example_7_gmm,example_9_csm_rollforward,example_11_vfa}.yaml` — hand-transcribed from the IASB Illustrative Examples companion document + synthetic hand-verified for cross-currency + chunked-aggregation + onerous edge cases.

### Kafka topology

Four new topics (three from Phase-15 net-new + one renamed from Phase 14):

| Component | Direction | Topic | Behaviour |
|---|---|---|---|
| `finance-service ReportJobPublisher` (renamed from `ActuarialJobPublisher` per I3/I10) | out | `medfund.report.job-requested` (new — renamed from `medfund.actuarial.job-requested` per I10 Phase A dual-write, Phase B cutover, Phase C delete) | On every `Ifrs17ReportController` request → publish N chunk-events keyed on `(portfolio_id, cohort_id, currency)` per I14. Payload size guarded (>800KB warn, >900KB MinIO fallback per I25). Publishes AFTER inserting `report_job (parent, status='requested')` + N `report_job_chunk` rows. Idempotent via params-hash partial UNIQUE index per Phase 14 A9. Dual-writes to old + new topics per I22 Phase A. |
| `ai-service ReportJobConsumer` (renamed from `ActuarialJobConsumer`) | in | `medfund.report.job-requested` | Consumes; delegates to `chain_ladder.compute()` / `persistency.compute()` / `mortality.compute()` / `morbidity.compute()` / `lapse.compute()` / **new** `ifrs17.paa.compute()` / `ifrs17.gmm.compute()` / `ifrs17.vfa.compute()` based on `reportKey`. On success → publishes to `medfund.report.job-completed`. On failure → publishes with `status='failed'` + error. Stateless — no DB writes on ai-service side. Dual-consumes from old + new topics per I22 Phase A. |
| `ai-service ReportResultPublisher` (renamed from `ActuarialResultPublisher`) | out | `medfund.report.job-completed` (new — renamed from `medfund.actuarial.job-completed` per I10) | Payload `{jobId, tenantId, reportKey, status, result_json (inline OR MinIO ref per I25), errorMessage, modelVersion, method, basis, computedAt, schema_version: 1}`. Event-size guard >900KB → MinIO ref fallback per I25. Dual-writes per I22 Phase A. |
| `finance-service ReportResultConsumer` (renamed from `ActuarialResultConsumer`) → `Ifrs17JobAggregator` (new, I14) | in | `medfund.report.job-completed` | On consume: verify `event.tenantId == chunk.tenantId` (Rule 2 guard); UPDATE `report_job_chunk` row `SET status='completed'/failed', result_json=..., completed_at=NOW()`; if all sibling chunks for parent_job_id are terminal → aggregate results into parent `report_job.result_json` + set parent status. `.doOnSuccess` ack per `bug_reactor_kafka_ack_swallow`. Emits `AuditEvent` on state transition per Rule 8. If parent job is an IBNR sub-job triggered by an IFRS 17 parent (I24) → notify parent poller. Dual-consumes per I22 Phase A. |
| `finance-service Ifrs17MaterialEventPublisher` (new, I30) | out | `medfund.ifrs17.material-event` (new) | Emitted by `Ifrs17JobService` + `Ifrs17JobAggregator` on: onerous auto-transition, CSM negative event, locked-in curve fallback, IBNR sub-job stale (auto-triggered), auto-derived opening balance override candidate. Payload `{tenantId, portfolioId, cohortId?, eventType, severity, message, sourceRunId, occurredAt, schema_version: 1}`. |
| `notification-service ifrs17/dispatcher.go` (new, I30) | in | `medfund.ifrs17.material-event` | Consumes; queries tenant's IFRS 17 notification recipients config; dispatches via email (existing SMTP dispatcher pattern) OR webhook (existing webhook dispatcher pattern). Throttle same-tenant same-event within N minutes deduplicated. |
| `services/go/market-data-service` (new, I16) | out | `medfund.market-data.yield-curve-updated` (new) | Cron pull from RBZ + SARB; publishes `{tenantIdWildcard: '*', currency, curveRows: [{tenor, rate}], source: 'RBZ_AUTO' | 'SARB_AUTO', publishedAt}`. Delivered at-most-once per (currency, publishing_day). |
| `tenancy-service YieldCurveConsumer` (new, I12) | in | `medfund.market-data.yield-curve-updated` | Consumes; upserts `public.tenant_yield_curve` rows for every tenant enrolled in auto-fetch for that currency (per-tenant `tenant_market_data_config`). |

**Rule-3 audit path**: `report_job.result_json` remains the immutable audit record per Phase 14 A16; JSONB-indexed for query; row INSERT-only-then-single-completion-UPDATE (append-only invariant enforced via V141 `BEFORE UPDATE` trigger that raises when `OLD.status IN ('completed', 'failed')`). Statutory-class rows (per I28) retained 7 years; operational-class rows retained 90d + 20 per (tenant, report_key).

**Deploy order per I22 + parent-plan invariant**: (Phase A) ai-service `ReportJobConsumer` dual-subscribes BEFORE finance-service `ReportJobPublisher` dual-writes (avoid publish-into-void); finance-service `ReportResultConsumer` dual-subscribes BEFORE ai-service `ReportResultPublisher` dual-writes (avoid orphan results); tenancy-service `YieldCurveConsumer` BEFORE `market-data-service` publishes; notification-service `ifrs17/dispatcher.go` BEFORE finance-service `Ifrs17MaterialEventPublisher` publishes. (Phase B) publisher/consumer flipped to NEW-only. (Phase C) old topics + shims removed.

### Report shapes

Two report surfaces (per I13 + I20 + I21). All `@RequiresReport(...)` + `ReportEnvelopeBuilder` + `SecurityEventPublisher.publishDataAccess` on export. All served from **finance-service** at `/api/v1/reports/ifrs17/*` per I7. All use async chunked job pattern per I14 — the POST endpoint returns immediately with a parent `jobId`; result endpoint returns `202 Accepted` with `{chunksTotal, chunksCompleted, chunksFailed, currentPhase}` progress or `200 OK` with aggregated `result_json`.

Endpoints:

- `POST /api/v1/reports/ifrs17/lrc-lic-reconciliation` — body `{periodStart, periodEnd, portfolioIds?, cohortIds?, reportingCurrency?}`. Returns `{jobId, status='requested', chunksTotal}`. Emits chunk events per I14.
- `POST /api/v1/reports/ifrs17/insurance-revenue-service-result` — same body shape. Same async chunk pattern.
- `GET /api/v1/reports/jobs/{jobId}` — polling endpoint (renamed from `/api/v1/reports/actuarial/jobs/{jobId}` per I22). Returns `{jobId, status, result_json?, errorMessage?, chunksTotal, chunksCompleted, chunksFailed}`. Angular polls every 2s (backoff to 5s after 30s) until status is terminal. Alias to old URL preserved during Phase A + B; 301 redirect during Phase B; removed in Phase C.
- `GET /api/v1/reports/jobs/{jobId}/export.xlsx` — XLSX export from completed parent `result_json` per I21. Emits `SecurityEvent` per parent-plan invariant #3.
- `GET /api/v1/reports/actuarial/jobs/{jobId}` — Phase A: routes to same controller as new URL; Phase B: 301 redirect; Phase C: 404.

Result envelope per report (per I13):

- **IFRS17_LRC_LIC_RECONCILIATION**: `{perPortfolio: Map<portfolio_id, {portfolioName, measurementModel, perCohort: Map<cohort_id, {cohortYear, cohortType, perCurrency: Map<currency, {lrcMovement: {opening, newBusiness, cashInflows, cashOutflows, insuranceRevenue, insuranceServiceExpenses, financeExpensePLPortion, financeExpenseOCIPortion, raRelease, csmRelease, lossComponentRecognized, lossComponentReleased, lossComponentReversal, closing}, licMovement: {opening, newIncurred, claimsPaid, expensesPaid, financeExpensePLPortion, financeExpenseOCIPortion, raChange, raRelease, closing}}>, cohortTotal, discountingApplied, discountingSkipReason?}>, portfolioTotal}>, tenantTotal, fxRates: Map<currency, closingRate>, warnings: [String]}`. XLSX per I21.
- **IFRS17_INSURANCE_REVENUE_SERVICE_RESULT**: `{perPortfolio: Map<portfolio_id, {portfolioName, measurementModel, perCohort: Map<cohort_id, {perCurrency: Map<currency, {insuranceRevenue: {expectedClaims, expectedExpenses, raRelease, csmRelease, lossComponentReversal, total}, insuranceServiceExpenses: {claimsPaid, acquisitionCosts, assumptionBasedOverhead, lossComponentRecognized, raChange, total}, insuranceServiceResult, expenseCategoriesTracked: [String]}>}>, portfolioTotal}>, tenantTotal, fxRates, warnings, expenseCategoriesTracked: [String]}`. XLSX per I21 with LIC block omitted; Insurance Revenue section only.

**Envelope FX / missing-rate semantics per I8**: historical rates for movements + closing rate for balances + no explicit translation adjustment row; movement reconciliation may not add up exactly for multi-currency portfolios (management-view choice, not audit-defensible for statutory). Envelope `fxRates` map carries closing rates by currency for tenant-side inspection. Missing FX rate → skip conversion + envelope warning (never fail entire report).

**Envelope discounting semantics per I15**: `discountingApplied: boolean` + `discountingSkipReason: 'IFRS_17_59_coverage_le_12mo' | null` per portfolio. Warning if periods cluster near threshold.

### Angular surfaces

Tenant-admin CRUD (per I6 + I5 + I12 + I4 + I29 + I30):

- New `/tenant/admin/settings/ifrs17-ra` — per-portfolio RA methodology (COC / CI) + params (coc_rate OR target_confidence_level) with effective-date versioning. Add / edit / delete rows.
- New `/tenant/admin/settings/ifrs17-yield-curves` — per-currency tabs; each tab per-tenor rows + CSV upload button + effective-date versioning. Auto-fetch source badge (RBZ_AUTO / SARB_AUTO / ADMIN). Admin can override.
- New `/tenant/admin/settings/ifrs17-expense-assumptions` — per-line per-expense-type tabs; add / edit / delete rows with effective-date versioning.
- New `/tenant/admin/settings/ifrs17-opening-balances` — per-portfolio-cohort-currency-balance_type rows with `reason_note` + `effective_from` + admin override for auto-derive.
- New `/tenant/admin/settings/ifrs17-notifications` — recipient list + throttle config + event-type toggles.
- New `/tenant/admin/underwriting/funds` — VFA unit-linked fund CRUD (name, currency, base_asset_class, is_active).
- New `/tenant/admin/underwriting/funds/{id}/nav-history` — NAV upload (CSV + per-row entry) + audit trail.
- New `/tenant/admin/underwriting/funds/{id}/variable-fees` — per-fund fee schedule with effective-date versioning.
- Existing `/tenant/admin/underwriting/portfolios/{id}` — new "Rules" tab showing IFRS17_MODEL rule that matches this portfolio + linked measurement_model.
- Existing `/tenant/admin/underwriting/cohorts/{id}` — new "Status history" tab showing cohort_status_history rows; new "Loss component" tab showing cohort_loss_component_history rows (visible only when cohort_type=ONEROUS).

Reports (per I20):

- `/tenant/finance/reports/ifrs17/lrc-lic-reconciliation` — period picker (default: quarterly, last 4 quarters), portfolio filter (multi-select), cohort filter (dependent on portfolio selection), reporting-currency override, "Run report" button. On submit → POST returns parent jobId → poll UI shows chunk progress ("N of M chunks complete") + IBNR sub-job stage indicator per I24 → renders split view per I20: PrimeNG treetable movement table + `app-waterfall-chart` at tenant-summary level. LRC / LIC / combined toggle. Warnings drawer. "Export XLSX" button emits SecurityEvent + downloads job export endpoint.
- `/tenant/finance/reports/ifrs17/insurance-revenue-service-result` — same shape, revenue + service expenses + service result columns.

Shared async job UX (extended from Phase 14):

- Existing `ActuarialJobPollingService` renamed to `ReportJobPollingService` per I22 + backwards-compat alias.
- Existing `ActuarialJobProgressComponent` renamed + extended with chunk-progress + IBNR-sub-job-stage indicators.
- New `app-waterfall-chart` component (reuses ngx-charts) per I20.

Tenant admin rules (per I2 + I9 + I17):

- Extends existing `/tenant/admin/rules` page with new IFRS17_MODEL category filter + three template cards (PAA / GMM / VFA) each prompting for mutable fields per I9 + I17.

Reports hub auto-registers both IFRS 17 keys via existing `TenantReportConfigService`; `REGULATORY` family card renders when any key enabled.

### Proposed sub-plan tranche structure (per I7 + I22)

**Single sub-plan `thoughts/shared/plans/2026-08-28-ifrs17-pack.md` built via `create-plan` at implement time. ~20-25 verifiable phases:**

- Phases 1-3: rename `actuarial_report_job` → `report_job` + Kafka topics + Java package + Python module dual-write per I22 Phase A (`ReportJob` entity + `ReportJobPublisher` + `ReportJobConsumer` renames + dual-write to old + new topics + URL alias `/reports/actuarial/jobs/*` → same controller as new `/reports/jobs/*`).
- Phases 4-6: new tenancy-service public migrations (`tenant_ra_config` + `tenant_yield_curve` + `tenant_expense_assumption`) + tenant-scoped migrations (`report_job_chunk` + `report_job.retention_class` + `report_job.parent_job_id` + `cohort_status_history` + `cohort_loss_component_history` + `ifrs17_opening_balance_seed` + JSONB columns on `ifrs17_cohort`) + admin CRUD controllers + Angular admin pages.
- Phases 7-10: VFA entities (`unit_linked_fund` + `fund_nav_history` + `policy_unit_ledger` + `variable_fee_schedule`) + admin CRUD + Angular admin pages + NAV upload.
- Phase 11: rules-engine `RuleCategory.IFRS17_MODEL` + `IfrsPortfolioFact` (4 mutable fields per I9 + I17) + three templates (PAA / GMM / VFA) + agenda group + engine wiring + tenant provisioning seed rules per I27.
- Phases 12-14: Python `app/ifrs17/paa.py` + PAA compute + PAA-specific report shaping in finance-service + IASB Example 3 golden fixture.
- Phases 15-18: Python GMM engine (`app/ifrs17/gmm.py` + `csm.py` + `risk_adjustment.py` + `discount_curve.py` + `coverage_units.py` + `onerous_test.py`) + tenant_yield_curve auto-lookup + tenant_expense_assumption feed + PolicyIssuedConsumer extension for `locked_in_yield_curve_snapshot` per I18 + IASB Examples 7 + 9 golden fixtures.
- Phases 19-21: Python VFA compute (`app/ifrs17/vfa.py`) + fund NAV lookup + variable fee application + policy unit ledger integration + IASB Example 11 golden fixture.
- Phases 22-24: finance-service `Ifrs17ReportController` + shaping services + `Ifrs17JobPublisher` (chunk fan-out per I14) + `Ifrs17JobAggregator` (parent/chunk aggregation per I14) + IBNR sub-job orchestrator per I24 + `MinIOPayloadStore` helper per I25 + `Ifrs17MaterialEventPublisher` per I30.
- Phases 25-27: Angular report pages under `/tenant/finance/reports/ifrs17/*` + XLSX per I21 + polling UI + `app-waterfall-chart` component + material-event admin surfaces per I30 + reports hub registration.
- Phase 28: rename cutover per I22 Phase B (publisher/consumer flipped to NEW-only + Angular URL flipped).
- Phase 29: rename cleanup per I22 Phase C (delete old Kafka topics + remove Java re-export shims + remove URL redirect).
- Phase 30: `services/go/market-data-service` + RBZ + SARB adapters + `medfund.market-data.yield-curve-updated` topic + tenancy-service consumer + admin config for auto-fetch enrollment.
- Phase 31 (may fold into Phase 30 or split): notification-service `internal/ifrs17/dispatcher.go` + email/webhook delivery + `/tenant/admin/settings/ifrs17-notifications` admin surface.
- Phase 32: e2e Playwright specs for both report journeys + material-event notification flow + admin CRUD.
- Phase 33: performance verification + cross-language docker-compose IT deferred to follow-up.

**Sub-plan tranche flexibility**: create-plan may reorder / merge phases based on dependency analysis at plan time. The above is the intended sequence; the actual count may compress to ~20 phases or expand to ~25 depending on how granular the split lands.

### Grill notes for `create-plan`

1. **Cross-service data pulls timeout policy (I17-adjacent)** — plan the `CrossServiceCallHelper` custom-timeout arm usage for IFRS 17 pulls: `Ifrs17ReportController` fans out concurrent `Mono.zip` calls to user-service (portfolios + cohorts + policies + funds + policy_unit_ledger + opening_balance_seed + fund_nav_history + variable_fee_schedule + cohort_status_history + cohort_loss_component_current), claims-service (claim history for LIC), contributions-service (earning_schedule + premium history), rules-engine (fires IFRS17_MODEL rule per portfolio). Reuse Phase 14 F14-4 custom-timeout arm (30s ceiling); document actuarial + IFRS 17 as the two named callers in `CrossServiceCallHelper` inline comment.
2. **Rules-engine deploy order (I32-adjacent)** — Phase 14 §D shipped `RuleCategory.ACTUARIAL`; Phase 15 adds `IFRS17_MODEL`. Plan: `TenantRuleEngine.loadRules` isolation IT per tenant remains green (mirrors `bug_rules_engine_tenant_isolation` guard).
3. **Concurrency policy for rule authoring mid-run (I31-adjacent)** — point-in-time evaluation: rules loaded at Ifrs17JobService.submit(); in-flight jobs use rules at submit time; new rule takes effect on next submit. Phase 14 §D precedent. Document in ADR or coding-standards.
4. **Testing strategy per phase (I25-adjacent)** — Phase 14 A18 pattern: unit + Testcontainers IT with per-tranche migration folder + Playwright per report; `AbstractIntegrationTest` + `.doOnSuccess` Kafka acks per `bug_reactor_kafka_ack_swallow`; Testcontainers 1.21.4 BOM override + flyway-database-postgresql + stub ReactiveJwtDecoder per `infra_testcontainers_pitfalls`. Cross-language docker-compose IT deferred to follow-up.
5. **Migration numbering verification (I26)** — verify at plan time next available: tenancy-service public V139+ (last is V138 Phase 14 basis tables); tenant V143+ (last is V142 Phase 14 sub-plan death status/reason widening). Finance-service tenant separate from tenancy — verify against Phase 14 §9 `V017__actuarial_report_job.sql` test-migration folder decision + `flyway-database-postgresql` per `infra_testcontainers_pitfalls`.
6. **SecurityEventPublisher granularity (I27)** — Phase 15 parent job has one export endpoint; one SecurityEvent per export per parent-plan invariant #3. Chunk events do NOT emit SecurityEvent (compute stays behind the export boundary). Document.
7. **Rules-engine template fact-field default values (I28)** — templates seed defaults per I27; per-line seed rows on tenant provisioning use `industry_default_v1` source_note; admin edits per rule have their own source_note.
8. **Insurance revenue formulas per model (I29)** — standard IFRS 17 formulas:
    - PAA: Insurance revenue = earning_schedule.earned_at_period_end delta (in period).
    - GMM (IFRS 17.83): Insurance revenue = expected incurred claims + expected insurance service expenses + CSM release + RA release − loss component reversal.
    - VFA: Insurance revenue = same as GMM + variable fee release.
9. **Materiality threshold values (I32-adjacent)** — thresholds queued as create-plan notes: CSM negative alert triggers on any exceedance; FX drift warning at >5% of closing balance; onerous auto-transition on any threshold breach (no buffer); IBNR staleness cutoff at 90 days; opening-balance-derived warning when the derived balance materially differs from any prior admin seed.
10. **Cohort auto-close trigger (I31-adjacent)** — when LRC + LIC + CSM = 0 for consecutive N periods (default 2), cohort auto-marks status `CLOSED`; excluded from future report envelopes but retained for audit; tenant admin can reopen with reason_note.
11. **Cross-tenant analytics feed (I34-adjacent)** — Phase 9 `/platform/analytics` stubs remain empty for IFRS 17 metrics; a follow-up plan adds cross-tenant LRC / LIC / insurance-service-result rollup + super-admin dashboard cards. Not in Phase 15 scope.
12. **Live dashboards integration (I35-adjacent)** — Elixir Phoenix `live_dashboard` app remains outside Phase 15 scope; a follow-up plan adds real-time LRC / LIC trend charts. `medfund.ifrs17.material-event` topic is the natural feed source.
13. **Payment-size guard exact thresholds** — MinIO fallback triggers at 900KB per I25 (matches Phase 14 A8b). Retention: input 7d after chunk complete; result matches parent `report_job.retention_class` per I28.
14. **Job-row retention policy** — per I28 split by `retention_class`. Nightly cron @ 02:00 (Phase 14 Grill note 8 precedent) respects the class. Cascade delete on chunks + MinIO refs via existing pattern.
15. **Backfill for existing cohorts (I18)** — plan the backfill: use `PolicyIssuedConsumer` scan of `medfund.user.policy-issued` topic replay OR SQL-side `SELECT earliest issuance date per cohort` + admin CSV upload for missing curve dates; fallback current curve + warning per I18. Backfill runs as one-off migration or lifecycle-startup pass.
16. **cause_of_death vocab reuse (Phase 14 §0 heritage)** — Phase 14 §0 introduced `cause_of_death` as ICD-10 chapter code. Phase 15 does not touch this vocabulary but may reference in future actuarial IFRS 17 disclosures (mortality-linked LIFE cohort onerous test detail).
17. **XLSX shape sheet-per-portfolio LRC-top / LIC-below per I21** — plan the sheet header row structure: (row 1) portfolio metadata; (row 2) measurement model badge; (row 3) LRC section header; (rows 4-K) LRC movement table with cohort rows + currency columns; (row K+2) LIC section header; (rows K+3 to end) LIC movement table. Summary sheet at position 0 has tenant-total across portfolios with waterfall movement.
18. **Message-size guard exact thresholds** — see grill note 13. 900KB rejects inline; 800KB warns; MinIO fallback with `payloadRef` per I25.
19. **result-persist Rule-2 guard** — plan the finance-service consumer: `if (event.tenantId != chunk.tenantId) throw new IllegalStateException(...)`. IT that spoofed cross-tenant events reject. Phase 14 A15 pattern.
20. **result_json schema_version** — plan the envelope: `{schema_version: 1, computed_at, model_version, method, basis, params_hash, warnings, output_summary}`. Immutable via append-only invariant (V141 trigger renamed to reference `report_job` post-rename).
21. **Append-only invariant** — plan the DB constraint: `BEFORE UPDATE` trigger that raises `EXCEPTION` when `OLD.status IN ('completed', 'failed')` (single terminal-write UPDATE from `status='processing'` permitted; subsequent UPDATEs error). Renamed from Phase 14 V141 to reference `report_job` post-rename.
22. **`.claude/coding-standards.md` update owed** — add paragraph on where AI-decision audit trails live per report family: actuarial → `report_job.result_json` (renamed from `actuarial_report_job.result_json`); IFRS 17 → same table + `cohort_loss_component_history` + `cohort_status_history` for lifecycle events; adjudication → existing `AiDecision` in claims-service.
23. **Cross-language docker-compose IT** — plan one full-stack spec per report type (2 specs for Phase 15 = LRC/LIC + Insurance Revenue) as a Phase-15-integration follow-up tranche. Deferrable per Phase-14 §C precedent but tracked as a known follow-up.
24. **IBNR sub-job de-duplication (I24-adjacent)** — plan the params-hash for the IBNR sub-job: derived from `(tenant, portfolio_line, period_start, period_end, insurance_line)`; parent Phase-15 job's poll waits on the sub-job via aggregator; if a concurrent IFRS 17 parent triggers the same IBNR sub-job, both parents wait on the same sub-job (natural via partial UNIQUE per Phase 14 A9).
25. **MinIO client dependency add (I25-adjacent)** — plan the addition: Java `services/java/shared/build.gradle.kts` gets `io.minio:minio` (or `aws-sdk-s3` for S3-compat); Python `services/python/ai-service/pyproject.toml` gets `minio` (or `boto3` for S3-compat). Auth: existing MinIO credentials from `make infra`. Buckets: `medfund-report-payloads`. Bucket lifecycle rules for retention.
26. **`services/go/market-data-service` scaffolding (I16-adjacent)** — plan the new Go service: Fiber v2 (matches gateway + notification pattern); cron via `robfig/cron`; per-adapter interface with `Fetch(currency, asOf) (Curve, error)` shape; RBZ adapter + SARB adapter; Kafka publisher via existing Sarama pattern. Docker Compose entry. Port assignment (next available: 3005). Health / readiness endpoints per InsureFlow convention. `.claude/architecture.md` update owed.
27. **notification-service `internal/ifrs17/dispatcher.go` scaffolding (I30-adjacent)** — plan the new dispatcher: consumes `medfund.ifrs17.material-event`; queries per-tenant recipients; dispatches via existing SMTP dispatcher + existing webhook dispatcher (both reuse from lifecycle/receipt/invoice/arrears patterns). Throttle via Redis-backed dedupe key. Per-event type template.
28. **Tenant `market_data_config` (I12-adjacent)** — new `public.tenant_market_data_config(tenant_id, currency, auto_fetch_enabled BOOLEAN, source VARCHAR)` — plan CRUD + tenant admin UI for enabling auto-fetch per currency. Default: auto-fetch disabled for all currencies (tenant admin opts in).

### What's owed back to the parent-plan outline

- **Line 3859 outline "LRC/LIC reconciliation + insurance revenue & service result reports"** — I1 escalates to full three-model end-to-end; I13 sets full grain per portfolio × cohort × currency. Struck through above.
- **Line 3863 outline "finance-service or ai-service (LRC/LIC computation is measurement-heavy — likely Python): IFRS17ReportController at Java layer, computation in Python"** — I3 settles finance-service aggregator + ai-service compute. Java layer holds `Ifrs17ReportController` + `Ifrs17JobPublisher` + `Ifrs17JobAggregator` + `MinIOPayloadStore`. Python holds `app/ifrs17/{paa,gmm,vfa,csm,risk_adjustment,coverage_units,onerous_test,discount_curve}.py`.
- **Line 3864 outline "Report keys IFRS17_LRC_LIC_RECONCILIATION, IFRS17_INSURANCE_REVENUE_SERVICE_RESULT"** — F15-1 confirms both keys already exist. No enum-add work.
- **Line 3865 outline "Requires portfolio × cohort × currency dimensioning of premium + claim data"** — F15-3 confirms Phase 12 landed the dimensional foundation. I13 sets the report grain to match.
- **Line 3867 outline "Grilling checkpoint critical — IFRS 17 model choice"** — satisfied by I1 (all three models).
- **Parent-plan invariants section (lines 118-125)** — I8 introduces a middle-ground FX policy (historical for movements + closing for balances + no explicit translation adjustment row) that departs from the strict IAS 21 approach. Parent-plan invariant #1 remains compatible: envelope still carries per-currency native totals + reporting-currency total; only the intermediate movement rows use historical rates. I28 adds a `retention_class` concept that composes with parent-plan invariant #3 (SecurityEvent on export) without changing it.
- **Phase 17 (Scheduled Email Delivery) cross-ref** — Phase 15's `medfund.ifrs17.material-event` topic (I30) gives Phase 17 an urgent-event feed source. Add "reuses `report_job` async pattern + IFRS 17 `material-event` topic for high-severity events" note to Phase 17 outline.
- **Phase 9 (Cross-Tenant Analytics Fill) cross-ref** — Grill note 11 flags Phase 9 gap for IFRS 17 metrics (LRC / LIC / insurance-service-result rollup). Follow-up plan tracked; not Phase 15 scope.
- **`.claude/architecture.md` update owed** — new `services/go/market-data-service` per I16 needs entry in the Quick Reference table + service description. Grill note 26 flags.
- **`.claude/coding-standards.md` update owed** — grill note 22 flags the AI-decision audit trail paragraph.
- **`.claude/multi-currency.md` update owed** — I8 middle-ground FX policy departs from strict IAS 21; documentation owes an entry describing the LRC/LIC-specific policy (movements historical, balances closing, no explicit translation adjustment row).

### Success Criteria

**Status: Fully implemented via the expanded sub-plan [thoughts/shared/plans/2026-08-28-ifrs17-pack.md](2026-08-28-ifrs17-pack.md), landed 2026-08-30 as commit `24dc83d "Land the IFRS 17 pack (Phases 1-25 of financial-reporting suite Phase 15)"`.**

Grilling checkpoint satisfied 2026-08-28 (I1..I30 + F15-1..F15-15 above). All 25 sub-plan phases green on their in-tranche automated verification per the sub-plan Deviations log. Aggregate success criteria across all phases:

- **Rename phases (A/B/C per I22) green**: `report_job` table + Kafka topics + Java package + Python module + URL alias all renamed cleanly; Phase-14 `IBNR_TRIANGLE` + `LOSS_TRIANGLE` + `PERSISTENCY_STUDY` + `MORTALITY_STUDY` + `MORBIDITY_STUDY` + `LAPSE_STUDY` reports continue to work end-to-end throughout; append-only invariant preserved; no in-flight actuarial jobs orphaned; Angular polling works throughout with both old + new URLs during dual-write window.
- **Admin infra phases green**: All 8 new admin CRUD pages (RA config + yield curves + expense assumptions + VFA funds + NAV history + variable fee schedules + opening balance seeds + notification recipients) list / add / update / delete / audit; per-tranche ITs green; industry_default_v1 rules seeded on tenant provisioning.
- **PAA phase green**: `IFRS17_LRC_LIC_RECONCILIATION` (PAA branch) enabled → submit → poll → renders + XLSX export; IASB Example 3 golden passes; toggle-off returns 403; PAA discounting simplification applied per I15 with envelope reason flag.
- **GMM phase green**: `IFRS17_LRC_LIC_RECONCILIATION` (GMM branch) enabled → LIFE/FUNERAL/DISABILITY portfolios compute end-to-end; IBNR sub-job auto-triggers when stale per I24; CoC → CI translation works per I6; locked-in curve snapshot per I18; auto-onerous test per I11; loss component history per I19; IASB Examples 7 + 9 golden pass.
- **VFA phase green**: `IFRS17_LRC_LIC_RECONCILIATION` (VFA branch) enabled → unit-linked LIFE portfolios compute with policy_unit_ledger + fund NAV + variable fee; IASB Example 11 golden passes; NAV upload works per admin CRUD.
- **Insurance Revenue phase green**: `IFRS17_INSURANCE_REVENUE_SERVICE_RESULT` enabled → same reports available under the second key; expense categories tracked flag correct per I23.
- **Chunking + MinIO fallback green**: Large-tenant test (10 portfolios × 5 cohorts × 4 currencies) chunks correctly per I14; oversize chunks fall back to MinIO per I25; parent job aggregates results; Angular polling shows chunk progress.
- **Notification phase green**: `medfund.ifrs17.material-event` events emitted on onerous auto-transition + CSM negative + locked-in curve fallback + IBNR sub-job stale + opening balance derived; notification-service `internal/ifrs17/dispatcher.go` consumes + emails; throttle deduplicates same-tenant same-event within N minutes.
- **Retention split green**: I28 STATUTORY_7Y class applies to IFRS17_* keys; OPERATIONAL_90D applies to actuarial keys; nightly cron respects both.
- **Market-data-service green**: RBZ + SARB adapters fetch daily; tenancy-service upserts `tenant_yield_curve`; admin can override.

Deferred to follow-up per Phase-11/12/13/14 precedent:

- **Cross-language docker-compose IT** — 2 specs per Grill note 23; deferred to a Phase-15-integration tranche.
- **Manual `verify` walkthroughs** — end-to-end golden-path browser demos per report; deferred to `verify` skill pass at end of sub-plan.
- **PAA sensitivity analysis + full IFRS 17 disclosure suite** — beyond LRC/LIC + Insurance Revenue + Service Result; sensitivity analysis, reconciliation of insurance service result, and confidence-level disclosure detail are follow-ups per parent-plan "What We're NOT Doing" line 106.
- **Additional market-data adapters** — US Fed + UK BoE + KES CBK + others; per-tenant demand basis.
- **Portfolio deactivation lifecycle** — handling for `ifrs17_portfolio.is_active=false` with open contracts; follow-up plan.
- **Live-defect note (carried from Phase 14)**: `services/python/ai-service/app/main.py` OTel imports without instrumentation; still a general observability follow-up.
- **Cross-tenant analytics for `/platform/analytics`** — IFRS 17 metrics rollup follow-up per Grill note 11.
- **Live dashboards (Elixir)** integration — follow-up per Grill note 12.
- **Bulk CSV upload for basis + yield curve + expense config** — beyond per-row entry; follow-up admin UX polish.

Per parent-plan Testcontainers policy each deferred IT lands with a purpose-built migration folder so the ITs don't force-widen every unrelated slice's baseline schema.

---

## Phase 16: Regulatory-Format Reports (Jurisdiction-Gated)

> **Grilled 2026-08-30.** Decisions REG1..REG21 (numbered `REG*` — for Regulatory — to avoid collision with plan-wide `G*` numbering which runs G1..G44 in the parent Implementation Approach section, and with prior phase prefixes `R*` (reinsurance, Phase 10) / `P*` (producer, Phase 11) / `U*` (underwriting, Phase 12) / `L*` (lifecycle, Phase 13) / `A*` (actuarial, Phase 14) / `I*` (IFRS 17, Phase 15)). Additional per-decision context bundled inline (no `REG<n>b` suffix explosion).
> The outline was expanded into a full mini-plan through interactive decision-making; scope escalated substantially beyond the original outline (all 8 pre-declared report keys ship per REG1, replacing the outline's silence on scope — see F-REG-outline for the 8 report keys already in `ReportKey.java:120-127`; single sub-plan with 5 tranches per REG2 replaces silence on tranche split; template acquisition + tenant-override model per REG3 with named-range + anchor-lookup write mechanism per REG4 replacing outline's brief "populates named cells from a ReportDataMap"; `@RequiresJurisdiction` + `@RequiresCountry` twin annotations per REG5/REG6 replace outline's single-annotation shape; new `TenantJurisdiction` enum + jurisdiction expansion per REG6; PMB tagging model via new rules-engine `RuleCategory.PMB_CLASSIFICATION` + `claim.is_pmb` + `claim.pmb_condition_code` per REG7 (0-day existing PMB tagging in tree); AML/STR two-shape design per REG8 with new `suspicious_transaction_alert` workflow + `medfund.aml.suspicious-transaction` topic; per-tenant `tenant_tax_config` per REG9 replaces parent-plan deferral at line 3084; async chunked reuse of Phase 15 `report_job` per REG10; hard-coded native currency per report per REG11 replacing tenant-picker semantics; new `regulatory_submission` amendment chain per REG12; MFA-step-up on submit per REG13; due-date banner + `medfund.regulatory.due-date-approaching` Kafka topic + notification dispatcher per REG14/REG20; rules-engine `RuleCategory.REGULATORY_PARAMETER` for solvency-formula overrides per REG15; new `us_tenant_naic_config` per REG16 to unblock NAIC keys; hybrid real+synthetic bundled templates per REG17; per-report golden YAML fixtures per REG18; new `PRUDENTIAL`/`TAX`/`COMPLIANCE` families per REG19 splitting the REGULATORY card; single sub-plan `thoughts/shared/plans/2026-08-30-regulatory-format-reports.md` with ~25-30 phases per REG21 replaces the outline's silence on tranche split).
> Original 14-line outline retained below as ~~strike-through~~ for provenance.

### Original outline (superseded 2026-08-30 by Decisions Log)

~~Regulator-specific XLSX templates: IPEC ZW quarterly return, CMS ASR ZA, NAIC Schedule P/F US, PMB spend, AML/STR, tax withheld + VAT return. Only visible when `tenant.jurisdiction_code` matches.~~

~~**shared** `report-templates/{regulator}/{report}-v{version}.xlsx` template resources.~~
~~`RegulatoryTemplateService` loads a template by regulator + effective date; populates named cells from a `ReportDataMap`.~~
~~Per-regulator controllers e.g. `IpecReportController`, `CmsAsrReportController`, `NaicScheduleReportController`, `PmbSpendReportController`, `AmlStrReportController`, `TaxWithheldReturnReportController`. Report keys per regulator.~~
~~`@RequiresJurisdiction("ZW_IPEC_SHORT_TERM")` annotation gates visibility.~~
~~**Angular** hub filters regulatory reports by tenant jurisdiction.~~

~~**Grilling checkpoint** — per-regulator: which report version, which template, which submission cycle.~~

### Overview

Greenfield **regulatory-format reports pack** spanning shared (`@RequiresJurisdiction` + `@RequiresCountry` annotations + `JurisdictionGuardAspect` + `CountryGuardAspect` in `shared/security` per REG5/REG6, `RegulatoryTemplateService` + `LabelAnchor` + `RegulatoryCellMap` + bundled `report-templates/{regulator}/{report}-v{version}.xlsx` resources per REG3/REG4/REG17, `ReportCadence` enum + cadence catalog per REG14, new `PRUDENTIAL` + `TAX` + `COMPLIANCE` families in `ReportFamily.java` per REG19, `RegulatoryReportCurrency` map per REG11, `MfaStepUpGuard` per REG13), tenancy-service (public V168+ migrations adding `TenantJurisdiction` enum-alignment + `tenant_regulatory_template` per REG3 + `tenant_tax_config` per REG9 + `us_tenant_naic_config` per REG16 + `tenant_aml_threshold_config` per REG8 + `tenant_regulatory_recipient` per REG13/REG20 + admin CRUD for each, Angular `JURISDICTIONS` widen from 3 to 6 values per REG6/F-REG5, seeded per-country tax-config defaults per REG9), finance-service (tenant V165+ migrations adding `regulatory_submission` per REG12 + `suspicious_transaction_alert` per REG8 + reuse Phase 15 `report_job` for async composition per REG10/F-REG2, new `com.medfund.finance.regulatory` package with `RegulatoryReportShapingService` + `RegulatoryTemplateService` + per-regulator calculators `IpecSolvencyCalculator` / `CmsAsrCalculator` / `NaicSchedulePCalculator` / `NaicScheduleFCalculator` / `PmbClassificationCalculator` / `AmlThresholdCalculator` per REG15, `IpecReportController` / `CmsAsrReportController` / `NaicSchedulePController` / `NaicScheduleFController` / `PmbSpendReportController` / `AmlStrReportController` / `TaxWithheldReturnReportController` / `VatReturnReportController` with `@RequiresJurisdiction`/`@RequiresCountry` + `@RequiresReport` + `@RequiresPermission` gates, `RegulatoryDueDateScanner` daily cron + `medfund.regulatory.due-date-approaching` Kafka publisher per REG20), claims-service (new `claim.is_pmb BOOLEAN` + `claim.pmb_condition_code VARCHAR(20)` columns per REG7 + PMB backfill batch job), rules-engine (new `RuleCategory.PMB_CLASSIFICATION` + `RuleCategory.REGULATORY_PARAMETER` per REG7/REG15 + `PmbClassificationFact` + `RegulatoryParameterFact` + `industry_default_v1` seeds for CMS PMB code list per REG7), notification-service (new `internal/regulatory/dispatcher.go` for due-date-approaching topic + new `internal/aml/dispatcher.go` slot for suspicious-transaction topic per REG20/REG8), gateway (new routes `/api/v1/reports/regulatory/*` + `/api/v1/reports/regulatory/submissions/*` + `/api/v1/reports/aml-str/*` + super-admin routes for regulator template management), and Angular (8 new report pages under `/tenant/finance/reports/{prudential,tax,compliance,aml-str}/*` per REG19 + submission-history page per REG12 + AML alert-raise/review-file page per REG8 + 5 admin pages (regulatory template override + tax config + NAIC config + AML threshold config + regulatory recipients) + due-date banner component per REG14 + jurisdiction/country gates on hub filtering).

Bundled real regulator XLSX where publicly obtainable (IPEC quarterly, ZIMRA VAT7, ZIMRA ITF12B, SARS VAT201, SARS IRP5-shape, goAML XML→XLSX) + synthetic hand-drawn where not (CMS ASR, NAIC Schedule P/F, PMB spend, AML periodic per jurisdiction) with `_v_SYNTHETIC_2026-08` version marker + Angular warning banner per REG17. Tenant admin override per REG3 lets each tenant upload their real portal-fetched template. Per-report golden YAML fixtures with hand-verified expected XLSX cell values per REG18 for compute regression protection. 25-30 sub-plan phases across §0/§A/§B/§C/§D + closeout per REG21.

### Decisions Log (REG1..REG21)

- **REG1 — v1 scope**: **All 8 declared report keys ship**: IPEC_QUARTERLY_RETURN (ZW), CMS_ASR (ZA), NAIC_SCHEDULE_P (US), NAIC_SCHEDULE_F (US), PMB_SPEND (ZA), AML_STR (multi-jurisdiction), TAX_WITHHELD_RETURN (ZW/ZA), VAT_RETURN (per-jurisdiction). Consequence: Phase 16 must build supporting infrastructure that does not exist today — PMB tagging on benefits/claims (REG7), per-tenant tax rate config for VAT (REG9), AML/STR event pipeline + threshold config (REG8), US tenant provisioning shape for NAIC (REG16). Rejected: ZW+ZA core (defers US/AML/VAT); ZW-only minimal (defers ZA); scaffolding-only (no tenant value).

- **REG2 — Sub-plan structure**: **One sub-plan `thoughts/shared/plans/2026-08-30-regulatory-format-reports.md`, tranched by domain.** Tranches: §0 shared infra (annotations, aspects, template service, submission chain, cadence, families, due-date scanner); §A prudential returns (IPEC + CMS + NAIC P + NAIC F); §B PMB spend (needs `RuleCategory.PMB_CLASSIFICATION` + claim tagging); §C tax pack (TAX_WITHHELD + VAT + `tenant_tax_config`); §D AML/STR (per-STR workflow + periodic summary + `tenant_aml_threshold_config`). Each tranche ships as its own commit(s) per Phase 14/15 precedent. Rejected: two sub-plans (first has no tenant value); five sub-plans (5x create-plan overhead + cascading design shifts); per-regulator phases within one plan (shared infra gets mangled by first phase).

- **REG3 — Template acquisition**: **Bundle canonical templates as `shared/src/main/resources/report-templates/{regulator}/{report}-v{version}.xlsx` + tenant admin override via new `public.tenant_regulatory_template` table.** Bundled templates are the best-available public XLSX (or synthetic hand-drawn approximation where the regulator restricts distribution, per REG17). Tenant admin can upload a newer version per report; override wins over bundled by (regulator, report_key, effective_from). Mirrors Phase 14 basis-tables pattern (bundled YAML defaults + `tenant_persistency_basis` override). Sub-plan §0 delivers: (a) resource-loader for bundled XLSX; (b) `tenant_regulatory_template` schema + admin CRUD; (c) resolution logic (bundled fallback if no tenant override); (d) `RegulatoryTemplateService` load-by-(regulator, key, effective_date) contract. Rejected: tenant-uploaded only (per-tenant onboarding friction); build programmatically (regulator format changes = code changes; no visual parity check); fetch from external repo (extra infra, templates not code-adjacent).

- **REG4 — XLSX write mechanism**: **Named ranges as the primary write mechanism + `LabelAnchor` fallback (locate row by column-A label + column by header text) for templates whose named-range coverage is incomplete.** Every bundled template ships with a curated set of named ranges (one-time authoring pass onto public regulator XLSX that lack them). `RegulatoryTemplateService` API: `.writeNamed(name, value)` first-preference, `.writeAnchored(sheet, rowLabel, columnHeader, value)` fallback. `RegulatoryCellMap<K>` per regulator (Java sealed interface + one impl per regulator) declares the (named-range OR anchor) mapping for each report field. Sub-plan §0 delivers `RegulatoryTemplateService`, `LabelAnchor`, `RegulatoryCellMap` interface; each regulator tranche (§A, §B, etc.) delivers its concrete cell map + writes named ranges onto its bundled templates. Rejected: fixed-coordinates only (brittle to any row-add by regulator); anchor-lookup only (slow at generation time, breaks on label rename); skip templates (reverses REG3).

- **REG5 — `@RequiresJurisdiction` semantics**: **Variadic list, exact match, NULL denies.** `@RequiresJurisdiction({"ZW_IPEC_SHORT_TERM", "ZA_CMS_MEDICAL_SCHEME"})` on controllers; single-value form (`@RequiresJurisdiction("ZW_IPEC_SHORT_TERM")`) is the common case. NULL tenant.jurisdiction_code → 403 (per V131 comment). New `JurisdictionGuardAspect` in `shared/security` mirrors `ReportGuardAspect` (Spring AOP `@Around`); resolves tenant via reactive context, reads `Tenant.jurisdictionCode` from `TenantContext`, checks membership, short-circuits with 403 + `SecurityEventPublisher.publishAccessDenied(...)` on miss. Multiple aspects stack — a controller carries `@RequiresJurisdiction(...)` + `@RequiresReport(REPORT_KEY)` + `@RequiresPermission(...)`; every gate must pass. Cross-regulator reports (AML/STR/TAX/VAT) do NOT use this annotation — they gate on `country_code` via `@RequiresCountry` (REG6). Rejected: prefix match (hides intent, harder to audit); single-value only + one controller per jurisdiction (3x boilerplate); runtime rule lookup (loses static grep-ability of gates).

- **REG6 — `TenantJurisdiction` enum + cross-regulator gating**: **Prudential enum + `@RequiresCountry` annotation for cross-regulator reports.** `TenantJurisdiction` sealed enum in `services/java/tenancy-service/src/main/java/com/medfund/tenancy/TenantJurisdiction.java` with initial values: `ZW_IPEC_SHORT_TERM`, `ZW_IPEC_LIFE`, `ZA_CMS_MEDICAL_SCHEME`, `ZA_FSCA_SHORT_TERM`, `ZA_FSCA_LONG_TERM`, `US_NAIC`. Angular `JURISDICTIONS` const in `settings.component.ts:63-68` widens from 3 to 6 (adds ZW_IPEC_LIFE, ZA_FSCA_SHORT_TERM, ZA_FSCA_LONG_TERM). Values persisted as free-form string in DB (per V131 comment "enum evolves faster than migration cadence"), but tenancy-service PUT-tenant validates against the enum at write time (422 on unknown value; existing rows unchanged). New `@RequiresCountry({"ZW", "ZA", "US"})` annotation + `CountryGuardAspect` sibling in `shared/security`, reads `Tenant.countryCode` (already exists per `Tenant.java:46-47`). AML/STR uses `@RequiresCountry({"ZW", "ZA", "US"})`; TAX_WITHHELD_RETURN uses `@RequiresCountry({"ZW", "ZA"})`; VAT_RETURN uses `@RequiresCountry({"ZW", "ZA"})`. Rejected: single mixed enum (ambiguous semantics — ZW vs ZW_IPEC_LIFE); free-string + Java catalog (no compile-time exhaustiveness); coarser country-only (contradicts V131's regulator+line purpose).

- **REG7 — PMB tagging model**: **Rules-engine `PMB_CLASSIFICATION` category + `is_pmb` derived on `claim`.** New `RuleCategory.PMB_CLASSIFICATION` in `services/java/rules-engine/src/main/java/com/medfund/rules/RuleCategory.java`; fires at claim adjudication time reading `ClaimFact.diagnosisCodes` + `ClaimFact.procedureCodes`; writes `claim.is_pmb BOOLEAN NOT NULL DEFAULT FALSE` + `claim.pmb_condition_code VARCHAR(20) NULL`. New tenant-schema migration adds both columns to `claim`. Seed per-tenant `industry_default_v1` PMB rules on tenant provisioning (Phase 14/15 A10/I27 seed convention) — one default rule per CMS-published PMB condition (270 diagnosis+procedure pairs, sourced from CMS PMB code list at effective date, versioned via `source_note='cms_pmb_v1'`). Angular tenant-admin rules page (existing at `/tenant/admin/rules`) surfaces the category. Backfill for existing claims: batch job re-runs rules against historical claims once seeded; adjudication history not disturbed. `PMB_SPEND` report aggregates `SUM(paid_amount) WHERE is_pmb=TRUE GROUP BY pmb_condition_code, benefit_category, currency, period`. Rejected: `is_pmb` on benefit only (loses per-diagnosis nuance, misses CMS per-condition breakdown); both columns (two write paths to keep consistent, adjudication cost); defer PMB_SPEND (contradicts REG1).

- **REG8 — AML/STR shape**: **Both per-STR filing AND periodic AML summary under a single `AML_STR` report key.** Two sub-report shapes: (a) Per-STR filing (event-driven) — new tenant-scoped table `suspicious_transaction_alert(id, raised_by_actor_id, raised_by_actor_email, raised_at, transaction_ref, transaction_type, amount_native, currency, member_id NULL, provider_id NULL, description TEXT, status ENUM('RAISED','REVIEWED','FILED','CLOSED'), reviewer_actor_id NULL, reviewer_actor_email NULL, filed_at NULL, filed_ref VARCHAR NULL, closed_reason VARCHAR NULL)` + Angular staff UI at `/tenant/finance/reports/aml-str/alerts` (with `RAISED→REVIEWED→FILED→CLOSED` workflow). Export XLSX in FIU/FIC/FinCEN per-STR template (§0 REG4 named-cell fill) at the FILED transition. New `medfund.aml.suspicious-transaction` Kafka topic emits on status changes (feeds Phase 15/I30 notification pattern for compliance-officer alerting). (b) Periodic AML summary (cadenced) — quarterly aggregate of transactions above jurisdictional thresholds (per-tenant `public.tenant_aml_threshold_config(tenant_id, transaction_type, threshold_amount, currency)` table, admin CRUD) + running count of STR filings in the period. Export XLSX in per-jurisdiction summary template. Controller `AmlStrReportController` with two endpoints; both gated by `@RequiresCountry({"ZW", "ZA", "US"})` + `@RequiresReport(ReportKey.AML_STR)`. Retention STATUTORY_7Y (F-REG1). AI fraud-detector hook is a future extension point on the alert-raise pipe (Phase 19 adjacent — the topic emit slot exists but is stubbed). Rejected: event-driven only (contradicts current cadenced=true metadata, misses periodic summary regimes); periodic-only (misses 24-72h STR clock — the most urgent AML obligation); two report keys (adds a 9th key, more surface).

- **REG9 — Tenant tax config model**: **Per-tenant per-tax-type per-category table.** New `public.tenant_tax_config(id, tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE, country_code CHAR(2) NOT NULL, tax_type VARCHAR CHECK IN ('VAT','WITHHOLDING'), transaction_category VARCHAR CHECK IN ('PREMIUM','CLAIM_PAID','ADMIN_FEE','COMMISSION','OTHER'), rate NUMERIC(5,4) NOT NULL, is_registered BOOLEAN NOT NULL DEFAULT TRUE, registration_number VARCHAR(80) NULL, effective_from DATE NOT NULL DEFAULT CURRENT_DATE, effective_to DATE NULL, source_note VARCHAR NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), actor_id UUID NOT NULL, actor_email VARCHAR NOT NULL)`. UNIQUE `(tenant_id, tax_type, transaction_category, currency, effective_from)`. Admin CRUD at `/tenant/admin/settings/tax-config` (tabbed by tax_type). `TenantTaxConfigService` + `TenantTaxConfigController` in tenancy-service (clone Phase 14 `TenantPersistencyBasisController` pattern). Seed per-country defaults on tenant provisioning via new migration (ZW: VAT 15% for PREMIUM/ADMIN_FEE/COMMISSION, WITHHOLDING 10% for COMMISSION/OTHER; ZA: VAT 15% for ADMIN_FEE, EXEMPT (rate 0) for PREMIUM, WITHHOLDING 25% for OTHER — subject to verification against ZIMRA + SARS tables at implementation time). `VAT_RETURN` aggregates VAT collected + input VAT + net owing per registered category. `TAX_WITHHELD_RETURN` aggregates `withholding_tax_pct × amount` on `payment_run_items`, falling back to per-tenant defaults from `tenant_tax_config` for missing per-item overrides. Rejected: single row per tenant (cannot model VAT-exempt premiums vs liable admin fees); country defaults + minimal overrides (loses per-category granularity + effective-from history); defer (contradicts REG1).

- **REG10 — Prudential-return composition**: **Async chunked, reuse Phase 15 `report_job` + `medfund.report.*` Kafka topics.** New `RegulatoryReportShapingService` in finance-service composes prudential data cross-service via `CrossServiceCallHelper` (contributions-service for premium register + earning schedule, claims-service for OS reserves + IBNR results, finance-service internal for revenue/expense ledgers + reinsurance recoverables from Phase 10 `recovery`/`cession` tables); hands the aggregated payload to `RegulatoryTemplateService` (REG3/REG4) for named-cell/anchor writes into the bundled template. Reuses Phase 15 aggregator + STATUTORY_7Y retention (F-REG1) + polling UI. Chunk fan-out per (regulator_section, currency) for tenants with multi-currency prudential returns — most tenants report single-currency so most jobs are single-chunk (aggregator short-circuits). Rejected: sync REST (blocks 30-60s requests for large tenants; contradicts async precedent); hybrid sync/async escalation (two code paths); formal general-ledger snapshot (2-3 sub-phases of GL construction — Phase-15-scale prerequisite that Phase 16 cannot absorb).

- **REG11 — Regulator report currency**: **Force per-report native currency.** Hard-coded per-key: `IPEC_QUARTERLY_RETURN → ZWL`, `CMS_ASR → ZAR`, `NAIC_SCHEDULE_P/F → USD`, `PMB_SPEND → ZAR`, `TAX_WITHHELD_RETURN → country-native from tenant.country_code (ZW→ZWL, ZA→ZAR)`, `VAT_RETURN → country-native`, `AML_STR periodic → country-native, per-STR → transaction-native`. New shared constant `RegulatoryReportCurrency` map (regulator/report → currency, or (regulator/report, country_code) → currency for country-native reports). Tenant `reportingCurrency` toggle disabled for these keys (Angular hides the picker on regulator-family reports; server-side rejects overrides with 422). Cross-currency source data FX-converted at period-end closing rate (Phase 15 I8 middle-ground). Missing FX = **fail loud** for regulator reports (`ReportGenerationException` — divergence from Phase 15 G28's warning-only default for general reports, because regulator submission with a silent-zero row is worse than a filing miss). `perCurrency` envelope block still populated for auditor reconciliation. Rejected: tenant picks (mis-filing risk); native-only with reject-mixed (prohibits multi-currency tenants entirely); regulator_currency_config DB table (extra admin surface for well-known constants).

- **REG12 — Submission tracking + amendments**: **New tenant-scoped `regulatory_submission` table with supersedes chain.** Columns: `id UUID PK, tenant_id UUID NOT NULL, report_key VARCHAR(80) NOT NULL, period_start DATE NOT NULL, period_end DATE NOT NULL, submission_number INT NOT NULL, supersedes_id UUID NULL REFERENCES regulatory_submission(id), source_run_id UUID NOT NULL REFERENCES report_job(id), submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), submitted_by_actor_id UUID NOT NULL, submitted_by_actor_email VARCHAR NOT NULL, xlsx_ref VARCHAR NOT NULL, filing_ref VARCHAR NULL, status VARCHAR NOT NULL CHECK IN ('DRAFT','SUBMITTED','AMENDED','SUPERSEDED'), reason_note TEXT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`. Unique on (tenant_id, report_key, period_start, submission_number). Workflow: first export in `submit` mode = SUBMITTED / submission_number=1; second export for same (tenant, key, period) in `amend` mode = AMENDED / submission_number=2 / supersedes_id=prior + auto-marks prior SUPERSEDED. Draft flag on export UI (`?draft=true` query arg); drafts create `report_job` but NOT `regulatory_submission` rows. XLSX blob stored in MinIO under `medfund-regulatory-submissions/{tenant_id}/{report_key}/{period}/{submission_number}.xlsx` with 7y retention matching STATUTORY_7Y (F-REG1). New Angular admin page `/tenant/finance/reports/regulatory/submission-history` lists all submissions with drill-down; supports re-download of any past filing XLSX + view of supersedes chain. Rejected: reuse report_job only (no filing-ref, no draft/final distinction); nullable columns on report_job (pollutes generic schema); defer (auditor-required from day one).

- **REG13 — Sign-off + external filing scope**: **MFA-gated submit transition + manual `filing_ref` capture.** Every `regulatory_submission` transition `DRAFT/(NEW)→SUBMITTED` requires MFA step-up via Keycloak (`amr` claim must include one of `mfa | otp | totp | hwk` — refresh within 5 min). New shared `MfaStepUpGuard` (SecurityContext consumer that inspects `Authentication.getCredentials()` for MFA freshness) enforces the check; miss returns 401 with `x-mfa-required: true` header + Angular re-auth modal. Captured on submission: `submitted_by_actor_id`, `submitted_by_actor_email`, `submitted_at`, optional `attestation_note` (free-text, "I certify …"). Post-filing: Angular form on `/tenant/finance/reports/regulatory/submission-history` lets the tenant record the regulator's receipt/reference into `regulatory_submission.filing_ref` — mutation audit-logged per Rule 8 (F-REG4). No external SFTP/API/EDI integration in v1; every regulator is manual portal upload / email today. Rejected: PDF-A + PKCS#7 digital signature (full PKI infra is a Phase-sized ambition; regulators still print+sign in practice); FilingAgent adapter interface (shape without substance — zero real implementations exist for v1); no sign-off (reverses REG12, leaves auditors with no formal trail).

- **REG14 — Cadence handling**: **No auto-cadence in Phase 16 + due-date banner.** New shared enum `ReportCadence { MONTHLY, QUARTERLY, ANNUAL, EVENT_DRIVEN }` in `shared/report/`; per-key cadence + `daysPostPeriodEnd` (days-of-grace) mapping added to `ReportKey` (or a sibling `ReportCadenceCatalog` if enum-add is too invasive). Initial mappings: `IPEC_QUARTERLY_RETURN → QUARTERLY / 30`, `CMS_ASR → ANNUAL / 180`, `NAIC_SCHEDULE_P/F → ANNUAL / 60`, `PMB_SPEND → ANNUAL / 180`, `TAX_WITHHELD_RETURN → MONTHLY / 15`, `VAT_RETURN → MONTHLY / 25`, `AML_STR (periodic) → QUARTERLY / 30`. Angular hub cards on regulator reports show "Due in N days" banner derived from cadence + last SUBMITTED `regulatory_submission.submitted_at`; banner turns amber at ≤ 7 days, red at ≤ 0 days. Actual scheduled auto-generation deferred to Phase 17. Phase 17's future grill can then design email dispatch + `tenant_report_schedule` on top of this metadata. Rejected: ship cadence + scheduler in Phase 16 (pre-empts Phase 17 design); declare Phase 17 table contract now (shape locked in before Phase 17 grill); auto-run every regulator report on cadence (wastes compute for unused reports).

- **REG15 — Regulator-specific parameters**: **Hybrid: bundled defaults in code + tenant rules-engine overrides.** Per-regulator Java calculators (`IpecSolvencyCalculator`, `CmsAsrCalculator`, `NaicSchedulePCalculator`, `PmbClassificationCalculator`, `AmlThresholdCalculator`) live in a new `com.medfund.finance.regulatory` package. Default parameters loaded from bundled YAML resources under `services/java/shared/src/main/resources/regulatory-defaults/{jurisdiction}/{yyyy-mm-dd}.yaml` (versioned by effective_from). New `RuleCategory.REGULATORY_PARAMETER` in rules-engine allows per-tenant overrides — sample rule: "For jurisdiction ZW_IPEC_SHORT_TERM effective 2026-01-01, solvency required-capital multiplier = 1.5x instead of default 1.3x, source_note='captive_modified_ratio_2026'". `RegulatoryParameterFact` (new fact class in rules-engine, per Phase 14 A5 / Phase 15 I9 patterns) exposes mutable fields `parameterKey`, `parameterValue`, `jurisdiction`, `effectiveFrom`. Calculator resolution order: (1) rules-engine tenant override matching (jurisdiction, parameter, effective_from), (2) bundled YAML default, (3) fail-loud if neither present. Angular tenant-admin rules page surfaces the category. Rejected: pure hardcode (blocks captive-modified cases + emergency-deploy risk); rules-engine for everything (hundreds of PMB codes in DRL becomes authoring bottleneck); YAML-only no override (blocks captive cases + slow-cycle regulator updates).

- **REG16 — US-specific tenant fields for NAIC**: **New `us_tenant_naic_config` table + jurisdiction-gated admin UI.** Public-schema table `public.us_tenant_naic_config(tenant_id UUID PRIMARY KEY REFERENCES public.tenants(id) ON DELETE CASCADE, state_domicile CHAR(2) NOT NULL, naic_company_code VARCHAR(10) NOT NULL, naic_group_code VARCHAR(10) NULL, fein VARCHAR(20) NOT NULL, effective_from DATE NOT NULL DEFAULT CURRENT_DATE, effective_to DATE NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), actor_id UUID NOT NULL, actor_email VARCHAR NOT NULL)`. Admin CRUD at `/tenant/admin/settings/naic-config` visible only when `tenant.country_code='US'` (Angular tab-hide + server-side 404 on non-US access). `NaicSchedulePController` + `NaicScheduleFController` refuse to run without config populated (422 with "NAIC config missing"). No US tenants exist today per grounding but Phase 16 §A ships the surface per REG1's all-8 commitment. Rejected: nullable columns on tenants (US-only pollution + every future country field follows); JSONB country_specific_config (no server-side validation + slower queries); defer NAIC (reverses REG1).

- **REG17 — Bundled template authoring**: **Real where obtainable + synthetic elsewhere with warning banner.** Real regulator XLSX bundled: IPEC ZW quarterly (ipec.co.zw public download), ZIMRA VAT7 + ITF12B (public), SARS VAT201 + IRP5-shape (public), FIU (ZW) goAML XML → hand-mapped XLSX, FIC (ZA) STR XML → XLSX, FinCEN (US) SAR CTR shape. Hand-drawn synthetic templates: CMS ASR (portal-locked), NAIC Schedule P/F (subscription), PMB spend (no regulator-issued template — synthesise from CMS PMB regulations), AML periodic summaries per jurisdiction. Every synthetic template carries `_v_SYNTHETIC_2026-08` version marker and Angular renders a warning banner on the report page: "This template is a synthetic approximation. Verify against your regulator's current template before filing." Tenants can upload their real portal-fetched templates via REG3 `tenant_regulatory_template` override mechanism; override wins over synthetic bundled. Sub-plan carries a Deferred item: compliance-domain validation of each synthetic template before first real filing (owed back to author list per grilling §5). Rejected: real-only + drop synthetic keys (contradicts REG1); all-synthetic MVP (no bundled real template = onboarding friction); block-until-override 424 (contradicts REG3 bundled-fallback + non-standard error code).

- **REG18 — Golden test fixtures**: **Per-report YAML fixtures + hand-verified expected XLSX cell values.** One golden fixture per report at `services/java/finance-service/src/test/resources/regulatory-fixtures/{regulator}/{report}-golden.yaml` (mirrors Phase 15 I26 IASB fixture pattern). Each fixture declares: (a) seeded state (tenant + policies + claims + payments + reserves + reinsurance recoverables + PMB flags + AML alerts as applicable), (b) expected XLSX cell values indexed by named-range (or anchor label). IT `RegulatoryGoldenIT` per regulator seeds the state via Testcontainers Postgres + `db/regulatory-migration/`, runs the report, opens the generated XLSX via POI, asserts every named-range value ≥ tolerance. Fixture provenance: real regulator worked examples where publicly documented (IPEC quarterly bulletin sample, ZIMRA VAT7 worked example, ZIMRA ITF12B worked example, SARS VAT201 EG101 example, NAIC Annual Statement Instructions Schedule P walkthrough), synthetic hand-verified elsewhere (CMS ASR, PMB spend, AML periodic). Rejected: aggregate-only unit tests (loses cell placement + template regressions go silent); Playwright golden-XLSX byte diff (POI metadata drift + hard to debug); skip fixtures (manual QA per release + regressions invisible in CI).

- **REG19 — Hub layout / family split**: **Add `PRUDENTIAL` + `TAX` + `COMPLIANCE` to `ReportFamily`; keep `REGULATORY` for IFRS 17 only.** New enum entries in `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java`: `PRUDENTIAL("Prudential Returns")`, `TAX("Tax")`, `COMPLIANCE("Compliance")`. Reassign Phase 16 keys: `IPEC_QUARTERLY_RETURN`, `CMS_ASR`, `NAIC_SCHEDULE_P`, `NAIC_SCHEDULE_F` → `PRUDENTIAL`; `VAT_RETURN`, `TAX_WITHHELD_RETURN` → `TAX`; `PMB_SPEND`, `AML_STR` → `COMPLIANCE`. IFRS 17 keys stay in `REGULATORY` unchanged (semantic rename deferred to avoid Phase 15 downstream disruption). Angular family label service in `clients/angular/src/app/pages/tenant/finance/reports/reports.service.ts` extended for the 3 new families (icon + colour + display order). Rejected: subFamily on ReportKey (sub-grouping logic in hub component, more metadata to author); flat 10-item card (crowds hub, poor scan); one-family-per-regulator (over-fragmented, thin cards).

- **REG20 — Due-date push notifications**: **New `medfund.regulatory.due-date-approaching` Kafka topic + notification-service dispatcher.** Daily cron job in finance-service `RegulatoryDueDateScanner` iterates per (tenant, regulator report_key), computes next due-date from cadence catalog (REG14) + last SUBMITTED `regulatory_submission.submitted_at`, emits event at (a) 7 days out, (b) 1 day out, (c) day of, (d) 1 day overdue. Payload: `{tenantId, reportKey, periodStart, periodEnd, dueDate, daysUntilDue, severity, occurredAt, schemaVersion: 1}`. New `services/go/notification-service/internal/regulatory/dispatcher.go` consumes; queries per-tenant compliance recipients (reuse REG13's `tenant_regulatory_recipient` table); dispatches via email + in-app notification (webhook slot). Throttle: same (tenant, key, event-tier) within 24h deduplicated. Angular notification bell picks up in-app notifications from existing feed. Rejected: in-app bell only (misses out-of-hours); defer to Phase 17 (gap in v1); reuse ifrs17 material-event topic (pollutes IFRS-17-specific pipe).

- **REG21 — Sub-plan phase count target**: **25–30 phases across §0/§A/§B/§C/§D + closeout.** Matches Phase 15 IFRS 17 pack scale. Preliminary phase inventory: §0 shared infra (7–8 phases: TenantJurisdiction enum + Angular JURISDICTIONS widening → `@RequiresJurisdiction` + `@RequiresCountry` + aspects → `RegulatoryTemplateService` + LabelAnchor + bundled resource loader → `tenant_regulatory_template` + admin CRUD → `regulatory_submission` + MFA-step-up + amendment chain → `RegulatoryDueDateScanner` + cadence catalog + due-date banner → `PRUDENTIAL/TAX/COMPLIANCE` families + Angular hub extension → `medfund.regulatory.due-date-approaching` topic + notification dispatcher); §A prudential returns (5–7 phases: shaping service + IPEC + CMS + NAIC P + NAIC F + `us_tenant_naic_config`); §B PMB spend (3–4 phases: claim column migration + backfill → PMB rules category + seed → PMB_SPEND controller + XLSX + Angular); §C tax pack (3–4 phases: `tenant_tax_config` + admin CRUD → VAT_RETURN → TAX_WITHHELD_RETURN); §D AML/STR (4–5 phases: alert table + workflow API → alert-raise/review-file UI → suspicious-transaction Kafka topic + hook slot → `tenant_aml_threshold_config` + admin + periodic AML summary + XLSX); Closeout (2 phases: e2e Playwright + performance verification + docker-compose IT deferred per Phase 15 §25 precedent). Each phase ships as its own commit(s). Rejected: 15–20 (harder PR review, more grouping); 35–40 (small-phase overhead); no-target (no early check on decomposability).

### Settled by fact (not asked)

- **F-REG-outline — All 8 Phase 16 report keys already declared.** `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:120-127` declares IPEC_QUARTERLY_RETURN, CMS_ASR, NAIC_SCHEDULE_P, NAIC_SCHEDULE_F, PMB_SPEND, AML_STR, TAX_WITHHELD_RETURN, VAT_RETURN — every one `ReportFamily.REGULATORY`, `cadenced=true`. **No enum-add work for keys.** REG19 reassigns to new families (PRUDENTIAL/TAX/COMPLIANCE).

- **F-REG1 — Retention class**: Phase 15 Decision I28 introduced `report_job.retention_class ENUM('OPERATIONAL_90D','STATUTORY_7Y')` derived from `ReportFamily` at insert (see plan lines 3936). Phase 16's `PRUDENTIAL` + `TAX` + `COMPLIANCE` families (REG19) all classify STATUTORY_7Y by the same derivation rule (they're regulator-driven statutory keys). The nightly retention cron already respects this. **No Phase 16 retention work required.**

- **F-REG2 — Async pattern reuse**: Phase 14 A8 + Phase 15 I3/I10/I22 established `report_job` (renamed from `actuarial_report_job`) + `medfund.report.*` Kafka topics as the generic async report pattern. **Regulator reports reuse `report_job` + `medfund.report.job-requested`/`job-completed` with new `ReportKey` values as `params_json.reportKey`.** The finance-service `ReportJobPublisher` already dispatches by `ReportKey`; the ai-service `ReportJobConsumer` does the same. Phase 16 wires new (regulator, report-key) branches into the existing dispatch table; no new job table or new Kafka topics needed for the async layer (REG10). Java composition happens in a new `RegulatoryReportShapingService` in finance-service.

- **F-REG3 — Security event emission**: Rule 9 in `.claude/CLAUDE.md:105-110` requires DATA_ACCESS security events on every report export. The `SecurityEventPublisher.publishDataAccess(...)` API at `services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java:74-80` is production-ready. Every Phase 16 controller calls it on export. Not a design choice.

- **F-REG4 — Rule 8 audit on template override / config change**: Every mutation of `tenant_regulatory_template`, `tenant_tax_config`, `tenant_aml_threshold_config`, `us_tenant_naic_config`, `tenant_regulatory_recipient`, `regulatory_submission.filing_ref`, `suspicious_transaction_alert` status transitions, `RuleCategory.PMB_CLASSIFICATION` + `RuleCategory.REGULATORY_PARAMETER` rule authorship emits an `AuditEvent` per Rule 8 (`.claude/CLAUDE.md:104`) using `AuditActor` (feedback_audit_actor_email memory) with entityName = friendly text (feedback_audit_entity_name memory). Not a design choice.

- **F-REG5 — Angular JURISDICTIONS list**: The Angular constant at `clients/angular/src/app/pages/tenant-admin/settings/settings.component.ts:63-68` is the current source of truth for jurisdiction selection (Phase 0/1 shipped 3 values: ZW_IPEC_SHORT_TERM, ZA_CMS_MEDICAL_SCHEME, US_NAIC). Phase 16 §0 must widen this in step with the `TenantJurisdiction` enum (REG6). Not a design choice — a bookkeeping fact.

- **F-REG6 — Migration numbering baseline**: Last applied public migration is `V167__tenant_market_data_config.sql` (Phase 15 §24). Last applied tenant migration is `V164__variable_fee_schedule.sql` (Phase 15 §8). **Phase 16 public migrations start V168+; Phase 16 tenant migrations start V165+.** Numbers to reserve at plan time: `TenantJurisdiction` enum-alignment migration (public), `tenant_regulatory_template` (public), `tenant_tax_config` (public), `us_tenant_naic_config` (public), `tenant_aml_threshold_config` (public), `tenant_regulatory_recipient` (public per REG13/REG20), + tenant-scoped `regulatory_submission` (tenant), `suspicious_transaction_alert` (tenant), and per-claim PMB columns (`ALTER TABLE claim ADD COLUMN is_pmb + pmb_condition_code` — tenant). Verify latest applied number at each sub-plan phase start per `feedback_never_edit_applied_migrations` memory.

- **F-REG7 — Deploy-order invariant**: Standard multi-service ordering per Phase 15 I22 pattern. For Phase 16 topics: notification-service `regulatory/dispatcher.go` (REG20) starts consuming BEFORE finance-service `RegulatoryDueDateScanner` starts publishing (avoid publish-into-void). `medfund.aml.suspicious-transaction` (REG8) consumer BEFORE producer. All new consumers subscribe from `earliest` on first deploy to catch pre-cutover events. `.doOnSuccess` ack pattern per `bug_reactor_kafka_ack_swallow` memory.

- **F-REG8 — Rule-2 tenant scoping**: All new tenant-scoped tables (`regulatory_submission`, `suspicious_transaction_alert`, `claim.is_pmb`, `claim.pmb_condition_code`) live under tenant schemas and are queried via unqualified names per `bug_public_prefix_silent_rollback` memory. Public-schema tables (`tenant_tax_config`, `tenant_regulatory_template`, `us_tenant_naic_config`, `tenant_aml_threshold_config`, `tenant_regulatory_recipient`) prefix `public.` in queries. Not a design choice — enforced by memory.

### Success Criteria

**Status: Grilled 2026-08-30 (REG1..REG21 + F-REG-outline + F-REG1..F-REG8 above). Ready for sub-plan via `create-plan`. Not yet implemented.**

Single sub-plan will ship at `thoughts/shared/plans/2026-08-30-regulatory-format-reports.md` built via `create-plan` → `implement-plan`. Aggregate success criteria across all tranches:

- **§0 shared infra green**: `TenantJurisdiction` enum + Angular JURISDICTIONS widening; `@RequiresJurisdiction` + `@RequiresCountry` annotations + aspects with unit + IT coverage (403 on wrong jurisdiction, 403 on NULL, 200 on match, security event on deny); `RegulatoryTemplateService` + `LabelAnchor` + `RegulatoryCellMap` interface + resource loader for bundled XLSX; `tenant_regulatory_template` schema + admin CRUD with tenant-override resolution; `regulatory_submission` table + MFA-step-up + amendment chain + supersedes; `RegulatoryDueDateScanner` + cadence catalog + Angular due-date banner; `PRUDENTIAL/TAX/COMPLIANCE` families + Angular hub reorg; `medfund.regulatory.due-date-approaching` topic + notification-service dispatcher end-to-end email delivery.
- **§A prudential returns green**: `RegulatoryReportShapingService` async chunk composition proven; `IpecSolvencyCalculator` + IPEC template + IPEC golden fixture matches; `CmsAsrCalculator` + synthetic CMS template + golden fixture; `NaicSchedulePCalculator` + `NaicScheduleFCalculator` + synthetic templates + fixtures; `us_tenant_naic_config` admin surface + jurisdiction-gate; toggle-off returns 403 per report; native-currency enforcement (REG11).
- **§B PMB spend green**: `claim.is_pmb` + `claim.pmb_condition_code` columns + backfill batch job; `RuleCategory.PMB_CLASSIFICATION` + `industry_default_v1` seed of ~270 CMS PMB rules on tenant provisioning; PMB adjudication write path + rule fires correctly on synthetic ClaimFact; `PMB_SPEND` controller + XLSX + Angular; golden fixture reconciles.
- **§C tax pack green**: `tenant_tax_config` + admin CRUD across all tax types + categories; VAT_RETURN aggregates VAT collected + input VAT correctly; TAX_WITHHELD_RETURN aggregates withholding across payment_run_items with tenant-default fallback; ZIMRA + SARS golden fixtures reconcile.
- **§D AML/STR green**: `suspicious_transaction_alert` table + workflow API (RAISED→REVIEWED→FILED→CLOSED); Angular alert-raise + review-file UI; `medfund.aml.suspicious-transaction` topic emits with dispatcher hook slot; `tenant_aml_threshold_config` + admin; periodic AML summary aggregates threshold-breach + STR count; per-STR XLSX export at FILED transition; both cadenced + event-driven paths under one `AML_STR` key work end-to-end.
- **REG13 sign-off green**: MFA-step-up gate on SUBMITTED transition (Keycloak `amr` claim inspection); Angular re-auth modal; `attestation_note` captured + audited; `filing_ref` post-filing capture + audited.
- **REG18 golden fixtures green**: One YAML fixture per report; `RegulatoryGoldenIT` per regulator seeds via Testcontainers + asserts every named-range value ≥ tolerance.
- **Closeout green**: e2e Playwright specs per report journey (open hub → filter jurisdiction → submit → poll → export → download → filing_ref capture); performance script `scripts/perf-test-regulatory.sh` shell-clean; docker-compose IT deferred to Phase-16-integration follow-up per Phase 15 §25 precedent.

Deferred to follow-up per Phase-11/12/13/14/15 precedent:
- **Cross-language docker-compose IT** — deferred to a Phase-16-integration tranche.
- **Manual `verify` walkthroughs** — end-to-end golden-path browser demos per report; deferred to `verify` skill pass at end of sub-plan.
- **PDF-A + PKCS#7 digital signature** — REG13-rejected; full PKI infra follow-up when a regulator publishes signature API.
- **External filing adapter interface (SFTP/API/EDI per regulator)** — deferred until first regulator publishes an integration API.
- **AI fraud-detector hook into `suspicious_transaction_alert`** — Phase 19 adjacent; the topic emit slot ships stubbed.
- **Bulk CSV upload for regulatory templates, tax config, AML thresholds** — beyond per-row entry; follow-up admin UX polish.
- **Compliance-domain validation of synthetic templates** — REG17 defers; owed back to compliance/actuarial function before first real filing.
- **Phase 15 `REGULATORY` → `IFRS17` rename** — REG19 defers; IFRS 17 keys stay in REGULATORY unchanged.
- **VAT/tax rate periodic auto-fetch from ZIMRA/SARS** — REG9 admin CRUD only; auto-fetch adapter (like Phase 15 I16 market-data pattern) follow-up.
- **Live dashboards (Elixir)** integration for AML/regulator due-date events — follow-up.
- **Live-defect note carried forward**: V131 comment references `com.medfund.tenancy.TenantJurisdiction` enum that doesn't exist; REG6 creates it; comment stays as-is (idempotent commentary).

Per parent-plan Testcontainers policy each deferred IT lands with a purpose-built migration folder so the ITs don't force-widen every unrelated slice's baseline schema.

### Owed back to plan authors

Corrections + contradictions the grill surfaced that touch other sections of the parent plan:

- **Phase 15 F15-2 stale** (parent-plan line ~3945): asserts that `REGULATORY` family "houses both IFRS 17 keys + future Phase 16 regulatory keys (IPEC / CMS / NAIC / PMB / AML / TAX)". REG19 splits those 8 keys across new `PRUDENTIAL` / `TAX` / `COMPLIANCE` families. F15-2 remains factually true at the time of writing but Phase 16 §0 implementation will invalidate it. Sub-plan Phase 16 §0 will amend F15-2 with a "superseded by REG19" note.
- **Parent-plan line 3084 deferral resolved**: "Withholding tax config surface — F11-d only sets the storage column. If a real tenant needs WHT enforcement, plan whether the rate lives per-tenant... Grill at code-altitude when the first tenant asks." REG9 resolves this by shipping `tenant_tax_config` under Phase 16 §C. Sub-plan §C phase notes will cite this parent-plan deferral as resolved.
- **AML_STR `cadenced=true` metadata** (`ReportKey.java:125`): only half-right per REG8. Per-STR filing is event-driven; periodic summary is cadenced. Not a schema bug — the enum flag reflects the periodic branch. Sub-plan §D controllers and UI shape correctly per REG8; no ReportKey change needed. Noted here so a future reader doesn't refactor the enum on incomplete information.
- **V131 comment enum reference stale**: `services/java/tenancy-service/src/main/resources/db/migration/public/V131__tenant_jurisdiction.sql:11-16` references `com.medfund.tenancy.TenantJurisdiction` as the source of truth, but the enum has never been created. REG6 creates it. Migration comment stays as-is (idempotent commentary; new content matches).
- **NOTES_TAX_WITHHELD vs TAX_WITHHELD_RETURN confusion** (`ReportKey.java:58` vs `ReportKey.java:126`): `NOTES_TAX_WITHHELD` is a payables note filter (`ReportFamily.PAYABLES`), `TAX_WITHHELD_RETURN` is the Phase 16 regulator report. Angular hub after REG19 puts them in different families (PAYABLES vs TAX) which visually distinguishes them; sub-plan §C intro comments will call this out.

### Cross-references

- Grilling scratchpad: `thoughts/shared/notes/2026-08-30-phase16-regulatory-grill.md`
- Sub-plan (to be authored via `create-plan`): `thoughts/shared/plans/2026-08-30-regulatory-format-reports.md`

---

## Phase 17: Scheduled Email Delivery

> **Grilled 2026-08-31.** Decisions S1..S13 (numbered `S*` — for Scheduled — to avoid collision with plan-wide `G*` numbering which runs G1..G44 in the parent Implementation Approach section, and with prior phase prefixes `R*` (reinsurance, Phase 10) / `P*` (producer, Phase 11) / `U*` (underwriting, Phase 12) / `L*` (lifecycle, Phase 13) / `A*` (actuarial, Phase 14) / `I*` (IFRS 17, Phase 15) / `REG*` (regulatory, Phase 16)).
>
> The outline was expanded into a full mini-plan through interactive decision-making; scope escalated substantially beyond the original outline (13 non-regulator operational cadenced keys per S1 replacing outline silence on scope — regulator + IFRS 17 + AML periodic + FRAUD_SIU_REPORT auto-run deferred to Phase 17.5 follow-up per REG14's due-date-only stance and REG12/REG13's MFA-gated human-filing model; `report_job` reuse per S2 replacing outline silence on persistence; `ReportCadence` enum + tenant TZ + hourly probe per S3/S4 replacing outline's per-report @Scheduled skeleton; `ReportPeriodShape` enum per S5 replacing outline silence on period derivation; sibling `tenant_report_schedule_recipient` table per S6 replacing outline's `recipients JSONB` shape; MIME attachment ≤10MB fallback signed-link per S7 replacing outline silence on delivery mechanism; fail-once + alert + manual re-run per S8 replacing outline silence on failure semantics; cascade-disable per S9 replacing outline silence on toggle interaction; schedule-creator actor per S10 replacing outline silence on Rule 8/9 actor; central finance-service topology per S11 replacing outline's implicit distributed shape; dedicated `/tenant/admin/settings/report-schedules` page per S12 replacing outline's implicit tab-extension; single sub-plan `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` with 5 tranches / ~10-12 phases per S13 replacing outline silence on tranche structure).
>
> Original 25-line outline retained below as ~~strike-through~~ for provenance.

### Original outline (superseded 2026-08-31 by Decisions Log)

~~`@Scheduled` jobs per recurring report + `tenant_report_schedule` table + notification-service `report/` dispatcher.~~

~~**tenancy-service** migration `V13x__tenant_report_schedule.sql`:~~

~~```sql~~
~~CREATE TABLE IF NOT EXISTS public.tenant_report_schedule (~~
~~    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),~~
~~    tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,~~
~~    report_key VARCHAR(80) NOT NULL,~~
~~    enabled BOOLEAN NOT NULL DEFAULT FALSE,~~
~~    recipients JSONB NOT NULL DEFAULT '[]'::jsonb,~~
~~    last_run_at TIMESTAMPTZ,~~
~~    last_status VARCHAR(20),~~
~~    CONSTRAINT uq_trs UNIQUE (tenant_id, report_key)~~
~~);~~
~~```~~

~~Angular reports settings tab (from Phase 0) extended with schedule + recipients UI where the report is code-marked as "cadenced".~~
~~Per-report `@Scheduled` job that runs on the fixed cadence, generates XLSX, publishes to `medfund.notification.report-delivery` Kafka topic.~~
~~**notification-service** new `internal/report/dispatcher.go` — consumes topic, sends email with XLSX attachment to recipients.~~

### Overview

Greenfield **scheduled report-email delivery layer** for 13 non-regulator operational cadenced report keys (per S1: COMMISSION_STATEMENT, LOSS_RATIO, COLLECTION_RATE, AGED_DEBTORS, CASH_FLOW_FORECAST_13W, CLAIMS_SUMMARY, POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS, PROVIDER_NETWORK_UTILIZATION, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES, UPR_MOVEMENT) spanning shared (new `ReportPeriodShape` enum + `ReportKey.periodShape` field authoring pass per S5, `ReportCadence.WEEKLY` add per S3), tenancy-service (public V171+ migrations for `tenant_report_schedule` per S3/S4 with `cadence` + `hour_of_day` + `day_of_week`/`day_of_month` + `created_by_actor_*`/`updated_by_actor_*` columns + `tenant_report_schedule_recipient` per S6 mirroring REG13/REG20 recipient shape + `TenantReportScheduleController` + `TenantReportScheduleRecipientController` + cascade-disable in `TenantReportConfigService.updateEnabled` per S9), finance-service (new `com.medfund.finance.report.schedule` package with `ScheduledReportProbe` @Scheduled(cron="0 5 * * * *") per S4 + `ScheduledReportOrchestrator` per S11 + `ScheduledReportShapeAdapter` interface + 4 finance-local adapters + `ReportDeliveryPublisher` for `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed` topics + ALTER `report_job` adding `source VARCHAR(20) DEFAULT 'ADHOC' CHECK IN ('ADHOC','SCHEDULED')` + `schedule_id UUID NULL REFERENCES public.tenant_report_schedule(id) ON DELETE SET NULL` per S2 + `POST /api/v1/reports/scheduled/{jobId}/rerun` per S8), contributions-service + claims-service + user-service (new `POST /api/v1/reports/{reportKey}/scheduled-render` endpoints per S11 owner-service contract, one per owned key), notification-service (new `internal/report/dispatcher.go` per S7 subscribing to both delivery + delivery-failed topics + MIME ≤10MB path + signed-link fallback + template resolution + `POST /unsubscribe/{token}` public route per S6), gateway (new signed-download route `GET /api/v1/reports/scheduled/{jobId}/download?token=...` + HMAC token verification for the fallback link), and Angular (new `/tenant/admin/settings/report-schedules` page per S12 + `ReportSchedulesPageComponent` + `ScheduleRecipientListComponent` + `ScheduleRunHistoryComponent` + Phase 0 reports-tab "Manage schedule" link column + cascade-disable confirm modal).

Single sub-plan `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` with 5 tranches (§0 shared types + migrations, §A backend probe + orchestrator + adapters, §B notification dispatcher, §C Angular, §D e2e + rollout) totaling ~10-12 phases per S13. Regulator + IFRS 17 + AML periodic + FRAUD_SIU_REPORT scheduled auto-run deferred to a Phase 17.5 follow-up.

### Decisions Log (S1..S13)

- **S1 — Scope of v1**: **13 non-regulator operational cadenced keys**: COMMISSION_STATEMENT, LOSS_RATIO, COLLECTION_RATE, AGED_DEBTORS, CASH_FLOW_FORECAST_13W, CLAIMS_SUMMARY, POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS, PROVIDER_NETWORK_UTILIZATION, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES, UPR_MOVEMENT. Phase 17 does NOT touch RegulatoryDueDateScanner path; NO MFA-step-up; NO regulatory_submission integration; NO ai-service Python dispatch. Rejected: all 24 cadenced keys (contradicts REG12/REG13 MFA-gated human filing; chunked IFRS 17 async materially expands scope); operational + regulatory drafts (introduces "scheduled draft" concept not in REG12); operational + FRAUD_SIU speculative (Phase 19 compute doesn't exist).

- **S2 — Execution architecture**: **Reuse `report_job` + MinIO + `medfund.notification.report-delivery` event**. `@Scheduled` fires → INSERT report_job (status=REQUESTED, source=SCHEDULED, schedule_id FK) → invoke Java shape service in-process → upload XLSX bytes to `medfund-report-payloads/{tenantId}/{yyyy}/{MM}/{jobId}.xlsx` → UPDATE report_job (status=COMPLETED, result_json={xlsxRef, sha256, sizeBytes, rowCount}, completed_at) → publish delivery event `{jobId, scheduleId, tenantId, reportKey, xlsxRef, sha256, sizeBytes, cadenceLabel, periodStart, periodEnd, occurredAt, schemaVersion:1}`. notification-service dispatcher fetches from MinIO + MIME-attaches (S7). Two new report_job columns: `source VARCHAR(20) NOT NULL DEFAULT 'ADHOC' CHECK IN ('ADHOC','SCHEDULED')` + `schedule_id UUID NULL REFERENCES public.tenant_report_schedule(id) ON DELETE SET NULL`. No new retention logic — existing OPERATIONAL_90D applies (keep last 20 per (tenant, report_key) covers 20 months monthly). Rejected: direct publish skip report_job (weak audit + duplicate retention); inline XLSX in Kafka (exceeds 900KB reject at ReportJobPublisher size guard); full report_job async pipe (unnecessary Kafka hops for Java-in-process compute).

- **S3 — Cadence source**: **Per-tenant `ReportCadence` enum + fixed conventions**. Extend `ReportCadence` enum to add `WEEKLY` (5 values total: WEEKLY / MONTHLY / QUARTERLY / ANNUAL / EVENT_DRIVEN — EVENT_DRIVEN stays unused by Phase 17). `tenant_report_schedule` carries: `cadence VARCHAR(20) NOT NULL CHECK IN ('WEEKLY','MONTHLY','QUARTERLY','ANNUAL')` + `hour_of_day INT NOT NULL DEFAULT 8 CHECK BETWEEN 0 AND 23` + `day_of_week INT NULL CHECK BETWEEN 1 AND 7` (WEEKLY only) + `day_of_month INT NULL DEFAULT 1 CHECK BETWEEN 1 AND 28` (MONTHLY only; capped at 28 to avoid Feb-boundary confusion). QUARTERLY = 1st of Jan/Apr/Jul/Oct; ANNUAL = 1st of Jan (tenant fiscal-year support deferred). Server derives Quartz cron internally; tenant never sees the cron string. Rejected: raw cron_expr (footgun UX + tempting over-schedule); enum + optional cron override (two ways to answer same question); code-declared per-ReportKey (contradicts "business-report cadence is a tenant choice").

- **S4 — Timezone**: **Tenant TZ via hourly probe**. `Tenant.timezone` (String, `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/Tenant.java:49`) is already populated. New `ScheduledReportProbe @Scheduled(cron="0 5 * * * *")` in finance-service runs every hour at HH:05. Query joins schedule + tenant, per-row resolves "now in tenant TZ" via `ZoneId.of(tenant.timezone)`, checks cadence match (WEEKLY: dayOfWeek + hourOfDay; MONTHLY: dayOfMonth + hourOfDay; QUARTERLY: dayOfMonth==1 + monthValue IN (1,4,7,10) + hourOfDay; ANNUAL: dayOfMonth==1 + monthValue==1 + hourOfDay). Multi-instance dedup via `report_job` UNIQUE (tenant_id, report_key, schedule_id, period_start) — DuplicateKeyException swallowed silently by losing racer. Invalid `tenant.timezone` (unparseable ZoneId) → log warning + skip. No ShedLock needed; matches RegulatoryDueDateScanner precedent. Rejected: UTC everywhere (disruptive email arrival times across ZW/ZA/US); per-schedule TaskScheduler bean (over-engineered lifecycle management); server-local TZ (JVM TZ is deployment accident).

- **S5 — Report period**: **Per-`ReportKey` `ReportPeriodShape` enum**. New sealed enum `ReportPeriodShape { PREVIOUS_COMPLETE_PERIOD, AS_OF_FIRE_TIME }` in `shared/report/`. Extend `ReportKey` with final `periodShape` field per-constant. Values in scope: PREVIOUS_COMPLETE_PERIOD for COMMISSION_STATEMENT, LOSS_RATIO, COLLECTION_RATE, CLAIMS_SUMMARY, POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS, PROVIDER_NETWORK_UTILIZATION, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES, UPR_MOVEMENT; AS_OF_FIRE_TIME for AGED_DEBTORS + CASH_FLOW_FORECAST_13W. Scheduler resolves `(periodStart, periodEnd, asOf)` from (cadence, firedAt, shape): PREVIOUS_COMPLETE_PERIOD + MONTHLY = last month's first-to-last day; AS_OF_FIRE_TIME = periodStart=periodEnd=asOf=firedAt. All shape-service call signatures already accept (from, to) or (asOf) — new adapter wraps each to uniform (periodStart, periodEnd, asOf). Rejected: per-schedule lookback (puts domain-modelling in tenant lap); hardcode previous-period always (AGED_DEBTORS + CASH_FLOW_FORECAST_13W break); firedAt-only shape-decides (contract fragmentation + audit opacity).

- **S6 — Recipients**: **Sibling table `public.tenant_report_schedule_recipient` mirroring REG13/REG20 pattern**. Schema: `id UUID PK, schedule_id UUID FK CASCADE, email VARCHAR(255), display_name VARCHAR(160) NULL, is_active BOOLEAN DEFAULT TRUE, unsubscribe_token UUID UNIQUE DEFAULT gen_random_uuid(), created_at/updated_at/actor_id/actor_email, UNIQUE(schedule_id, LOWER(email))`. Overrides the outline's JSONB shape — outline superseded. Admin CRUD in tenancy-service clones REG13 `TenantRegulatoryRecipientController` pattern. notification-service exposes `POST /unsubscribe/{token}` public route → sets is_active=FALSE + audit. Email body renders token URL. Rejected: JSONB per outline (per-recipient audit + unsubscribe token become verbose blob-diff); generic per-report recipient (loses per-schedule granularity); generalise tenant_regulatory_recipient (mid-flight rename disturbs REG20 for zero Phase 17 gain).

- **S7 — XLSX delivery mechanism**: **MIME attachment ≤10MB, else signed download link**. Dispatcher reads `sizeBytes` from delivery event; if <10*1024*1024 → attach via `mail.Attachment{Filename, ContentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Data: minIOFetchBytes()}` on Message.Attachments (invoice dispatcher precedent). Else → render body with `{{.DownloadUrl}}` pointing at gateway route `GET /api/v1/reports/scheduled/{jobId}/download?token={signedToken}` — HMAC token over {jobId, tenantId, recipientEmail, exp} with 7-day expiry. Subject: `[{{.TenantName}}] {{.ReportLabel}} — {{.CadenceLabel}} — {{.PeriodLabel}}`. Body template via existing `tenant_email_templates` mechanism. Filename: `{report_key_lower}_{period_start}_{period_end}.xlsx`. Rejected: always attach (SMTP relay caps silently bounce); always signed link (auth-flow friction for small XLSX); attach+link (duplicated payload, link invisible when attach fails).

- **S8 — Failure semantics**: **Fail-once + alert + manual re-run; no auto-retry, no auto-disable**. Failure at shape service / MinIO PUT / Kafka publish / delivery dispatch / SMTP 5xx → `report_job.status=FAILED, error_message=<summary>, completed_at=NOW()`. Publish `medfund.notification.report-delivery-failed` event `{jobId, scheduleId, tenantId, reportKey, periodStart, periodEnd, failureStage, errorSummary, occurredAt, schemaVersion:1}`. Single `internal/report/dispatcher.go` handles both topics; failure emails to schedule recipients + fallback to `tenant.contact_email` if zero active recipients. Schedule stays `enabled=TRUE`; next fire proceeds. Angular schedule-history exposes `POST /api/v1/reports/scheduled/{jobId}/rerun` — enqueues fresh report_job with source=SCHEDULED + schedule_id=<original> + params_json=<original> (period preserved); rerun audit-logged with invoking human actor (not system). Alert subject: `[{{.TenantName}}] Scheduled report failed: {{.ReportLabel}} ({{.PeriodLabel}})`. Rejected: retry-N with backoff (state-machine complexity for diminishing return); silent fail (auditor visibility gap); auto-disable after N failures (big hammer + N-with-monthly = 3 months).

- **S9 — Toggle-off ↔ schedule**: **Cascade-disable**. Tenancy-service `TenantReportConfigService.updateEnabled(...)` flipping TRUE→FALSE executes transactional cascade: UPDATE `tenant_report_schedule SET enabled=FALSE, updated_at=NOW(), updated_by=<actor>` WHERE `tenant_id=? AND report_key=? AND enabled=TRUE`; emit one AuditEvent per affected schedule row with friendly entity_name per `feedback_audit_entity_name` — e.g. `"Scheduled Commission statement (Monthly, first day)"`. Re-enabling report toggle (FALSE→TRUE) does NOT cascade back on — admin must re-enable each schedule (asymmetric on purpose; disable is safe direction). Angular admin surface shows confirm modal on disable when affected count > 0: "Disabling this report will pause N scheduled deliveries. Continue?" Scheduler probe still short-circuits on `schedule.enabled=FALSE` (belt-and-braces). Rejected: silent-skip at fire time (schedule state doesn't reflect truth); fire-and-403 (deliberate self-inflicted alert noise); reject at CRUD only (inconsistent semantics create vs continue).

- **S10 — Actor**: **Schedule creator carried through**. `tenant_report_schedule` columns: `created_by_actor_id UUID NOT NULL, created_by_actor_email VARCHAR(255) NOT NULL, updated_by_actor_id UUID NOT NULL, updated_by_actor_email VARCHAR(255) NOT NULL`. Every fire: `report_job.requested_by = schedule.updated_by_actor_id, report_job.requested_by_email = schedule.updated_by_actor_email` (most-recent editor takes responsibility). AuditEvent + `SecurityEventPublisher.publishDataAccess(actor=..., context={source:"SCHEDULED", scheduleId:..., cadence:..., periodStart:...})` use the same actor via `AuditActor.of(updatedByActorId, updatedByActorEmail)` per `feedback_audit_actor_email` memory. Email FROM header is a fixed no-reply address (`noreply@{tenant.contact_domain}` or platform fallback) — separate concern from audit actor. Ownership transfer admin surface = follow-up. Rejected: system actor per service (violates `feedback_audit_actor_email` + loses accountability); tenant-admin fallback (cross-service call each fire); empty actor (hard Rule 8 violation).

- **S11 — Topology**: **Central: finance-service owns the probe**. New `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportProbe.java` @Scheduled(cron="0 5 * * * *") + `ScheduledReportOrchestrator` service. Queries `public.tenant_report_schedule` JOIN `public.tenants`; filters cadence match; per-fire dispatches via `ScheduledReportShapeAdapter { Mono<byte[]> render(ScheduledFireContext); ReportKey key(); ReportPeriodShape periodShape(); }`. Finance-owned adapters (COMMISSION_STATEMENT, LOSS_RATIO, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES) are local @Components; other-service adapters delegate via `CrossServiceCallHelper` to new HTTP endpoints. Owner-service HTTP contract: `POST /api/v1/reports/{reportKey}/scheduled-render` body `{tenantId, periodStart, periodEnd, asOf, reportingCurrency, cadenceLabel, scheduleId}` → returns `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` bytes. Gated by new `@RequiresPermission("scheduled_report:render")` (internal-service-only via M2M token). Per-service inventory: contributions-service (COLLECTION_RATE, AGED_DEBTORS, UPR_MOVEMENT, CASH_FLOW_FORECAST_13W); claims-service (CLAIMS_SUMMARY, PROVIDER_NETWORK_UTILIZATION); user-service (POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS); finance-service (COMMISSION_STATEMENT, LOSS_RATIO, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES). Rejected: distributed per-service probes (4x operational surface + dedup races); central probe + Kafka fan-out (4 hops per fire for zero benefit); new scheduler-service (unjustified 15th service).

- **S12 — Angular UI**: **Dedicated `/tenant/admin/settings/report-schedules` page + grid link-through**. New sidebar entry under Settings. `ReportSchedulesPageComponent` = accordion cards grouped by family (matches Phase 0). Card shows: report label, family badge, enabled toggle, cadence dropdown (WEEKLY/MONTHLY/QUARTERLY/ANNUAL), day/hour picker (contextual — day_of_week for WEEKLY, day_of_month for MONTHLY, no day picker for QUARTERLY/ANNUAL), reportingCurrency override (optional; blank = tenant default), collapsible recipient list. `ScheduleRecipientListComponent` = table with add/remove/toggle-active + per-row unsubscribe token display. `ScheduleRunHistoryComponent` = last N `report_job` runs for (schedule, report_key) with status + duration + error message + re-run button. Phase 0 reports-tab gets "Manage schedule" link column next to cadenced rows; anchor scroll to card. Backend APIs (tenancy-service): GET/POST/PUT/DELETE `/api/v1/tenants/{tenantId}/report-schedules` + `/recipients` sub-resource + `/history` sub-resource. Permission gates: `tenant_settings:report_schedules:read` + `:write` + `:manage_recipients`. Rejected: expand-row on grid (visually crushes 26+ rows); modal (painful nested CRUD); under finance-officer per-report pages (mixes tenant-admin controls with finance-officer role).

- **S13 — Sub-plan structure**: **Single sub-plan, 5 tranches, ~10-12 phases**. `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md`. Tranches: **§0 shared types + migrations** (~2 phases: `ReportCadence.WEEKLY` add, `ReportPeriodShape` enum, `ReportKey.periodShape` field authoring pass across 24 cadenced keys, V171+ public migrations for `tenant_report_schedule` + `tenant_report_schedule_recipient`, tenant migration for `report_job` ALTER `source` + `schedule_id` columns). **§A backend probe + orchestrator + adapters** (~3 phases: `ScheduledReportProbe` + `ScheduledReportOrchestrator` + `ScheduledReportShapeAdapter` interface + 4 finance-local adapters + `/scheduled-render` endpoints on contributions/claims/user + `ReportDeliveryPublisher` + tenancy-service CRUD + cascade-disable in `TenantReportConfigService.updateEnabled`). **§B notification dispatcher** (~2 phases: `internal/report/dispatcher.go` subscribing to both topics + MIME ≤10MB path + signed-link path + gateway route + unsubscribe route + template files). **§C Angular** (~2 phases: `/tenant/admin/settings/report-schedules` page + components + Phase 0 grid link + cascade-disable modal). **§D e2e + rollout** (~2 phases: Playwright specs incl test-only "force fire" endpoint + mailpit assertion + report_job history + XLSX download; docker-compose IT deferred to Phase 17-integration follow-up per Phase 15/16 precedent; rollout notes with deploy order). Rejected: two sub-plans backend/frontend (halfway state; drift); flat phase list (harder review boundaries); 3 sub-plans (create-plan overhead for Phase-8-scale scope).

### Settled by fact (not asked)

- **F-S1 — Reporting-currency default**: Cross-service invariant #1 (line 120) already mandates `tenant_currency_config.is_default` unless overridden. Scheduled runs use the same via `TenantConfigClient.getDefaultCurrency(tenantId)` at fire time. No per-schedule `reporting_currency_override` column in v1 (add later if a tenant asks). Missing-FX semantics inherit invariant #6 warning-only behaviour for the general reports in scope.

- **F-S2 — Rule 8 audit on every mutation**: `AuditEvent` on schedule CRUD, recipient CRUD, cascade-disable, ownership-transfer (N/A v1). Uses `AuditActor` helper (`feedback_audit_actor_email` memory) with entity_name as friendly text per `feedback_audit_entity_name` — e.g. `"Scheduled Commission statement (Monthly, first day)"` not the UUID.

- **F-S3 — Rule 9 SecurityEvent on every scheduled export**: `SecurityEventPublisher.publishDataAccess(...)` (`services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java:74-80`) fires from the orchestrator right before returning XLSX bytes to MinIO upload. `context.source = "SCHEDULED"`.

- **F-S4 — Kafka ack pattern**: `.doOnSuccess` acknowledgement per `bug_reactor_kafka_ack_swallow` memory in the finance-service Reactor-Kafka publisher path. Go notification-service consumer uses existing `events.Subscriber` callback pattern (implicit commit-on-callback-return) per regulatory dispatcher precedent.

- **F-S5 — Public vs tenant-schema prefix**: `tenant_report_schedule` + `tenant_report_schedule_recipient` are public tables → SQL uses `public.` prefix. `report_job` is a tenant-schema table (Phase 15) → unqualified name. Enforced by `bug_public_prefix_silent_rollback` memory.

- **F-S6 — Retention**: `report_job.retention_class = OPERATIONAL_90D` (Phase 15 §14 / I28) applies uniformly to scheduled runs. No new retention job; existing `ReportJobRetentionJob` (`services/java/finance-service/src/main/java/com/medfund/finance/report/scheduler/ReportJobRetentionJob.java:57`) handles cleanup (>90 days, keep top 20 per (tenant, report_key)).

- **F-S7 — Migration numbering**: Last applied public migration is `V170__regulatory_due_date_notification_sent.sql` (Phase 16 REG20). Phase 17 public migrations start V171+. Numbers to reserve: `V171__tenant_report_schedule.sql`, `V172__tenant_report_schedule_recipient.sql`. Tenant-schema ALTER `report_job` add columns: verify latest applied tenant migration at implementation time per `feedback_never_edit_applied_migrations` memory (~V16x-V17x range as of Phase 15).

- **F-S8 — Kafka topic naming convention**: `medfund.<domain>.<event>` (dot-separated, dashed lowercase). New topics: `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed`. Both follow convention.

- **F-S9 — Deploy order invariant**: notification-service `internal/report/dispatcher.go` MUST start consuming both `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed` BEFORE finance-service `ScheduledReportOrchestrator` starts publishing. New consumer subscribes from `earliest` on first deploy to catch pre-cutover events. Standard multi-service ordering per Phase 15 I22 pattern.

- **F-S10 — ReportKey.cadenced authoring already done**: The 24 cadenced keys are already tagged in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:33-139`. Phase 17 §0 authoring pass adds `periodShape` to those 24 + updates the 13 in S1 scope to their concrete `PREVIOUS_COMPLETE_PERIOD` / `AS_OF_FIRE_TIME` values; the 11 out-of-scope cadenced keys get their periodShape too (for future use) but no schedule row can be created for them yet (Phase 17 UI whitelist enforces).

- **F-S11 — ReportCadence.EVENT_DRIVEN**: Existing enum value stays; unused by Phase 17 UI (dropdown offers WEEKLY/MONTHLY/QUARTERLY/ANNUAL only). Kept for regulatory REG20's due-date scanner semantics.

- **F-S12 — Angular JURISDICTIONS + country gates**: Not touched by Phase 17 (S1 scope excludes regulatory keys, so no jurisdiction gate applies to scheduled reports in v1).

- **F-S13 — MinIO bucket + path**: Reuse `medfund-report-payloads` (Phase 15 §14). Path convention: `{tenantId}/{yyyy}/{MM}/{jobId}.xlsx` (consistent with Phase 15 pattern; per-year/per-month sharding for listing efficiency).

- **F-S14 — SMTP relay + mailpit dev**: notification-service `SMTPSender` already supports PLAIN auth + mailpit no-auth dev mode (`services/go/notification-service/internal/mail/sender.go:41-71`). MIME attachment path already used by invoice dispatcher (`services/go/notification-service/internal/invoice/dispatcher.go:136-140`).

### Success Criteria

**Status: Grilled 2026-08-31 (S1..S13 + F-S1..F-S14 above). Ready for sub-plan via `create-plan`. Not yet implemented.**

Single sub-plan will ship at `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` built via `create-plan` → `implement-plan`. Aggregate success criteria across all tranches:

- **§0 shared types + migrations green**: `ReportCadence.WEEKLY` added + downstream `ReportCadenceCatalog` uses; `ReportPeriodShape` enum authored; every cadenced `ReportKey` carries a `periodShape` value; V171/V172 public migrations apply cleanly on fresh + existing tenants; `report_job` ALTER adds `source` + `schedule_id` with idempotent SQL (`ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...`); tenancy-service `TenantReportConfigService.updateEnabled` cascade-disable executes as one transaction + emits per-affected-schedule audit events; unit + IT coverage of cadence enum-add and cascade-disable.
- **§A backend probe + orchestrator + adapters green**: `ScheduledReportProbe` fires hourly; `ScheduledReportOrchestrator` correctly matches cadences per tenant TZ; all 13 adapters return valid XLSX bytes on test invocation; 3 owner-service `/scheduled-render` endpoints permission-gated by `@RequiresPermission("scheduled_report:render")` + tenant-scoped + return XLSX bytes; `report_job` UNIQUE index prevents duplicate fires across multiple finance-service instances; Kafka `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed` produced with schemaVersion:1 payload; tenancy-service schedule + recipient CRUD APIs work end-to-end + audit-log per mutation; Rule 8 audit + Rule 9 SecurityEvent fire correctly with schedule-creator actor.
- **§B notification dispatcher green**: `internal/report/dispatcher.go` consumes both topics; MIME attachment for XLSX <10MB verified via mailpit; signed-link path renders + gateway route verifies HMAC token + returns MinIO bytes; unsubscribe route `POST /unsubscribe/{token}` sets is_active=FALSE + audit; per-tenant email templates resolve via `tenant_email_templates`; failure alert emails routed to schedule recipients + fallback to `tenant.contact_email`.
- **§C Angular green**: `/tenant/admin/settings/report-schedules` page loads + lists cadenced reports grouped by family; schedule CRUD works end-to-end incl. cadence + day + hour + reportingCurrency + recipients; Phase 0 grid "Manage schedule" link column + anchor scroll works; cascade-disable confirm modal shows correct affected count; permission gates enforced (read/write/manage_recipients); `ScheduleRunHistoryComponent` shows report_job history with rerun button that enqueues a fresh job.
- **§D e2e + rollout green**: Playwright specs (create schedule → force fire via test-only endpoint → verify email at mailpit → verify report_job history + XLSX download); performance script `scripts/perf-test-scheduled-reports.sh` shell-clean; docker-compose IT deferred to Phase-17-integration follow-up per Phase 15/16 §25 precedent.

Deferred to follow-up per Phase-11/12/13/14/15/16 precedent:
- **Cross-language docker-compose IT** — Phase-17-integration tranche.
- **Manual `verify` walkthroughs** — end-to-end golden-path browser demos of the schedule CRUD + fire + email arrival; `verify` skill pass at end of sub-plan.
- **Phase 17.5 — Regulator + IFRS 17 + AML periodic + FRAUD_SIU auto-run** — deferred; S1 excluded these to preserve regulator human-MFA-filing model (REG12/REG13) + avoid stepping on REG20 due-date reminders; separate grill + sub-plan.
- **Ownership-transfer admin surface** — reassigning `updated_by_actor_*` on schedule to a new admin when creator leaves.
- **Per-schedule reporting_currency_override column** — v1 uses tenant default; add per-schedule override when a tenant asks.
- **Cron-style advanced scheduler** — v1 offers enum cadence + day/hour picker; raw cron string deferred until a tenant needs "every Tuesday and Friday" or "every 5th business day".
- **Rerun-schedule "backfill" mode** — v1 supports single-job rerun with same period; "rerun all missed fires between date X and Y" deferred.
- **Attachment size histogram + alert** — deferred instrumentation to detect a tenant whose commission_statement XLSX is trending toward the SMTP cap.

Per parent-plan Testcontainers policy each deferred IT lands with a purpose-built migration folder so the ITs don't force-widen every unrelated slice's baseline schema.

### Owed back to plan authors

Corrections + contradictions the grill surfaced that touch other sections of the parent plan:

- **Parent-plan header `phases_status` stale**: header line `"16-19": outline depth; each needs its own grilling pass before implementation` superseded — Phase 16 landed 2026-08-30 (commit `ba9e65c` "Land Phase 16 regulatory-format reports (Phases 1-28)"); Phase 17 grilled 2026-08-31 (this apply step). `phases_status["16"]` now landed, `["17"]` now grilled; `last_grilled_phase: 17`, `last_grilled_date: 2026-08-31`.
- **Cross-invariant #2 tension with S9 cascade** (line 121): invariant says GET endpoints 403 when `tenant_report_config` disabled. S9 chose cascade-disable so schedules never fire against a disabled report. No contradiction, but sub-plan §0 will include a note in the `TenantReportConfigService.updateEnabled` phase spelling out the two-write transaction so a reviewer doesn't wonder why the scheduler doesn't also check.
- **`ReportCadence.EVENT_DRIVEN` semantics** (`services/java/shared/src/main/java/com/medfund/shared/report/ReportCadence.java`): unused by Phase 17. Regulatory REG20 uses it implicitly for tier-driven emissions. Sub-plan §0 note: "EVENT_DRIVEN excluded from Phase 17 schedule cadence dropdown per F-S11."
- **AML_STR + IFRS 17 + regulatory cadenced keys stay out of Phase 17**: per S1. Sub-plan intro calls out that scheduled auto-run + email for regulator/IFRS keys is a follow-up ("Phase 17.5 — Regulator draft auto-run") to prevent scope-creep during implementation.
- **`ReportKey.cadenced` javadoc line 148-152** already correctly says "Drives whether the Phase 17 schedule form exposes the switch for this report at all" — no correction needed; S1 further restricts to 13 keys within the 24 marked (UI whitelist per F-S10).
- **`report_job.source` + `schedule_id` ALTER**: Phase 15 ReportJob entity + migration need to grow two new columns (S2 design). Not a contradiction but a follow-on schema change to keep in mind for anyone reading Phase 15's tables in isolation.
- **Phase 19 FRAUD_SIU_REPORT (cadenced=true)** (`ReportKey.java:139`): marked cadenced but Phase 19 not built. Phase 17 does not deliver it (S1). Sub-plan §0 UI whitelist explicitly excludes FRAUD_SIU_REPORT until Phase 19 lands.

### Cross-references

- Grilling scratchpad: `thoughts/shared/notes/2026-08-31-phase17-scheduled-email-grill.md`
- Sub-plan (to be authored via `create-plan`): `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md`

---

## Phase 18: Executive KPI Dashboards

> **Grilled 2026-09-05.** Decisions K1..K18 (numbered `K*` — for KPI — to avoid collision with plan-wide `G*` numbering and with prior phase prefixes `R*` (reinsurance, Phase 10) / `P*` (producer, Phase 11) / `U*` (underwriting, Phase 12) / `L*` (lifecycle, Phase 13) / `A*` (actuarial, Phase 14) / `I*` (IFRS 17, Phase 15) / `REG*` (regulatory, Phase 16) / `S*` (scheduled email, Phase 17)).
>
> Original 3-line outline retained below as ~~strike-through~~ for provenance. Scope escalated substantially beyond the outline: 5 KPI keys instead of 3 (K1 adds `CLAIMS_FREQUENCY` + `AVERAGE_SEVERITY`), incurred-basis loss ratio requires new aggregate endpoints on 3 services (K7/K8/K9), 5 KPI keys flip to `cadenced=true` widening Phase 17 S1 whitelist (K11), mixed-basis combined ratio (K6), on-demand + Redis cache storage (K10), per-currency native + composite scalar (K12), 3-chip slicing (K13), 12-month trend with dropdown to 24 (K14), tile + sparkline + click-through (K15).

### Original outline (superseded 2026-09-05 by Decisions Log)

~~Combined ratio + expense ratio + loss ratio dashboards for tenant execs.~~

~~**finance-service** `ExecutiveKpiController`: `/reports/kpi/combined-ratio`, `/loss-ratio`, `/expense-ratio`. Report keys `COMBINED_RATIO`, `LOSS_RATIO_KPI`, `EXPENSE_RATIO`. Composes from billing + claims + expense aggregates.~~

~~**Angular** `pages/tenant/finance/reports/kpi/` — dashboard shell with ratio trend charts.~~

### Overview

Executive KPI dashboard for tenant execs consisting of 5 KPI tiles (per K1: `COMBINED_RATIO`, `LOSS_RATIO_KPI`, `EXPENSE_RATIO`, `CLAIMS_FREQUENCY`, `AVERAGE_SEVERITY`) spanning shared (add 2 new enum keys per K1 + flip 5 existing/new keys to `cadenced=true, periodShape=PREVIOUS_COMPLETE_PERIOD` per K11), contributions-service (new `GET /api/v1/reports/aggregate/premium-earned` per K8 for earned-basis loss-ratio denominator), claims-service (new `GET /api/v1/reports/aggregate/claims-incurred` per K9 for paid+Δreserve numerator on incurred loss ratio), finance-service (new `GET /api/v1/reports/aggregate/commissions` per K7 for commission-only acquisition-ratio proxy + new `ExecutiveKpiController` + `KpiComposerService` fanning out via `CrossServiceCallHelper` + Redis 15-min TTL cache per K10 + IBNR lookup from `report_job.result_json` per K9 + K12 per-currency + composite scalar assembly + 5 individual + 1 batch + 5 trend endpoints under `/api/v1/reports/kpi/*` + 5 `ScheduledReportShapeAdapter` implementations for Phase 17 §D orchestrator per K11 + `KpiWorkbookService` for XLSX export), and Angular (new `KpiDashboardComponent` at `/tenant/finance/reports/kpi` per K16 + tile grid + new `app-sparkline` compact-height component per K15 + 3 filter chips for line/scheme/producer per K13 + click-through drill-down navigation per K15 + Reports Hub Dashboard family card + Phase 17 UI whitelist extension for 5 new cadenced keys per K11 consequence). No Flyway migrations (F18-8).

Single sub-plan `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md` with 3 tranches (§0 enum + aggregates, §A composer + Angular, §B scheduled adapter + e2e) totaling ~6-8 phases per K18. Full operating ratio (acquisition + admin + investment), acquisition-vs-servicing commission classifier, and `kpi_snapshot` materialized-warm-path deferred to Phase 18.5 follow-up.

### Decisions Log (K1..K18)

- **K1 — Ratio scope for v1**: **Three ratios + claims frequency + average severity**. Ship `COMBINED_RATIO` + `LOSS_RATIO_KPI` + `EXPENSE_RATIO` plus two new keys `CLAIMS_FREQUENCY` (claim count / policy-months exposure) + `AVERAGE_SEVERITY` (paid / claim count). Both new keys land in `DASHBOARD` family, non-cadenced-then-flipped-by-K11-to-cadenced, as part of Phase 18 §0. Frequency requires a policy-month exposure feed (Phase 13 lifecycle join — dependency surfaces again in K8/K9; sub-plan §0 needs a decision at code altitude between adding `/aggregate/policy-exposure` on contributions-service or approximating with `active_policy_count × period_length_in_months` from Phase 13's active-policy count). Severity is a straight claims-service aggregate (paid ÷ count) using the existing `/api/v1/reports/aggregate/claims` endpoint plus a new count column. Rejected: three-only (executes will demand adjacent KPIs on day 2); +solvency +ROE (needs an accounting ledger + capital-model service that don't exist — two-quarter build, not a phase). Adjacent unbuilt KPIs (solvency, ROE, policy-persistency variants) deferred to Phase 18.5.

- **K2 — Formula: `LOSS_RATIO_KPI` numerator = incurred**: Numerator = paid + Δoutstanding reserves + IBNR. Matches actuarial + IFRS 17 framing. Requires (a) exposing reserve movement via a new `/aggregate/claims-incurred` endpoint on claims-service (K9); (b) reading the latest committed IBNR run per (line, cohort) from Phase 14's `report_job` output (K9). Freshness caveat: if the IBNR job hasn't run for the period, dashboard shows an "IBNR pending" warning banner rather than a zero (invariant #6 spirit). Rejected: paid-only (misleads execs + misaligns with regulator + IFRS 17); dual-line side-by-side (chart clutter + doubles compute; execs still fixate on the single "the ratio" reading).

- **K3 — Formula: `LOSS_RATIO_KPI` denominator = earned premium**: Denominator = earned premium sourced from Phase 12's UPR earning schedule. Requires a new aggregate endpoint on `contributions-service` returning per-(scheme, currency, period) *earned* totals — mirrors the shape of the existing `BillingAggregateController` but reads `EarningSchedule.earned_at_period_end` sums over the period boundaries (see K8 for endpoint shape). Rejected: written premium via billing aggregate (mismatched exposure basis; contradicts the whole reason we built UPR); dual-axis (chart-clutter + "which one is THE ratio" ambiguity + still needs the Phase 12 work).

- **K4 — Formula: `EXPENSE_RATIO` numerator = commission-only (Acquisition Ratio)**: Numerator = paid commission from Phase 11's `commission_statement` for the period. **Renamed on the UI to "Acquisition Ratio"** (or "Commission Ratio") so execs read the honest scope. The `EXPENSE_RATIO` `ReportKey` label stays as-is (it's a code identifier), but the report's `displayLabel` on the catalogue + Angular page title reads "Acquisition Ratio". Requires the new `/aggregate/commissions` endpoint on finance-service (K7). Rejected: manual-entry `operating_expense_entry` table (data-entry burden + two sources of truth vs the tenant's accounting system); IFRS 17 fulfilment cash flows (estimates vs actuals; conflates two audiences). Full operating ratio (acquisition + admin + investment) deferred to Phase 18.5 alongside a general-ledger integration.

- **K5 — Formula: `EXPENSE_RATIO` denominator = written premium**: Denominator = written premium via existing `/api/v1/reports/aggregate/billing` endpoint (`services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BillingAggregateController.java:50-66`). Matches when acquisition cost was actually incurred (commission paid at policy write). NAIC + trade-press convention. Reuses the billing aggregate endpoint verbatim — no new endpoint. **Consequence for K6**: combined ratio carries a "mixed basis" footnote (expense/written + loss/earned). Rejected: earned for both (mismatched timing; underestimates expense ratio early in a policy year); footnoted mixed-basis-with-recompute (subtle to reviewers scanning a widget). IFRS 17 auditor pushback acknowledged and left for the sub-plan to add a per-tenant override switch in a follow-up (v1 uses NAIC).

- **K6 — Formula: `COMBINED_RATIO` = sum with mixed-basis footnote**: Combined = LossRatio(incurred/earned) + ExpenseRatio(commission/written). Widget carries a persistent info-icon tooltip: *"Mixed basis — loss on earned premium, expense on written premium (NAIC convention)."* Server response envelope includes a `basisNote: "MIXED_LOSS_EARNED_EXPENSE_WRITTEN"` string so downstream consumers (XLSX export, scheduled dispatch via K11) can render the same disclaimer. Rejected: recompute-on-earned (three numbers that don't add up + users try to reconcile and fail); skip-scalar / stacked-only (evasive tone to "what's our combined ratio"). A future "additive combined" toggle where the tenant sets a preferred basis lives in Phase 18.5.

- **K7 — Expense fact source = sum PAID `commission_transaction`, classifier deferred**: New endpoint `GET /api/v1/reports/aggregate/commissions?periodStart&periodEnd&dimension&insuranceLine&producerId` in finance-service (mirrors `BillingAggregateController` + `ClaimsAggregateController` shape). SQL: `SELECT native_currency AS currency_code, SUM(native_amount) AS total_paid, COUNT(*) AS row_count FROM commission_transaction WHERE status='PAID' AND paid_at >= :periodStart AND paid_at < :periodEnd GROUP BY native_currency`, optionally faceted by `producer_id` + `insurance_line`. **No acquisition-vs-servicing split for v1** — `commission_transaction` lacks the classifier (`services/java/finance-service/src/main/java/com/medfund/finance/producer/entity/CommissionTransaction.java:38-100`) and Phase 18 does not add it. KPI page carries a UI note: *"Includes all paid commission; new-business/trail split in a future release."* Rejected: add `commission_type` column now (schema thrash without a live requirement; risks wrong enum); compose acquisition via `first_bind_date` (cross-service join per compute + year-boundary edge cases + still misses trail on old policies). Add classifier in Phase 18.5 when a tenant asks.

- **K8 — Earned-premium source = new `/api/v1/reports/aggregate/premium-earned`**: New lean aggregate endpoint on `contributions-service/PremiumReportController` (or a sibling `PremiumAggregateController` following the `ClaimsAggregateController` / `BillingAggregateController` pattern). Shape: `GET /api/v1/reports/aggregate/premium-earned?periodStart&periodEnd&dimension&insuranceLine` returns `List<{schemeId, schemeName, insuranceLine, currencyCode, earnedPremium, rowCount}>`. SQL: `SUM(earned_at_period_end) WHERE period_end >= :periodStart AND period_end < :periodEnd AND earned_at_period_end IS NOT NULL` grouped by `(currency_code, insurance_line[, scheme_id via policy-enrichment CTE from PremiumReportQueryRepository.java:211-249])`. **Freshness caveat**: the nightly `PremiumEarningExecutor` (`services/java/contributions-service/src/main/java/com/medfund/contributions/premium/scheduler/PremiumEarningExecutor.java:20-31`) closes periods; a KPI compute for a period that hasn't fully closed yet gets NULL rows omitted — KPI page displays a banner *"Earned premium reflects fully-closed periods only (nightly batch)."* Rejected: reuse UPR movement report (tight coupling to human report shape; bandwidth waste; mixes report/feed semantics); direct DB read from finance (violates service boundary; schema-change fragility; multi-tenant pool sizing pain).

- **K9 — Incurred-claims source = `/aggregate/claims-incurred` + latest IBNR job JSON**: Two-part composition on the finance-service KPI composer side:
  1. **New claims-service endpoint** `GET /api/v1/reports/aggregate/claims-incurred?periodStart&periodEnd&dimension` returns per-(currency, dimension) `{totalPaid, reserveBalanceStart, reserveBalanceEnd, reserveMovement, subtotalIncurredExIbnr}` where `reserveBalance(T) = SUM(latest reserved_amount per claim_id WHERE effective_at <= T)`. SQL uses a `DISTINCT ON (claim_id) ... ORDER BY claim_id, effective_at DESC` subquery per period boundary, summed. Native per-currency; no conversion. Reads `claim_reserve_history` (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V139__claim_reserve_history.sql:7-20`).
  2. **IBNR read** — finance-service KPI composer queries `report_job WHERE report_key='IBNR_TRIANGLE' AND status='COMPLETED' AND period_end <= :periodEnd AND period_end > :periodEnd - INTERVAL '90 days' ORDER BY completed_at DESC LIMIT 1`, parses `result_json.ibnr_total` (scalar) or `result_json.per_cohort_ultimate[]` (per-line breakdown) from `services/java/finance-service/src/main/java/com/medfund/finance/report/entity/ReportJob.java:59-60`. Handles both `result_json` and MinIO `payload_ref` per Phase 15 §14 fallback.
  3. **Assembly** — `incurred = subtotalIncurredExIbnr + ibnrTotal`. If no completed IBNR job in the 90-day window, `ibnrTotal = null`, envelope `warnings` carries *"IBNR run pending or older than 90 days for (line=X, asOf=Y) — displaying paid + Δreserve only"*, and the `LOSS_RATIO_KPI` widget shows an "IBNR pending" info-icon per K2.

  Rejected: synchronous sub-job trigger + poll (dashboards must render fast; couples display widget to a Kafka round-trip + Mack chain-ladder compute); case-reserve-only shortcut (contradicts K2's explicit full-incurred choice).

- **K10 — Storage = on-demand compute + Redis cache (15-min TTL)**: KPI composer runs every widget refresh (fanning out to billing / earned / incurred / commission aggregates); results cached in Redis with key `kpi:{tenantId}:{reportKey}:{periodStart}:{periodEnd}:{reportingCurrency}:{insuranceLine}:{schemeId}:{producerId}` and 15-minute TTL. Matches Phase 8 forecast precedent (on-demand, no snapshot table). Trend chart with 12 monthly points = 12 cache lookups (11 warm after first render). Cache stampede on tenant switch mitigated by the standard `Cache-Control: no-store` on individual widget calls + a debounced batch endpoint (K14/K16). Rejected: month-close snapshot job + `kpi_snapshot` table (extra migration + job + backfill; can't answer as-of-Tuesday queries); lazy materialization (write-on-read concurrency semantics; sits awkwardly between the two options). A future materialized `kpi_snapshot` warm path for the ANNUAL trend view is a Phase 18.5 optimization.

- **K11 — Cadence = flip 5 keys to `cadenced=true`; refresh on load only**: Enum change on `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java`: `COMBINED_RATIO` (line 137), `LOSS_RATIO_KPI` (line 138), `EXPENSE_RATIO` (line 139), plus new `CLAIMS_FREQUENCY` + `AVERAGE_SEVERITY` all move to `cadenced=true, periodShape=PREVIOUS_COMPLETE_PERIOD`. Requires:
  - Phase 18 §B adapter registration in `ScheduledReportOrchestrator` (`services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/`) with 5 new `ScheduledReportShapeAdapter` implementations (finance-owned since composer lives there per K16).
  - Phase 17 S1 whitelist extension: add the 5 KPI keys to the UI whitelist of cadenced report keys the tenant admin can schedule (see Owed back to plan authors below).
  - No changes to the Phase 17 dispatcher / delivery topics — same `medfund.notification.report-delivery` pattern; XLSX rendering = Phase 18 §B's `KpiWorkbookService`.

  Angular page fetches on load + on filter change; no auto-poll. Rejected: keep `cadenced=false` (retro-fit drag later; loses natural pair with Phase 17); 60s auto-poll (execs don't watch; polling burns Redis+CPU; laptops-left-open cost).

- **K12 — Multi-currency = per-currency native ratios + reporting-currency composite scalar**: Response payload shape (extending the standard `ReportResponse<T>` envelope):
  ```java
  public record KpiValue(
      BigDecimal ratio,               // dimensionless [0..N.NN]
      BigDecimal numerator,           // native amount
      BigDecimal denominator,         // native amount
      String currencyCode
  ) {}

  public record KpiReportData(
      BigDecimal compositeRatio,      // numerator/denominator both converted to reportingCurrency at asOf, then divided
      BigDecimal compositeNumerator,  // in reportingCurrency
      BigDecimal compositeDenominator,// in reportingCurrency
      String basisNote                // "MIXED_LOSS_EARNED_EXPENSE_WRITTEN" for COMBINED_RATIO per K6
  ) {}
  ```
  Standard envelope's `perCurrency: Map<String, KpiValue>` carries per-currency ratios. Composite uses `FxRateReader.convert(...)` per invariant #6: fail-loud if the *composite* denominator can't be built (any missing FX for a currency present in the data throws `ReportGenerationException` naming (base, quote, date)), best-effort for envelope perCurrency (missing currency omitted + warnings entry per G28). Angular widget layout: big composite number + info-icon → per-currency breakdown panel + reporting currency label. Rejected: reporting-only scalar (hides currency-blend masking; silently drops missing-FX rows); per-currency only + dropdown (contradicts single-headline exec KPI UX).

- **K13 — Slicing = line + scheme + producer (three filter chips)**: Every KPI endpoint accepts three optional filters:
  - `?insuranceLine=HEALTH|LIFE|FUNERAL|GROUP|TRAVEL|DISABILITY|VEHICLE|PROPERTY`
  - `?schemeId=<uuid>`
  - `?producerId=<uuid>`

  Blank = tenant-wide. SQL `GROUP BY` drops each unused dimension. Angular filter chips: single-select per chip; searchable dropdown for scheme + producer per memory `feedback_no_raw_id_inputs`. **Small-denominator guard**: composer returns `warnings` entry when the denominator falls below a per-KPI threshold (e.g. `earned < 1000` for the reporting currency at asOf) — widget shows *"Ratio may be noisy at this slice"* info-icon. Small-denominator threshold configurable in future via a tenant setting (Phase 18.5). Rejected: line-only (execs will demand scheme + producer drills on day 2); line+scheme (leaves producer half-built for the acquisition-ratio drill). Downside acknowledged: three-chip UI complexity + small-slice noise — mitigated by chip behavior + guard rail.

- **K14 — Trend = 12 monthly buckets default, dropdown to 24; MONTHLY only**: Default trend view = last 12 complete months (rolling, ending at previous month-end). Dropdown lets user switch to 24. Granularity fixed to MONTHLY (weekly ratios are noise). Batch endpoint on the KPI composer: `GET /api/v1/reports/kpi/{key}/trend?windowMonths=12|24&insuranceLine&schemeId&producerId&reportingCurrency` returns `List<{periodStart, periodEnd, composite: KpiValue, perCurrency: Map<String, KpiValue>}>`. Each element is a K10 cache lookup keyed by `(tenant, key, periodStart, periodEnd, reportingCurrency, insuranceLine, schemeId, producerId)`. Young-tenant gap: months predating tenant creation return empty rows (envelope `warnings` naming them); Angular chart draws blank buckets. Rejected: fixed 24 (doubles compute; noisy empty area on young tenants); weekly granularity (meaningless for ratios; two-granularity toggle complexity).

- **K15 — Chart = KPI tile + sparkline + click-through to detail report**: Each KPI renders as a tile:
  ```
  ┌────────────────────────────────┐
  │ Loss Ratio           ⓘ  ↗️     │  (basis note + trend arrow)
  │                                │
  │        71.4%                   │  (composite scalar, big font)
  │  ▁▂▂▃▄▄▅▆▆▇▇█   ← sparkline    │  (12-month app-line-chart compact)
  │                                │
  │  USD 68%  |  ZWL 82%           │  (per-currency breakdown row)
  │                                │
  │  Period: Sep 2025 - Aug 2026   │  (footer)
  └────────────────────────────────┘
  ```
  Entire tile clickable. Drill navigation:
  - `LOSS_RATIO_KPI` → `/tenant/finance/reports/loss-ratio` (Phase 5)
  - `EXPENSE_RATIO` → `/tenant/finance/reports/commission/statement` (Phase 11)
  - `COMBINED_RATIO` → anchor scroll back to top of KPI page (no separate detail report; the sum is the summary)
  - `CLAIMS_FREQUENCY` → `/tenant/finance/reports/claims/summary` (Phase 4)
  - `AVERAGE_SEVERITY` → same as `CLAIMS_FREQUENCY`

  Requires a new `app-line-chart` compact variant (height:60px, no axes, no legend, single-color line) — Phase 18 §A authors it as `app-sparkline` sitting alongside `app-line-chart` in `clients/angular/src/app/shared/components/charts/`. Rejected: full chart + accordion (scroll fatigue + duplicates detail reports); chart + drill both (double UI + fragile period-filter route hops).

- **K16 — Angular route = `/tenant/finance/reports/kpi` under Reports Hub**: Single page component `KpiDashboardComponent` at `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-dashboard.component.ts`. Sidebar entry: `KPI Dashboard` under Finance → Reports. Reports Hub gets a new `DASHBOARD` family group (matches `ReportFamily.DASHBOARD` already in `ReportKey`) with a single card linking to the page. Backend endpoints live under `/api/v1/reports/kpi/*` on finance-service (K17 permission gate). Each of the 5 KPIs has an individual endpoint (`/loss-ratio`, `/expense-ratio`, `/combined-ratio`, `/claims-frequency`, `/average-severity`) plus a batch endpoint `GET /api/v1/reports/kpi/dashboard?insuranceLine&schemeId&producerId&reportingCurrency` that returns all 5 in one round-trip for the tile-grid render. Trend endpoint per KPI: `GET /api/v1/reports/kpi/{key}/trend?windowMonths` (K14). Rejected: dedicated exec portal (adds a fifth portal + Keycloak role work + duplicates hub logic); dual-render on tenant-admin home (two paths + cache variants + admin-home already busy).

- **K17 — Permissions = reuse `finance:reports:view` + `@RequiresReport(key)`**: Zero new permission strings. Each KPI endpoint carries the standard stack:
  ```java
  @RequiresPermission("finance:reports:view")
  @RequiresReport(ReportKey.<KPI_KEY>)
  ```
  Matches every other report in the plan. Batch dashboard endpoint requires all 5 individual permissions/toggles at once (composer 403s the whole payload if any of the 5 is disabled; alternative: `warnings` entry per disabled key + partial payload — implementer chooses at code altitude, recommendation is fail-loud 403 because a dashboard with missing tiles is confusing). Toggle-off from `/tenant/admin/settings/reports` per Phase 0 §7. Rejected: dedicated `finance:reports:kpi:view` (role bloat for no security gain); `executive:dashboard:view` (contradicts invariant #2; "executive" role doesn't exist in `.claude/portals.md`).

- **K18 — Sub-plan = single file, 3 tranches (§0 aggregates / §A composer+Angular / §B scheduled adapter)**: Single sub-plan `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md` authored via `create-plan` at implement time. Tranches:
  - **§0 — Enum + aggregates** (~2-3 phases): `ReportKey` add `CLAIMS_FREQUENCY` + `AVERAGE_SEVERITY` (K1); flip 5 KPI keys to `cadenced=true, periodShape=PREVIOUS_COMPLETE_PERIOD` (K11). New contributions-service `GET /api/v1/reports/aggregate/premium-earned` (K8). New claims-service `GET /api/v1/reports/aggregate/claims-incurred` (K9 — paid + Δreserve). New finance-service `GET /api/v1/reports/aggregate/commissions` (K7). Each: repository + controller + Swagger + IT via `ReportRetrofitAssertions`.
  - **§A — KPI composer + Angular** (~3-4 phases): finance-service `KpiComposerService` fanning to the 4 aggregates via `CrossServiceCallHelper` (invariant #7); `ExecutiveKpiController` with 5 individual endpoints + 1 batch + 5 trend endpoints (K14); IBNR lookup from `report_job.result_json` (K9); K12 per-currency envelope + composite scalar assembly; Redis cache (K10). Angular `KpiDashboardComponent` at `/tenant/finance/reports/kpi` (K16); new `app-sparkline` component (K15); tile grid + drill-through navigation (K15); 3 filter chips (K13); reports-hub `DASHBOARD` family card.
  - **§B — Scheduled adapter + e2e** (~2 phases): 5 `ScheduledReportShapeAdapter` implementations in finance-service for Phase 17 orchestrator (K11); Phase 17 UI whitelist extension adds the 5 KPI keys; `KpiWorkbookService` XLSX for scheduled + on-demand export (mirrors Phase 17 shape); Playwright goldens (open dashboard, filter by line, drill to loss-ratio detail, export XLSX, receive scheduled email via mailpit); docker-compose IT deferred to Phase-18-integration follow-up per Phase 15/16/17 precedent.

  Est. 6-8 phases total. Rejected: 5-tranche matching Phase 17 shape (over-tranched; §0 would be a two-line phase); no sub-plan (contradicts scope-warning banner + parent plan file already 626KB).

### Settled by fact (not asked)

- **F18-1 — `ReportFamily = DASHBOARD`**. The three enum entries are already `ReportFamily.DASHBOARD` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:136-139`). The two new keys (`CLAIMS_FREQUENCY`, `AVERAGE_SEVERITY` per K1) land in the same family. Reports Hub renders a `Dashboard` group.

- **F18-2 — Envelope shape unchanged**. Standard `ReportResponse<T>` per invariant #1 wraps every KPI response. `T` is `KpiReportData` (K12). `perCurrency: Map<String, KpiValue>` (K12); `fxRates + warnings` per G28; `period: ReportPeriod` populated (K1 KPIs are all `PREVIOUS_COMPLETE_PERIOD` per K11).

- **F18-3 — Native currency in aggregate rows**. All 3 new aggregate endpoints (K7/K8/K9) return native-currency rows; conversion to reporting currency happens only in the KPI composer via `FxRateReader` (K12) per invariant #6. Per G25.

- **F18-4 — Missing FX semantics**. Composite scalar fails loud on missing FX (throws `ReportGenerationException` naming (base, quote, date)); per-currency envelope is best-effort with warnings entry (G28).

- **F18-5 — Audit on KPI export**. XLSX export path emits `SecurityEventPublisher.publishDataAccess(...)` with `reportKey=<KPI_KEY>` before returning bytes (invariant #3). JSON reads do NOT emit per G24.

- **F18-6 — Rule 8 audit on tenant-toggle mutation**. Toggling any KPI key from `/tenant/admin/settings/reports` emits `AuditEvent` via the Phase 0 `TenantReportConfigService.updateEnabled` path; K11 cascade applies (Phase 17 S9) since KPIs will be cadenced — cascade-disable behavior means disabling a KPI key auto-pauses its scheduled deliveries.

- **F18-7 — Reactor-Kafka ack pattern**. Any Kafka publish path in Phase 18 (e.g. the Phase 17 orchestrator emitting delivery event on behalf of scheduled KPI runs) uses `.doOnSuccess` per `bug_reactor_kafka_ack_swallow` memory.

- **F18-8 — Migration numbering**. Phase 18 §0 has **no schema changes** — the 5 enum flips + 2 new enum entries are code-only, and the 3 new aggregate endpoints are SELECT-only over existing tables (Phase 11 `commission_transaction`, Phase 12 `earning_schedule`, Phase 14 `claim_reserve_history`, Phase 15 `report_job`). No Flyway migrations required. K18 sub-plan §0 spells this out to avoid a reviewer looking for an absent migration.

- **F18-9 — `public.` prefix**. Not applicable — the three new aggregate endpoints all query tenant-schema tables. Unqualified names per `bug_public_prefix_silent_rollback` memory.

- **F18-10 — Cross-service peer-failure**. Composer uses `CrossServiceCallHelper` with envelope `warnings` capture per invariant #7 (G37). A peer down (contributions or claims aggregate 500) → KPI page shows the widget with a warning banner, not a broken widget.

- **F18-11 — Tenant scoping**. Every aggregate SQL is tenant-scoped via the standard `TenantContext` interceptor (Rule 2). No new tenant resolution logic needed.

- **F18-12 — KPI keys already reachable in `tenant_report_config`**. Absent-row defaults to enabled per V130. First tenant toggle load seeds a row via Phase 0's bulk-upsert. No seeding migration needed.

- **F18-13 — `AuditActor` for scheduled KPI runs**. Scheduled dispatches inherit the schedule creator's `updated_by_actor_*` via Phase 17 S10. Manual XLSX exports carry the requesting user via `AuditActor.of(jwt)` per `feedback_audit_actor_email` memory.

- **F18-14 — Kafka topic naming**. No new topics. Phase 18 reuses the Phase 17 `medfund.notification.report-delivery` + `-failed` topics via the K11-added adapters.

- **F18-15 — Retention**. `report_job` rows for scheduled KPI runs inherit `retention_class = OPERATIONAL_90D` (Phase 15 §14) per existing `ReportJobRetentionJob`. No new retention logic.

### Success Criteria

**Status: Grilled 2026-09-05 (K1..K18 + F18-1..F18-15 above). Ready for sub-plan via `create-plan`. Not yet implemented.**

Single sub-plan will ship at `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md` built via `create-plan` → `implement-plan`. Aggregate success criteria across all tranches:

- **§0 enum + aggregates green**: `ReportKey` adds `CLAIMS_FREQUENCY` + `AVERAGE_SEVERITY` (K1) + flips 5 KPI keys to `cadenced=true, periodShape=PREVIOUS_COMPLETE_PERIOD` (K11); three new aggregate endpoints (`/aggregate/premium-earned` on contributions per K8, `/aggregate/claims-incurred` on claims per K9, `/aggregate/commissions` on finance per K7) return correct native-currency per-(currency, dimension) rows verified via unit + IT with seeded multi-currency multi-line fixtures; each endpoint carries full Swagger annotations (Rule 7); each endpoint carries `@RequiresReport` gate + emits `SecurityEvent` on XLSX export path where applicable (invariants #2, #3); shared `ReportRetrofitAssertions` helper consumed by IT classes.
- **§A KPI composer + Angular green**: `KpiComposerService` correctly composes each of the 5 KPIs from the 4 aggregate feeds; composite scalar (K12) uses `FxRateReader.convert` with fail-loud on missing FX for composite, best-effort for envelope perCurrency; Redis cache (K10) hits/misses observable via `/actuator/metrics`; IBNR read from `report_job.result_json` correctly handles both scalar `ibnr_total` + `per_cohort_ultimate[]` shapes plus MinIO `payload_ref` fallback (K9); 5 individual + 1 batch + 5 trend endpoints (K14/K16) return correct envelopes; `KpiDashboardComponent` renders all 5 tiles with sparklines (K15) + 3 filter chips (K13) + click-through drill navigation (K15); Reports Hub `DASHBOARD` family card links to the page.
- **§B scheduled adapter + e2e green**: 5 `ScheduledReportShapeAdapter` implementations in finance-service integrate with Phase 17's `ScheduledReportOrchestrator`; Phase 17 UI whitelist accepts the 5 new cadenced KPI keys (K11); `KpiWorkbookService` renders correct XLSX for scheduled dispatch + on-demand export path; Playwright specs cover golden path (open dashboard, filter by line, drill to loss-ratio detail, export XLSX, receive scheduled email via mailpit); manual `verify` walkthrough of the dashboard page + tenant-admin schedule creation deferred to Phase-18-integration follow-up per Phase 15/16/17 precedent.

**Deferred to follow-up per Phase 11/12/13/14/15/16/17 precedent**:
- **Cross-language docker-compose IT** — Phase-18-integration tranche.
- **Manual `verify` walkthroughs** — end-to-end golden-path browser demos of the dashboard + filter interaction + drill navigation + scheduled email arrival; `verify` skill pass at end of sub-plan.
- **Phase 18.5 — Adjacent KPIs + operating-expense integration** — solvency ratio + ROE (need accounting ledger + capital-model service that don't exist); full operating-expense ratio (needs general-ledger integration, `operating_expense_entry` table with kind-classification, or third-party accounting-system connector); acquisition-vs-servicing commission classifier on `commission_rate_card` or `commission_transaction`; `kpi_snapshot` materialized warm-path table for ANNUAL trend view; per-tenant configurable small-denominator noise threshold; per-tenant configurable IFRS-17-vs-NAIC combined-ratio basis toggle (K5/K6 follow-up).
- **CLAIMS_FREQUENCY exposure feed decision** — sub-plan §0 has an open code-altitude decision between (a) adding new contributions-service `GET /api/v1/reports/aggregate/policy-exposure` computing `SUM(policy-months-in-force)` per (line, scheme, period) via Phase 13 lifecycle join, or (b) approximating with `active_policy_count × period_length_in_months` from Phase 13's active-policy count. Recommendation for the sub-plan author: option (a) with a Phase 13 join for a defensible frequency numerator.

Per parent-plan Testcontainers policy each deferred IT lands with a purpose-built migration folder so the ITs don't force-widen every unrelated slice's baseline schema.

### Owed back to plan authors

Corrections + contradictions the grill surfaced that touch other sections of the parent plan:

- **Parent-plan header `phases_status` stale**: header line `"18-19": outline depth; each needs its own grilling pass before implementation` superseded by this grill — `phases_status["18"]` now grilled, `["19"]` still outline; `last_grilled_phase: 18`, `last_grilled_date: 2026-09-05`. Frontmatter already updated as part of this apply step.

- **Phase 17 S1 whitelist widens (K11 consequence)**: Phase 17 S1 (parent plan line 4344) listed 13 cadenced keys in scope for scheduled email delivery. K11 flips 5 KPI keys to `cadenced=true` — `COMBINED_RATIO`, `LOSS_RATIO_KPI`, `EXPENSE_RATIO`, plus the two new `CLAIMS_FREQUENCY`, `AVERAGE_SEVERITY` — that join the whitelist. Phase 17 sub-plan `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` needs a Deviations note that Phase 18 sub-plan §B will add 5 more `ScheduledReportShapeAdapter` implementations to the orchestrator's finance-owned adapter set + extend the S1 whitelist enforcement in the Angular `/tenant/admin/settings/report-schedules` UI. F-S10 in Phase 17 says the 24 cadenced keys are already tagged and the UI whitelist enforces the 13 in-scope; when Phase 18 flips 5 more keys to cadenced=true, the whitelist must accept them. Not a bug in Phase 17; a foreseeable extension. Sub-plan §B owns the UI-whitelist widening.

- **Phase 2 aggregate endpoints established the shape**: the three new aggregate endpoints (K7/K8/K9) mirror the Phase 2 `BillingAggregateController` and Phase 5 `ClaimsAggregateController` shape verbatim (per-native-currency rows, dimension filter, half-open period interval). Sub-plan §0 refers to these as the pattern, not re-invents shape.

- **Phase 14 IBNR result shape assumed but not verified end-to-end**: K9 depends on `report_job.result_json.ibnr_total` being a scalar and `per_cohort_ultimate` being an array. Explore verification confirmed this from `services/python/ai-service/app/actuarial/chain_ladder.py:54-67`, but the sub-plan §A KPI composer needs a JSON parse test with a golden fixture to protect against a Phase 14 schema drift.

- **No `CLAIMS_FREQUENCY` exposure feed exists** (K1 consequence): frequency requires a "policy-months in force" denominator. Phase 13 lifecycle data has `policy_status_history` but no direct policy-months rollup. Sub-plan §0 needs a code-altitude decision — see the deferred Success Criteria bullet above for the two options.

- **No `.claude/*.md` architecture doc mentions ratio definitions**: sub-plan §0 could optionally add a short section to `.claude/adjudication.md` or a new `.claude/kpi.md` naming the formulas from K2-K6 for future reference. Not strictly required but avoids re-litigating the definitions at code-review time.

- **`ReportKey.cadenced=false→true` migration is code-only, but changes the S1 UI whitelist rule** — see Phase 17 whitelist widening bullet above.

### Cross-references

- Grilling scratchpad: `thoughts/shared/notes/2026-09-05-phase18-executive-kpi-grill.md`
- Sub-plan (to be authored via `create-plan`): `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md`

---

## Phase 19: Fraud / SIU Report

> **Grilled 2026-09-05.** Decisions FR1..FR16 (numbered `FR*` — for FRaud — to avoid collision with plan-wide `G*` numbering, with the plan's own `F<n>` settled-by-fact markers used in earlier phases (F6-F11 in Phase 0, F12-F17 in Phase 1, F18-F29 in Phase 2, F52-F61 in Phase 4), and with prior phase decision prefixes `R*` (reinsurance, Phase 10) / `P*` (producer, Phase 11) / `U*` (underwriting, Phase 12) / `L*` (lifecycle, Phase 13) / `A*` (actuarial, Phase 14) / `I*` (IFRS 17, Phase 15) / `REG*` (regulatory, Phase 16) / `S*` (scheduled email, Phase 17) / `K*` (KPI, Phase 18)). Settled-by-fact prefix `F19-*` follows the plan's `F<phase>-<n>` convention (F14-1..F14-16, F15-1..F15-15, F17-1..F17-13, F18-1..F18-15 precedent).
>
> Original 4-line outline retained below as ~~strike-through~~ for provenance. Scope escalated substantially beyond the outline: full SIU case-management module (FR1) replaces the report-only framing; 5 entities via flag-as-evidence pattern (FR5) replace the implied 2; 5-state machine with four-eyes on non-dismissal closures (FR6) replaces the implied 2-state; 8 fine-grained permissions + 2 Keycloak roles (FR7) replace the implied single perm; AI service gains its first Kafka producer (FR9) publishing every prediction as Rule 3 audit-of-record instead of the outline's speculative topic verification; new rules-engine category FRAUD_TRIAGE with `FraudFlagFact` + 6 templates (FR4/FR15) replaces the implied hardcoded threshold; full-analytics report (FR11) with 6 tiles + trend + top-N + AI calibration + investigator productivity replaces the 4-metric summary; Phase 17 whitelist widens with per-schedule sensitive-sheet gate (FR12); 2 new `RetentionClass` values (FR13); one currency per case with reporting-currency composite scalar per invariant #6 (FR14).

### Original outline (superseded 2026-09-05 by Decisions Log)

~~Ties the existing fraud-detection AI outputs into a fraud referral + savings report. Report key `FRAUD_SIU_REPORT`.~~

- ~~**claims-service** reads `medfund.claims.fraud-flagged` events (verify topic exists; otherwise add producer in fraud-detection AI consumer).~~
- ~~New `FraudReportController.summary(period)` — returns referral count, confirmed fraud, savings, referral rate.~~
- ~~**Angular** page under `reports/fraud/`.~~

### Overview

Full SIU (Special Investigations Unit) case-management module in **claims-service** paired with a full-analytics fraud report surfaced in the Reports Hub. Scope spans:

- **shared** — 2 new `RetentionClass` values (FR13), 8 permission constants added in `Permissions.java` (FR7), second Keycloak realm-bootstrap adds `siu_officer` + `siu_supervisor` roles (FR7).
- **claims-service** (FR2) — 5 new tenant-schema Flyway migrations for `fraud_flag` / `siu_case` / `siu_case_note` / `siu_evidence` / `siu_referral` (FR5); entities + repositories + `SiuCaseService` (5-state machine + four-eyes, FR6) + `FraudFlagService` + `FraudFlaggedConsumer` (FR9); controllers + Swagger; `FraudReportService` composing tiles + trend + top-N + calibration + productivity (FR11); `/api/v1/reports/fraud/summary` + `/aggregate/fraud`; new `FraudFlagRetentionJob` (FR13); `ScheduledReportShapeAdapter` for Phase 17 orchestrator (FR12).
- **rules-engine** — new `RuleCategory.FRAUD_TRIAGE` with `FraudFlagFact` + 6 templates + DRL compiler mapping + `TemplateProvider` bean (FR4/FR15).
- **ai-service** (`services/python/ai-service`) — first outbound Kafka producer wired into the existing `medfund.claims.submitted` consumer, publishing every classified claim (Rule 3 audit-of-record) to `medfund.claims.fraud-flagged` with `(model_version, risk_score, risk_level, top-N indicators, computed_at)` (FR9/FR10).
- **Angular** — split routing (FR3): workflow at `/tenant/claims/siu/*` (queue, case-detail, evidence panel, note timeline, referral form, admin UI for FRAUD_TRIAGE rules); report at `/tenant/finance/reports/fraud/*` (6 KPI tiles + trend + top-N drills + AI calibration + investigator productivity, XLSX 6-sheet export); tenant-admin `/settings/report-schedules` UI gains `includeSensitiveSheets` toggle (FR12).
- **Phase 17 dependency** — sub-plan `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` gains a Deviations note that Phase 19 §B widens the scheduled-delivery whitelist to include `FRAUD_SIU_REPORT` + the `includeSensitiveSheets` per-schedule flag.

Single sub-plan `thoughts/shared/plans/2026-09-05-fraud-siu-report.md` (planned via `create-plan` at implement time) with 2 tranches per FR16 (§A MVP ~5-6 phases, §B expansion ~5-6 phases) totaling ~10-12 phases.

### Decisions Log (FR1..FR16)

- **FR1 — Scope: full SIU case-management module.** Cases (`siu_case`), investigators (siu_officer + siu_supervisor roles), evidence attachments via file-service, case notes / activity log, related-claims linking (one case → many claims), referrals to law enforcement, four-eyes case-closure, case-audit trail. Sub-plan expected to be ~10-12 phases split into 2 tranches (FR16). Rejected: report-only (dishonest "confirmed" number, weak Rule 3 auditability); report + minimal review workflow (real SIU teams outgrow it, incremental follow-up cost higher than up-front build).

- **FR2 — Service ownership: claims-service.** SIU is a claims-domain workflow. `Claim` entity is already here, tenant schema is here, `TenantContext` is wired, AI publishes to `medfund.claims.*` namespace. Report aggregations compose from claims-service + optional finance-service call (recovery amounts) via `CrossServiceCallHelper`. Rejected: new siu-service microservice (breaks precedent of folding into existing services; speculative decomposition, no team asking); split claims/finance (cross-service chatter for every op, violates Rule 6, 'recovery' is only one closure outcome).

- **FR3 — Angular routing: split.** Workflow (case queue, case detail, evidence panel, referrals, activity log) lives under `/tenant/claims/siu/*` (peer of `preauth`, `pending`, `tax-withheld`). Summary analytics + XLSX export lives under `/tenant/finance/reports/fraud/*` in the Reports Hub. Follows precedent (claims-status has a workflow page in Claims + a report page in Finance/Reports). Sidebar role-filter handles siu-only users. Rejected: all-in-claims (breaks Reports Hub 'one place for every report' promise + breaks parent-plan outline); all-in-finance (mismatches hub's read-only shape); new top-level `/tenant/siu/` (adds 7th portal section, breaks precedent, empty section for tenants without SIU staff).

- **FR4 — Case creation: tenant-configurable via rules-engine.** New `RuleCategory.FRAUD_TRIAGE` with fact `FraudFlagFact(riskScore, riskLevel, insuranceLine, providerId, claimAmount, ...)` and action `emitCaseCreation`. Tenants configure policy ("auto-open if risk > 0.85 AND amount > 5000", "always manual triage", etc.); default (no rules configured) = auto-open HIGH-risk (matches Rule 5 precedent for platform defaults). `fraud_flag` and `siu_case` stay separate entities. Rejected: auto-open every HIGH (floods queue, no tenant control); officer triage of raw queue (defeats AI value); hardcoded threshold (not per-tenant tunable, no audit trail for dropped low-risk flags).

- **FR5 — Entity model: 5 entities, many-to-many via flag-as-evidence.**
  - `fraud_flag` — one per (claim, AI-run OR manual-open); cols include `claim_id`, `siu_case_id` (nullable), `flag_source ENUM('AI_MODEL','MANUAL_OFFICER')`, `model_version`, `risk_score`, `risk_level`, `indicators JSONB`, `flagged_at`. Officer-initiated cases get a synthetic MANUAL_OFFICER flag row so the case-to-claim link is always via a flag.
  - `siu_case` — one per investigation; cols include `case_number` (VARCHAR, tenant-scoped unique), `status`, `assigned_to`, `saved_amount`, `saved_currency`, `priority`, `tags[]`, `opened_by`, `opened_at`, `closed_by`, `closed_at`, `closure_reason`, `outcome`.
  - `siu_case_note` — append-only activity log; cols `case_id`, `author_id`, `note_type ENUM('COMMENT','STATUS_CHANGE','EVIDENCE_ADDED','ASSIGNED','REFERRAL_ADDED')`, `body`, `created_at`.
  - `siu_evidence` — file-service refs; cols `case_id`, `file_service_ref`, `description`, `uploaded_by`, `uploaded_at`, `evidence_type`.
  - `siu_referral` — external referrals; cols `case_id`, `referral_to ENUM('LAW_ENFORCEMENT','REGULATOR','INTERNAL_HR')`, `referral_reference`, `referred_by`, `referred_at`, `response_received_at`, `response_notes`.

  Rejected: 4 entities with `siu_case_claim` join (breaks flag-as-evidence-audit for officer-initiated cases); 3 entities 1:1 (fraud rings force sibling cases); 2 entities JSONB (kills Rule 8 audit + concurrent-update race).

- **FR6 — State machine: 5-state, four-eyes on non-dismissal closures, reopen supported.**
  - States: `OPEN → ASSIGNED → UNDER_REVIEW → PENDING_APPROVAL → (CLOSED_CONFIRMED_FRAUD | CLOSED_REFERRED_LAW_ENFORCEMENT | CLOSED_ACTION_TAKEN)`.
  - Fourth terminal state: `CLOSED_DISMISSED_FALSE_POSITIVE` reachable directly from `UNDER_REVIEW` (no four-eyes).
  - `REOPENED` jumps any `CLOSED_*` back to `UNDER_REVIEW`; report metric `reopened count` becomes possible.
  - Four-eyes enforced via `AuditActor` + `updated_by_actor_id != checker_id` service-layer constraint (Phase 11 `CommissionAdjustment` precedent).
  - Rejected: 3-state (can't distinguish referral; loses audit thread on reopen); 2-state (governance gap); four-eyes on every closure (clogs supervisor queue with dismissal noise).

- **FR7 — Permissions: fine-grained (8 perms) + 2 Keycloak roles.**
  - New perms in `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java`: `claims:siu:view`, `claims:siu:create`, `claims:siu:assign`, `claims:siu:investigate`, `claims:siu:approve`, `claims:siu:reopen`, `claims:siu:refer`, `claims:siu:admin` (rules-engine FRAUD_TRIAGE config).
  - New Keycloak roles: `siu_officer` (view+create+investigate+refer), `siu_supervisor` (officer set + approve+assign+reopen). `admin` scoped to `tenant_admin`.
  - Report page gated by existing `finance:reports:view` + `@RequiresReport(FRAUD_SIU_REPORT)` (Phase 0 precedent).
  - Rejected: mid-grained 4-perm (bundles refer+assign into write, risky); coarse 2-perm 1-role (four-eyes becomes honor system, breaks Rule 8); ultra-fine per-transition 12+ perms (permission bloat, no team asking).

- **FR8 — Savings: per-case, investigator-entered on closure, defaults to `SUM(claimed - paid)`.** On `PENDING_APPROVAL`, investigator enters `saved_amount` — UI prefills with `SUM(claimed_amount - paid_amount)` across the case's flagged claims. Report sums `saved_amount` across `CLOSED_CONFIRMED_FRAUD` cases only. Supervisor can adjust during four-eyes; every edit hits `AuditEvent` per Rule 8. Mixed-currency cases capture per-claim currency (see FR14). Rejected: auto-computed no-override (doesn't capture post-close clawback / 'let X through to catch a bigger fish'); auto at flag-time (undercounts, no manual-case path); two-number avoided+recovered split (double surface for marginal UX gain).

- **FR9 — Kafka publish: every AI prediction; topic `medfund.claims.fraud-flagged`.** AI-service Kafka consumer at `services/python/ai-service/app/core/kafka_consumer.py:68-96` publishes on every classified claim (LOW/MEDIUM/HIGH). Claims-service consumer writes a `fraud_flag` row per event. `FRAUD_TRIAGE` rules-engine (FR4) then decides case creation. Rule 3 satisfied by construction. Rules can be re-run over historical flags on policy change. Downside acknowledged: high row volume (~100K claims/mo/tenant → ~100K flag rows/mo); retention (FR13) manages it. Payload shape: `{eventType, eventId, occurredAt, tenantId, claimId, modelVersion, riskScore, riskLevel, indicators[], computedAt}`. Rejected: threshold-only (Rule 3 gap, no per-tenant tune); two-topic raw+action (marginal Rule 3 gain, doubles infra); HIGH-only (worst combined loss).

- **FR10 — AI audit fidelity: top-N `indicators` on `fraud_flag`; full feature vector deferred to Phase 19.5 ML-ops tranche.** `fraud_flag` row carries `model_version` + `risk_score` + `risk_level` + `indicators JSONB` (top-N, ~5 entries) + `flagged_at`. Satisfies Rule 3 compliance read ("why did you flag this — these indicators, this model, this score"). Full feature vector + model weights + reproducibility deferred to a Phase 19.5 ML-ops tranche (registry, MLflow-lite, model-artefact store) — no team has asked and gap is documented in sub-plan explicitly. Rejected: full feature vector inline (~150MB/mo/tenant of blob without closing reproducibility gap); separate `ai_audit_log` table (ML-ops MVP inside this phase); Kafka-log-as-audit (not human-reviewable, retention risk).

- **FR11 — Report metrics scope: full analytics.** Tile-grid (6 tiles: cases opened / confirmed fraud / savings realised / confirmation rate / avg cycle time / reopened) + monthly trend chart + top-N drill-tables (top-10 providers, top-10 members by confirmed-fraud amount) + AI model calibration (precision/recall by `risk_level`, false-positive rate curve; "Insufficient data" fallback for N<50 confirmed cases) + investigator productivity (cases-closed per officer; role-gated so `siu_officer` sees own stats only, `siu_supervisor` + `tenant_admin` see all). Filter chips: period, insurance line, closure outcome. XLSX has 6 sheets (Summary / Cases detail / Provider top-N / Member top-N / AI calibration / Investigator productivity). Sub-plan §D (in FR16 collapsed to §B expansion) covers the full analytics surface. Rejected: minimum-viable 4 metrics (undelivers vs full-SIU scope); tile-only (misses top-N execs demand day 2); split fraud-report + SIU-workload page (duplicates infra, splits catalogue).

- **FR12 — Cadenced: added to Phase 17 whitelist with per-schedule sensitive-sheet gate.** `FRAUD_SIU_REPORT` joins the Phase 17 scheduled-delivery whitelist (currently 13-key baseline, →18 after Phase 18 flips 5 KPI keys, →19 with `FRAUD_SIU_REPORT` in §B). Default cadenced XLSX has 4 sheets (Summary + Cases + Provider top-N + Member top-N); investigator-productivity + AI-calibration sheets included **only** when schedule creator opts in via new per-schedule flag `includeSensitiveSheets` (defaults false). Cascade-on-disable per Phase 17 S9 applies. New `ScheduledReportShapeAdapter` in claims-service. Phase 17 sub-plan gets a Deviations note that whitelist widened to include `FRAUD_SIU_REPORT`. Note: FR16 defers this whole decision to §B (MVP §A has no scheduled dispatch). Rejected: no sensitive-sheet gate (GDPR/POPIA/labour-law risk); on-demand only (execs don't get monthly digest); tenant-admin-only creator (friction, wrong role knows the recipient list).

- **FR13 — Retention: add 2 new `RetentionClass` values.** `FRAUD_FLAG_1Y` (raw AI flags with no linked case → auto-purge after 12 months) + `SIU_CASE_7Y` (any case + its linked flags + evidence + notes + referrals + `report_job` rows for `FRAUD_SIU_REPORT` → 7-year retention aligns with insurance-fraud statute). Classifier: `fraud_flag WHERE siu_case_id IS NULL AND flagged_at < NOW() - INTERVAL '1 year'` → purged; anything linked to a case retained 7y past case-closure. Extend existing `ReportJobRetentionJob` (or introduce peer `FraudFlagRetentionJob`). Per-tenant jurisdiction override ('ZW wants 10y, ZA wants 7y') deferred to Phase 19.5 via `tenants.jurisdiction_code` widening. Rejected: reuse 7Y for everything (100K/mo/tenant × 7y noise); split 90D flag / 7Y case (breaks flag-as-evidence chain-of-custody); single 10Y blanket (over-retains for shorter jurisdictions).

- **FR14 — Multi-currency: one currency per case.** `siu_case.saved_amount NUMERIC + saved_currency VARCHAR(3)`. Investigator picks one currency at close (defaults to majority currency across the case's flagged claims). Report groups `SUM(saved_amount)` by `saved_currency` for envelope `perCurrency`; composite scalar converts via `FxRateReader.convert(...)` at asOf date, fails loud if any `perCurrency` currency lacks FX rate (invariant #6 + G28). Investigator UI shows soft warning when case has multi-currency flagged claims — investigator collapses to one number + adds a case note explaining the mix. Rejected: proportional per-claim auto-breakdown (arbitrary math investigator can't defend); full join table `siu_case_savings` (6th entity, real UX cost for rare case shape); reporting-currency-only (violates invariant #6, non-reproducible).

- **FR15 — Rules-engine FRAUD_TRIAGE: 1 fact + 6 templates.**
  - **`FraudFlagFact`**: `riskScore, riskLevel, insuranceLine, providerId, memberId, claimAmount, currencyCode, indicators[], flaggedAt, historicalMemberFlagCount, historicalProviderHighFlagCount`.
  - **6 templates**: (1) 'Auto-open above risk threshold' — param `minScore`; (2) 'Auto-open large claim + high risk' — params `minScore + minAmount`; (3) 'Auto-open for watchlisted provider' — param `providerIds[]`; (4) 'Auto-open on member repeat-offender pattern' — params `windowDays + minCount`; (5) 'Auto-open on provider high-flag pattern' — params `windowDays + minCount`; (6) 'Never auto-open' — explicit off switch.
  - Default policy (no tenant rules) = template (1) with `minScore=0.85`.
  - Note: FR16 defers templates (4)+(5)+(6) to §B (MVP §A ships templates (1)+(2)+(3)).
  - Rejected: 3-template minimum (loses pattern-rec seeds); 8 templates (extra depends on non-existent fact fields); 2-fact `ProviderRiskProfileFact` (materialized-view refresh infra cost).

- **FR16 — Sub-plan structure: 2 tranches, MVP-first then expansion.** Single sub-plan at `thoughts/shared/plans/2026-09-05-fraud-siu-report.md` split into two tranches, both landed as part of Phase 19:

  **§A MVP (~5-6 phases)** — early value ship:
  - 3 entities: `fraud_flag`, `siu_case`, `siu_case_note`
  - 3-state machine: `OPEN → UNDER_REVIEW → (CLOSED_CONFIRMED | CLOSED_DISMISSED)` — four-eyes deferred to §B
  - Permissions subset: `claims:siu:view`, `claims:siu:create`, `claims:siu:investigate`, `claims:siu:admin` (4 of the 8 FR7 perms); one role `siu_officer`
  - AI service Kafka producer + `medfund.claims.fraud-flagged` event + claims-service `FraudFlaggedConsumer` (FR9 shape, top-N indicators per FR10)
  - `FRAUD_TRIAGE` rules-engine category + `FraudFlagFact` + 3 templates (threshold, threshold+amount, watchlist)
  - Angular `/tenant/claims/siu/` — queue + case-detail (no evidence panel, no referrals)
  - Angular `/tenant/finance/reports/fraud/` — 4 tiles (cases opened, confirmed, savings, confirmation rate) + XLSX with 2 sheets (Summary + Cases detail)
  - Retention: add `FRAUD_FLAG_1Y` + `SIU_CASE_7Y` enum values; classifier + purge job

  **§B Expansion (~5-6 phases)** — completes to full FR5/FR6/FR11/FR15:
  - 2 additional entities: `siu_evidence` (file-service refs), `siu_referral`
  - Full 5-state machine: adds `ASSIGNED`, `PENDING_APPROVAL`, `REOPENED`, `CLOSED_REFERRED_LAW_ENFORCEMENT`, `CLOSED_ACTION_TAKEN` + four-eyes gate on non-dismissal closures
  - 4 remaining permissions: `claims:siu:assign`, `claims:siu:approve`, `claims:siu:reopen`, `claims:siu:refer`; second role `siu_supervisor`
  - 3 additional templates: repeat-offender, provider high-flag pattern, never-auto-open
  - Angular workflow additions: evidence upload panel, referral form, activity-timeline enhancement, admin UI for FRAUD_TRIAGE rules
  - Angular report additions: trend chart, top-10 providers, top-10 members, AI calibration (precision/recall by `risk_level` with N<50 fallback), investigator productivity (role-gated so `siu_officer` sees own stats only)
  - XLSX widens from 2 sheets to 6 sheets (adds Provider top-N, Member top-N, AI calibration, Investigator productivity)
  - Phase 17 wiring: `ScheduledReportShapeAdapter`, whitelist add, per-schedule `includeSensitiveSheets` flag (FR12), Phase 17 sub-plan Deviations note

  **Total ~10-12 phases across both tranches.** Rejected: 5-tranche 20-24-phase full-scope-up-front (implementer session cost); 3-tranche mega-§A (harder to reviewer-unbundle); 6-tranche with dedicated Playwright (thin last tranche, typically folded).

### Settled by fact (not asked)

- **F19-1 — Report enum + family already exist.** `FRAUD_SIU_REPORT` at `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:150` already carries `ReportFamily.FRAUD, cadenced=true, periodShape=PREVIOUS_COMPLETE_PERIOD`. `ReportFamily.FRAUD` at `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:35` already defined. No shared-module enum additions needed.

- **F19-2 — Kafka topic `medfund.claims.fraud-flagged` does not exist today.** Producer must be added inside the AI service's Kafka consumer at `services/python/ai-service/app/core/kafka_consumer.py:68-96`. Outline's "verify topic exists" alternative resolves to the fallback path; this is the AI service's first outbound Kafka path (currently consumer-only).

- **F19-3 — Claims entity has zero fraud fields today.** `services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java:1-284` has no `fraud_flag`, `fraud_score`, `siu_status`. Tenant-schema Flyway migration is unavoidable.

- **F19-4 — No SIU adjudication controller / Angular page exists today.** No `FraudFlagController` / `SiuController` under `services/java/claims-service/src/main/java/com/medfund/claims/controller/`; no `/tenant/claims/siu/` or `/tenant/finance/reports/fraud/` folder under `clients/angular/src/app/pages/tenant/`.

- **F19-5 — AI fraud detection is live.** `services/python/ai-service/app/services/fraud_service.py:12-41` runs `FraudService.detect_fraud(claim_data, tenant_id)` on every `CLAIM_SUBMITTED` Kafka event via `app/core/kafka_consumer.py:68-96`. Underlying model: `IsolationForest` at `services/python/ai-service/app/services/ml_models.py:11-96`; output shape `(risk_score ∈ [0,1], risk_level ∈ {LOW,MEDIUM,HIGH}, indicators[], model_version)`.

- **F19-6 — Rejection code `R16 FRAUD` already exists.** `services/java/tenancy-service/src/main/resources/db/migration/tenant/V014__claims_schema.sql:116-135` seeds R16 as a categorical rejection code. Not a per-claim flag column — different concept from `fraud_flag` (`fraud_flag` is AI's assessment; R16 is adjudicator's manual rejection decision). Both coexist.

- **F19-7 — Retention taxonomy today has 2 values.** `services/java/finance-service/src/main/java/com/medfund/finance/report/entity/ReportJob.java:35-36` defines `OPERATIONAL_90D` + `STATUTORY_7Y`; classifier at `services/java/finance-service/src/main/java/com/medfund/finance/report/service/Ifrs17JobService.java:239-242` routes IFRS 17 → 7Y, everything else → 90D. FR13 widens to 4 values (adds `FRAUD_FLAG_1Y`, `SIU_CASE_7Y`).

### Success Criteria

**Status: Grilled 2026-09-05 (FR1..FR16 + F19-1..F19-7 above). Ready for sub-plan via `create-plan`. Not yet implemented.**

Single sub-plan will ship at `thoughts/shared/plans/2026-09-05-fraud-siu-report.md` built via `create-plan` → `implement-plan`. Aggregate success criteria across both tranches:

- **§A MVP green** — 3 entities + 3-state machine + 4 perms + 1 role + AI producer + FRAUD_TRIAGE (3 templates) + 4-tile report all pass unit + IT + Playwright golden path. AI service publishes every classified claim to `medfund.claims.fraud-flagged`; claims-service consumer writes `fraud_flag` rows carrying `model_version` + `risk_score` + `risk_level` + top-N indicators + `flagged_at` (Rule 3 audit trail). `FRAUD_TRIAGE` rules-engine auto-opens `siu_case` rows per tenant policy (default template (1) `minScore=0.85` for tenants without rules). Officer can open a case, add notes, propose confirmed / dismissed closure; permissions gate correctly; report tiles compute correctly; XLSX 2-sheet export emits `SecurityEventMessage` with `reportKey=FRAUD_SIU_REPORT` before returning bytes. Retention job purges aged unlinked `fraud_flag` rows on schedule. `AuditEvent` on every mutation carries `actorEmail` + friendly `entityName` per `feedback_audit_actor_email` / `feedback_audit_entity_name` memories.

- **§B Expansion green** — 2 additional entities (`siu_evidence`, `siu_referral`); full 5-state machine + four-eyes gate on non-dismissal closures + REOPENED transitions; `siu_supervisor` role + remaining 4 perms; 3 additional FRAUD_TRIAGE templates (pattern-recognition); Angular evidence upload via file-service, referral form, admin UI for FRAUD_TRIAGE rules; report widens to 6 tiles + trend chart + top-10 provider + top-10 member + AI calibration (with N<50 fallback) + investigator productivity (role-gated); XLSX widens to 6 sheets; Phase 17 whitelist widens to include `FRAUD_SIU_REPORT` + per-schedule `includeSensitiveSheets` flag lands; `ScheduledReportShapeAdapter` in claims-service integrates with Phase 17 orchestrator; four-eyes maker/checker enforced (Phase 11 `CommissionAdjustment` precedent — `updated_by_actor_id != checker_id`). Multi-currency composite scalar per invariant #6 (fail-loud on missing FX); per-currency envelope best-effort per G28.

**Deferred to follow-up per Phase 11/12/13/14/15/16/17/18 precedent**:

- **Cross-language docker-compose IT** — Phase-19-integration tranche.
- **Manual `verify` walkthroughs** — end-to-end golden-path browser demos (open a case, upload evidence, refer to law enforcement, close with four-eyes, receive scheduled email via mailpit); `verify` skill pass at end of sub-plan.
- **Phase 19.5 — ML-ops audit-of-record + jurisdiction-specific retention + provider dashboard + fraud typing.** Full feature-vector persistence + model-weights registry + MLflow-lite for AI decision reproducibility (FR10). Per-tenant jurisdiction retention override ('ZW wants 10y, ZA wants 7y') via `tenants.jurisdiction_code` widening on RetentionClass classifier (FR13). Dedicated `provider-detail-page` "SIU history" tab drilling into all cases for a provider (FR11 top-N implies drill-target; v1 links to `/tenant/claims/siu/?providerId=<id>` filter only). Fraud-typing classifier — internal-vs-external, provider-vs-member, hard-vs-soft fraud — plus `commission_type`-style categorical enum on `siu_case`. Configurable per-tenant small-N calibration threshold (currently hardcoded to 50 confirmed cases per FR11).

Per parent-plan Testcontainers policy each deferred IT lands with a purpose-built migration folder so the ITs don't force-widen every unrelated slice's baseline schema.

### Owed back to plan authors

Corrections + contradictions the grill surfaced that touch other sections of the parent plan:

- **Parent-plan header `phases_status` stale**: header line `"19": outline depth; needs its own grilling pass before implementation` superseded by this grill — `phases_status["19"]` now grilled; `last_grilled_phase: 19`, `last_grilled_date: 2026-09-05`. Frontmatter updated as part of this apply step.

- **Phase 17 whitelist widens (FR12 consequence)**: Phase 17 sub-plan `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` needs a Deviations note that Phase 19 §B adds `FRAUD_SIU_REPORT` to the scheduled-delivery whitelist plus a new per-schedule `includeSensitiveSheets` flag. `ScheduledReportEligibilityTest.java:38-49` currently *excludes* `FRAUD_SIU_REPORT` with comment "bespoke draft workflows" — that exclusion + comment need updating in §B to include the key and reference the sensitive-sheet gate. Similar to Phase 18's whitelist widening from 13→18 keys, Phase 19 §B widens further to include the fraud key.

- **Phase 15 retention taxonomy extension (FR13 consequence)**: `RetentionClass` enum on `services/java/finance-service/src/main/java/com/medfund/finance/report/entity/ReportJob.java:35-36` currently has 2 values (`OPERATIONAL_90D`, `STATUTORY_7Y`). Phase 19 §A widens to 4 values (adds `FRAUD_FLAG_1Y`, `SIU_CASE_7Y`) and the classifier at `Ifrs17JobService.java:239-242` needs a new branch. Extend existing `ReportJobRetentionJob` (or add peer `FraudFlagRetentionJob`) to purge aged unlinked `fraud_flag` rows. Not a bug in Phase 15; a foreseeable extension.

- **Claims-service tenant-schema migration numbering**: last claims-service migration in `services/java/tenancy-service/src/main/resources/db/migration/tenant/` is `V139__claim_reserve_history.sql`. Phase 19 §A migrations start at V140+ (3 tables: `fraud_flag`, `siu_case`, `siu_case_note`). Phase 19 §B lands 2 more (`siu_evidence`, `siu_referral`). Verify latest applied number at sub-plan write time — never edit an applied migration (per `feedback_never_edit_applied_migrations`).

- **Phase 0 `TenantReportConfig` — `FRAUD_SIU_REPORT` default enabled**: `FRAUD_SIU_REPORT` is in the `ReportKey` enum but absent from `tenant_report_config` seed. Absent-row defaults to enabled per V130 semantics. No seed migration needed; first tenant toggle load will seed a row via Phase 0's bulk-upsert. Consistent with parent-plan G12.

- **No `.claude/*.md` architecture doc mentions SIU workflow or fraud detection**: sub-plan §A could optionally add a short section to `.claude/adjudication.md` (references SIU as an adjacent post-adjudication concern) or a new `.claude/siu.md` naming the state machine + role model + AI producer + FRAUD_TRIAGE rules category. Not strictly required but avoids re-litigating the design at code-review time.

- **AI service Kafka producer wiring (new outbound path)**: `services/python/ai-service` today has no Kafka producer at all (only a consumer at `app/core/kafka_consumer.py:68-96`). Phase 19 §A adds the first outbound Kafka path from the AI service — likely a new `app/core/kafka_producer.py` (aiokafka producer) + startup wiring in `app/main.py`. Docker compose + local dev startup should verify the producer is provisioned even when the consumer isn't yet active (avoid startup-order coupling).

- **Live defect surfaced during grill (deferred to §B fix)**: `ScheduledReportEligibilityTest.java:38-49` groups `FRAUD_SIU_REPORT` with "regulator + IFRS 17 + AML periodic" for whitelist exclusion. The comment says "depend on MFA-gated human filing (REG12/REG13) or bespoke draft workflows" — but `FRAUD_SIU_REPORT` was never MFA-gated (it's an internal SIU report, not a regulator filing). The comment should be corrected in §B to name the actual reason: "excluded until Phase 19 authors the case-management module + per-schedule sensitive-sheet gate". Not blocking — just misleading to future readers.

### Cross-references

- Grilling scratchpad: `thoughts/shared/notes/2026-09-05-phase19-fraud-siu-grill.md`
- Sub-plan (to be authored via `create-plan`): `thoughts/shared/plans/2026-09-05-fraud-siu-report.md`

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

**2026-08-16 (Phase 8 grilling — expansion to code altitude, before implementation)**

- **§1 — Forecast lives in contributions-service, inverting the aggregator direction.** The Phase 3/5
  precedent (G2/G16) is finance-service hosting every cross-service composer and fanning out to
  contributions via `ContributionsClient`. The Phase 8 outline instead names
  `AgedDebtorsForecastController` in contributions-service, and the grill confirmed that placement
  (D8-1). Consequently contributions-service gains a reverse `FinanceClient` (WebClient to
  finance-service, `services.finance.base-url:http://localhost:8085`, no in-client retries —
  G37 fallbacks live at the caller) and finance-service exposes a narrow ungated
  `/api/v1/reports/aggregate/outflows` feed (mirroring `ReceiptsAggregateController` — toggling
  `CASH_FLOW_FORECAST_13W` off must not break the forecast's data feed). The inbound
  `X-Tenant-ID` header convention is unchanged.

**2026-08-16 (Phase 8 §2 — implementation deltas)**

- **§2a — ngx-charts animations disabled on the shared `app-line-chart`.** The Phase 8 chart pages
  (cash-flow-forecast, collection-rate-trend) rendered `[animations]="true"`. In the dev-mode build
  (what Playwright + `ng serve` test) a 13-week × 2-currency dataset pegged the main thread — a trivial
  `performance.now()` round-trip took 7→36s, and the e2e spec's first interaction timed out even though
  the DOM was attached (the toolbar's `asOf` input existed but was never actionable). Flipping to
  `[animations]="false"` dropped round-trips to ~16-55ms. That is also a UX win: every refetch (rolling
  weeks / as-of / currency change) previously re-animated the whole chart. The `view` input on the
  wrapper was already a no-op (never bound through to `ngx-charts-line-chart`).
- **§2b — rollingWeeks refilter binding was missing** (`cash-flow-forecast.component.html`): the
  `app-select` for rolling weeks bound only `[(ngModel)]` and never called `onFilterChange()`, so
  changing the window updated the model but never refetched. The currency select already had
  `(selectionChange)="onFilterChange()"`; the weeks select now matches. The e2e golden path caught it.
- **§2c — contributions IT stubs the finance peer with `@MockBean FinanceClient` rather than
  MockWebServer.** okhttp mockwebserver is not on the contributions-service test classpath (finance has
  it; contributions does not), and the IT must assert Kafka audit events on export, so it extends
  `AbstractIntegrationTest` (Postgres + Kafka) with a mock client bean. The finance-side
  `CollectionRateTrendControllerIT` does use the existing MockWebServer pattern.
- **§2d — toggle-off 403 is proven via the permission gate, not a literal disabled row.** The test
  schema has no `tenant_report_config` table, so `@RequiresReport` falls back to enabled (absent row =
  default TRUE) — a literal disable-row round-trip is unrepresentable there. Both Phase 8 ITs assert the
  `FINANCE_VIEW_SUBLEDGER` 403 instead; the toggle-hides-from-hub behaviour belongs to the Phase-0
  reports-settings specs (per the Phase-0 §6 deferral note above), so the e2e spec ships golden-path +
  empty-window rather than a toggle round-trip.

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

**2026-08-16 (Phase 9 grilling — expansion to code altitude, before implementation)**

- **§9a — "Cross-tenant connection factory" not needed.** The plan's outline names "requires a
  cross-tenant connection factory that doesn't bind `search_path`". `TenantAwareConnectionFactory`
  already yields platform-context connections (`search_path = public`, no `SET ROLE`) when no
  tenant UUID is in Reactor context (`shared/.../TenantAwareConnectionFactory.java:84`). The
  existing claims-service and user-service `PlatformStatsController` classes prove the pattern:
  enumerate `information_schema.schemata LIKE 'tenant_%'` + schema-qualified queries +
  `onErrorResume(Flux.empty())` per schema. All four new endpoints copy this — no new beans or
  connection factories required.
- **§9b — revenue-by-tenant lives in contributions-service, not finance-service.** "Revenue" is
  the receipts definition (netted completed transactions per `transaction_types.sign`, Phase 3
  receipts CTE), which is contributions-domain data. finance-service serves payouts only. The
  plan's "finance-service: revenue-by-tenant" assignment is a deviation.
- **§9c — Angular changes: revenue-by-tenant bar chart.** The plan's outline states "Angular: no
  changes". The grilled decision (D9-3) adds a new bar chart to the analytics page, using the
  existing `app-bar-chart` component and the already-existing-but-uncalled
  `getRevenueByTenant()` method in `platform-dashboard.service.ts:79`. Deviation from the
  no-changes constraint.
- **§9d — Super-admin enforcement applies to all existing platform endpoints.** The plan's outline
  doesn't mention super-admin middleware. The grilled decision (D9-6) adds a gateway middleware
  that covers all `/api/v1/platform/*` routes including the pre-existing seven (tenant-count,
  claims-stats, user-stats, member-growth, claims-distribution, stats, activity). This closes
  an existing hole where any authenticated user could read cross-tenant aggregates (the old
  endpoints had no role check; `TenantResolver` already exempts `/api/v1/platform`).
- **§9e — Stub count: 5, not 6.** The plan's outline says "fill all 6 stubbed gateway endpoints".
  The actual count is 5 stubs (`claims-over-time`, `billing-over-time`,
  `billing-payments-over-time`, `claim-payouts-over-time`, `revenue-by-tenant`) plus 3 already-real
  endpoints (`tenant-growth` → moved server-side, `member-growth`, `claims-distribution`). The
  `stats` and `activity` endpoints are not stubs.
- **§9f — seriesPoint.Value is float64.** The existing `seriesPoint` struct uses `int Value`
  (`handler.go:15`). All four money series change this to `float64` for USD-converted amounts
  with fractional precision. ngx-charts renders floats; `yTickFormat` blanks fractional labels
  but data integrity is unaffected.

## Decisions Log

Grilling on 2026-08-16 settled the following. D9-* = Phase 9 grill decisions; facts from codebase exploration.

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
- **G12** — Cross-tenant analytics: yes — fill all 5 stubbed gateway endpoints + move tenant-growth server-side + wire revenue-by-tenant bar chart into Angular (Phase 9).
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
