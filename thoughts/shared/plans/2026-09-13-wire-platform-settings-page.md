---
date: 2026-09-13
git_commit: 2a80248e
branch: rename-adjustments-to-notes
ticket: none
research: none — investigation done inline while planning (user identified the page as non-functional during a session about UI polish; scope was small enough to skip the separate research pass, but grilling was invoked before drafting)
steer: "to wire the page, /grilling on any open questions"
services_touched: [tenancy-service, angular, shared-java, shared-go]
status: in-progress (phases 1-4 complete; phase 4 manual verification outstanding)
---

# Wire `/platform/settings` Page — Implementation Plan

## Deviations

Recorded as they arise. The plan's design remains authoritative; each entry says
what was changed and why, so a reviewer knows why the diff diverges.

- **2026-09-14 (Phase 1) — Logo storage: MinIO → Postgres bytea.** tenancy-service
  has no MinIO client wiring today; V168's own comment codifies the convention
  ("tenancy-service has no existing MinIO wiring; Postgres bytea keeps it simple").
  So the plan's `POST /platform/settings/logo` writes bytes to a new pair of
  columns on `platform_settings` (`logo_bytes bytea`, `logo_mime varchar`), and
  `GET /api/v1/public/platform/logo` streams them out. `logo_url` in the response
  DTO is the URL of that streaming endpoint. Consequences: 2 MB upload cap stays
  (already row-bounded in Postgres); no separate migration for a MinIO bucket
  path; the plan's `services/go/file-service` "platform/ prefix" work is dropped.
- **2026-09-14 (Phase 1) — `@PreAuthorize` on controllers dropped.** tenancy-service
  uses path-based reactive security (`SecurityConfig.java:15-32`, `.anyExchange().authenticated()`);
  no existing controller in this service uses method-level `@PreAuthorize`.
  Role enforcement lives at the gateway (`middleware.RequireSuperAdmin()` on
  the `/api/v1/platform/*` group). Java layer just requires authentication.
- **2026-09-14 (Phase 1) — Controller paths align with existing convention.**
  Plan said `/admin/platform-settings`; existing tenancy-service controllers
  use `/api/v1/<resource>` (see `TenantController`, `PlatformStatsController`).
  Endpoints move to `/api/v1/platform/settings`, `/api/v1/platform/feature-flags/{key}`,
  `/api/v1/public/platform/branding`, `/api/v1/public/platform/logo`. Angular
  AdminService already targets `/platform/settings`, so no client-side URL churn.
- **2026-09-14 (Phase 1) — Gateway proxy pattern, not handler methods.** Plan
  said "add three routes to `platformHandler.Register()`" but that mount is for
  aggregation handlers, not proxies. Instead, register the platform-settings
  and feature-flags paths in `routes/routes.go` **before** the `platformGroup`
  mount, using `proxy.Handler(cfg.TenancyServiceURL)`. Fiber's first-match
  dispatch picks them up ahead of the aggregator. Public branding + logo
  endpoints register outside `middleware.RequireSuperAdmin()` and are also
  whitelisted in `jwt.go` `isPublicPath()` so the gateway skips JWT validation.
- **2026-09-14 (Phase 1) — `PlatformFeatureFlag` implements `Persistable<String>`.**
  R2DBC treats any entity with a pre-populated `@Id` as UPDATE on `save()`
  (the "Row with Id X does not exist" trap, per `bug_r2dbc_pre_populated_id_update_mode`).
  Since the flag key is the enum name, it's always non-null before the first
  insert. Implementing `Persistable<String>` with a transient `newRow` flag
  (set by the seeder before the first save) forces INSERT mode.
- **2026-09-14 (Phase 1) — Isolated test-migration folder.** `PlatformSettingsIT`
  uses `classpath:db/platform-settings-it-migration/` instead of the shared
  `classpath:db/test-migration`. Sharing the folder pulled in an unrelated
  `V007__schemes_currency_code.sql` that expected a `schemes` table absent
  from the platform-settings IT context. Isolated folders keep IT slices
  independent.
- **2026-09-14 (Phase 1) — `PlatformFlagSeeder` uses `.blockLast()`.** The plan's
  `.subscribe()` was fire-and-forget, causing tests to run before rows landed.
  Blocking on startup is fine (the whole chain is ~10 short queries) and
  prevents a race where the first admin toggle hits "flag row missing".

- **2026-09-20 (Phase 2) - `logoUrl` is server-derived, so no follow-up PUT.**
  The plan's `onLogoSelected` chained `uploadPlatformLogo` into
  `updatePlatformSettings({ logoUrl })`. Phase 1 as built persists the bytes
  inside the upload endpoint and derives `logoUrl` from them, and
  `UpdatePlatformSettingsRequest` has no `logoUrl` field at all. The client
  now chains the upload into a re-`GET` of the settings row, purely to pick
  up the new cache-busted URL for the preview.
- **2026-09-20 (Phase 2) - theme picker reads the shared template catalogue.**
  The plan kept the page's hard-coded five-theme list (`Ocean Breeze`,
  `Sunset`, `Royal`, ...). Those names match nothing in
  `TENANT_TEMPLATES`, and the DB column stores a `themeTemplateId`. The
  Appearance tab now renders `BrandingService.getTemplates()` directly, so
  the seven platform/tenant templates are the single catalogue and the
  saved id always resolves.
- **2026-09-20 (Phase 2) - dark-mode variables renamed to the real tokens.**
  The plan's sample block used `--color-text`, `--success-bg`, `--danger-fg`
  and friends; `styles.scss` actually defines `--color-text-primary`,
  `--color-success-light`, etc. The `[data-theme="dark"]` block overrides
  the tokens that exist, and additionally moves the `--color-sidebar-*`
  family and the shadow scale, because the sidebar reads
  `--color-text-primary` for its brand name (dark-on-dark otherwise) and
  4-10%-alpha shadows are invisible on a dark surface.
- **2026-09-20 (Phase 2) - feature-flag bootstrap lives inside the Keycloak
  initializer.** The plan called for "a second APP_INITIALIZER scoped to
  authenticated context". Angular runs initializers concurrently, so a
  standalone one would race the Keycloak login and 401. `FeatureFlagService
  .bootstrap()` is instead awaited inside `initializeKeycloak` right after
  `establishSession`, which is the only point where a JWT is guaranteed.
  `PlatformThemeService.bootstrap()` keeps its own APP_INITIALIZER; it hits
  the unauthenticated branding endpoint.
- **2026-09-20 (Phase 2) - no tenant-branding reset needed in the layout.**
  The plan asked `layout.component.ts` to reset tenant vars on `/platform/*`
  navigation. Not needed: `TenantLayoutComponent` already applies tenant
  branding to its own shell element (`:143`) and resets it on destroy
  (`:215`), so tenant vars never reach `document.body` where the platform
  base layer lives. No change made.
- **2026-09-20 (Phase 2) - Phase 1 gap: `/api/v1/public/**` was not exempt
  from tenant resolution.** The public branding endpoint 400'd with
  "tenant could not be resolved" (gateway) and "Missing X-Tenant-ID header"
  (tenancy-service) - Phase 1's Swagger/curl manual checks had not been run.
  `/api/v1/public` added to `middleware.platformPaths` (gateway) and
  `TenantWebFilter.PLATFORM_PATHS` (shared).
- **2026-09-20 (Phase 2) - Phase 1 gap: the gateway proxy dropped multipart
  bodies.** Logo upload 500'd with "Could not find first boundary" through
  the gateway while succeeding straight against tenancy-service. fasthttp's
  `Request.CopyTo` only carries `bodyRaw`/`body`; once fasthttp has parsed a
  payload into a multipart form both are empty, so the copy went upstream
  with a zero-length body and the original `Content-Length`.
  `proxy.Handler` now re-sets the body with `req.SetBody(c.Request().Body())`,
  guarded by `TestProxy_ForwardsMultipartBody`. This fixes every multipart
  upload through the gateway, not just the logo.
- **2026-09-20 (Phase 2) - Phase 1 gap: audit events violated Rule 8 /
  `feedback_audit_entity_name`.** `PLATFORM_SETTINGS` events shipped
  `changedFields = null`, and `PLATFORM_FEATURE_FLAG` used the raw enum key
  as `entityName`. `PlatformSettingsService` now diffs the before/after
  snapshots (excluding `version`, which bumps on every save), and the flag
  service passes `PlatformFlag.displayName()`. `PlatformSettingsIT` asserts
  both.

- **2026-09-20 (Phase 3) - Java FlagRegistry already exists; broadcast made
  additive instead of a rewrite.** The plan specified a new shared
  `FlagRegistry` `@Component` that bootstraps over HTTP and hot-reloads via
  `@KafkaListener`, exposing a synchronous `boolean`. Since the plan was
  written (against `2a80248e`, 2026-09-13), commit `357e2eee` (2026-09-15,
  AI Tranche 0) landed `com.medfund.shared.flags.FlagRegistry` as an
  interface returning `Mono<Boolean>`, backed by `FlagRegistryR2dbc` which
  reads `public.platform_feature_flags` with a 30-second Caffeine TTL. It is
  already consumed by claims-service `AiServiceClient` and
  contributions-service `AiPricingClient`, and covered by
  `AiServiceClientFlagTest`. Implementing the plan literally would mean
  rewriting a live, tested abstraction and its two call sites, or shipping two
  competing registries in one package. Dev chose the additive route:
  the existing registry and every call site stay as they are, and the Kafka
  broadcast is wired in as a *cache-invalidation* signal rather than a
  replacement read path. The 30s TTL becomes the fallback, not the mechanism.
