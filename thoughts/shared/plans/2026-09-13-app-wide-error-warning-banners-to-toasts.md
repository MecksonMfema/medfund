---
date: 2026-09-13
git_commit: 456726d3369a414b2e9c8a7f4b910fbb638713f5
branch: rename-adjustments-to-notes
research:
  - thoughts/shared/research/2026-09-13-app-wide-error-warning-banners-to-toasts.md
steer: "phase this — start with the finance report pages, land the extractErrorMessage helper, roll out per feature area, keep form-validation banners inline"
services_touched: [angular]
status: implemented
---

# App-Wide Error/Warning Banners → Toasts

## Overview

Every page in the Angular web app currently renders backend request failures as a
full-width red `.banner.banner-error` between the toolbar and the content, and report
pages additionally render `envelope?.warnings` as a stacked yellow `.banner.banner-warning`
block. Both consume prime real estate, don't auto-dismiss, and push actual data down the
page (see screenshots `2026-09-13 09-24-53.png` and `2026-09-13 09-25-00.png`).

The toast subsystem (`ToastService` + `<app-toast-container>`, mounted in both layouts)
already exists and is used by ~49 newer components. This plan flips the default for the
remaining ~117 files: request-failure feedback and envelope warnings become transient
severity-tagged toasts; only a small whitelisted set of persistent form-state banners
stays inline. The design-system doc is flipped in the same change so new pages don't
reintroduce the old idiom.

## Current State Analysis

- **Inline banner grammar** — `.banner.banner-error` + `.banner.banner-warning` classes
  live in the shared shell SCSS `clients/angular/src/app/pages/tenant/finance/reports/receipts/receipts-report.component.scss:67-76`
  and (per design-system.md:229-244) also in the global `styles.scss` form-grammar block.
  Template idiom repeats across 224 sites: `179 banner-error` + `45 banner-warning` hits
  across 117 files (see the research doc for the per-area breakdown).

- **HTTP error extraction is copy-pasted** — every fetch's `error:` callback does the
  same `err?.error?.detail || err?.error?.title || '<verb-specific fallback>'` dance.
  `treaty-edit.component.ts` alone has 12 handlers with this shape.

- **Toast infra is ready** — `ToastService` at `clients/angular/src/app/shared/components/toast/toast.service.ts:19-65`
  exposes `success/error/info/warning(msg, durationMs?)` with defaults 4s / 6s / 4s / 5s.
  Container mounted at `clients/angular/src/app/layout/tenant-layout/tenant-layout.component.html:2`
  and `clients/angular/src/app/layout/layout.component.html:2`.

- **Design-system doc points both ways** — `design-system.md:226` recommends `<app-toast>`
  as the canonical notification, but the canonical page structure at `design-system.md:270-273`
  still teaches new pages the banner idiom. Both places need aligned edits.

- **Playwright specs assert the banner** — `clients/angular/e2e/tests/cash-flow-forecast.spec.ts:12,87,105,137`
  and `clients/angular/e2e/tests/loss-ratio-report.spec.ts:11,77,99,128` explicitly
  wait on "the warnings banner" and "the partial-data banner". They will go red when the
  banner disappears; they must be updated in Phase 1 as part of the same phase.

- **`errorMessage` is dual-use in some pages** — e.g. `cash-flow-forecast.component.ts:108`
  sets it from a client-side filter guard ("Choose an as-of date."); lines 119 and 135
  set it from HTTP failures. The migration rule (see Implementation Approach) handles
  both.

## Desired End State

- **HTTP request failures** — surfaced as `toast.error(...)` at top-right, auto-dismiss
  after 6s; the red `.banner-error` no longer renders above the page content.
- **Envelope `warnings[]` on report pages** — surfaced as a single aggregated
  `toast.warning('Partial data — N series unavailable\n<line1>\n<line2>\n...')`, rendered
  multi-line via `white-space: pre-line`.
- **Filter-guard messages** (e.g. "Choose an as-of date.") — surfaced as
  `toast.warning(...)` (transient, same UX as HTTP failures).
- **Persistent form-state banners** — whitelisted set stays inline as `.banner.banner-error`
  / `.banner.banner-warning` (see the whitelist in "What We're NOT Doing" below). The
  `.banner*` classes remain defined in the global `styles.scss` for these callers.
- **Shared shell SCSS** — `.banner*` block deleted from `receipts-report.component.scss`
  once the four reports that use its `styleUrl` have migrated (Phase 1 tail).
