---
date: 2026-09-05
phase: 18
grilling: Executive KPI Dashboards
parent_plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md
decision_prefix: K
---

# Phase 18 grilling scratchpad — Executive KPI Dashboards

## Verification pass (before any questions)

Findings from the Explore subagent — see full report in the grilling session
transcript. Summary:

**Confirmed by code:**
- `ReportKey.COMBINED_RATIO`, `ReportKey.LOSS_RATIO_KPI`, `ReportKey.EXPENSE_RATIO`
  all exist in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:136-139`.
  All three: `ReportFamily.DASHBOARD, cadenced=false, periodShape=null`.
- **Name collision resolved**: `LOSS_RATIO` (Phase 5, `RECONCILIATION` family, cadenced,
  PREVIOUS_COMPLETE_PERIOD) is distinct from `LOSS_RATIO_KPI` (Phase 18, `DASHBOARD`
  family, non-cadenced). Two separate reports — the former is a cross-service
  per-scheme/currency breakdown, the latter is executive dashboard summary.
- Phase 5 loss-ratio surface already exists: `CrossServiceReportController` at
  `services/java/finance-service/src/main/java/com/medfund/finance/controller/CrossServiceReportController.java:73-137`
  with `GET /api/v1/reports/billing-vs-claims` + `/export/excel`. Adapter for
  Phase 17 scheduled render lives at `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/adapter/LossRatioAdapter.java:23-31`.
- Billing aggregate endpoint: `contributions-service/BillingAggregateController.java:50-66`
  `GET /api/v1/reports/aggregate/billing` returns per-(scheme, currency, totalBilled)
  rows. Semantics = "committed contribution" per line 54. Monthly variant at
  `/api/v1/reports/aggregate/billing/monthly` (line 68).
- Claims aggregate: `claims-service/ClaimsAggregateController.java:50-89`
  `GET /api/v1/reports/aggregate/claims` returns per-(dimension, currency,
  totalClaimed, totalApproved, totalPaid) via `adjudicated_at`. Monthly variant
  at `/api/v1/reports/aggregate/claims/monthly` supports SCHEME/GROUP/MEMBER/PROVIDER
  dimensions.
- Reserve history table lives at
  `services/java/tenancy-service/src/main/resources/db/migration/tenant/V139__claim_reserve_history.sql`
  with columns `(id, claim_id, reserved_amount, effective_at, actor_id, actor_email,
  reason_note, created_at)`. **Not currently exposed via the cross-service claims
  aggregate.**
- Angular chart primitives exist as `app-line-chart` (`clients/angular/src/app/shared/components/charts/line-chart/line-chart.component.ts`);
  used in `collection-rate-trend` and `cash-flow-forecast`. Also available:
  area/bar/grouped-bar/pie/waterfall.

**Missing / greenfield:**
- **No expense-tracking infrastructure**: no `ExpenseController`, no `expenses`
  table, no `commission_expense` feed, no `underwriting_expense` fact.
  Phase 11 `commission_statement` is a producer *payout* report, not a platform
  *expense* aggregate for ratio purposes.
- No `ExecutiveKpiController` / no `/reports/kpi/*` route family in finance-service.
- No `pages/tenant/finance/reports/kpi/` folder in Angular.
- No architectural definition of "combined ratio" / "expense ratio" anywhere in
  `.claude/*.md` — must be defined during the grill.

**Deletion of question:** the outline's mention of "expense aggregates" implied
they already exist. Verification killed that; expense sourcing becomes the
biggest decision of the phase (K7 below).

---

## Decisions Log (K1..Kn)

_(populated as the interview settles each one)_

- **K1 — Ratio scope for v1**: **Three ratios + claims frequency + average severity.**
  Ship COMBINED_RATIO + LOSS_RATIO_KPI + EXPENSE_RATIO plus two new keys
  `CLAIMS_FREQUENCY` (claim count / policy-months exposure) + `AVERAGE_SEVERITY`
  (paid / claim count). Both new keys land in `DASHBOARD` family, non-cadenced,
  as part of Phase 18 §0. Frequency requires a policy-month exposure feed
  (Phase 13 lifecycle join — dependency surfaces again in K8/K9). Severity is
  a straight claims-service aggregate (paid ÷ count) using the existing
  `/api/v1/reports/aggregate/claims` endpoint plus a new count column.
  Rejected: three-only (executes will demand adjacent KPIs on day 2); +solvency
  +ROE (needs an accounting ledger + capital-model service that don't exist —
  two-quarter build, not a phase). Adjacent unbuilt KPIs (solvency, ROE,
  retention already in Phase 13, policy-persistency variants) deferred to
  Phase 18.5.
- **K2 — Formula: LOSS_RATIO_KPI numerator = incurred**:
  Numerator = paid + Δoutstanding reserves + IBNR. Matches actuarial + IFRS 17
  framing. Requires (a) exposing reserve movement via a new `/aggregate/claims`
  variant or a sibling `/aggregate/claims-incurred` endpoint on claims-service
  (K9); (b) reading the latest committed IBNR run per (line, cohort) from
  Phase 14's `report_job` output (K9). Freshness caveat: if the IBNR job hasn't
  run for the period, dashboard shows an "IBNR pending" warning banner rather
  than a zero (invariant #6 spirit). Rejected: paid-only (misleads execs +
  misaligns with regulator + IFRS 17); dual-line side-by-side (chart clutter
  + doubles compute; execs still fixate on the single "the ratio" reading).
- **K3 — Formula: LOSS_RATIO_KPI denominator = earned premium**:
  Denominator = earned premium sourced from Phase 12's UPR earning schedule.
  Requires a new aggregate endpoint on `contributions-service/PremiumReportController`
  (Phase 12 §B) returning per-(scheme, currency, period) *earned* totals
  — mirrors the shape of the existing `BillingAggregateController` but reads
  `EarningSchedule.earned_to_date` deltas over the period boundaries.
  Endpoint suggestion: `GET /api/v1/reports/aggregate/premium-earned?periodStart&periodEnd&dimension`.
  Rejected: written premium via billing aggregate (mismatched exposure basis;
  contradicts the whole reason we built UPR); dual-axis (chart-clutter +
  "which one is THE ratio" ambiguity + still needs the Phase 12 work).
- **K4 — Formula: EXPENSE_RATIO numerator = commission-only (Acquisition Ratio)**:
  Numerator = paid commission from Phase 11's `commission_statement` for the
  period. **Renamed on the UI to "Acquisition Ratio"** (or "Commission Ratio")
  so execs read the honest scope. The `EXPENSE_RATIO` ReportKey label stays
  as-is (it's a code identifier), but the report's `displayLabel` on the
  catalogue + Angular page title reads "Acquisition Ratio". Requires a
  new aggregate endpoint on finance-service:
  `GET /api/v1/reports/aggregate/commission-expense?periodStart&periodEnd&dimension`
  that sums paid commission from Phase 11's ledger. Rejected: manual-entry
  operating_expense_entry table (data-entry burden + two sources of truth
  vs the tenant's accounting system); IFRS 17 fulfilment cash flows
  (estimates vs actuals; conflates two audiences). Full operating ratio
  (acquisition + admin + investment) deferred to Phase 18.5 alongside a
  general-ledger integration.
- **K5 — Formula: EXPENSE_RATIO denominator = written premium**:
  Denominator = written premium via existing `/api/v1/reports/aggregate/billing`
  endpoint. Matches when acquisition cost was actually incurred (commission
  paid at policy write). NAIC + trade-press convention. Reuses the billing
  aggregate endpoint verbatim. **Consequence for K6**: combined ratio carries
  a "mixed basis" footnote (expense/written + loss/earned) — see K6. Rejected:
  earned for both (mismatched timing; underestimates expense ratio early in
  a policy year); footnoted mixed-basis (subtle to reviewers scanning a
  widget). IFRS 17 auditor pushback is acknowledged and left for the sub-plan
  to add a per-tenant override switch in a follow-up (v1 uses NAIC).
- **K6 — Formula: COMBINED_RATIO = sum with mixed-basis footnote**:
  Combined = LossRatio(incurred/earned) + ExpenseRatio(commission/written).
  Widget carries a persistent info-icon tooltip: "Mixed basis — loss on
  earned premium, expense on written premium (NAIC convention)." Server
  response envelope includes a `basisNote: "MIXED_LOSS_EARNED_EXPENSE_WRITTEN"`
  string so downstream consumers (XLSX export, scheduled dispatch if
  cadenced later) can render the same disclaimer. Rejected: recompute-on-earned
  (three numbers that don't add up + users try to reconcile and fail);
  skip-scalar / stacked-only (evasive tone to "what's our combined ratio").
  A future "additive combined" toggle where the tenant sets a preferred
  basis lives in Phase 18.5.
- **K7 — Expense fact source = sum PAID commission_transaction, classifier deferred**:
  New endpoint `GET /api/v1/reports/aggregate/commissions` in finance-service
  (mirrors the `BillingAggregateController` + `ClaimsAggregateController` shape).
  SQL: `SELECT native_currency AS currency_code, SUM(native_amount) AS total_paid,
  COUNT(*) AS row_count FROM commission_transaction WHERE status='PAID' AND
  paid_at >= :periodStart AND paid_at < :periodEnd GROUP BY native_currency`,
  optionally faceted by producer_id + insurance_line. **No acquisition-vs-servicing
  split for v1** — commission_transaction lacks the classifier and Phase 18 does
  not add it. KPI page carries a UI note: "Includes all paid commission;
  new-business/trail split in a future release." Rejected: add `commission_type`
  column now (schema thrash without a live requirement; risks wrong enum);
  compose acquisition via `first_bind_date` (cross-service join per compute
  + year-boundary edge cases + still misses trail on old policies). Add classifier
  in Phase 18.5 when a tenant asks.
- **K8 — Earned-premium source = new `/api/v1/reports/aggregate/premium-earned`**:
  New lean aggregate endpoint on `contributions-service/PremiumReportController`
  (or a sibling `PremiumAggregateController` following the
  `ClaimsAggregateController`/`BillingAggregateController` pattern). Shape:
  `GET /api/v1/reports/aggregate/premium-earned?periodStart&periodEnd&dimension&insuranceLine`
  returns `List<{schemeId, schemeName, insuranceLine, currencyCode,
  earnedPremium, rowCount}>`. SQL: `SUM(earned_at_period_end) WHERE
  period_end >= :periodStart AND period_end < :periodEnd AND earned_at_period_end
  IS NOT NULL` grouped by (currency_code, insurance_line [, scheme_id via
  policy-enrichment CTE]). **Freshness caveat**: the nightly
  `PremiumEarningExecutor` closes periods; a KPI compute for a period that
  hasn't fully closed yet gets NULL rows omitted — KPI page displays a
  banner "Earned premium reflects fully-closed periods only (nightly batch)."
  Rejected: reuse UPR movement report (tight coupling to human report shape;
  bandwidth waste; mixes report/feed semantics); direct DB read from finance
  (violates service boundary; schema-change fragility; multi-tenant pool sizing
  pain).
- **K9 — Incurred-claims source = `/aggregate/claims-incurred` + latest IBNR job JSON**:
  Two-part composition on the finance-service KPI composer side:
  1. **New claims-service endpoint** `GET /api/v1/reports/aggregate/claims-incurred?periodStart&periodEnd&dimension`
     returns per-(currency, dimension) `{totalPaid, reserveBalanceStart,
     reserveBalanceEnd, reserveMovement, subtotalIncurredExIbnr}` where
     `reserveBalance(T) = SUM(latest reserved_amount per claim_id WHERE
     effective_at <= T)`. SQL uses a `DISTINCT ON (claim_id) ... ORDER BY
     claim_id, effective_at DESC` subquery per period boundary, summed. Native
     per-currency; no conversion.
  2. **IBNR read** — finance-service KPI composer queries `report_job
     WHERE report_key='IBNR_TRIANGLE' AND status='COMPLETED' AND
     period_end <= :periodEnd AND period_end > :periodEnd - INTERVAL '90 days'
     ORDER BY completed_at DESC LIMIT 1`, parses `result_json.ibnr_total`
     (scalar) or `result_json.per_cohort_ultimate[]` (per-line breakdown).
     Handles both `result_json` and MinIO `payload_ref` per Phase 15 §14 fallback.
  3. **Assembly** — `incurred = subtotalIncurredExIbnr + ibnrTotal`. If no
     completed IBNR job in the 90-day window, `ibnrTotal = null`, envelope
     `warnings` carries `"IBNR run pending or older than 90 days for
     (line=X, asOf=Y) — displaying paid + Δreserve only"`, and the LOSS_RATIO_KPI
     widget shows an "IBNR pending" info-icon per K2. Rejected: synchronous
     sub-job trigger + poll (dashboards must render fast; couples display widget
     to a Kafka round-trip + Mack chain-ladder compute); case-reserve-only
     shortcut (contradicts K2's explicit full-incurred choice).
- **K10 — Storage = on-demand compute + Redis cache (15-min TTL)**:
  KPI composer runs every widget refresh (fanning out to billing / earned /
  incurred / commission aggregates); results cached in Redis with key
  `kpi:{tenantId}:{reportKey}:{periodStart}:{periodEnd}:{reportingCurrency}`
  and 15-minute TTL. Matches Phase 8 forecast precedent (on-demand, no snapshot
  table). Trend chart with 12 monthly points = 12 cache lookups (11 warm after
  first render). Cache stampede on tenant switch mitigated by the standard
  `Cache-Control: no-store` on individual widget calls + a debounced batch
  endpoint (K14). Rejected: month-close snapshot job + `kpi_snapshot` table
  (extra migration + job + backfill; can't answer as-of-Tuesday queries);
  lazy materialization (write-on-read concurrency semantics; sits awkwardly
  between the two options). A future materialized `kpi_snapshot` warm path
  for the ANNUAL trend view is a Phase 18.5 optimization.
- **K11 — Cadence = flip 5 keys to cadenced=true; refresh on load only**:
  Enum change: `COMBINED_RATIO`, `LOSS_RATIO_KPI`, `EXPENSE_RATIO`,
  `CLAIMS_FREQUENCY`, `AVERAGE_SEVERITY` all move to `cadenced=true,
  periodShape=PREVIOUS_COMPLETE_PERIOD`. Requires:
  - Phase 18 §D adapter registration in `ScheduledReportOrchestrator`
    (`services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/`)
    with 5 new `ScheduledReportShapeAdapter` implementations (finance-owned
    since composer lives there per K16 to come).
  - Phase 17 S1 whitelist extension: add the 5 KPI keys to the UI whitelist
    of cadenced report keys the tenant admin can schedule.
  - No changes to the Phase 17 dispatcher / delivery topics — same
    `medfund.notification.report-delivery` pattern; XLSX rendering =
    Phase 18 §B's `KpiWorkbookService`.
  Angular page fetches on load + on filter change; no auto-poll. Rejected:
  keep cadenced=false (retro-fit drag later; loses natural pair with
  Phase 17); 60s auto-poll (execs don't watch; polling burns Redis+CPU;
  laptops-left-open cost).
- **K12 — Multi-currency = per-currency native ratios + reporting-currency composite scalar**:
  Response payload shape (extending the standard `ReportResponse<T>` envelope):
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
      String basisNote                // MIXED_LOSS_EARNED_EXPENSE_WRITTEN for COMBINED_RATIO per K6
  ) {}
  ```
  Standard envelope's `perCurrency: Map<String, KpiValue>` carries per-currency
  ratios. Composite uses `FxRateReader.convert(...)` per invariant #6:
  fail-loud if the *composite* denominator can't be built (any missing FX for
  currency present in the data), best-effort for envelope perCurrency (missing
  currency omitted + warnings entry per G28). Angular widget layout: big
  composite number + info-icon → per-currency breakdown panel + reporting
  currency label. Rejected: reporting-only scalar (hides currency-blend
  masking; silently drops missing-FX rows); per-currency only + dropdown
  (contradicts single-headline exec KPI UX).
- **K13 — Slicing = line + scheme + producer (three filter chips)**:
  Every KPI endpoint accepts three optional filters:
  - `?insuranceLine=HEALTH|LIFE|FUNERAL|GROUP|TRAVEL|DISABILITY|VEHICLE|PROPERTY`
  - `?schemeId=<uuid>`
  - `?producerId=<uuid>`
  Blank = tenant-wide. SQL GROUP BY drops each unused dimension. Angular
  filter chips: single-select per chip; searchable dropdown for scheme +
  producer per memory `feedback_no_raw_id_inputs`. **Small-denominator
  guard**: composer returns `warnings` entry when the denominator falls
  below a per-KPI threshold (e.g. `earned < 1000` for the reporting
  currency at asOf) — widget shows "Ratio may be noisy at this slice"
  info-icon. Small-denominator threshold configurable in future via a
  tenant setting (Phase 18.5). Rejected: line-only (execs will demand
  scheme + producer drills on day 2); line+scheme (leaves producer
  half-built for the acquisition-ratio drill). Downside acknowledged:
  three-chip UI complexity + small-slice noise — mitigated by chip
  behavior + guard rail.
- **K14 — Trend = 12 monthly buckets default, dropdown to 24; MONTHLY only**:
  Default trend view = last 12 complete months (rolling, ending at previous
  month-end). Dropdown lets user switch to 24. Granularity fixed to MONTHLY
  (weekly ratios are noise). Batch endpoint on the KPI composer:
  `GET /api/v1/reports/kpi/{key}/trend?windowMonths=12|24&insuranceLine&schemeId&producerId&reportingCurrency`
  returns `List<{periodStart, periodEnd, composite: KpiValue, perCurrency:
  Map<String, KpiValue>}>`. Each element is a K10 cache lookup keyed by
  (tenant, key, periodStart, periodEnd, reportingCurrency). Young-tenant
  gap: months predating tenant creation return empty rows (envelope
  `warnings` naming them); Angular chart draws blank buckets. Rejected:
  fixed 24 (doubles compute; noisy empty area on young tenants); weekly
  granularity (meaningless for ratios; two-granularity toggle complexity).
- **K15 — Chart = KPI tile + sparkline + click-through to detail report**:
  Each KPI renders as a tile:
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
  - `COMBINED_RATIO` → anchor scroll back to top of KPI page (no separate
    detail report; the sum is the summary)
  - `CLAIMS_FREQUENCY` → `/tenant/finance/reports/claims/summary` (Phase 4)
  - `AVERAGE_SEVERITY` → same as CLAIMS_FREQUENCY
  Requires a new `app-line-chart` compact variant (height:60px, no axes,
  no legend, single-color line) — Phase 18 §C authors it as
  `app-sparkline` sitting alongside `app-line-chart` in
  `clients/angular/src/app/shared/components/charts/`. Rejected: full
  chart + accordion (scroll fatigue + duplicates detail reports);
  chart + drill both (double UI + fragile period-filter route hops).
- **K16 — Angular route = `/tenant/finance/reports/kpi` under Reports Hub**:
  Single page component `KpiDashboardComponent` at
  `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-dashboard.component.ts`.
  Sidebar entry: `KPI Dashboard` under Finance → Reports. Reports Hub gets a
  new `DASHBOARD` family group (matches `ReportFamily.DASHBOARD` already in
  `ReportKey`) with a single card linking to the page. Backend endpoints
  live under `/api/v1/reports/kpi/*` on finance-service (K17 permission gate).
  Each of the 5 KPIs has an individual endpoint (`/loss-ratio`, `/expense-ratio`,
  `/combined-ratio`, `/claims-frequency`, `/average-severity`) plus a batch
  endpoint `GET /api/v1/reports/kpi/dashboard?insuranceLine&schemeId&producerId&reportingCurrency`
  that returns all 5 in one round-trip for the tile-grid render. Trend
  endpoint per KPI: `GET /api/v1/reports/kpi/{key}/trend?windowMonths` (K14).
  Rejected: dedicated exec portal (adds a fifth portal + Keycloak role work +
  duplicates hub logic); dual-render on tenant-admin home (two paths + cache
  variants + admin-home already busy).
- **K17 — Permissions = reuse `finance:reports:view` + `@RequiresReport(key)`**:
  Zero new permission strings. Each KPI endpoint carries the standard
  ```java
  @RequiresPermission("finance:reports:view")
  @RequiresReport(ReportKey.<KPI_KEY>)
  ```
  stack — matches every other report in the plan. Batch dashboard endpoint
  requires all 5 individual permissions/toggles at once (composer 403s the
  whole payload if any of the 5 is disabled; alternative: `warnings` entry per
  disabled key + partial payload — implementer chooses at code altitude,
  recommendation is fail-loud 403 because a dashboard with missing tiles
  is confusing). Toggle-off from `/tenant/admin/settings/reports` per Phase 0
  §7. Rejected: dedicated `finance:reports:kpi:view` (role bloat for no
  security gain); `executive:dashboard:view` (contradicts invariant #2;
  "executive" role doesn't exist in `.claude/portals.md`).
- **K18 — Sub-plan = single file, 3 tranches (§0 aggregates / §A composer+Angular / §B scheduled adapter)**:
  Single sub-plan `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md`
  authored via `create-plan` at implement time. Tranches:
  - **§0 — Enum + aggregates** (~2-3 phases): `ReportKey` add
    `CLAIMS_FREQUENCY` + `AVERAGE_SEVERITY` (K1); flip 5 KPI keys to
    `cadenced=true, periodShape=PREVIOUS_COMPLETE_PERIOD` (K11). New
    contributions-service `GET /api/v1/reports/aggregate/premium-earned`
    (K8). New claims-service `GET /api/v1/reports/aggregate/claims-incurred`
    (K9 — paid + Δreserve). New finance-service
    `GET /api/v1/reports/aggregate/commissions` (K7). Each: repository +
    controller + Swagger + IT via `ReportRetrofitAssertions`.
  - **§A — KPI composer + Angular** (~3-4 phases): finance-service
    `KpiComposerService` fanning to the 4 aggregates via `CrossServiceCallHelper`
    (invariant #7); `ExecutiveKpiController` with 5 individual endpoints +
    1 batch + 5 trend endpoints (K14); IBNR lookup from `report_job.result_json`
    (K9); K12 per-currency envelope + composite scalar assembly; Redis cache
    (K10). Angular `KpiDashboardComponent` at
    `/tenant/finance/reports/kpi` (K16); new `app-sparkline` component (K15);
    tile grid + drill-through navigation (K15); 3 filter chips (K13);
    reports-hub Dashboard family card.
  - **§B — Scheduled adapter + e2e** (~2 phases): 5 `ScheduledReportShapeAdapter`
    implementations in finance-service for Phase 17 orchestrator (K11);
    Phase 17 UI whitelist extension adds the 5 KPI keys; `KpiWorkbookService`
    XLSX for scheduled + on-demand export (mirrors Phase-17 shape); Playwright
    goldens (open dashboard, filter by line, drill to loss-ratio detail,
    export XLSX, receive scheduled email via mailpit); docker-compose IT
    deferred to Phase-18-integration follow-up per Phase 15/16/17 precedent.
  Est. 6-8 phases total. Rejected: 5-tranche (over-tranched; §0 would be a
  two-line phase); no sub-plan (contradicts scope-warning banner + parent
  plan file already 626KB).

## Settled by fact (not asked)

- **F18-1 — ReportFamily = DASHBOARD**. The three enum entries are already
  `ReportFamily.DASHBOARD` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:136-139`).
  The two new keys (`CLAIMS_FREQUENCY`, `AVERAGE_SEVERITY` per K1) land in
  the same family. Reports Hub renders a `Dashboard` group.

- **F18-2 — Envelope shape unchanged**. Standard `ReportResponse<T>` per
  invariant #1 wraps every KPI response. `T` is `KpiReportData` (K12).
  `perCurrency: Map<String, KpiValue>` (K12); `fxRates + warnings` per G28;
  `period: ReportPeriod` populated (K1 KPIs are all
  `PREVIOUS_COMPLETE_PERIOD` per K11).

- **F18-3 — Native currency in aggregate rows**. All 3 new aggregate
  endpoints (K7/K8/K9) return native-currency rows; conversion to
  reporting currency happens only in the KPI composer via `FxRateReader`
  (K12) per invariant #6.

- **F18-4 — Missing FX semantics**. Composite scalar fails loud on missing
  FX (throws `ReportGenerationException` naming (base, quote, date));
  per-currency envelope is best-effort with warnings entry (G28).

- **F18-5 — Audit on KPI export**. XLSX export path emits
  `SecurityEventPublisher.publishDataAccess(...)` with `reportKey=<KPI_KEY>`
  before returning bytes (invariant #3). JSON reads do NOT emit per G24.

- **F18-6 — Rule 8 audit on tenant-toggle mutation**. Toggling any KPI
  key from `/tenant/admin/settings/reports` emits `AuditEvent` via the
  Phase 0 `TenantReportConfigService.updateEnabled` path; K11 cascade
  applies (S9) since KPIs will be cadenced.

- **F18-7 — Reactor-Kafka ack pattern**. Any Kafka publish path in
  Phase 18 (e.g. the Phase 17 orchestrator emitting delivery event on
  behalf of scheduled KPI runs) uses `.doOnSuccess` per
  `bug_reactor_kafka_ack_swallow` memory.

- **F18-8 — Migration numbering**. Phase 18 §0 has **no schema changes**
  — the 5 enum flips + 2 new enum entries are code-only, and the 3 new
  aggregate endpoints are SELECT-only over existing tables (Phase 11
  commission_transaction, Phase 12 earning_schedule, Phase 14
  claim_reserve_history, Phase 15 report_job). No Flyway migrations
  required. K18 sub-plan §0 spells this out to avoid a reviewer
  looking for an absent migration.

- **F18-9 — `public.` prefix**. Not applicable — the three new aggregate
  endpoints all query tenant-schema tables. Unqualified names per
  `bug_public_prefix_silent_rollback` memory.

- **F18-10 — Cross-service peer-failure**. Composer uses
  `CrossServiceCallHelper` with envelope `warnings` capture per invariant
  #7 (G37). A peer down (contributions or claims aggregate 500) → KPI
  page shows the widget with a warning banner, not a broken widget.

- **F18-11 — Tenant scoping**. Every aggregate SQL is tenant-scoped via
  the standard `TenantContext` interceptor (Rule 2). No new tenant
  resolution logic needed.

- **F18-12 — KPI keys already in `tenant_report_config` reachable**.
  Absent-row defaults to enabled per V130. First tenant toggle load
  seeds a row via Phase 0's bulk-upsert. No seeding migration needed.

- **F18-13 — `AuditActor` for scheduled KPI runs**. Scheduled dispatches
  inherit the schedule creator's `updated_by_actor_*` via Phase 17 S10.
  Manual XLSX exports carry the requesting user via `AuditActor.of(jwt)`
  per `feedback_audit_actor_email` memory.

- **F18-14 — Kafka topic naming**. No new topics. Phase 18 reuses the
  Phase 17 `medfund.notification.report-delivery` + `-failed` topics
  via the K11-added adapters.

- **F18-15 — Retention**. `report_job` rows for scheduled KPI runs
  inherit `retention_class = OPERATIONAL_90D` (Phase 15 §14) per
  existing `ReportJobRetentionJob`. No new retention logic.

## Owed back to the parent plan

Corrections + contradictions this grill surfaced against the parent plan:

- **`phases_status` frontmatter stale**: current header line
  `"18-19": outline depth; each needs its own grilling pass before implementation`
  needs to be split — Phase 18 is now grilled (this session), Phase 19
  still outline. Apply-step will change the frontmatter to:
  ```yaml
  "18": grilled 2026-09-05 (K1..K18 — executive-KPI decisions numbered
    K* to avoid collision with plan-wide G* and prior phase R*/P*/U*/L*/A*/I*/REG*/S* numbering)
  "19": outline depth; needs its own grilling pass before implementation
  last_grilled_phase: 18
  last_grilled_date: 2026-09-05
  ```

- **Phase 17 S1 whitelist widens (K11 consequence)**: Phase 17 S1 listed
  13 cadenced keys in scope for scheduled email delivery. K11 flips 5 KPI
  keys to cadenced=true — three of them (COMBINED_RATIO, LOSS_RATIO_KPI,
  EXPENSE_RATIO, plus the two new CLAIMS_FREQUENCY, AVERAGE_SEVERITY) join
  the whitelist. Phase 17 sub-plan `2026-08-31-scheduled-email-delivery.md`
  needs a Deviations note that Phase 18 sub-plan §B will add 5 more
  `ScheduledReportShapeAdapter` implementations to the orchestrator's
  finance-owned adapter set + extend the S1 whitelist enforcement in the
  Angular `/tenant/admin/settings/report-schedules` UI.

- **`ReportKey.java` cadenced=false→true migration is code-only, but
  changes the S1 UI whitelist rule** — F-S10 in Phase 17 says the 24
  cadenced keys are already tagged and the UI whitelist enforces the
  13 in-scope. When Phase 18 flips 5 more keys to cadenced=true, the
  whitelist must accept them. Not a bug in Phase 17; a foreseeable
  extension. Sub-plan §B owns the UI-whitelist widening.

- **Phase 2 aggregate endpoints established the shape**: the three new
  aggregate endpoints (K7/K8/K9) mirror the Phase 2 `BillingAggregateController`
  and Phase 5 `ClaimsAggregateController` shape verbatim (per-native-currency
  rows, dimension filter, half-open period interval). Sub-plan §0 refers
  to these as the pattern, not re-invents shape.

- **Phase 14 IBNR result shape assumed but not verified end-to-end**:
  K9 depends on `report_job.result_json.ibnr_total` being a scalar and
  `per_cohort_ultimate` being an array. Explore K9 §2 confirmed this
  from `chain_ladder.py:54-67`, but the sub-plan §A KPI composer needs
  a JSON parse test with a golden fixture to protect against a Phase 14
  schema drift.

- **No CLAIMS_FREQUENCY exposure feed exists** (K1 consequence): frequency
  requires a "policy-months in force" denominator. Phase 13 lifecycle
  data has `policy_status_history` but no direct policy-months rollup.
  Sub-plan §0 needs a decision at code altitude: (a) add a new
  contributions-service `GET /api/v1/reports/aggregate/policy-exposure`
  computing SUM of policy-months-in-force per (line, scheme, period), or
  (b) approximate with `active_policy_count × period_length_in_months`
  read from Phase 13's active-policy count. Recommendation for the sub-plan
  author: option (a) with a Phase 13 join; note this here so the author
  doesn't discover it at implementation.

- **No `.claude/*.md` architecture doc mentions ratio definitions**:
  Explore verification §10. Sub-plan §0 could optionally add a short
  section to `.claude/adjudication.md` or a new `.claude/kpi.md` naming
  the formulas from K2-K6 for future reference. Not strictly required
  but avoids re-litigating the definitions at code-review time.

## Owed back to the parent plan

_(populated at apply time)_
