# InsureFlow Design System — Angular Frontend

The single source of truth for the Angular web app's visual language. Read this before adding any new
page, and before changing anything in `clients/angular/src/styles.scss` or `clients/angular/src/app/shared/`.

The system exists to prevent drift: every page shipped from the reports family in Phases 1–19 initially
reinvented the same toolbar, the same status pill, the same stat card, five times over. This doc is
what "consistent" now means, in writing.

## Tokens (do not hardcode)

Every color, spacing, radius, and font weight comes from a CSS custom property defined in
`clients/angular/src/styles.scss:5-118`. If a token doesn't exist for what you need, add it there
first, then use it. **Never inline a hex value in a component SCSS.**

### Colors (Ocean Breeze palette)

| Token | Value | Use for |
|---|---|---|
| `--color-primary` | `#0077B6` | Primary actions, active states, links |
| `--color-primary-hover` | `#005f8f` | Hover on primary |
| `--color-primary-light` | rgba(primary, 0.1) | Selected chips, subtle backgrounds |
| `--color-secondary` | `#00B4D8` | Secondary accents |
| `--color-accent` | `#90E0EF` | Highlights |
| `--color-bg` | `#F8FDFF` | Page background |
| `--color-surface` | `#FFFFFF` | Card / panel surface |
| `--color-text-primary` | `#1B2A4A` | Headings, body copy |
| `--color-text-secondary` | `#5A6B8A` | Labels, secondary copy |
| `--color-text-muted` | `#8E9BB8` | Placeholders, meta text |
| `--color-border` | `#E2E8F0` | Standard borders |
| `--color-border-light` | `#F0F4F8` | Subtle dividers, table lines |
| `--color-success` / `-light` | `#2EC4B6` / rgba(0.1) | Approved / paid / completed |
| `--color-warning` / `-light` | `#FF9F1C` / rgba(0.1) | Pending / draft / in-progress |
| `--color-error` / `-light` | `#E71D36` / rgba(0.1) | Rejected / failed / overdue |
| `--color-info` / `-light` | `#00B4D8` / rgba(0.1) | Info / enrolled / raised |

**Text-on-tint colors** for AA contrast on `-light` backgrounds:
`#1a9a8e` (success text), `#c47800` (warning text), `#c4162d` (error text), `#0096b4` (info text).
These four are not tokenized (no matching `--color-*` exists); they're consistently used in the
global `.badge-*` block at `styles.scss:242-315` and may be inlined in component SCSS as an
explicit allowlist. Never invent new tinted-text colors — pick from this set.

### Spacing

`--spacing-xs` 4px · `--spacing-sm` 8px · `--spacing-md` 12px · `--spacing-base` 16px ·
`--spacing-lg` 20px · `--spacing-xl` 24px · `--spacing-2xl` 32px · `--spacing-3xl` 40px ·
`--spacing-4xl` 48px.

### Radius

`--radius-sm` 6px · `--radius-md` 8px · `--radius-lg` 12px · `--radius-xl` 16px · `--radius-full` 9999px.

### Typography

`--font-family` Inter. Size scale from `--font-size-xs` (0.75rem) to `--font-size-4xl` (2.25rem).
Weights: light 300, regular 400, medium 500, semibold 600, bold 700. Line-height tokens: tight 1.25,
normal 1.5, relaxed 1.75.

### Shadows

`--shadow-xs` (subtle), `-sm`, `-md`, `-lg`, `-xl` (deepest). All tinted with `rgba(3, 4, 94, ...)`
to keep the drop shadow on-brand.

### Transitions

`--transition-fast` 150ms, `--transition-base` 200ms, `--transition-slow` 300ms — all `ease`.

## Global classes to prefer

Defined once in `clients/angular/src/styles.scss`. Never redefine locally — the whole point is one
authoritative styling.

### Buttons (`styles.scss:283-397`)

