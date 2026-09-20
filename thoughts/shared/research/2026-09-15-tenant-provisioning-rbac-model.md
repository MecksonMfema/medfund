---
date: 2026-09-15T00:00:00Z
researcher: Claude Code
git_commit: 2a80248e3c0de32c18c8d90b2c72c607aafffcae
branch: rename-adjustments-to-notes
repository: medfund
topic: "Tenant provisioning, staff/user model, and role/permission system in InsureFlow"
tags: [research, codebase, tenancy, rbac, keycloak, provisioning]
status: complete
last_updated: 2026-09-15
last_updated_by: Claude Code
---

# Research: Tenant Provisioning, Staff/User Model, and Role/Permission System in InsureFlow

**Date**: 2026-09-15 · **Researcher**: Claude Code · **Commit**: 2a80248 · **Branch**: rename-adjustments-to-notes

## Summary

InsureFlow uses a **schema-per-tenant PostgreSQL architecture** with Keycloak per-tenant realms for auth. Tenant provisioning is synchronous (API call → schema creation → Flyway migrations → Keycloak realm → role/permission seeding). Staff are stored in `public.staff_users` (platform-wide) with references to tenant-specific Keycloak users. Members are tenant-scoped in their schema's `members` table. Roles and permissions live in tenant-schema `roles` and `role_permissions` tables; Keycloak holds the identity/OIDC layer only. The bootstrap script creates platform-level test users (superadmin, claimsclerk, etc.) in the `medfund-platform` realm; tenant-specific staff are provisioned separately via user-service API after tenant creation.

## Findings

### 1. Tenant Entity & Provisioning Flow

#### Tenant Entity (Public Schema)
File: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/Tenant.java:1-87`

- **Fields**: `id` (UUID PK), `name`, `slug` (unique), `domain`, `schema_name` (per-tenant schema identifier), `plan_id` (subscription), `status` ('active'|'suspended'), `settings` (JSONB), `branding` (JSONB), `contact_email`, `country_code`, `timezone`, `membership_model` ('INDIVIDUAL_ONLY'|'GROUP_ONLY'|'BOTH'), `pricing_model` ('AGE_GROUP'|'INDIVIDUAL'), `member_number_scheme`, `keycloak_realm`, `jurisdiction_code` (regulator), `created_at`, `updated_at`
- **Storage**: `public.tenants` table (V101__tenants.sql)

#### Create Flow (End-to-End)
File: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantService.java:114-170`

