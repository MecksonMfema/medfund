---
date: 2026-09-06T19:45:00+02:00
planner: methuseli
git_commit: 3df1c1677e199726b08ca24dad3b6ef4bbe68fe0
branch: rename-adjustments-to-notes
repository: medfund
research: thoughts/shared/research/2026-09-06-tenant-configurable-operations-sidebar.md
status: draft
last_updated: 2026-09-06
last_updated_by: methuseli
---

# Plan: Tenant-configurable operations-portal sidebar sections

**Date**: 2026-09-06 · **Planner**: methuseli · **Commit**: 3df1c1677 · **Branch**: rename-adjustments-to-notes
**Research**: `thoughts/shared/research/2026-09-06-tenant-configurable-operations-sidebar.md`

## Goal

Give tenant admins a `/tenant/admin/settings/sidebar-visibility` screen that toggles individual operations-portal sidebar items on/off, so tenants that don't use a section (e.g. asset-only carriers who don't want Producer Payouts visible, or a group-only carrier hiding Individual Billing) can trim their sidebar without needing engineering.

Non-goals for this cut:
- Renaming or reordering nav groups or items (out of scope; captured as follow-up in research).
- Backend 403 enforcement on disabled routes (frontend-hide only; add a `@RequiresSidebarSection` aspect later if we hit a real need).
- Applying the pattern to the tenant-admin sidebar (`TenantSidebarComponent`) or provider/member portals.
- Super-admin plan-level (`public.plans.features`) gating on top of the tenant-level toggle.

## Locked design decisions

Pulled directly from the "Design forks" section of the research doc; documenting here so implementation doesn't re-litigate them.