- **`extractErrorMessage(err, fallback)` and `composeWarningsToast(warnings, subject)`**
  live in `clients/angular/src/app/core/util/http-errors.ts` and are used by every
  migrated component.
- **`design-system.md`** — the canonical page-structure example uses a toast for
  request feedback and marks the inline banner as the *exception* for persistent
  form-state.
- **Playwright specs** — updated to assert on the toast (via `[role="status"]` +
  message match) instead of `.banner-warning` DOM.
- **Repo-wide grep** — `grep -rn "banner-error\|banner-warning" clients/angular/src/app/**/*.html`
  returns only the whitelisted persistent-form files.

### Key Discoveries

- **Toast infra requires no code**: `ToastService.success/error/warning/info(msg, durationMs?)`
  covers every migration case (`toast.service.ts:32-35`).
- **Multi-line toast messages** — `toast-container.component.scss:28-31` sets
  `.toast-message { flex: 1; line-height: 1.4; }` but not `white-space`. Default `normal`
  collapses `\n` to spaces. The Phase-1 SCSS edit adds `white-space: pre-line;` to that
  block so aggregated warning toasts render with per-series lines.
- **Layout mount points already in place** — no bootstrapping needed
  (`layout.component.html:2`, `tenant-layout.component.html:2`).
- **Icon reuse** — `toast-container.component.ts:21-28` already maps
  `error` and `warning` to `alert-triangle`; no icon work needed.
- **The `errorMessage` field is often the only reader on a page** — after migration,
  the field, its template `@if (errorMessage) { <div class="banner banner-error">... }`
  block, and any imports it caused (rare — mostly none) can be deleted.
- **Do NOT drop `.banner*` styles globally** — persistent form-state banners still
  need them. Only the copy inside `receipts-report.component.scss` shell is redundant
  once its four consumers migrate.

## What We're NOT Doing

- **Not touching Java, Go, Python, Elixir, Flutter.** Backend contracts unchanged; only
  the Angular presentation layer moves.
- **Not migrating persistent form-state banners.** These stay inline:
  - `pages/tenant/billing/generate-billing/generate-billing-wizard.component.html`
    — the 3 cooldown/retrospective warning banners are procedural guardrails visible
    while the user fills the wizard, not request feedback.
  - `pages/tenant/members/member-form.component.html` — the schema-change warning banner.
  - Any other `.banner-warning` that renders inline copy the user must see *while
    interacting with a form* (Phase 1 will enumerate the final whitelist during the
    triage sweep; the list stays in this plan under Phase 6).
- **Not building a "Warnings (N)" toolbar chip** — decided in review: aggregated toast
  only, no persistent chip. Follow-up ticket if operators complain about losing detail.
- **Not fanning out** — one toast per envelope-warnings block, not one per line.
- **Not adding a global HttpInterceptor** to auto-toast every failed request — some
  requests are intentionally allowed to fail quietly (see e.g.
  `cash-flow-forecast.component.ts:92` "non-fatal" currency listing). Migration is
  per-call-site so the intent is preserved.
- **Not tuning toast default TTLs** — the 6s/5s defaults are kept. Per-call overrides
  allowed where the fallback string is uninformative (see Migration Notes).
- **Not touching the `NotificationsService`** — that serves the bell icon and unread
  count. Unrelated.

## Implementation Approach

**Migration rule per call site** — apply mechanically, one file at a time:

1. **HTTP-error assignment** (`this.errorMessage = err?.error?.detail || err?.error?.title || 'X';`)
   → `this.toast.error(extractErrorMessage(err, 'X'));`
2. **Client-side filter guard** (`this.errorMessage = 'Choose a date.';`)
   → `this.toast.warning('Choose a date.');`
3. **Envelope-warnings render** (`@if (envelope?.warnings?.length) { <div class="banner banner-warning">... }`)
   → move to a `private surfaceWarnings(envelope)` method that fires
   `toast.warning(composeWarningsToast(envelope.warnings, 'Loss ratio'), 8000)` — 8s
   because the message is longer to read. Call it once in the `next:` handler.
4. **Delete the `errorMessage: string | null` field** if no template reader remains.
5. **Delete the `<div class="banner banner-error">` template block.**
6. **Delete the `<div class="banner banner-warning">` template block** (if present).
7. **Inject `ToastService`** via constructor (or `inject()` — match existing DI style
   on the page).

**Order** — the steer says report pages first, then per feature area, keeping wizard
warnings inline. Each phase is one feature area, one PR-sized change, verified in
isolation.

**Phase boundaries** — a phase is verifiable when its feature area's pages compile,
unit tests pass, and `verify` on one representative page confirms toasts render and no
banner appears.

