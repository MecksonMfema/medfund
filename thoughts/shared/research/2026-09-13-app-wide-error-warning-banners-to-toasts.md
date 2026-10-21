---
date: 2026-09-13T09:31:00+02:00
researcher: Methuseli
git_commit: 456726d3369a414b2e9c8a7f4b910fbb638713f5
branch: rename-adjustments-to-notes
repository: medfund
topic: "Replace app-wide inline error/warning banners with transient toasts"
tags: [research, codebase, angular, ux, toasts, banners, design-system]
status: complete
last_updated: 2026-09-13
last_updated_by: Methuseli
---

# Research: Replace app-wide inline error/warning banners with transient toasts

**Date**: 2026-09-13T09:31:00+02:00 · **Researcher**: Methuseli · **Commit**: 456726d3 · **Branch**: rename-adjustments-to-notes

## Research Question

The inline error/warning banners currently rendered on every page (see screenshots
`Screenshot From 2026-09-13 09-24-53.png` — a full-width red "Failed to load cash-flow
forecast" bar; and `Screenshot From 2026-09-13 09-25-00.png` — a stacked yellow warnings
block listing 6 backend availability issues) are the wrong UX. They consume prime real
estate between the toolbar and the content, they persist until the user manually
dismisses them, and they push the actual data down the page. The user's directive is:
**replace them with severity-tagged toasts that auto-dismiss so the UI stays clean.**

## Summary

The Angular app already has a full-featured toast subsystem
(`ToastService` + `AppToastContainerComponent`) mounted in both the tenant layout and
the super-admin layout. It has been in use in newer features (~49 components, 97 call
sites) but was never adopted by the older report and admin pages, which stuck with the
inline `.banner.banner-error` / `.banner.banner-warning` grammar from the shared shell.

**Scope of the migration is large**: 179 `banner-error` template hits across 117 files
and 45 `banner-warning` hits across 43 files — **224 sites in total.** Every finance
report, every tenant-admin settings tab, every billing/claims list page renders one of
these banners today.

The task is not "build a toast" — it's "flip the whole app from inline banner to toast,
delete the dead classes, and decide what to do with the small minority of legitimately
persistent messages (form-validation errors that belong to a form, not a request)."

## Findings

### Existing toast infrastructure (already wired, ready to use)

- **Service** — `clients/angular/src/app/shared/components/toast/toast.service.ts:19-65`
  - `@Injectable({ providedIn: 'root' })` singleton
  - API: `success(msg, durationMs?)`, `error(msg, durationMs?)`, `info(msg, durationMs?)`, `warning(msg, durationMs?)`, `dismiss(id)`, `clear()`
  - Default TTLs: success 4s, info 4s, warning 5s, error 6s (`:25-30`)
  - Returns a toast `id` so callers can dismiss imperatively (`:38-40`)
  - Pass `durationMs: 0` to make a toast sticky
- **Container** — `clients/angular/src/app/shared/components/toast/toast-container.component.ts:7`
  - Selector `app-toast-container`, uses `IconComponent`
  - Subscribes to `ToastService.toasts$`, renders the stack
  - Styling at `toast-container.component.scss`:
    - Fixed top-right (`80px` from top, `20px` from right)
    - `z-index: 1100` (above dialogs at `1000`)
    - 4px colored left border per severity, animated slide-in 180ms ease-out
    - Uses the same `--color-{success|error|warning|info}` tokens as the banners, so visual consistency is free
- **Mount points** — already in place:
  - `clients/angular/src/app/layout/layout.component.html:2`
  - `clients/angular/src/app/layout/tenant-layout/tenant-layout.component.html:2`
- **No third-party dep** — this is plain Angular + RxJS. No ngx-toastr, PrimeNG, ng-zorro pulled in.

### Current inline banner pattern (what we're replacing)

Two variants live in the shared shell SCSS (e.g. `clients/angular/src/app/pages/tenant/finance/reports/receipts/receipts-report.component.scss:67-76` and mirrored in `clients/angular/src/styles.scss` form-grammar block):

```scss
.banner {
  padding: 10px 14px;
  margin: 12px 24px 0;
  border-radius: 8px;
  cursor: pointer;
  &.banner-error   { background: var(--color-error-light);   color: #c4162d; border-color: rgba(196,22,45,.2); }
  &.banner-warning { background: var(--color-warning-light); color: #c47800; cursor: default; }
}
```

Template idioms (found uniformly across all consumers):

```html
@if (errorMessage) {
  <div class="banner banner-error" (click)="errorMessage = null">{{ errorMessage }}</div>
}

@if (envelope?.warnings?.length) {
  <div class="banner banner-warning">
    @for (w of envelope!.warnings; track w) { <div>{{ w }}</div> }
  </div>
}
```

TS side is uniform too: `errorMessage: string | null` set in the fetch `error:` callback
from `err?.error?.detail || err?.error?.title || 'Failed to <do X>'`, cleared in the
next `fetch()`. Warnings come from a report envelope (`ReportResponse<T>.warnings: string[]`).

