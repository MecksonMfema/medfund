---
date: 2026-09-15T21:50:24+02:00
researcher: Methuseli
git_commit: cd4524a40a27c9918203599d4e486f928e0d6685
branch: rename-adjustments-to-notes
repository: medfund
topic: "AI service Tranche 1 gaps — where each open item stands in code, and what closing it needs"
tags: [research, codebase, ai-service, gateway, fraud, pricing, permissions, drift-monitoring]
status: complete
last_updated: 2026-09-15
last_updated_by: Methuseli
---

# Research: AI Service Tranche 1 Gaps

**Date**: 2026-09-15 21:50 +02:00 · **Researcher**: Methuseli · **Commit**: cd4524a4 · **Branch**: rename-adjustments-to-notes

## Research Question

Tranche 1 landed real fraud models per InsuranceLine plus a load-at-startup / poll-driven registry, but the summary at hand-off named eleven open items (2 blockers, 2 parked defects, 7 deferred tranches). This research maps each of those eleven gaps against the current code so a follow-up plan can be scoped precisely.

## Summary

Of the eleven gaps, only two block a pilot end-to-end:

1. **Gateway `X-User-Permissions` forwarding** — the header the Python promote endpoint expects is not populated anywhere. The best-fit implementation is a **gateway-side cache with Kafka invalidation** (Option C below) — it reuses the exact TTL, cache-key format, and `medfund.permissions.invalidated` topic the Java stack already runs on.
2. **`FraudContext` historical lookups** — three of the four canonical-vector signals return a hardcoded default at request time. Two signals (`days_since_start`, `provider_flag_count`) are trivially reachable from live endpoints/repositories today; `subject_flag_count` is live for members but not for vehicle/property; `claim_frequency_30d` needs a genuinely new query.

The remaining items are either mechanical (MOTOR/VEHICLE key), a test-harness hygiene fix (conftest.py for the chatbot flake), or genuinely large-scope multi-tranche work with substantial upstream infrastructure gaps (pricing loss-ratio labels, drift monitoring, Helm).

The single most impactful architectural takeaway: **the Java stack already has a mature permission-resolution stack — the `PermissionResolver` + Caffeine cache + Kafka invalidation triple — that the Python AI service's Phase 5 endpoint is completely unaware of.** Bridging that terminates the biggest blocker; nothing else here is a design problem.

## Findings

### Gap 1 — Gateway `X-User-Permissions` forwarding (blocker)

The Python promote endpoint requires `ai:models:promote` inside a comma-separated `X-User-Permissions` header (`services/python/ai-service/app/api/models.py:102-115`). Nothing in the gateway populates it today.

**Current state, gateway side:**
- `services/go/gateway/internal/middleware/jwt.go:85-113` — JWT middleware sets `X-Actor-ID`, `X-Actor-Email`, `X-Tenant-ID`, `X-Platform-Scope`. It inspects `realm_access.roles[]` for `super_admin` but never reads a `permissions` claim.
- No cache, no user-service call, no Kafka consumer inside the gateway process (only the platform-scope short-circuit).

**Current state, Java side (mature, hands-off from the gateway):**
- `services/java/shared/src/main/java/com/medfund/shared/security/DefaultPermissionResolver.java:30-42` — `PermissionResolver` queries `user_roles JOIN role_permissions` from the tenant schema, wrapped in a **60-second Caffeine cache** keyed `{tenantId}:{userId}`.
- `services/java/shared/src/main/java/com/medfund/shared/security/PermissionInvalidationConsumer.java:39` — consumes `medfund.permissions.invalidated` to bust the cache before TTL.
- `services/java/user-service/src/main/java/com/medfund/user/controller/RoleController.java:163-194` — `GET /api/v1/me/permissions` returns the caller's effective set (used by Angular). Super_admin short-circuit at line 184.
- `services/java/shared/src/main/java/com/medfund/shared/security/PermissionResolverFilter.java` — Spring WebFilter stuffs perms into Reactor context for `@RequiresPermission` aspect.

