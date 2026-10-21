---
date: 2026-08-11T00:15:00+02:00
researcher: Methuseli
git_commit: 0a1609d72451938c1e12346b63f7f6595122b8e5
branch: rename-adjustments-to-notes
repository: medfund
topic: "Financial reporting in InsureFlow, benchmarked against the MASCA-Backend reference"
tags: [research, codebase, finance-service, contributions-service, angular, reporting, multi-tenancy, multi-currency]
status: complete
last_updated: 2026-08-11
last_updated_by: Methuseli
---

# Research: Financial reporting in InsureFlow, benchmarked against MASCA-Backend

**Date**: 2026-08-11T00:15:00+02:00 · **Researcher**: Methuseli · **Commit**: 0a1609d · **Branch**: rename-adjustments-to-notes

## Research Question

Map the current financial-reporting surface across InsureFlow (finance-service, contributions-service, tenancy-service, Angular web client) and compare it against the reference implementation in `/home/methuseli-mfema/Documents/personal/MASCA-Backend/finance/`. The reference is a single-tenant Django backend and is only a **guideline** — InsureFlow is a multi-tenant, multi-line, polyglot core, so parity is not the goal; the goal is to inventory what exists, what's stubbed, and what the multi-tenant version of each MASCA report should look like.

## Summary

InsureFlow's financial reporting is **partially implemented and unevenly distributed**:

- **Contributions-service is the strongest surface today.** `StatementController`, `BalanceController`, `InvoiceController` and `BeneficiaryAnnualTotalController` are production-shaped: server-side pagination, permission guards, ISO-4217 currency required on all queries, XLSX export via Apache POI 5.2.5, PDF proxied from file-service. This maps cleanly onto MASCA's `debtors-subledger`, `billing-report`, and per-invoice history.
- **Finance-service is a mix of solid transactional endpoints and a naive `ReportController`.** The unified `CreditorController` (providers + members with XLSX export), `PaymentAdviceController`, `PaymentRunController`, `AdvancePaymentController`, `CtcPaymentController`, `NoteController`, `ReconciliationController` are all real. The **`/api/v1/reports/*` group is a stub** — five endpoints that ignore their `period` param, do in-memory `stream()` aggregation over `findAllOrderByCreatedAtDesc()`, and mix currencies without a reporting-currency conversion.
- **Angular has 18 operational report/list surfaces and 13 `ComingSoon` stubs**, most of the stubs mapping 1:1 to MASCA reports the backend does not yet expose (aged-debtors analytics, receipts-to-billing, billing-to-claims, schemes report, group-billing report, group-notes, claims-status financial view, member-payments).
- **Tenancy-service exposes only `GET /api/v1/platform/tenant-count`** — no super-admin platform-wide revenue/analytics endpoint exists yet, though `.claude/portals.md:70` says `/super-admin/analytics` should aggregate across tenants via the `analytics` schema. **Update (2026-08-11 grilling):** the Angular super-admin analytics screen **does exist** at `clients/angular/src/app/pages/platform/analytics/` (not `/super-admin/analytics` as the portals doc suggests), wired to `PlatformDashboardService`. Gateway registers 8 `/analytics/*` endpoints at `services/go/gateway/internal/platform/handler.go:35-42` — 6 of them return `[]`/placeholder because the per-service `/api/v1/platform/*` aggregate endpoints don't exist yet (only `user-service` `/member-growth` and `claims-service` `/claims-distribution` return real data).
- **Two critical-rule violations to flag** (see Architecture Insights): `ReportController` mixes currencies and lacks period filtering, and its aggregations happen in-memory rather than in Postgres, so they will not scale and will silently return wrong totals in multi-currency tenants.

## Findings

### finance-service — `services/java/finance-service/`

**`ReportController`** (`.../controller/ReportController.java:22`) — five endpoints under `/api/v1/reports`, all Swagger-annotated but functionally naive:

- `GET /api/v1/reports/payment-advice/{paymentRunId}` — delegates to `PaymentAdviceService.generateAdvice()`; real (line 46).
- `GET /api/v1/reports/claims-summary?period={period}` — returns totalClaims, approved/rejected/pending, totalAmount, approvedAmount. **The `period` param is echoed back verbatim and never used to filter** (line 56–90). Aggregation is `payments.stream()` over the entire table.
- `GET /api/v1/reports/payment-summary?period={period}` — countByStatus, amountByStatus, countByProvider, amountByProvider. Same `period`-is-ignored bug (line 98–138).
- `GET /api/v1/reports/provider-performance` — approvalRate, averageAmount per provider, in-memory (line 146–192).
- `GET /api/v1/reports/contribution-summary?period={period}` — filters payments where `payment_type = 'contribution'`, but again ignores `period` (line 201–235). Note the endpoint description at line 197 explicitly says "For full contribution data, query the contributions service directly" — the endpoint knows it's incomplete.

**`CreditorController`** (`.../controller/CreditorController.java`) — the real unified creditors surface:

- `GET /api/v1/creditors/page` — paginated PROVIDER|MEMBER|BOTH creditors list; sortable on totalClaimed/totalApproved/totalPaid/outstandingBalance/lastActivityAt.
- `GET /api/v1/creditors/provider/{id}` → `ProviderBalanceResponse`.
- `GET /api/v1/creditors/member/{id}` → `Flux<MemberBalanceResponse>` (one per currency).
- `GET /api/v1/creditors/export/excel` — XLSX via `CreditorsExcelService`, 10k-row ceiling.
- Backed by `CreditorQueryRepository` (real SQL, not in-memory).

**`PaymentAdviceController`** (`.../controller/PaymentAdviceController.java`) — `/api/v1/payment-advices/page`, `/api/v1/payment-advices/{id}`, `/api/v1/payment-runs/{runId}/advices`. Typed ledger lines (CARRY_FORWARD, CLAIM_PAID, NOTE_DEBIT, CTC_APPLIED, ADVANCE_APPLIED, etc.) via `PaymentAdvice` DTO — this is the strongest ledger-shaped output in the whole service.

**`PaymentRunController`** — `/api/v1/payment-runs`, `/page`, `/{id}/items`. Full paginated list + item drill-down.

**`AdvancePaymentController`, `CtcPaymentController`, `NoteController`, `ReconciliationController`, `MemberPayableController`, `MascaBankAccountController`** — all follow the same `/page` + detail + export? shape. Only creditors has an Excel export today.

**Query repositories** (`.../repository/`): `CreditorQueryRepository`, `ProviderBalanceQueryRepository`, `MemberBalanceQueryRepository`, `PaymentQueryRepository`, `PaymentRunQueryRepository`, `NoteQueryRepository`, `CtcPaymentQueryRepository`, `PaymentAdviceRecordRepository`, `BankReconciliationRepository`. These are the reporting-scale query layer — where a proper `ReportController` rewrite should live.

**Exports** — `CreditorsExcelService` is the only export helper. POI 5.2.5 on classpath (`finance-service/build.gradle.kts:12`).

### contributions-service — `services/java/contributions-service/`

**`StatementController`** (`.../controller/StatementController.java:32`) — this is the reference-quality report in the codebase:

- `GET /api/v1/statements` — targetType (GROUP|MEMBER), targetId, periodStart, periodEnd, optional currency; returns `StatementResponse` with opening balance, chronological ledger lines, closing balance (line 48–55).
- `GET /api/v1/statements/export/excel` — same filters, XLSX via `StatementExcelService`, filename `statement-{targetId}-{periodStart}-{periodEnd}.xlsx` (line 61–74).

**`BalanceController`** (`.../controller/BalanceController.java:48`) — the debtors surface:

- `GET /api/v1/billing/balances/members/{memberId}?currency={c}` — member running balance (line 58–67).
- `GET /api/v1/billing/balances/groups/{groupId}?currency={c}` — group running balance (line 69–76).
- `GET /api/v1/billing/balances/debtors?currency=&subjectType=&q=&page=&size=` — currently-billable subjects with positive balance; `@RequiresPermission(BILLING_VIEW_DEBTORS)` (line 78–93).
- `GET /api/v1/billing/balances/debtors/export/excel` — XLSX via `DebtorsExcelService` (line 95–112).
- `GET /api/v1/billing/balances/bad-debts` + `/bad-debts/export/excel` — deactivated/terminated subjects still owing (line 114–150).
- `GET /api/v1/billing/balances/aged-balances?currency=&minAgeDays=` — aging classification GRACE/SUSPENDED/WRITE_OFF (line 152–166).
- `POST /api/v1/billing/balances/bad-debts/flag` — write-side (line 168–180).

