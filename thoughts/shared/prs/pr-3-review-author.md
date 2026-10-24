# PR #3 self-review (author, round 1)

## TL;DR

KYC Plan A lands the per-tenant module-entitlement seam, the kyc-service skeleton on :8087, and the
rules-engine `VERIFICATION` category — all three phases, grounded against the grilled Plan A doc. The
sweep found **no Blockers**. There is **one Important**: the entitlement toggle broadcasts the Kafka
invalidation *only if the audit publish succeeds*, and the Go gateway has no TTL/resync — so an
audit-broker hiccup at toggle time can leave an enabled tenant 403'd at the edge until a gateway
restart. One more Important is a Lombok convention slip (manual constructor). The rest are suggestions
and a Plan-B pagination follow-up. Both CI suites are red, but **every failure is inherited from `main`**
(Go `covdata` toolchain; claims-service `ClaimStatusMatrixIT` timeout; rules-engine coverage backlog) —
none is caused by this PR, which in fact nudged rules-engine coverage 0.60→0.61.

Overall the horizontal thesis holds: one `ModuleKey` literal everywhere, fail-closed on every uncertain
path, tenant isolation via `search_path`, and defence-in-depth (gateway edge gate + in-service aspect).

## Build / Test status

CI read by head SHA `4db2b214`:

| Workflow | Result | Caused by this PR? |
|---|---|---|
| Go Services CI | ❌ failure — `go: no such tool "covdata"` in `internal/events` + `cmd` | **No.** Identical on `main` (run 39566653297). Pre-existing coverage-toolchain gap; the actual test packages all report `ok`. |
| Java Services CI | ❌ failure — (a) `ClaimStatusMatrixIT` TimeoutException; (b) `rules-engine` coverage 0.61 < 0.70 | **No.** Both identical on `main` (run 39566653334): claims-service IT times out there too (untouched here); rules-engine was **0.60** on main and is a documented `.claude/coverage-backlog.md` service (baseline 47.4%). This PR raised it to 0.61. |

So the authoritative suites are red for inherited reasons only. The PR's own units/ITs
(`EntitlementAspectTest`, `TenantModuleEntitlementServiceTest`, `TenantModuleEntitlementIT`,
`KycSkeletonIT`, `VerificationTemplatesTest`, Go `entitlement_test`/`registry_test`) are green per the
run logs. Not run locally (JDK-21/Docker); CI is authoritative.

## Completeness

Claimed files exist and match the narrative (67 files, +6695/−6): shared entitlement package,
tenancy V187 + controllers/service/publisher, kyc-service module, rules-engine VERIFICATION set, Go
gateway gate + registry mirror, docker-compose wiring, program docs. Behavioural claims in the body
re-validated against HEAD and hold (200/403 paths, fail-closed, no `tenant_id` on V278).

## Spec alignment (Plan A acceptance criteria)