**Angular reference implementation:**
- `clients/angular/src/app/core/security/permission.service.ts:84` — calls `/me/permissions`, caches in `BehaviorSubject`, comment at line 18 explicitly names the "60-s cache TTL on the server".

**Four options, ordered by fit:**

| # | Approach | Feasibility | New infra required |
|---|---|---|---|
| A | Keycloak protocol mapper → `permissions` claim in JWT | **LOW** — Keycloak sandboxed from tenant tables | Custom mapper + external role sync |
| B | Gateway calls a new `/api/v1/users/{sub}/permissions` M2M endpoint per request | MEDIUM | New user-service endpoint + M2M creds in gateway |
| C | **Gateway caches per-user perms, Kafka-invalidated** | **HIGH** | Reuses existing Kafka topic + Java cache pattern |
| D | Python AI service calls user-service itself | LOW | Couples Python to Java; per-request latency |

**Recommendation: Option C.** All the pieces exist — the topic (`medfund.permissions.invalidated`), the endpoint (`/me/permissions` — or a new `/{sub}` variant with M2M auth), the TTL convention (60s), the cache-key format. Gateway grows a Caffeine-equivalent Go cache (e.g., `github.com/patrickmn/go-cache`) plus a Kafka consumer group. Python service already parses the header at `services/python/ai-service/app/api/models.py:102-115` — no changes needed downstream.

**Ancillary Kafka topic worth knowing about:** `medfund.users.role-assigned` (published by `UserEventPublisher.publishRoleAssigned`) — currently unconsumed except by the invalidation flow, but could carry richer payload if per-user cache pre-warming becomes useful later.

---

### Gap 2 — `FraudContext` historical lookups (blocker)

Every fraud request lands with `days_since_start=365`, `claim_frequency_30d=0`, `provider_flag_count=0`, `subject_flag_count=0` because `FraudContext` at `services/python/ai-service/app/services/feature_extractors.py:45-56` is a dataclass of defaults. The Kafka-consumer path reads the same fields from the CLAIM_SUBMITTED envelope but its producer stubs them at `services/python/ai-service/app/services/fraud_service.py:93-96`.

**Per-signal verdict (a=REST live / b=Kafka live / c=needs new query / d=stub-only):**

| Signal | Verdict | Evidence |
|---|---|---|
| `days_since_start` | **(a) + (b)** | `Member.enrollment_date` at `services/java/user-service/.../entity/Member.java:54`; `LifePolicy.coverage_start` etc.; live endpoint `GET /api/v1/members/{id}/policy-portfolio` already consumed by `services/python/ai-service/app/services/member_context_client.py:30`. Kafka `medfund.claims.submitted` envelope carries `service_date`. |
| `claim_frequency_30d` | **(c)** | `claims.member_id` + `claims.submission_date` at `V001__baseline.sql:127-135`, but no aggregate query exists. `ClaimQueryRepository` is member-scoped but returns individual claims, not counts. Needs new query: `SELECT COUNT(*) FROM claims WHERE member_id = :m AND submission_date >= NOW() - INTERVAL '30 days'`. |
| `provider_flag_count` | **(a)** | Already live: `FraudFlagRepository.countHighRiskForProviderSince()` at `services/java/claims-service/src/main/java/com/medfund/claims/siu/repository/FraudFlagRepository.java:30-34`. Consumed by `SiuCaseService.countsFor()` and rules template `FRAUD5`. No REST wrapper today; the AI service would need `GET /api/v1/providers/{id}/fraud-flags/count?since=…` added to claims-service. |
| `subject_flag_count` | **(a) for members / (c) for assets** | Members: `FraudFlagRepository.countHighRiskForMemberSince()` at line 25-28 — same story as provider, live but not REST-exposed. Vehicle/property: no query exists; asset → claim → fraud_flag join needs authoring. |

**Cross-service flow that already exists but was never wired into feature-extraction:**