- **2026-09-20 (Phase 3) - publisher uses reactor-kafka `KafkaSender`.** The
  plan's snippet used `KafkaTemplate`. No Java service has a `KafkaTemplate`
  bean; `AuditPublisher` and every other producer use reactor-kafka's
  `KafkaSender`. `FlagEventPublisher` mirrors `AuditPublisher` exactly.
- **2026-09-20 (Phase 3) - consumer mirrors `PermissionInvalidationConsumer`.**
  Rather than `@KafkaListener`, the shared consumer copies the existing
  cache-invalidation consumer in `com.medfund.shared.security`: a
  `KafkaReceiver` wired through a `@ConditionalOnBean(ReceiverOptions.class)`
  bean, with `.doOnSuccess` offset ack and full-cause logging per
  `bug_reactor_kafka_ack_swallow`. This is the codebase's established
  broadcast-invalidation pattern and it gives each service its own consumer
  group for free.
- **2026-09-20 (Phase 3) - Go bootstrap endpoint follows the `/internal/v1`
  precedent.** The plan offered `/internal/feature-flags` permitAll or a
  service-account JWT. tenancy-service already has the former pattern:
  `SecurityConfig` permits `GET /internal/**` and
  `InternalMarketDataConfigController` serves the Go market-data-service at
  `/internal/v1/market-data-config/enabled`. The new endpoint is
  `GET /internal/v1/feature-flags`, not exposed through the gateway.
- **2026-09-20 (Phase 3) - pre-existing bug: `/internal/**` was not exempt from
  tenant resolution.** `curl http://localhost:8081/internal/v1/market-data-config/enabled`
  400s with "Missing X-Tenant-ID header", and `market-data-service`'s client
  sends no such header, so that Phase 24 integration is broken at runtime
  today. `/internal` added to `TenantWebFilter.PLATFORM_PATHS`, which fixes
  the existing endpoint as well as the new one. Same class as the
  `/api/v1/public` gap found in Phase 2.
- **2026-09-20 (Phase 3) - Go registry wired into six services, not five.** The
  plan listed gateway, notification, audit, file and payment. `market-data-service`
  is a sixth Go service on the same `medfund/shared` + `kafka-go` stack; it gets
  the same wiring for symmetry.

- **2026-09-20 (Phase 3) - flag broadcast bounded by a 2s timeout.** The
  reactor-kafka producer inherits Kafka's 60-second `max.block.ms` default, so
  an unreachable broker stalls the publish rather than erroring. Without a
  bound, "toggle a flag with Kafka down" hung the admin's request for a full
  minute. `FlagEventPublisher.PUBLISH_TIMEOUT` caps the attempt at 2s and then
  swallows the failure: the row is already committed and consumers still pick
  the change up on their next 30s cache expiry.
- **2026-09-20 (Phase 3) - pre-existing: `AuditPublisher` has the same unbounded
  publish, and it is NOT fixed here.** With Kafka stopped, `PUT /platform/settings`
  (which never touches the flag publisher) also hangs, because
  `AuditPublisher.publish` is chained into the request path with no timeout. This
  affects every audited mutation in every Java service, so the Phase 3 criterion
  "no in-flight request hangs" is only half met: the flag broadcast no longer
  blocks, the audit publish ahead of it still does. Bounding `AuditPublisher`
  or setting `max.block.ms` on the shared producer config changes behaviour for
  the whole platform and is left for the dev to scope.
- **2026-09-20 (Phase 3) - "Bootstrapped 5 flags" now applies to Go, not Java.**
  The plan's manual step asked claims-service to log a bootstrap line. Under the
  additive design the Java registry reads Postgres on demand and has nothing to
  bootstrap; the Go registry is the one that seeds over HTTP, and it logs
  `[flags] bootstrapped 5 flags from <url>`.
- **2026-09-20 (Phase 3) - shared IT fixture matcher made `protected`.** Adding
  `FlagBroadcastIT` broke `PlatformSettingsIT`: both publish
  `PLATFORM_FEATURE_FLAG` audit events to the Kafka container shared by every IT
  in the JVM, and the fixture's `consumeAuditEvent(topic, entityType, ...)`
  returns the *first* matching record, whichever class published it.
  `consumeAuditEventMatching(topic, predicate, timeout)` is now `protected` on
  both `AbstractIntegrationTest` and `AbstractKafkaIntegrationTest`, and both ITs
  filter on something that identifies their own event.

- **2026-09-20 (Phase 4) - `KeycloakRealmSyncIT` stubs Keycloak with MockWebServer,
  not a Keycloak testcontainer.** No Keycloak container exists anywhere in the
  Java tree today, and booting one costs ~30s plus a new dependency. The
  assertion that matters is the shape of the outgoing admin REST call (method,
  path, bearer header, payload), and the codebase already stubs peer HTTP this
  way (finance-service `CollectionRateTrendControllerIT`,
  `Ifrs17JobServiceIT`). `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`
  added to tenancy-service, same version finance-service pins. The real realm
  round-trip is covered by the live-stack verification below, which was run.
- **2026-09-20 (Phase 4) - the logo `<img src>` is absolute, from a new
  `platform.public-base-url` property.** The plan's `buildDisplayNameHtml` read
  `settings.getLogoUrl()`, but the Phase 1 deviation moved logo storage to
  Postgres bytea and there is no `logo_url` column. The derived path is
  relative (`/api/v1/public/platform/logo?v=...`), and Keycloak renders the
  login page on its own origin, so a relative src would resolve against
  `:9080`. `platform.public-base-url` (default `http://localhost:3000`, the
  gateway; also set in docker-compose) prefixes it. `keycloak.platform-realm`
  is likewise a property rather than the hard-coded literal.
- **2026-09-20 (Phase 4) - `HtmlUtils.htmlEscape`, not commons-text.** The plan
  called for `org.apache.commons.text.StringEscapeUtils.escapeHtml4`;
  commons-text is not on the classpath and `ProducerBackfillJob` records the
  house preference for avoiding it. Spring's `org.springframework.web.util.HtmlUtils`
  is already there via spring-web.
- **2026-09-20 (Phase 4) - `PlatformSettings.logoPath()` extracted.** Phase 4
  would have been the third copy of the "has bytes? then build the cache-busted
  URL" logic (it already sat in `PlatformSettingsResponse.from` and
  `PublicBrandingResponse.from`). Now one derived accessor on the entity, and
  `uploadLogo` returns it too instead of rebuilding the string inline.
- **2026-09-20 (Phase 4) - the realm sync also fires on logo upload.** The plan
  hooked `PlatformSettingsService.update` only. But the logo reaches the DB via
  `uploadLogo`, and Angular follows it with a re-GET, never a PUT, so a new logo
  would not have reached the login screen until the next unrelated save. Both
  mutations now sync. Same best-effort semantics.
- **2026-09-20 (Phase 4) - `PlatformSettingsIT` pinned to a dead Keycloak URL,
  and `KeycloakRealmSyncIT` restores the singleton row.** `update()` now talks
  to Keycloak, so on a developer machine with `make infra` up the IT would have
  patched the real dev realm; `keycloak.admin.url=http://localhost:1` makes it
  fail fast instead. Separately, the two ITs share the per-JVM Postgres
  container and the per-JVM Kafka topic, so `KeycloakRealmSyncIT` uses distinct
  platform names ("MedFund Realm Sync" / "MedFund Realm Offline" - the shared
  audit topic is matched by `newValue.platformName`) and restores the V183 seed
  values in `@AfterAll`. Same shared-fixture hazard as the Phase 3 deviation.
- **2026-09-20 (Phase 4) - XSS renders as nothing, not as an escaped literal.**
  The plan's criterion expected the login screen to show the literal
  `<script>alert(1)</script>`. What actually happens: we store the escaped form
  (`&lt;script&gt;...` - confirmed in the realm) and Keycloak's own theme
  sanitizer then strips the entity-encoded text, so the `<h1>` renders empty. No
  script executes, which is the point, but the visible outcome is blank rather
  than a literal.

## Overview

The super-admin `/platform/settings` page renders four tabs today (General, Appearance, Email Templates, Feature Flags) but is **almost entirely non-functional**: none of the Save buttons have click handlers, the three GET endpoints it calls return 404, and no backing tables exist. This plan wires the page end-to-end: three tabs (General, Appearance, Feature Flags) go real with persistence, consumption, and audit; the Email Templates tab is removed. A minimal dark theme, a platform-scoped BrandingService layer, a shared feature-flag registry with Kafka hot-reload, and Keycloak realm-branding sync are introduced along the way.

## Current State Analysis

### Frontend

- `clients/angular/src/app/pages/platform/settings/settings.component.ts:1-75` — 4-tab component. `ngOnInit` calls three GETs; Save buttons at `.html:43,80,94` have no `(click)` handler; the logo drop-zone at `.html:37-42` has no file-handling. Feature-flag toggles at `.html:133-136` mutate local state only.
- `clients/angular/src/app/core/services/admin.service.ts:822-832` — three GET methods (`getPlatformSettings`, `getEmailTemplates`, `getFeatureFlags`) hitting `/platform/settings`, `/platform/email-templates`, `/platform/feature-flags`. **No corresponding write methods exist.**
- `clients/angular/src/app/core/services/branding.service.ts` — bidirectional; `apply(element, branding)` sets CSS custom properties; `parseBranding` reads JSON. 7 built-in template presets. **Currently only invoked with tenant branding**; no consumer for a platform-level layer.
- `clients/angular/src/app/auth/auth.guard.ts:21-41` — `roleGuard(['super_admin'])` already protects every `/platform/*` route (`app.routes.ts:11`). No additional auth wiring needed on the client.
- **No file-upload primitive** in `clients/angular/src/app/shared/components/`.
- **No dark-mode CSS system** exists; no `[data-theme]` hook, no dark override variables.

