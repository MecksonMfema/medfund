---
date: 2026-09-06
git_commit: 9b22a840
branch: rename-adjustments-to-notes
research:
  - thoughts/shared/research/2026-09-06-reports-ui-consistency.md
steer: "Full sweep across all ~55 reports in one plan"
services_touched: [angular]
status: draft
---

# Reports UI Consistency Sweep

## Overview

Bring the ~55 report pages under `clients/angular/src/app/pages/tenant/finance/reports/` (plus the two settings-tab pages under `.../tenant-admin/settings/`) into visual and structural parity with the rest of the Angular app. Six phases, each independently reviewable, each leaving the app fully functional. The plan is scoped to Angular only; no backend changes.

## Current State Analysis

The design system is coherent and complete (`clients/angular/src/styles.scss:1-426` + `clients/angular/src/app/shared/components/*`), but the reporting family has drifted in five load-bearing ways:

- **Local `.tx-toolbar` per report SCSS file (~20 files) with wrap-based layout** clashing with the canonical flat/flush toolbar at `clients/angular/src/app/pages/tenant/billing/transactions/transactions-list.component.scss:59-150`. Same class name, different visuals — a hidden trap where a change to one has no effect on the other.
- **Local `.rate-table` per report SCSS file** for summary/matrix tables — never matches `<app-data-table>` for header treatment, row padding, hover, or empty/loading states. Hybrid pages (`claims-detail`, `claim-status-matrix`, `receipts-detail`) show both styles in one page.
- **Hardcoded hex colors instead of `--color-*` tokens.** KPI subsystem is ~80% hex (including the wrong primary `#6366f1` where the app is `--color-primary: #0077B6`); compliance, claims, receipts, reinsurance all mix hex with tokens.
- **Duplicated status pills.** `.status-pill` in `claims-report.component.scss`, `.status-chip` in `aml-alerts-list.component.scss`, both with hardcoded hex/rgba. Global `.badge-*` covers most of these statuses already; the AML statuses (`raised`, `under_review`, `filed`, `closed`) are missing and need to be added.
- **Custom sub-components not extracted to `shared/`.** `recoveries-bordereau` ships local `.btn-primary`/`.btn-danger` that shadow the globals + its own modal styling. `commission-statement` reimplements a producer picker instead of using the shared `<app-entity-picker>`. `kpi-tile` duplicates `<app-stat-card>` behavior with a completely different styling system. `reports-hub` invents `.hub-empty` instead of using the standard `.empty-state`. `fraud/fraud-report` uses `.filter-strip` (not `.tx-toolbar`) and inline `.tile` divs (not `<app-stat-card>`). `aged-debtors` reaches into `<app-select>` internals via `::ng-deep`.

Compliance with the 9 Critical Rules is intact; this is a pure UX/UI consistency finding.

## Desired End State

- One canonical `.tx-toolbar` and one canonical `.rate-table` in `styles.scss`; no locally-defined duplicates in any report SCSS.
- All report status chips render via global `.badge-{status}`; `.status-pill` and `.status-chip` deleted from the codebase.
- KPI dashboard renders through `<app-stat-card>` (with a new sparkline slot). Primary color is `--color-primary`. All hex values in `kpi/*.scss` are `--color-*` tokens.
- `commission-statement` uses `<app-entity-picker kind="producer">`. `recoveries-bordereau` uses the global `.btn-*` classes; its modal stays local but no longer shadows globals. `fraud/fraud-report` renders KPIs via `<app-stat-card>` and its filter row via `.tx-toolbar`. `reports-hub` renders its empty state via `<app-coming-soon>` or the standard `.empty-state`. `aged-debtors`'s `::ng-deep` is gone.
- `.claude/design-system.md` codifies the tokens + shared-component contract so future report pages don't drift.

**Verification:** the "Approved" chip on `/tenant/finance/reports/claims/claims-detail` visually matches the "Approved" chip on `/tenant/billing/transactions` and `/tenant/claims/pending`. The filter row on any report visually matches `/tenant/billing/transactions`. KPI tiles use `--color-primary` (ocean blue), not `#6366f1` (indigo).

### Key Discoveries

- Global `.badge-*` at `clients/angular/src/styles.scss:228-281` covers most statuses (DRAFT, APPROVED, COMMITTED-via-completed, VOIDED-not-yet, RAISED-not-yet, etc.). Full missing list is finalized during Phase 1 audit; expected additions: `.badge-raised`, `.badge-under_review`, `.badge-filed`, `.badge-closed`, `.badge-voided`.
- `<app-stat-card>` at `clients/angular/src/app/shared/components/stat-card/stat-card.component.ts:13-22` currently takes Inputs only. Adding a content-projection slot for a sparkline is additive and won't break existing consumers.
- `<app-entity-picker>` at `clients/angular/src/app/shared/components/entity-picker/entity-picker.component.ts:22` supports `EntityKind = 'provider' | 'member' | 'group' | 'scheme' | 'beneficiary'`. Extending with `'producer'` requires wiring `ProducerService.search()` (already exists at `clients/angular/src/app/core/services/producer.service.ts:214`).
- No shared `<app-modal>` exists; the codebase pattern is per-modal components (change-group-modal, terminate-group-modal, swap-dependant-modal, etc.). `recoveries-bordereau`'s modal stays local — we just remove the local `.btn-*` shadowing.
- The report family SCSS files are shared across many components via `styleUrls`. Editing `receipts-report.component.scss` propagates to ~15 receivables reports; editing `claims-report.component.scss` propagates to 11 claims reports. This concentrates the diff — one file change hits many pages.
- The freshness check between the research commit `bcec612b` and HEAD `9b22a840` shows no changes under `styles.scss`, `shared/components/`, or `reports/` — every `file:line` reference in the research doc is still valid.