### Scope catalogue (by feature area)

Grouped counts from a full grep of `banner-error` / `banner-warning` in `clients/angular/src/app/**/*.html`:

| Area | Files | Error hits | Warning hits |
|---|---|---|---|
| `pages/tenant/finance/reports/**` | 50 | 50 | 41 |
| `pages/tenant/admin/**` (settings tabs, producers, rate cards, backfill, portfolios, funds, cohorts) | 27 | 27 | 0 |
| `pages/tenant/billing/**` (groups, transactions, benefits, waiting-periods, invoices, schemes, contributions, emails, age-groups, generate-billing-wizard) | 24 | 24 | 3 (wizard cooldown/retrospective) |
| `pages/tenant/claims/**` (preauth, tariffs, drugs, SIU, rejection-reasons, modifiers, eligibility-quote, pending, CTC) | 21 | 21 | 0 |
| `pages/tenant/finance/**` (non-reports: payments, runs, advance, creditors, notes, reinsurance, underwriting, payouts, advice/reconciliation) | 22 | 22 | 0 |
| `pages/tenant/policies/**` + members detail/form | 3 | 2 | 1 (schema-change warning on member form) |
| `pages/platform/**` + legacy `members.component.html` + jobs monitor | 2 | 2 | 0 |
| Miscellaneous | ~11 | 31 | 0 |
| **Total** | **117** | **179** | **45** |

Finance reports dominate — they are the only surface that carries both patterns, because the report envelope has a real `warnings[]` field. The rest are single-line request-failure banners.

### Two subtly different UX cases

1. **"Request failed" errors** (majority — the red banners) — transient by nature.
   Toast is the correct swap. The banner is currently the only feedback the user gets
   when a GET returns 4xx/5xx, and dismissing it is manual.
2. **Envelope warnings** (report pages) — the yellow banner in
   `Screenshot 09-25-00` is 6 lines listing which upstream KPI series were unavailable.
   These aren't errors; they're informational badges of partial data. There is a real
   design question about *how* they surface as toasts (one per line? one summary?
   collapsed to a "warnings" chip in the header?).
3. **A small minority are form-scoped**: e.g. "Choose a start and end date" set inside
   an `onFilterChange()` guard. These aren't request failures; they're form-validation
   messages that belong in-context near the field, not floating in the top-right for 6
   seconds. Some of these might convert cleanly, some might stay inline (below the
   field, not as a page-level banner).

### The shared shell SCSS

The `.banner` / `.banner-error` / `.banner-warning` classes live in two places:

- `clients/angular/src/app/pages/tenant/finance/reports/receipts/receipts-report.component.scss:67-76` — shared shell reused by the 4 reconciliation reports
- Global `form-grammar.scss` block referenced by design-system.md (`design-system.md:229-244`)

Both need to stay defined only if we keep the pattern for form-validation-in-context uses. Otherwise the classes and their `--color-error-light` / `--color-warning-light` overhead can be removed.

## Cross-service flow

Not applicable — this is a pure client-side UX change. Backend contract (envelope
`.warnings[]` and standard problem-details `err.error.detail`/`err.error.title`) is
unchanged; only the *presentation* moves from inline DOM to the toast overlay.

## Architecture doc vs. code

- `design-system.md:226` names `<app-toast>` as the canonical application-wide
  notification component. **Code has this in place** and it's already used in ~49
  components — so the doc is honored on the *newer* features but not on the older
  reports/admin/billing surfaces. This is drift by omission, not by disagreement.
- `design-system.md:271-273` also allows an optional **persistent** page-level banner
  ("Optional banner — errorMessage ? show .banner.banner-error") — so the design
  system explicitly permits banners for the sub-case where the message needs to stay
  visible. The plan should honor that carve-out, not delete the pattern entirely.