### Backend

- The three GETs the client calls are **not registered anywhere**. Gateway `services/go/gateway/internal/platform/handler.go:31-43` mounts only `/stats`, `/health`, `/activity`, `/analytics/*` under `/api/v1/platform/*`. No `platform-settings`, `email-templates`, or `feature-flags` handler.
- No `public.platform_settings` or `public.platform_feature_flags` tables exist. Latest tenancy-service `public` migration is `V182__grant_report_config_tables_to_tenant_roles.sql`.
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/KeycloakRealmService.java` — already uses WebClient against Keycloak Admin REST API (`getAdminToken` + realm create). Already sets `realmConfig.put("displayName", ...)` at line 67. **Phase 4 extends this, no new dependency needed.**
- Per-tenant email templates already exist end-to-end (`public.tenant_email_templates` table + `TenantController.upsertEmailTemplate` + audit). Not relevant to this plan since the Email Templates tab is being removed.
- Audit helpers exist across all Java services: `services/java/shared/src/main/java/com/medfund/shared/audit/AuditEvent.java`, `AuditActor.java`, `AuditPublisher.java` — the required pattern (see `feedback_audit_actor_email` memory) is `AuditEvent.create(...)` with `AuditActor.id(jwt) + AuditActor.email(jwt)`, and `entityName` must be a friendly string, never a UUID.
- Keycloak platform login realm is `medfund-platform` (confirmed in `docker-compose.yml:175` and `clients/angular/src/environments/environment.ts:6`). Phase 4 targets this realm.

### Key constraints

- **9 Critical Rules** apply throughout: every mutation is audit-logged (rule 8); every endpoint documented in Swagger (rule 7); Kafka events for side-effect propagation (rule 6, applies to flag broadcast); PII/security events logged (rule 9, super-admin actions all qualify).
- **Never edit an applied migration** (`feedback_never_edit_applied_migrations`) — new migrations get fresh higher numbers.
- **`public.<tenant-table>` prefix is fine for platform-wide tables** (`bug_public_prefix_silent_rollback`) — both new tables live in `public.` schema by design (they are cross-tenant).
- **No em dashes** anywhere in code, commit messages, or files this plan writes (`feedback_no_em_dashes`).

## Desired End State

After all 4 phases:

1. Super admin navigates to `/platform/settings`, sees three tabs (General, Appearance, Feature Flags) each with real values from `public.platform_settings` and `public.platform_feature_flags`.
2. Every Save button persists to `tenancy-service` via authenticated PUT, emits an audit event, and shows a toast on success.
3. The Appearance tab's theme picker + dark-mode toggle immediately re-skins the `/platform/*` portal (via `BrandingService` layer swap), and the login/pre-auth screen reflects the platform theme.
4. The logo uploader accepts SVG/PNG/JPG up to 2 MB, uploads via a new `tenancy-service` endpoint to MinIO `platform/` prefix, and the returned URL populates `platform_settings.logo_url`.
5. Toggling a feature flag persists to DB, broadcasts on `platform.feature-flags.v1` Kafka topic, and every Java + Go service updates its in-process `FlagRegistry` within seconds. Angular clients pick up the new state on next full-page reload (deferred: SSE push to the client).
6. Saving General or Appearance also patches Keycloak's `medfund-platform` realm (`displayName`, `displayNameHtml`, logo URL) so the login screen reflects the change.

### Verification of end state
- Curl `GET /api/v1/platform/settings` returns a JSON body with the persisted values.
- Curl `PUT` with a modified body then re-`GET` reads the new values.
- `SELECT * FROM public.platform_settings` returns exactly one row.
- `SELECT key, enabled FROM public.platform_feature_flags` returns 5 rows (the seeded catalogue).
- Logging out, hitting the login screen, and seeing the platform logo + hero title above the login form.

### Key Discoveries
- Keycloak admin-client is **already wired** in `services/java/tenancy-service/.../service/KeycloakRealmService.java` via direct WebClient + admin-token flow. Phase 4 adds a `updatePlatformRealmBranding(...)` method to that same class.
- `BrandingService.apply` at `clients/angular/src/app/core/services/branding.service.ts:199` takes an `HTMLElement` target — we can call it twice with different sources (platform base, then tenant overlay) by targeting different elements or by clear + re-apply.
- Shared audit + Kafka publishers already exist (`services/java/shared/src/main/java/com/medfund/shared/audit/AuditPublisher.java:18`). New flag-broadcast publisher extends the same pattern.
- `services/java/tenancy-service/src/main/resources/db/migration/public/` is the correct target for cross-tenant migrations; latest is `V182`. New migrations start at `V183`.

## What We're NOT Doing

- **Email Templates tab / editable defaults** — dropped from this page entirely. Per-tenant overrides on `public.tenant_email_templates` remain the only editing surface; notification-service continues to use its code-defined `CATALOGUE`. A future plan can promote defaults to DB.
- **Per-tenant feature-flag overrides** — flags are platform-wide only. A per-tenant override table is a separate plan.
- **Add/remove flags via UI** — catalogue is hard-coded in a Java enum. Admin toggles `enabled` only.
- **Custom Keycloak login theme (Freemarker templates, message bundles)** — MVP uses `displayName` + `displayNameHtml` on the realm only. A full custom login theme is a separate plan.
- **`supportEmail` consumer** — persisted only. Wiring it as the "From" address on outbound notifications is a follow-up.
- **Dark-mode overrides for every screen** — the minimal dark theme covers the ~15 root CSS custom properties. A per-page audit for hard-coded colors is documented as manual verification; fixing offenders is scoped per-page and can happen as regressions surface.
- **File-service `platform/` scope refactor** — logo upload goes through a new `tenancy-service` multipart endpoint that writes to MinIO directly, not through file-service.
- **Server-Sent-Events push of flag changes to the Angular client** — client picks up new flag state on next bootstrap. Deferred.

## Implementation Approach

Four phases, each independently verifiable. Order matters:

1. **Phase 1** stands up the storage + endpoints in tenancy-service. Verifiable via curl and Swagger UI.
2. **Phase 2** wires the Angular page against Phase 1 endpoints, adds `PlatformThemeService`, extends `BrandingService`, builds `LogoUploaderComponent`, adds minimal dark theme, drops the Email Templates tab. Verifiable in the browser via the `verify` skill.
3. **Phase 3** adds the Kafka broadcast + shared `FlagRegistry` in Java + Go, so services can consume flags. No service starts *reading* a flag in this phase (plumbing only). Verifiable by toggling a flag and reading consumer logs.
4. **Phase 4** extends `KeycloakRealmService` to patch the `medfund-platform` realm on save. Isolated because it depends on a running Keycloak, and can slip without blocking phases 1-3.

Kafka contract discipline: `platform.feature-flags.v1` is additive-only. Payload fields can be added but never removed or renamed without a `v2` topic.

---

## Phase 1: Backend foundation (tenancy-service)

### Overview
Land the storage, entities, repositories, REST endpoints, audit emission, and Swagger for platform settings and feature flags. Nothing external consumes any of this yet.

### Changes Required

#### 1. Flyway migration — platform_settings

**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V183__platform_settings.sql`

Single-row config. Enforced by a boolean `singleton` column with a partial-unique index.

```sql
CREATE TABLE IF NOT EXISTS public.platform_settings (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton          BOOLEAN NOT NULL DEFAULT TRUE,
    platform_name      VARCHAR(200),
    support_email      VARCHAR(320),
    logo_url           VARCHAR(1024),
    theme_template_id  VARCHAR(50)  NOT NULL DEFAULT 'ocean',
    dark_mode          BOOLEAN      NOT NULL DEFAULT FALSE,
    hero_title         VARCHAR(200),
    hero_subtitle      VARCHAR(500),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by         VARCHAR(320),
    version            BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_platform_settings_singleton
    ON public.platform_settings (singleton) WHERE singleton = TRUE;

INSERT INTO public.platform_settings (platform_name, theme_template_id)
VALUES ('MedFund', 'ocean')
ON CONFLICT DO NOTHING;
```

#### 2. Flyway migration — platform_feature_flags

**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V184__platform_feature_flags.sql`

Row per flag key; catalogue is defined in Java and seeded via `MERGE` on startup (see step 5 below), so the migration only creates the table.

```sql
CREATE TABLE IF NOT EXISTS public.platform_feature_flags (
    key         VARCHAR(64)  PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(320),
    version     BIGINT       NOT NULL DEFAULT 0
);
```

#### 3. R2DBC entities

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/PlatformSettings.java`

```java
@Getter
@Setter
@Table("platform_settings")
public class PlatformSettings {
    @Id private UUID id;
    private Boolean singleton;
    private String platformName;
    private String supportEmail;
    private String logoUrl;
    private String themeTemplateId;
    private Boolean darkMode;
    private String heroTitle;
    private String heroSubtitle;
    private OffsetDateTime updatedAt;
    private String updatedBy;
    private Long version;
}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/PlatformFeatureFlag.java`

```java
@Getter
@Setter
@Table("platform_feature_flags")
public class PlatformFeatureFlag {
    @Id private String key;
    private Boolean enabled;
    private OffsetDateTime updatedAt;
    private String updatedBy;
    private Long version;
}
```

Both entities follow Java conventions from `.claude/CLAUDE.md` (@Getter/@Setter on R2DBC entities, never @Data).

#### 4. Repositories

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/repository/PlatformSettingsRepository.java`

```java
public interface PlatformSettingsRepository extends ReactiveCrudRepository<PlatformSettings, UUID> {
    Mono<PlatformSettings> findFirstBySingletonIsTrue();
}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/repository/PlatformFeatureFlagRepository.java`

```java
public interface PlatformFeatureFlagRepository extends ReactiveCrudRepository<PlatformFeatureFlag, String> {
    Flux<PlatformFeatureFlag> findAllByOrderByKeyAsc();
}
```

#### 5. Flag catalogue enum + startup seeder

**File**: `services/java/shared/src/main/java/com/medfund/shared/flags/PlatformFlag.java`

Lives in shared so downstream Java services (Phase 3) can reference the enum without depending on tenancy-service.

```java
public enum PlatformFlag {
    AI_ADJUDICATION("AI-assisted claims adjudication",
        "Enable the AI service to score and pre-adjudicate claims. When off, claims go straight to human queue."),
    FRAUD_DETECTION("Fraud detection scoring",
        "Enable the AI fraud model on submitted claims. When off, no fraud flags are attached."),
    GROUP_PORTAL("Group liaison portal",
        "Enable the /group/* portal so corporate group liaisons can self-serve enrollments and reports."),
    PROVIDER_PORTAL("Provider portal",
        "Enable the /provider/* portal for healthcare providers to submit claims and view remittances."),
    MOBILE_PWA("Member PWA",
        "Enable the Flutter member PWA at /member/*. When off, the app-shell 404s.");

    private final String displayName;
    private final String description;

    PlatformFlag(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    public String displayName() { return displayName; }
    public String description() { return description; }
}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/config/PlatformFlagSeeder.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformFlagSeeder implements ApplicationRunner {
    private final PlatformFeatureFlagRepository repo;

    @Override
    public void run(ApplicationArguments args) {
        Flux.fromArray(PlatformFlag.values())
            .flatMap(flag -> repo.findById(flag.name())
                .switchIfEmpty(Mono.defer(() -> {
                    var row = new PlatformFeatureFlag();
                    row.setKey(flag.name());
                    row.setEnabled(false);
                    row.setUpdatedAt(OffsetDateTime.now());
                    row.setUpdatedBy("system");
                    return repo.save(row);
                })))
            .doOnNext(row -> log.debug("Flag catalogue: {} enabled={}", row.getKey(), row.getEnabled()))
            .subscribe();
    }
}
```

#### 6. DTOs

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/dto/PlatformSettingsResponse.java`

```java
public record PlatformSettingsResponse(
    String platformName,
    String supportEmail,
    String logoUrl,
    String themeTemplateId,
    Boolean darkMode,
    String heroTitle,
    String heroSubtitle,
    OffsetDateTime updatedAt,
    String updatedBy
) {
    public static PlatformSettingsResponse from(PlatformSettings e) {
        return new PlatformSettingsResponse(e.getPlatformName(), e.getSupportEmail(), e.getLogoUrl(),
            e.getThemeTemplateId(), e.getDarkMode(), e.getHeroTitle(), e.getHeroSubtitle(),
            e.getUpdatedAt(), e.getUpdatedBy());
    }
}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/dto/UpdatePlatformSettingsRequest.java`

```java
public record UpdatePlatformSettingsRequest(
    @Size(max = 200) String platformName,
    @Email @Size(max = 320) String supportEmail,
    @Size(max = 1024) String logoUrl,
    @Size(max = 50) String themeTemplateId,
    Boolean darkMode,
    @Size(max = 200) String heroTitle,
    @Size(max = 500) String heroSubtitle
) {}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/dto/PlatformFeatureFlagResponse.java`

```java
public record PlatformFeatureFlagResponse(String key, String name, String description, Boolean enabled,
                                          OffsetDateTime updatedAt, String updatedBy) {
    public static PlatformFeatureFlagResponse from(PlatformFeatureFlag row, PlatformFlag meta) {
        return new PlatformFeatureFlagResponse(row.getKey(), meta.displayName(), meta.description(),
            row.getEnabled(), row.getUpdatedAt(), row.getUpdatedBy());
    }
}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/dto/UpdateFlagRequest.java`

```java
public record UpdateFlagRequest(@NotNull Boolean enabled) {}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/dto/PublicBrandingResponse.java`

Used by the unauthenticated pre-auth endpoint.

```java
public record PublicBrandingResponse(String platformName, String logoUrl, String themeTemplateId,
                                     Boolean darkMode, String heroTitle, String heroSubtitle) {
    public static PublicBrandingResponse from(PlatformSettings e) {
        return new PublicBrandingResponse(e.getPlatformName(), e.getLogoUrl(),
            e.getThemeTemplateId(), e.getDarkMode(), e.getHeroTitle(), e.getHeroSubtitle());
    }
}
```

#### 7. Service layer

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/PlatformSettingsService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {
    private final PlatformSettingsRepository repo;
    private final AuditPublisher auditPublisher;
    private final ObjectMapper mapper;

    public Mono<PlatformSettings> get() {
        return repo.findFirstBySingletonIsTrue()
            .switchIfEmpty(Mono.error(new IllegalStateException("Platform settings row missing; check V183 migration")));
    }

    public Mono<PlatformSettings> update(UpdatePlatformSettingsRequest req, String actorId, String actorEmail) {
        return get().flatMap(existing -> {
            var before = clone(existing);
            if (req.platformName()     != null) existing.setPlatformName(req.platformName());
            if (req.supportEmail()     != null) existing.setSupportEmail(req.supportEmail());
            if (req.logoUrl()          != null) existing.setLogoUrl(req.logoUrl());
            if (req.themeTemplateId()  != null) existing.setThemeTemplateId(req.themeTemplateId());
            if (req.darkMode()         != null) existing.setDarkMode(req.darkMode());
            if (req.heroTitle()        != null) existing.setHeroTitle(req.heroTitle());
            if (req.heroSubtitle()     != null) existing.setHeroSubtitle(req.heroSubtitle());
            existing.setUpdatedAt(OffsetDateTime.now());
            existing.setUpdatedBy(actorEmail != null ? actorEmail : actorId);
            existing.setVersion(existing.getVersion() + 1);
            return repo.save(existing)
                .flatMap(saved -> emitAudit(before, saved, actorId, actorEmail).thenReturn(saved));
        });
    }

    private Mono<Void> emitAudit(PlatformSettings before, PlatformSettings after, String actorId, String actorEmail) {
        return auditPublisher.publish(AuditEvent.create("UPDATE", "platform_settings",
            after.getId().toString(), "Platform settings",
            mapper.valueToTree(PlatformSettingsResponse.from(before)),
            mapper.valueToTree(PlatformSettingsResponse.from(after)),
            actorId, actorEmail));
    }

    private PlatformSettings clone(PlatformSettings src) { /* field copy */ }
}
```

Audit conventions:
- `entityName = "Platform settings"` (friendly text, never a UUID; see `feedback_audit_entity_name`).
- `actorId + actorEmail` come from `AuditActor.id(jwt) + AuditActor.email(jwt)` at the controller layer (see `feedback_audit_actor_email`).

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/PlatformFeatureFlagService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformFeatureFlagService {
    private final PlatformFeatureFlagRepository repo;
    private final AuditPublisher auditPublisher;

    public Flux<PlatformFeatureFlagResponse> list() {
        return repo.findAllByOrderByKeyAsc().mapNotNull(row -> {
            try {
                return PlatformFeatureFlagResponse.from(row, PlatformFlag.valueOf(row.getKey()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown flag key in DB: {} (removed from enum?)", row.getKey());
                return null;
            }
        });
    }

    public Mono<PlatformFeatureFlagResponse> update(String key, boolean enabled, String actorId, String actorEmail) {
        PlatformFlag meta;
        try { meta = PlatformFlag.valueOf(key); }
        catch (IllegalArgumentException e) { return Mono.error(new IllegalArgumentException("Unknown flag: " + key)); }

        return repo.findById(key)
            .switchIfEmpty(Mono.error(new IllegalStateException("Flag row missing; seeder didn't run?")))
            .flatMap(row -> {
                boolean before = Boolean.TRUE.equals(row.getEnabled());
                row.setEnabled(enabled);
                row.setUpdatedAt(OffsetDateTime.now());
                row.setUpdatedBy(actorEmail != null ? actorEmail : actorId);
                row.setVersion(row.getVersion() + 1);
                return repo.save(row).flatMap(saved -> emitAudit(key, before, enabled, actorId, actorEmail)
                    .thenReturn(PlatformFeatureFlagResponse.from(saved, meta)));
            });
    }
    // emitAudit(...) same pattern as PlatformSettingsService
}
```