---

## Phase 1: Foundation + finance reports

### Overview
Land the two shared helpers, update the SCSS for multi-line toast rendering, flip the
design-system doc, migrate the ~50 finance report pages, delete the redundant local
`.banner*` block from the shared shell, and update the two Playwright specs that
currently assert on the banner DOM.

### Changes Required

#### 1. New helper file — `extractErrorMessage` + `composeWarningsToast`

**File**: `clients/angular/src/app/core/util/http-errors.ts` (new)

```typescript
import { HttpErrorResponse } from '@angular/common/http';

/**
 * Extracts a human-readable error message from a backend response.
 * Prefers RFC 7807 problem-details fields; falls back to a caller-supplied string.
 * Mirror of the copy-pasted expression used across ~180 sites before this helper.
 */
export function extractErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object') {
    const body = (err as HttpErrorResponse).error;
    if (body && typeof body === 'object') {
      const detail = (body as { detail?: string }).detail;
      const title  = (body as { title?: string  }).title;
      if (typeof detail === 'string' && detail.trim()) return detail;
      if (typeof title  === 'string' && title.trim())  return title;
    }
  }
  return fallback;
}

/**
 * Composes an aggregated warning toast message from a report envelope's
 * warnings[] array. Renders as a header line + one line per warning. The
 * `<app-toast-container>` message CSS uses `white-space: pre-line`, so `\n`
 * separators render as line breaks.
 */
export function composeWarningsToast(warnings: readonly string[], subject: string): string {
  const count = warnings.length;
  if (count === 0) return '';
  const header = `${subject}: partial data — ${count} ${count === 1 ? 'series' : 'series'} unavailable`;
  return [header, ...warnings].join('\n');
}
```

#### 2. Multi-line message rendering

**File**: `clients/angular/src/app/shared/components/toast/toast-container.component.scss`
**Changes**: Add `white-space: pre-line;` to the `.toast-message` block so aggregated
warning toasts render with per-series lines.

```scss
.toast-message {
  flex: 1;
  line-height: 1.4;
  white-space: pre-line;   /* aggregated warning toasts use \n separators */
  word-break: break-word;
}
```

#### 3. Flip the design-system canonical page structure

**File**: `.claude/design-system.md`
**Changes**: replace the "Optional banner" block at lines 270-273 with an "Optional
toast" note, and add a "When to use a toast vs an inline banner" subsection above the
"Anti-patterns" section.

```markdown
<!-- 2. Optional inline banner (only for persistent form-state) --------- -->
<!-- Request failures and envelope warnings go to a toast — inject the -->
<!-- ToastService, call toast.error(extractErrorMessage(err, 'Failed to …')). -->
<!-- The inline banner is reserved for messages that must remain visible -->
<!-- while the user interacts with a form (e.g. wizard guardrails). -->
@if (formGuardMessage) {
  <div class="banner banner-warning">{{ formGuardMessage }}</div>
}
```

Add subsection immediately after Canonical page structure (before Anti-patterns):

```markdown
### When to use a toast vs an inline banner

**Toast (default) — `ToastService.error/warning/success/info`**:
- Every HTTP failure (`err` in a `subscribe({ error: ... })`).
- Every envelope-warning list (`envelope.warnings[]`). Aggregate with
  `composeWarningsToast(warnings, 'Subject')` — one toast, not one per line.
- Every client-side filter guard ("Choose a date.").
- Every action-outcome confirmation ("Report exported.").

**Inline `.banner-warning` / `.banner-error` (exception)**:
- Persistent form-state that must stay visible while the user fills the form
  (wizard cooldown, member-form schema-change, etc.).
- Rule of thumb: if the message disappearing after 6s would confuse the user, use a
  banner; otherwise use a toast.

Never both for the same message.
```

Also update `design-system.md:226` recommendation row to read:
```markdown
| `<app-toast>` | Application-wide notifications — **default for request failures and envelope warnings**. See "When to use a toast vs an inline banner" below. | `toast/` |
```

#### 4. Migrate every finance report page

**Files** (~50):
- `pages/tenant/finance/reports/cash-flow-forecast/cash-flow-forecast.component.{ts,html}`
- `pages/tenant/finance/reports/collection-rate-trend/collection-rate-trend.component.{ts,html}`
- `pages/tenant/finance/reports/loss-ratio/loss-ratio-report.component.{ts,html}`
- `pages/tenant/finance/reports/member-payments/member-payments-report.component.{ts,html}`
- `pages/tenant/finance/reports/receipts/receipts-report.component.{ts,html}`
- Every other page under `pages/tenant/finance/reports/**` that has `banner-error` or
  `banner-warning`. Use `grep -l "banner-error\|banner-warning" pages/tenant/finance/reports -r`
  to list them at start of phase.