**`InvoiceController`** (`.../controller/InvoiceController.java:59`) — paginated invoices with per-invoice statement + per-invoice contributions + PDF streaming from file-service (line 110–131).

**`BeneficiaryAnnualTotalController`** — `AnnualCapUtilizationResponse` (consumedAmount, capAmount, currencyCode) — the cap-utilization report (line 42–86).

**Other list-shaped surfaces**: `BadDebtController`, `ContributionController`, `TransactionController`, `SchemeChangeController`, `BillingCatalogueController` are transactional but list-capable.

POI 5.2.5 on classpath (`contributions-service/build.gradle.kts:10`).

### tenancy-service — `services/java/tenancy-service/`

- `GET /api/v1/platform/tenant-count` — `Map<String, Long>{totalTenants}` (super-admin only, no tenant context) — literally the only platform-stats endpoint. `.claude/portals.md:59` calls for platform revenue, member count, claims count, system health — none of that is wired.

### Angular client — `clients/angular/`

**Operational finance report/list pages** (~~18~~ **31** total per re-count during 2026-08-11 grilling):

- `/tenant/finance/runs` and `/runs/:id` — payment-runs-list + detail.
- `/tenant/finance/payments` — payments list (also aliased as `/reports/provider-payments`, `/reports/committed-payments`, `/reports/provider-payment-status` with preset filters).
- `/tenant/finance/creditors` — the only XLSX export button in the UI (`exportCreditorsExcel()`).
- `/tenant/finance/creditors/provider/:id`, `/creditors/member/:id` — detail views.
- `/tenant/finance/notes` — unified debit/credit/memo (`FinanceService.listNotesPage()`); aliased as `/reports/withheld-tax` with preset `TAX_WITHHELD`.
- `/tenant/finance/payments/advance`, `/advance/add` — advance-payments list + form.
- `/tenant/finance/payments/ctc` — CTC payments list.
- `/tenant/finance/advice`, `/advices/:id` — payment-advice list + detail (ledger lines).
- `/tenant/finance/reconciliations` — bank reconciliation list.
- `/tenant/finance/copayments` — copayment transaction filter.

**ComingSoon stubs** (~~13~~ **25** per re-count during 2026-08-11 grilling — most mirror MASCA reports the backend does not yet expose):

`/tenant/finance/subledger-debtors`, `/debtors-report`, `/billing-to-claims`, `/receipts-to-billing`, `/receipts/report`, `/reports` (hub), `/reports/schemes`, `/reports/group-billing`, `/reports/group-schemes`, `/reports/group-billing-to-claims`, `/reports/group-notes`, `/reports/claims-status`, `/reports/member-payments`, `/reports/member-payment-status`, `/currencies`.

**Dashboards**: `TenantOperationalDashboardComponent` (`/tenant/dashboard`) has a Finance tab (payment status pie, method distribution donut, top-payees bar, recent payments table, twin-series cash-flow chart per currency) — feeds off `AdminService.getPaymentsStatusDistribution/getPaymentMethodDistribution/getTopPayees/getRecentPayments`. Chart lib is `@swimlane/ngx-charts`. Platform-level dashboard is stub-shaped for finance.

**Permissions referenced** (per portals.md convention): `finance:view`, `finance:view_creditors`, `finance:view_subledger`, `finance:view_debtors`, `finance:view_withheld_tax`, `finance:view_payment_advice`, `finance:view_advance_payments`, `finance.notes:read/write`, `finance:manage_receipts`, `finance:manage_billing_reconcile`, `billing:view_debtors`, `billing:manage_bad_debts`.

### MASCA-Backend reference — `/home/methuseli-mfema/Documents/personal/MASCA-Backend/finance/`

25 report/service surfaces. Each report in MASCA is scheme-scoped rather than tenant-scoped; the multi-tenant re-implementation always adds a JWT-resolved `TenantContext` filter on top and lets tenants pick a reporting currency (see `.claude/multi-currency.md:167`).