`.btn` is the base. Variants: `.btn-primary`, `.btn-secondary`, `.btn-outline`, `.btn-default`,
`.btn-warn`, `.btn-danger`. Size modifier: `.btn-sm`. Icon-only: `.btn-icon`. Disabled treatment is
automatic via `:disabled` / `[disabled]`.

```html
<button class="btn btn-primary" (click)="save()">Save</button>
<button class="btn btn-default" (click)="cancel()">Cancel</button>
<button class="btn btn-sm btn-danger" (click)="delete()">Delete</button>
```

### Badges (`styles.scss:229-315`)

`.badge` is the base pill. Statuses map to color families:

- Green (success): `-active`, `-approved`, `-adjudicated`, `-paid`, `-completed`, `-filed`,
  `-closed`, `-committed`, `-received`, `-ceded`
- Amber (warning): `-pending`, `-submitted`, `-draft`, `-in_adjudication`, `-in-adjudication`,
  `-pending_verification`, `-processing`, `-invited`, `-expected`, `-invoiced`
- Red (error): `-rejected`, `-suspended`, `-overdue`, `-failed`, `-over_age`, `-terminated`,
  `-voided`, `-written_off`, `-written-off`
- Blue (info): `-enrolled`, `-verified`, `-info`, `-raised`, `-reviewed`, `-under_review`,
  `-under-review`

```html
<span class="badge badge-approved">Approved</span>
<span class="badge" [class]="'badge badge-' + row.status.toLowerCase()">{{ row.status }}</span>
```

Adding a new status? Add it to the existing color family — don't invent a new class.

### Card surface (`styles.scss:216-226`)

`.card` — background, radius, shadow, hover elevation. Use around form cards, filter cards, tile
cards, table cards.

### Toolbar (`styles.scss:316-411`)

`.tx-toolbar` is the canonical filter/action strip: flat, flush, divider-separated cells. Every
page's filter row uses this — do not build a new one.

```html
<div class="tx-toolbar">
  <div class="toolbar-cell cell-search">
    <app-icon name="search" [size]="16"></app-icon>
    <input type="search" [(ngModel)]="query" placeholder="Search…" />
  </div>
  <div class="toolbar-cell cell-select">
    <span class="cell-label">Status</span>
    <app-select [(ngModel)]="status" [options]="statusOptions" size="sm"></app-select>
  </div>
  <div class="toolbar-cell cell-date">
    <span class="cell-label">From</span>
    <input type="date" [(ngModel)]="from" />
  </div>
</div>
```

Cell modifiers built in: `.cell-search`, `.cell-select`, `.cell-date`, `.cell-clear`. Report-specific
modifiers (e.g. `.cell-currency` at 200px min-width, `.cell-age` at 110px) go in the component's
own SCSS as extensions that only tune width — never restyle the base look.

### Rate table (`styles.scss:413-448`)

`.rate-table` — a summary / matrix / monthly-bucket table that visually matches `<app-data-table>`.
Use it for hand-rolled tabular data. For paginated / sortable data, reach for `<app-data-table>`
instead.

```html
<table class="rate-table">
  <thead>
    <tr><th>Currency</th><th class="num">Amount</th><th class="actions">Actions</th></tr>
  </thead>
  <tbody>
    @for (row of rows; track row.id) {
      <tr>
        <td>{{ row.currency }}</td>
        <td class="num">{{ row.amount | number:'1.2-2' }}</td>
        <td class="actions"><button class="btn btn-sm btn-default">Edit</button></td>
      </tr>
    }
  </tbody>
</table>
```

Column modifiers: `.num` (right-aligned, tabular-nums), `.actions` (right-aligned, no-wrap).

### Tab bar

`.scheme-tabs` (container) + `.scheme-tab` (button) — the underlined-tab strip that filters a list. Sits directly below a fullbleed page-header and above the data-table. Active tab draws a 2px underline in `--color-primary`. Also aliased as `.tab-bar` / `.tab-bar__tab` for new pages that want a semantic name.