**Change pattern** (canonical example, taken from `loss-ratio-report.component.ts:85-103`):

```typescript
// Add to imports at top:
import { ToastService } from '../../../../../shared/components/toast/toast.service';
import { extractErrorMessage, composeWarningsToast } from '../../../../../core/util/http-errors';

// Add to constructor:
constructor(
  private finance: FinanceService,
  private currencyService: CurrencyService,
  private tenantService: TenantService,
  private toast: ToastService,
) {}

// Replace the fetch's error handler:
fetch(): void {
  if (!this.periodStart || !this.periodEnd) {
    this.toast.warning('Choose a start and end date.');
    return;
  }
  this.loading = true;
  this.finance.getLossRatio(this.buildParams()).subscribe({
    next: env => {
      this.envelope = env;
      this.loading = false;
      this.surfaceWarnings(env);
    },
    error: err => {
      this.toast.error(extractErrorMessage(err, 'Failed to load loss ratio'));
      this.envelope = null;
      this.loading = false;
    },
  });
}

private surfaceWarnings(env: ReportResponse<LossRatioReportResponse>): void {
  const warnings = env?.warnings ?? [];
  if (warnings.length === 0) return;
  this.toast.warning(composeWarningsToast(warnings, 'Loss ratio'), 8000);
}
```

**Delete** in each report page:
- The `errorMessage: string | null` field (no reader remains).
- The `<div class="banner banner-error" (click)="errorMessage = null">` template block.
- The `@if (envelope?.warnings?.length) { <div class="banner banner-warning">... }` block.

#### 5. Delete redundant local `.banner*` block from shared shell

**File**: `clients/angular/src/app/pages/tenant/finance/reports/receipts/receipts-report.component.scss`
**Precondition**: verify `.banner`, `.banner-error`, `.banner-warning` are defined in
the global `clients/angular/src/styles.scss` (they are — see design-system.md:229-244).
If missing, promote them to `styles.scss` first, then delete from this file.

**Changes**: delete lines 67-76 (the `.banner { ... .banner-error { ... } .banner-warning { ... } }`
block). No consumer needs them — the four reports that use this file via `styleUrl` have
migrated to toasts.

#### 6. Update Playwright specs

**File**: `clients/angular/e2e/tests/cash-flow-forecast.spec.ts`
**Changes**: at lines 12, 87, 105, 137 replace assertions on `.banner-warning` /
`.banner-error` with assertions on the toast:

```typescript
// Old (roughly):
await expect(page.locator('.banner-warning')).toContainText('finance-service unavailable');

// New:
await expect(
  page.locator('[role="status"]', { hasText: 'partial data' })
).toBeVisible();
```

The test docstring at line 12 also changes: "Shows the warnings banner …" → "Fires an
aggregated warning toast when the envelope carries warnings".

**File**: `clients/angular/e2e/tests/loss-ratio-report.spec.ts` — same edit at lines 11, 77, 99, 128.

**File**: `clients/angular/e2e/tests/claims-reports-toggle.spec.ts:106` — comment
reference to "error banner" updated to "error toast"; if there's an actual assertion,
mirror the cash-flow-forecast change.

### Success Criteria

#### Automated Verification:
- [ ] Angular type-checks + unit tests pass: `make test-angular`
- [ ] Playwright suite is green: `make test-e2e` (specifically `cash-flow-forecast.spec.ts` + `loss-ratio-report.spec.ts` + `claims-reports-toggle.spec.ts`)
- [ ] `verify` on `/tenant/finance/reports/cash-flow-forecast`: no `.banner-error` in the DOM after stopping the finance backend and clicking the "As of" date input; a red error toast appears top-right; the toast auto-dismisses after 6s.
- [ ] `verify` on `/tenant/finance/reports/collection-rate-trend`: with the finance backend down for the `receipts-aggregate-monthly` KPI series, a yellow warning toast appears containing the header "partial data (6 series unavailable)" and each series name on its own line; no `.banner-warning` in the DOM.
- [x] `grep -rn "banner-error\|banner-warning" clients/angular/src/app/pages/tenant/finance/reports` returns zero hits.

