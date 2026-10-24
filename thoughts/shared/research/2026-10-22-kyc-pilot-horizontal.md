---
date: 2026-10-22T13:48:33+00:00
researcher: methuseli
git_commit: e5051ef3a1cccc79fcebb62668ea589f2dcb98c0
branch: main
repository: medfund
topic: "Spec KYC as the pilot horizontal — line-agnostic, standalone, sellable module"
tags: [research, codebase, kyc, entitlement, packaging, rules-engine, user-service, tenancy-service, gateway, ai-service, multi-tenancy, horizontal-module]
status: complete
last_updated: 2026-10-22
last_updated_by: methuseli
---

# Research: Spec KYC as the pilot horizontal

**Date**: 2026-10-22T13:48:33+00:00 · **Researcher**: methuseli · **Commit**: `e5051ef3` · **Branch**: main

## Research Question

Spec KYC as the **pilot horizontal** — a line-agnostic capability that verifies any party across any
insurance line, deployable and sellable as a standalone module (whichever product line signs up first),
with its own per-tenant entitlement. This reframes the already-written member-KYC work, which treats
KYC as a *member layer* rather than a standalone horizontal product.

## Summary

KYC is a strong choice for the first horizontal, and three of the four things it needs already exist or
are cheap to add. One thing — the thing that makes it *sellable standalone* — does **not** exist and must
be built.

1. **Party-neutrality is feasible and idiomatic.** The platform has no shared `party` table, but every
   verifiable party (member, dependant, provider, policyholder) shares one handle — a `UUID id` — and the
   `(subjectType, subjectId)` discriminator pattern KYC needs is **already used** in production code
   (`Group.liaison_kind`, `claims.payee_type`, `provider_tenants`). Providers already have an independent
   onboard→verify→active lifecycle. So KYC can key off `(subject_type, subject_id, insurance_line?)`
   rather than `member_id`.