#### 8. Controllers

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/PlatformSettingsController.java`

```java
@Slf4j
@RestController
@RequestMapping("/admin/platform-settings")
@RequiredArgsConstructor
@Tag(name = "Platform Settings", description = "Super-admin platform-wide configuration")
public class PlatformSettingsController {
    private final PlatformSettingsService svc;

    @GetMapping
    @PreAuthorize("hasRole('super_admin')")
    @Operation(summary = "Get platform settings")
    public Mono<PlatformSettingsResponse> get() { return svc.get().map(PlatformSettingsResponse::from); }

    @PutMapping
    @PreAuthorize("hasRole('super_admin')")
    @Operation(summary = "Update platform settings")
    public Mono<PlatformSettingsResponse> update(@Valid @RequestBody UpdatePlatformSettingsRequest req,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return svc.update(req, AuditActor.id(jwt), AuditActor.email(jwt)).map(PlatformSettingsResponse::from);
    }

    @PostMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('super_admin')")
    @Operation(summary = "Upload platform logo (SVG/PNG/JPG, max 2MB)")
    public Mono<Map<String, String>> uploadLogo(@RequestPart("file") FilePart file,
                                                @AuthenticationPrincipal Jwt jwt) {
        return svc.uploadLogo(file, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(url -> Map.of("logoUrl", url));
    }
}
```

`uploadLogo` in `PlatformSettingsService` validates mime + size, writes to MinIO under `platform/logo-<epoch>.<ext>`, and returns the public URL. It **does not** update `platform_settings.logo_url` — that's a separate PUT after the client confirms the upload, so a failed upload never leaves a broken URL.

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/PlatformFeatureFlagController.java`

```java
@RestController
@RequestMapping("/admin/feature-flags")
@RequiredArgsConstructor
@Tag(name = "Feature Flags", description = "Platform-wide feature toggles")
public class PlatformFeatureFlagController {
    private final PlatformFeatureFlagService svc;

    @GetMapping
    @PreAuthorize("hasRole('super_admin')")
    public Flux<PlatformFeatureFlagResponse> list() { return svc.list(); }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('super_admin')")
    public Mono<PlatformFeatureFlagResponse> update(@PathVariable String key,
                                                    @Valid @RequestBody UpdateFlagRequest req,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return svc.update(key, req.enabled(), AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
```

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/PublicBrandingController.java`

Unauthenticated read-only endpoint for the pre-auth Angular surface.

```java
@RestController
@RequestMapping("/public/platform")
@RequiredArgsConstructor
public class PublicBrandingController {
    private final PlatformSettingsService svc;