#### Manual Verification:
- [ ] Hard-refresh screenshot subjects (cash-flow-forecast, collection-rate-trend, loss-ratio, member-payments): the toolbar+content is flush against the header, no banner strip between them.
- [ ] Aggregated warning toast is readable, line-count-appropriate, doesn't overflow the 380px stack (`toast-container.component.scss:9`).

**Implementation Note**: after automated verification passes, pause for human confirmation of the manual visual checks before starting Phase 2.

---

## Phase 2: Tenant-admin

### Overview
Migrate the tenant-admin surface (~27 files). `treaty-edit.component.ts` is the biggest
single win — 12 handlers collapse via the helper.

### Changes Required

#### 1. Migrate every tenant-admin page with `banner-error` or `banner-warning`

**Files** (~27) — starting list from research doc:
- `pages/tenant-admin/reinsurance/treaty-edit.component.{ts,html}` (12 handlers)
- `pages/tenant-admin/reinsurance/reinsurers-list.component.{ts,html}`
- `pages/tenant-admin/producers/producer-form.component.{ts,html}`
- `pages/tenant-admin/producers/producers-list.component.{ts,html}`
- `pages/tenant-admin/rate-cards/**`
- `pages/tenant-admin/backfill-review/**`
- `pages/tenant-admin/portfolios/**`
- `pages/tenant-admin/funds/**`
- `pages/tenant-admin/cohorts/**`
- Every settings tab (bank-accounts, currencies, billing, actuarial-bases, IFRS17, proration, endorsement, roles, reports, auto-lapse, sidebar-sections) — files are one component per tab.

Enumerate at phase start with `grep -l "banner-error\|banner-warning" clients/angular/src/app/pages/tenant-admin -r`.

Apply the migration rule from Implementation Approach to every hit.

### Success Criteria

#### Automated Verification:
- [ ] `make test-angular`
- [ ] `verify` on `/tenant-admin/reinsurance/treaties/:id/edit` — save a treaty with an invalid payload; a red error toast appears with the backend `detail`; no banner.
- [ ] `verify` on `/tenant-admin/producers/:id/edit` — save with an invalid payload; error toast appears.
- [ ] `grep -rn "banner-error\|banner-warning" clients/angular/src/app/pages/tenant-admin` returns zero hits.

#### Manual Verification:
- [ ] Random sample of 3 settings tabs shows a toast on backend failure and clean UI when there's no error.

---

## Phase 3: Tenant billing

### Overview
Migrate the tenant billing pages (~24 files), **skipping** the persistent wizard warnings
which stay inline.

### Changes Required

#### 1. Migrate tenant billing pages

**Files** (~24) — under `clients/angular/src/app/pages/tenant/billing/**`:
groups, transactions, benefits, waiting-periods, invoices, schemes, contributions,
emails, age-groups, and the top-level list pages.

Enumerate at phase start with `grep -l "banner-error\|banner-warning" clients/angular/src/app/pages/tenant/billing -r`.

Apply the migration rule.

#### 2. Preserve wizard cooldown warnings (whitelist)

**File**: `pages/tenant/billing/generate-billing/generate-billing-wizard.component.html`

The 3 `banner-warning` blocks in this file are **kept inline** — they are procedural
guardrails visible while the user is filling the wizard, not request-failure feedback.
Leave them untouched. Add a code comment above the first one:

```html
<!-- Persistent form-state banner — kept inline per design-system.md
     "When to use a toast vs an inline banner". Do not migrate. -->
@if (isRetrospective) {
  <div class="banner banner-warning">...</div>
}
```

### Success Criteria

#### Automated Verification:
- [ ] `make test-angular`
- [ ] `verify` on `/tenant/billing/groups` — trigger a delete failure; error toast appears; no banner.
- [ ] `verify` on `/tenant/billing/generate-billing` — the wizard cooldown warning still renders **inline** (this is intended).
- [ ] `grep -rn "banner-error\|banner-warning" clients/angular/src/app/pages/tenant/billing` returns only the whitelisted `generate-billing-wizard.component.html` hits (3).

---

## Phase 4: Tenant claims

### Overview
Migrate the tenant claims surface (~21 files).

### Changes Required

**Files** (~21) — under `clients/angular/src/app/pages/tenant/claims/**`:
preauth, tariffs, drugs, SIU, rejection-reasons, modifiers, eligibility-quote, pending, CTC.

Enumerate at phase start with `grep -l "banner-error\|banner-warning" clients/angular/src/app/pages/tenant/claims -r`.

Apply the migration rule.

### Success Criteria