| MASCA report / URL | Family | Grain | Key metrics | InsureFlow status |
|---|---|---|---|---|
| `billing-report` (`finance/reporting.py:17`) | Billing | Scheme or group aggregate | Contributions USD/ZWL, principal + dependants + lives, revenue by age group | **Missing** — no `/reports/billing` equivalent; UI stub `/reports/schemes` + `/reports/group-billing` |
| `group-billing-report` (`finance/reporting.py:601`) | Billing | Group aggregate | Group ZWL/USD contributions (committed only) | **Missing** — UI stub `/reports/group-billing` |
| `billing-aggregate` (`finance/views/csv_reporting.py:421`) | Billing | Per-scheme or per-group | Contributions + revenue by age group × currency | **Missing** — closest is contributions-service `InvoicesPage` but grain differs |
| `receipts-report` (`finance/reporting.py:313`) | Receipts | Group aggregate | USD/ZWL transactions | **Missing** — UI stub `/receipts/report`; billing `TransactionController` has listing but no aggregate |
| `receipts-aggregate` (`finance/views/csv_reporting.py:592`) | Receipts | Per-group + tx detail | USD/ZWL transactions | **Missing** |
| `receipts-to-billing-aggregate` (`finance/views/csv_reporting.py:648`) | Reconciliation | Per-group | Collection rate = receipts / contributions | **Missing** — UI stub `/receipts-to-billing` |
| `claims-report` (`finance/reporting.py:394`) | Claims-financial | Scheme or group aggregate | Total member/dependant claims + values + lives claimed | **Missing** in finance-service; claims-service has its own reports controller (out of scope) |
| `billing-to-claims-aggregate` (`finance/views/csv_reporting.py:85`) | Reconciliation | Per-scheme / per-group | Contributions vs claims_awarded, claims_ratio, surplus/deficit | **Missing** — UI stub `/billing-to-claims`, `/reports/group-billing-to-claims` |
| `claim-status` (`finance/views/payment_status.py:117`) | Claims-financial | Per-claim listing | Full claim detail | **Missing** — UI stub `/reports/claims-status` |
| `payment-advice` (`finance/views/payment_advices.py:11`) | Payables | Per-provider + claim detail | Awards, claim_count, drug_claims | **Present** — `PaymentAdviceController` + typed ledger lines is richer than MASCA |
| `creditor-payments` (`finance/views/payment_status.py:22`) | Payables | Per-creditor list | Payment + AdvancePayment + CTCPayment + Claim + balance | **Partial** — `CreditorController.getCreditorMemberDetail/getCreditorProviderDetail` returns balance snapshot but not the unified transaction history |
| `creditors` (`finance/views/csv_reporting.py:752`) | Balances | Per creditor | usd_balance, zwl_balance | **Present + better** — `CreditorController.listCreditorsPaged` is unified provider+member with XLSX export |
| `group-adjustments-aggregate` (`finance/views/csv_reporting.py:716`) | Adjustments | Per-adjustment | ADDITION/TRANSFER/TERMINATION/REINSTATEMENT + scheme changes | **Missing** — UI stub `/reports/group-notes` (though V074 unified notes already list adjustments) |
| `debit-notes`, `credit-notes` (`finance/views/notes.py`) | Notes | Per-note | amount, currency, created_by, task ref | **Present + better** — post-V074 `NoteController` unifies debit/credit/memo with direction + noteType filter |
| `tax-with-held-adjustment` / `tax-with-held-update` (`finance/urls.py:49-50`) | Notes | Per-provider adjustment | tax withheld | **Present via preset** — `notes-list.component` with `presetNoteType='TAX_WITHHELD'` at `/reports/withheld-tax` |
| `provider-balances` (`finance/views/provider_balance.py:12`) | Balances | Per-provider | usd/zwl balance | **Present** — subsumed by unified `CreditorController` |
| `balance_snapshots` (`finance/services/balance_snapshots.py`) | Balances | Snapshot per event | Historical balances per payment run | **Unclear** — no `provider_balance_snapshots` / `member_balance_snapshots` tables surfaced; a design question |
| `debtors-subledger` (`finance/views/debtors_subledger.py:21`) | Debtors | Group, monthly | Opening + contribs + fees + txs by type + closing | **Partial** — contributions-service `StatementController` covers the shape for members and groups |
| `payments-statics` (`finance/views/dashboard_stats.py:14`) | Dashboard | Aggregate | YTD contributions, payments, adjustments, pending run balance | **Partial** — Angular tenant dashboard shows some KPIs via `AdminService`, no single endpoint yet |
| `graph_statistics` (`finance/services/graph_statistics.py`) | Dashboard | Monthly | Monthly payment totals per currency | **Partial** — dashboard twin-series chart per currency exists client-side |
| `generate_billing_report` (`finance/services/generate_billing_report.py`) | Ops | Email summary | Scheme lives + amount billed | **Missing** — no email delivery / scheduled report machinery |
| `generate_payment_run_excel_file` (`finance/services/generate_payment_run_excel_file.py`) | Payables | Excel workbook | Multi-sheet USD + ZWL | **Missing** — payment-run has no XLSX export |
| `compare_balances_with_claim_awarded_amounts` (`finance/services/compare_balances_with_claim_awarded_amounts.py`) | Reconciliation | Ops report | Balance vs claim award surplus/deficit | **Missing** — related to `billing-to-claims` |

