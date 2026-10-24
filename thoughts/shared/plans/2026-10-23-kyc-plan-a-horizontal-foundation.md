---
date: 2026-10-23
git_commit: e5051ef3a1cccc79fcebb62668ea589f2dcb98c0
branch: main
ticket: none — grounded in research + interactive scope decisions
research:
  - thoughts/shared/research/2026-10-22-kyc-pilot-horizontal.md
  - thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md
steer: "reconcile toward a standalone, line-agnostic, sellable KYC horizontal: party-neutral subject, build the entitlement seam, decide kyc-service vs user-service module"
services_touched: [shared, tenancy-service, gateway, kyc-service, rules-engine]
status: grilled (2026-10-23) — decisions G1–G8 + F1–F2 applied
kind: plan
program: thoughts/shared/plans/2026-10-23-kyc-horizontal-program-overview.md
sequence: A (of A→B→C)
---

# KYC Plan A — Horizontal Foundation

> **Grilled 2026-10-23.** Decisions G1–G8 and factual fixes F1–F2 are applied inline (superseded text
> struck through with the replacement beside it). Decision log: `scratchpad/plan-a-grilling-log.md`.
> Headline changes: the file-service pipeline **moved to Plan B** (G1), so Plan A is now **3 phases**;
> the entitlements migration is **V187** not V186 (F1); `ModuleKey` gains **`KYC_BIOMETRICS`** (G2).

## Overview