- Python AI service scores → publishes to `medfund.claims.fraud-flagged` (topic name in `services/python/ai-service/app/core/kafka_producer.py`).
- `FraudFlaggedConsumer` (claims-service) → persists to `fraud_flag` table (`V269__fraud_flag.sql`) → invokes `SiuCaseService.evaluateTriage`, which itself calls the same `countHighRiskForProviderSince` / `countHighRiskForMemberSince` queries the AI service now needs.

**Implication for a follow-up plan:** the historical-count queries are already load-tested by SIU; the follow-up is a cross-service read surface (small REST endpoint on claims-service) plus an HTTP client on the AI service that lives next to `member_context_client.py`. `claim_frequency_30d` is the only signal that needs a genuinely new SQL statement, and it's a one-line count.

---

### Gap 3 — Phase 0.5 anonymization audit sign-off

Not a code gap — a manual policy artifact.

- Doc: `thoughts/shared/audit/2026-09-15-ai-training-corpus-anonymization.md` — currently `status: DRAFT — awaiting auditor sign-off`.
- Gate: `services/python/ai-service/scripts/_audit_gate.py` reads `MEDFUND_TRAINING_AUDIT_SIGNED_OFF` env var. `train_fraud.py` refuses to write until it's `true`.
- Sample-dump for the auditor: `services/python/ai-service/scripts/dump_corpus_sample.py` exists and runs with a fixed seed.

**Follow-up shape:** operator work — reviewer signs off in the doc, PR flips the env var in the deployment config. No plan needed.

---

### Gap 4 — MOTOR / VEHICLE pricing key mismatch (parked)

**Root cause, precisely located:**

- Python registration: `services/python/ai-service/app/api/pricing.py:145` — dispatch table maps `"MOTOR": _score_motor` (with `"VEHICLE"` absent).
- Java sender: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/AiPricingClient.java:99-109` — `resolveInsuranceLine()` reads `schemes.insurance_line` and passes the raw enum name, so `"VEHICLE"` is what leaves the JVM.
- Java enum: `services/java/shared/src/main/java/com/medfund/shared/insurance/InsuranceLine.java:31` — defines `VEHICLE` as the canonical name; the same enum has a UI-alias helper `from("MOTOR") → VEHICLE` at line 59-62.
- Consequence: every VEHICLE pricing call falls through the `unknown line` branch at `pricing.py:158-162` and returns `multiplier=1.0` with rationale `"No scorer registered for line 'VEHICLE'"`.

**Test that would have caught it:** `services/python/ai-service/tests/test_prediction_audit.py:243-262` posts VEHICLE and asserts a 200, but never checks the multiplier. `tests/services/test_pricing_registry.py:41-45` parametrizes over `list(InsuranceLine)` but only asserts the returned model type, not the rationale.

**Patch shape:** one-line change on Python — add `"VEHICLE": _score_motor` at line 145 and keep `"MOTOR"` as a legacy alias for anything that predates the enum rename. Add a regression assertion to the audit test.

---

### Gap 5 — `test_chat_with_conversation_id` ordering flake

**Symptom:** passes in isolation, fails when the whole suite runs.

**Shared-state candidates:**

- `services/python/ai-service/app/core/gemini_client.py:8-30` — process-lifetime `GeminiClient` singleton.
- `services/python/ai-service/app/services/fraud_registry.py:55-59` — module globals `_CACHE`, `_FALLBACK`, `_SCHEMA_MISMATCHES`, `_poll_task`.
- `services/python/ai-service/tests/test_chatbot.py:4` — module-scoped `client = TestClient(app)` that no test tears down.

**No conftest.py under `tests/`** — the repo has zero fixtures that reset app dependency overrides or module singletons between test modules. `test_pricing_registry.py:31-38` cleans up its own state via `reset_for_tests()`, but nothing else does.

**Most plausible cause:** an upstream test in the full run monkey-patches Gemini/LLM state (`gemini_module.gemini_client`, `anthropic_module.claude_client`, or a `resolve_llm()` override) and leaves it in place. When `test_chat_with_conversation_id` runs later, the LLM path returns a mocked / None-shaped response and the conversation_id echo assertion fails.

**Fix shape:** add `services/python/ai-service/tests/conftest.py` with an `autouse=True` fixture that snapshots + restores `app.dependency_overrides` and resets known module globals (`fraud_registry.reset_for_tests()`, `pricing_registry.reset_for_tests()`, `gemini_module.gemini_client`, `anthropic_module.claude_client`).

---

### Gap 6 — Pricing training (Tranche 2, loss-ratio labels)

**What exists:**

1. **Contribution / premium side.** `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Contribution.java:1-161` holds premium amounts per member (or dependant) per period. Cross-service aggregate endpoint: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/controller/PremiumAggregateController.java:46-66` — `GET /api/v1/reports/aggregate/premium-earned`, gated by `CONTRIBUTIONS_READ_AGGREGATE` (M2M-only).
2. **Claims-paid side.** `services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java:1-285` — `paid_amount` + `adjudicated_at`. Aggregate endpoint: `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimsAggregateController.java:98-120` — `GET /api/v1/reports/aggregate/claims-incurred`, gated by `CLAIMS_READ_AGGREGATE`.
3. **A live composer that already joins them.** `services/java/finance-service/src/main/java/com/medfund/finance/kpi/service/KpiComposerService.java:76-96` — Phase 18 executive KPI composer runs `computeLossRatio(warnings, req)` combining the two aggregates as `(paidClaims + Δreserve) / earnedPremium`.