## Cross-service flow

Reports naturally cross service boundaries — the multi-tenant re-implementation of MASCA's `billing-to-claims-aggregate` needs figures from **contributions-service** (charged amounts, invoices), **finance-service** (payment records, advances, CTCs), and **claims-service** (adjudicated amounts). Today these live in separate schemas and services, connected only by:

- Kafka events (`ClaimAdjudicatedConsumer` at `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java`, `PaymentAdviceStatusConsumer`).
- Cross-service HTTP for reads (`TenantConfigClient`, `FxConverter` in `.../client/`).

There is no dedicated **reporting/analytics service** and no read-model / materialized-view layer. `.claude/multi-tenancy.md` mentions the `analytics` schema for super-admin cross-tenant summaries, but it is not implemented — the tenancy-service only exposes tenant-count.

Producer → consumer for advice/report inputs:
- Claims service publishes `medfund.claims.adjudicated` → finance-service `ClaimAdjudicatedConsumer` → payments/advances/CTC records → `PaymentAdviceService.generateAdvice()` → `PaymentAdvice` DTO on `GET /api/v1/reports/payment-advice/{runId}`.

## Architecture doc vs. code

- **`.claude/multi-currency.md:164`** — "Financial reports support currency filtering and cross-currency totals (using a reporting currency)." **Drift**: `finance-service ReportController` sums BigDecimal amounts across all currencies with no conversion or currency stratification (ReportController.java:72–78). `StatementController` and `BalanceController` correctly require an ISO-4217 `currency` param — those are compliant.
- **`.claude/multi-currency.md:167`** — "All financial reports allow selecting a reporting currency." **Missing**: no report endpoint accepts a `reportingCurrency` param anywhere. The Excel exports (creditors, statement, debtors, bad-debts) also do not offer this.
- **`.claude/portals.md:154`** — `/finance/reports` should offer "P&L, balance sheet, provider aging, payment summary" as exportable reports. **Drift**: none of these exist; the Angular route is ComingSoon.
- **`.claude/portals.md:70`** — `/super-admin/analytics` should show cross-tenant aggregated metrics via the `analytics` schema. **Missing**: no analytics service, no analytics schema, only `GET /api/v1/platform/tenant-count`.
- **`.claude/portals.md:176`** — `/contributions/reports` (Contribution Reports: collection rates, aging analysis, group compliance). **Partial**: BalanceController has debtors/bad-debts/aged endpoints; there is no explicit "collection rate" or "group compliance" endpoint.
- **`.claude/portals.md:142`** — "Finance Dashboard: Pending payments, balances, cash flow summary (live via Elixir WebSocket)". **Drift**: dashboard KPIs today are pulled via REST through `AdminService`, not via Elixir Phoenix Channels. The `live_dashboard` app exists at `services/elixir/apps/live_dashboard` but is not wired to finance metrics.

## Code References