- No accessibility guidance in either the doc or the current toast container
  (`aria-live="polite"` is set on the container per the earlier research pass, which
  is correct for non-critical notifications; errors *could* justify `role="alert"` /
  `aria-live="assertive"`, but that's a follow-up decision).

## Code References

- `clients/angular/src/app/shared/components/toast/toast.service.ts:19-65` — service to inject
- `clients/angular/src/app/shared/components/toast/toast-container.component.ts:7` — container selector
- `clients/angular/src/app/layout/tenant-layout/tenant-layout.component.html:2` — mount point (tenant surfaces)
- `clients/angular/src/app/layout/layout.component.html:2` — mount point (super-admin + auth surfaces)
- `clients/angular/src/app/pages/tenant/finance/reports/loss-ratio/loss-ratio-report.component.html:23-25,49-55` — canonical single-error + multi-warning pattern to migrate
- `clients/angular/src/app/pages/tenant/finance/reports/loss-ratio/loss-ratio-report.component.ts:97-101` — canonical error-handler shape
- `clients/angular/src/app/pages/tenant/finance/reports/receipts/receipts-report.component.scss:67-76` — shared shell `.banner` styles
- `.claude/design-system.md:226` — `<app-toast>` is the sanctioned pattern
- `.claude/design-system.md:271-273` — persistent `.banner-error` is *also* sanctioned for form-scoped/page-level cases

## What this ticket needs decided

1. **All 224 sites, or only the request-failure ones?** — Recommend migrating every
   `banner-error` that's assigned from an HTTP `error:` callback (the vast majority),
   plus every `banner-warning` bound to `envelope.warnings`. **Leave** the handful of
   form-validation banners inline (or convert them to inline field-level errors) —
   they don't belong as toasts. The scan needs one pass with a manual triage column.
2. **Envelope-warning fan-out** — six-line list vs. one toast. Options: (a) fire one
   toast per warning (loud and spammy), (b) fire one aggregate toast "6 report series
   unavailable" and keep a compact "Warnings (6)" chip in the toolbar that opens a
   popover for the detail (clean but adds a new UI), (c) fire one toast summarizing
   count + severity, no chip (simplest, loses detail). Recommend (b) so operators can
   still see which series went missing without cluttering the page.
3. **Keep or drop `.banner-error` / `.banner-warning` classes?** — Recommend keep (they
   remain in `styles.scss` for form-scoped use per design-system.md:271-273) but remove
   the copies from `receipts-report.component.scss` since the shell no longer needs
   them once every report page migrates. Design-system.md text may want a note that
   the default choice is a toast; banner is the escape hatch.
4. **Error-message extraction helper** — every migration site does the same
   `err?.error?.detail || err?.error?.title || 'Failed to <verb>'` dance. Recommend
   adding `extractErrorMessage(err, fallback)` in `core/util/http-errors.ts` (or
   similar) so the 179 sites collapse to one-liner `toast.error(extractErrorMessage(err, 'Failed to load loss ratio'))`.
5. **Do we also swap the wizard cooldown/retrospective warnings?** — The
   generate-billing-wizard warnings (`3 hits`) are procedural guardrails that the user
   needs to see *while filling the form*. Recommend they stay inline (they're closer
   to form-validation than request-failure).

## Gaps between spec and code

- `design-system.md:226` names `<app-toast>` first-class; ~49 modules honor this, ~117
  do not. That's a large drift where the older surfaces predate the toast service.
- `design-system.md:271-273` says `.banner-error` is *optional* — but the codebase
  uses it as the *default*. The plan flips those defaults: toast is default, banner
  is optional.

## Architecture Insights

- **This is a UX polish task, not a Critical Rule issue.** No tenant-scoping, currency,
  auditing, or Kafka contract is touched. The nine Critical Rules in `.claude/CLAUDE.md`
  are unaffected.
- **The migration compresses well.** Each site's diff is ~5 lines: delete the
  `@if (errorMessage) { <div class="banner banner-error"... }` block, inject
  `ToastService`, replace `this.errorMessage = ...` with `this.toast.error(...)`,
  drop the `errorMessage` field if it has no other reader. A codemod is plausible
  but risky (the banner text is bespoke per page); a scripted-assist manual pass is
  probably faster than perfect regex.
- **Component isolation matters.** Because the banner classes live in per-component
  SCSS (via `styleUrl: '../receipts/receipts-report.component.scss'`), the migration
  can go page-by-page without a big-bang stylesheet edit — a component can stop using
  `.banner` locally even while the class still exists.
- **Toast durations may need tuning per severity** — the current defaults
  (`error: 6000ms`) are on the short side for a report-failure message where the
  operator is likely mid-scroll. Recommend the plan makes duration a call-site
  argument (already supported by the service) rather than raising the default.

## Historical Context (from thoughts/shared/)

- No prior research doc targets the toast/banner UX explicitly. The toast service was
  introduced organically as newer features rolled out; there is no design ADR that
  captured the decision.

## Related Research

- `thoughts/shared/research/2026-08-08-*.md` and later — several report/finance-facing
  research docs assume the banner-error idiom but do not question it.

## Open Questions

- Should error toasts render `role="alert"` / `aria-live="assertive"` (instead of the
  container's blanket `polite`) so screen readers announce them immediately?
- On mobile / narrow viewports, does the top-right stack collide with the hamburger
  header? Worth a quick check in the Flutter member-portal + PWA views.
- Do we want a `toast.error(msg, { action: { label: 'Retry', handler: () => this.fetch() } })`
  pattern for request failures? That would be a service extension, worth doing once
  since we're touching 224 sites.

## Hand-off

Research written to `thoughts/shared/research/2026-09-13-app-wide-error-warning-banners-to-toasts.md`.

Clear your context, then run:

```
create-plan thoughts/shared/research/2026-09-13-app-wide-error-warning-banners-to-toasts.md \
            "phase this — start with the finance report pages (the four in the screenshots), \
             land the extractErrorMessage helper, then roll out per feature area. keep \
             form-validation banners inline; don't try to migrate them"
```
