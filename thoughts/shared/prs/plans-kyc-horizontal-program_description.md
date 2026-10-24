[Plan: KYC horizontal — Plan A (foundation)](https://github.com/MecksonMfema/medfund/blob/plans/kyc-horizontal-program/thoughts/shared/plans/2026-10-23-kyc-plan-a-horizontal-foundation.md)
[Overview: KYC horizontal program (A→B→C)](https://github.com/MecksonMfema/medfund/blob/plans/kyc-horizontal-program/thoughts/shared/plans/2026-10-23-kyc-horizontal-program-overview.md)

## Summary
Stands up KYC as InsureFlow's first sellable, line-agnostic **horizontal**, gated by a new per-tenant
**module-entitlement** seam. This PR lands the program's planning docs plus **Plan A** end to end: the
reusable entitlement seam (Phase 1), a kyc-service skeleton on :8087 with a party-neutral schema behind
the gate (Phase 2), and the rules-engine `VERIFICATION` category the pilot's policy will be authored in
(Phase 3).

The entitlement seam is deliberately modelled on the existing feature-flags/permissions stacks rather
than invented fresh — a Caffeine-cached registry with Kafka invalidation, a super-admin toggle surface,
and **two enforcement points**: the Go gateway gates at the edge and a Java `@RequiresEntitlement` AOP
aspect is the in-service backstop (defence in depth, so a forgotten gateway prefix-map entry can't leak a
paid module). It fails **closed** — an unknown or absent `(tenant, module)` reads as *not entitled* (403),
never open.

Look hardest at **tenant isolation and the fail-closed paths**: the registry's default-FALSE + error
path, the gateway edge gate vs. the aspect backstop agreeing, and the V187 tenant-role grant (the aspect
reads `public.tenant_module_entitlements` under `SET ROLE <schema>_role`, so the table must be granted or
every gated call 403s). The kyc-service schema (V278) is **party-neutral** — one row per `(subject_type,
subject_id)`, `insurance_line` contextual-only, no `tenant_id` — which is the shape Plan B/C build on.

## Changes

**shared (Java)**
- `ModuleKey` enum (KYC, KYC_BIOMETRICS as a separate SKU, placeholders), `TenantEntitlementRegistry`
  (Caffeine cache, 30s TTL, fail-closed default FALSE), R2DBC-backed impl + Kafka invalidation consumer on
  `platform.module-entitlements.v1`, auto-config wiring.
- `@RequiresEntitlement` + `EntitlementAspect` (reactive `deferContextual`, Mono/Flux, fails closed on
  missing tenant). `SecurityEventPublisher.publishEntitlementChanged` (Rule 9).

**tenancy-service (Java)**
- `V187` public `tenant_module_entitlements` table + re-provisioned `provision_tenant_role` grant + backfill.
- Entity/repo/DTOs, super-admin `PlatformModuleEntitlementController` + `InternalModuleEntitlementController`,
  and a service that upserts then audits (Rule 8), broadcasts (Rule 6) and emits an `ENTITLEMENT_CHANGED`
  security event (Rule 9) on every real toggle.

**kyc-service (Java, new — :8087)**
- New Gradle module (deps `:shared` + `:rules-engine`), app + Kafka/R2DBC/Security config mirroring siblings,
  `application.yml` (Flyway disabled — tenancy owns tenant DDL), Dockerfile.
- `V278` tenant-family `kyc_verifications` + `kyc_documents` (party-neutral; `UNIQUE(subject_type,
  subject_id)`; no `tenant_id`).
- Entities + repos + `VerificationResponse`, `VerificationController` (`@RequiresEntitlement(KYC)`, full Swagger).

**rules-engine (Java)**
- `VerificationFact`, `VERIFICATION` rule category (agenda-gated), `REQUIRE_MANUAL_REVIEW` /
  `GATE_ACTIVATION` action verbs + emitters, two seed templates.

**gateway (Go)**
- `EntitlementGate` middleware (edge gate, fail-closed) + `shared/entitlements` registry mirror (bootstrap
  + Kafka consumer); `config.go`/`routes.go` proxy `/api/v1/verifications` → kyc-service behind the gate.

**ops & tooling**
- `docker-compose.yml`: `kyc-service` block + gateway `KYC_SERVICE_URL`/`depends_on`.
- `.claude/commands/create-pr.md` + `review-pr.md` (PR skills adapted to the make/thoughts/gh workflow).
- Program planning docs (overview + Plan A/B/C) and KYC research under `thoughts/shared/`.

## How to Verify
### Automated
- [x] `./gradlew :kyc-service:build` — **green** (4 `KycSkeletonIT` tests incl. 200-entitled / 403-un-entitled,
      + the 70% line-coverage gate).
- [x] `TenantModuleEntitlementIT`, `PublicMigrationFlywayIT`, `TenantMigrationFlywayIT` (V278 applies cleanly
      in the full tenant chain), `EntitlementAspectTest`, rules-engine `VerificationTemplatesTest` — all green.
- [x] Go gateway `go build ./...` + `go vet ./...` + `config`/`entitlement` middleware tests — green.
      (4 pre-existing `routes_test.go` timeouts are unrelated — they dial unresolvable hostnames; identical
      with this PR's route change stashed.)
- [ ] GitHub Actions on this PR: `.github/workflows/java.yml` (unit + integration) and `go.yml` — the
      authoritative full-suite run.

### Manual
- [ ] `make infra` up, then `make kyc` (:8087) + `make gateway` (:3000). With a tenant that has **no** KYC
      entitlement: `GET http://localhost:3000/api/v1/verifications` (tenant JWT) → **403**.
- [ ] Toggle on as super-admin: `PUT /api/v1/platform/module-entitlements/{tenantId}/KYC` `{"enabled":true}`;
      repeat the `GET` → **200** + empty list.
- [ ] Swagger: `http://localhost:8087/swagger-ui` renders `GET /api/v1/verifications`.

## Notes
- **New Flyway migrations:** public `V187` (adds a table **and** re-provisions `provision_tenant_role` — the
  grant is required, not optional) and tenant-family `V278` (tenancy-owned; no `tenant_id` — isolation is the
  search_path).
- **New Kafka topic:** `platform.module-entitlements.v1`. **Cross-language consumers:** the Go
  `shared/entitlements` registry and the Java `EntitlementInvalidationConsumer` both key off this payload's
  `tenantId`/`moduleKey`/`enabled`/`actor` field names — a rename there is a silent break, neither consumer
  is in tenancy-service's diff.
- **New gateway route** `/api/v1/verifications` → kyc-service, gated by the `/api/v1/verifications → KYC`
  prefix map; the in-service aspect is the backstop.
- **Fail-closed by design (G3):** gateway bootstrap failure logs and continues with every module reading as
  not-entitled, rather than handing a paid module away during a cold start.
- **Deferred to Plan B/C (not this PR):** the document upload/OCR/screening pipeline, MEMBER/PROVIDER subject
  resolvers, the create/verify/reject flow, and the Angular/permissions authoring surface for VERIFICATION
  rules. `KYC_BIOMETRICS` is a separate SKU wired as a placeholder only.
- **Framing:** schema, DTOs and copy are line-neutral ("subject", "party") — KYC is a horizontal, not a
  health-vertical feature.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