- `services/java/finance-service/src/main/java/com/medfund/finance/controller/ReportController.java:22-236` — the naive reports controller (five endpoints, four ignore their `period` param, all mix currencies).
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/CreditorController.java` — unified creditors + Excel export.
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentAdviceController.java` — advice list/detail with typed ledger lines.
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentAdviceService.java` — advice generation.
- `services/java/finance-service/src/main/java/com/medfund/finance/service/CreditorsExcelService.java` — only real Excel export in finance.
- `services/java/finance-service/build.gradle.kts:12` — `org.apache.poi:poi-ooxml:5.2.5`.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/StatementController.java:32-75` — reference-shaped ledger statement + XLSX.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceController.java:48-166` — debtors/bad-debts/aged-balances with per-currency requirement.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/InvoiceController.java:59-131` — invoice list + statement + PDF.
- `services/java/contributions-service/build.gradle.kts:10` — `org.apache.poi:poi-ooxml:5.2.5`.
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/PlatformStatsController.java:24-33` — sole platform-level stats endpoint.
- `clients/angular/src/app/core/services/finance.service.ts` — full Angular API surface (creditors, notes, advices, runs, payments, advances, CTCs, reconciliations).
- `clients/angular/src/app/features/finance/finance.routes.ts` — the 18 operational + 13 ComingSoon routes.
- `clients/angular/src/app/features/tenant/dashboard/tenant-operational-dashboard.component.ts` — finance-tab charts.
- `.claude/multi-currency.md:155-169` — reporting currency + historical rate rules.
- `.claude/portals.md:140-155` — Finance-portal route contract.
- `.claude/portals.md:157-176` — Contributions-portal route contract.
- `.claude/portals.md:59, 70` — Super-admin dashboard + analytics contract.
- Reference: `/home/methuseli-mfema/Documents/personal/MASCA-Backend/finance/urls.py:11-51` — full report URL registry to map against.

## Architecture Insights

**Critical-rule concerns raised by the current `ReportController`:**

1. **Multi-currency (Rule 1)** — `ReportController.claimsSummary`, `paymentSummary`, `providerPerformance`, `contributionSummary` all `.reduce(BigDecimal.ZERO, BigDecimal::add)` on `payment.getAmount()` regardless of `currencyCode`. In a tenant that transacts in both USD and ZWL, the reported totals are meaningless. The `.claude/multi-currency.md:130` rule "never mix currencies in arithmetic" is being violated. Fix: stratify by currency in SQL, or introduce a `reportingCurrency` param + `FxConverter` call, or emit a `Map<Currency, BigDecimal>`.
2. **Tenant scoping (Rule 2)** — Not visible in the controller. Relies on the ambient `TenantAwareConnectionFactory` swapping `search_path`. Should be re-checked once the endpoints are made real, because in-memory aggregation loads *all* payments — if the tenant filter fails silently, the leak is total. `StatementController` and `BalanceController` do not have this problem because their queries take strong per-target params.
3. **Aggregation in memory** — `paymentRepository.findAllOrderByCreatedAtDesc().collectList()` will not scale beyond a few thousand payments per tenant. The `PaymentQueryRepository` alongside it already knows how to do server-side pagination and could be extended with aggregate SQL.
4. **Ignored `period` param** — every `@RequestParam String period` is echoed into the response but never used. Swagger promises filtering; the code delivers none. Either drop the param or actually parse+filter.
5. **Swagger completeness (Rule 7)** — the endpoints are annotated but the described behaviour (period filtering, currency handling) does not match the implementation. That is a documentation defect as much as a code defect.
6. **Security-event logging on export (Rule 9)** — spot-check: `CreditorController` XLSX export and `BalanceController` debtors/bad-debts XLSX exports do not appear to emit a `SecurityEvent` of type `DATA_ACCESS`/`EXPORT`. `.claude/portals.md:480, 520` explicitly define `finance:*:export` permissions, and V037 conventions expect export to be an audit-visible action. Worth verifying — may be a systemic gap.
7. **Reporting-currency selector is architecturally required but nowhere implemented.** All Excel exports today are per-currency (creditors filter, debtors filter). The tenant-admin reporting-currency setting from `.claude/multi-currency.md:167` needs a home.
8. **No analytics/read-model service.** Cross-service reports (`billing-to-claims`, `receipts-to-billing`, `payments-statics` YTD-across-domains) will keep bumping into the polyglot/Kafka boundary unless there's a read-side projection. Options: a dedicated Java reporting-service that consumes events into a report-optimised schema, or the `analytics` schema on a read replica (per `.claude/multi-tenancy.md`).

**Operational patterns that already work well:**

- The V074 unified-notes surface (`NoteController` + Angular `notes-list.component` with `presetNoteType` aliasing) is a good template for consolidating MASCA's fragmented debit-notes/credit-notes/tax-withheld/adjustments views.
- The V074-style "one component, many preset routes" pattern (`payments-list.component` reused for provider-payments / committed-payments / provider-payment-status) should extend to schemes/groups reports once the backend exists.
- `StatementController` + `StatementExcelService` is the pattern the missing MASCA-family reports should copy.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-08-10-creditors-workflow-unify-providers-and-members.md` — background on the unified creditor surface that supersedes MASCA's separate provider-balances / member-payments views.
- `thoughts/shared/research/2026-08-10-debit-and-credit-notes-in-insurance.md` — background on V074 unified notes (supersedes MASCA `debit-notes`/`credit-notes`/`tax-with-held-adjustment`).
- `thoughts/shared/research/2026-08-10-copayments-standard-flow.md` — copayments surfacing; feeds `/finance/copayments`.
- `thoughts/shared/research/2026-08-09-contribution-statement-pdf-divergence.md` — the silent-fallback bug in statement PDF generation; the "always fail loudly on report data fetch" precedent.
- `thoughts/shared/research/2026-08-09-payment-run-vs-payments.md` — payment run vs payments distinction that the payment-run + advice reports lean on.
- `thoughts/shared/research/2026-08-09-ctc-payments.md` — CTC ledger data flows into member advices and would feed a future member-payments report.
- `thoughts/shared/research/2026-08-08-advance-payments.md` — advance payment offsets that feed the advice CARRY_FORWARD/ADVANCE_APPLIED lines and any future creditor-payments report.

