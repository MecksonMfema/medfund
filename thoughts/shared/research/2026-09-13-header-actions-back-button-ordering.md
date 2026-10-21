---
date: 2026-09-13T00:00:00Z
researcher: Methuseli
git_commit: abb5ea34087c23ba8e0acbc3bbfc84e4df84d6c4
branch: rename-adjustments-to-notes
repository: medfund
topic: "Back-button placement inside `.header-actions` across all tenant pages"
tags: [research, angular, design-system, header-actions]
status: complete
last_updated: 2026-09-13
last_updated_by: Methuseli
---

# Research: Back-button placement inside `.header-actions`

**Date**: 2026-09-13 · **Researcher**: Methuseli · **Commit**: abb5ea34 · **Branch**: rename-adjustments-to-notes

## Research Question

The user reported that `/tenant/finance/reports/regulatory/tax/withheld-return` places
the back button before the primary action inside the header. They asked us to fix that
page against the canonical pattern shown on `/tenant/members/<id>` (member-detail),
then to sweep the whole app and list every page that gets the ordering wrong.

## Summary

**Canonical rule (from `member-detail.component.html:28-49`):** inside
`<div class="header-actions">`, the back element must be the **LAST** child so it
sits on the far right of the header actions cluster. Any action buttons come before
it, sorted left to right.

**Current reality:** 54 pages violate the rule. All of them use the shape
`[back, primary]` (or `[back, ..., primary]`) instead of `[primary, ..., back]`.
The regressions are heavily concentrated in `pages/tenant/finance/reports/**` (49 of
54), because every report was scaffolded from the same `<app-report-back-button>` +
`Export Excel` template with the wrong order.

The report page family (which includes the withheld-return page in the original
question) accounts for 49 of the violations; the remaining 5 are one-off pages in
tenant-admin, tenant/billing, and tenant/finance/creditors.

Fix is mechanical: swap the two children of `.header-actions` on each file below.
No CSS, no component change (`.header-actions` is already `flex` with implicit
row direction, so DOM order == visual order).

## Findings

### Canonical pattern — `pages/tenant/members/detail/member-detail.component.html:28-49`

```html
<div class="header-actions">
  @if (member) {
    @if (canActivate())      { <button class="btn btn-default" (click)="activate()">Activate</button> }
    @if (canSuspend())       { <button class="btn btn-warn"    (click)="suspend()">Suspend</button> }
    @if (canTerminate())     { <button class="btn btn-danger"  (click)="terminate()">Terminate</button> }
    @if (canRecordDeath())   { <button class="btn btn-danger" ...>Record death</button> }
    @if (canClearOverride()) { <button class="btn btn-default" (click)="clearBillingOverride()">Clear custom premium</button> }
    <button class="btn btn-default" (click)="openSwapModal()">Swap with dependant</button>
  }
  <a class="btn btn-default" routerLink="/tenant/members">
    <app-icon name="arrow-left" [size]="14"></app-icon>
    Back to members
  </a>
</div>
```

Back is the terminal child. Every action outranks it left-to-right. Comment on
line 22-27 explicitly notes: "Single right-side action cluster. All buttons ...
share the same outlined weight so nothing screams louder than its neighbour."

### Violating page named by the user — `pages/tenant/finance/reports/regulatory/tax/withheld-return-report.component.html:6-16`

```html
<div class="header-actions">
  <app-report-back-button></app-report-back-button>   <!-- WRONG: first -->
  <button class="btn btn-primary" ...>Download XLSX</button>
</div>
```

Fix: move `<app-report-back-button>` after the Download XLSX `<button>`.

### Also-compliant reference pages

- `pages/tenant-admin/producers/bulk-reassign.component.html`
- `pages/tenant/billing/groups/detail/group-detail.component.html`
- `pages/tenant/billing/invoices/invoice-statement.component.html`
- `pages/tenant/claims/detail/claim-detail.component.html`
- `pages/tenant/claims/preauth/pre-auth-detail.component.html`

### Violations (54)

Reports (49):