**What's missing for training labels:**

- **Grain mismatch.** Both aggregate endpoints group by `(scheme, line, currency)`; training needs `(member_id, insurance_line, 6-month_window)`. A new per-member aggregate endpoint or a denormalized `member_loss_metrics` table is required.
- **No training corpus table.** `ai_predictions_store` only carries fraud accept/override labels. A `pricing_training_corpus` (or `member_loss_labels`) table needs to accumulate rows keyed on `(member_id, line, window_end)` with the loss-ratio value.
- **6-month rolling window logic.** Fragments exist in `KpiComposerService.trend()` at lines 126-142 (monthly bucketing), but no formalized 6-month rolling join.

Tranche 2 pricing work is a genuine multi-week effort, not a small ticket — it needs new data infrastructure before a `train_pricing.py` script is meaningful. The Phase 4 plumbing shipped in Tranche 1 (resolver + registry slot + admin row) is ready to consume artifacts the moment the labels exist.

---

### Gap 7 — Per-tenant models (Tranche 3)

Not researched in this pass — currently a single line in the Tranche 1 plan's "Not Doing" section. Needs its own research doc when Tranche 3 opens (differential privacy technique choice, tenant opt-in UX, cross-tenant vs per-tenant training toggle). Punted.

---

### Gap 8 — Push-based / event-driven model reload (Tranche 2)

**Current state:** polling loop at `services/python/ai-service/app/services/fraud_registry.py:167-199` — 60 s per pod. Pricing registry mirrors the pattern.

**Options:**

- **SIGHUP handler** — no existing signal handler in `services/python/ai-service/app/**`. Cost: `signal.signal(SIGHUP, handler)` + call `refresh_from_manifest()`. Ops sends signal via `kubectl exec` or a `preStop` hook. **Lowest infra cost.**
- **Kafka topic** (`medfund.ai.model.reload` or similar) — no such topic exists today. Would require a new topic, a consumer group per pod, and consumer wiring in the FastAPI lifespan. The AI service already has aiokafka wiring for `medfund.audit.events`, so the incremental cost is a new consumer group, but the operational overhead (topic definition, per-pod consumer, at-least-once delivery semantics) makes it heavier than needed for what's effectively a cache bust.
- **Stay with poll** — 60 s pilot promotion latency is acceptable per G6.

