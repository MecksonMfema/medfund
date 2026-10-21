---
date: 2026-09-06T20:38:00+02:00
researcher: Methuseli
git_commit: bcec612b64567c90565d7a444860734d5444d7ab
branch: rename-adjustments-to-notes
repository: medfund
topic: "Reporting-page UI drift vs the rest of the Angular app"
tags: [research, codebase, angular, reports, ui, design-system]
status: complete
last_updated: 2026-09-06
last_updated_by: Methuseli
---

# Research: Reporting-page UI drift vs the rest of the Angular app

**Date**: 2026-09-06T20:38:00+02:00 · **Researcher**: Methuseli · **Commit**: bcec612b · **Branch**: rename-adjustments-to-notes

## Research Question
"Check all the reporting pages in the application; the current UI on the pages does not match the UI in the rest of the application."

## Summary
The reporting pages are structurally close to the rest of the app (they use `<header class="page-header">`, `.btn-*` classes, `.banner` classes and the shared `<app-data-table>` in many places) but they diverge in five load-bearing ways that make them *look* different from every other portal page:

1. **A local `.tx-toolbar` per report file, not the app's shared one.** The rest of the app uses the flat, flush, divider-separated toolbar defined in `clients/angular/src/app/pages/tenant/billing/transactions/transactions-list.component.scss:59-150`. Reports re-declare `.tx-toolbar` inside each report SCSS with a *different* style (flex-wrap layout, `background: #f8fafc`, hardcoded gaps). Same class name, different visuals.
2. **A local `.rate-table` per report file, not `<app-data-table>` or a shared table baseline.** Every finance report has its own `.rate-table` with its own header colors, padding, row hover, and status pills. The shared `<app-data-table>` (`clients/angular/src/app/shared/components/data-table/data-table.component.scss`) has the "real" design-system table, but reports only use it for the *paginated ledger* sections; the summary/matrix/monthly-bucket tables are always the divergent local one.
3. **Hardcoded hex colors instead of `--color-*` tokens.** Global styles set a full theme in `clients/angular/src/styles.scss:1-426` (ocean-blue primary, off-white bg, status color families with `-light` variants). Reports mostly ignore this — the KPI subsystem is the worst offender with 50+ hardcoded hex values across two files, but claims, compliance, receipts, and reinsurance all mix hex with tokens.
4. **Duplicated status-pill implementations.** Global `.badge-*` classes exist for every status the tables render, but claims reports invent `.status-pill`, AML alerts invent `.status-chip`, and each defines its own colors with hardcoded hex/rgba. The badges in a report row don't visually match the badges in the same row inside `/tenant/billing/transactions` or `/tenant/claims/pending`.
5. **Custom sub-components not extracted to `shared/`.** `recoveries-bordereau` ships its own modal + its own `.btn-primary`/`.btn-danger` that shadow the globals. `commission-statement` ships its own producer picker (`.picker-*`, `.chip-row`) instead of the shared debounced search-select pattern. KPI ships its own search dropdown instead of `<app-select>`.

The `<header class="page-header">` pattern is **consistent** across almost all report pages and matches the rest of the app; the drift is in the toolbar, the tables inside the page, the colors, and the badges. Two report pages break the header pattern too (`reports-hub` uses `<div class="reports-hub">` + inner h1; `reports-tab` and `report-schedules-page` wrap in `<div class="page">` / `<div class="reports-tab">` before the header) but these are cosmetic naming, not visual drift.

## Findings

### 1. Global design system — what "the rest of the app" looks like

**Entry point:** `clients/angular/src/styles.scss:1-426` defines the full token set:

- **Colors:** `--color-primary: #0077B6` (ocean blue), `--color-surface: #FFFFFF`, `--color-bg: #F8FDFF`, `--color-text-primary: #1B2A4A`, `--color-text-muted: #8E9BB8`. Status families come in matched pairs: `--color-success` / `--color-success-light`, and similarly for warning/error/info.
- **Spacing:** `--spacing-xs` 4px through `--spacing-4xl` 48px. Page content padding is `var(--spacing-xl)` (24px) set once on `.page-content` (`clients/angular/src/app/layout/layout.component.scss`).
- **Radius:** `--radius-md` 8px for buttons/inputs, `--radius-lg` 12px for cards/panels, `--radius-full` for pills.
- **Typography:** Inter font family, `--font-size-*` scale from `xs` 12px through `4xl` 36px.

**Shared surfaces (global CSS classes):**
- `.card` at `styles.scss:215-226` — white surface, 12px radius, `--shadow-sm`, elevated to `--shadow-md` on hover.
- `.btn` + variants at `styles.scss:283-396` — `.btn-primary`, `.btn-secondary`, `.btn-outline`, `.btn-default`, `.btn-warn`, `.btn-danger` and size modifiers `.btn-sm`, `.btn-icon`.
- `.badge` + status variants at `styles.scss:228-281` — `.badge-active/approved/adjudicated/paid/completed` (success), `.badge-pending/submitted/draft/…` (warning), `.badge-rejected/suspended/overdue/…` (error), `.badge-enrolled/verified/info` (info).
- `.section-header` + `.section-icon` at `styles.scss:98-148` — reusable content-section title bar; icon pill in primary tint.

**Shared components (`clients/angular/src/app/shared/components/`):**
- `<app-data-table>` — the canonical list table with built-in search, sortable headers, badges, pagination, empty state, loading skeleton, action columns. Defined at `data-table.component.ts` / `.html` / `.scss` (all ~200+ lines each).
- `<app-stat-card label icon value color>` — the canonical KPI card (`stat-card.component.scss:1-106`), colored icon variants `.icon-blue/-green/-orange/-red/-cyan/-dark`, standard 30px value font, trend pill, footer action link.
- `<app-select>` — the app's dropdown (used in every filter across the non-report portals).
- `<app-skeleton>` — line/circle/card/table-row variants with shimmer animation.
- `<app-icon>` — icon renderer.
- `<app-coming-soon>` — placeholder for unfinished features.
- Chart family: `<app-area-chart>`, `<app-bar-chart>`, `<app-pie-chart>`, `<app-line-chart>`.

**Canonical page structure** (`clients/angular/src/app/pages/tenant/claims/pending/pending-claims-list.component.scss:3-24`, `clients/angular/src/app/pages/tenant/billing/transactions/transactions-list.component.scss:59-150`):
```html
<header class="page-header">                     <!-- white surface, border-bottom, padding: 20px 24px -->
  <div>
    <h1>Title</h1>
    <p class="page-sub">Subtitle…</p>
  </div>
  <button class="btn btn-primary">Action</button>
</header>

@if (errorMessage) {
  <div class="banner banner-error">…</div>       <!-- var(--color-error-light) bg, click to dismiss -->
}

<div class="tx-toolbar">                          <!-- flat, flush, divider-separated cells -->
  <div class="toolbar-cell cell-search">…</div>
  <div class="toolbar-cell cell-select">…</div>
  <div class="toolbar-cell cell-clear">…</div>
</div>

<app-data-table [columns]="cols" [data]="rows" …></app-data-table>
```

### 2. Reporting-page inventory — ~55 pages under two roots

