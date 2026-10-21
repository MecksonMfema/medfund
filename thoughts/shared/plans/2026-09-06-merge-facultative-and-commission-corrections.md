---
date: 2026-09-06
author: Methuseli
branch: rename-adjustments-to-notes
base_commit: bcec612b64567c90565d7a444860734d5444d7ab
status: ready-to-implement
scope: [angular, tenancy-service]
tags: [ui-refactor, sidebar-collapse, rename, facultative, commission]
---

# Merge facultative + commission-adjustment pages, rename Adjustments → Corrections

## Context

The operations portal ships four sidebar entries for two workflows that are structurally two-page each:

- **Facultative reinsurance** — `Facultative - Browse` (underwriter picks an adjudicated claim and cedes it) and `Facultative - Queue` (supervisor approves / commits / voids draft cessions).
- **Commission adjustments** — `Adjustments (draft)` (drafter creates a `DRAFT` correction against a `commission_transaction`) and `Adjustments (approve)` (approver moves rows through `DRAFT → APPROVED → COMMITTED`, with `VOIDED` as an escape hatch).

Both workflows are four-eyes. The current two-page split maps to the drafter/approver roles, but each workflow really belongs on a single page — commission adjustments trivially so (both pages hit `GET /commission/adjustments` and differ only in default status), facultative less trivially (Browse and Queue fetch different entity types, so the merge is tabbed rather than filter-based).

Additionally, the word "Adjustments" is now ambiguous in the app after commit `0a1609d7 "Rename Adjustment to Note and wire notes onto payment advices"` retitled the billing-notes feature. The `commission/adjustments/*` routes are the last user-facing "adjustments" and their name no longer disambiguates from claim notes, tariff modifiers, and policy endorsements. This plan renames them to **"Commission corrections"** in every user-facing string, leaving the Angular internal class names (`CommissionAdjustmentService`, `Adjustment`, `AdjustmentStatus`) unchanged so they continue to match the backend API URL (`/commission/adjustments`) and Java classes.

## Decisions (from grilling session, /tmp/grilling-scratchpad.md)

- **G1** — Rename `Commission adjustments` → **`Commission corrections`** (user-facing only).
- **G2** — Commission corrections is a **single view** with a status filter and a "New correction" toolbar button (no tabs).
- **G3** — Facultative is **two tabs**: `Cedable claims` (candidates + inline cede-form split-pane) and `Cession queue` (list + status filter + row actions). Cede form stays inline; no modal.
- **G4** — Both pages visible on **OR-set** of `view | drafter-perm | approver-perm`. Inner surfaces (new-button, cede-form, row actions) gated on the specific action-perm. Read-only for viewers.
- **G5** — **No legacy redirects.** Old paths return 404 (fall through to the not-found route).
- **G6a** — Delete the four deprecated `SidebarSectionKey` enum values; add two new ones (`REPORTING_FACULTATIVE`, `REPORTING_COMMISSION_CORRECTIONS`); write V180 to delete orphan `tenant_sidebar_section_config` rows.
- **G6b** — Leave Angular internal class/interface names as `CommissionAdjustment*`. Only user-facing strings change.
- **G7** — One plan, three phases: Facultative merge → Commission corrections merge + rename → Section-key housekeeping.

## Manual verification note (applies to all phases)

Because there are no legacy redirects (G5), the plan reviewer must confirm before merge that nothing outside this branch references the four old URL paths. Grep the repo for:
- `/reinsurance/facultative/browse`
- `/reinsurance/facultative/queue`
- `/commission/adjustments/draft`
- `/commission/adjustments/approve`

Anything found outside the four component files being retired must be updated in the same PR (email templates, docs, seed data, e2e specs, external docs).

## Phase 1 — Merge Facultative

### Goal
Collapse the two facultative sidebar entries into one page `/tenant/finance/reinsurance/facultative` with two permission-gated tabs.

### Design

**New component:** `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-page.component.ts`