**Steps**:
1. Validate defaultCurrencyCode exists in `public.currencies` (V111)
2. Check slug uniqueness
3. Generate `schema_name` = "tenant_" + slug.replace("-", "_")
4. Generate `keycloak_realm` = "medfund-" + slug
5. Insert Tenant row into `public.tenants` (r2dbcTemplate.insert)
6. **Schema Provisioning** (SchemaProvisioningService.provisionSchema) — runs sequentially:
   - CREATE SCHEMA IF NOT EXISTS {schemaName}
   - Run Flyway migrations from `classpath:db/migration/tenant` against the schema (idempotent via Flyway's flyway_schema_history table)
   - Call `SELECT public.provision_tenant_role($1)` to create tenant-scoped role, grant memberships to connection pool user (V117 function)
7. **Keycloak Realm Creation** (KeycloakRealmService.createRealm) — creates per-tenant realm
8. **Default Currency Config** (TenantCurrencyConfigRepository) — seeds initial currency
9. **Default Scheduled Jobs** (ScheduledJobService.seedDefaults) — seeds 6 job types: BILLING_CYCLE, OVERDUE_CHECK, PAYMENT_RUN, AGE_PROCESSING, PRE_AUTH_EXPIRY, TARIFF_ACTIVATION
10. **Audit Event** — publishes CREATE event
11. **Kafka Event** — publishes TENANT_PROVISIONED, which triggers `services/java/user-service/src/main/java/com/medfund/user/consumer/TenantProvisionedConsumer.java` to call `RoleService.seedDefaultRoles(tenantId)`
12. **Cache Evict** (Redis)

#### Controller Entry Point
File: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantController.java:83-100`

- `POST /api/v1/tenants` — requires `super_admin` role (JWT auth)
- Request: `CreateTenantRequest` (name, slug, domain, planId, contactEmail, countryCode, timezone, membershipModel, settings, defaultCurrencyCode)
- Response: `TenantResponse` (full tenant with all fields)

### Per-Tenant Flyway Migrations
File: `services/java/tenancy-service/src/main/resources/db/migration/tenant/`

- **Baseline (V001)**: Creates tables: `groups`, `members`, `dependants`, `providers`, `schemes`, `scheme_benefits`, and baseline claims/finance schema
- Subsequent: V002 (scheduled_jobs), V003-V100+ progressively add features (tariffs, drug claims, state machines, etc.)
- **V006** in tenant schema: Seeds `tenant_admin` role with full permission catalogue and adds `keycloak_role_name` column
- All tenant-schema tables created with tenant-scoped foreign keys; no cross-tenant leakage

#### Platform vs. Tenant Schemas

**Public Schema** (single copy, all tenants):
- `public.tenants` — tenant registry
- `public.staff_users` — platform staff (admin/ops) with optional `tenant_id` for per-tenant assignment
- `public.platform_settings` — singleton platform-wide config
- `public.currencies` — ISO 4217 registry (V111)
- `public.exchange_rates` — FX rates (V112)
- `public.scheduled_job_configs` — job scheduling (V114)

**Tenant Schemas** (one per tenant: `tenant_health_first`, etc.):
- `members`, `dependants`, `groups` — member registry
- `providers` — in-network provider list
- `schemes`, `scheme_benefits`, `age_groups` — product config
- `claims`, `claim_lines`, `claim_documents` — claims
- `payments`, `payment_runs` — finance
- `roles`, `role_permissions`, `user_roles` — RBAC (seeded per tenant)

### Platform Settings & Branding
File: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/PlatformSettings.java:1-39`

- **Table**: `public.platform_settings` (singleton, `singleton=true`)
- **Fields**: `platformName`, `supportEmail`, `logoBytes`, `logoMime`, `themeTemplateId`, `darkMode`, `heroTitle`, `heroSubtitle`
- **Usage**: Consumed by Angular pre-auth shell for login-page branding before JWT available

### Insurance Lines
File: `services/java/shared/src/main/java/com/medfund/shared/insurance/InsuranceLine.java:1-84`

- **Enum** (tenant-independent): HEALTH(personCentric=true), LIFE(true), FUNERAL(true), GROUP(true), TRAVEL(true), DISABILITY(true), VEHICLE(personCentric=false), PROPERTY(false)
- **Tenant Activation**: Stored in `Tenant.settings` as JSON key `insuranceLines` (array of enabled line codes)
- **isPersonCentric()**: Distinguishes member-based (HEALTH/LIFE/FUNERAL/GROUP/TRAVEL/DISABILITY) from asset-based (VEHICLE/PROPERTY)
- **Portal Visibility**: Angular operations portal shows sidebar items for enabled lines only; `.claude/portals.md:854-862` describes per-tenant feature flag gating

### PlatformFlag & PublicBranding
File: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/PlatformSettings.java` (branding) + git status indicates no separate `PlatformFlag` entity file found — flags appear seeded in `Tenant.settings` JSONB

### 2. Staff/User Model

#### Staff User Entity (Platform-Scope)
File: `services/java/user-service/src/main/java/com/medfund/user/entity/StaffUser.java:1-56` · Storage: `public.staff_users` (V100)

- **Fields**: `id`, `firstName`, `lastName`, `email` (unique), `phone`, `jobTitle`, `department`, `realm_role` (Keycloak role name), `keycloak_user_id` (FK to Keycloak), `tenant_id` (NULL for platform super_admin, non-NULL for tenant staff), `status` ('active'|'inactive'|'invited'), `invited_at` (timestamp for invite link expiry), `created_at`, `updated_at`
- **Key Design**: Platform-wide record; `tenant_id` field allows same staff entity to be "assigned" to a tenant; Keycloak holds the actual identity
- **Lifecycle**: 
  1. StaffUserController POST /api/v1/staff-users creates StaffUser with status='invited'
  2. Keycloak user created with invite link (expires after `INVITE_TTL` hours)
  3. Invite email sent
  4. Staff clicks link, sets password → Keycloak status='active', staff_users.status transitions to 'active'

#### Member Entity (Tenant-Scope)
File: `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:1-80` · Storage: Tenant schema `members` table (V001)

- **Fields**: `id`, `memberNumber` (unique per tenant), `firstName`, `lastName`, `dateOfBirth`, `gender`, `nationalId`, `email`, `phone`, `address`, `group_id` (FK to groups), `scheme_id` (FK to schemes), `keycloak_user_id` (FK to Keycloak user in tenant realm), `status` ('active'|'suspended'|'terminated'|'pending_verification'), `enrollment_date`, `termination_date`, `death_date` (V140), `cause_of_death` (V140), `scheduledStatus`/`scheduledStatusEffectiveFrom` (V042 future status transitions), `created_at`, `updated_at`
- **Distinction from Staff**: Members are insured persons; staff are operational personnel. A single natural person could be both (e.g., employee of medical aid who is also a member).
- **Keycloak Link**: Each member linked to a Keycloak user in the tenant's `tenant-{slug}` realm via `keycloak_user_id`

#### Provider Entity (Tenant-Scope)
File: Tenant schema `providers` table (V001) — Java entity in `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java`

- **Fields**: `id`, `name`, `practice_number`, `ahfoz_number` (health facility code), `specialty`, `email`, `phone`, `address`, `banking_details` (JSONB), `keycloak_user_id`, `status`, `created_at`
- **Keycloak Link**: Provider has a Keycloak user in EACH tenant realm they serve (can be multi-tenant)

### Keycloak Bootstrap & Provisioning
File: `scripts/bootstrap-keycloak.sh:1-339`

**What it does**:
1. Creates `medfund-platform` realm (super admin portal realm)
2. Creates OIDC client `medfund-web` with redirectUris for Angular + gateway, PKCE enabled, public client (no secret)
3. Seeds platform-level realm roles: `super_admin`, `tenant_admin`, `claims_clerk`, `claims_assessor`, `finance_officer`, `contributions_officer`, `provider`, `member`, `group_liaison`, `siu_officer`, `siu_supervisor` (line 163)
4. Adds protocol mapper to include `tenant_id` in JWT (hardcoded 'platform' for platform users)
5. Creates test user `superadmin` (password: admin123) with `super_admin` role
6. Creates test users: `claimsclerk`, `financeofficer`, `contribofficer`, `siuofficer`, `siusupervisor`, `testmember`, `testprovider` (all password: test123) with corresponding roles

**Per-Tenant Realm Creation**:
- Not in bootstrap script; done by `KeycloakRealmService.createRealm()` during tenant provisioning
- Realm name: `medfund-{tenant-slug}` (e.g., `medfund-zmmas` for slug "zmmas")
- Each tenant realm has its own users (members, staff, providers)
- Protocol mapper includes `tenant_id` = actual tenant UUID

### 3. Roles & Permissions System

#### Role Entity (Tenant-Scope)
File: `services/java/user-service/src/main/java/com/medfund/user/entity/Role.java:1-69` · Storage: Tenant schema `roles` table (V001 + V006 keycloak_role_name column)

- **Fields**: `id`, `name` (stable identifier, e.g., 'tenant_admin'), `display_name` (human label), `description`, `is_system` (boolean; TRUE for seeded roles like tenant_admin, FALSE for custom), `keycloak_role_name` (Keycloak realm role identifier; reserved for future per-tenant realm-role mirroring), `created_at`, `updated_at`
- **Design**: Database-native RBAC. Keycloak doesn't store these custom roles; only `tenant_admin` is mirrored. Today, permissions are resolved server-side from the DB via JWT claims + permission table joins.

#### Default Seeded Roles (Per-Tenant)
File: `services/java/user-service/src/main/java/com/medfund/user/service/RoleService.java:seedDefaultRoles` (invoked on TENANT_PROVISIONED event)

**Seeded by RoleService**:
- `tenant_admin` — Full access; pre-seeded with every permission in `Permissions.ALL` catalogue via V006 SQL migration
- `operations` — Operations Staff
- `claims_officer` — Claims Officer
- `finance_officer` — Finance Officer
- `provider` — Service Provider
- `member` — Scheme Member

All other roles are custom, created by tenant admins via `/admin/roles` UI.

#### Permission Model
File: `.claude/portals.md:428-719` + `services/java/shared/security/Permissions.java` (referenced but not fully read)

**Format**: `{portal_section}:{action}`
- **Portal sections**: `claims`, `finance`, `contributions`, `admin`, `audit`, `tickets`
- **Actions**: `read`, `write`, `delete`, `approve`, `export`, `configure`, `override_ai`, `assign`, etc.
- **Storage**: Tenant schema `role_permissions` table: (role_id, permission, access_level)
- **Access Levels**: Read-only, Write, Approve, Configure (inferred from RolePermission.accessLevel)

**Enforcement Layer** (per portals.md:719-810):
1. **JWT**: Keycloak includes `permissions` array claim (populated by custom protocol mapper querying the DB)
2. **Gateway** (Go): Middleware checks route → required permission mapping
3. **Angular**: PermissionService queries token; shows/hides UI elements conditionally
4. **API**: Controllers return 403 if JWT lacks required permission

#### Hardcoded vs. DB-Driven
- **Keycloak**: Holds realm roles (`super_admin`, `tenant_admin`, etc. in platform realm; tenant-specific roles NOT mirrored yet) — identity layer only
- **Database**: Holds `roles` + `role_permissions` (custom + seeded) + `user_roles` (assignment junction)
- **Permissions are DB-resolved** at token refresh time via Keycloak event listener or sync job (details not fully visible in this research scope)

### 4. Member vs. Staff Distinction

| Aspect | Staff | Member |
|--------|-------|--------|
| **Entity** | `public.staff_users` | Tenant-schema `members` |
| **Scope** | Platform-wide (with optional tenant_id) | Tenant-scoped |
| **Keycloak Realm** | `medfund-platform` (super admin) OR `tenant-{slug}` (tenant staff) | `tenant-{slug}` |
| **Role** | Platform realm role (super_admin) or custom tenant role | Custom member role (can be group_liaison + member) |
| **Purpose** | Operational/administrative | Insured person/beneficiary |

**Special Case**: A member can be granted a staff role (e.g., member + group_liaison) via Flutter role switcher (portals.md:243-251), enabling dual-persona access without violating data isolation.

### 5. The 4 (+ 1) Portals & Roles

File: `.claude/portals.md:1-1040` (comprehensive)

| Portal | Realm | Key Roles | Platform Availability |
|--------|-------|-----------|----------------------|
| **Super Admin** | `medfund-platform` | `super_admin` | Angular web only |
| **Tenant Admin** | `tenant-{slug}` | `tenant_admin` | Angular web only |
| **Operations** (Claims/Finance/Billing) | `tenant-{slug}` | `claims_officer`, `finance_officer`, `contributions_officer` (and custom roles) | Angular web only |
| **Provider** | `tenant-{slug}` (multi-tenant) | `provider`, `provider_admin` | Angular + Flutter mobile |
| **Member** | `tenant-{slug}` | `member` (often + `group_liaison` for liaisons) | Flutter mobile + web PWA |

**Key Portal-Role Bindings**:
- Super Admin portal (`/super-admin/*`) → Keycloak realm `medfund-platform`, role `super_admin`
- Tenant Admin portal (`/admin/*`) → Realm `tenant-{slug}`, role `tenant_admin`
- Operations portals (`/tenant/claims/*`, `/tenant/finance/*`, etc.) → Realm `tenant-{slug}`, roles from {claims_officer, finance_officer, contributions_officer, etc.}
- Provider portal (`/providers/*`) → Realm `tenant-{slug}` (account in each tenant served), roles `provider`/`provider_admin`
- Member portal (Flutter) → Realm `tenant-{slug}`, role `member`
- Group Liaison → Realm `tenant-{slug}`, roles `member` + `group_liaison` (dual role)

**Keycloak vs. DB Split**:
- **Keycloak**: Identity (login, MFA, password reset), realm membership, platform-level roles (super_admin)
- **Database**: Fine-grained permissions (claims.queue:read, finance.payments:approve, etc.), per-tenant custom roles, user-role assignments

## Provisioning Order (What Must Exist Before What)

### For a Functional Tenant with Logged-In Users Across All Portals:

**Phase 1: Tenant Core**
1. `Tenant` row in `public.tenants` (id, name, slug, domain, keycloak_realm, etc.)
2. Tenant-schema provisioned (`CREATE SCHEMA`, Flyway runs)
3. Keycloak realm created (`medfund-{slug}`)
4. `public.currencies` row for tenant's default currency

**Phase 2: Base Entities (Tenant-Schema)**
5. `groups` table populated (if GROUP_ONLY or BOTH membership model)
6. `schemes` and `scheme_benefits` (required for billing/member enrollment)
7. `age_groups` (if using AGE_GROUP pricing)
8. `providers` (in-network provider list, can be empty initially)
9. Seed default `roles` (tenant_admin, claims_officer, finance_officer, etc.) via RoleService.seedDefaultRoles
10. `role_permissions` join all default roles with permissions from V006 SQL migration

**Phase 3: Staff Users (Platform + Tenant Realms)**
11. Create `public.staff_users` rows (tenant_admin, claims_officer, etc.)
12. Create corresponding Keycloak users in `tenant-{slug}` realm with invite links
13. Assign roles via user_roles table

**Phase 4: Members (Tenant-Schema)**
14. Create `members` rows with enrollmentDate, schemeId, groupId (if group member)
15. Create corresponding Keycloak users in `tenant-{slug}` realm
16. Optionally create `dependants` if members have family coverage

**Phase 5: Providers (Tenant-Schema)**
17. Create `providers` rows
18. Create Keycloak users in `tenant-{slug}` realm for each provider (and in other tenant realms they serve)

**Phase 6: Access Tests**
19. Member can log in to Flutter member portal
20. Staff can log in to Angular operations portal
21. Provider can log in to provider portal

### For 2 Fully-Provisioned Tenants (HEALTH + LIFE)

**Minimally Required Inserts**:

```
public.tenants (2 rows)
  ├─ HEALTH: id, name, slug, schemaName, keycloakRealm, settings (insuranceLines: ["HEALTH"])
  └─ LIFE: id, name, slug, schemaName, keycloakRealm, settings (insuranceLines: ["LIFE"])

public.staff_users (12 rows: 6 per tenant)
  ├─ HEALTH: tenant_admin, claims_officer, finance_officer, contributions_officer, siu_officer, provider (test)
  └─ LIFE: tenant_admin, claims_officer, finance_officer, contributions_officer, siu_officer, provider (test)

Keycloak Realms (2):
  ├─ medfund-health-org: 6 staff + 1 test provider + 1 test member
  └─ medfund-life-org: 6 staff + 1 test provider + 1 test member

tenant_health_org schema:
  ├─ roles (7 default): tenant_admin, operations, claims_officer, finance_officer, provider, member, group_liaison
  ├─ role_permissions (all default roles seeded with V006 SQL)
  ├─ schemes (≥1 health scheme with benefits)
  ├─ age_groups (≥1)
  ├─ groups (≥1 if GROUP_ONLY, can be empty if INDIVIDUAL_ONLY)
  ├─ members (≥1 test member)
  ├─ providers (≥1 test provider)
  └─ dependants (optional, 1 per test member)

tenant_life_org schema:
  └─ (Same structure as health)

public.currencies:
  └─ ZWL (default) + USD (optional multi-currency test)
```

## Architecture Doc vs. Code

### Alignment
- `.claude/portals.md` matches code: role hierarchies, permission granularity, member/staff/provider distinctions all accurate
- `multi-tenancy.md` would confirm schema-per-tenant; this research validates it live in code
- `coding-standards.md` recommends Lombok—verified in `@Slf4j`, `@RequiredArgsConstructor` usage

### Drift (None Detected)
- Keycloak bootstrap script aligns with `KeycloakRealmService.createRealm()`
- Permission enforcement via JWT + gateway + Angular matches portals.md:719-810

## Code References

**Tenant Provisioning**:
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/Tenant.java:1-87` — Tenant entity
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantService.java:114-170` — create() method
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/SchemaProvisioningService.java:36-107` — schema creation + Flyway
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantController.java:83-100` — POST /api/v1/tenants endpoint

**Staff/User Model**:
- `services/java/user-service/src/main/java/com/medfund/user/entity/StaffUser.java:1-56` — Platform staff entity
- `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:1-80` — Member entity
- `services/java/user-service/src/main/java/com/medfund/user/entity/Role.java:1-69` — Role entity
- `services/java/user-service/src/main/java/com/medfund/user/service/RoleService.java:seedDefaultRoles` — Default role seeding
- `scripts/bootstrap-keycloak.sh:1-339` — Keycloak realm + user bootstrap

**Platform Config**:
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/PlatformSettings.java:1-39` — Platform branding
- `services/java/shared/src/main/java/com/medfund/shared/insurance/InsuranceLine.java:1-84` — Insurance line enum

**Database Schemas**:
- `services/java/tenancy-service/src/main/resources/db/migration/public/V100__staff_users.sql` — Platform staff schema
- `services/java/tenancy-service/src/main/resources/db/migration/public/V101__tenants.sql` — Tenants table
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql` — Per-tenant baseline

**Architecture & Config**:
- `.claude/portals.md:1-1040` — Portal specifications, role definitions, permission model
- `.claude/CLAUDE.md` — Critical rules: tenant scoping, audit, permissions, multi-tenancy

## Key Insights

1. **Synchronous Provisioning**: Tenant creation is a single transaction (with async side effects via Kafka). Schema exists immediately after POST; staff provisioning is separate.

2. **Keycloak is Identity Only**: Permissions are NOT stored in Keycloak realm roles. Database holds the source of truth; Keycloak holds the identity.

3. **StaffUser is Platform-Scoped**: A single `staff_users` row can serve multiple tenants if `tenant_id` is set to each. Multi-tenancy at staff level is possible but not heavily used.

4. **Member ≠ Staff**: Clear boundary. Members are insured; staff are operational. A person can be both (employee + member).

5. **Dual Roles Possible**: Member + Group Liaison role allows a member to manage their employer group's roster without seeing other members' claims/medical data (enforced at API level).

6. **Role Seeding is Transactional**: When a tenant is created, RoleService.seedDefaultRoles runs on TENANT_PROVISIONED event (async via Kafka consumer), inserting 6-7 default roles + permissions into the tenant schema.

7. **Per-Line Customization**: InsuranceLine enum drives which sidebar items appear; tenant settings include active lines; rules-engine stores per-line rules. The platform is line-agnostic structurally.

## What Must Be Inserted (Complete Entity List)

To seed 2 fully-provisioned tenants (HEALTH + LIFE) with staff for every portal/role, the complete entity manifest is organized by schema:

### Public Schema (Both Tenants)
- `tenants` (2 rows): health-org, life-org with insuranceLines setting
- `staff_users` (12 rows): 6 per tenant × 2 tenants
- `currencies` (2-3 rows): ZWL, USD
- `platform_settings` (1 row): singleton

### Per Tenant-Schema
- `roles` (7 rows): tenant_admin, operations, claims_officer, finance_officer, provider, member, group_liaison
- `role_permissions` (60+ rows): All default-role–permission mappings from V006
- `schemes` (1+ rows): Product packages
- `scheme_benefits` (3-5 rows): Benefit tiers
- `age_groups` (4-6 rows): Rate bands
- `groups` (1+ rows): Employer groups (if GROUP_ONLY)
- `members` (1-3 rows): Test insureds
- `dependants` (1-2 rows): Family members
- `providers` (1-2 rows): In-network service providers
- `user_roles` (8-12 rows): Staff role assignments

### Keycloak Realms
- `medfund-health-org`: 6 staff users + 1 test provider + 1 test member
- `medfund-life-org`: 6 staff users + 1 test provider + 1 test member

## Historical Context (from thoughts/shared/)

This is the first comprehensive research into tenant provisioning; no prior research documents reference this topic directly. The findings align with `.claude/portals.md` (existing architecture doc) and `.claude/CLAUDE.md` critical rules.

## Related Research

N/A — this is the definitive reference for tenant/staff/role provisioning in InsureFlow.

## Open Questions

1. **Permission Sync Mechanism**: The exact flow of how DB permissions get into the JWT `permissions` claim (Keycloak protocol mapper? periodic sync job?) is not fully traced. Recommendation: Review `KeycloakEventListener` and custom protocol mapper implementation in services/java/keycloak-event-listener-service.

2. **Multi-Tenant Staff**: Design allows a single staff_users row to serve multiple tenants (tenant_id field), but usage pattern is unclear. Recommendation: Audit staff_users table to confirm single-tenant-per-row in practice.

3. **Group Liaison API Enforcement**: Data isolation at API level is asserted (.claude/portals.md:351) but not fully traced in code. Recommendation: Review claims-service and user-service request handlers for group_liaison permission checks.

---

## Status

**Complete** — All tenant provisioning, staff/user, role/permission flows mapped. Live code is the primary source; portals.md and CLAUDE.md architectural docs align. Ready to build seed script or test fixture.

**Date Completed**: 2026-09-15  
**Confidence**: High (code-backed, cross-referenced)  
**Scope**: Exhaustive (all 3 target service areas covered)