Build the reusable scaffolding that makes KYC (and every later capability) a **sellable, per-tenant
horizontal**: a per-tenant module-entitlement seam, the new `kyc-service` skeleton with its party-neutral
schema behind the entitlement gate, and the rules-engine `VerificationFact`/`VERIFICATION` category. ~~the
file-service inbound document pipeline~~ (→ **moved to Plan B, G1** — it isn't needed to prove the
entitlement/horizontal thesis and its only consumer is Plan B's document capture). No verification
*behaviour* yet (that's Plan B) — this plan delivers the frame and proves it: an entitled tenant reaches
`/api/v1/verifications/*`; an un-entitled one is rejected at the gateway. **Three phases:** entitlement seam
→ kyc-service skeleton → rules fact.

See the program overview for settled decisions and cross-cutting constraints:
`thoughts/shared/plans/2026-10-23-kyc-horizontal-program-overview.md`.

## Current State Analysis

- **No per-tenant entitlement exists.** `PlatformFlag` is global-by-design (`shared/.../flags/PlatformFlag.java:11`);
  `TenantSidebarSectionConfig` is cosmetic (default-on, no API enforcement); `Plan.features` is inert (no
  service reads it); the gateway does zero entitlement gating — the flag plumbing is wired but unused
  (`gateway/cmd/main.go:76`).
- **RBAC is the one genuine per-tenant gate** — `@RequiresPermission` + `PermissionAspect.java:36-72`,
  resolved per-service from the tenant schema (`PermissionResolverFilter.java:42-50`).
- ~~**file-service inbound path is a stub**~~ (→ relevant to **Plan B** now that the pipeline moved there, G1).
- **tenancy-service owns all tenant DDL** — next free tenant migration **V278** (confirmed: max tenant =
  V277); next free **public** migration is ~~V186~~ **V187** (**F1** — V186 is already taken by
  `V186__provider_tenants_cascade_on_tenant_delete.sql`). kyc-service will not own its tables.
- **rules-engine is an in-process library** — `implementation(project(":rules-engine"))`; new fact =
  two `DrlCompiler` edits + a POJO; new category = one enum value + one `TemplateProvider` bean.
- **Next free Java service port is 8087** (`gateway/internal/config/config.go` tops out at rules 8086).

## Desired End State

- A `public.tenant_module_entitlements` table + a `TenantEntitlementRegistry` (Java) and
  `entitlements.Registry` (Go) resolve "is tenant T entitled to module M" with Kafka invalidation.
- A gateway `EntitlementGate` middleware rejects un-entitled module routes with 403; a `@RequiresEntitlement`
  aspect does the same inside Java services (defence in depth).
- Super admins manage entitlements under `/api/v1/platform/module-entitlements` (inherits `RequireSuperAdmin`);
  a toggle emits **both** an audit event (Rule #8) **and** a security event (Rule #9) (**G5**).
- ~~file-service exposes `POST /api/v1/files/documents` …~~ (→ **Plan B**, G1).
- `kyc-service` runs on :8087, owns `kyc_verifications` + `kyc_documents` keyed by the party-neutral
  `(subject_type, subject_id[, insurance_line])` triple, is routed behind the entitlement gate, and returns an
  empty-but-live `GET /api/v1/verifications` for an entitled tenant. Verification is **per-subject** (one row
  per subject; `insurance_line` is contextual, **G6**); `kyc_documents` ships here but is first written in Plan B.
- rules-engine knows `VerificationFact` + `RuleCategory.VERIFICATION` with a seed template.

### Key Discoveries
- Mirror `FlagRegistry`/`FlagRegistryR2dbc`/`FlagInvalidationConsumer` (`shared/.../flags/`) for the
  registry — but thread `tenantId` through the cache key, SQL `WHERE`, Kafka payload, and invalidation.
- Mirror `PermissionAspect` + `RequiresPermission` + `PermissionContext` + `PermissionsAutoConfiguration`
  for `@RequiresEntitlement`; resolve `tenantId` from `TenantContext.get(ctx)` inside the `deferContextual`.
  **F2:** `PermissionsAutoConfiguration` is a plain `@Configuration` picked up by the `com.medfund.shared`
  component scan — there is **no** `AutoConfiguration.imports` file in this repo. Follow that pattern.
- Mirror `PlatformFeatureFlagController`/`Service`/`FlagEventPublisher`/`InternalFeatureFlagController`
  for the management + internal-snapshot surface; mirror `TenantSidebarSectionConfig` for the
  per-tenant table/default semantics (but default **FALSE** — a module is off until sold).
- Mirror `SecurityEventPublisher` (`shared/.../security/`) for the Rule #9 security event on entitlement
  changes (**G5**) — `publish(SecurityEventMessage)`, topic `medfund.security.events`.
- ~~Mirror the file-service stub-vs-real swap … for a `Scanner`.~~ (→ **Plan B**, G1.)
- Mirror `FraudFlagFact`/`FRAUD_TRIAGE`/`FraudTriageTemplates` for the new fact/category/provider.

## What We're NOT Doing

- No verification state machine, OCR, screening, or UI (Plans B/C).
- No KMS (Plan C) — this plan stores no PII (document *bytes* live in MinIO via file-service; kyc-service
  stores only handles + status, populated in Plan B).
- Not wiring `Plan.features`/`planId` (left inert; entitlement is its own table). Optional future linkage.
- No Angular entitlement UI yet (Plan B adds the platform-admin toggle screen).
- No re-verification/expiry flow — `expired` is set by a **later scheduled job** (named, not built here); no
  episode-grouping of re-verification cycles (**G7** — current-state row + history + prediction store suffice).

## Implementation Approach

Entitlement seam first (Phase 1) — it is the reusable unlock and is verifiable before kyc-service exists
(gate a placeholder route). ~~File pipeline (Phase 2) is independent and parallelizable.~~ (moved to Plan B,
**G1**.) kyc-service skeleton (**Phase 2**, was Phase 3) depends on the gate (it routes behind it). Rules fact
(**Phase 3**, was Phase 4) is independent of both.

**Cross-service contracts fixed up front:**
- New Kafka topic (additive; producer before consumer): `platform.module-entitlements.v1` (producer
  tenancy-service; consumers: every service's registry + the gateway). ~~`medfund.documents.uploaded`
  (producer file-service)~~ → **Plan B** (G1).
- `ModuleKey` enum (new, in `shared`) is the single source of module identity — values `KYC` **and
  `KYC_BIOMETRICS`** (biometrics is a separately-sellable add-on SKU, **G2**), plus placeholders for future
  horizontals. The gateway maps route prefix → `ModuleKey`; **the prefix map is hardcoded in the gateway for
  the pilot** (accepted; promote to a served mapping when there are many modules, **G4**).
- Entitlement default is **FALSE** (absent row = not entitled), the inverse of the sidebar default. Enforcement
  **fails closed**: uncertain (absent row / tenancy unreachable) → 403; the gateway bootstrap **logs-and-continues**
  (does not crash), so a cold-start tenancy outage means KYC is 403 until tenancy returns — intended (**G3**).

---

## Phase 1: Reusable per-tenant module-entitlement seam

### Overview
A `tenant_module_entitlements` table, a Java `TenantEntitlementRegistry` + `@RequiresEntitlement` aspect, a
Go `entitlements.Registry` + gateway `EntitlementGate`, super-admin management endpoints, and Kafka
invalidation. KYC is the first `ModuleKey`.

### Changes Required

#### 1. `ModuleKey` enum (shared)
**File**: `services/java/shared/src/main/java/com/medfund/shared/entitlement/ModuleKey.java` (new)
```java
// Catalogue of sellable platform modules. Mirrors PlatformFlag's (displayName, description)
// shape but is gated PER TENANT via tenant_module_entitlements (not global).
@Getter
@RequiredArgsConstructor
public enum ModuleKey {
    KYC("KYC & Verification", "Identity verification, document capture, sanctions/PEP screening"),
    // G2: biometrics is a SEPARATE sellable add-on SKU — base KYC can be sold without it.
    KYC_BIOMETRICS("Biometric Verification", "Face-match + liveness (add-on to KYC)"),
    // placeholders for the horizontal roadmap — safe to add (opaque elsewhere):
    CLAIMS("Claims", "Claims management"),
    BILLING("Billing & Contributions", "Premium billing and collection");

    private final String displayName;
    private final String description;

    public static java.util.Optional<ModuleKey> parse(String raw) {
        try { return java.util.Optional.of(ModuleKey.valueOf(raw)); }
        catch (IllegalArgumentException e) { return java.util.Optional.empty(); }
    }
}
```

#### 2. Entitlement table (public schema)
**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V187__tenant_module_entitlements.sql` (new)
*(**F1** — V186 is taken by `V186__provider_tenants_cascade_on_tenant_delete.sql`; max public = V186, so this is **V187**.)*
```sql
-- Per-tenant module entitlement. Absent row = NOT entitled (module off until sold) —
-- the inverse of tenant_sidebar_section_config's default-on. Mirrors that table's shape.
CREATE TABLE IF NOT EXISTS public.tenant_module_entitlements (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    module_key  VARCHAR(40) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  UUID,
    version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_module UNIQUE (tenant_id, module_key)
);
CREATE INDEX IF NOT EXISTS tenant_module_entitlements_enabled_idx
    ON public.tenant_module_entitlements (tenant_id) WHERE enabled = TRUE;
```
Add `public.tenant_module_entitlements` to the `provision_tenant_role` readable-tables list if platform
tables are role-granted (check `V185__platform_provider_membership.sql:66-87`).

#### 3. Java `TenantEntitlementRegistry` (shared)
**Files** (new, `services/java/shared/.../entitlement/`): `TenantEntitlementRegistry.java` (interface,
mirror `FlagRegistry.java:19`), `TenantEntitlementRegistryR2dbc.java` (`@Configuration` + Caffeine, mirror
`FlagRegistryR2dbc.java`), `EntitlementInvalidationConsumer.java` (mirror `FlagInvalidationConsumer.java`).
```java
public interface TenantEntitlementRegistry {
    Mono<Boolean> isEntitled(UUID tenantId, ModuleKey module);
    default void invalidate(String tenantId, String rawKey) { }
}
// Impl: composite cache key record Key(UUID tenantId, ModuleKey module); maximumSize scaled to
// tenants×modules (not 64). Query — fail-safe default FALSE:
//   client.sql("SELECT enabled FROM public.tenant_module_entitlements " +
//              "WHERE tenant_id = :t AND module_key = :k")
//         .bind("t", tenantId).bind("k", module.name())
//         .map((row,meta) -> Boolean.TRUE.equals(row.get("enabled", Boolean.class)))
//         .one().defaultIfEmpty(Boolean.FALSE)
//         .doOnNext(v -> cache.put(key, v))
//         .onErrorResume(e -> { log.warn(...); return Mono.just(Boolean.FALSE); });
```
Topic constant `platform.module-entitlements.v1`; payload `{tenantId, moduleKey, enabled, updatedAt, actor}`.
Register beans in a new `EntitlementAutoConfiguration` (mirror `PermissionsAutoConfiguration.java:23`).
~~list it in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`~~ **F2: no
such imports file exists in this repo** — `PermissionsAutoConfiguration` is a plain `@Configuration` picked
up by the `com.medfund.shared` component scan. Make `EntitlementAutoConfiguration` a `@Configuration` under
`com.medfund.shared` (auto-scanned by every service); no imports file.

#### 4. `@RequiresEntitlement` aspect (shared)
**Files** (new): `security/RequiresEntitlement.java` (`@interface { ModuleKey value(); }`),
`security/EntitlementAspect.java` (mirror `PermissionAspect.java:36` — `@Around("@annotation(...RequiresEntitlement)")`,
`Mono`/`Flux` `deferContextual`, read `tenantId` via `TenantContext.get(ctx)`, call
`registry.isEntitled(...)`, throw `ResponseStatusException(FORBIDDEN, "module not enabled: "+module)` when false).

#### 5. Super-admin management + internal snapshot (tenancy-service)
Mirror the flag quartet:
- **Entity/repo**: `TenantModuleEntitlement` (mirror `TenantSidebarSectionConfig.java:19`), repository with
  `findByTenantId` + `findByTenantIdAndModuleKey`.
- **Service** `TenantModuleEntitlementService` (mirror `PlatformFeatureFlagService.java:53` +
  `TenantSidebarSectionConfigService` upsert/no-op/audit): `setEntitlement(tenantId, moduleKey, enabled,
  actorId, actorEmail)` → upsert → audit (`entityType="TENANT_MODULE_ENTITLEMENT"`) → **then**
  `eventPublisher.publish(...)` (DB-commit before broadcast).
  **G5 — also emit a security event** (entitlement change is a privilege-level change, Rule #9): inject
  `SecurityEventPublisher` (`shared/.../security/`) and, after the audit, emit via a new
  `publishEntitlementChanged(tenantId, actorId, actorEmail, moduleKey, enabled)` helper added to
  `SecurityEventPublisher` (add an `EVENT_TYPE_ENTITLEMENT_CHANGED = "ENTITLEMENT_CHANGED"` constant;
  mirror `publishAccessDenied`). Topic `medfund.security.events`; fire-and-forget (never blocks the toggle).
- **Publisher** `ModuleEntitlementEventPublisher` (mirror `FlagEventPublisher.java:53`), topic
  `platform.module-entitlements.v1`, keyed by `tenantId+moduleKey`, fail-open.
- **Admin controller** `/api/v1/platform/module-entitlements` (mirror `PlatformFeatureFlagController.java`):
  `GET` (list, optional `?tenantId=`), `PUT /{tenantId}/{moduleKey}` (body `{enabled}`). Register explicit
  proxy lines in `routes.go` **before** the `platformGroup` so it inherits `RequireSuperAdmin`.
- **Internal controller** `/internal/v1/module-entitlements` returning `(tenantId, moduleKey, enabled)` for
  the Go registry bootstrap (mirror `InternalFeatureFlagController.java`, `permitAll` via `/internal/**`).
- **Seeder**: mirror `PlatformFlagSeeder` to ensure no rows are required (absent = FALSE); optionally seed
  KYC=true for a demo tenant.

#### 6. Go `entitlements.Registry` + gateway `EntitlementGate`
**Files**: `services/go/shared/entitlements/registry.go` (new, mirror `flags/registry.go` — composite key
`map[string]bool` with `tenantId|moduleKey`, `IsEntitled(tenantID, module string) bool`, `flagRow` gains
`TenantID`, `bootstrapPath="/internal/v1/module-entitlements"`, distinct `Topic`), and
`services/go/gateway/internal/middleware/entitlement.go` (new).
```go
// EntitlementGate rejects module routes a tenant is not entitled to. Registered AFTER
// TenantResolver() so c.Locals("tenant_id") is set. Skips a platform-path allowlist.
func EntitlementGate(reg *entitlements.Registry, prefixToModule map[string]string) fiber.Handler {
    return func(c *fiber.Ctx) error {
        module, gated := matchPrefix(c.Path(), prefixToModule) // e.g. "/api/v1/verifications" -> "KYC"
        if !gated { return c.Next() }
        tenantID, _ := c.Locals("tenant_id").(string)
        if tenantID == "" || !reg.IsEntitled(tenantID, module) {
            return c.Status(fiber.StatusForbidden).JSON(fiber.Map{"error": "module not enabled for tenant"})
        }
        return c.Next()
    }
}
```
**Wire in** `gateway/cmd/main.go`: construct `entitlementRegistry := entitlements.New(cfg.TenancyServiceURL)`
+ `Bootstrap` + `go StartConsumer(ctx, cfg.KafkaBrokers, "gateway")` **above** `routes.Register`, then
`app.Use(middleware.EntitlementGate(entitlementRegistry, prefixMap))` immediately after
`middleware.TenantResolver()` (`main.go:64`).

**G3 — fail-closed, don't crash the gateway:** `Bootstrap` **logs-and-continues** on error (mirror the flag
registry), so the gateway still starts if tenancy is down; the gate then 403s KYC until tenancy returns. The
registry's steady-state default is FALSE on a cache miss / query error. A cold-start tenancy outage ⇒ KYC 403
is **intended** (never give a paid module away during uncertainty).

**G4 — why both the gateway gate and the `@RequiresEntitlement` aspect (§4):** the `prefixToModule` map is
**hand-maintained**. If a future KYC route is added to `routes.go` but the map entry is forgotten, the gateway
won't gate it — only the aspect (enforced inside kyc-service from `TenantContext`) catches that drift. Gateway
is the primary edge gate; the aspect is the safety net. Keep both.

**G2 — prefix map covers both SKUs:** `/api/v1/verifications → KYC`; **biometric routes → `KYC_BIOMETRICS`**
(added in Plan C Phase 5). Base KYC routes never require `KYC_BIOMETRICS`.

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :shared:build :tenancy-service:build` — main + test sources compile clean on JDK 21 (`compileJava`/`compileTestJava`). NOTE: the full `build` task also runs the Testcontainers ITs below, which need Docker (unavailable in this session).
- [x] Go builds + vets: `cd services/go/gateway && go build ./... && go vet ./...`; same for `services/go/shared` — both clean
- [x] Java unit tests: `make test-java` — `EntitlementAspectTest` (denies 403 when not entitled / no tenant; proceeds when entitled; class-level `@within` honoured) and `TenantModuleEntitlementServiceTest` (unknown module rejected before any write; insert audits with `entityType=TENANT_MODULE_ENTITLEMENT` + friendly entityName + actorEmail; broadcast + **`ENTITLEMENT_CHANGED` security event (G5)**; no-op flip writes nothing) both pass
- [x] Integration test: `make test-integration` — `TenantModuleEntitlementIT` (extends `AbstractIntegrationTest`): set entitlement → `isEntitled` true; absent → false; audit event published with `entityType="TENANT_MODULE_ENTITLEMENT"` + non-blank actorEmail; **a security event (`ENTITLEMENT_CHANGED`) is published on toggle (G5)**; **V187** applies on a fresh container (pinned by a column assertion in `PublicMigrationFlywayIT`). *(ran green against Testcontainers Postgres+Kafka)*
- [x] Go tests: `make test-go` — `EntitlementGate` returns 403 for un-entitled tenant / missing tenant, `c.Next()` for entitled + un-gated routes (httptest + fake registry); **a cold/empty registry (bootstrap-failed) returns 403, not a panic (G3)**; registry round-trips the Kafka payload + per-tenant isolation. All pass.
- [ ] Swagger renders `/api/v1/platform/module-entitlements` at tenancy `:8081/swagger-ui` *(needs running service — manual)*

#### Manual Verification:
- [ ] `make infra`; set KYC entitlement off for a tenant → `curl` a placeholder `/api/v1/verifications` via the gateway → 403; toggle on via the platform endpoint → the next call passes the gate
- [ ] `platform.module-entitlements.v1` lands on the topic (console consumer) on toggle

**Implementation Note**: pause for human confirmation of the gate flip before Phase 2 routes real traffic through it.

---

## ~~Phase 2: file-service inbound document pipeline (Go)~~ → MOVED TO PLAN B (G1)

**G1:** The file-service inbound pipeline (multipart → virus-scan → MinIO → `medfund.documents.uploaded`,
plus the byte-stream read path) is **not needed to prove Plan A's entitlement/horizontal thesis** and its only
consumer is Plan B's document capture. It has been **moved to Plan B** as the phase immediately before the OCR
pull. Nothing in Plan A depends on it. (Full spec now lives in Plan B.)

---

## Phase 2 (was Phase 3): kyc-service skeleton + party-neutral schema + gated routing

### Overview
Stand up the new Java service on :8087, create the party-neutral `kyc_verifications` + `kyc_documents`
tables (tenancy-owned DDL), route `/api/v1/verifications/*` behind the Phase-1 entitlement gate, and expose
a live-but-empty `GET /api/v1/verifications`. No behaviour — the skeleton proves the boundary.

### Changes Required

#### 1. New Gradle module + bootstrap
**Files**: `services/java/kyc-service/build.gradle.kts` (mirror `user-service/build.gradle.kts:5-18` —
`:shared` + `:rules-engine` deps, validation, actuator, `testFixtures(project(":shared"))`,
`flyway-database-postgresql` testRuntimeOnly, Testcontainers BOM 1.21.4 override),
`settings.gradle.kts` (add `include("kyc-service")`),
`KycServiceApplication.java` (`@SpringBootApplication(scanBasePackages={"com.medfund.kyc","com.medfund.shared","com.medfund.rules.engine","com.medfund.rules.compiler","com.medfund.rules.consumer","com.medfund.rules.fact","com.medfund.rules.model","com.medfund.rules.entity","com.medfund.rules.repository","com.medfund.rules.service","com.medfund.rules.template"})`),
`application.yml` (`server.port: 8087`, `springdoc.swagger-ui.path: /swagger-ui`, `spring.r2dbc.*`, OAuth2
issuer, actuator), `Dockerfile` (mirror an existing service Dockerfile).

#### 2. Party-neutral schema (tenancy-owned, tenant family)
**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V278__kyc_core.sql` (new)
```sql
-- Party-neutral KYC. subject = (subject_type, subject_id); the only common identity handle across
-- party types is the UUID id. MEMBER subjects live in THIS tenant schema; PROVIDER subjects live in
-- public.providers — no FK is enforced on subject_id for that reason (resolution is per subject_type
-- in kyc-service). Mirrors siu_evidence for the document handle.
-- G6: verification is PER-SUBJECT (one row per subject) — the UNIQUE is (subject_type, subject_id).
--     insurance_line is CONTEXTUAL only (the line that triggered/contextualizes this verification and
--     feeds the rules); it is NOT part of identity. Enhanced per-line/jurisdiction requirements are
--     expressed as a higher verification_level via VERIFICATION rules, never as a second row.
CREATE TABLE IF NOT EXISTS kyc_verifications (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type       VARCHAR(24) NOT NULL,
    subject_id         UUID NOT NULL,
    insurance_line     VARCHAR(24),   -- G6: contextual only, not part of the UNIQUE identity
    verification_status VARCHAR(24) NOT NULL DEFAULT 'unverified',
    verification_level  SMALLINT NOT NULL DEFAULT 0,
    method             VARCHAR(16),
    ocr_prediction_id       UUID,
    screening_prediction_id UUID,
    screening_result   VARCHAR(16),
    verified_at        TIMESTAMPTZ,
    verified_by        UUID,
    verified_by_email  VARCHAR(255),
    rejected_reason    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by         UUID,
    -- G8: CHECK allows all four types now (forward-compat, user's call). The DB therefore does NOT
    --     guard against a write for a type with no resolver — so the COMPENSATING CONTROL is at the
    --     service layer: kyc-service's SubjectResolverRegistry MUST reject a verification request whose
    --     subject_type has no registered resolver (HTTP 400), until DEPENDANT/POLICYHOLDER resolvers
    --     exist (Plan B builds MEMBER + PROVIDER only). Prevents orphan rows the decision flow can't action.
    CONSTRAINT kyc_subject_type_chk CHECK (subject_type IN ('MEMBER','DEPENDANT','PROVIDER','POLICYHOLDER')),
    CONSTRAINT kyc_status_chk CHECK (verification_status IN ('unverified','pending_verification','verified','rejected','expired')),
    -- G6: per-subject identity. 'expired' (G7) is set by a LATER scheduled job, not in the pilot.
    CONSTRAINT kyc_verifications_subject_uq UNIQUE (subject_type, subject_id)
);
CREATE INDEX IF NOT EXISTS kyc_verifications_status_idx ON kyc_verifications (verification_status);

CREATE TABLE IF NOT EXISTS kyc_documents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type      VARCHAR(24) NOT NULL,
    subject_id        UUID NOT NULL,
    document_type     VARCHAR(32) NOT NULL,
    file_service_ref  VARCHAR(255) NOT NULL,
    bucket            VARCHAR(128) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    sha256            VARCHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'uploaded',
    uploaded_by       UUID NOT NULL,
    uploaded_by_email VARCHAR(255) NOT NULL,   -- feedback_audit_actor_email
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT kyc_documents_type_chk CHECK (document_type IN
        ('NATIONAL_ID','PASSPORT','DRIVERS_LICENSE','PROOF_OF_ADDRESS','SELFIE','BIRTH_CERTIFICATE','OTHER')),
    CONSTRAINT kyc_documents_status_chk CHECK (status IN ('uploaded','verified','rejected','expired'))
);
CREATE INDEX IF NOT EXISTS kyc_documents_subject_idx ON kyc_documents (subject_type, subject_id);
```
*(No `tenant_id` column — tenant isolation is the search_path. PROVIDER rows: see Plan B for the public-schema
read-join discipline, Critical Rule #2.)*

#### 3. Entity/repo + a read-only skeleton controller
- `entity/KycVerification.java`, `entity/KycDocument.java` (`@Getter @Setter`, R2DBC `@Table`, `@Id`,
  `@Column`, `@CreatedDate`/`@LastModifiedDate` — mirror `Dependant.java`).
- `repository/KycVerificationRepository.java`, `KycDocumentRepository.java` (`R2dbcRepository` + `@Query`
  with named params — mirror `DependantRepository.java`).
- `controller/VerificationController.java` — `@RestController @RequestMapping("/api/v1/verifications")`,
  `@RequiresEntitlement(ModuleKey.KYC)` on the class, `GET` list (returns `Flux` of a response record),
  full Swagger. This is the route the gate protects end-to-end.

#### 4. Fleet wiring
- **Gateway**: `config.go` add `KycServiceURL` (`KYC_SERVICE_URL`, default `http://localhost:8087`);
  `routes.go` add `app.All("/api/v1/verifications", proxy.Handler(cfg.KycServiceURL))` + `/*` (specific
  before wildcard); add `/api/v1/verifications → KYC` to the `EntitlementGate` prefix map.
- **docker-compose.yml**: add a `kyc-service` block (mirror `user-service:188-206`, port 8087, standard
  R2DBC/OAuth2/Kafka env); add `KYC_SERVICE_URL` + `kyc-service` to the gateway's env + `depends_on`.

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :kyc-service:build` (green — tests + 70% coverage gate pass)
- [x] Integration test: `make test-integration` — `KycSkeletonIT` (extends `AbstractPostgresIntegrationTest`,
      own `ReactiveJwtDecoder` stub): the party-neutral schema applies; entitled tenant → empty/seeded list,
      un-entitled tenant → 403 (the `@RequiresEntitlement(KYC)` aspect, G4)
- [x] Gateway build + `go vet` green; `config` tests pass. (No route-table test exercises
      `/api/v1/verifications`; the 4 `routes_test.go` timeouts are pre-existing — tests dial unresolvable
      hostnames, identical with the route change stashed.)
- [ ] Swagger renders `/api/v1/verifications` at `:8087/swagger-ui` (controller `@Operation`/`@ApiResponses`
      compile; live render is folded into the manual step below)

#### Manual Verification:
- [ ] `make infra` + bring up kyc-service + gateway; entitled tenant → `GET /api/v1/verifications` via gateway → 200; un-entitled → 403 (end-to-end gate, Phase 1 + Phase 2 together)

**Implementation Note**: pause — this is the first real traffic through the entitlement gate. Confirm both the 200 and 403 paths before Plan B. (Phase 3 below — the rules fact — is independent and may be done before or after this phase.)

---

## Phase 3 (was Phase 4): rules-engine VerificationFact + VERIFICATION category

### Overview
Register the line-neutral fact and category so Plan B can author per-line verification policy as rule
content. Mirrors exactly how `FraudFlagFact`/`FRAUD_TRIAGE` were added.

### Changes Required
- **Fact** — `rules-engine/.../fact/VerificationFact.java` (new, mirror `FraudFlagFact.java:27-52`):
  `@Getter @Setter @Builder`; fields `subjectType`, `insuranceLine`, `country`, `List<String> documentTypesPresent`,
  `verificationLevel`, `screeningResult`; result flags `@Builder.Default boolean requireManualReview = false;`
  and `@Builder.Default boolean gateActivation = false;` that rules mutate and kyc-service reads back.
- **Category** — `rules-engine/.../model/RuleCategory.java`: append `VERIFICATION` after `FRAUD_TRIAGE`
  (`:160-161`) with a javadoc block naming the fact + the agenda group (mirror `:151-160`).
- **DrlCompiler** — add `"import com.medfund.rules.fact.VerificationFact;\n"` to `IMPORTS` (`:36-55`) and
  `m.put("verification", new FactMapping("$verification", "VerificationFact"));` to `FACT_MAPPINGS` (`:69-92`).
  Add `"VERIFICATION"` to `AGENDA_GATED_CATEGORIES` (`:64-66`) only if verification rules must be agenda-gated.
- **TemplateProvider** — `rules-engine/.../template/providers/VerificationTemplates.java` (new, `@Component`,
  mirror `FraudTriageTemplates.java:56-151`): `category()` → `RuleCategory.VERIFICATION`; `templates()` returns a
  seed rule (e.g. "require manual review when screeningResult == 'hit'").

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :rules-engine:build` — `:rules-engine:test` (full suite) green on JDK 21
- [x] Unit tests: `make test-java` — `VerificationTemplatesTest` compiles a `VERIFICATION` rule over a `VerificationFact` and asserts the flag mutates through `TenantRuleEngine.evaluateInGroup` (manual-review on screening hit; gate-activation at/above tier; tenant isolation); `RuleCategory.VERIFICATION` present (`RuleCategoryTest` count 24→25); `VerificationTemplates` returns 2 templates
- [x] rules-service template catalogue lists the VERIFICATION category — `RuleTemplateServiceTest.getDefaultRules_coversEveryDeclaredCategory` passes with `VerificationTemplates` added to the hand-wired provider list

#### Manual Verification:
- [ ] (none — pure library change, fully covered by automated tests)

---

## Testing Strategy

### Unit Tests
- Java: entitlement registry fail-safe + aspect deny; **entitlement toggle emits an `ENTITLEMENT_CHANGED`
  security event (G5)**; `DrlCompiler` VERIFICATION round-trip.
- Go: `EntitlementGate` 403/next; **cold/empty registry → 403 not panic (G3)**; registry Kafka apply.
  (~~documents handler~~ → Plan B, G1.)

### Integration Tests (Testcontainers)
- `TenantModuleEntitlementIT`, `KycSkeletonIT` — extend `AbstractPostgresIntegrationTest`, own JWT-decoder
  stub, migrate the tenant family incl. V278 (+ public **V187**), `@MockBean` publishers, assert audit shape
  (friendly `entityName`, non-blank `actorEmail`). Mind the TC 1.21.4 BOM override + `flyway-database-postgresql`.

### E2E Tests
- Deferred to Plan B (no Angular surface in Plan A).

### Manual Testing Steps
1. `make infra`; bring up tenancy, gateway, kyc-service.
2. Toggle KYC entitlement off → gateway 403 on `/api/v1/verifications`; toggle on → 200.
3. Confirm the toggle emits both an audit event and an `ENTITLEMENT_CHANGED` security event (G5).
   (~~Upload a document to file-service~~ → Plan B, G1.)

## Performance Considerations
- Entitlement registry is Caffeine-cached (short TTL) + Kafka-invalidated — the gate adds one in-memory
  lookup per request, no per-request DB hit after warm. Size the cache to tenants×modules.
- `kyc_verifications.verification_status` and `kyc_documents (subject_type, subject_id)` are indexed.
  (~~Document bytes transit file-service…~~ → Plan B, G1.)

## Migration Notes
- **Flyway**: public **`V187`** (entitlements, **F1** — V186 is taken) in the public family; tenant `V278`
  (kyc_core) in the tenant family — both ship from tenancy-service; applied on its next boot across all tenant
  schemas (`SchemaProvisioningService`/`TenantMigrationRunner`). Never edit an applied migration.
- Mind `bug_public_prefix_silent_rollback` / `bug_public_flyway_history_load_bearing` for the public **V187**.
- **Kafka**: one additive topic (`platform.module-entitlements.v1`); no recompaction. No Keycloak realm
  change. (~~`medfund.documents.uploaded`~~ → Plan B, G1.)

## Rollout & Rollback
- **Deploy order (producer before consumer):** tenancy-service (publishes entitlement events, serves the
  internal snapshot) → shared-consuming services pick up the new registry → gateway (gate + registry) →
  kyc-service. (file-service → Plan B, G1.)
- **Rollback:** the gate fails *closed* (un-entitled = 403); reverting the gateway removes gating (all routes
  open as before). Reverting kyc-service leaves the unused V278 / public **V187** tables in place (harmless — no
  `tenant_id`, no cross-tenant risk). Entitlement default FALSE means no tenant is silently exposed if the
  registry can't reach tenancy (fail-safe default is FALSE).

## References
- Program overview: `thoughts/shared/plans/2026-10-23-kyc-horizontal-program-overview.md`
- Research: `thoughts/shared/research/2026-10-22-kyc-pilot-horizontal.md`
- Mirror sources: `shared/.../flags/{FlagRegistry,FlagRegistryR2dbc,FlagInvalidationConsumer}.java`,
  `shared/.../security/{PermissionAspect,RequiresPermission,PermissionContext,PermissionsAutoConfiguration}.java`,
  `tenancy-service/.../{PlatformFeatureFlagController,PlatformFeatureFlagService}.java` +
  `kafka/FlagEventPublisher.java` + `TenantSidebarSectionConfig*`,
  `gateway/{cmd/main.go,internal/middleware/tenant.go,internal/routes/routes.go,internal/config/config.go}`,
  `go/shared/flags/registry.go`, `file-service/{internal/handler/handler.go,cmd/main.go,internal/events/publisher.go}`,
  `rules-engine/.../fact/FraudFlagFact.java` + `template/providers/FraudTriageTemplates.java` + `compiler/DrlCompiler.java`