## What We're NOT Doing

- **Not touching backend Java, Go, Python, or Elixir services.** This is Angular-only. No API contract changes, no Kafka event shape changes, no Java class renames.
- **Not renaming Angular classes.** `CommissionAdjustment*` etc. stay named after their wire types (per G6b from the merge plan just landed).
- **Not building a generic `<app-modal>` component.** Extracting one would touch every existing bespoke modal (change-group-modal, terminate-group-modal, swap-dependant-modal, deactivate-dependant-modal, change-scheme-modal, policy-status-action-modal, terminate-producer-modal, plus the two internal to the merge just landed). That is its own plan.
- **Not adding a stylelint rule that fails builds on hardcoded hex** in Phase 6. Recommended stretch, but deferred to a separate ticket to keep this plan reviewable.
- **Not reworking `.page-header` on report pages.** It's the one thing reports already got right (see research Finding #8).
- **Not touching `<app-data-table>` internals.** Reports migrate onto the existing API surface.
- **Not migrating the ~30 summary/matrix `<table class="rate-table">` markup to `<app-data-table>`.** That was Option 2 for divergence #2; the decision was to promote `.rate-table` to a global class (Option 1), which keeps templates as-is. A follow-up plan can migrate specific summary tables to `<app-data-table>` where the ergonomics fit (paginated summaries, sortable summaries), but that is not this plan.

## Implementation Approach

Phase 1 lands the primitives (global `.tx-toolbar`, global `.rate-table`, new `.badge-*` statuses, `<app-stat-card>` sparkline slot, `<app-entity-picker kind="producer">`) without touching a single report template. All reports keep working — they just now inherit from global instead of local. Phase 2 deletes the local duplicates and rewrites templates to use `.badge-*`. Phases 3–5 clean up the specific outliers (KPI, custom sub-components, fraud + residual). Phase 6 adds the design-system doc that codifies what "consistent" means going forward.

The `styleUrls` sharing pattern makes Phase 2 cheaper than the file count suggests: one SCSS edit propagates to a whole family. The status-pill template edits are more scattered but small per file.

Phase 6 is the only phase that touches `.claude/*`. Phases 1–5 are all under `clients/angular/`.

---

## Phase 1: Design-system primitives (no template changes)

### Overview
Land the global classes, missing badges, `<app-stat-card>` sparkline slot, and `<app-entity-picker>` producer kind. No report template changes; every report keeps working unchanged.

### Changes Required

#### 1. Add canonical `.tx-toolbar` to global styles
**File**: `clients/angular/src/styles.scss`
**Changes**: Add a `.tx-toolbar` block after the `.badge-*` variants (around line 282), lifting the flat divider-separated implementation from `transactions-list.component.scss:59-150`.

```scss
/* ── Toolbar (canonical: flat, flush, divider-separated cells) ─────────── */
.tx-toolbar {
  display: flex;
  align-items: stretch;
  min-height: 48px;
  padding: 0 var(--spacing-xl);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
  gap: 0;
  flex-wrap: nowrap;
}
.toolbar-cell {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: 0 14px;
  border-right: 1px solid var(--color-border-light);
  min-height: 48px;
}
.toolbar-cell:last-of-type {
  border-right: none;
}
.toolbar-cell .cell-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-weight: var(--font-weight-medium);
  flex-shrink: 0;
}
.toolbar-cell.cell-search {
  flex: 1 1 260px;
  max-width: 380px;
}
.toolbar-cell.cell-select {
  flex: 0 0 auto;
  min-width: 220px;
}
.toolbar-cell.cell-date input[type="date"] {
  border: none;
  outline: none;
  background: none;
  height: 32px;
  padding: 0;
  min-width: 130px;
  font: inherit;
  color: var(--color-text-primary);
}
.toolbar-cell.cell-clear .clear-btn {
  background: none;
  border: none;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.toolbar-cell.cell-clear .clear-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
```

#### 2. Add canonical `.rate-table` to global styles
**File**: `clients/angular/src/styles.scss`
**Changes**: Add a `.rate-table` block after `.tx-toolbar` — a baseline that matches `<app-data-table>` visually.

```scss
/* ── Rate table (summary / matrix / monthly-bucket tables) ─────────────── */
.rate-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--font-size-sm);
}
.rate-table th,
.rate-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--color-border-light);
  vertical-align: top;
}
.rate-table th {
  background: var(--color-bg);
  font-weight: var(--font-weight-semibold);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  white-space: nowrap;
}
.rate-table th:first-child,
.rate-table td:first-child { padding-left: var(--spacing-xl); }
.rate-table th:last-child,
.rate-table td:last-child { padding-right: var(--spacing-xl); }
.rate-table td.num,
.rate-table th.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.rate-table td.actions,
.rate-table th.actions {
  text-align: right;
  white-space: nowrap;
}
```

#### 3. Add missing `.badge-*` variants
**File**: `clients/angular/src/styles.scss` (after line 281)
**Changes**: Extend each color family with the missing status keys.