```html
<nav class="scheme-tabs" role="tablist">
  @for (tab of tabs; track tab.value) {
    <button
      type="button"
      role="tab"
      class="scheme-tab"
      [class.is-active]="activeTab === tab.value"
      [attr.aria-selected]="activeTab === tab.value"
      (click)="selectTab(tab.value)">
      {{ tab.label }}
    </button>
  }
</nav>
```

Canonical example: `/tenant/claims` (`clients/angular/src/app/pages/claims/claims.component.html`).

### Stats grid (`styles.scss:451-455`)

`.stats-grid` — a responsive grid for a row of `<app-stat-card>` components. Auto-fits at 200px.

```html
<div class="stats-grid">
  <app-stat-card label="Cases opened" [value]="12" icon="alert-triangle" color="orange"></app-stat-card>
  <app-stat-card label="Confirmed"    [value]="4"  icon="alert-triangle" color="red"></app-stat-card>
</div>
```

### Empty state (`styles.scss:457-495`)

`.empty-state` — matches the shape inside `<app-data-table>`. For host pages that need an empty state
outside a table.

```html
<div class="empty-state">
  <div class="empty-state__icon"><app-icon name="folder-search" [size]="32"></app-icon></div>
  <div class="empty-state__title">Nothing enabled yet</div>
  <div class="empty-state__description">Ask an admin to enable at least one report.</div>
</div>
```

### Section icon + header

`.section-icon` at `styles.scss:124-138` and `.section-header` at `styles.scss:141-148`. Use for
themed section headings. Icon color / background are CSS-var driven so a tenant rebrand cascades.

## Shared components to prefer

Live under `clients/angular/src/app/shared/components/`. Prefer these over rolling your own.

| Component | Use for | Path |
|---|---|---|
| `<app-data-table>` | Sortable / paginated / searchable tabular data | `data-table/` |
| `<app-stat-card>` | Dashboard tile with label + value + trend + sparkline slot | `stat-card/` |
| `<app-select>` | Single-select dropdown (searchable, sizes) | `select/` |
| `<app-entity-picker>` | Debounced search-select for `provider` / `member` / `group` / `scheme` / `beneficiary` / `producer` | `entity-picker/` |
| `<app-icon>` | Inline SVG icon by name | `icon/` |
| `<app-skeleton>` | Loading placeholder | `skeleton/` |
| `<app-coming-soon>` | Route stub for unbuilt features | `coming-soon/` |
| `<app-line-chart>` / `<app-sparkline>` / `<app-bar-chart>` | ngx-charts wrappers | `charts/` |
| `<app-toast>` | Application-wide notifications | `toast/` |

### `<app-stat-card>` sparkline slot

Since Phase 1 of the reports-ui consistency sweep, `<app-stat-card>` accepts projected sparkline
content:

```html
<app-stat-card label="Loss ratio" [value]="displayValue" [trend]="+2">
  <app-sparkline stat-sparkline [data]="series" [height]="40"></app-sparkline>
</app-stat-card>
```

The slot is `<ng-content select="[stat-sparkline]">` — any content with the `stat-sparkline`
attribute lands in the reserved space below the value.

### `<app-entity-picker>` — the only search-select

`feedback_no_raw_id_inputs`: never render a bare `<input>` for an entity ID (memberId, schemeId,
providerId, groupId, producerId). The `EntityKind` union is:

```typescript
export type EntityKind = 'provider' | 'member' | 'group' | 'scheme' | 'beneficiary' | 'producer';
```

Adding a new kind: extend the union in `entity-picker.component.ts:20`, inject the service, add a
case to the debounced `search()` switch. Do NOT roll a new picker.

## Canonical page structure

The shape every report / list page adopts:

```html
<!-- 1. Page header ------------------------------------------------ -->
<header class="page-header">
  <div>
    <h1>{{ pageTitle }}</h1>
    <p class="page-sub">{{ pageDescription }}</p>
  </div>
  <div class="action-row">
    <button class="btn btn-primary" (click)="doPrimary()">Primary action</button>
  </div>
</header>

<!-- 2. Optional banner -------------------------------------------- -->
@if (errorMessage) {
  <div class="banner banner-error" (click)="errorMessage = null">{{ errorMessage }}</div>
}

<!-- 3. Filter toolbar --------------------------------------------- -->
<div class="tx-toolbar">
  <div class="toolbar-cell cell-search">…</div>
  <div class="toolbar-cell cell-select">…</div>
  <div class="toolbar-cell cell-date">…</div>
</div>

<!-- 4. KPI strip (optional) --------------------------------------- -->
@if (envelope) {
  <section class="stats-grid">
    <app-stat-card label="…" [value]="…"></app-stat-card>
  </section>
}

<!-- 5. Body: either a shared table or a hand-rolled rate-table ----- -->
<app-data-table [data]="rows" [columns]="columns" [loading]="loading" />
```

## Anti-patterns

These are the divergences the Phase 1–5 sweep removed. **Do not reintroduce them.**

1. **Hardcoded hex.** Inline `#6366f1` (wrong primary — this app is `#0077B6`), `#fee2e2`,
   `#dcfce7`, etc. Use tokens.
2. **Local `.tx-toolbar` / `.rate-table` / `.status-pill` / `.status-chip`.** These are global.
   Extending is fine (`.toolbar-cell.cell-currency { min-width: 200px }`), redefining is not.
3. **`::ng-deep` piercing shared components** to force the flat look. The global class already
   flattens the select trigger — extend the toolbar cell, don't reach into the child.
4. **Custom modal implementations that shadow global buttons.** Modals are per-modal components
   (no shared `<app-modal>` yet), but the buttons inside them must still be `.btn.btn-primary` etc.
5. **Custom search dropdowns.** Use `<app-entity-picker>`. If you need a new kind, extend the
   `EntityKind` union.
6. **Custom stat cards.** Use `<app-stat-card>` — with the sparkline slot when a trend chart is
   needed. If a page needs multiple sparkline positions or a footer with actions, wrap
   `<app-stat-card>` in a sibling layout rather than reinventing the surface.
7. **Multiple bespoke empty-states.** Use `.empty-state` (host pages) or `<app-data-table>`'s
   built-in empty state (rows inside a table).
8. **Class shadow through `styleUrls`.** If two components share `styleUrls: ['./foo.scss']`,
   editing that SCSS propagates to both. Prefer promoting the shared bits to `styles.scss` and
   keep per-component SCSS thin.

## Where the load-bearing files live

- **Global tokens + classes:** `clients/angular/src/styles.scss`
- **Shared components:** `clients/angular/src/app/shared/components/*`
- **This doc:** `.claude/design-system.md`
- **Prior research on drift:** `thoughts/shared/research/2026-09-06-reports-ui-consistency.md`
- **Sweep plan (implemented 2026-09-06):** `thoughts/shared/plans/2026-09-06-reports-ui-consistency-sweep.md`

## Adding a new report page — checklist

1. Use the canonical `<header class="page-header">` shape.
2. Use `.tx-toolbar` with `.toolbar-cell` cells for filters. Never build a new toolbar.
3. Use `.badge-{status}` for status chips. If a status is missing, add it to `styles.scss:229-315`.
4. Use `<app-stat-card>` inside `.stats-grid` for KPI tiles. If you need a sparkline, use the slot.
5. Use `<app-data-table>` for sortable / paginated tabular data; `.rate-table` for summary tables.
6. Use `<app-entity-picker>` for any entity-ID input. Never a raw `<input type="text">` for IDs.
7. All colors from `--color-*` tokens. If a token doesn't exist, add it before using it.
8. All spacing from `--spacing-*`. All radii from `--radius-*`. All shadows from `--shadow-*`.
9. Component SCSS should be under ~50 lines. If it grows larger, something is being reinvented.