    @GetMapping("/branding")
    public Mono<PublicBrandingResponse> get() { return svc.get().map(PublicBrandingResponse::from); }
}
```

Register `/public/**` as `permitAll` in `SecurityConfig`.

#### 9. Gateway routes

**File**: `services/go/gateway/internal/platform/handler.go`

Add three routes to the existing `Register()` method (line 31-43):

```go
platformGroup.Get("/settings",          proxyTo("http://tenancy-service:8081/admin/platform-settings"))
platformGroup.Put("/settings",          proxyTo("http://tenancy-service:8081/admin/platform-settings"))
platformGroup.Post("/settings/logo",    proxyTo("http://tenancy-service:8081/admin/platform-settings/logo"))
platformGroup.Get("/feature-flags",     proxyTo("http://tenancy-service:8081/admin/feature-flags"))
platformGroup.Put("/feature-flags/:key", proxyTo("http://tenancy-service:8081/admin/feature-flags/{key}"))
```

**File**: `services/go/gateway/cmd/main.go` (or wherever public route mounting lives) — add:

```go
app.Get("/api/v1/public/platform/branding", proxyTo("http://tenancy-service:8081/public/platform/branding"))
```

The public route is registered outside `middleware.RequireSuperAdmin()`.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :tenancy-service:compileJava` — PASS
- [x] Unit + integration tests pass for the new IT: `./gradlew :tenancy-service:test --tests com.medfund.tenancy.integration.PlatformSettingsIT` — 4/4 PASS. Full `:tenancy-service:test` shows 228 pass + 2 pre-existing failures unrelated to this work (`TenantMigrationFlywayIT.v102_...` and `v111_to_v113_...`).
- [x] V183 + V184 migrations apply against a fresh testcontainer Postgres — PlatformSettingsIT boots the schema via its isolated migration folder; V183/V184 shape is validated in-process.
- [x] Go gateway compiles: `cd services/go/gateway && go build ./...` — PASS
- [x] Go gateway routes test suite passes: `go test ./internal/routes/...` — PASS, new `TestPlatformSettingsRoutesRegistered` asserts all 7 new routes are registered.
- [x] Swagger renders the new endpoints at `http://localhost:8081/swagger-ui` — verified 2026-09-20 via `/v3/api-docs`: all 6 endpoints present with summaries under the Platform Settings, Platform Feature Flags, and Public Branding tags.

#### Manual Verification
- [x] `curl -H "Authorization: Bearer $SUPER_ADMIN_JWT" http://localhost:3000/api/v1/platform/settings` returns the seeded row — verified 2026-09-20 during Phase 2
- [x] `curl -X PUT ... /platform/settings -d '{"platformName":"MedFund Zim"}'` persists (verify with a re-GET) — verified 2026-09-20
- [x] `curl -X POST ... /platform/settings/logo -F "file=@logo.png"` returns `{"logoUrl":"/api/v1/public/platform/logo?v=..."}` and `curl -o out.png <logoUrl>` fetches the bytes back — verified 2026-09-20 after the gateway multipart fix (see Deviations)
- [x] `curl http://localhost:3000/api/v1/public/platform/branding` (no auth) returns 200 with the branding payload — verified 2026-09-20 after the tenant-resolution fix (see Deviations)
- [x] Swagger UI at `http://localhost:8081/swagger-ui/index.html` lists Platform Settings, Platform Feature Flags, Public Branding groups with all endpoints — verified 2026-09-20
- [x] `SELECT actor_email, action, entity_name, changed_fields FROM public.audit_events WHERE entity_type IN ('PLATFORM_SETTINGS','PLATFORM_FEATURE_FLAG')` shows the super admin's email, friendly entity names, and a populated changed-field list — verified 2026-09-20

**Implementation Note**: pause after this phase for manual verification before starting Phase 2.

---

## Phase 2: Angular wiring + platform theme + dark mode

### Overview
Wire the Angular Settings page against Phase 1 endpoints. Build `LogoUploaderComponent`, `PlatformThemeService`, `FeatureFlagService`. Extend `BrandingService` to layer tenant vars over a platform base. Add minimal dark-mode CSS system. Drop the Email Templates tab.

### Changes Required

#### 1. AdminService write methods

**File**: `clients/angular/src/app/core/services/admin.service.ts`

Add after line 832:

```typescript
updatePlatformSettings(patch: Partial<PlatformSettings>): Observable<PlatformSettings> {
  return this.api.put<PlatformSettings>('/platform/settings', patch);
}

uploadPlatformLogo(file: File): Observable<{ logoUrl: string }> {
  const form = new FormData();
  form.append('file', file);
  return this.api.postMultipart<{ logoUrl: string }>('/platform/settings/logo', form);
}

updateFeatureFlag(key: string, enabled: boolean): Observable<FeatureFlag> {
  return this.api.put<FeatureFlag>(`/platform/feature-flags/${key}`, { enabled });
}
```

Add TypeScript interfaces `PlatformSettings` and `FeatureFlag` matching the DTO shapes. Retype `getPlatformSettings` / `getFeatureFlags` return types (no more `any`).

If `ApiService.postMultipart` doesn't exist, add it — thin wrapper around `HttpClient.post` that omits the JSON content-type header so the browser sets multipart boundaries.

#### 2. Wire the Settings component

**File**: `clients/angular/src/app/pages/platform/settings/settings.component.ts`

Drop the `email` tab (line 20). Add save handlers, upload handler, flag-toggle handler. All with toast feedback + inline error banners.

```typescript
tabs = [
  { id: 'general',    label: 'General',        icon: 'settings' },
  { id: 'appearance', label: 'Appearance',     icon: 'globe' },
  { id: 'features',   label: 'Feature Flags',  icon: 'check-circle' },
];

saving = false;
uploading = false;

saveGeneral(): void {
  this.saving = true;
  this.adminService.updatePlatformSettings({
    platformName: this.platformName,
    supportEmail: this.supportEmail,
  }).subscribe({
    next: () => { this.toast.success('Platform identity saved.'); this.saving = false; },
    error: (err) => { this.toast.error(extractErrorMessage(err, 'Save failed')); this.saving = false; },
  });
}

saveAppearance(): void {
  this.saving = true;
  this.adminService.updatePlatformSettings({
    themeTemplateId: this.selectedThemeId,
    darkMode: this.darkMode,
  }).subscribe({
    next: (s) => {
      this.toast.success('Appearance saved.');
      this.platformTheme.applyFromSettings(s);
      this.saving = false;
    },
    error: (err) => { this.toast.error(extractErrorMessage(err, 'Save failed')); this.saving = false; },
  });
}

saveLandingCopy(): void { /* same shape: heroTitle, heroSubtitle */ }

onLogoSelected(file: File): void {
  this.uploading = true;
  this.adminService.uploadPlatformLogo(file).pipe(
    switchMap(({ logoUrl }) => this.adminService.updatePlatformSettings({ logoUrl })),
  ).subscribe({
    next: (s) => {
      this.toast.success('Logo uploaded.');
      this.platformTheme.applyFromSettings(s);
      this.uploading = false;
    },
    error: (err) => { this.toast.error(extractErrorMessage(err, 'Upload failed')); this.uploading = false; },
  });
}

