---
date: 2026-09-06T19:33:27+02:00
researcher: methuseli
git_commit: 3df1c1677e199726b08ca24dad3b6ef4bbe68fe0
branch: rename-adjustments-to-notes
repository: medfund
topic: "Tenant-configurable enable/disable of operations-portal sidebar sections"
tags: [research, codebase, angular, tenancy-service, sidebar, tenant-configuration]
status: complete
last_updated: 2026-09-06
last_updated_by: methuseli
---

# Research: Tenant-configurable enable/disable of operations-portal sidebar sections

**Date**: 2026-09-06T19:33:27+02:00 · **Researcher**: methuseli · **Commit**: 3df1c1677 · **Branch**: rename-adjustments-to-notes

## Research Question

The operations portal sidebar exposes a large number of links. Some tenants will not use some of them. We need a way for tenants to enable and disable sections or functions they do not need. What does the current setup already provide, what are the closest precedents to build on, and what design decisions does a plan for this feature have to resolve?

## Summary

The sidebar is a filtered projection of a static, code-owned nav (`OPERATIONAL_NAV`, 8 groups / 44 items). Filtering already runs against **three separate tenant-configurable sources**:

1. RBAC permissions (per-user; `PermissionService`)
2. Feature flags (per-tenant JSON on `tenants.settings`: `insuranceLines`, `membershipModel`, `drugClaimsEnabled`)
3. Report-catalogue toggles (per-tenant rows in `public.tenant_report_config`; hides items whose `reportKey` is disabled)

The report-catalogue mechanism is the **closest precedent** and the recommended template: dedicated public table, "absent row = enabled" default, bulk-upsert REST endpoint, dedicated tenant-admin tab, single set of disabled-keys hydrated into the sidebar. Extending this pattern to a `SidebarSectionKey` catalogue lets tenants disable individual items (and, via empty-group auto-collapse, whole sections) without any code change to the nav config.

The main design forks a plan has to resolve are: **grain** (per-item vs per-group), **section-key naming** (explicit key vs route-derived), **backend enforcement** (frontend-only hide vs 403 gate like `@RequiresReport`), and **defaults for newly shipped items** (enabled vs disabled).

## Findings

### 1. The nav model and how it is filtered today

**Static config** (`clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:72-197`) — `OPERATIONAL_NAV` is a hand-written const of 8 groups:

| Group | Items |
|-------|-------|
| Overview | 1 |
| Billing | 10 |
| Policies | 6 |
| Members | 2 |
| Claims | 7 |
| Finance | 9 |
| Reporting | 9 |

Each item shape (`OperationalNavItem`, lines 27-52) carries these optional gating fields:

- `permissions?: PermissionKey[]` — ANY-of; empty = always visible.
- `reportKey?: string` — tenant report-catalogue toggle. Mirrors the Java `@RequiresReport` annotation.
- `featureFlag?` — a closed union: `drugClaims | ageGroupsAvailable | groupsAvailable | vehiclesAvailable | propertiesAvailable | lifeAvailable | funeralAvailable | travelAvailable | disabilityAvailable | preauthAvailable`.
- `exactMatch?: boolean` — routing-only, unrelated to visibility.

There is **no** `insuranceLine`, `license`, `subscriptionTier`, or explicit `sectionKey` field.

**The filter pipeline** (`operational-sidebar.component.ts:103-120`) rebuilds visible groups whenever permissions or the tenant snapshot change:

```
OPERATIONAL_NAV
  .map(group => group.items
      .filter(allowed)              // permission ANY-of via PermissionService.hasAny
      .filter(featureFlagPasses)    // switch over featureFlag union, reads TenantService snapshot
      .filter(reportEnabled))       // rejects if item.reportKey ∈ disabledReportKeys
  .filter(group => group.items.length > 0)   // empty groups collapse
```

Two important properties fall out of this:

- **Empty groups auto-collapse** (line 119). A per-item toggle mechanism can already deliver "hide the whole section" simply by disabling every item in it — no group-level gating needed.
- **Fail-closed on unknown feature flags** (line 165). Typos hide the item until fixed.

### 2. Existing tenant-configurable sources that trim the sidebar

**Feature flags via `tenants.settings` JSONB.** All 10 flags in the union resolve against the Angular `TenantService.tenant$` snapshot, which hydrates from `GET /api/v1/tenants/{tenantId}` on bootstrap (`clients/angular/src/app/auth/tenant-bootstrap.ts:33-60`; entity at `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/Tenant.java:38-39`). Resolution logic:

- `insuranceLines` (array in settings JSON) drives Vehicles / Properties / Life / Funeral / Travel / Disability / Age Groups / Pre-Auth availability. Asset-only tenants already never see person-centric items.
- `membershipModel` (column) drives Groups visibility — `INDIVIDUAL_ONLY` tenants never see the Groups row.
- `drugClaimsEnabled` (settings JSON) drives drug-claims items.

**Report catalogue toggles** (`services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/TenantReportConfig.java`; migration `V130__tenant_report_config.sql`) — dedicated table `public.tenant_report_config(tenant_id, report_key, enabled)` with UNIQUE `(tenant_id, report_key)` and a partial index on disabled rows. Endpoints:

- `GET /api/v1/tenants/{tenantId}/report-config` — list merged with catalogue defaults.
- `PUT /api/v1/tenants/{tenantId}/report-config` — bulk-upsert.
- `GET /api/v1/tenants/{tenantId}/report-config/enabled/{reportKey}` — point lookup for cross-service checks.

Angular consumer: `TenantReportConfigService` (`clients/angular/src/app/core/services/tenant-report-config.service.ts:11-49`), hydrated in the sidebar `ngOnInit` (`operational-sidebar.component.ts:81-95`) into `disabledReportKeys: Set<string>`. Absent row = enabled by default, so newly-added report items ship visible without any migration.

Backend enforcement of the same key set lives in the shared `@RequiresReport` annotation — the sidebar hides items whose backend would 403 anyway. This is the alignment property we would want to preserve for any new toggle.

**Permissions** (`clients/angular/src/app/core/security/permission.service.ts`, permission registry at `clients/angular/src/app/core/security/permissions.ts`) are per-user (not per-tenant), delivered on the JWT via a Keycloak protocol mapper (`GET /api/v1/me/permissions`). Super-admins bypass all checks (line 51).

### 3. Report-catalogue pattern — the concrete template

This is the pattern to copy. Concrete pieces:

| Concern | Report catalogue | For sidebar sections |
|---|---|---|
| Domain of allowed keys | `ReportKey` enum in Java (`services/java/shared`) | New `SidebarSectionKey` enum |
| Storage | `public.tenant_report_config(tenant_id, report_key, enabled)` + partial index on `enabled=false` | `public.tenant_sidebar_section_config(tenant_id, section_key, enabled)` |
| Default when row absent | Enabled | Enabled (recommendation — see forks) |
| REST | List, bulk PUT, point lookup | Same three endpoints |
| Angular hydration | `TenantReportConfigService.list()` on tenant switch → `disabledReportKeys: Set` | New `TenantSidebarConfigService` → `disabledSectionKeys: Set` |
| Sidebar filter | Extra `.filter(reportEnabled)` step | Extra `.filter(sectionEnabled)` step |
| Backend enforcement | `@RequiresReport` annotation, 403 on hit | Optional — route guard on the Angular side; or an equivalent annotation on the Java routes served by disabled sections |
| Admin UI | Reports tab under `/tenant/admin/settings` | New tab under `/tenant/admin/settings` |

**Why this shape wins over the JSONB-flag route:** the report-catalogue table gives you a clean audit trail on `updated_by`/`updated_at`, cheap indexed lookups, enum-validated keys at the API layer (400 on unknown key rather than silent write), and the "absent row = enabled" default lets new items ship without a per-tenant migration.

### 4. Sibling sidebars

- `TenantSidebarComponent` (`clients/angular/src/app/layout/tenant-sidebar/tenant-sidebar.component.ts`) — the IT-admin console at `/tenant/admin/*`. Nav is hardcoded; no permission or feature-flag gating on individual items. Not in scope for this ask, but if we ship section-toggling as a general capability, this is the second candidate surface.
- No super-admin, provider, or member sidebars in the current Angular codebase — those portals are still scaffolded per `portals.md`.

### 5. What the architecture docs say

**`portals.md:783-789`** already describes exactly the mechanism this feature wants to formalize:

> Navigation — only shows portals the user can access
> `showClaims = computed(() => this.perms.canAccessPortal('claims'));`

And **`portals.md:664`**:

> The Angular app dynamically shows/hides navigation items and portal sections based on the user's effective permissions.

The docs describe the current pattern as **permission-driven** — there is no design-level notion of a per-tenant "we don't want this section visible even though our users have the permission" toggle. That is the gap this feature fills.