#### Automated Verification:
- [ ] `make test-angular`
- [ ] `verify` on `/tenant/claims/preauth` — trigger a load failure; error toast appears; no banner.
- [ ] `grep -rn "banner-error\|banner-warning" clients/angular/src/app/pages/tenant/claims` returns zero hits.

---

## Phase 5: Non-report finance + policies + platform + misc

### Overview
Migrate the remaining call sites: non-report finance pages, policies, member detail/form,
platform jobs monitor, legacy `members.component.html`, and any straggler hits.

### Changes Required

**Files** (~24):
- `pages/tenant/finance/**` (excluding `finance/reports/**` already covered in Phase 1):
  payments, runs, advance, creditors, notes, reinsurance, underwriting, payouts,
  advice/reconciliation.
- `pages/tenant/policies/**` — endorsements.
- `pages/tenant/members/member-detail.component.{ts,html}`.
- `pages/tenant/members/member-form.component.{ts,html}` — **whitelist** the schema-change
  warning (persistent, form-scoped).
- `pages/platform/jobs-monitor/**`.
- Legacy `members.component.html` (find via grep — full path in the research doc's misc bucket).
- Any tenant-picker, shared/components, or straggler hit.

Enumerate at phase start with:
```
grep -l "banner-error\|banner-warning" clients/angular/src/app -r \
  | grep -v generate-billing-wizard \
  | grep -v member-form.component.html
```

Apply the migration rule to every hit not in the whitelist.

#### Preserve member-form schema-change warning (whitelist)

**File**: `pages/tenant/members/member-form.component.html` — leave the 1 `banner-warning`
inline. Add a code comment identical to Phase 3's wizard note.

### Success Criteria

#### Automated Verification:
- [ ] `make test-angular`
- [ ] `verify` on `/tenant/finance/payments` — trigger a failure; error toast appears; no banner.
- [ ] `verify` on `/tenant/members/:id/edit` — the schema-change warning still renders **inline**.
- [ ] `grep -rn "banner-error\|banner-warning" clients/angular/src/app` returns only the whitelist: `generate-billing-wizard.component.html` + `member-form.component.html` (+ any others enumerated in Phase 6).

---

## Phase 6: Final cleanup + design-system doc pass

### Overview
Freeze the whitelist, tighten the design-system doc, and run a final Playwright pass to
guard against regressions.

### Changes Required

#### 1. Freeze the whitelist in this plan

Take the final `grep -rn "banner-error\|banner-warning" clients/angular/src/app` output
after Phase 5. Every hit is either:
- On the whitelist below (persistent form-state), or
- A bug — migrate it now.

**Final whitelist** (frozen 2026-09-13 after Phase 5, from `grep -rn "banner-error\|banner-warning" clients/angular/src/app`):

| File | Hits | Reason |
|---|---|---|
| `shared/styles/_form-grammar.scss` | 1 | Global source of `.banner*` styles referenced by the two whitelisted pages. |
| `pages/tenant/billing/generate/generate-billing-wizard.component.html` | 3 | Persistent form-state guardrails (retrospective run notice, revoke card, cooldown). Includes the error banner because the wizard flow relies on the error staying visible across steps. |
| `pages/tenant/billing/generate/generate-billing-wizard.component.scss` | 2 | Local styles supporting the wizard's inline banners. |
| `pages/tenant/members/add/member-form.component.html` | 2 | Schema-change warning + form-level error banner. Both persist while the operator fills the form. |
| `pages/tenant/members/add/member-form.component.scss` | 1 | Local styles supporting the member-form banners. |

Total whitelist: **9 hits across 5 files**. Every other page in the app now surfaces request-failure and envelope-warning feedback through `ToastService`.

#### 2. Final design-system doc polish

Add a small "The `<app-toast>` service" section under "Shared components" with an
example matching the migration rule, so future PRs have a copy-pasteable pattern:

```markdown
### `<app-toast>` — application-wide notifications

Inject `ToastService` and use the shared error extractor:

```typescript
import { extractErrorMessage } from '../../../core/util/http-errors';
import { ToastService } from '../../../shared/components/toast/toast.service';

constructor(private toast: ToastService, /* ... */) {}

save(): void {
  this.svc.save(this.form.value).subscribe({
    next: () => this.toast.success('Saved'),
    error: err => this.toast.error(extractErrorMessage(err, 'Save failed')),
  });
}
```

Aggregate envelope warnings with `composeWarningsToast(warnings, 'Subject')`. Never
render one toast per warning.
```

### Success Criteria

#### Automated Verification:
- [ ] `grep -rn "banner-error\|banner-warning" clients/angular/src/app` matches the frozen whitelist byte-for-byte.
- [ ] `make test-angular` (final regression pass across the app).
- [ ] `make test-e2e` (full Playwright suite).
- [ ] `verify` sweep on one representative page from each area (finance report, tenant-admin settings, billing list, claims list, non-report finance, wizard) — every page shows a clean layout with no banner strip (except the two whitelisted persistent-form pages).

#### Manual Verification:
- [ ] Design-system doc reads cleanly and the toast section is copy-pasteable.
- [ ] The two whitelisted persistent-form pages still render their inline banner (the wizard cooldown, the member-form schema-change).

---

## Testing Strategy

### Unit Tests
- No new component-level unit tests required for the migration itself — the change is a
  swap of one render mechanism for another with the same semantics.
- **New unit tests for the helpers** at `clients/angular/src/app/core/util/http-errors.spec.ts`:
  - `extractErrorMessage` — returns `detail` when present; falls back to `title`; falls back to fallback string when neither present; safe against `null` / `undefined` / non-object bodies.
  - `composeWarningsToast` — returns empty string on empty input; joins with `\n`; header uses "series" for both 1 and N (per the design decision above).

### Integration Tests
- N/A — the migration is client-side only. The backend contract (`err.error.detail` and `envelope.warnings[]`) is unchanged.

### E2E Tests (Playwright)
- Updated in Phase 1 — `cash-flow-forecast.spec.ts`, `loss-ratio-report.spec.ts`,
  `claims-reports-toggle.spec.ts` swap `.banner-warning` assertions for
  `[role="status"]` toast assertions.
- New spec at `clients/angular/e2e/tests/toasts-app-wide.spec.ts`:
  - Given: the tenant-admin producers list.
  - When: the backend returns 500 on load.
  - Then: an error toast with the backend `detail` appears; no `.banner-error` in the DOM; the toast auto-dismisses after ≥ 6s.
- This spec is the guard against regressions app-wide.

### Manual Testing Steps
1. For each of the six phases, hard-refresh the representative page and confirm no banner strip renders between the toolbar and the content.
2. Force the backend into a failing state (`docker compose stop finance` etc.) and confirm the failure surfaces as a toast, not a banner.
3. Force a report page's warnings array to be non-empty (fake the finance service down) and confirm one aggregated toast appears with per-series lines.
4. Confirm the two whitelisted persistent-form pages (wizard, member-form) still render their inline banner — this is the intended carve-out.

## Performance Considerations

- **Bundle size** — negligible. The helper file is ~30 lines; the migration deletes more
  code than it adds (per-page template blocks + fields shrink).
- **DOM size** — decreases app-wide. The `.banner` elements are gone from ~110 pages;
  the toast stack renders at most a handful of transient elements.
- **Change detection** — a toast render + auto-dismiss touches change detection twice
  per event (push, then remove). Trivial vs. the banner's per-page always-present
  DOM node.

## Migration Notes

- **No database or Kafka changes.** This is a pure client-side UX change.
- **No deployment ordering.** The Angular app ships as one artifact; no service order concerns.
- **Backwards compatibility on the backend** — none needed. The frontend continues to
  consume `err.error.detail` and `envelope.warnings[]` unchanged.
- **Toast TTL tuning** — the plan keeps the service defaults (error 6s, warning 5s).
  Two exceptions per the migration rule:
  - Aggregated envelope-warning toast → **8000ms** (longer body needs more reading time).
  - Filter guards keep the 5s warning default.
- **The `.banner*` classes remain in `clients/angular/src/styles.scss`** — do not remove
  them; the whitelisted persistent-form pages depend on them. The only deletion is the
  local copy in `receipts-report.component.scss` (Phase 1).
- **A note about `errorMessage` as a dual-use field** — every migrated page's
  `errorMessage: string | null` field is deleted only when no template reader remains.
  In the rare case where the field is still read (e.g. an `[disabled]="!!errorMessage"`
  binding on a retry button), keep the field but stop writing it from HTTP errors — the
  binding should key off `loading` or a new dedicated flag instead. Enumerate any such
  case during the phase's migration sweep.

## Rollout & Rollback

- **Rollout** — merge each phase independently. Each phase is a self-contained PR.
  Feature-flag not required — the toast subsystem is already live and the migration is
  render-only.
- **Rollback per phase** — revert the phase's PR. The prior banner behavior returns
  wholesale for that feature area. Phase 1's design-system doc + shared shell SCSS
  changes require the same revert; the toast infra itself was already in place and does
  not need to be removed.

## Deviations

- **2026-09-13, Phase 1** — the plan claims `.banner`, `.banner-error`, `.banner-warning` are defined
  in the global `clients/angular/src/styles.scss` (referenced as "design-system.md:229-244"). They are
  not: grep against `styles.scss` returns zero matches. The real global source is
  `clients/angular/src/app/shared/styles/_form-grammar.scss:227-244`, which is a Sass partial pulled
  in by callers via `@use '.../shared/styles/form-grammar'`. Classes are also redefined locally in
  ~111 per-component SCSS files across the app, including several finance-report shells
  (`billing-report.component.scss`, `claims-report.component.scss`, `fraud-report.component.scss`,
  `aged-debtors.component.scss`, `kpi-dashboard.component.scss`, `actuarial-triangle.component.scss`,
  plus the shared `receipts-report.component.scss`). The two whitelisted persistent-form pages
  (`generate/generate-billing-wizard.component.scss` and `add/member-form.component.scss`) also have
  their own local copies, so they remain self-contained without needing the partial.

  **Consequence for Phase 1:** removing `.banner*` blocks stays safe because each page migrated in
  Phase 1 stops referencing the classes in its HTML. Phase 1 broadens the SCSS cleanup step to delete
  the redundant local `.banner*` blocks from every finance-report shell that has one, not just
  `receipts-report.component.scss`. The whitelisted persistent-form pages are untouched.

  **Consequence for Phases 3 and 5:** the whitelist enumeration also inspects local `.banner*` style
  blocks; any page in the whitelist must keep its local styles, and any migrated page's leftover
  `.banner*` styles get deleted at the same time as the template migration.

- **2026-09-13, at user request during Phase 1 completion** — expanded the run to cover every
  non-form page in the app (phases 2 through 5) in a single pass rather than pausing at each phase
  boundary. This bundled: tenant-admin (46 files), tenant/billing (23 files + wizard whitelist),
  tenant/claims (17 pages), non-report finance + policies + platform + misc (12 additional pages
  handled in Phase 5, including 3 pages with a `{ kind, text } banner` object pattern:
  `advance-payments-list`, `advance-payment-detail`, `ctc-payments-list`). Spec fallout was fixed
  in the same pass: `groups/group-form`, `groups/detail/group-detail`, `benefits/benefit-form`,
  `charge-preview/charge-preview`, `schemes/scheme-form`, `commission/corrections/corrections-page`,
  and `members/detail/member-detail` were all rewired to assert on `ToastService` spies. Also:
  cross-page router-state banners on the advance and CTC lists were replaced with a
  `toast[kind](text)` dispatch on init, keeping the redirect-with-feedback UX.

- **2026-09-13, Phase 5 whitelist adjustment** — the wizard whitelist grew from 3 items (2 warnings +
  the revoke card described in the plan) to 3 items in HTML + 2 in SCSS after inspection. Also the
  wizard's `banner-error` at line 73 is kept inline (not migrated to toast) because the wizard is a
  multi-step form flow where a persistent error is friendlier than a 6s toast that could dismiss
  mid-navigation. Member-form gained the same treatment (1 error + 1 warning both persistent).

## References

- Research: `thoughts/shared/research/2026-09-13-app-wide-error-warning-banners-to-toasts.md`
- Design system: `.claude/design-system.md` (lines 226, 270-273 are edited in Phase 1)
- Toast service: `clients/angular/src/app/shared/components/toast/toast.service.ts:19-65`
- Toast container: `clients/angular/src/app/shared/components/toast/toast-container.component.ts:7-29`
- Toast container SCSS (edited in Phase 1): `clients/angular/src/app/shared/components/toast/toast-container.component.scss:28-31`
- Canonical migration reference: `clients/angular/src/app/pages/tenant/finance/reports/loss-ratio/loss-ratio-report.component.{ts,html}`
- Shared shell SCSS (partial deletion in Phase 1): `clients/angular/src/app/pages/tenant/finance/reports/receipts/receipts-report.component.scss:67-76`
- Playwright specs (updated in Phase 1): `clients/angular/e2e/tests/cash-flow-forecast.spec.ts`, `clients/angular/e2e/tests/loss-ratio-report.spec.ts`, `clients/angular/e2e/tests/claims-reports-toggle.spec.ts`
- Whitelisted persistent-form banners (never migrated):
  - `clients/angular/src/app/pages/tenant/billing/generate-billing/generate-billing-wizard.component.html`
  - `clients/angular/src/app/pages/tenant/members/member-form.component.html`