Owns the tab state and OR-set permission gate. Renders two tabs conditionally:

- `Cedable claims` tab (visible if user has `finance.reinsurance:cede_facultative`) — renders the existing split-pane candidates + inline cede-form. Content is a straight lift from `facultative-browse.component.html/scss/ts` into a child component `<app-facultative-candidates-tab>`.
- `Cession queue` tab (visible if user has `finance.reinsurance:view` or `finance.reinsurance:approve_facultative`) — renders the existing status-filtered queue. Content lifted from `facultative-approve-queue.component.html/scss/ts` into `<app-facultative-queue-tab>`. Row action buttons (Approve, Commit, Void) inside gated on `approve_facultative`.

**Default tab rule:**
- User has only `cede_facultative` → default `Cedable claims`.
- User has `view` or `approve_facultative` (with or without cede) → default `Cession queue` (approver's queue is the higher-priority task; a user with both perms lands on the queue because the queue is what tells them "there's work to do").
- User has neither cede nor approve/view (shouldn't happen — route guard would reject) → route guard rejects.

**Tab UI pattern:** Use the existing settings-tabs pattern (pill-shaped `.tab` buttons in a `.tabs` strip — see `clients/angular/src/app/pages/tenant-admin/settings/settings.component.scss:21-87`). This matches the app's canonical tab-strip and does not introduce a new design pattern.

### Changes

**Add:**
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-page.component.ts` — new tab container. `@Component` standalone, imports `CommonModule`, `FacultativeCandidatesTabComponent`, `FacultativeQueueTabComponent`, `PermissionService`. Reads `perms.hasAny([...])` to decide default tab and visibility.
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-page.component.html` — tab strip + `@switch (activeTab)` for content.
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-page.component.scss` — reuse `.tabs`/`.tab` pattern from settings; add `.tab-body` wrapper with `min-height: 400px` so the cede-form's split-pane doesn't collapse inside a shorter tab.
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-candidates-tab.component.ts/html/scss` — extracted from `facultative-browse.component.*`, unchanged internal logic. Rename selector to `app-facultative-candidates-tab`. Move `styleUrl` file wholesale (renamed to `facultative-candidates-tab.component.scss`).
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-queue-tab.component.ts/html/scss` — extracted from `facultative-approve-queue.component.*`. Same rename pattern.
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-page.component.spec.ts` — 4+ specs: default-tab per permission set, tab-visibility per permission set, hides `Cedable claims` tab when user lacks `cede_facultative`, hides `Cession queue` tab when user lacks both `view` and `approve_facultative` (i.e. only cede-perm user only sees one tab and there's no tab-strip UI in that case).

**Delete:**
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-browse.component.ts/html/scss` — content lives in `facultative-candidates-tab.*` now.
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative-approve-queue.component.ts/html/scss` — content lives in `facultative-queue-tab.*` now.
- Corresponding `.spec.ts` files if they exist (verify during implementation).

**Modify:**
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts` — remove the two current facultative route entries (browse + queue), add one new route:
  ```
  {
    path: 'reinsurance/facultative',
    canActivate: [permissionGuard([
      'finance.reinsurance:view',
      'finance.reinsurance:cede_facultative',
      'finance.reinsurance:approve_facultative',
    ])],
    loadComponent: () =>
      import('./reinsurance/facultative-page.component')
        .then(m => m.FacultativePageComponent),
    data: { title: 'Facultative reinsurance' },
  }
  ```
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:180-184` — replace the two entries with:
  ```
  {
    label: 'Facultative reinsurance',
    icon: 'shield',
    route: '/tenant/finance/reinsurance/facultative',
    permissions: [
      'finance.reinsurance:view',
      'finance.reinsurance:cede_facultative',
      'finance.reinsurance:approve_facultative',
    ],
    sectionKey: 'REPORTING_FACULTATIVE',
  },
  ```
  Delete the two-entry stanza (`Facultative - Browse` and `Facultative - Queue`).

### Success criteria

**Automated:**
- `make test-angular` passes (all specs green, including the new `facultative-page.component.spec.ts`)
- `cd clients/angular && npx ng build` succeeds
- No lingering imports of `FacultativeBrowseComponent` or `FacultativeApproveQueueComponent` (grep across `clients/angular/`)
- Route table has exactly one `reinsurance/facultative*` entry (grep)

**Manual (browser at http://localhost:5100):**
- As a user with only `cede_facultative`: sidebar shows `Facultative reinsurance`; clicking lands on `Cedable claims` tab only (no `Cession queue` tab visible)
- As a user with only `finance.reinsurance:view`: sidebar shows `Facultative reinsurance`; page opens on `Cession queue` tab, row actions are absent
- As a user with `approve_facultative`: sidebar shows `Facultative reinsurance`; page opens on `Cession queue` tab, row actions are present
- As a user with both `cede_facultative` and `approve_facultative`: both tabs visible, `Cession queue` is default
- Ceding a candidate from Tab 1 → row appears in Tab 2 after a manual page refresh (or automatically if we wire a reload — see follow-up)
- Bookmarks to `/reinsurance/facultative/browse` and `/reinsurance/facultative/queue` return the app's not-found route (G5 confirmation)

## Phase 2 — Merge Commission adjustments, rename → Commission corrections

### Goal
Collapse `Adjustments (draft)` + `Adjustments (approve)` into a single page `/tenant/finance/commission/corrections` with a status filter and a permission-gated "New correction" button. Rename all user-facing strings from "Adjustments" to "Commission corrections".

### Design

**New component:** `clients/angular/src/app/pages/tenant/finance/commission/corrections/corrections-page.component.ts`

Single view. Toolbar has:
- Status filter dropdown: `All open (DRAFT + APPROVED)` [default], `DRAFT`, `APPROVED`, `COMMITTED`, `VOIDED`, `All`.
- `New correction` button — visible only when `perms.hasAny(['finance.commission:draft_adjustment'])`. Opens the existing create-modal (lifted from `adjustments-drafter-queue.component.*`).

Table renders `Adjustment` rows via `<app-data-table>` (design-system table). Columns: reference, target-transaction, type, native amount, status (as `.badge-*`), drafter, approver, actions. Actions column visible only when `perms.hasAny(['finance.commission:approve_adjustment'])`.

Row-action buttons (Approve, Commit, Void) render per status:
- DRAFT → Approve / Void
- APPROVED → Commit / Void
- COMMITTED, VOIDED → no actions (row is terminal)

Inline hint below the page header (visible when the user has draft-perm but not approve-perm): "Your drafts appear in the list; an approver must move them through Approve → Commit before they post to the ledger."

**Rename mechanics — user-facing strings only:**
- Page title: `Commission corrections`
- Sidebar label: `Commission corrections`
- Route segment: `commission/corrections`
- Nav icon: keep `edit` (or consider `arrow-repeat` for "correction" — implementation-time choice from the available icon set)
- Permission descriptions in `clients/angular/src/app/core/security/permissions.ts:212-213` — update labels/descriptions to say "correction" instead of "adjustment" while keeping the permission keys (`finance.commission:draft_adjustment`, `finance.commission:approve_adjustment`) unchanged.
- Modal title, form labels, error messages, empty-state copy, banner copy — all "adjustment" → "correction" (case-preserving).

**Angular class/interface names stay `CommissionAdjustment*` (G6b decision).** This creates a UI-vs-internals vocabulary split; document it in the plan's Deviations if implementation-time this feels wrong.

### Changes

**Add:**
- `clients/angular/src/app/pages/tenant/finance/commission/corrections/corrections-page.component.ts/html/scss` — single-view page with toolbar, status filter, `New correction` button, `<app-data-table>` with action columns.
- `clients/angular/src/app/pages/tenant/finance/commission/corrections/correction-detail.component.ts/html/scss` — lifted from the existing `adjustment-detail.component.*` at `clients/angular/src/app/pages/tenant/finance/commission/adjustments/`, with strings updated.
- `clients/angular/src/app/pages/tenant/finance/commission/corrections/corrections-page.component.spec.ts` — 6+ specs: default status filter, "New correction" hidden without drafter perm, action buttons hidden without approver perm, view-only user sees list, drafter opens create-modal on click, approver clicks approve → service.approve called, terminal-status rows have no action buttons.

**Delete:**
- `clients/angular/src/app/pages/tenant/finance/commission/adjustments/adjustments-drafter-queue.component.ts/html/scss/spec.ts`
- `clients/angular/src/app/pages/tenant/finance/commission/adjustments/adjustments-approver-queue.component.ts/html/scss/spec.ts`
- `clients/angular/src/app/pages/tenant/finance/commission/adjustments/adjustment-detail.component.ts/html/scss/spec.ts`
- (Whole `adjustments/` folder — verify empty before removing.)

**Modify:**
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:844-856` — replace the two `commission/adjustments/*` route entries with:
  ```
  {
    path: 'commission/corrections',
    canActivate: [permissionGuard([
      'finance.commission:view',
      'finance.commission:draft_adjustment',
      'finance.commission:approve_adjustment',
    ])],
    loadComponent: () =>
      import('./commission/corrections/corrections-page.component')
        .then(m => m.CorrectionsPageComponent),
    data: { title: 'Commission corrections' },
  },
  {
    path: 'commission/corrections/:id',
    canActivate: [permissionGuard([
      'finance.commission:view',
      'finance.commission:draft_adjustment',
      'finance.commission:approve_adjustment',
    ])],
    loadComponent: () =>
      import('./commission/corrections/correction-detail.component')
        .then(m => m.CorrectionDetailComponent),
    data: { title: 'Commission correction' },
  }
  ```
  Do not add redirects for `/commission/adjustments/*` (G5).
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:191-195` — replace the two entries with:
  ```
  {
    label: 'Commission corrections',
    icon: 'edit',
    route: '/tenant/finance/commission/corrections',
    permissions: [
      'finance.commission:view',
      'finance.commission:draft_adjustment',
      'finance.commission:approve_adjustment',
    ],
    sectionKey: 'REPORTING_COMMISSION_CORRECTIONS',
  },
  ```
- `clients/angular/src/app/core/security/permissions.ts:212-213` — update the `label` and `description` fields of `finance.commission:draft_adjustment` and `finance.commission:approve_adjustment` to say "correction" instead of "adjustment". The permission key itself stays (renaming the key is a breaking change for tenant role bindings — separate future ticket).
- `clients/angular/src/app/core/security/permissions.ts:210` — update the `finance.commission:view` description ("Read access to commission transactions, clawback register, and adjustment queues.") to say "corrections" instead of "adjustment queues".

### Success criteria

**Automated:**
- `make test-angular` passes
- `cd clients/angular && npx ng build` succeeds
- Grep confirms no `adjustments-drafter-queue`, `adjustments-approver-queue`, or `adjustment-detail` component references remain outside the git-history
- Grep confirms the `commission/adjustments/*` route paths are absent

**Manual (browser):**
- As a user with only `draft_adjustment`: sidebar shows `Commission corrections`; page opens on `All open` filter; `New correction` button is visible; row-action columns are absent; clicking `New correction` opens the modal
- As a user with only `approve_adjustment`: sidebar shows `Commission corrections`; page opens on `All open`; `New correction` is hidden; row actions Approve/Commit/Void are visible per status
- As a user with only `finance.commission:view`: sidebar shows `Commission corrections`; page is fully read-only (no button, no actions)
- Filtering to `COMMITTED` shows terminal rows with no action buttons
- Bookmark to `/commission/adjustments/draft` and `/commission/adjustments/approve` return not-found
- Sidebar-visibility admin UI (`/tenant/admin/settings` → Profile → Sidebar Visibility) shows the new `Commission corrections` entry (and does not show the deleted `Adjustments (draft)` / `Adjustments (approve)` entries)

## Phase 3 — Section-key housekeeping and V180

### Goal
Retire the four deprecated `SidebarSectionKey` enum values and clean up orphan `tenant_sidebar_section_config` rows.

### Changes

**Modify:**
- `services/java/shared/src/main/java/com/medfund/shared/sidebar/SidebarSectionKey.java:75-80` — delete the four values (`REPORTING_FACULTATIVE_BROWSE`, `REPORTING_FACULTATIVE_QUEUE`, `REPORTING_ADJUSTMENTS_DRAFT`, `REPORTING_ADJUSTMENTS_APPROVE`), add two new values:
  ```
  REPORTING_FACULTATIVE              ("Facultative reinsurance",     SidebarGroup.REPORTING),
  REPORTING_COMMISSION_CORRECTIONS   ("Commission corrections",      SidebarGroup.REPORTING),
  ```
  (Position them near where the deprecated values were, alphabetically or by insertion order to match the file's existing convention.)
- `services/java/shared/src/test/java/com/medfund/shared/sidebar/SidebarSectionKeyTest.java` — update the value-count assertion (currently `assertEquals(43, values.length)` per prior implementation — verify at implementation time) to reflect the new count (43 - 4 + 2 = **41**). No other test changes needed; the "unique values" and "no key in OVERVIEW" invariants continue to hold.

**Add:**
- `services/java/tenancy-service/src/main/resources/db/migration/public/V180__drop_deprecated_sidebar_section_keys.sql`:
  ```sql
  -- Section keys REPORTING_FACULTATIVE_BROWSE / _QUEUE and
  -- REPORTING_ADJUSTMENTS_DRAFT / _APPROVE were retired when
  -- the corresponding operations-portal pages were merged in
  -- 2026-09. Delete tenant rows referencing them so the admin
  -- sidebar-visibility grid stays consistent with the enum.
  DELETE FROM public.tenant_sidebar_section_config
   WHERE section_key IN (
     'REPORTING_FACULTATIVE_BROWSE',
     'REPORTING_FACULTATIVE_QUEUE',
     'REPORTING_ADJUSTMENTS_DRAFT',
     'REPORTING_ADJUSTMENTS_APPROVE'
   );
  ```

### Success criteria

**Automated:**
- `make test-java` passes (SidebarSectionKeyTest updated count)
- `cd services/java && ./gradlew :tenancy-service:build` succeeds
- `make tenancy` starts cleanly and applies V180 (check log or `docker exec medfund-postgres psql -U medfund -d medfund -c "SELECT version FROM public.flyway_schema_history WHERE version = '180'"`)

**Manual (browser):**
- On a tenant that had disabled `Adjustments (approve)` before this branch: after login, the sidebar shows `Commission corrections` (intended: the merged entry is a new section-key, so "absent row = enabled" default applies)
- Admin sidebar-visibility grid at `/tenant/admin/settings` → Profile → Sidebar Visibility shows `Facultative reinsurance` and `Commission corrections` under the Reporting group, and does not show the four old entries

## Deviations

**2026-09-06 — Status filter exposes all four terminal + open states.**
Plan said "COMMITTED and VOIDED as historical filters" was optional. Verified backend
`CommissionAdjustmentController.queue` at
`services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/CommissionAdjustmentController.java:74-85`
accepts any of `DRAFT / APPROVED / COMMITTED / VOIDED` as a single-status filter. Exposed all
five options (All open + four statuses) in the merged page's toolbar dropdown.

**2026-09-06 — Corrections table always shows the Detail link column even for view-only users.**
Plan was silent. Kept the "Detail" link as a non-privileged navigation aid so read-only users
can drill into a correction (the detail page renders the timeline read-only for them).

**2026-09-06 — Inline four-eyes hint added only when the user has draft-perm without approve-perm.**
Plan called for a "small inline hint under the header explaining the four-eyes flow". Rendered
as `.hint-banner` only when `canDraft && !canApprove` (the case the hint is written for). Users
with both permissions or with only approve don't need the disambiguating message.

**2026-09-06 — Facultative queue tab hides the entire Actions column for view-only users.**
Plan said row actions were permission-gated but not what to do with the header cell. Hiding the
`<th>Actions</th>` when `canAct === false` keeps the read-only queue visually clean rather
than leaving an empty column.

**2026-09-06 — Angular class/interface renames skipped per G6b.**
The `CommissionAdjustment*` types, service methods, and internal template variables stay named
after the wire (Adjustment / AdjustmentStatus / AdjustmentType / commission-adjustment.service).
Only user-visible strings and route segments say "correction". This is a permanent UI-vs-
internals vocabulary split, called out in the corrections-page component JSDoc.

**2026-09-06 — SidebarSectionKeyTest needed no changes.**
Plan speculated "assertEquals(43, values.length)" would need updating. Verified the actual test
suite uses invariants (uniqueness, non-blank labels, group coverage) — no value-count assertion
exists. Enum count changed 43 → 41 with no test change needed.

**2026-09-06 — Two pre-existing test failures on the branch (not caused by this plan).**
`TenantMigrationFlywayIT.v102_backfillsLegacyPolicies_toLegacyNoPremiumWithMiscPortfolio` and
`.v111_to_v113_backfill_seedsHistoryRowsFromCurrentState` both fail with
`FlywayException: No migration with a target version 101/110 could be found`. These target
tenant-schema migrations V101 and V110, but the tenant migration set at
`services/java/tenancy-service/src/main/resources/db/migration/tenant/` jumps from V099 to V200
(historical branch drift, not this branch). Recorded here as a known follow-up; my sidebar-key
and V180 changes touch only public-schema state and have zero interaction with these tests.

**2026-09-06 — jacocoTestCoverageVerification fails at 62% (threshold 70%).**
The failure is on the aggregate `shared` bundle coverage, not on this plan's touched files
(`com.medfund.shared.sidebar` measures at 100% instructions / 100% branches). Pre-existing
project-wide coverage regression; not introduced by this plan. `make test-java` (which is what
the plan gates on) passes because it runs unit tests only and does not trigger the coverage
threshold.

## Acceptance-criteria checklist

**Phase 1**
- [x] `FacultativePageComponent` renders both tabs when permitted
- [x] Default tab per permission-set matches G3
- [x] Route guard rejects users with none of the three perms
- [x] Old `browse` / `queue` route paths return not-found
- [x] Sidebar has exactly one `Facultative reinsurance` entry
- [x] `make test-angular` green (5 new specs pass)
- [ ] Manual browser verification per Phase 1 criteria

**Phase 2**
- [x] `CorrectionsPageComponent` renders single view with status filter
- [x] `New correction` button gated on drafter perm
- [x] Row actions gated on approver perm
- [x] View-only users see read-only list
- [x] Terminal-status rows have no actions
- [x] Old `adjustments/draft` / `adjustments/approve` paths return not-found
- [x] `permissions.ts` labels/descriptions updated (keys unchanged)
- [x] `make test-angular` green (8 new specs pass)
- [ ] Manual browser verification per Phase 2 criteria

**Phase 3**
- [x] `SidebarSectionKey` enum has two new values, four old removed
- [x] `SidebarSectionKeyTest` value-count assertion (verified: none needed, see Deviations)
- [x] V180 migration file present and applies cleanly (already applied to local Postgres)
- [x] `make test-java` green (sidebar test suites pass; two pre-existing IT failures unrelated, see Deviations)
- [ ] Manual: sidebar-visibility admin UI reflects the new keys