**`clients/angular/src/app/pages/tenant/finance/reports/`** — the main body, ~50 pages:
- **Reports hub:** `reports-hub.component.*` (family cards + regulatory due-date banner).
- **KPI:** `kpi/kpi-dashboard.component.*` + `kpi/kpi-tile.component.*`.
- **Claims family** (11 pages): `claims/{claims-detail, claim-status-matrix, denial-analysis, frequency-severity, group-claims, high-cost-claimants, member-claims, pre-auth-activity, provider-claims, provider-network-utilization, scheme-claims}`.
- **Billing family** (2 pages): `billing/{group, scheme}-billing-report`.
- **Receipts family** (4 pages): `receipts/{group-receipts, member-receipts, receipts-detail, scheme-receipts}`.
- **Receivables analytics** (8 pages): `member-billing`, `member-payments`, `aged-debtors`, `balance-history/{member,provider}`, `collection-rate`, `collection-rate-trend`, `loss-ratio`, `cash-flow-forecast`.
- **Commission & reinsurance** (5): `commission/{commission-statement, commission-clawback-register}`, `reinsurance/{cession-bordereau, recoveries-bordereau, treaty-utilization}`.
- **Underwriting & premium** (4): `underwriting/{new-business-register, premium-register, endorsement-register, upr-movement}`.
- **Policy lifecycle** (3): `policy-lifecycle/{group-census, movement/movement, persistency-cohort/persistency-cohort}`.
- **Actuarial** (7): `actuarial/{actuarial-triangle, ibnr-triangle, loss-triangle, lapse-study, mortality-study, morbidity-study, persistency-study}`.
- **IFRS 17** (2): `ifrs17/{insurance-revenue-service-result, lrc-lic-reconciliation}`.
- **Regulatory & compliance** (2 currently rendered): `regulatory/due-date-banner`, `compliance/aml-str/aml-alerts-list`.
- **Fraud/SIU:** `fraud/fraud-report.component.*`.

**`clients/angular/src/app/pages/tenant-admin/settings/`:**
- `report-schedules/report-schedules-page.component.*` (+ `schedule-recipient-list`, `schedule-run-history`).
- `reports/reports-tab.component.*` (+ `high-cost-claimant-config`).

### 3. Divergence #1 — Local `.tx-toolbar` per file, same name as the global one

**The app's shared toolbar** lives at `clients/angular/src/app/pages/tenant/billing/transactions/transactions-list.component.scss:59-150`:
- `min-height: 48px`, `padding: 0 24px`, `background: var(--color-surface)`, `border-bottom: 1px solid var(--color-border-light)`
- Cells are `.toolbar-cell` with `border-right: 1px solid var(--color-border-light)` (vertical dividers)
- `flex-wrap: nowrap` — single-line, flat, flush

**In reports**, `.tx-toolbar` is redeclared in every report SCSS with **different visuals**:
- `claims/claims-report.component.scss` — `flex-wrap: wrap`, `gap: 12px`, `background: var(--color-surface-2, #f9fafb)`
- `billing/billing-report.component.scss` — same wrap-based layout
- `receipts/receipts-report.component.scss` — same
- `aged-debtors/aged-debtors.component.scss` — full-bleed variant with `min-height: 48px` (closer to the canonical), plus `::ng-deep` selectors to reach into `<app-select>` internals (line 101-114) — the only report to attempt the flat/flush look, but it breaks encapsulation to do so
- `kpi/kpi-dashboard.component.scss` — uses a *different class* entirely, `.kpi-toolbar`, with a CSS-grid 4-col auto-fit layout and a hardcoded `#f8fafc` background

**Result:** Every report page has a filter row that visually differs from `/tenant/billing/transactions` (the canonical), and they differ from each other.

### 4. Divergence #2 — Local `.rate-table` instead of `<app-data-table>` for summary tables