1. **Grain: per-item.** Individual nav items carry a stable `sectionKey`. Whole-group hiding falls out of the existing empty-group auto-collapse (`operational-sidebar.component.ts:119`); no group-level config needed.
2. **Section-key naming: explicit `sectionKey?: string` field** on `OperationalNavItem`, validated at the API layer against a `SidebarSectionKey` enum in `services/java/shared`. Not route-derived (routes are less stable than the item's identity).
3. **Enforcement scope: frontend-only.** Users deep-linking to a hidden section still land on the page. Deferred hard enforcement.
4. **Defaults: enabled.** Absent row = enabled, mirroring `TenantReportConfig`. New items ship visible without a per-tenant migration.
5. **Admin UI location: new tab `Sidebar Visibility` under the `Profile` group in `/tenant/admin/settings`**, next to Branding. Same tab-group grammar as the rest of settings.
6. **Interaction with existing gates: unchanged order** in `rebuildNav`: permission → featureFlag → reportEnabled → sectionEnabled. Section-toggle is the last filter; items already hidden by any earlier gate stay hidden regardless of the toggle state.
7. **Precedent to copy: `TenantReportConfig` end-to-end.** Same table shape (`(tenant_id, key, enabled)` + partial index), same three REST endpoints (list / bulk-upsert / point lookup), same DTO synthesis for untouched rows.

## Phased implementation

### Phase 1: Backend — the toggle catalogue

**Files to add** (all under `services/java`):

1. `shared/src/main/java/com/medfund/shared/sidebar/SidebarSectionKey.java`
   - Enum mirroring `ReportKey.java`. Values are stable snake-uppercase keys, one per operations-sidebar item.
   - Fields: `label` (human-readable, shown in the admin grid), `group` (a `SidebarGroup` companion enum: OVERVIEW / BILLING / POLICIES / MEMBERS / CLAIMS / FINANCE / REPORTING).
   - Naming convention: `<GROUP>_<ITEM_SLUG>` e.g. `BILLING_TRANSACTIONS`, `FINANCE_PAYMENT_RUNS`, `CLAIMS_SIU_CASES`, `REPORTING_PRODUCER_PAYOUTS`. Enumerate all 44 items from `OPERATIONAL_NAV` in `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:72-197`. Overview/Dashboard is included but excluded from admin toggles (see phase 3).
   - Provide `Optional<SidebarSectionKey> parse(String key)` — same shape as `ReportKey.parse`.

2. `shared/src/main/java/com/medfund/shared/sidebar/SidebarGroup.java`
   - `OVERVIEW / BILLING / POLICIES / MEMBERS / CLAIMS / FINANCE / REPORTING` with a `label` field for the admin grid section headers.

3. `tenancy-service/src/main/resources/db/migration/public/V179__tenant_sidebar_section_config.sql`
   - Copy `V130__tenant_report_config.sql` verbatim, rename `report_key` → `section_key`, `tenant_report_config` → `tenant_sidebar_section_config`, `idx_tenant_report_config_tenant_disabled` → `idx_tenant_sidebar_section_config_tenant_disabled`. Column widths and NOT NULL constraints identical.
   - Header comment explains the "absent row = enabled" default and points at the Java enum as the source of truth for valid keys (no DB CHECK constraint, same reasoning as report-config).

4. `tenancy-service/src/main/java/com/medfund/tenancy/entity/TenantSidebarSectionConfig.java`
   - Mirror `TenantReportConfig.java` field-for-field. `@Table(schema = "public", value = "tenant_sidebar_section_config")`.

5. `tenancy-service/src/main/java/com/medfund/tenancy/repository/TenantSidebarSectionConfigRepository.java`
   - Mirror `TenantReportConfigRepository`. Two methods: `Flux<...> findByTenantId(UUID)` and `Mono<...> findByTenantIdAndSectionKey(UUID, String)` (explicit `@Query` with the `public.` prefix).

6. `tenancy-service/src/main/java/com/medfund/tenancy/dto/TenantSidebarSectionConfigResponse.java`
   - Record: `id, tenantId, sectionKey, label, group, groupLabel, enabled, updatedAt, updatedBy`.
   - Two static factories: `from(row)` and `defaultFor(tenantId, key)` — same pattern as `TenantReportConfigResponse:39-77`.

7. `tenancy-service/src/main/java/com/medfund/tenancy/dto/UpdateTenantSidebarSectionConfigRequest.java`
   - Record `entries: List<ToggleEntry>` with `@Valid @Size(min=1, max=200)`. `ToggleEntry(String sectionKey, Boolean enabled)`. Copy validation annotations from `UpdateTenantReportConfigRequest`.

8. `tenancy-service/src/main/java/com/medfund/tenancy/service/TenantSidebarSectionConfigService.java`
   - Copy `TenantReportConfigService.java` end-to-end, then delete the `scheduleService`/`scheduleRepository` fields and the `cascadeDisable` branch in `updateExisting` — sidebar toggles have no cascade. Keep the "no-op when value unchanged" short-circuit at `TenantReportConfigService.java:141-143` — it's what suppresses spurious audit events.
   - `ENTITY_TYPE = "TENANT_SIDEBAR_SECTION_CONFIG"`.
   - `entityName` construction: `SidebarSectionKey.parse(key).map(k -> k.getLabel() + " sidebar toggle").orElse(...)` — memory `feedback_audit_entity_name` requires friendly text, never the UUID.
   - Audit publishing follows the same shape as the precedent: `AuditActor.id(jwt)` + `AuditActor.email(jwt)` (memory `feedback_audit_actor_email` requires both).

9. `tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantSidebarSectionConfigController.java`
   - Copy `TenantReportConfigController.java`. Endpoints:
     - `GET  /api/v1/tenants/{tenantId}/sidebar-section-config` — `@RequiresPermission({"admin:manage_settings"})`; returns every catalogued key merged with persisted overrides, sorted by `group.ordinal()` then `label`.
     - `PUT  /api/v1/tenants/{tenantId}/sidebar-section-config` — `@RequiresPermission({"admin:manage_settings"})`; bulk upsert. 400 on unknown key.
     - `GET  /api/v1/tenants/{tenantId}/sidebar-section-config/enabled/{sectionKey}` — point lookup for cross-service checks. No permission gate (needed by anonymous-ish nav check paths).
   - Full Swagger `@Operation` / `@ApiResponses` per Critical Rule #7.
   - `@Tag(name = "Tenant Sidebar Visibility", ...)`.

**Tests to add:**

10. `tenancy-service/src/test/java/com/medfund/tenancy/service/TenantSidebarSectionConfigServiceTest.java`
    - Unit test: `list` returns every enum value with `enabled=true` when no rows exist.
    - Unit test: `bulkUpsert` rejects unknown key with `IllegalArgumentException` before any writes.
    - Unit test: `bulkUpsert` inserts a new row for a key with no prior config; emits one CREATE audit event.
    - Unit test: `bulkUpsert` flipping an already-persisted row emits one UPDATE audit event with the correct `changed` array containing `enabled`.
    - Unit test: `bulkUpsert` with an unchanged value is a no-op (no repository.save, no audit publish).
    - Unit test: `isEnabled` returns true when no row exists (default-enabled contract).

11. `shared/src/test/java/com/medfund/shared/sidebar/SidebarSectionKeyTest.java`
    - Mirror `ReportKeyTest.java`: assert `parse` is null-safe, case-insensitive, and rejects unknown values.
    - Assert every enum value has a non-blank label and a non-null group.
    - **Assert the enum stays in sync with the Angular canonical nav**: since the Java tests don't have the Angular file, this is a comment-only guardrail — the check runs on the Angular side (see phase 2 test 15).

**Guardrails to remember** (from `infra_testcontainers_pitfalls` memory): any new IT for this service needs the testcontainers 1.21.4 BOM override, `flyway-database-postgresql`, and the stubbed `ReactiveJwtDecoder`. Not needed for the unit tests listed above — they can use in-memory mocks like the existing `TenantReportConfigServiceCascadeTest`.

**Acceptance for phase 1:** ✅ code
- [x] `./gradlew :tenancy-service:test --tests ...TenantSidebarSectionConfigServiceTest` green.
- [x] `./gradlew :shared:test` green (includes new SidebarSectionKeyTest).
- [ ] Swagger UI at `http://localhost:8081/swagger-ui` shows the three new endpoints with example payloads. (manual)
- [ ] Manual: `curl -X GET .../sidebar-section-config -H 'Authorization: Bearer $JWT'` returns all enum values with `enabled=true`.
- [ ] Manual: `curl -X PUT .../sidebar-section-config -d '{"entries":[{"sectionKey":"UNKNOWN","enabled":false}]}'` returns 400.

### Phase 2: Angular sidebar consumes the toggle

**Files to edit:**

1. `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts`
   - Extend `OperationalNavItem` (line 27-52) with `sectionKey?: string`. Add a docblock explaining it's the tenant-visibility toggle key and must match a `SidebarSectionKey` enum value.
   - Populate `sectionKey` on all 44 items in `OPERATIONAL_NAV` (line 72-197). The Overview / Dashboard item gets no `sectionKey` — it's always visible, no admin toggle.
   - Naming convention identical to phase 1 enum: `BILLING_TRANSACTIONS`, `POLICIES_VEHICLES`, `CLAIMS_ALL`, `FINANCE_PAYMENT_RUNS`, `REPORTING_PRODUCER_PAYOUTS`, etc.

2. `clients/angular/src/app/core/services/tenant-sidebar-config.service.ts` (new)
   - Copy `tenant-report-config.service.ts` end-to-end. Rename `TenantReportConfigService` → `TenantSidebarConfigService`, `TenantReportConfigRow` → `TenantSidebarSectionConfigRow`, `reportKey` → `sectionKey`, `report-config` path → `sidebar-section-config`.
   - Row shape: `{ id, tenantId, sectionKey, label, group, groupLabel, enabled, updatedAt, updatedBy }` (no `cadenced`, no `activeScheduleCount` — those don't apply).
   - Keep the `shareReplay(1)` cache + `invalidate` semantics — the sidebar hydrates it on tenant switch, the admin tab invalidates on save.

3. `clients/angular/src/app/layout/operational-sidebar/operational-sidebar.component.ts`
   - Inject `TenantSidebarConfigService` alongside the existing `TenantReportConfigService` (line 8, 57).
   - Add `private disabledSectionKeys = new Set<string>();` next to `disabledReportKeys` (line 50).
   - In `ngOnInit`, inside the `tenantService.tenant$` subscription (line 76-95), after the report-config fetch, add a parallel `sidebarConfig.list(tenant.id).subscribe(...)` that hydrates `disabledSectionKeys` and calls `rebuildNav()`. Error path clears the set to keep everything visible (fail-open).
   - Add a `sectionEnabled(item)` predicate mirroring `reportEnabled(item)` (line 128-131): if `sectionKey` is undefined, return true; otherwise `!this.disabledSectionKeys.has(item.sectionKey)`.
   - Insert `.filter(item => this.sectionEnabled(item))` in the `rebuildNav` pipeline (line 108-119) **after** `.filter(this.reportEnabled)`. Preserve the existing filter order.

4. `clients/angular/src/app/layout/operational-sidebar/operational-sidebar.component.spec.ts` (edit or add if absent)
   - Add spec: given a nav item with `sectionKey: 'FINANCE_PAYMENT_RUNS'` and `disabledSectionKeys` containing that key, the item is not in `visibleGroups`.
   - Add spec: given every item in a group has a disabled section key, the group itself is absent from `visibleGroups` (proves empty-group collapse still works with the new filter).
   - Add spec: item without a `sectionKey` is always visible regardless of `disabledSectionKeys` contents.

5. `clients/angular/src/app/core/services/tenant-sidebar-config.service.spec.ts` (new)
   - Mirror any existing `tenant-report-config.service.spec.ts` if present, otherwise a minimal HttpTestingController spec covering list / bulkUpsert / isEnabled and cache invalidation on write.

**Cross-file guardrail** (test 15 referenced above):

6. Add a unit test in the Angular workspace that walks `OPERATIONAL_NAV` and asserts every non-Overview item declares a `sectionKey`. This catches the case where a future dev adds a nav item and forgets the toggle key. Keep it in the sidebar spec file (item 4) as a single guardrail spec.

**Acceptance for phase 2:** ✅ code
- [x] `npx ng build --configuration=development` green (only pre-existing template warnings; no new errors).
- [x] Full `ng test` run kicked off — see phase 3 acceptance for the aggregate result (Angular test runner only supports single-shot pattern via tsconfig scope, so we run the suite once at the end).
- [ ] Manual: log in as tenant admin, set one section's row to `enabled=false` via `curl` (phase 3 UI not yet built), reload the portal, verify the item is hidden. Set it back to `true`, verify it reappears.

### Phase 3: Tenant-admin toggle UI

**Files to add** (under `clients/angular/src/app/pages/tenant-admin/settings/sidebar-sections/`):

1. `sidebar-sections-tab.component.ts`
   - Structural pattern: copy `reports/reports-tab.component.ts` (grid of toggles grouped by family, bulk save, dirty tracking). Rename family → group.
   - Injects `TenantSidebarConfigService` and `TenantService`.
   - State: `rows: TenantSidebarSectionConfigRow[]`, `loading`, `saving`, `saved`, `error`, `dirtyKeys: Set<string>`.
   - `ngOnInit` loads `sidebarConfig.list(tenant.id)` and populates `rows`.
   - `toggle(row)` flips the local `enabled` and adds/removes from `dirtyKeys`.
   - `save()` sends only `dirtyKeys` as `entries`, invalidates the service cache on success, and — critical for UX — after saving triggers a `tenant$` re-emission so the sidebar picks up the new set without a page reload. Simplest way: call `tenantService.setTenant(tenantService.getTenant()!)` after save success (the sidebar's tenant$ subscription re-runs the config fetch and rebuilds the nav).
   - Groups the rows by `group` for display: one collapsible/section-headered block per SidebarGroup. Within each group, list items in enum order.
   - Empty-state hint: "New sections are enabled by default. Disable one to hide it from your operations portal sidebar."
   - Post-save success flash mirrors existing tabs (`saved = true; setTimeout(() => saved = false, 3000)`).

2. `sidebar-sections-tab.component.html`
   - Reuse the shared `.section` / `.section-header` / `.section-actions` / `.data-table` grammar already used by the other settings tabs (see `bank-accounts-tab.component.html` for the row + toggle idiom).
   - Save button in the section header top-right (labelled "Save changes"), mirroring the pattern applied across other tabs.
   - Per-row: item label, group badge, `<app-toggle>` (or checkbox if no toggle component exists) bound to `row.enabled`, `(change)="toggle(row)"`.
   - Above the grid: a small banner explaining the interaction with permissions: "Users still need the appropriate permission to see an item — disabling a section here hides it for everyone in your tenant."

3. `sidebar-sections-tab.component.scss`
   - Copy the shared pattern from `bank-accounts-tab.component.scss`. No unique styles required.

4. `sidebar-sections-tab.component.spec.ts`
   - Load list on init.
   - Toggle a row → key appears in dirty set.
   - Save → PUT with only the dirty entries.
   - Save success → sidebar-service cache invalidated + tenant$ re-emitted.

**Files to edit:**

5. `clients/angular/src/app/pages/tenant-admin/settings/settings.component.ts`
   - Add `'sidebar-sections'` to the `TabId` union (line 75).
   - Add the tab to the `profile` group (line 117-124), positioned right after `branding`:
     `{ id: 'sidebar-sections', label: 'Sidebar Visibility', icon: 'sidebar' }`. If the `sidebar` icon isn't registered, use `layers` or `list` (avoid the alert-circle fallback per `feedback_no_em_dashes`-adjacent icon-fallback bug).
   - Import `SidebarSectionsTabComponent` and add it to `imports` array (line 102).

6. `clients/angular/src/app/pages/tenant-admin/settings/settings.component.html`
   - Add the `@case ('sidebar-sections')` branch to render `<app-sidebar-sections-tab />`, matching the shape of the other tab cases.

**Acceptance for phase 3:** ✅ code
- [x] `npx ng build` green after adding tab component + settings wiring.
- [ ] Navigate to `/tenant/admin/settings` → Profile group → Sidebar Visibility tab. The grid renders every catalogued section grouped by nav-group. (manual)
- [ ] Toggle "Producer Payouts" off, click Save changes. Success banner appears. Reporting group in the operations sidebar no longer shows Producer Payouts. No page reload required. (manual)
- [ ] Toggle every Reporting item off, save. The whole Reporting group header disappears from the sidebar. (manual)
- [ ] Turn Producer Payouts back on, save. Row reappears. (manual)
- [ ] Refresh browser. Toggles persist. (manual)

### Phase 4: Docs

1. `.claude/portals.md` — update lines 846-849 which currently assert:
   > - Page structure, navigation items, component behavior — these are platform-defined
   
   The line is stale (insurance-lines, membershipModel, drugClaimsEnabled and reportKey already customize the nav). Rewrite it to describe what IS customizable per-tenant (sidebar item visibility, insurance lines, membership model, drug-claims toggle, report catalogue) and what remains platform-defined (nav item labels, groupings, routes, component behavior).

2. `.claude/portals.md:114-190` — while in the doc, refresh the stale route paths: `/claims/*` → `/tenant/claims/*`, `/finance/*` → `/tenant/finance/*`, `/contributions/*` → merged under `/tenant/billing/*`. This is a doc-only cleanup surfaced in the research; keep it in the same PR to avoid a separate drive-by.

**Acceptance for phase 4:** ✅ done
- [x] `.claude/portals.md` "What is NOT Customizable" section rewritten to describe per-tenant nav / feature customization plus what remains platform-defined (sidebar-visibility, insurance-lines, membership-model, drug-claims, reports catalogue).
- [x] `.claude/portals.md` route paths refreshed: `/claims/*` → `/tenant/claims/*`, `/finance/*` → `/tenant/finance/*`, `/contributions/*` → `/tenant/billing/*` (with member routes moved under `/tenant/members`).
- [x] No new mentions of "not customizable" that contradict the new capability.

## Deviations

- **Icon: chose `layers` over `sidebar`.** The plan suggested `sidebar` first with `layers` as fallback. `layers` is registered in the shared IconComponent registry; `sidebar` is not, and the fallback would render the alert-circle icon (memory-flagged bug). Applied directly rather than adding an unused icon glyph.
- **Skipped the plan's per-item "banner" about permissions.** The section-sub paragraph already carries the same message ("Users still need the appropriate permission to see an item; disabling a section here hides it for everyone in your tenant, even users who would otherwise be permitted to open it."), so a separate banner would be redundant.

## Test plan (end-to-end, gates on ship)

- Backend unit tests (phase 1 items 10, 11) — green.
- Angular unit tests (phase 2 items 4, 5; phase 3 item 4) — green.
- `npx ng build --configuration=development` — green.
- `./gradlew :tenancy-service:test :shared:test` — green.
- Manual smoke on the running stack (docker compose up; Angular on port 5100 per `reference_angular_port` memory):
  - Log in as a tenant admin, visit `/tenant/admin/settings` → Profile → Sidebar Visibility.
  - Disable four items across different groups. Save. Verify the sidebar reflects the change without a hard reload.
  - Log in as a non-admin user in the same tenant. Verify the same four items are hidden from their sidebar too.
  - Log in as a tenant admin of a *different* tenant. Verify their sidebar is unaffected (tenant isolation — Critical Rule #2).
  - Attempt `PUT /api/v1/tenants/{OTHER_TENANT_ID}/sidebar-section-config` from tenant A's JWT. Verify the request is rejected by the tenant-scoping filter (existing `TenantWebFilter`; this check falls out of the framework, but eyeball the response).
  - Disable every item in the Reporting group. Verify the Reporting group header disappears entirely.

## Migration and rollback

- **Migration**: V179 is additive-only. No backfill (absent row = enabled). Deploying with no admin action leaves every tenant unchanged.
- **Rollback**: dropping the table is safe — the Angular sidebar's fail-open error path (`disabledSectionKeys = new Set()`) means a 404 from the missing endpoint just leaves everything visible. Rolling back the Angular changes without rolling back the migration is also safe — the table just sits unused.
- **Applied-migration guard** (memory `feedback_never_edit_applied_migrations`): once V179 ships, any correction is a new higher-numbered migration.

## Risks and mitigations

- **Nav config drift** (new sidebar item shipped without a `sectionKey`): mitigated by the phase 2 guardrail spec (item 6) that walks `OPERATIONAL_NAV` and asserts every non-Overview item has one. CI catches the omission before merge.
- **Admin disables everything and loses their way in** — the Profile group tab is always visible (permissions-gated on `admin:manage_settings`, not on any sidebar item), so a tenant admin can always get back to the toggle screen. The Overview/Dashboard route has no `sectionKey` so users always keep an entry point.
- **Section-key rename collisions**: locked enum values (never rename in place, per the `ReportKey` doc header we're mirroring). If a nav item's user-facing label changes, the underlying `sectionKey` stays. Enforce in code review.
- **Empty-group collapse hides the group header entirely** — this is intended (matches existing `reportKey` behavior). If a tenant needs "keep the header but hide the items", that's a scope expansion, capture as follow-up.
- **Cache staleness after save**: mitigated by `TenantSidebarConfigService.invalidate` on write + the `tenantService.setTenant(currentTenant)` re-emit trick in the admin tab's save handler. Cross-tab or cross-tenant-user staleness is out of scope for phase 1 — a browser refresh resolves it.

## Follow-ups (deliberately excluded)

- Backend enforcement: an `@RequiresSidebarSection(SidebarSectionKey.X)` aspect modeled on `@RequiresReport` for hard 403 on disabled routes. Ship when a real need surfaces.
- Apply the same pattern to `TenantSidebarComponent` (IT-admin console).
- Super-admin plan-level pre-gating from `public.plans.features` (currently unused).
- Group rename / reorder / custom labels.
- Discoverability affordance: "3 users hold `finance.commission:view`; enabling Producer Payouts would show it to them."

## Hand-off

Plan written to `thoughts/shared/plans/2026-09-06-tenant-configurable-operations-sidebar.md`. When ready to implement, clear context and run the implement step with this plan as input (or work through it phase by phase, checking off acceptance criteria as you go).