| Criterion | Verdict | Evidence |
|---|---|---|
| Phase 1 — `tenant_module_entitlements` + Java/Go registries + aspect + gateway gate + super-admin endpoints + Kafka invalidation, fail-closed FALSE | ✅ met (⚠️ see finding #1) | V187; `TenantEntitlementRegistryR2dbc` (default FALSE + error→FALSE); `EntitlementAspect`; `EntitlementGate`; `PlatformModuleEntitlementController`; invalidation consumer. The invalidation broadcast has a gap on audit failure — finding #1. |
| Phase 2 — kyc-service :8087, party-neutral `(subject_type, subject_id)` schema, gated `/api/v1/verifications`, empty-but-live GET | ✅ met | `application.yml:2` port 8087 + Flyway disabled; V278 (no `tenant_id`, `UNIQUE(subject_type,subject_id)`); `VerificationController` `@RequiresEntitlement(KYC)`; `routes.go:345-346` + prefix map. |
| Phase 3 — `VerificationFact` + `RuleCategory.VERIFICATION` (agenda-gated) + `REQUIRE_MANUAL_REVIEW`/`GATE_ACTIVATION` verbs + emitters + 2 seed templates | ✅ met | `DrlCompiler` IMPORTS/FACT_MAPPINGS/AGENDA_GATED (68/93); emitters set the two flags; `VerificationTemplates` 2 rules; `RuleCategoryTest` 24→25. |
| Fail-closed: absent row / unreachable tenancy / missing tenant → not entitled | ✅ met | registry `.defaultIfEmpty(FALSE).onErrorResume(→FALSE)`; aspect no-tenant→`false`; gateway bootstrap logs-and-continues; gate missing-tenant→403. |
| Isolation of kyc tables with no `tenant_id` | ✅ met | kyc-service scans `com.medfund.shared` → tenant-aware connection factory sets `search_path`/`SET ROLE`; repos use unqualified names. V187 grants the table SELECT to tenant roles. |

## Verification performed

- **Kafka contract (`platform.module-entitlements.v1`)** — producer `ModuleEntitlementEventPublisher`
  emits `{tenantId, moduleKey, enabled, updatedAt, actor}`; Java `EntitlementInvalidationConsumer` and
  Go `entitlementRow` both read `tenantId/moduleKey/enabled`. One literal `"KYC"` end to end
  (`ModuleKey.name()` = Go prefix-map value = query bind = payload).
- **V187 grant regression** — diffed `provision_tenant_role` against the only prior definition (V185):
  V187 preserves all 18 readable + 3 writable grants and adds `tenant_module_entitlements`. No
  regression; backfill re-provisions every tenant.
- **Gateway wiring** — gate registered after `TenantResolver`, before `routes.Register`
  (`cmd/main.go:65,88,94`); super-admin proxy lines (`routes.go:35-36`) precede the `platformGroup` and
  carry `RequireSuperAdmin()` inline. `/internal/**` is not proxied by the gateway.
- **AuditPublisher error propagation** (the crux of finding #1) — `AuditPublisher.java:35-37` is
  `.doOnError(...).then()` with no `onErrorResume`; errors propagate. Confirms the broadcast-skip path.
- **Reactor-Kafka ack** — consumer acks on `doOnSuccess`; the error-path ack is the deliberate
  poison-drop (no swallow-without-ack). Clean per `bug_reactor_kafka_ack_swallow`.

## Important

**1. Entitlement broadcast is skipped when the audit publish fails → gateway can 403 an enabled tenant
indefinitely.** `tenancy-service/.../service/TenantModuleEntitlementService.java:99-104`.
`afterCommit` is `emitAudit(...).then(eventPublisher.publish(...)).then(securityEvent...)`. The row is
already committed by `repo.save` before `afterCommit` runs. `AuditPublisher.publish`
(`shared/.../audit/AuditPublisher.java:35-37`) **propagates** Kafka errors — unlike the broadcast and
security publishers, which both `onErrorResume(→empty)`. So on an audit-broker hiccup: row committed
`enabled=true`, the `.then()` short-circuits, **the `platform.module-entitlements.v1` broadcast never
fires**, admin gets 500. The Go gateway registry has **no TTL and no periodic resync**
(`registry.go` — `Bootstrap` once, then Kafka-only), so it never learns of the change. On the admin's
retry, `updateExisting` sees `before == enabled` and returns 200 via the **no-op path (`:78-81`) without
broadcasting**. Net: an enabled tenant is 403'd at the gateway edge with no self-heal short of a gateway
restart. (The in-service aspect self-heals on its 30s TTL, but the edge gate 403s first.) **CONFIRMED**
(read `AuditPublisher`; no-op and no-TTL paths confirmed by source).
*Fix:* make the three post-commit side-effects independent best-effort (give `emitAudit` the same
`onErrorResume` the siblings have, or merge rather than `.then()`-chain them), so a broadcast is never
hostage to audit-publish success. Longer term, give the Go registry a periodic resync as a TTL backstop.

**2. `ModuleEntitlementEventPublisher` uses a manual constructor instead of `@RequiredArgsConstructor`.**
`tenancy-service/.../kafka/ModuleEntitlementEventPublisher.java:43-46`. CLAUDE.md mandates
`@RequiredArgsConstructor` for components ("never write manual boilerplate"); every other new class in
this PR complies. No behaviour change. **CONFIRMED.** *Fix:* annotate the class, delete the constructor.

## Suggestions

3. **`matchPrefix` has no path-segment boundary** (`gateway/.../middleware/entitlement.go:54`). `HasPrefix`
   would also gate a hypothetical `/api/v1/verifications-export`. Fail-closed direction (only ever
   over-gates), no such route today. Harden: `path == prefix || HasPrefix(path, prefix+"/")`.
4. **`@RequiresEntitlement` on a non-reactive method always 403s** (`EntitlementAspect.java:78-82`).
   Documented as intentional; consider rejecting the bean at startup instead of denying at request time.
   No live target today (only `Flux` method annotated).
5. **Concurrent first-time enable can 500** (`TenantModuleEntitlementService.java:57-73`). Two racing
   inserts for the same `(tenant, module)` → the second hits `uq_tenant_module` → 500. Admin-only, rare.
   The UNIQUE correctly prevents duplicate rows.
6. **Test-consistency**: no `verificationCategory_isDeclared()` in `RuleCategoryTest` (covered by the
   size guard + `VerificationTemplatesTest`); sync-deny branch of the aspect untested.

## Triage

| # | Severity | Disposition |
|---|---|---|
| 1 | Important | **Open** — recommend fixing before merge (cheap, localized to `afterCommit` + a resync backstop). Not a Blocker: needs an audit-broker partial failure to trigger. |
| 2 | Important | **Open** — one-line convention fix. |
| 3–4, 6 | Suggestion | Deferred — cheap hardening; fold into #1/#2 fix commit if touching those files, else a follow-up. |
| 5 | Suggestion | Won't fix now — admin-only race, UNIQUE guards data integrity; revisit if it recurs. |

## Verify-stage tally

Two Importants carried into verify. Finding #1 **CONFIRMED** by reading `AuditPublisher` + the no-op/
no-TTL paths. Finding #2 is a static convention fact (no runtime claim to refute). No findings REFUTED;
none RESHAPED. No unverified Blocker/Important in this write-up.

## Deferred follow-ups (out of Plan A scope)

- **Pagination**: `KycVerificationRepository.findAllOrdered()` / `VerificationController.list()` return
  the whole tenant table unbounded — fine for a skeleton; add paging before Plan B grows the table.
- **rules-engine coverage backlog** (0.61 < 0.70) and **claims-service `ClaimStatusMatrixIT` flake** are
  pre-existing `main` failures — track separately, not this PR.
- **Angular `RULE_CATEGORIES`** has no `VERIFICATION` entry yet — authoring-surface, explicitly Plan B.

## Prior threads

None — no existing comments or reviews on this PR.