**`portals.md:846-849`** explicitly enumerates what is NOT customizable today:

> - Page structure, navigation items, component behavior — these are platform-defined

So this feature is a real doc-level expansion, not just an implementation cleanup. The plan should update `portals.md` alongside the code.

**`multi-tenancy.md:196-201`** mentions **super-admin-level** feature flags ("Enable/disable features per tenant") and **plan-based** feature access on `public.plans.features` JSONB. Neither is wired into the sidebar today — the only thing that references `plans.features` is the entity, no consumers. Worth deciding whether this new toggle sits **below** plan-level (super admin sets what a plan can even offer, tenant admin sets what they actually want to see) or **replaces** it. See the forks section.

## Architecture doc vs. code

Where docs and code agree:
- Permission-based nav filtering is described in `portals.md:783-789` and matches `operational-sidebar.component.ts:191-194`.
- Multi-tenancy schema-per-tenant model in `multi-tenancy.md:255-296` matches `Tenant.java` and `V101__tenants.sql`.

Where docs and code have drifted:
- `portals.md:114-190` enumerates Operations Portal routes under `/claims/*`, `/finance/*`, `/contributions/*`. The actual routes are `/tenant/claims/*`, `/tenant/finance/*`, `/tenant/billing/*` (contributions was absorbed under billing). Cosmetic drift but worth a doc pass while we are in this area.
- `portals.md:846-849` says navigation is not tenant-customizable. `insuranceLines`, `membershipModel`, and `drugClaimsEnabled` already customize it — the doc is behind the code.
- `multi-tenancy.md:196-201` mentions super-admin per-tenant feature flags. No such controller exists in `tenancy-service`. Not blocking this work, but if we build tenant-level toggles, the super-admin story becomes a legit design decision.

## Code References

- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:27-52` — nav item shape and gating fields
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:72-197` — the static nav (8 groups, 44 items)
- `clients/angular/src/app/layout/operational-sidebar/operational-sidebar.component.ts:103-120` — filter pipeline
- `clients/angular/src/app/layout/operational-sidebar/operational-sidebar.component.ts:128-131` — `reportEnabled` filter
- `clients/angular/src/app/layout/operational-sidebar/operational-sidebar.component.ts:138-167` — `featureFlagPasses` switch
- `clients/angular/src/app/core/security/permission.service.ts:51,60-71,84` — permission checks + super-admin bypass
- `clients/angular/src/app/core/services/tenant-report-config.service.ts:11-49` — closest precedent client service
- `clients/angular/src/app/core/services/tenant.service.ts:13` — tenant snapshot shape
- `clients/angular/src/app/auth/tenant-bootstrap.ts:23,33-60` — how the tenant snapshot is loaded
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/Tenant.java:38-39` — `settings` JSONB
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/TenantReportConfig.java` — precedent entity
- `services/java/tenancy-service/src/main/resources/db/migration/public/V130__tenant_report_config.sql` — precedent migration
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantReportConfigController.java:50,64,75` — precedent REST
- `.claude/portals.md:664,783-789,846-849` — what the doc says about nav customization
- `.claude/multi-tenancy.md:196-201,255-296` — plan-based feature flags (unused) and tenants schema

## Design forks a plan will need to resolve

Each one is a genuine fork with evidence on both sides. Not open questions for the archive — these are the decisions a `create-plan` run has to lock down.

- **Grain: per-item vs per-group vs both.**
  Per-item mirrors the report-catalogue pattern exactly (small, additive, empty groups already auto-collapse so "hide the whole section" is a bulk toggle). Per-group is fewer clicks for the tenant admin but requires either a group-key on the config (new concept) or defining the group as the union of its items in the enum. **Recommendation: per-item, with the admin UI grouping toggles by nav-group for UX**.

- **Section-key naming: explicit `sectionKey` on the nav item vs derived from route.**
  Explicit keeps the key stable across route renames (routes have changed twice already — `/adjustments` → `/notes`). Route-derived is zero-config but breaks on any route change. Backend annotations for enforcement need a stable key regardless. **Recommendation: explicit `sectionKey?: string` field on `OperationalNavItem`, validated against a `SidebarSectionKey` enum at admin-write time.**

- **Backend enforcement: frontend-only hide vs 403 on the disabled route.**
  Frontend-only is faster to ship and covers the actual ask (visual clutter). 403 enforcement matches the `@RequiresReport` model and prevents deep-linking to disabled sections. Frontend-only leaves bookmarks and shared links working, which some tenants may actually want (staff who need the disabled feature once a quarter). **Recommendation: frontend-only in phase 1; if a tenant later needs hard enforcement, add an `@RequiresSidebarSection` annotation modeled on `@RequiresReport`.**

- **Defaults when a new item ships: enabled or disabled by default.**
  Enabled by default (absent row = enabled) matches the report-catalogue pattern; new features are discoverable without admin action. Disabled by default is safer for lines-of-business tenants who care about visual noise but risks tenants never noticing new capability. **Recommendation: enabled by default, mirroring report-config.**

- **Where the tenant-admin UI lives.**
  Options: (a) new dedicated tab `/tenant/admin/settings/sidebar-sections`, (b) extend the Reports tab into a general "Portal Visibility" tab (rename), (c) merge into the general Settings tab. (a) is the cleanest; (b) risks over-loading the Reports concept; (c) doesn't scale. **Recommendation: (a).**

- **Interaction with the existing three gates.**
  Order of evaluation matters when reporting to the admin "this item is hidden because X". Today the sidebar just silently drops the item. If we add a fourth gate, the admin UI should show, per item, *why* it is currently invisible for at least one user (missing permission / disabled by feature flag / disabled by tenant / disabled by report toggle). **Recommendation: keep filter order as-is (permission → featureFlag → report → new section-toggle) and surface the last blocker in the admin UI as a hint.**

- **Do we let tenants disable the *groups* themselves — rename or reorder them?**
  Rename and reorder are scope-creep from the ask. The ask is enable/disable. **Recommendation: out of scope; document as follow-up if the ask surfaces.**

- **Super-admin plan-level pre-gating** (`public.plans.features` mentioned in `multi-tenancy.md:283-295`).
  If a plan doesn't include a feature, should the tenant admin be able to see the toggle at all? Today `plans.features` is unused. Adding plan-level gating on top of tenant-level is a real product decision — it turns the sidebar into a two-level opt-in. **Recommendation: ignore plans.features in phase 1; the disabled column on `tenant_sidebar_section_config` is enough for the immediate ask, and we can layer plans on later without a schema change.**

## Architecture Insights

- **The sidebar filter pipeline is the enforcement surface most tightly coupled to tenant data.** Extending it is safe (empty-groups collapse gracefully); introducing enforcement at the backend requires touching every controller for a disabled section. That asymmetry is why frontend-only phase 1 is a defensible starting point despite Critical Rule #2 ("every DB query tenant-scoped") — the *data* is already tenant-scoped, we are only choosing what the client renders.
- **Rule 8 (audit-log every mutation) applies here.** Toggle flips are entity mutations. The `TenantReportConfig` bulk-upsert already emits audit events via the shared audit publisher — the new endpoint should follow the same pattern (`AuditActor` helper, friendly `entityName` per memory `feedback_audit_entity_name`, `actorEmail` populated per `feedback_audit_actor_email`).
- **Rule 7 (Swagger) applies.** The bulk PUT and list endpoints must ship with complete OpenAPI schemas including example payloads.
- **`portals.md:846-849` will need an update.** The doc currently asserts navigation is not tenant-customizable, but this feature (and the existing feature-flag machinery) makes that assertion stale.

## Historical Context (from thoughts/shared/)

No prior research in `thoughts/shared/research/` on sidebar customization, tenant feature flags, or the report-config table. This is greenfield territory in that sense — the report-config work landed straight into code without a research doc predating it.

## Related Research

None yet under `thoughts/shared/research/`.

## Open Questions

- **Does the tenant admin who disables a section want visibility on which users would still see it if enabled?** (i.e., "3 users hold `finance.commission:view` — enabling Producer Payouts would show it to them.") Nice-to-have discoverability, not blocking.
- **Do we want an "all or nothing per group" bulk toggle in the admin UI, on top of per-item toggles?** UX detail for the admin surface.
- **What is the migration story for tenants whose existing `tenant_report_config` rows disable an item that now also has a `sectionKey`?** Do we consolidate the two mechanisms or run them side-by-side? (Side-by-side is simpler; consolidation is cleaner but touches every existing `reportKey` item.)

---

Research written to `thoughts/shared/research/2026-09-06-tenant-configurable-operations-sidebar.md`.

Clear your context, then run:
  create-plan thoughts/shared/research/2026-09-06-tenant-configurable-operations-sidebar.md