Every finance report renders its summary/matrix/monthly-bucket tables as a plain `<table class="rate-table">` with styles in `claims-report.component.scss`, `billing-report.component.scss`, `receipts-report.component.scss`, `cash-flow-forecast.component.scss`, etc. These do not match `<app-data-table>` in:
- Header treatment (`<app-data-table>` uses `background: var(--color-bg)`, uppercase 12px labels, letter-spacing 0.04em; `.rate-table` headers are inconsistent per file).
- Row padding, border color, hover treatment.
- Empty/loading states — `<app-data-table>` has `.empty-state` with icon/title/description and a three-dot loading animation; `.rate-table` sections typically just render `<p>Loading…</p>` or a bare row.
- Status column rendering — `<app-data-table>` uses `.badge-{status}` from globals; `.rate-table` uses local `.status-pill` (see divergence #4).

**Hybrid pattern:** `claims-detail`, `claim-status-matrix`, `receipts-detail` use `.rate-table` for the summary and `<app-data-table>` for the drill-down ledger — so within a *single page* users see two different table styles.

**One page uses a raw `<table class="data-table">` with manual action cells:** `compliance/aml-str/aml-alerts-list.component.*` — not `<app-data-table>` at all.

### 5. Divergence #3 — Hardcoded hex colors, minimal token adoption

Aggregate token-adoption rate across report SCSS files:

| Family | var() usage | Mixed | Hardcoded |
|---|---|---|---|
| KPI dashboard/tile | ~0% | ~20% | **~80%** |
| Compliance (AML) | ~20% | ~30% | ~50% |
| Claims | ~30% | ~40% | ~30% |
| Actuarial / IFRS 17 | ~40% | ~40% | ~20% |
| Receivables (cash-flow, balance-history) | ~50% | ~30% | ~20% |
| Billing | ~60% | ~30% | ~10% |
| Settings pages (reports-tab, report-schedules) | **~95%** | ~5% | ~0% |

The worst offenders are `kpi/kpi-dashboard.component.scss` and `kpi/kpi-tile.component.scss` (~300 lines combined, 50+ hex values including `#64748b`, `#f8fafc`, `#e2e8f0`, `#6366f1`, `#1e293b`, `#eef2ff`, `#c7d2fe`, `#4338ca`, `#16a34a`, `#dc2626`, `#fef3c7`, `#92400e`, etc.). This is why the KPI dashboard visually reads as "not part of the app" — the primary purple `#6366f1` isn't even the app's `--color-primary` `#0077B6`.

### 6. Divergence #4 — Duplicate status-pill implementations

**Global** at `styles.scss:228-281`: `.badge`, `.badge-active`, `.badge-approved`, `.badge-pending`, `.badge-rejected`, `.badge-suspended`, `.badge-enrolled`, `.badge-verified`, `.badge-info`, `.badge-completed`, `.badge-overdue`, `.badge-failed`, `.badge-processing`, `.badge-invited`. All use `--color-{status}-light` bg + darker text color. `<app-data-table>` renders these automatically when a column has `type: 'status'`.

**Reports instead invent:**
- `.status-pill` in `claims-report.component.scss` — variants `pill-approved`, `pill-rejected`, `pill-pending`, etc., with hardcoded `#fee2e2`/`#991b1b`, `#fef3c7`/`#92400e`, `#dcfce7`/`#166534`, `#eff6ff`/`#2563eb`.
- `.status-chip` in `aml-alerts-list.component.scss` — `chip-raised`, `chip-under_review`, `chip-filed`, `chip-closed` with RGBA translucent backgrounds and hardcoded text colors (`#b91c1c`, `#a16207`, `#1d4ed8`, `#166534`).
- Ad-hoc badge coloring inline in several matrix cells.

**Result:** The "Approved" pill on `/tenant/finance/reports/claims/claims-detail` looks nothing like the "Approved" pill on `/tenant/claims/pending` or `/tenant/billing/transactions`.

### 7. Divergence #5 — Custom sub-components not extracted to `shared/`

- **`recoveries-bordereau.component.scss:1-67`** defines its own `.btn`, `.btn-primary`, `.btn-danger` — these shadow the globals within the component and diverge in padding/color from the app-wide button system. It also defines a full `.modal-*` family (backdrop, dialog, header, body, footer) — the app has no shared modal component so every one is local, but this violates the memory rule `feedback_no_raw_id_inputs` in spirit (bespoke instead of reused).
- **`commission-statement.component.*`** ships a custom producer picker (`.picker-*`, `.chip-row`) instead of using `<app-select>` with debounced search — inconsistent with the debounced search-select pattern the rest of the app uses (compare: `kpi-dashboard` has *its own* debounced search-select embedded inline, and neither matches the AsyncSelect used in claims/producer pages).
- **`kpi/kpi-tile.component.*`** re-implements what `<app-stat-card>` does — colored icon pill, value, label, sparkline, trend pill — but with a completely different styling system.
- **`reports-hub.component.scss:1-136`** defines a whole `.hub-*` family (grid, family-card, report-item, empty-state, counter badge) with `#0369a1` and `#b91c1c` hex hardcoded — the empty state uses the pattern rather than `<app-coming-soon>` or the standard `.empty-state`.

### 8. Positive: What the reports actually *do* right

- **`<header class="page-header">` is consistent** across almost every report page (see the ~20-file table in the underlying research pass). The header/subtitle/action-button structure matches the rest of the app.
- **`.btn-primary`, `.btn-default`, `.btn-outline`** are used for all header/toolbar actions (with the recoveries-bordereau exception noted above).
- **`.banner`, `.banner-error`, `.banner-warning`, `.banner-info`** are used consistently for error/warning messages, though the colors are sometimes hardcoded instead of using tokens.
- **`<app-data-table>` is used** for every paginated ledger — it's only the summary/matrix tables that fall back to `.rate-table`.
- **`<app-select>` is used** for most filters (KPI and commission-statement are the two exceptions).
- **`<app-icon>`, `<app-skeleton>`, `<app-line-chart>`, `<app-waterfall-chart>`, `<app-actuarial-job-progress>`** are all shared and used broadly.

### 9. Well-structured reports (candidates as refactor templates)

- **`cash-flow-forecast/cash-flow-forecast.component.*`** — 32 lines of SCSS, minimal custom styling, reuses `.rate-table` + `.detail-section` patterns cleanly.
- **`balance-history/member-balance-history.component.*`** — clean table, no custom SCSS.
- **`collection-rate/collection-rate-report.component.*`** — minimal toolbar, single `.rate-table`, no custom styles.

### 10. Most divergent reports (cleanup priority)

1. **KPI subsystem** (`kpi/kpi-dashboard.component.*`, `kpi/kpi-tile.component.*`) — ~300 lines of custom SCSS, 50+ hardcoded hex, wrong primary color (`#6366f1` vs `--color-primary #0077B6`), custom toolbar class, custom search dropdown, custom tile.
2. **`aged-debtors/aged-debtors.component.scss`** — uses `::ng-deep` (line 101-114) to pierce component boundaries.
3. **`compliance/aml-str/aml-alerts-list.component.*`** — raw `<table class="data-table">` not `<app-data-table>`, custom `.status-chip` colors, not using standard toolbar.
4. **`reinsurance/recoveries-bordereau.component.scss`** — local `.btn-primary`/`.btn-danger` shadowing globals, self-contained modal styling that should be shared.
5. **`fraud/fraud-report.component.*`** — uses `.filter-strip` (not `.tx-toolbar`), inline `<div class="tile">` KPI tiles instead of `<app-stat-card>`.

## Architecture doc vs. code
- **`.claude/portals.md`** describes the operational portal but doesn't specify a design-system contract for reports. The report list is described as capability (which reports must exist for which line) rather than visual/component contract. There's no rule anywhere saying "reports must use `<app-data-table>`" or "reports must use `.badge-*` for statuses" — so the drift is *silent* against the documented architecture.
- **`.claude/CLAUDE.md`** critical rules (9) don't cover UI consistency. This is an implicit gap: PII/audit/tenancy/currency are load-bearing, but visual coherence has no doc.
- **No `.claude/design-system.md` exists** — the design tokens are all in `styles.scss`, discoverable only by grepping. A UI-cleanup phase should consider adding a short design-system doc that says "any new page: use `<app-data-table>` for lists, `<app-stat-card>` for KPIs, `.badge-*` for statuses, `--color-*`/`--spacing-*`/`--radius-*` tokens, `.tx-toolbar` from `transactions-list.component.scss` (or lift to global)".

## Code References

**Global design system**
- `clients/angular/src/styles.scss:1-426` — full token set, `.card`, `.btn-*`, `.badge-*`, `.section-header`
- `clients/angular/src/app/layout/layout.component.scss` — 24px `.page-content` padding

**Canonical shared components**
- `clients/angular/src/app/shared/components/data-table/data-table.component.ts` (+ `.html`, `.scss`) — the design-system table
- `clients/angular/src/app/shared/components/stat-card/stat-card.component.scss:1-106` — the KPI card
- `clients/angular/src/app/shared/components/select/select.component.ts` — the app's dropdown
- `clients/angular/src/app/shared/components/skeleton/skeleton.component.scss:1-36` — shimmer loader
- `clients/angular/src/app/shared/components/coming-soon/coming-soon.component.scss:1-46` — placeholder

**Canonical (non-report) pages**
- `clients/angular/src/app/pages/tenant/billing/transactions/transactions-list.component.scss:59-150` — the app's *actual* `.tx-toolbar`
- `clients/angular/src/app/pages/tenant/claims/pending/pending-claims-list.component.scss:3-24` — canonical `.page-header`
- `clients/angular/src/app/pages/tenant-admin/settings/settings.component.scss:21-118` — canonical section pattern with high var() adoption
- `clients/angular/src/app/pages/platform/users/users.component.html` — canonical `<app-stat-card>` grid

**Divergent reporting pages (drill points)**
- `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-dashboard.component.scss` — 167 lines, ~80% hardcoded hex, custom `.kpi-toolbar` and custom search dropdown
- `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-tile.component.scss` — 135 lines, primary color `#6366f1` (wrong)
- `clients/angular/src/app/pages/tenant/finance/reports/aged-debtors/aged-debtors.component.scss:101-114` — `::ng-deep` piercing
- `clients/angular/src/app/pages/tenant/finance/reports/compliance/aml-str/aml-alerts-list.component.scss` — custom `.status-chip`, custom raw table
- `clients/angular/src/app/pages/tenant/finance/reports/reinsurance/recoveries-bordereau.component.scss` — local `.btn-primary`/`.btn-danger`, self-contained modal
- `clients/angular/src/app/pages/tenant/finance/reports/claims/claims-report.component.scss:1-192` — the shared claims-family sheet: local `.tx-toolbar`, local `.rate-table`, local `.status-pill`, hardcoded banner colors
- `clients/angular/src/app/pages/tenant/finance/reports/billing/billing-report.component.scss` — same pattern as claims
- `clients/angular/src/app/pages/tenant/finance/reports/receipts/receipts-report.component.scss` — same pattern; shared across ~15 receivables reports via `styleUrls`
- `clients/angular/src/app/pages/tenant/finance/reports/fraud/fraud-report.component.html` — `.filter-strip` (not `.tx-toolbar`), inline `.tile` divs (not `<app-stat-card>`)
- `clients/angular/src/app/pages/tenant/finance/reports/commission/commission-statement.component.*` — custom producer picker `.picker-*` / `.chip-row`

**Well-structured (refactor templates)**
- `clients/angular/src/app/pages/tenant/finance/reports/cash-flow-forecast/cash-flow-forecast.component.scss` — 32 lines, minimal, mostly tokens
- `clients/angular/src/app/pages/tenant/finance/reports/balance-history/member-balance-history.component.*`
- `clients/angular/src/app/pages/tenant/finance/reports/collection-rate/collection-rate-report.component.*`

## Architecture Insights
- **The design system exists and is coherent** (`styles.scss` + `shared/components/*`), but there is no doc, no linter, and no enforcement. New report pages are copy-forked from earlier report pages (which is why an entire family shares `styles.scss` via `styleUrls`), so drift compounds inside `pages/tenant/finance/reports/` while `pages/tenant/*` (non-report) stays close to the tokens.
- **The KPI subsystem was clearly built in isolation** — different primary color (`#6366f1` vs `#0077B6`), different toolbar class, different search-select pattern, its own tile component that duplicates `<app-stat-card>`. This is the strongest visual mismatch and the highest-value cleanup target.
- **Status-badge duplication is the most user-visible drift.** A user going from `/tenant/billing/transactions` (`.badge-approved`, teal) into `/tenant/finance/reports/claims/claims-detail` (`.status-pill.pill-approved`, `#dcfce7`/`#166534`) sees two visibly different "Approved" chips for the same underlying state.
- **`.tx-toolbar` name-collision is a hidden trap** — because the class name is the same but the styles are per-file, a developer refactoring the shared toolbar in `transactions-list.component.scss` will not affect the reports, and vice versa. A short-term fix is to rename the local class; the long-term fix is to lift `.tx-toolbar` to a global class or a `<app-tx-toolbar>` component.
- **`<app-data-table>` is under-used inside reports.** The pattern of "summary matrix on top, `<app-data-table>` ledger below" is baked into `claims-detail`, `claim-status-matrix`, `receipts-detail`. There's no fundamental reason the summary can't be an `<app-data-table>` too — the API supports non-paginated rendering with `[serverSide]="false"`.
- **Compliance with the 9 Critical Rules is intact** — none of this drift affects tenancy, currency, audit, or security. It's a pure UX/UI-consistency finding.

## Historical Context (from thoughts/shared/)
- No prior research doc addresses report UI consistency specifically. Prior research under `thoughts/shared/research/` covers domain topics (advance payments, audit path, CTC payments, payment-run vs payments, contribution-statement PDF) rather than frontend consistency. The design drift accumulated as ~50 report pages shipped in phases (17-19 landed the latest — see `git log`).
- The `2026-09-06-tenant-configurable-operations-sidebar.md` plan (just landed) touches settings and sidebar visibility but does not modify report UI.

## Related Research
- None directly. Adjacent: `.claude/portals.md` for portal spec (operational portal owns most reports).

## Open Questions
- **Scope preference:** cleanup all reports in one large refactor, or fix the top 5 divergent pages (KPI, aged-debtors, aml-alerts, recoveries-bordereau, fraud) and leave the rest? A staged approach avoids one giant PR touching 55 files.
- **`.tx-toolbar` strategy:** lift the canonical implementation from `transactions-list.component.scss` to a global class in `styles.scss`, extract it as `<app-toolbar>` with cell-projection slots, or rename all report-local `.tx-toolbar` to `.report-toolbar` and accept the divergence?
- **`.rate-table` strategy:** promote to global, or push everyone onto `<app-data-table>` even for non-paginated summary tables? The latter is cleaner but touches every report template.
- **Status pills:** delete `.status-pill` / `.status-chip` and switch to `.badge-*` for all reports (breaking visual change per user), or add the missing status variants to global `.badge-*` first?
- **KPI subsystem:** rewrite `kpi-tile` to wrap `<app-stat-card>` (with sparkline slot), or rewrite `<app-stat-card>` to accept a sparkline and delete `kpi-tile`?
- **Design-system doc:** should we add `.claude/design-system.md` codifying the tokens and shared-component contract so future report pages don't drift again? (Recommended.)

---

## Suggested next step (research → plan hand-off)

Research written to `thoughts/shared/research/2026-09-06-reports-ui-consistency.md`.

Clear your context, then run:
```
create-plan thoughts/shared/research/2026-09-06-reports-ui-consistency.md
```

Add a steer if you want scope-narrowed: e.g. `"focus on KPI subsystem + status badges only; defer table unification"` or `"do everything but do it as one phase per divergence"`.