toggleFlag(flag: FeatureFlag): void {
  const desired = !flag.enabled;
  this.adminService.updateFeatureFlag(flag.key, desired).subscribe({
    next: (updated) => { flag.enabled = updated.enabled; this.toast.success(`${flag.name} ${desired ? 'enabled' : 'disabled'}.`); },
    error: (err) => { this.toast.error(extractErrorMessage(err, 'Flag toggle failed')); },
  });
}
```

**File**: `clients/angular/src/app/pages/platform/settings/settings.component.html`

- Delete the Email Templates tab section (lines 100-118).
- Wire `(click)` on the three Save buttons.
- Wire the theme swatch `(click)` to set `selectedThemeId` (currently sets `selectedTheme` — the name — but the DB stores `themeTemplateId`; rename local field for clarity).
- Wire `(fileSelected)` on the new `<app-logo-uploader>` (replaces the current dumb drop-zone div).
- Wire `(change)` on the flag toggle to call `toggleFlag(flag)`.

#### 3. LogoUploaderComponent

**File**: `clients/angular/src/app/shared/components/logo-uploader/logo-uploader.component.ts`

New standalone component. Drag-and-drop area + click-to-pick file input. Validates mime (`image/svg+xml`, `image/png`, `image/jpeg`) and size (2MB). Emits `(fileSelected)`. Shows a spinner while `[uploading]` is true. Shows `[currentUrl]` as a preview thumbnail if provided.

```typescript
@Component({
  selector: 'app-logo-uploader',
  standalone: true,
  imports: [CommonModule, IconComponent],
  template: `
    <div class="upload-area" [class.dragging]="dragging" [class.uploading]="uploading"
         (dragover)="$event.preventDefault(); dragging = true"
         (dragleave)="dragging = false"
         (drop)="onDrop($event)"
         (click)="fileInput.click()">
      @if (currentUrl) { <img class="preview" [src]="currentUrl" alt="Current logo" /> }
      <app-icon name="plus" [size]="24"></app-icon>
      <span>{{ uploading ? 'Uploading...' : 'Click to upload or drag and drop' }}</span>
      <span class="upload-hint">SVG, PNG, or JPG (max. 2MB)</span>
      <input #fileInput type="file" hidden accept="image/svg+xml,image/png,image/jpeg" (change)="onPick($event)" />
    </div>
  `,
  styleUrls: ['./logo-uploader.component.scss'],
})
export class LogoUploaderComponent {
  @Input() currentUrl: string | null = null;
  @Input() uploading = false;
  @Output() fileSelected = new EventEmitter<File>();
  dragging = false;
  // onPick / onDrop with validation
}
```

#### 4. PlatformThemeService

**File**: `clients/angular/src/app/core/services/platform-theme.service.ts`

Fetches from the public endpoint on app bootstrap (via `APP_INITIALIZER` provider). Applies platform CSS vars to `document.body` as a base layer. Toggles `document.documentElement.dataset.theme` between `'light'` and `'dark'`. Exposes an observable for consumers.

```typescript
@Injectable({ providedIn: 'root' })
export class PlatformThemeService {
  private settings$ = new BehaviorSubject<PublicBranding | null>(null);
  constructor(private http: HttpClient, private branding: BrandingService) {}

  bootstrap(): Observable<void> {
    return this.http.get<PublicBranding>('/api/v1/public/platform/branding').pipe(
      tap(s => this.applyFromSettings(s)),
      map(() => void 0),
      catchError(() => of(void 0)),
    );
  }

  applyFromSettings(s: PublicBranding | PlatformSettings): void {
    const template = this.branding.templateById(s.themeTemplateId);
    this.branding.apply(document.body, { templateId: s.themeTemplateId, logoUrl: s.logoUrl, ...template });
    document.documentElement.dataset['theme'] = s.darkMode ? 'dark' : 'light';
    this.settings$.next(s);
  }

  get settings() { return this.settings$.asObservable(); }
}
```

**File**: `clients/angular/src/app/app.config.ts`

Add `APP_INITIALIZER`:

```typescript
{
  provide: APP_INITIALIZER,
  multi: true,
  deps: [PlatformThemeService],
  useFactory: (svc: PlatformThemeService) => () => firstValueFrom(svc.bootstrap()),
},
```

#### 5. BrandingService layering

**File**: `clients/angular/src/app/core/services/branding.service.ts`

Add `templateById(id: string): TenantBranding | null` helper (line 23-173 already defines the 7 templates by key; expose a lookup). No functional change to `apply`, just a new read helper.

Also add an `applyToRoot(template: TenantBranding)` convenience that targets `document.body` — currently callers pass in an element themselves. The layering discipline: tenant overlay happens on `.tenant-shell` element (inside layout), platform base sits on `document.body`. When a tenant is not resolved (login, super-admin routes), no `.tenant-shell` exists so only the platform base shows through.

**File**: `clients/angular/src/app/layout/layout.component.ts`

Add: when the active route is under `/platform/*`, call `BrandingService.reset(tenantShellElement)` on navigation so no tenant vars leak over the platform theme. Under other routes, apply tenant branding as today.

#### 6. Dark-mode CSS system

**File**: `clients/angular/src/styles.scss` (or wherever root CSS custom props live — grep to confirm)

Add a `[data-theme="dark"]` selector that overrides the ~15 root CSS custom properties with dark equivalents. Sample:

```scss
[data-theme="dark"] {
  --color-surface:      #1f2937;
  --color-bg:           #111827;
  --color-text:         #f3f4f6;
  --color-text-muted:   #9ca3af;
  --color-text-secondary: #d1d5db;
  --color-border:       #374151;
  --color-border-light: #1f2937;
  --success-bg:         #064e3b;
  --success-fg:         #d1fae5;
  --warning-bg:         #78350f;
  --warning-fg:         #fef3c7;
  --danger-bg:          #7f1d1d;
  --danger-fg:          #fee2e2;
  --color-primary-light: rgba(59, 130, 246, 0.12);
}
```

Note in the Appearance tab: a persistent inline banner (`.dark-mode-notice`) that reads _"Dark mode covers global UI chrome. Individual pages may still show light-mode colors; report visual bugs and they'll be fixed per-page."_ Sets expectations honestly.

#### 7. FeatureFlagService

**File**: `clients/angular/src/app/core/services/feature-flag.service.ts`

Fetches all flags on bootstrap (public? no — flags may be sensitive; auth-required). Exposes `isEnabled(key: PlatformFlagKey): boolean` and `flags$: Observable<Record<PlatformFlagKey, boolean>>`. Consumers use it directly or via a `<ng-container *appFeatureGate="'AI_ADJUDICATION'">` structural directive that renders children only when enabled.

Bootstrap via a second `APP_INITIALIZER` scoped to authenticated context (only fetches once a JWT is available).

No consumer wired in this phase — just the plumbing so Phase 3 or later features can reach for it.

#### 8. Drop Email Templates tab

Handled inline in the settings component changes above. No further work.

### Success Criteria

#### Automated Verification
- [x] Angular builds: `cd clients/angular && npx ng build` — compiles clean. The build still reports 7 pre-existing SCSS budget errors (claim-detail, tenant dashboard, data-table, ...); none are files this plan touches.
- [x] Angular unit tests pass: `make test-angular` — 19 new specs across `logo-uploader.component.spec.ts` (5), `platform-theme.service.spec.ts` (4), `settings.component.spec.ts` (10), all green. Baseline on this branch is 3 stable pre-existing failures (`insurance-lines providerModeForLine`, `KpiTileComponent` x2) plus one order-dependent flake (`ReportSchedulesPageComponent saveCard`, passes in isolation and in some full runs); confirmed against a clean stash.
- [x] Browser verification against the live stack (`make infra` + tenancy + gateway + web, logged in as `superadmin`). No `verify` skill exists on this machine, so this was driven through Claude-in-Chrome:
  - [x] All three tabs render, no console errors on load
  - [x] General Save persists `platformName` + `supportEmail`, toast "Platform identity saved.", confirmed by re-GET
  - [x] Appearance theme swatch re-skins live (`--color-primary` `#0d9488` -> `#e11d48` on picking Rose) and the dark toggle flips `<html data-theme>` and the body background before the save
  - [x] Appearance Save persists `themeTemplateId` + `darkMode`; survives a full reload via the APP_INITIALIZER bootstrap
  - [x] Landing copy Save persists `heroTitle` + `heroSubtitle`
  - [x] Logo upload of a 2.3KB PNG shows "Logo uploaded." and the preview thumbnail renders from the gateway URL
  - [x] Flag toggle flips, toasts "AI-assisted claims adjudication enabled.", persists, and reads back enabled after reload
  - [x] Every mutation lands in `public.audit_events` with the actor's email
- [ ] DEFERRED to Phase 4: the login screen is Keycloak's own page (`onLoad: 'login-required'` redirects before Angular renders), so there is no Angular pre-auth surface to skin. Platform branding on the login screen is entirely Phase 4's realm sync.

#### Manual Verification
- [x] Dark-mode QA sweep with dark mode on: `/platform/dashboard`, `/platform/tenants` and `/tenant/admin/audit` all render legibly (sidebar, cards, data tables, charts, badges). One offender noted for a follow-up ticket: native `<input type="date">` controls keep light browser chrome. The tenant sidebar correctly keeps its own tenant branding over the platform base layer.
- [x] Upload an oversized PNG: rejected client-side with "huge.png is 3.0MB; the limit is 2MB.", no request sent
- [x] Upload a `.txt` file: rejected with "notes.txt is not an SVG, PNG, or JPG.", no request sent

**Implementation Note**: pause after this phase for manual verification before starting Phase 3.

---

## Phase 3: Kafka broadcast + shared FlagRegistry

### Overview
Emit flag toggles to a Kafka topic; add a shared `FlagRegistry` component that Java and Go services embed to read current flag state with in-process caching + hot-reload. No service reads a specific flag in this phase; only the plumbing is added.

### Changes Required

#### 1. Kafka publisher in tenancy-service

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/kafka/FlagEventPublisher.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class FlagEventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public Mono<Void> publish(String key, boolean enabled, String actorEmail) {
        var payload = Map.of("key", key, "enabled", enabled,
            "updatedAt", OffsetDateTime.now().toString(), "actor", actorEmail == null ? "" : actorEmail);
        return Mono.fromRunnable(() -> {
            try {
                kafka.send("platform.feature-flags.v1", key, mapper.writeValueAsString(payload));
            } catch (Exception e) { log.error("Failed to publish flag event", e); }
        });
    }
}
```

Wire into `PlatformFeatureFlagService.update` after `repo.save`.

#### 2. Shared FlagRegistry (Java)

**File**: `services/java/shared/src/main/java/com/medfund/shared/flags/FlagRegistry.java`

```java
@Slf4j
@Component
public class FlagRegistry {
    private final Map<PlatformFlag, Boolean> cache = new ConcurrentHashMap<>();
    private final WebClient tenancyClient;

    public FlagRegistry(@Value("${tenancy.url:http://tenancy-service:8081}") String tenancyUrl,
                        WebClient.Builder builder) {
        this.tenancyClient = builder.baseUrl(tenancyUrl).build();
    }

    @PostConstruct
    void bootstrap() {
        tenancyClient.get().uri("/internal/feature-flags").retrieve()
            .bodyToFlux(FlagRow.class)
            .doOnNext(row -> { try { cache.put(PlatformFlag.valueOf(row.key()), row.enabled()); } catch (Exception ignored) {} })
            .subscribe();
    }

    @KafkaListener(topics = "platform.feature-flags.v1", groupId = "${spring.application.name}-flag-registry")
    void onFlagChange(String payload) { /* parse + cache.put */ }

    public boolean isEnabled(PlatformFlag flag) { return Boolean.TRUE.equals(cache.get(flag)); }

    record FlagRow(String key, boolean enabled) {}
}
```

Tenancy-service exposes an `/internal/feature-flags` endpoint (permitAll for internal network; documented as internal-only) returning `[{key, enabled}, ...]`. Or reuse `/admin/feature-flags` with a service-account JWT — pick the simpler one.

**Reactor-Kafka consumer discipline (from `bug_reactor_kafka_ack_swallow` memory)**: never use `.doOnTerminate` for offset ack; use `.doOnSuccess` with full-cause-chain error logging. Even for a plain `@KafkaListener` here, log with `log.error("...", ex)` so the cause chain is preserved.

Auto-inject `FlagRegistry` into any service that wants to read flags:

```java
@RequiredArgsConstructor
public class SomeAdjudicationHandler {
    private final FlagRegistry flags;
    public void handle(...) { if (flags.isEnabled(PlatformFlag.AI_ADJUDICATION)) { ... } }
}
```

No such handler is added in this phase.

#### 3. Shared FlagRegistry (Go)

**File**: `services/go/shared/flags/registry.go`

```go
type Registry struct {
    mu     sync.RWMutex
    values map[string]bool
    client *http.Client
    url    string
}