## Related Research

- `thoughts/shared/plans/2026-08-09-payment-run-generation-and-payee-support.md` — payment-run and advice implementation plan; touches per-payee advice ledger lines that would drive a proper payment-run XLSX export.
- `thoughts/shared/plans/2026-08-09-contribution-statement-pdf-divergence.md` — statement-rendering plan; label consistency and fail-loud principles.
- `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md` — advance-payment lifecycle.

## Open Questions

**Status (2026-08-11 grilling)**: this research doc was consumed by a `create-plan + grilling` session on 2026-08-11 that produced `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md`. Answers below reflect that session's decisions.

1. ~~**Is a dedicated `reporting-service` (or `analytics-service`) planned?**~~ **DECIDED (G2)**: no dedicated service. Reports live distributed by data ownership — billing/receipts/debtors in contributions-service, payables/creditors/notes in finance-service, claim-status in claims-service. Cross-service reports use a thin aggregator controller in finance-service that fans out over sync HTTP to per-service `/api/v1/reports/aggregate/{family}` endpoints. Actuarial computation lives in `services/python/ai-service` (G10).
2. ~~**Where does the "reporting currency" tenant setting live?**~~ **ANSWERED (2026-08-11 grilling)**: it already exists as `tenant_currency_config.is_default = TRUE`, added by `services/java/tenancy-service/src/main/resources/db/migration/public/V104__tenant_currency_config.sql:2-9` and extended by V113. Angular admin surface is at `clients/angular/src/app/pages/tenant-admin/settings/currencies/currencies-tab.component.ts:143-158`. Tenant service DTO javadoc even states "used as the reporting currency on dashboards" (`clients/angular/src/app/core/services/tenant.service.ts:35-39`). Reports must fetch this via a `TenantConfigClient.getDefaultCurrency()` call — no new migration needed.
3. ~~**Should the naive `ReportController` be rebuilt in-place or superseded?**~~ **DECIDED (F7)**: deleted outright — verified zero callers across all languages. Replaced by per-family controllers per G2.
4. ~~**Are balance snapshots needed?**~~ **DECIDED (G9)**: yes — new `provider_balance_snapshot` + `member_balance_snapshot` tables, written by `PaymentRunExecutor` in the finalise transaction.
5. ~~**Export audit-event coverage**~~ **CONFIRMED as a gap and DECIDED (F8)**: all 5 XLSX/PDF exports emit no `SecurityEvent`. `SecurityEventPublisher` will be lifted from keycloak-event-listener to `services/java/shared/security/` and wired into every export endpoint.
6. **Should aged-debtors and cash-flow forecasting be on the same roadmap?** **DECIDED**: yes — both in Phase 8 of the plan. 13-week rolling cash-flow forecast + collection-rate report.
7. ~~**Which reports should be email-scheduled?**~~ **DECIDED (G8)**: fixed cadences per report code (commission=semi-monthly, bordereau=quarterly, provider-network-util=monthly); tenant admin picks on/off + recipient list in reports settings tab. Phase 17 of the plan.