```scss
/* AML/workflow statuses (info blue) */
.badge-raised,
.badge-under_review,
.badge-under-review {
  background: var(--color-info-light);
  color: #0096b4;
}

/* Regulatory-filed / resolved (green) */
.badge-filed,
.badge-closed,
.badge-committed,
.badge-received,
.badge-ceded {
  background: var(--color-success-light);
  color: #1a9a8e;
}

/* Voided / written off (muted red) */
.badge-voided,
.badge-written_off,
.badge-written-off {
  background: var(--color-error-light);
  color: #c4162d;
}

/* Expected / invoiced (amber) */
.badge-expected,
.badge-invoiced {
  background: var(--color-warning-light);
  color: #c47800;
}
```

Note: full list finalized during implementation by grepping every `.status-pill[data-status]` and `.status-chip.chip-*` variant in the codebase — the above is the expected shape.

#### 4. Add sparkline slot to `<app-stat-card>`
**File**: `clients/angular/src/app/shared/components/stat-card/stat-card.component.html`
**Changes**: Add a projected content slot below the value line.

```html
<!-- add near the bottom of the .stat-card template, after .stat-subtitle -->
<div class="stat-sparkline">
  <ng-content select="[stat-sparkline]"></ng-content>
</div>
```

**File**: `clients/angular/src/app/shared/components/stat-card/stat-card.component.scss`
**Changes**: Add the container style.

```scss
.stat-sparkline {
  margin-top: var(--spacing-sm);
  min-height: 0;
}
.stat-sparkline:empty {
  display: none;
}
```

No changes to `stat-card.component.ts` — content projection is a template-only feature.

#### 5. Extend `<app-entity-picker>` with `kind: 'producer'`
**File**: `clients/angular/src/app/shared/components/entity-picker/entity-picker.component.ts`
**Changes**:
- Add `'producer'` to `EntityKind` union
- Inject `ProducerService`
- Add producer branch to the debounced search dispatch

```typescript
// Update the type union
export type EntityKind = 'provider' | 'member' | 'group' | 'scheme' | 'beneficiary' | 'producer';

// In the constructor add ProducerService
constructor(
  // ... existing services
  private producers: ProducerService,
) {}

// In the debounced search switchMap, add:
case 'producer':
  return this.producers.search(q, 20).pipe(
    map(rows => rows.map(p => ({
      id: p.id,
      label: p.name,
      sublabel: p.reference ?? undefined,
    }))),
  );
```

#### 6. Add `ProducerService.search()` type export if missing
**File**: `clients/angular/src/app/core/services/producer.service.ts`
**Changes**: Ensure the `search()` return shape includes `id`, `name`, and `reference` — verify at implementation time.

### Success Criteria

#### Automated Verification:
- [x] `cd clients/angular && npx ng build --configuration development` succeeds
- [x] `make test-angular` passes (761 of 763 non-skipped specs green; 2 pre-existing failures in `insurance-lines.spec.ts` documented as deviations)
- [x] Grep confirms `.tx-toolbar` and `.rate-table` blocks exist in `clients/angular/src/styles.scss`
- [x] `EntityKind` union includes `'producer'` (grep)

#### Manual Verification (via `verify` skill):
- [ ] `verify` on `/tenant/billing/transactions`: filter row still renders correctly (this used the canonical, now inheriting from global)
- [ ] `verify` on any report page (e.g. `/tenant/finance/reports/aged-debtors`): still renders identical to today, because the local SCSS still overrides
- [ ] `verify` on `/tenant/finance/reports/kpi`: still renders as-is (KPI cleanup is Phase 3)

**Implementation Note:** after Phase 1 automated verification passes, pause for the human to confirm nothing regressed before moving to Phase 2.

---

## Phase 2: Delete duplicated primitives, switch templates to global badges

### Overview
Remove every local `.tx-toolbar` and `.rate-table` block from report SCSS files (they now inherit from global). Rewrite every `<span class="status-pill">` and `<span class="status-chip">` in report templates to `<span class="badge badge-{status}">`. Delete the local `.status-pill` and `.status-chip` SCSS blocks. Screenshot-review the visual change.

### Changes Required