**Least-infra winner: SIGHUP.** Notably, the Elixir chat_service (`services/elixir/apps/chat_service/lib/chat_service/application.ex:1-15`) has no config-reload machinery either, so no cross-language pattern is being ignored.

---

### Gap 9 — Drift monitoring (Tranche 3)

**Infrastructure that exists:**

- **Scheduled job framework.** `services/java/shared/src/main/java/com/medfund/shared/scheduler/ScheduledJobConfig.java:1-152` — `scheduled_job_configs` table in the public schema, with `job_type`, `cron_expression`, `settings` (JSONB), `next_execution_at`. `ScheduledJobService` + `ScheduledJobRepository` handle CRUD + run history.
- **Kubernetes CronJob pattern** — the Tranche 1 `k8s/cronjobs/train-fraud.yaml` is the first-of-its-kind manifest; nothing else exists yet.
- **Observability.** `.claude/infrastructure.md:242-258` promises OpenTelemetry → Mimir/Tempo/Loki. AI Service dashboard specced at line 260-266 lists "Prediction latency, confidence distribution, override rate" but no drift-score-distribution dashboard.
- **Prediction storage.** `services/python/ai-service/app/models/db_models.py:9-27` — `ai_predictions_store.output` is a JSON blob carrying `risk_score`; no time-bucketed histogram.

**What's missing:**

- Drift-comparison job type registered in `scheduled_job_configs`.
- A `risk_score_histogram` (or bucketed table) so a weekly job can compute vs a monthly window without full-table scans.
- Statistical distance computation (KS test, Wasserstein, PSI) — nothing in the codebase computes distribution divergence.
- Alerting rules on distribution shift — infrastructure.md lists rules for latency + Kafka lag, none for score drift.
- **Time-window comparison template exists** in fragments: `KpiComposerService.trend()` monthly bucketing at `services/java/finance-service/src/main/java/com/medfund/finance/kpi/service/KpiComposerService.java:126-142`. A drift job can be templated on that shape (buckets + per-bucket stats) but computes a divergence metric on top.

Tranche 3 territory — multiple pieces to invent before the CronJob glue.

---

### Gap 10 — Per-line trainers for LIFE / FUNERAL / DISABILITY / TRAVEL / GROUP

Data-availability constrained, not code-constrained. Tranche 1 shipped the artifact framework line-generically (see `LINE_HYPERPARAMS` at `services/python/ai-service/scripts/train_fraud.py:68-77` — hyperparams exist for all eight lines including LIFE/FUNERAL/etc). Adding CronJobs mirroring `k8s/cronjobs/train-fraud.yaml` is a copy-paste when the 200-sample data-sufficiency threshold clears per line.

No follow-up plan required until pilot data accumulates.

---

### Gap 11 — Helm chart wrap for CronJobs (deferred)

- **`.claude/infrastructure.md:73-110`** describes a planned layout: `infrastructure/helm/charts/{claims-service,contributions-service,…,ai-service}/` + `infrastructure/helm/umbrella/medfund-platform/`.
- **Reality:** no `infrastructure/helm/` directory. No `Chart.yaml` anywhere. `k8s/cronjobs/train-fraud.yaml` is the **only** kubernetes manifest in the repo.
- **Status:** pre-Helm. Wrapping in Helm is a platform-level infra story, not an AI-service story — the AI service is just the first tenant that would use it.

## Cross-service flow

The two blockers imply the same cross-service topology:

**Gap 1 (permissions):**
```
Angular /promote button click
  → gateway HTTPS request with Bearer JWT
    → gateway JWT middleware (jwt.go:45) validates + sets X-Actor-*
    → NEW: gateway per-user permission cache (60s TTL, Kafka-invalidated)
      → cache miss: HTTPS GET user-service /api/v1/users/{sub}/permissions (M2M)
    → gateway proxies to AI service with X-User-Permissions: "ai:models:promote,ai:models:view,..."
      → Python endpoint at models.py:194 checks the header
```