- `pages/tenant/finance/reports/actuarial/actuarial-triangle.component.html:6`
- `pages/tenant/finance/reports/actuarial/lapse-study.component.html:6`
- `pages/tenant/finance/reports/actuarial/morbidity-study.component.html:6`
- `pages/tenant/finance/reports/actuarial/mortality-study.component.html:6`
- `pages/tenant/finance/reports/actuarial/persistency-study.component.html:6`
- `pages/tenant/finance/reports/aged-debtors/aged-debtors.component.html:10`
- `pages/tenant/finance/reports/balance-history/member-balance-history.component.html:9`
- `pages/tenant/finance/reports/balance-history/provider-balance-history.component.html:9`
- `pages/tenant/finance/reports/billing/group-billing-report.component.html:11`
- `pages/tenant/finance/reports/billing/scheme-billing-report.component.html:10`
- `pages/tenant/finance/reports/cash-flow-forecast/cash-flow-forecast.component.html:10`
- `pages/tenant/finance/reports/claims/claim-status-matrix.component.html:11`
- `pages/tenant/finance/reports/claims/claims-detail.component.html:9`
- `pages/tenant/finance/reports/claims/denial-analysis-report.component.html:11`
- `pages/tenant/finance/reports/claims/frequency-severity-report.component.html:13`
- `pages/tenant/finance/reports/claims/group-claims-report.component.html:11`
- `pages/tenant/finance/reports/claims/high-cost-claimants-report.component.html:11`
- `pages/tenant/finance/reports/claims/member-claims-report.component.html:10`
- `pages/tenant/finance/reports/claims/pre-auth-activity-report.component.html:11`
- `pages/tenant/finance/reports/claims/provider-claims-report.component.html:11`
- `pages/tenant/finance/reports/claims/provider-network-utilization.component.html:10`
- `pages/tenant/finance/reports/claims/scheme-claims-report.component.html:11`
- `pages/tenant/finance/reports/collection-rate-trend/collection-rate-trend.component.html:10`
- `pages/tenant/finance/reports/collection-rate/collection-rate-report.component.html:10`
- `pages/tenant/finance/reports/commission/commission-clawback-register.component.html:10`
- `pages/tenant/finance/reports/commission/commission-statement.component.html:10`
- `pages/tenant/finance/reports/compliance/aml-str/aml-alerts-list.component.html:6`
- `pages/tenant/finance/reports/fraud/fraud-report.component.html:11`
- `pages/tenant/finance/reports/ifrs17/insurance-revenue-service-result.component.html:6`
- `pages/tenant/finance/reports/ifrs17/lrc-lic-reconciliation.component.html:6`
- `pages/tenant/finance/reports/loss-ratio/loss-ratio-report.component.html:9`
- `pages/tenant/finance/reports/member-billing/member-billing-report.component.html:10`
- `pages/tenant/finance/reports/member-payments/member-payments-report.component.html:9`
- `pages/tenant/finance/reports/policy-lifecycle/group-census/group-census.component.html:13`
- `pages/tenant/finance/reports/policy-lifecycle/movement/movement.component.html:11`
- `pages/tenant/finance/reports/policy-lifecycle/persistency-cohort/persistency-cohort.component.html:10`
- `pages/tenant/finance/reports/receipts/group-receipts-report.component.html:11`
- `pages/tenant/finance/reports/receipts/member-receipts-report.component.html:10`
- `pages/tenant/finance/reports/receipts/receipts-detail.component.html:9`
- `pages/tenant/finance/reports/receipts/scheme-receipts-report.component.html:12`
- `pages/tenant/finance/reports/regulatory/ipec/ipec-quarterly-return.component.html:6`
- `pages/tenant/finance/reports/regulatory/pmb-spend/pmb-spend-report.component.html:6`
- `pages/tenant/finance/reports/regulatory/tax/vat-return-report.component.html:6`
- `pages/tenant/finance/reports/regulatory/tax/withheld-return-report.component.html:6`
- `pages/tenant/finance/reports/reinsurance/cession-bordereau.component.html:11`
- `pages/tenant/finance/reports/reinsurance/recoveries-bordereau.component.html:10`
- `pages/tenant/finance/reports/reinsurance/treaty-utilization.component.html:11`
- `pages/tenant/finance/reports/underwriting/endorsement-register.component.html:11`
- `pages/tenant/finance/reports/underwriting/new-business-register.component.html:10`
- `pages/tenant/finance/reports/underwriting/premium-register.component.html:10`
- `pages/tenant/finance/reports/underwriting/upr-movement.component.html:10`

Non-report violations (5):

- `pages/tenant-admin/producers/rate-cards-list.component.html:10`
- `pages/tenant/billing/benefits/benefits-list.component.html:12`
- `pages/tenant/finance/creditors/member-balance-detail.component.html:8` — regression from commit `abb5ea34` (this branch); Back was intentionally moved into the right cluster last session, but placed first-in-cluster rather than last
- `pages/tenant/finance/reports/compliance/aml-str/aml-alerts-list.component.html:6`
- `pages/tenant/finance/reports/fraud/fraud-report.component.html:11`

All 54 files use the two-element or three-element shape `[back, ...primary]`. None
of them have back in the middle; the fix on every file is: move the back element
(usually `<app-report-back-button>`) to the end of the `.header-actions` block.

## Architecture doc vs. code

`.claude/design-system.md` is the canonical UI reference. This research did not
re-open it in full, but the code pattern shown on `member-detail.component.html` is
what the recent commits on this branch (`a1e03ca2`, `04a659d5`, `9c385d63`,
`eb8e6d49`, `456726d3`) treat as the target. The current drift is a scaffolding
mistake in the report family, not a doc conflict.

## Code References

- `clients/angular/src/app/pages/tenant/members/detail/member-detail.component.html:22-49` — canonical: comment + right-side cluster with back terminal
- `clients/angular/src/app/pages/tenant/finance/reports/regulatory/tax/withheld-return-report.component.html:6-16` — user-reported violation
- `clients/angular/src/app/pages/tenant/finance/creditors/member-balance-detail.component.html:8-17` — new regression from this branch
- 54 total files listed above under Violations

## Architecture Insights

- `.header-actions` is a plain `display: flex` row; DOM order equals visual order.
  There is no CSS or template-directive knob for "put back last" — it is a
  hand-authored convention.
- The 49 report violations share one root cause: every report page was cloned from
  the same starter shape (`<app-report-back-button>` first, `<button>Export Excel</button>`
  second). One incorrect scaffold multiplied into 49 identical bugs. A shared
  `<app-report-header>` wrapper that takes the primary action as a slot would have
  prevented every one of them — a follow-up worth considering but not required
  for the fix.
- No Critical Rule (from `.claude/CLAUDE.md`) is touched by this work; pure UI.

## Historical Context (from thoughts/shared/)

None found — this is a design-system-level UI rule that has not previously been
formalized in `thoughts/`.

## Related Research

None — this is a targeted UI audit.

## Open Questions

- Do we want to enforce this with a lint rule or a shared `<app-report-header>`
  component after fixing? Out of scope for the immediate fix; worth raising as a
  design-system follow-up so the pattern doesn't re-drift the next time someone
  scaffolds a new report.
- The tenant-admin/tenant/billing violations may have their own accepted patterns
  (e.g. `benefits-list` has more than two children — verify visual expectation
  before mechanically swapping). Spot-check before merging.