func New(tenancyURL string) *Registry { /* ... */ }

func (r *Registry) Bootstrap(ctx context.Context) error { /* GET tenancyURL/internal/feature-flags */ }

func (r *Registry) StartConsumer(ctx context.Context, brokers []string) error {
    /* Kafka reader on platform.feature-flags.v1, updates r.values on message */
}

func (r *Registry) IsEnabled(key string) bool {
    r.mu.RLock(); defer r.mu.RUnlock()
    return r.values[key]
}
```

Callsite:
```go
flags := flags.New(cfg.TenancyURL)
if err := flags.Bootstrap(ctx); err != nil { log.Warn(...) }
go flags.StartConsumer(ctx, cfg.KafkaBrokers)
```

No service invokes `IsEnabled(...)` in this phase; only plumbing.

#### 4. Wire the registry into every Java service

Add `FlagRegistry` as a component-scanned bean in `services/java/shared/` so all services pick it up. Confirm each service's `@ComponentScan` includes `com.medfund.shared.flags`. If not, add.

Verify each service starts without exception (registry logs "bootstrapped 5 flags").

#### 5. Wire the registry into every Go service

Add `New(...) + Bootstrap + StartConsumer` calls in the `main.go` of each Go service (gateway, notification-service, audit-service, file-service, payment-gateway). Documented pattern; adds ~10 lines per service.

### Success Criteria

#### Automated Verification
- [x] Java compiles across all services: `./gradlew compileJava compileTestJava` — BUILD SUCCESSFUL
- [x] Java tests for the touched modules: `:claims-service:test` + `:contributions-service:test` — 451 tests, 0 failures, including `AiServiceClientFlagTest` (proves the new `default invalidate` did not break the interface's stub implementors). `:shared:test` has 1 pre-existing failure (`CrossServiceCallHelperTest.guarded_returnsFallbackAndWarnsWhenCallFails`), reproduced against a clean stash. Full `make test-java` is still red on this branch from the broken shared `db/test-migration` folder documented in Phase 2.
- [x] `FlagBroadcastIT` in tenancy-service: 2 tests pinning the `platform.feature-flags.v1` payload shape in both directions (enabled true and false), green alongside `PlatformSettingsIT`
- [x] Go compiles across all six services (gateway, notification, audit, file, payment, market-data) — all OK
- [x] Go tests pass per module (the `make test-go` target itself is broken on this branch: `pattern ./...: directory prefix . does not contain modules listed in go.work`). Every module green, including 7 new specs in `shared/flags`.

#### Manual Verification
- [x] Toggled `AI_ADJUDICATION` against the live stack: gateway and audit-service both logged `[flags] AI_ADJUDICATION -> enabled=true` and tenancy-service logged `[feature-flags] AI_ADJUDICATION changed (enabled=true) - cache dropped`, all within the same second
- [x] Restated for the additive design (see Deviations): the Go services log `[flags] bootstrapped 5 flags from http://localhost:8081` on startup and reflect the current DB state. The Java registry reads Postgres on demand, so it has no bootstrap step.
- [x] PARTIAL. With Kafka stopped the DB write still lands (`SELECT` confirms `enabled=f` committed) and the flag broadcast is now bounded at 2s. But the request still hangs, because `AuditPublisher` ahead of it has no timeout: an unrelated `PUT /platform/settings` hangs identically with Kafka down. Pre-existing and platform-wide; see Deviations. On Kafka recovery both Go consumers self-healed and picked up the next toggle.

**Implementation Note**: pause after this phase for manual verification before starting Phase 4.

---

## Phase 4: Keycloak realm sync

### Overview
On PUT `/admin/platform-settings`, also patch the `medfund-platform` realm in Keycloak so the login screen reflects the platform name, hero copy, and logo.

### Changes Required

#### 1. Extend KeycloakRealmService

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/KeycloakRealmService.java`

Add a new method:

```java
public Mono<Void> updatePlatformRealmBranding(PlatformSettings settings) {
    return getAdminToken().flatMap(token -> {
        Map<String, Object> realmPatch = new HashMap<>();
        if (settings.getPlatformName() != null) {
            realmPatch.put("displayName", settings.getPlatformName());
        }
        String htmlName = buildDisplayNameHtml(settings);
        if (htmlName != null) {
            realmPatch.put("displayNameHtml", htmlName);
        }
        return webClient.put()
            .uri(keycloakUrl + "/admin/realms/medfund-platform")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(realmPatch)
            .retrieve()
            .toBodilessEntity()
            .then();
    }).onErrorResume(e -> {
        log.warn("Keycloak realm branding sync failed; UI change persists, login screen unaffected", e);
        return Mono.empty();
    });
}

private String buildDisplayNameHtml(PlatformSettings s) {
    var sb = new StringBuilder();
    if (s.getLogoUrl() != null) sb.append("<img src=\"").append(s.getLogoUrl()).append("\" alt=\"logo\" />");
    if (s.getHeroTitle() != null) sb.append("<h1>").append(escapeHtml(s.getHeroTitle())).append("</h1>");
    if (s.getHeroSubtitle() != null) sb.append("<p>").append(escapeHtml(s.getHeroSubtitle())).append("</p>");
    return sb.length() == 0 ? null : sb.toString();
}
```

Realm name `medfund-platform` is confirmed in `docker-compose.yml:175`, `services/java/tenancy-service/src/main/resources/application.yml:47`, `clients/angular/src/environments/environment.ts:6`.

`escapeHtml` uses `org.apache.commons.text.StringEscapeUtils.escapeHtml4` (already a transitive dep; if not, add commons-text).

#### 2. Hook into PlatformSettingsService.update

**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/PlatformSettingsService.java`

After `repo.save`, invoke:

```java
.flatMap(saved -> emitAudit(before, saved, actorId, actorEmail)
    .then(keycloakRealmService.updatePlatformRealmBranding(saved))
    .thenReturn(saved));
```

Sync failure logs a warning but does not fail the request (user still sees success; audit still emits; DB still persists). This matches the graceful-degradation pattern in `KeycloakRealmService.createRealm` (line 45-51).

#### 3. Documented MVP limitations

Add to the plan output and a follow-up ticket:

- `displayNameHtml` is the only Keycloak surface being modified. It renders above the login form on the default Keycloak theme.
- Custom login themes (Freemarker templates, message bundles, structured hero regions) are **out of scope**. A follow-up spec covers them.
- Logo appears via `<img>` tag inside `displayNameHtml`; it does **not** replace the Keycloak-native logo path (which requires custom theme).
- Dark mode does not affect the login screen (Keycloak has its own theming system, decoupled from ours).

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :tenancy-service:compileJava :tenancy-service:compileTestJava` - PASS. (`:tenancy-service:build` is not runnable as a gate on this branch: `check` pulls the full test task, which is red from the pre-existing shared `db/test-migration` breakage documented in Phase 2/3.)
- [x] Integration test `KeycloakRealmSyncIT` - 3 tests green (realm PUT path/header/payload, hero HTML-escaping, Keycloak-500 does not fail the save). Stubbed with MockWebServer rather than a Keycloak container, see Deviations. Run together with `PlatformSettingsIT` + `FlagBroadcastIT`: 9/9 PASS.
- [x] No regressions in `:tenancy-service:test`: failing-test list diffed against a stashed baseline. Baseline 71 failures / 230 tests, after 78 / 237. Every added failure is one of the 9 platform-IT tests, all of which fail in the full suite for the pre-existing reason (`platform_feature_flags` missing because the shared container's Flyway history already holds a V001 from `db/test-migration`, so the isolated folder's V001 is skipped). Nothing that passed before fails now.
- [x] Live-stack verification, driven through Claude-in-Chrome (no `verify` skill on this machine, as in Phase 2). `PUT /api/v1/platform/settings` with `platformName` + hero copy, then `GET /admin/realms/medfund-platform`: `displayName` = "MedFund Zimbabwe" and `displayNameHtml` = the logo `<img>` + `<h1>` + `<p>`. Login screen at the authorize endpoint renders the uploaded logo, "WELCOME TO MEDFUND" and "COVER THAT TRAVELS WITH YOU" above the sign-in card; tab title reads "Sign in to MedFund Zim".
- [x] The partial realm PUT leaves the rest of the realm alone: `loginTheme`, `registrationAllowed`, `resetPasswordAllowed`, `bruteForceProtected`, `failureFactor`, `maxDeltaTimeSeconds`, `accessTokenLifespan` all unchanged afterwards, and the password grant still returns 200.
- [x] The absolute logo URL baked into `displayNameHtml` resolves: `curl http://localhost:3000/api/v1/public/platform/logo?v=...` returns 200 `image/png`.
- [x] `heroTitle` = `<script>alert(1)</script>`: the realm stores `&lt;script&gt;alert(1)&lt;/script&gt;` and the rendered login page contains no raw `<script>`. See the Deviations note - Keycloak's own sanitizer then blanks the `<h1>` rather than showing the literal.

#### Manual Verification
- [ ] Toggle a Keycloak-relevant field (`platformName`) from the `/platform/settings` UI (not curl), log out, verify the login screen displays the new name
- [ ] Break the Keycloak admin credentials (`keycloak.admin.password=wrong`), attempt a save: the DB update still succeeds, the toast still says success, the log records the sync failure. Restore the correct password. (The behaviour is pinned by `KeycloakRealmSyncIT.keycloakFailureDoesNotFailTheSave`; this step confirms the toast and the log line on the real stack.)

---

## Testing Strategy

### Unit Tests
- `PlatformSettingsService`: `get()` returns singleton row; `update(...)` mutates only non-null fields, bumps version, emits audit exactly once
- `PlatformFeatureFlagService`: unknown flag key returns error; toggle emits audit
- `PlatformThemeService`: `bootstrap()` fetch + apply; falls back to defaults on 404 without throwing
- `LogoUploaderComponent`: rejects oversize file, rejects wrong mime, emits `fileSelected` on valid pick
- `FlagRegistry` (Java + Go): `isEnabled` returns false for unknown flag; consumer updates cache on Kafka message

### Integration Tests (Testcontainers slices)
- `PlatformSettingsControllerIT`: GET on fresh DB returns seeded row; PUT persists + emits audit event; unauthorized (non-super-admin JWT) returns 403
- `PlatformFeatureFlagControllerIT`: seeder inserts 5 flags on startup; toggle persists + emits audit
- `PublicBrandingControllerIT`: no-auth GET returns 200
- `FlagBroadcastIT`: publisher emits well-formed JSON on toggle; a test consumer receives it
- `KeycloakRealmSyncIT` (Phase 4): PUT to `/admin/platform-settings` triggers a Keycloak realm patch (verified via Keycloak admin API from the test)

All ITs use existing tenancy-service Testcontainers harness (Testcontainers 1.21.4 BOM override, flyway-database-postgresql, ReactiveJwtDecoder stub already in place — see `infra_testcontainers_pitfalls`).

### E2E Tests (Playwright, clients/angular/e2e/)
- `platform-settings.spec.ts`: super_admin logs in, navigates to `/platform/settings`, saves each tab, reloads, values persist
- `flag-toggle.spec.ts`: super_admin toggles a flag, page shows updated state

### Manual Testing Steps
1. Fresh DB: run all four services; `/platform/settings` renders with seeded values
2. Save each tab in turn; verify DB row updates and audit event fires
3. Upload a 200 KB PNG logo; verify MinIO has the file at `platform/logo-<epoch>.png` and DB stores the URL
4. Toggle a flag; watch every service's log for the broadcast reception
5. Log out and back in; verify Keycloak login screen reflects saved platform name + hero
6. Toggle dark mode; verify chrome inverts across `/platform/*` pages (individual page issues to be documented as follow-ups)

## Performance Considerations

- `platform_settings` is a single-row table; N+1 is impossible. `GET /admin/platform-settings` should be cached in-process for 30 s to avoid a DB hit on every super-admin page load (optional; not required for MVP).
- Flag registry cache is process-local; no N+1 risk. Kafka consumer memory footprint is negligible (5 flags).
- Logo upload writes to MinIO synchronously in-request. A 2 MB file at gigabit is <20 ms — acceptable.
- Angular bundle-size impact: new `LogoUploaderComponent` + `PlatformThemeService` + `FeatureFlagService` add ~4 KB gzipped.

## Migration Notes

- V183 + V184 are additive; safe to run against an existing tenancy-service database. No backfill needed.
- V183's `INSERT` uses `ON CONFLICT DO NOTHING` so re-running is idempotent.
- V184 has no seed data in SQL; the Java `PlatformFlagSeeder` populates the catalogue on startup (idempotent via `findById` + `switchIfEmpty`).
- No tenant-schema migrations in this plan (all changes are in `public.`).
- Do not delete existing `public.tenant_email_templates` — it's untouched by this plan and still serves per-tenant overrides.

## Rollout & Rollback

**Deploy order:**
1. Tenancy-service (Phase 1 + 3 publisher + Phase 4 Keycloak client) first — publishes the topic before any consumer subscribes.
2. Gateway (Phase 1 route additions) second — makes the endpoints reachable.
3. Angular (Phase 2) third — consumes the new endpoints.
4. Java + Go services with the new `FlagRegistry` (Phase 3 consumers) any time after the tenancy-service publisher is live.

**Rollback:**
- Angular: revert the deploy. Backend endpoints remain but nothing calls them; no data loss.
- Gateway: revert route additions. Endpoints become unreachable; DB rows remain.
- Tenancy-service: revert the deploy. **Do not** revert V183 / V184 migrations (Flyway forbids and it would drop data). If the tables need to go, write a V185 that drops them; but this is an emergency-only path.
- Kafka topic: leave `platform.feature-flags.v1` in place even on rollback — it's cheap and additive.
- Keycloak realm state: rolling back tenancy-service does not un-patch the realm. Manually restore `medfund-platform` `displayName`/`displayNameHtml` via the Keycloak admin UI if needed.

## References

- Component: `clients/angular/src/app/pages/platform/settings/settings.component.ts`
- Existing Keycloak client: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/KeycloakRealmService.java`
- Existing branding service: `clients/angular/src/app/core/services/branding.service.ts`
- Existing audit helpers: `services/java/shared/src/main/java/com/medfund/shared/audit/AuditEvent.java`, `AuditActor.java`, `AuditPublisher.java`
- Existing role guard: `clients/angular/src/app/auth/auth.guard.ts:21-41`
- Gateway platform handler: `services/go/gateway/internal/platform/handler.go:31-43`
- Latest tenancy-service public migration: `services/java/tenancy-service/src/main/resources/db/migration/public/V182__grant_report_config_tables_to_tenant_roles.sql`
- Keycloak platform realm name: `medfund-platform` (`docker-compose.yml:175`, `clients/angular/src/environments/environment.ts:6`)
- Architecture doc: `.claude/multi-tenancy.md` (per-tenant vs platform-scoped table conventions)
- Architecture doc: `.claude/coding-standards.md` (audit + Lombok conventions)