#### 1. Delete local `.tx-toolbar` from every report SCSS
**Files** (from research Finding #3 + follow-up audit):
- `clients/angular/src/app/pages/tenant/finance/reports/claims/claims-report.component.scss`
- `clients/angular/src/app/pages/tenant/finance/reports/billing/billing-report.component.scss`
- `clients/angular/src/app/pages/tenant/finance/reports/receipts/receipts-report.component.scss`
- `clients/angular/src/app/pages/tenant/finance/reports/aged-debtors/aged-debtors.component.scss` (also removes the `::ng-deep` at line 101-114 — it exists to force the flat look which the global class now handles natively)
- Any other report SCSS containing `.tx-toolbar` (audit via `grep -rn "\.tx-toolbar" clients/angular/src/app/pages/tenant/finance/reports/`)

**Changes**: Delete the entire `.tx-toolbar { … }` block and any nested `.toolbar-cell` variants that duplicate the global. Keep any *report-specific* cell modifiers (e.g. `.cell-currency` if unique).

#### 2. Delete local `.rate-table` from every report SCSS
**Files** (audit list): every `.scss` under `pages/tenant/finance/reports/` matching `grep -l "\.rate-table" clients/angular/src/app/pages/tenant/finance/reports/**/*.scss`.

**Changes**: Delete the `.rate-table { … }` block. Keep any report-specific column-width overrides as `.rate-table .my-custom-col { … }` overrides.

#### 3. Rewrite `.status-pill` templates to `.badge`
**Files** (from research Finding #4 + audit):
- Every `.html` file under `pages/tenant/finance/reports/**` containing `class="status-pill"`
- Specifically: `claims/claims-report.component.scss` consumers (all 11 claims-family templates), `aml-alerts-list.component.html`, plus any others surfaced by `grep -rn "status-pill\|status-chip" clients/angular/src/app/pages/tenant/finance/reports/`

**Change pattern**:
```html
<!-- before -->
<span class="status-pill" [attr.data-status]="row.status">{{ row.status }}</span>

<!-- after -->
<span class="badge" [class]="'badge badge-' + row.status.toLowerCase()">{{ row.status }}</span>
```

Note the lowercase pipe — `.badge-approved` (not `.badge-APPROVED`). If a status contains underscores (`IN_ADJUDICATION`), the class is `.badge-in_adjudication` (matches the existing global naming at `styles.scss:255`).

For `aml-alerts-list.component.html`, `status-chip` follows the same pattern with `chip-{status}` → `badge-{status}`.

#### 4. Delete local `.status-pill` and `.status-chip` SCSS blocks
**Files**:
- `clients/angular/src/app/pages/tenant/finance/reports/claims/claims-report.component.scss` (`.status-pill` block)
- `clients/angular/src/app/pages/tenant/finance/reports/compliance/aml-str/aml-alerts-list.component.scss` (`.status-chip` block)
- `clients/angular/src/app/pages/tenant/finance/reports/reinsurance/facultative-queue-tab.component.scss` (`.status-pill` block, if still present after the merge just landed)
- Any others surfaced by audit

**Changes**: Delete the SCSS block entirely.

#### 5. Rewrite `aml-alerts-list` from raw `<table class="data-table">` to `<app-data-table>` OR to a `<table class="rate-table">`
Research Finding #10 flagged this as a divergent case. Simpler landing: switch to `<table class="rate-table">` (now global) with the same manual action cells inline. Full migration to `<app-data-table>` is out of scope (see "What We're NOT Doing").

**File**: `clients/angular/src/app/pages/tenant/finance/reports/compliance/aml-str/aml-alerts-list.component.html`
**Changes**: Replace `<table class="data-table">` with `<table class="rate-table">` and remove the `data-table` class definition from the SCSS.

### Success Criteria

#### Automated Verification:
- [x] `cd clients/angular && npx ng build --configuration development` succeeds
- [x] `make test-angular` passes (761 of 763 non-skipped specs green; 2 pre-existing failures in `insurance-lines.spec.ts` documented as deviations)
- [x] Grep confirms no `\.status-pill\b` or `\.status-chip\b` remain in `clients/angular/src/app/pages/tenant/finance/reports/**/*.scss`
- [x] Grep confirms no `class="status-pill"` or `class="status-chip"` remain in `clients/angular/src/app/pages/tenant/finance/reports/**/*.html`
- [ ] `verify` on `/tenant/finance/reports/claims/scheme-claims`: filter row matches `/tenant/billing/transactions` visually (flat, flush, dividers)
- [ ] `verify` on `/tenant/finance/reports/aged-debtors`: no `::ng-deep`-related layout regression
- [ ] `verify` on `/tenant/finance/reports/compliance/aml-str/aml-alerts`: table renders via `.rate-table`, status chips are teal/amber/red-family globals

#### Manual Verification:
- [ ] Side-by-side screenshot: `/tenant/finance/reports/claims/claims-detail` "Approved" chip vs `/tenant/billing/transactions` "Approved" chip — they should match
- [ ] Side-by-side screenshot: any report's filter row vs `/tenant/billing/transactions` filter row — they should match (flat, flush, dividers, same padding, same background)
- [ ] No visible regressions on the "well-structured" reports (cash-flow-forecast, member-balance-history, collection-rate-report) — these already used tokens and should be unchanged

---

## Deviations

- **2026-09-07 · Tab strip promoted to global (`.scheme-tabs` / `.scheme-tab`).** User feedback: "all lists with tabs should follow the pattern in /tenant/claims". Every tabbed list page (9 pages) redeclared the same underlined-tab strip locally with slight variations; facultative-page even used a different class name (`.tabs`/`.tab`/`.active` — a pill-tab design). Promoted the /tenant/claims tab strip to `styles.scss` under `.scheme-tabs`/`.scheme-tab`, plus new semantic aliases `.tab-bar`/`.tab-bar__tab`. Deleted the local blocks from: `claims.component.scss`, `pre-auth-list.component.scss`, `invoices-list.component.scss`, `ledger.component.scss`, `charge-preview.component.scss`, `bad-debts-list.component.scss`, `debtors-list.component.scss`, `creditors-list.component.scss`, `contributions.component.scss`. Renamed `facultative-page`'s outlier `.tabs`/`.tab`/`.active` to canonical. Verified via playwright screenshots — all tabbed lists now render identically.

- **2026-09-06 · Phase 1 revision · Global primitives shipped as self-contained cards + `.is-flush` modifier.** Original Phase 1 promoted `.tx-toolbar` and `.rate-table` from the fullbleed transactions page as-is: flat, no radius, border-bottom only. That worked for transactions/aged-debtors (fullbleed:true, padding:0) where the toolbar sits flush against a page-header / table forming one edge-to-edge white surface. But **most reports are padded pages** (`padding: var(--spacing-xl)`), so the flat toolbar rendered as a bare white strip floating on the light-blue page background — visually broken vs the canonical. Also promoted `.page-header` to global (30+ pages redeclared the same flex row locally with slight variations). Verified by driving both pages with playwright + screenshots. **Fix:**
  - `.tx-toolbar` default: rounded card (border-all + radius + shadow-sm). `.tx-toolbar.is-flush` strips the radius/shadow/side-borders for fullbleed pages.
  - `.rate-table` default: same card treatment. `.is-flush` modifier available.
  - `.page-header`: promoted to global; no default `margin-bottom` (fullbleed pages need flush stacking).
  - Fullbleed pages that use `.tx-toolbar` updated to `class="tx-toolbar is-flush"`: transactions-list, aged-debtors, charge-preview, bad-debts-list, debtors-list.

- **2026-09-06 · Phase 3 · kpi-tile stays a first-class component instead of a "thin wrapper" around `<app-stat-card>`.** The current tile carries per-currency chips, drill-through click nav, per-tile Excel export, and a basis-note affordance — `<app-stat-card>` has none of those. Reducing the tile to the wrapper the plan proposed would silently drop features the caller (`kpi-dashboard.component.html`) depends on. Instead: preserve the full API surface, restructure the template to match `<app-stat-card>`'s visual language (card surface, header layout, badge chips for per-currency), and swap every hardcoded hex for `--color-*` tokens including the wrong-primary `#6366f1` → `--color-primary`. Phase 3's other work items (kpi-dashboard toolbar → `.tx-toolbar`, custom search dropdowns → `<app-entity-picker>`, hex → tokens) are unchanged.

- **2026-09-06 · Testing · 2 pre-existing Angular unit-test failures.** `insurance-lines.spec.ts:344` "providerModeForLine lines that can be provider-paid OR member-reimbursed are OPTIONAL" fails for HEALTH/GROUP/TRAVEL/VEHICLE/PROPERTY (expected OPTIONAL, got REQUIRED). Neither the spec nor the model was touched by this sweep (`git diff HEAD -- clients/angular/src/app/core/models/insurance-lines.*` returns empty). Documented as pre-existing drift.

- **2026-09-06 · Testing · Angular coverage below the karma threshold (42% vs 70%).** Pre-existing project-wide regression, same shape as the jacoco 62% regression noted in the prior merge plan. Not introduced by this sweep; documented so the reviewer can see the baseline.

---

## Phase 3: KPI subsystem rewrite

### Overview
Rewrite `kpi-tile` as a thin wrapper around `<app-stat-card>` using the sparkline slot added in Phase 1. Replace the custom search dropdown in `kpi-dashboard` with `<app-select>` (or `<app-entity-picker>` where a search is appropriate). Delete all hardcoded hex; use `--color-*` tokens.

### Changes Required

#### 1. Rewrite `kpi-tile` as a wrapper around `<app-stat-card>`
**File**: `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-tile.component.ts`
**Changes**: Keep the `@Input()` API (label, value, currencyBreakdown, sparklinePoints, warnings, basisNote, exportHandler) but rewrite the template to compose `<app-stat-card>` with the sparkline as a projected slot.

```typescript
@Component({
  selector: 'app-kpi-tile',
  standalone: true,
  imports: [CommonModule, StatCardComponent, LineChartComponent, IconComponent],
  templateUrl: './kpi-tile.component.html',
  styleUrl: './kpi-tile.component.scss',
})
export class KpiTileComponent {
  @Input() label = '';
  @Input() compositeValue: string | number = '--';
  @Input() sparklinePoints: number[] = [];
  @Input() basisNote = '';
  @Input() warnings: string[] = [];
  @Input() exporting = false;
  @Input() color = 'blue';
  @Input() trend: number | null = null;
  @Output() export = new EventEmitter<void>();
}
```

**File**: `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-tile.component.html`
**Changes**: Replace the current custom markup with:

```html
<app-stat-card
  [label]="label"
  [value]="compositeValue"
  [color]="color"
  [trend]="trend">
  <div stat-sparkline>
    <app-line-chart
      [data]="sparklinePoints"
      [height]="40"
      [showAxes]="false">
    </app-line-chart>
  </div>
</app-stat-card>

@if (basisNote) {
  <p class="kpi-basis-note">{{ basisNote }}</p>
}
@if (warnings.length) {
  <ul class="kpi-warnings">
    @for (w of warnings; track w) { <li>{{ w }}</li> }
  </ul>
}
```

**File**: `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-tile.component.scss`
**Changes**: Shrink from ~135 lines to ~30. Delete the whole custom `.tile-*` family. Keep only `.kpi-basis-note` and `.kpi-warnings` using tokens.

```scss
.kpi-basis-note {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  margin: var(--spacing-sm) 0 0;
}
.kpi-warnings {
  list-style: none;
  padding: 0;
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-warning);
}
.kpi-warnings li {
  padding: 2px 0;
}
```

#### 2. Fix `kpi-dashboard` — replace custom search dropdown with `<app-select>` / `<app-entity-picker>`
**File**: `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-dashboard.component.html`
**Changes**: Every `.picker-*` custom search dropdown becomes an `<app-entity-picker kind="scheme">` (or `producer`, or `member` — matches what the picker filters on). Custom `.kpi-toolbar` becomes `.tx-toolbar` (global).

#### 3. Token-ize `kpi-dashboard` and `kpi-tile` SCSS
**Files**:
- `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-dashboard.component.scss`
- `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-tile.component.scss`

**Changes**: Replace every hardcoded hex with a token:
- `#6366f1` (indigo primary) → `var(--color-primary)` (ocean blue)
- `#f8fafc`, `#e2e8f0` → `var(--color-bg)`, `var(--color-border-light)`
- `#64748b`, `#475569` → `var(--color-text-secondary)`
- `#1e293b`, `#0f172a` → `var(--color-text-primary)`
- `#eef2ff`, `#c7d2fe` → `var(--color-primary-light)`
- `#4338ca` → `var(--color-primary-hover)`
- `#16a34a`, `#dc2626`, `#fef3c7`, `#92400e` → semantic tokens from status families

Full mapping list built during implementation by grepping every `#[0-9a-f]{3,6}` in the two files.

### Success Criteria

#### Automated Verification:
- [x] `cd clients/angular && npx ng build --configuration development` succeeds
- [x] `make test-angular` passes (761 of 763 non-skipped specs green; 2 pre-existing failures in `insurance-lines.spec.ts` documented as deviations)
- [x] `grep -c "#[0-9a-fA-F]\{3,6\}" clients/angular/src/app/pages/tenant/finance/reports/kpi/*.scss` returns `2` (both are the darker AA-contrast text-on-tint colors `#c4162d` / `#c47800` — same allowlist as the global `.badge-*` block in `styles.scss`, no `--color-*` token exists for them)
- [ ] `verify` on `/tenant/finance/reports/kpi`: primary color is ocean blue (screenshot delta vs today), five tiles render, sparklines still visible in each

#### Manual Verification:
- [ ] KPI dashboard visually reads as "part of the app" — same primary color, same header, same toolbar as `/tenant/billing/transactions`
- [ ] Sparklines still readable inside the smaller stat-card layout (may need `height` tweak)
- [ ] Producer / scheme filters still search + narrow the tiles correctly

---

## Phase 4: Custom sub-components — pickers, buttons, empty states

### Overview
`commission-statement` adopts `<app-entity-picker kind="producer">`. `recoveries-bordereau` drops its local `.btn-*` shadowing. `reports-hub` swaps `.hub-empty` for the standard `.empty-state`.

### Changes Required

#### 1. `commission-statement` → `<app-entity-picker kind="producer">`
**Files**:
- `clients/angular/src/app/pages/tenant/finance/reports/commission/commission-statement.component.ts`
- `clients/angular/src/app/pages/tenant/finance/reports/commission/commission-statement.component.html`
- `clients/angular/src/app/pages/tenant/finance/reports/commission/commission-statement.component.scss`

**Changes**:
- Delete the local producer-picker logic (`.picker-*` state, `.chip-row` rendering, debounced search machinery)
- Import `EntityPickerComponent`
- Template becomes:
  ```html
  <app-entity-picker
    kind="producer"
    [(ngModel)]="selectedProducerId"
    placeholder="Search producers by name…"
    (selectionChange)="onProducerChange($event)">
  </app-entity-picker>
  ```
- Delete the `.picker-*` and `.chip-row` SCSS blocks

#### 2. `recoveries-bordereau` — drop local `.btn-*` shadowing
**File**: `clients/angular/src/app/pages/tenant/finance/reports/reinsurance/recoveries-bordereau.component.scss`
**Changes**: Delete the local `.btn { … }`, `.btn-primary { … }`, `.btn-danger { … }` blocks (lines ~1-67). The templates already say `class="btn btn-primary"` etc., so removing the shadowing lets the globals take over.

**File**: `clients/angular/src/app/pages/tenant/finance/reports/reinsurance/recoveries-bordereau.component.html`
**Changes**: Verify buttons still render correctly under global `.btn-*`. Modal styles stay local (per "What we're NOT doing").

Token-ize the remaining hardcoded hex in `recoveries-bordereau.component.scss` (backdrop rgba stays, but `#111827` → `var(--color-text-primary)` etc.).

#### 3. `reports-hub` — use standard `.empty-state`
**File**: `clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.html`
**Changes**: Replace the custom `.hub-empty` markup with the standard `.empty-state` structure (matches `<app-data-table>`'s empty state):

```html
<div class="empty-state">
  <div class="empty-state__icon">
    <app-icon name="folder-search" [size]="48"></app-icon>
  </div>
  <div class="empty-state__title">No reports enabled</div>
  <div class="empty-state__description">
    A tenant admin can enable report families under
    <a routerLink="/tenant/admin/settings" class="link">Settings → Reports</a>.
  </div>
</div>
```

**File**: `clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.scss`
**Changes**: Delete `.hub-empty` block. Token-ize the remaining `#0369a1`, `#b91c1c` in `.hub-counter` and error styles.

Alternative: extract the `.empty-state` pattern from `<app-data-table>` into a global block in `styles.scss` if not already global — audit at implementation time.

### Success Criteria

#### Automated Verification:
- [x] `cd clients/angular && npx ng build --configuration development` succeeds
- [x] `make test-angular` passes (761 of 763 non-skipped specs green; 2 pre-existing failures in `insurance-lines.spec.ts` documented as deviations)
- [x] Grep confirms `.picker-` / `.chip-row` do not appear in `commission/*.scss` or `.html`
- [x] Grep confirms no local `\.btn(-primary|-danger)?\s*\{` in `recoveries-bordereau.component.scss`
- [x] Grep confirms no `\.hub-empty` in `reports-hub.component.scss`
- [ ] `verify` on `/tenant/finance/reports/commission/statement`: producer search + select works; picker matches other `<app-entity-picker>` instances visually
- [ ] `verify` on `/tenant/finance/reports/reinsurance/recoveries-bordereau`: buttons match global `.btn-primary`/`.btn-danger` (visual delta from local shadow)
- [ ] `verify` on `/tenant/finance/reports` with no reports enabled: empty state renders via standard `.empty-state`

#### Manual Verification:
- [ ] Producer picker on commission statement feels identical to `<app-entity-picker kind="scheme">` used elsewhere in the app
- [ ] Recoveries-bordereau modal actions (mark-received, write-off) still fire correctly
- [ ] Reports-hub empty state fits the same visual language as `<app-data-table>`'s empty state

---

## Phase 5: Fraud report + residual sweep

### Overview
`fraud/fraud-report` migrates its custom `.filter-strip` to global `.tx-toolbar` and its inline `.tile` divs to `<app-stat-card>`. Final sweep for any hardcoded hex still in report SCSS after Phases 1–4.

### Changes Required

#### 1. `fraud/fraud-report` — `.filter-strip` → `.tx-toolbar`
**File**: `clients/angular/src/app/pages/tenant/finance/reports/fraud/fraud-report.component.html`
**Changes**: Replace `<div class="filter-strip">` block (~lines 21-35 per research doc) with `<div class="tx-toolbar">` + `<div class="toolbar-cell cell-select">` cells. The internal `<div class="field">` / `<span class="field-label">` shapes become `<div class="toolbar-cell cell-select">` / `<span class="cell-label">`.

Delete the `.filter-strip`, `.field`, `.field-label` blocks from any related SCSS (styles are inline per research doc).

#### 2. `fraud/fraud-report` — inline `.tile` divs → `<app-stat-card>`
**File**: `clients/angular/src/app/pages/tenant/finance/reports/fraud/fraud-report.component.html`
**Changes**: The 6 summary tiles (~lines 49-80) become `<app-stat-card>` instances inside a `.stats-grid`.

```html
<div class="stats-grid">
  <app-stat-card label="Confirmed fraud loss" [value]="tiles.confirmedLoss" icon="alert-triangle" color="red"></app-stat-card>
  <app-stat-card label="AI-flagged claims" [value]="tiles.aiFlagged" icon="chart" color="orange"></app-stat-card>
  <!-- ... 4 more tiles -->
</div>
```

Delete the local `.tiles` and `.tile` blocks from `fraud-report.component.scss` (or the inline styles per research doc — audit at implementation time).

Add a global `.stats-grid` helper to `styles.scss` if not already present (common pattern from `<app-stat-card>` docs — audit at implementation time).

#### 3. Final hex sweep across all report SCSS
**Files**: Every `.scss` under `clients/angular/src/app/pages/tenant/finance/reports/**` and `clients/angular/src/app/pages/tenant-admin/settings/report-schedules/` + `reports/`.

**Changes**: `grep -rn '#[0-9a-fA-F]\{3,6\}' clients/angular/src/app/pages/tenant/finance/reports/` produces the residual list. For each hit, replace with the nearest `--color-*` token. Small file-by-file diffs; no template changes.

Files known to have residuals per research doc §5:
- `reports-hub.component.scss` (`#0369a1`, `#b91c1c`)
- `claims-report.component.scss` (banner colors `#fee2e2`, `#991b1b`, etc.)
- `due-date-banner.component.scss` (banner variants)
- Any others surfaced by grep

### Success Criteria

#### Automated Verification:
- [x] `cd clients/angular && npx ng build --configuration development` succeeds
- [x] `make test-angular` passes (761 of 763 non-skipped specs green; 2 pre-existing failures in `insurance-lines.spec.ts` documented as deviations)
- [x] Grep: no `.filter-strip` in `fraud/*.html` or `.scss`
- [x] Grep: no inline `<div class="tile">` in `fraud/*.html`
- [x] Grep: `#[0-9a-fA-F]{3,6}` count in report SCSS drops to allowlisted residuals only (text-on-tint contrast colors `#c4162d` / `#c47800` / `#0096b4`, `#fff` on colored backgrounds, `var(--x, #fallback)` idiom)
- [ ] `verify` on `/tenant/finance/reports/fraud`: 6 tiles render via `<app-stat-card>`, filter row matches other reports

#### Manual Verification:
- [ ] Fraud report visually reads as part of the app (same tiles, same filter, same fonts)
- [ ] All 12-month trend chart, top-10 provider/member tables, AI calibration section still render correctly
- [ ] No color regressions on any report that used a specific hex for a specific state (e.g. warning-amber, error-red)

---

## Phase 6: Design-system contract

### Overview
Codify the tokens + shared-component contract in a new `.claude/design-system.md`. No code changes. This is the guardrail against future drift.

### Changes Required

#### 1. Add `.claude/design-system.md`
**File**: `.claude/design-system.md` (new)
**Changes**: Structured doc covering:

- **Token catalog** — `--color-*`, `--spacing-*`, `--radius-*`, `--font-*`, `--shadow-*` (extract from `styles.scss` root)
- **Global classes to prefer** — `.card`, `.btn-*`, `.badge-*`, `.tx-toolbar`, `.rate-table`, `.section-header`
- **Shared components to prefer** — `<app-data-table>`, `<app-stat-card>` (with sparkline slot), `<app-select>`, `<app-entity-picker>` (with `kind` list), `<app-skeleton>`, `<app-coming-soon>`, `<app-icon>`, chart components
- **Canonical page structure** — the header + banner + toolbar + table skeleton (matches research doc §1)
- **Anti-patterns** — hardcoded hex, `::ng-deep` piercing shared components, custom modal implementations that shadow globals, custom search dropdowns instead of `<app-entity-picker>`, custom stat cards instead of `<app-stat-card>`
- **Where the load-bearing files live** — `clients/angular/src/styles.scss`, `clients/angular/src/app/shared/components/`
- **How to add a new report** — checklist: use the canonical page-header, the global `.tx-toolbar` with `.toolbar-cell` cells, the global `.rate-table` OR `<app-data-table>` for tabular data, `.badge-*` for statuses, `--color-*` tokens for any color

#### 2. Update `.claude/CLAUDE.md` architecture doc list
**File**: `.claude/CLAUDE.md`
**Changes**: Add a row to the "Architecture Documents" table (around line 68) pointing to the new doc.

```markdown
13. **[Design System](design-system.md)** — Tokens, shared components, global CSS classes, canonical page structure. Read before adding any new Angular page.
```

Also add a work-area row to the pick-list at the top of the `create-plan` / `implement-plan` skill docs (they enumerate `.claude/*.md` docs; the design-system doc should be picked when the work area is "Angular UI").

### Success Criteria

#### Automated Verification:
- [x] `.claude/design-system.md` exists and is well-formed markdown
- [x] `.claude/CLAUDE.md` references it in the architecture table

#### Manual Verification:
- [ ] Read the doc as a new dev would: does it tell you enough to add a report page that doesn't drift?

---

## Testing Strategy

### Unit Tests
- No new unit-level test files. The existing `stat-card.component.spec.ts` (if present) should still pass with the new sparkline slot — content projection doesn't change the class API.
- The `entity-picker.component.spec.ts` gets a new spec covering `kind: 'producer'` (fires `ProducerService.search`, maps rows to `EntityPickerSelection` shape).

### Integration Tests (Playwright, `clients/angular/e2e/`)
- No new e2e specs. The existing `kpi-dashboard.spec.ts` (if present) should still pass — the tile values are the same, only the rendering component changed.
- The existing `commission-statement.spec.ts` (if present) may need updating if it interacts with the custom picker DOM; switch to `<app-entity-picker>` interaction patterns.

### Manual Testing Steps
1. After Phase 1: no visible change — the primitives exist but nothing consumes them yet.
2. After Phase 2: side-by-side screenshots of every status chip (Approved / Rejected / Pending / Committed / Voided / etc.) on a report page vs. on `/tenant/billing/transactions` or `/tenant/claims/pending`. They should match.
3. After Phase 3: KPI dashboard visual regression — primary color should be ocean blue, tiles should read as part of the app.
4. After Phase 4: exercise the producer search in commission-statement, the modal actions in recoveries-bordereau, and the empty state in reports-hub.
5. After Phase 5: fraud report tiles + filter row match other reports visually.
6. After Phase 6: read `.claude/design-system.md` as an outsider and see if it answers "how do I add a report page?"

## Performance Considerations
- No expected performance impact. Global CSS classes evaluate the same as local scoped-encapsulated ones. Content projection has negligible runtime cost.
- The bundle size shrinks slightly (thousands of lines of duplicated SCSS deleted).

## Migration Notes
- No database migrations.
- No Kafka event contract changes.
- No API changes.
- Angular deployment: standard build + deploy; no coordinated backend deploy needed.

## Rollout & Rollback
- Rollout: single PR per phase (6 PRs total), or one bundled PR with 6 commits. Preference is separate PRs so each can be reviewed and shipped independently.
- Rollback: any phase can be reverted with a single `git revert`. Earlier phases don't depend on later phases in a way that breaks reverts (Phase 3+ builds on the sparkline slot from Phase 1, but if Phase 3 is reverted, the sparkline slot in Phase 1 just becomes an unused feature — no runtime break).

## References
- Research: `thoughts/shared/research/2026-09-06-reports-ui-consistency.md`
- Architecture doc (to be added in Phase 6): `.claude/design-system.md`
- Canonical toolbar reference: `clients/angular/src/app/pages/tenant/billing/transactions/transactions-list.component.scss:59-150`
- Global tokens: `clients/angular/src/styles.scss:1-426`
- Shared components inventory: `clients/angular/src/app/shared/components/` (25 dirs)
- Related plan: `thoughts/shared/plans/2026-09-06-merge-facultative-and-commission-corrections.md` (just landed; touches facultative + commission-corrections pages but not their report SCSS)