**Gap 2 (fraud context):**
```
POST /api/v1/ai/fraud/check
  → gateway → AI service fraud endpoint
    → HealthExtractor.extract calls _canonical(req, tenant_id, ctx)
      → NEW: ctx is populated by a HistoricalSignalRepository that fans out:
        → member_context_client → GET /api/v1/members/{id}/policy-portfolio (days_since_start)
        → NEW: claim_history_client → GET /api/v1/members/{id}/claim-count?window=30d (claim_frequency_30d)
        → NEW: fraud_flag_client → GET /api/v1/providers/{id}/fraud-flags/count?since=… (provider_flag_count)
        → NEW: fraud_flag_client → GET /api/v1/members/{id}/fraud-flags/count?since=… (subject_flag_count)
    → canonical vector fed to resolve_fraud_model(line).predict_canonical(...)
```

Both new REST surfaces on the Java side reuse existing SQL: for Gap 2 the queries live in `FraudFlagRepository` already; only the wrapping controllers are new. For Gap 1 the resolver lives in `DefaultPermissionResolver` already; the gateway just needs a cache + Kafka consumer.

## Architecture doc vs. code

Two clear drifts:

- **`.claude/infrastructure.md:73-110`** promises `infrastructure/helm/charts/**` — code has zero Helm assets. Doc is aspirational.
- **`.claude/infrastructure.md:260-266`** promises an AI-service dashboard with "Prediction latency, confidence distribution, override rate" — no such dashboard config exists in the repo; there is no `dashboards/` or `grafana/` directory.
- **`.claude/ai-integration.md`** describes a hybrid Drools + classical ML + Claude pipeline. Tranche 1 delivers the classical-ML piece for fraud; the doc's promise is intact but the coverage per line is uneven (three lines trained, five on canonical fallback).

No drift found between the Java permission resolver design and code — that piece is solid.

## Code References

**Gap 1 — Gateway forwarding:**
- `services/go/gateway/internal/middleware/jwt.go:85-113` — current header set
- `services/java/shared/src/main/java/com/medfund/shared/security/DefaultPermissionResolver.java:30-42` — Java resolver + cache
- `services/java/shared/src/main/java/com/medfund/shared/security/PermissionInvalidationConsumer.java:39` — Kafka invalidation
- `services/java/user-service/src/main/java/com/medfund/user/controller/RoleController.java:163-194` — `/me/permissions` endpoint
- `services/python/ai-service/app/api/models.py:102-115` — Python side already parses the header

**Gap 2 — FraudContext lookups:**
- `services/python/ai-service/app/services/feature_extractors.py:45-56` — the stubbed dataclass
- `services/python/ai-service/app/services/fraud_service.py:93-96` — Kafka path defaults
- `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:54` — enrollment_date source
- `services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java:24,64` — member_id + submission_date
- `services/java/claims-service/src/main/java/com/medfund/claims/siu/repository/FraudFlagRepository.java:24-34` — the two live count queries