2. **Per-line policy can live in the rules engine** (Critical Rule #5) with a small, well-bounded
   engine-code seam: a new `VerificationFact` + a `VERIFICATION` `RuleCategory`. After that, all per-line /
   per-jurisdiction verification policy is pure rule JSON content — no code per line.
3. **The horizontal plumbing exists**: an auditable AI-prediction store + human-review queue (Rule #3), an
   OCR engine (needs an ID-document prompt variant), MinIO byte storage, MFA step-up, and two immutable
   audit pipelines. (Mapped in detail by the prior research doc — see Historical Context; not re-derived
   here.)
4. **The sellable-standalone seam is MISSING.** There is **no per-tenant module-entitlement concept**
   anywhere. Gating today is: RBAC permissions (genuinely per-tenant, enforced), a cosmetic sidebar toggle
   (default-on, no API enforcement), global-only platform flags ("no per-tenant override by design"), and
   an inert `Plan.features` record that no service reads. The gateway does **zero** entitlement gating. To
   sell "tenant X has bought KYC" you must build that seam — it is the real work of making KYC a *product*
   rather than a *feature*.

**The headline divergence from the existing plan:** the approved `2026-10-21` plan builds KYC as a
member sub-resource in user-service (`member_documents`/`member_verification` keyed to `member_id`), with
static per-tenant config and no entitlement. A *horizontal* KYC instead needs a party-neutral subject, an
entitlement gate, and (ideally) its own service boundary. These are reconcilable but must be decided
before building — see **What a plan must decide**.

## Findings

### 1. The existing KYC work — and exactly how this diverges

Two untracked docs already cover member-KYC in depth and are the base this builds on (not re-researched):

- `thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md` — exhaustive map of what a
  member is, the absent KYC primitives, and the reusable foundations (OCR, prediction store, review
  queue, MinIO, MFA, audit).
- `thoughts/shared/plans/2026-10-21-member-layers-and-kyc-integration.md` — a 14-phase implementation
  plan, KYC front-loaded (Phases 1–7): `member_documents` (V278) + `member_verification` (V279), an
  activation gate via `pending_verification`, an ID-OCR variant, an in-house LLM sanctions/PEP classifier,
  staff review UI, Flutter self-upload.

That plan is **member-coupled by design**. It never discusses line-agnostic packaging, entitlement keys,
or selling KYC to a tenant that has no member/billing module. The reframe to a *horizontal* changes three
load-bearing choices (subject model, service boundary, entitlement) — detailed below.

### 2. Party-neutral subject — feasible, with the pattern already in the codebase

- `InsuranceLine` enum carries a `personCentric` boolean: `services/java/shared/.../insurance/InsuranceLine.java:24-32`;
  `isPersonCentric()` at `:44-46`. Person-centric: HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY.
  Asset-centric: VEHICLE, PROPERTY (MOTOR is a UI alias → VEHICLE, `:62`).
- **No shared `party`/`person` table exists.** Each entity is standalone with a `UUID id` PK. Identity
  fields differ: Member/Dependant have `national_id` (`Member.java:16-17,33-34`, `Dependant.java:16-17`);
  Provider/Group repurpose `registration_number` (`Provider.java:21-22` with a javadoc noting its meaning
  varies by line, `:29-34`); StaffUser has neither (`StaffUser.java:26-27`). **The only common identity
  handle across all party types is the `UUID id`** — a national-id-keyed subject would not generalize.
- **The discriminator pattern KYC needs is already idiomatic:**
  - `Group.liaison_kind ∈ {MEMBER, STAFF}` + `liaison_user_id` UUID, CHECK-enforced pair
    (`Group.java:36-46`) — this *is* `(subjectType, subjectId)`.
  - `claims.payee_type ∈ {PROVIDER, MEMBER}` with a CHECK guard
    (`tenancy-service/.../tenant/V066__claims_payee_type.sql:20-36`).
- **Provider verification is the existing precedent for a non-member subject.** Providers default to
  `status = 'pending_verification'` (`tenancy-service/.../public/V105__providers.sql:21`);
  `ProviderService.onboard()` sets it (`ProviderService.java:136`), `verifyAhfoz()` transitions to
  `active` + writes audit (`:168-182`). Providers are platform-shared with per-tenant membership via
  `public.provider_tenants` and line-tagged via `public.provider_insurance_lines`
  (`V185__platform_provider_membership.sql:12-31,42-49`) — the exact "platform subject + per-tenant/
  per-line membership" model a horizontal KYC can mirror.
- **Coupling that blocks naive reuse:** scaffolded policies hard-FK their subject to `members(id)`
  (`tenancy-service/.../tenant/V032__asset_and_policy_tables.sql`: life `:118`, funeral `:154`, travel,
  disability NOT NULL; vehicle `:43` / property `:80` nullable `owner_member_id`). Members/Dependants live
  in tenant schemas; Providers/StaffUsers live in `public`. So a KYC subject cannot assume one schema or
  one FK target — it must store an opaque `(subject_type, subject_id[, insurance_line])` triple and resolve
  per type.

**Verdict:** KYC can cleanly use `(subject_type ∈ {MEMBER, DEPENDANT, PROVIDER, POLICYHOLDER, …},
subject_id, insurance_line?)`. The platform is member-*referencing* but not so member-*centric* that KYC
must bind to `member_id`.

### 3. Entitlement / packaging machinery — the missing seam

There is **no per-tenant module-entitlement concept**. Four gating axes exist; none is "tenant X bought
KYC":

| Axis | Where | Per-tenant? | Enforced? |
|---|---|---|---|
| **Platform feature flags** | `shared/.../flags/PlatformFlag.java:13-23` (5 flags); `public.platform_feature_flags` (`V184`, **no `tenant_id`**); `FlagRegistryR2dbc.java:83-99`; Kafka `platform.feature-flags.v1` (`FlagInvalidationConsumer.java:46`, payload has no tenantId) | **No — global by design** (`PlatformFlag.java:11`: "No per-tenant override capability by design") | n/a |
| **Sidebar section visibility** | `shared/.../sidebar/SidebarSectionKey.java:28-85` (~41 keys), `SidebarGroup.java:21-29` (OVERVIEW/BILLING/POLICIES/MEMBERS/CLAIMS/FINANCE/REPORTING); `TenantSidebarSectionConfig` (`V179`); Angular `operational-nav.ts:50` | Yes | **Cosmetic only** — `isEnabled()` returns true when no row exists (`TenantSidebarSectionConfigService.java:71-75`); hides nav, does not block routes/APIs |
| **Insurance-line UI gate** | Angular `insurance-lines.ts:80-89` reads `Tenant.settings.insuranceLines`; nav gates on `vehiclesAvailable`/`lifeAvailable` (`operational-nav.ts:51-61`) | Yes | Presentation-layer only |
| **RBAC permissions** | `shared/.../security/Permissions.java`, `PermissionCatalogue.java:24-183`; `@RequiresPermission` + `PermissionAspect.java:36-72`; resolved per-service from tenant schema (`PermissionResolverFilter.java:42-50`, `DefaultPermissionResolver`); `GET /api/v1/me/permissions` (`RoleController.java:163-194`); Angular `permission.service.ts`, `permissionGuard` | **Yes** | **Yes — the real access-control seam** |

- **`Plan` is inert for access control.** `Plan.java:13-45` has `features (String, defaulted "{}")`,
  `maxMembers`, etc. Grep confirms **no service outside tenancy-service reads `Plan` or enforces limits**;
  `Tenant.planId` is stored/echoed only (`TenantService.java:142,192,329,357`). It is the obvious home for
  entitlements but is currently a billing record.
- **The gateway does no entitlement/flag gating.** `services/go/gateway/internal/routes/routes.go:13-373`
  is static prefix→backend proxying; JWT/tenant resolution in `middleware/jwt.go` + `middleware/tenant.go`;
  the only gates are `RequireSuperAdmin()` on `/api/v1/platform/*` and rate-limiting. The flag registry is
  **imported and running** (`gateway/cmd/main.go:16,77-81`) but `main.go:76` states: *"Nothing reads a
  flag in this service yet — this is the plumbing."* The `X-User-Permissions` header mentioned in a
  `routes.go:364` comment is **not actually set** anywhere.

**Verdict:** to sell KYC per-tenant you must add the missing seam — most cleanly a
`tenant_module_entitlement` store + a per-tenant accessor (mirroring `FlagRegistry`) consumed by (a) a new
gateway middleware (reusing the already-wired flag plumbing hook) and/or (b) a `@RequiresEntitlement`
aspect alongside `@RequiresPermission`, optionally activating the inert `Plan.features`/`planId`.

### 4. Rules-engine as the home for per-line / per-tenant KYC policy

- Rules are an **in-process library**, not a service call: consumers declare
  `implementation(project(":rules-engine"))` (`user-service/build.gradle.kts:7`,
  `claims-service/build.gradle.kts:7`) and component-scan the engine subpackages but not its controllers
  (`UserServiceApplication.java:14-26`). Evaluation example:
  `claims-service/.../AdjudicationPipeline.java:900-913` (`ensureLoaded` → `evaluateClaim`). The standalone
  `rules-service` (port 8086) exists only for author-time CRUD/template UI.
- `RuleDefinition` JSON (`rules-engine/.../model/RuleDefinition.java:8-20`) has an **opaque String
  `category`** — the compiler never switches on a closed enum. Rules are stored in the **public** table
  `tenant_rules` keyed by `tenant_id` (`entity/TenantRule.java:24-50`, DDL
  `public/V109__tenant_rules.sql`), compiled to DRL (`compiler/DrlCompiler.java:106-141`), cached per
  tenant with atomic KieContainer swap (`engine/TenantRuleEngine.java:34-101`), and hot-reloaded via
  `medfund.rules.updated` (`service/RuleEventPublisher.java:27`, `consumer/RuleChangeConsumer.java:78`).
- **Facts** are POJOs in `rules-engine/.../fact/`. The newest, `FraudFlagFact.java:30-51`, is the exact
  template for a KYC fact: it carries `insuranceLine`, subject ids, a `List<String> indicators`, a
  `riskScore`/`riskLevel`, and an `emitCase` result flag a rule mutates. A `VerificationFact`
  (`subjectType, insuranceLine, country, documents[], screeningResult`) would look identical.
- **Adding KYC policy = a small bounded engine-code change, then pure content:**
  - New fact: two edits in `DrlCompiler` — an IMPORTS line (`:36-55`) and a `FACT_MAPPINGS` entry
    (`:68-91`).
  - New write-action (require-document / block / escalate): one `ActionType` value
    (`model/ActionType.java:12`) + one auto-discovered `ActionEmitter` bean (`compiler/ActionEmitter.java`)
    — `DrlCompiler` itself needs no edit; add a `factForAction` mapping only if the action mutates an
    unbound fact.
  - New category: `RuleCategory.java:12-161` has grown to ~27 values; adding `VERIFICATION`/`KYC` is one
    enum value + one `TemplateProvider` bean, non-breaking (category is opaque). It sits beside the
    existing `ELIGIBILITY` (`:28`), `WAITING_PERIOD` (`:29`), `UNDERWRITING` (`:19`) cluster.
  - After that, per-line/per-jurisdiction verification thresholds and required-document sets are pure
    `RuleDefinition` JSON, authored per tenant, hot-reloaded. This is identical to how FraudFlagFact /
    FRAUD_TRIAGE were added.

### 5. Service boundary & schema ownership — a standalone kyc-service cannot own its DDL

- A new Java service is lightweight to stand up: Spring Boot plugin + `:shared` + `:rules-engine` deps
  (`user-service/build.gradle.kts:5-18`), a `@SpringBootApplication(scanBasePackages={…})` scanning your
  package + `com.medfund.shared` + the rules-engine subpackages (`UserServiceApplication.java:14-31`), and
  an `application.yml` with a new port. **R2DBC + tenant isolation come for free** by scanning
  `com.medfund.shared`: `TenantAwareAutoConfiguration.java:35-52` wraps the ConnectionFactory in
  `TenantAwareConnectionFactory` (per-acquire `RESET ROLE → SET search_path → SET ROLE`), driven by
  `TenantWebFilter`/`TenantContext`. Next free port slot is **8087**.
- **CRITICAL: tenancy-service owns ALL tenant-schema DDL.** There are exactly two migration trees, both in
  tenancy-service: `db/migration/public/` and `db/migration/tenant/`. The tenant tree already holds *other
  services'* tables — `tenant/V001__baseline.sql:21` (members), `tenant/V014__claims_schema.sql` (claims),
  `tenant/V016__finance_schema.sql` (finance). user-service and claims-service have **no** `db/migration`
  dir (Flyway is a test-only dep). Migrations are applied to every tenant schema by
  `SchemaProvisioningService.migrateTenantSchema` (`:81-86`, `locations("classpath:db/migration/tenant")`)
  at provision time and on every tenancy-service boot (`TenantMigrationRunner.java:46-66`).
- **Implication:** a `kyc-service` would **not** own its tables. Its DDL (`kyc_verifications`,
  `kyc_documents`, …) is added as `V###__kyc_*.sql` under `tenancy-service/.../db/migration/tenant/` (or
  `db/migration/public/` for a cross-tenant table, like the `tenant_rules` precedent), and kyc-service
  reads/writes via its own R2DBC repositories — a *consumer* of tenancy-owned DDL, exactly like
  claims/finance. Next free tenant migration version is **V278** per the prior research.
- Adding the service to the fleet is two edits: gateway route + URL
  (`gateway/internal/routes/routes.go`, `config.go:36-54` port table — specific paths before wildcards)
  and a `docker-compose.yml` block mirroring user-service (`:188-206`) plus the gateway `depends_on`.

### 6. Horizontal plumbing already present (from prior research — cited, not re-derived)

Per `2026-10-21-member-layers-and-kyc-integration.md`: OCR engine
(`ai-service/app/api/ocr.py:31`, prompt hardcoded to healthcare claims at `ocr_service.py:59` — needs an
ID-document variant); auditable AI-decision store `ai_predictions_store`
(`ai-service/app/models/db_models.py:10`, satisfies Rule #3); human-review queue
(`ai-service/app/api/predictions.py:96`); MinIO byte storage (`file-service/internal/storage/minio.go`,
inbound path is MockStorage stubs, no virus scan); MFA step-up (`shared/.../security/MfaStepUpGuard.java:32`);
two immutable audit pipelines (`audit-service/internal/audit/event.go:5`); and the typed-document-linked-to-
entity pattern `siu_evidence` (`tenant/V272`). **Do not conflate** with adjacent non-KYC features:
`finance-service/.../regulatory/aml/AmlAlertService.java:53` is transaction-monitoring STR;
`FraudTriageTemplates.java:37` "watchlist" matches *provider* IDs; `ai-service/app/api/fraud.py:34` is
*claim* fraud.

## Cross-service flow (a KYC verification, as a horizontal)

1. **Angular (staff) / Flutter (self)** → upload document bytes to **file-service** (needs the real
   inbound multipart → virus-scan → MinIO path built; emits `medfund.documents.uploaded`).
2. **kyc-service** (or user-service module) persists a `kyc_documents` handle keyed by
   `(subject_type, subject_id)` and creates/updates a `kyc_verifications` row.
3. **kyc-service** pulls OCR synchronously (Rule #6 read/compute) from **ai-service** (ID-document
   variant), writing `ai_predictions_store` with `entity_type=<subject_type>`,
   `prediction_type="ocr_id_document"`, model version + confidence (Rule #3).
4. Optional **sanctions/PEP screening** → another `ai_predictions_store` row.
5. **rules-engine** (in-process) evaluates a `VerificationFact` against the tenant's `VERIFICATION`
   rules → decision (required level met? screening clear? gate activation?).
6. kyc-service transitions verification status, emits a business audit event
   (`medfund.audit.events`) and a `medfund.kyc.verification-changed` (or
   `medfund.users.member-verification-changed`) Kafka event; staff review MFA-gated.
7. **Entitlement check** at the gateway (new seam): tenant must be entitled to the `kyc` module or the
   `/api/v1/kyc/*` route is rejected before any of the above runs.

## Architecture doc vs. code (drifts)

- **A. No module-entitlement model.** Docs' only packaging primitives are `plans.features` JSONB
  (`.claude/multi-tenancy.md:297`) and super-admin per-tenant feature flags (`:196`) + sidebar/line
  toggles (`.claude/portals.md:860-867`). "Sellable horizontal module" is not a modeled concept — speccing
  entitlement extends the architecture beyond any `.claude` doc.
- **B. Existing plan treats KYC as a member layer, not a standalone module** — diverges from this
  horizontal framing (see Finding 1). Reconcile before building.
- **C. No rules-engine category for verification/screening.** Rule #5 and the proposal
  (`proposals/AI4I_Track3_Proposal_Draft.md:95`, "PEP-list-only KYC checks … live in the per-tenant Drools
  rules engine") say KYC policy *should* be rules content, but the `2026-10-21` plan explicitly defers
  this to static per-tenant config (`plan:44,162`). Static-now vs rules-later is a documented fork.
- **D. OCR is healthcare-coupled** (`.claude/ai-integration.md:142-144`, system prompt "healthcare
  document data extractor") — a line-neutral KYC needs a separate ID-extraction variant or it inherits a
  medical bias (framing-rule violation).
- **E. Encryption gap (Rule #4).** KMS is not wired repo-wide (bank details cleartext,
  `.claude/payments.md:610`); a KYC PII store inherits the documented gap unless addressed.
- **F. Naming collisions.** "Member Verification" in the provider portal (`.claude/portals.md:227`) is
  *eligibility*; `AmlAlertService` is STR; `FraudTriage` "watchlist" is provider IDs. A horizontal KYC
  must carve a distinct, line-neutral vocabulary.
- **G. `PENDING_VERIFICATION` gate — doc designs it, DB omits it for members.** `.claude/portals.md:405`
  designs it and the `/tenant/members/pending` queue (`:178`), but the member status CHECK (V242) has no
  such value — it exists only for providers (`V105:21`). Status badges already exist
  (`.claude/design-system.md:92,95`).

## What a plan must decide (the forks)

1. **Service boundary: dedicated `kyc-service` vs. user-service module.** Horizontal/sellable framing
   favors a dedicated service (own route prefix `/api/v1/kyc/*`, own entitlement gate, deployable to a
   KYC-only tenant). The existing plan embeds it in user-service. Note either way it does *not* own its
   DDL (Finding 5). Evidence for a service: clean entitlement/route boundary, sell to a tenant with no
   member module. Against: more fleet overhead, and the party-neutral subject still references tenant/
   public tables owned elsewhere.
2. **Subject model: party-neutral `(subject_type, subject_id, insurance_line?)` vs. `member_id`.**
   Horizontal needs the triple (Finding 2); the existing plan uses `member_id`. The triple is strictly
   more general and the pattern is idiomatic — but provider subjects live in `public`, member subjects in
   the tenant schema, so resolution is per-type.
3. **Entitlement seam: what to build.** Minimum = a `tenant_module_entitlement` table + per-tenant
   accessor + a gateway gate (reuse the wired flag plumbing) and/or a `@RequiresEntitlement` aspect.
   Optional = activate the inert `Plan.features`/`planId`. This is the Phase-0 unlock for *every* future
   horizontal, not just KYC.
4. **Per-line policy: rules-engine `VerificationFact` + `VERIFICATION` category (now) vs. static
   per-tenant config (defer).** The engine seam is small and bounded (Finding 4); doing it now makes KYC
   genuinely line-agnostic from day one and sets the pattern for later horizontals. Deferring matches the
   existing plan but re-introduces per-line code risk.
5. **Entitlement vs. permission relationship.** Entitlement = "tenant bought the module"; permission =
   "this user may act within it." Both are needed; decide whether the gateway enforces entitlement and
   services enforce permission (clean split) or one aspect does both.
6. **File-service inbound pipeline is a hard prerequisite** (MockStorage + no virus scan today). In scope
   for the KYC pilot or sequenced first?

## Code References

- `services/java/shared/src/main/java/com/medfund/shared/insurance/InsuranceLine.java:24-46` — line enum + `isPersonCentric()`
- `services/java/user-service/.../entity/Member.java:16-48`, `Provider.java:21-51`, `Group.java:36-46` — party identity fields + the `liaison_kind` discriminator
- `services/java/tenancy-service/.../tenant/V066__claims_payee_type.sql:20-36` — `(payee_type, …)` discriminator precedent
- `services/java/tenancy-service/.../public/V105__providers.sql:21` + `ProviderService.java:136,168-182` — provider onboard→verify lifecycle (non-member subject precedent)
- `services/java/tenancy-service/.../public/V185__platform_provider_membership.sql:12-49` — platform subject + per-tenant/per-line membership model
- `services/java/shared/.../flags/PlatformFlag.java:11-23` + `FlagRegistryR2dbc.java:83-99` + `tenancy-service/.../public/V184__platform_feature_flags.sql` — global flags (no tenant dim)
- `services/java/shared/.../sidebar/SidebarSectionKey.java:28-85`, `SidebarGroup.java:21-29` + `TenantSidebarSectionConfig(Service/Controller).java` + `tenant/V179__tenant_sidebar_section_config.sql` — cosmetic per-tenant sidebar toggle
- `services/java/tenancy-service/.../entity/Plan.java:13-45` — inert billing record (never read for enforcement)
- `services/java/shared/.../security/Permissions.java`, `PermissionCatalogue.java:24-183`, `PermissionAspect.java:36-72`, `PermissionResolverFilter.java:42-50` — the real per-tenant access-control seam
- `services/go/gateway/internal/routes/routes.go:13-373` + `config.go:36-54` + `cmd/main.go:76-81` — static routing, no entitlement gate, flag plumbing present-but-unused
- `clients/angular/src/app/core/services/feature-flag.service.ts`, `shared/directives/feature-gate.directive.ts`, `core/models/insurance-lines.ts:80-89`, `layout/operational-sidebar/operational-nav.ts:50`, `app.routes.ts:101-138` — Angular gating + lazy route pattern
- `services/java/rules-engine/.../model/RuleDefinition.java:8-20`, `entity/TenantRule.java:24-50`, `compiler/DrlCompiler.java:36-91,106-141`, `fact/FraudFlagFact.java:30-51`, `model/RuleCategory.java:12-161`, `service/RuleEventPublisher.java:27` — rules storage/compile/facts/categories/hot-reload
- `services/java/claims-service/.../service/AdjudicationPipeline.java:900-913` — in-process rules evaluation
- `services/java/user-service/build.gradle.kts:5-18`, `UserServiceApplication.java:14-31`, `application.yml:1-42` — new-service skeleton
- `services/java/shared/.../tenant/TenantAwareAutoConfiguration.java:35-52` — free tenant isolation via `:shared`
- `services/java/tenancy-service/.../service/SchemaProvisioningService.java:81-86`, `TenantMigrationRunner.java:46-66` — tenancy-service owns all tenant DDL
- `.claude/multi-tenancy.md:196,297`, `.claude/rules-engine.md:9-29`, `.claude/portals.md:178,405,860-867`, `.claude/ai-integration.md:20-22,142-144,211-236`, `.claude/design-system.md:92-103` — intended architecture

## Architecture Insights (vs. the 9 Critical Rules)

- **Rule #2 (tenant scoping):** member/verification data in the tenant schema (auto-scoped). A
  provider KYC subject references `public.providers` → any per-tenant read must join `public.provider_tenants`.
  A shared sanctions/PEP reference list in `public` would need the same membership discipline.
- **Rule #3 (AI auditability):** OCR + screening each write `ai_predictions_store` (model version,
  anonymised features, confidence, output) — pattern already exists. Extend `anonymize._STRIPPED_KEYS`
  with identity keys (`national_id`, `passport_number`, `date_of_birth`, …) *before* any ID field reaches
  the store.
- **Rule #4 (PII/PHI):** ID documents are PII → encrypted at rest/in transit; retention per tenant
  jurisdiction + per line. Inherits the repo-wide KMS gap (flag it).
- **Rule #5 (rules-engine):** per-line/per-jurisdiction verification policy belongs in rules content
  (`VerificationFact` + `VERIFICATION` category), not engine code.
- **Rule #6 (Kafka):** verification state changes emit a Kafka event; OCR/screening are synchronous
  read/compute (allowed), not side-effect calls.
- **Rule #7 (Swagger):** every new endpoint fully documented; `/swagger-ui`.
- **Rule #8 (audit):** every verification mutation emits `medfund.audit.events` via `AuditPublisher` with
  friendly `entityName` + dual actorId/actorEmail.
- **Rule #9 (security events):** bulk access to KYC PII trips mass-data-access alerting; permission/
  entitlement denials log to `security_events`.
- **Framing rule:** line-neutral vocabulary ("verification", "document", "subject", "party") — never
  "patient". KYC applies across HEALTH/LIFE/FUNERAL/VEHICLE/…

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md` — the member-KYC foundation
  map (OCR, prediction store, review queue, MinIO, MFA, audit; absent primitives). **Primary base.**
- `thoughts/shared/plans/2026-10-21-member-layers-and-kyc-integration.md` — the 14-phase member-coupled
  KYC plan this reframes toward a horizontal.
- `thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md` (+ plan) —
  the nearest existing self-service + verify + `pending_verification` design (for providers); the template
  to mirror for a party-neutral subject.
- `thoughts/shared/research/2026-09-15-tenant-provisioning-rbac-model.md` — member↔Keycloak linkage,
  provider `pending_verification`, provisioning order.
- `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md` — OCR/fraud model, prediction
  store patterns.
- `proposals/AI4I_Track3_Proposal_Draft.md:21,82,95` — Biometric KYC + PEP-in-rules-engine roadmap intent.

## Open Questions

1. Does the KYC pilot ship the entitlement seam (Fork 3) as a reusable Phase-0 for all horizontals, or a
   KYC-specific gate first?
2. Dedicated `kyc-service` (port 8087) or user-service module? (Fork 1)
3. Party-neutral subject now, or member-only first with the triple reserved? (Fork 2)
4. Rules-engine `VerificationFact` now, or static config with a later migration? (Fork 4)
5. Is the file-service inbound pipeline in scope for the pilot or sequenced ahead of it? (Fork 6)
6. Which parties does the first release verify — members only, or members + providers (to prove
   party-neutrality end-to-end)?

## Related Research

- `thoughts/shared/research/2026-10-21-member-layers-and-kyc-integration.md`
- `thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md`
- `thoughts/shared/research/2026-09-15-tenant-provisioning-rbac-model.md`