**Gap 4 — MOTOR/VEHICLE:**
- `services/python/ai-service/app/api/pricing.py:145` — where the key is `"MOTOR"`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/AiPricingClient.java:99-109` — where `"VEHICLE"` gets sent
- `services/java/shared/src/main/java/com/medfund/shared/insurance/InsuranceLine.java:31,59-62` — the enum + its MOTOR alias helper
- `services/python/ai-service/tests/test_prediction_audit.py:243-262` — the assertion-lite regression test

**Gap 5 — Chatbot flake:**
- `services/python/ai-service/tests/test_chatbot.py:4,20-30` — module-scoped `TestClient` + no fixture reset
- `services/python/ai-service/app/services/fraud_registry.py:55-59,242-247` — globals + `reset_for_tests()`
- Absent: `services/python/ai-service/tests/conftest.py`

**Gap 6 — Pricing loss-ratio:**
- `services/java/finance-service/src/main/java/com/medfund/finance/kpi/service/KpiComposerService.java:76-96` — the live join
- `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/controller/PremiumAggregateController.java:46-66` — premium aggregate endpoint
- `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimsAggregateController.java:98-120` — claims aggregate endpoint

**Gap 9 — Drift monitoring:**
- `services/java/shared/src/main/java/com/medfund/shared/scheduler/ScheduledJobConfig.java:1-152` — scheduled-job framework
- `services/python/ai-service/app/models/db_models.py:9-27` — where risk_score lives, un-bucketed
- `.claude/infrastructure.md:242-266` — dashboard + metric promise (currently aspirational)

## Architecture Insights

**Critical Rule alignment for the two blockers:**

- **Rule 4 (Data protection first).** Gap 1 is directly a Rule-4 issue: without the header, the promote endpoint has no defence-in-depth beyond the confirmation modal. The Java side's caching pattern is already Rule-4-shaped (per-tenant cache key, per-user scope, Kafka-invalidated). Gap 1 done right *reinforces* Rule 4 across the polyglot boundary rather than duplicating it.
- **Rule 6 (Kafka events).** Gap 1 leans on the existing `medfund.permissions.invalidated` topic — no new event contracts. Gap 2 could optionally emit historical-signal fetch events on Kafka but the synchronous REST call is cheaper (context is read on every fraud check).
- **Rule 8 (audit-log per mutation).** Gap 1 has no mutation of its own; the promote endpoint already emits its audit event. Gap 2 is read-only.

**Reusable pattern:** `services/python/ai-service/app/services/member_context_client.py` (the existing `httpx`-based Java client) is the exact shape a `HistoricalSignalRepository` should take. Same construction, same tenant-id header forwarding.

**Anti-pattern to avoid:** don't put the historical-count queries in the AI service database. `ai_predictions_store` is AI-service-owned SQLAlchemy; the claim / fraud_flag rows live in per-tenant Postgres schemas the AI service must **not** query directly (Rule 2 — every DB query is tenant-scoped via `TenantContext`, and the AI service has no such interceptor for tenancy-DB tables). REST via the gateway is the correct boundary.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md` — the research doc that fed Tranche 1's plan; still the reference for what tranches 2/3 would cover.
- `thoughts/shared/plans/2026-09-15-ai-service-pilot-readiness.md` — Tranche 0 plan; contains the `_canonical(req, tenant_id, ctx)` design where `FraudContext` was *supposed* to accumulate historical signals in Tranche 1 (never picked up).
- `thoughts/shared/plans/2026-09-15-ai-service-tranche-1-model-training.md` — the plan that just landed (46 files committed at cd4524a4).
- `thoughts/shared/audit/2026-09-15-ai-training-corpus-anonymization.md` — Phase 0.5 doc awaiting sign-off.

## Related Research

- Gaps + strategy (parent): `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md`
- Tenant provisioning RBAC (Gap 1 depends on this being solid): `thoughts/shared/research/2026-09-15-tenant-provisioning-rbac-model.md`

## Open Questions

1. **For Gap 1, does the gateway want a Go Caffeine equivalent or would a hand-rolled `sync.Map` + `time.AfterFunc` cache be simpler for the pilot?** — pending a call with the platform team.
2. **For Gap 2, is a single `GET /api/v1/claims/context/{claim_id}` mega-endpoint (returning all four signals in one round-trip) preferable to four narrow endpoints?** — favors the AI service (one HTTP call, no fan-out) but couples the shape to the caller.
3. **For Gap 6, does the pricing training corpus want to live in the AI service DB (SQLAlchemy) or in a new tenancy-service table with the standard tenant scoping?** — the fraud corpus lives in AI-service-owned `ai_predictions_store`; consistency argues for the same, but loss-ratio joins into contributions/claims argue for putting it closer to that data.
4. **For Gap 9, does the AI service subscribe to `medfund.claims.fraud-flagged` for a streaming drift calculation, or does it batch-scan `ai_predictions_store` on a schedule?** — streaming is cheaper on read but harder to backfill.
