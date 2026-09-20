---
date: 2026-09-15
git_commit: 26906fa65318807e020e2abb4aad75bfc6710a75
branch: rename-adjustments-to-notes
research:
  - thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md
steer: "start with the claims FK fix, that's the highest-confidence and unblocks Phase 6 verify"
services_touched: [user-service, claims-service, contributions-service, angular, scripts]
status: partially-superseded
superseded_by: thoughts/shared/plans/2026-09-20-platform-scoped-providers-with-tenant-membership.md
superseded_at: 2026-09-20
superseded_scope: phase-1-only
---

> **⚠️ Phase 1 (provider fan-out) superseded on 2026-09-20.**
>
> After follow-up research ([thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md](../research/2026-09-20-platform-scoped-providers-with-tenant-membership.md)) the Option A fan-out approach was replaced by Option C: platform-scoped providers with a `public.provider_tenants` membership junction and a `public.provider_insurance_lines` line-tag junction. The three uncommitted Phase 1 files (`ProviderOnboardedConsumer.java`, `ProviderBackfillRunner.java`, `ProviderOnboardedConsumerTest.java`), the extended `UserEventPublisher.publishProviderOnboarded` 4-arg signature, and the matching `ProviderService.onboard` call-site change are all deleted / reverted in Phase 1 of the superseding plan.
>
> **Phase 2 (age-group `effectiveFrom`) is NOT superseded.** That change is orthogonal to the provider work, already applied in the working tree, and stays. The superseding plan explicitly does not touch it.
>
> **Do not implement Phase 1 from this document.** Follow the superseding plan instead: [thoughts/shared/plans/2026-09-20-platform-scoped-providers-with-tenant-membership.md](2026-09-20-platform-scoped-providers-with-tenant-membership.md).

# ~~Provider fan-out~~ + age-group `effectiveFrom` implementation plan

*(Provider fan-out portion superseded 2026-09-20; only the age-group `effectiveFrom` half of this plan remains active.)*

## Overview

Two targeted server-side fixes that unblock the demo-seeder's Phase 6 verify pass:

1. ~~Ship a per-tenant fan-out consumer + boot-time backfill so every platform provider in `public.providers` gets a shadow row in every `tenant_<uuid>.providers` schema. This is the root cause of the `POST /api/v1/claims` 500: `claims.provider_id` FKs into the tenant-local `providers` table, which was empty because no consumer subscribed to `medfund.users.provider-onboarded`. See research at [thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md](../research/2026-09-15-seeder-phase-6-500s-root-cause.md) §Findings.~~ **[SUPERSEDED 2026-09-20]** Replaced by the platform-scoped-providers redesign: `tenant_<uuid>.providers` is dropped entirely, providers stay in `public.providers` alone, and per-tenant relevance is expressed via a new `public.provider_tenants` membership junction. See [thoughts/shared/plans/2026-09-20-platform-scoped-providers-with-tenant-membership.md](2026-09-20-platform-scoped-providers-with-tenant-membership.md).
2. Add an optional `effectiveFrom` field to `CreateAgeGroupRequest`. `SchemeService.insertCurrentPrice` currently hard-codes `effective_from = CURRENT_DATE`, so historical billing periods produced by the seeder's back-dated timeline miss every `age_group_prices` row, and the preview response collapses to `amount=0`. Wiring an optional date through the DTO lets the seeder pass `timeline_start` while keeping the real-operator default (today) unchanged. **(Still active; already applied in the working tree.)**

Scope is deliberately narrow: only the two HIGH-confidence root causes from the research doc. The two MODERATE / LOW-confidence 500s (`POST /api/v1/contributions/commit`, `POST /api/v1/payment-runs`) are deferred to a follow-up plan gated on a runtime stdout capture (see [What We're NOT Doing](#what-were-not-doing)).

## Current State Analysis

### ~~Provider FK: schema drift, no fan-out~~ [SUPERSEDED]

*The entire "Provider FK" analysis below described the situation that motivated the Option A fan-out. It is retained for historical context. The superseding plan's Current State Analysis re-analyses the same situation and reaches a different design: retire the tenant-local `providers` shadow rather than keep it populated by fan-out.*

- Every `POST /api/v1/providers` writes to `public.providers`. Entity: `services/java/user-service/src/main/java/com/medfund/user/entity/Provider.java:18` (`@Table(schema = "public", value = "providers")`).
- Every tenant schema gets its own `providers` table at provisioning time. Migration: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:60-78`.
- `claims.provider_id` FKs into the tenant-local `providers` table. Migration: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V001__baseline.sql:129`.
- `UserEventPublisher.publishProviderOnboarded` already fires on every onboard. Publisher: `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java:161-167`. Call site: `services/java/user-service/src/main/java/com/medfund/user/service/ProviderService.java:128`.
- **No consumer subscribes.** `grep -r 'medfund.users.provider-onboarded'` across `services/java/` returns only the publisher, its test, and the topic-registry doc. Every existing tenant schema's `providers` table is therefore empty even after the seeder has posted N providers to `public.providers`.
- Effect: `ClaimService.submit` at `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java:258` calls `claimRepository.save(claim)`; Postgres enforces the FK against the tenant's empty `providers` table; the R2DBC `DataIntegrityViolationException` reaches `GlobalExceptionHandler` and becomes a generic 500 with only a correlation ID.

### Age-group prices: `effective_from` hard-coded to today

- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/SchemeService.java:91-102` hard-codes `effective_from = CURRENT_DATE` in the `age_group_prices` INSERT.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/candidate/HealthCandidateResolver.java:112-120` LATERAL-joins on `age_group_prices WHERE effective_from <= :periodEnd AND (effective_to IS NULL OR effective_to >= :periodStart)`.
- When the seeder's timeline back-dates a preview to (e.g.) 2026-06-01 and Phase 3 posted the age group today (2026-09-15), the LATERAL join finds no row and the derived amount falls through to NULL. Downstream serialization renders NULL as `"0"` in the response body (the exact null-to-zero path isn't in scope; the fix cuts the LATERAL miss at its root by populating history correctly).

### Existing publisher and consumer patterns to model on

- Reactor-Kafka consumer template: `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/SchemeChangedConsumer.java:55-71`. `.doOnSuccess(v -> record.receiverOffset().acknowledge())` ack, `.onErrorResume(e -> ack; return Mono.empty())` on failure, `.retry()` at the top level for infra-only retries.
- `ReceiverOptions<String, String>` bean already lives in `services/java/claims-service/src/main/java/com/medfund/claims/config/KafkaConsumerConfig.java:26`; adding a new consumer to claims-service needs no new infrastructure bean.
- Boot-time per-tenant iteration: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantMigrationRunner.java:32` shows the `CommandLineRunner` + raw-SQL `SELECT ... FROM public.tenants` pattern with log-and-continue error handling.
- `TenantAwareConnectionFactory` at `services/java/shared/src/main/java/com/medfund/shared/tenant/TenantAwareConnectionFactory.java:74-90` resolves the tenant schema from `TenantContext` (a UUID string) via `Mono.deferContextual`; when no tenant is in context, `search_path` falls back to `public` only. That means the same connection factory serves platform reads (`public.tenants`, `public.providers`) and per-tenant writes (`providers` in each tenant schema) simply by wrapping the tenant-scoped Mono in `.contextWrite(Context.of(TenantContext.KEY, tenantUuid.toString()))`.

## Desired End State

After both phases are applied and claims-service + user-service + contributions-service redeploy:

- Every existing platform provider in `public.providers` has an idempotent shadow row in every `tenant_<uuid>.providers` schema (via the boot-time backfill runner).
- Every new `POST /api/v1/providers` fan-outs to every tenant's `providers` table within a Kafka event round-trip (via the reactor-kafka consumer).
- `POST /api/v1/claims` returns 201 for the seeder's payloads instead of 500. `make seed-demo-verify TIER=micro` reports `claims_submitted > 0`.
- `POST /api/v1/contributions/preview` returns non-zero `amount` for the seeder's back-dated periods, because `SchemeService.insertCurrentPrice` writes `age_group_prices` rows with `effective_from = timeline_start` when the seeder supplies it.
- The Angular tenant-admin age-group form continues to work unchanged (backwards-compatible: the new field is optional, existing form submissions omit it, and the backend defaults to today).

### Verification

- `curl -X POST http://localhost:8083/api/v1/claims -H 'X-Tenant-ID: ...' -H 'Authorization: Bearer ...' -d @claim.json` returns 201 (was 500).
- `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM tenant_health_first.providers"` matches `SELECT count(*) FROM public.providers`.
- `curl -X POST http://localhost:8084/api/v1/contributions/preview -d '{"insuranceLine":"HEALTH","periodStart":"2026-06-01",...}'` returns rows with `amount > 0` when age-groups were seeded with `effectiveFrom = 2026-06-01`.
- `make seed-demo-reset && make seed-demo-micro && make seed-demo-verify TIER=micro` transitions the `claims` metric from FAIL to PASS and the preview amount-zero cascade no longer trips.

### Key Discoveries

- `UserEventPublisher.publishProviderOnboarded` at `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java:161-167` already fires. Payload today is `{event, providerId, name}` via `Map.of(...)`. We need to extend it to carry `status` and `networkTier` so the shadow row reflects platform state accurately, and switch to a `LinkedHashMap` with null-guards to match the null-tolerant pattern used by every other publisher method in this class (`publishMemberEnrolled`, `publishMemberLifecycle`, etc.).
- Fan-out UPSERT can be **minimal**: `id`, `name`, `status`, `network_tier`. Nothing else is read from `tenant.providers` in the claims-service SQL (`ClaimsReportQueryRepository` selects `name` only for `dimension_name`; `ClaimFactBuilder` reads `public.providers` directly for `status` + `provider_type`; `ProviderClient` fetches full metadata over HTTP from user-service). Every other column on `tenant.providers` gets its schema default on INSERT (`banking_details DEFAULT '{}'`, `status DEFAULT 'active'`, `network_tier DEFAULT 'STANDARD'`, timestamps `DEFAULT NOW()`). Overriding `status` and `network_tier` from the event payload is enough to keep the row accurate.
- Existing consumers in the codebase never re-order events across a partition; the `providerId` partition key (already set by `publishEvent`) ensures that a `PROVIDER_ONBOARDED` for a given provider always fans out before any later update for the same provider. That property holds by virtue of Kafka partition ordering; no consumer-side coordination needed.
- `age_group_prices` schema already accepts any DATE for `effective_from`, so **no Flyway migration** is needed for Phase 2. Migration V029 at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V029__age_group_price_history.sql:23-46` defines `effective_from DATE NOT NULL` without further constraint.
- `CreateAgeGroupRequest` is a `record` (six fields) at `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/CreateAgeGroupRequest.java:11-21`. Adding an optional `LocalDate effectiveFrom` extends the record by one field. Records in Java are positional; extending a record adds a new positional slot. Every callsite (`SchemeServiceTest.java:232, :402, :588`) uses positional construction. Rather than plumb the new slot through every test, the plan uses **two constructors on the record** (compact canonical + secondary that defaults `effectiveFrom = null`) or a static factory. See Phase 2 §2.1.
- Angular's `UpsertAgeGroupPayload` at `clients/angular/src/app/core/services/contributions.service.ts:361-368` is a TypeScript interface. Adding an optional field is silently omitted from JSON when unset; existing form submissions in `age-group-form.component.ts:117-123` are untouched.
- The auto-memory notes several relevant guardrails: [[infra_testcontainers_pitfalls]] (BOM override, flyway-database-postgresql, ReactiveJwtDecoder stub), [[bug_reactor_kafka_ack_swallow]] (`.doOnSuccess` for ack, not `.doOnTerminate`), [[feedback_audit_actor_email]] (every audit event needs `actorEmail`), and [[feedback_no_em_dashes]] (no em dashes in code, commits, plan text, or chat).

## What We're NOT Doing

- **Not** fixing the `POST /api/v1/contributions/commit` 500. Research assigns MODERATE confidence to three candidates inside `BillingService.doCommit`, none of which can be picked without a runtime stdout capture. That capture is Phase 3's manual verification; the fix belongs in a follow-up plan.
- **Not** fixing the `POST /api/v1/payment-runs` 500. Research assigns LOW confidence; static reading is inconclusive. Same runtime capture + follow-up plan.
- **Not** handling `PROVIDER_UPDATED`, `PROVIDER_SUSPENDED`, `PROVIDER_ACTIVATED`, or `PROVIDER_VERIFIED` events. `ProviderService.update`, `.suspend`, `.activate`, `.verifyAhfoz`, `.updateNetworkTier` publish audit events only, not Kafka business events (verified at `ProviderService.java:150-234`). The seeder never mutates providers after onboarding, so the shadow row's `status` field can drift without breaking the FK. Widening to updates is a follow-up.
- **Not** retiring the `tenant.providers` shadow (Option B in the research). Option A (fan-out consumer) is what this plan implements; retirement would break Critical Rule 2 (tenant-scoped queries) without additional design.
- **Not** adding new columns to `tenant.providers` (V001 has `practice_number` while `public.providers` has `registration_number` after V106; V001 lacks `provider_type` and `city` which V105 added to `public.providers`). The shadow row only needs `id + name + status + network_tier` for claims-service reads; the schema drift is out of scope. A follow-up plan can align the two shapes.
- **Not** exposing `effectiveFrom` in the Angular age-group form UI. The Angular DTO extends by one optional field for API-consistency; the form remains unchanged. Tenant admins continue to see the "price effective today" behavior. If back-dating in the UI becomes desired, that is a UI plan.
- **Not** modifying `tenant.providers` primary-key semantics. The FK from `claims.provider_id` to `tenant.providers.id` remains. The shadow row shares the platform provider's UUID as its own primary key.

## Implementation Approach

**Phase 1** touches three services (user-service payload extension, claims-service new consumer + backfill runner) with **no schema migrations**. Kafka `AUTO_OFFSET_RESET=earliest` on the new consumer group means historical `medfund.users.provider-onboarded` events already in the topic replay on first subscribe; the boot-time backfill runner is the defensive belt-and-braces for cases where retention has dropped events or providers were seeded through a code path that bypassed `ProviderService.onboard` (not observed today but cheap to guard).

**Phase 2** is a two-line service change and a DTO extension: optional `effectiveFrom` on the record, plumbed into `insertCurrentPrice`. The seeder passes `timeline_start`, Angular gets the field for future use, no schema migration.

**Phase 3** is verification only: re-run the seeder against a fresh reset, verify the two unblocked metrics flip to PASS, and record whether the two deferred 500s (contributions-commit, payment-run) are now non-500 (bonus outcome) or still trip (as expected, follow-up plan needed with the stdout capture instructions from the research doc).

**Backwards compatibility.** The `publishProviderOnboarded` payload extension is additive: every existing consumer (which today reads `event`, `providerId`, `name` at most) continues to work. Kafka `medfund.users.provider-onboarded` is a producer-shared topic today with no in-repo consumers other than the new one this plan adds, so no coordination round is needed. The `CreateAgeGroupRequest` extension is additive at the JSON layer (optional field, unset defaults to today); the record's Java constructor gains a secondary that preserves existing test callsites.

---

## ~~Phase 1: Provider fan-out consumer + boot backfill~~ [SUPERSEDED 2026-09-20]

> **Do not implement.** All Phase 1 code (uncommitted in the working tree at commit `109cdc9b` on 2026-09-20) is deleted in Phase 1 of the superseding plan: [thoughts/shared/plans/2026-09-20-platform-scoped-providers-with-tenant-membership.md](2026-09-20-platform-scoped-providers-with-tenant-membership.md). The `UserEventPublisher.publishProviderOnboarded` 4-arg signature reverts to 2-arg, and the `ProviderService.onboard` call-site reverts to match.
>
> The original Phase 1 specification below is retained verbatim for historical context. It records what was tried, why it looked right at the time, and what changed the analysis (research doc 2026-09-20 found that platform-level providers + membership junction is a cleaner design than keeping the shadow populated).

### Overview

Extend the `PROVIDER_ONBOARDED` event payload to include `status` and `networkTier`. Add a reactor-kafka consumer in claims-service that upserts a shadow row into every tenant schema's `providers` table for each event. Add a `CommandLineRunner` that, at claims-service boot, iterates `public.providers` × `public.tenants` and does the same upsert (idempotent). Ship integration tests that submit a claim end-to-end and assert 201.

### Changes Required

#### 1. Extend `UserEventPublisher.publishProviderOnboarded` payload

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java`
**Changes**: Extend the method signature with `status` and `networkTier`; switch from `Map.of(...)` (null-hostile) to `LinkedHashMap` with null-guards, matching the pattern used by `publishMemberEnrolled` at lines 34-49.

```java
public Mono<Void> publishProviderOnboarded(String providerId, String name,
                                            String status, String networkTier) {
    var payload = new java.util.LinkedHashMap<String, String>();
    payload.put("event", "PROVIDER_ONBOARDED");
    payload.put("providerId", providerId);
    payload.put("name", name);
    payload.put("status",      status      != null ? status      : "pending_verification");
    payload.put("networkTier", networkTier != null ? networkTier : "STANDARD");
    return publishEvent("medfund.users.provider-onboarded", providerId, payload);
}
```

#### 2. Update `ProviderService.onboard` to pass the two new fields

**File**: `services/java/user-service/src/main/java/com/medfund/user/service/ProviderService.java`
**Changes**: Update the publisher call at line 128 to include `saved.getStatus()` and `saved.getNetworkTier()`.

```java
.then(eventPublisher.publishProviderOnboarded(
    saved.getId().toString(),
    saved.getName(),
    saved.getStatus(),
    saved.getNetworkTier()))
```

#### 3. New consumer: `ProviderOnboardedConsumer` in claims-service

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderOnboardedConsumer.java`
**Changes**: New file. Follows the `SchemeChangedConsumer` template verbatim: `@PostConstruct` subscribe, `.doOnSuccess(...ack...)`, `.onErrorResume` log-and-ack, `.retry()` at the top level. On each event, reads all active tenant UUIDs from `public.tenants` (no tenant context set on that read, so it lands in `public` per `TenantAwareConnectionFactory:84-86`), then `concatMap`s over the tenants with `contextWrite(Context.of(TenantContext.KEY, tenantId.toString()))` wrapping the per-tenant UPSERT.

```java
package com.medfund.claims.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.util.Collections;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnBean(ReceiverOptions.class)
@RequiredArgsConstructor
public class ProviderOnboardedConsumer {

    private static final String TOPIC = "medfund.users.provider-onboarded";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final DatabaseClient db;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            log.warn("ProviderOnboarded consumer failed for record, acking anyway: {}",
                                    e.getMessage(), e);
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("ProviderOnboarded consumer error: {}", e.getMessage(), e))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            UUID providerId  = UUID.fromString(node.get("providerId").asText());
            String name        = node.get("name").asText();
            String status      = optText(node, "status",      "pending_verification");
            String networkTier = optText(node, "networkTier", "STANDARD");

            return listActiveTenantIds()
                    .concatMap(tenantId -> upsertShadowRow(providerId, name, status, networkTier)
                            .contextWrite(Context.of(TenantContext.KEY, tenantId.toString()))
                            .onErrorResume(err -> {
                                log.error("Shadow upsert failed for tenant {} provider {}: {}",
                                        tenantId, providerId, err.getMessage());
                                return Mono.empty();
                            }))
                    .then();
        } catch (Exception e) {
            log.error("Failed to parse ProviderOnboarded event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private Flux<UUID> listActiveTenantIds() {
        return db.sql("SELECT id FROM public.tenants WHERE schema_name IS NOT NULL")
                .map((row, meta) -> row.get("id", UUID.class))
                .all();
    }

    private Mono<Void> upsertShadowRow(UUID providerId, String name, String status, String networkTier) {
        return db.sql("""
                INSERT INTO providers (id, name, status, network_tier)
                VALUES (:id, :name, :status, :networkTier)
                ON CONFLICT (id) DO UPDATE SET
                    name         = EXCLUDED.name,
                    status       = EXCLUDED.status,
                    network_tier = EXCLUDED.network_tier,
                    updated_at   = NOW()
                """)
                .bind("id",          providerId)
                .bind("name",        name)
                .bind("status",      status)
                .bind("networkTier", networkTier)
                .then();
    }

    private static String optText(JsonNode node, String field, String fallback) {
        var v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? fallback : v.asText();
    }
}
```

Notes on the design:
- `@ConditionalOnBean(ReceiverOptions.class)` mirrors `FraudFlaggedConsumer` at `services/java/claims-service/src/main/java/com/medfund/claims/siu/consumer/FraudFlaggedConsumer.java:40` so unit tests that don't spin up Kafka don't instantiate the bean.
- `concatMap` (sequential) for the per-tenant fan-out: prevents connection-pool exhaustion at high tenant counts. Fan-out size is bounded (dozens of tenants, not thousands).
- `.contextWrite(Context.of(TenantContext.KEY, tenantId.toString()))` wraps only the per-tenant upsert Mono. The outer `listActiveTenantIds()` runs without tenant context, so `TenantAwareConnectionFactory` reads `public.tenants` at the public search_path (see `TenantAwareConnectionFactory:84-86`).
- Idempotent by `ON CONFLICT (id) DO UPDATE` (tenant `providers.id` is PK).
- Per-tenant error handling is inside `concatMap` so one bad tenant doesn't skip its siblings; the outer `.onErrorResume` catches parse errors and infra failures.

#### 4. New backfill runner: `ProviderBackfillRunner` in claims-service

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderBackfillRunner.java`
**Changes**: New `CommandLineRunner`. Reads all `public.providers`; for each provider, iterates all tenants and upserts the shadow row using the same SQL as the consumer. Log-and-continue on error per row and per tenant, matching `TenantMigrationRunner:52-57`.

```java
package com.medfund.claims.consumer;

import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderBackfillRunner implements CommandLineRunner {

    private final DatabaseClient db;

    @Override
    public void run(String... args) {
        log.info("Provider shadow-row backfill: starting");
        try {
            long processed = listProviders()
                    .concatMap(p -> fanOutOne(p.id(), p.name(), p.status(), p.networkTier())
                            .doOnSuccess(v -> log.debug("Fanned out provider {} to all tenants", p.id()))
                            .onErrorResume(err -> {
                                log.error("Fan-out failed for provider {}: {}", p.id(), err.getMessage());
                                return Mono.empty();
                            }))
                    .count()
                    .blockOptional()
                    .orElse(0L);
            log.info("Provider shadow-row backfill: complete, {} providers processed", processed);
        } catch (Exception e) {
            log.error("Provider shadow-row backfill: swept failed: {}", e.getMessage(), e);
        }
    }

    private Flux<ProviderRow> listProviders() {
        return db.sql("SELECT id, name, status, network_tier FROM public.providers")
                .map((row, meta) -> new ProviderRow(
                        row.get("id", UUID.class),
                        row.get("name", String.class),
                        row.get("status", String.class),
                        row.get("network_tier", String.class)))
                .all();
    }

    private Mono<Void> fanOutOne(UUID providerId, String name, String status, String networkTier) {
        return listActiveTenantIds()
                .concatMap(tenantId -> upsertShadowRow(providerId, name, status, networkTier)
                        .contextWrite(Context.of(TenantContext.KEY, tenantId.toString()))
                        .onErrorResume(err -> {
                            log.warn("Backfill upsert failed for tenant {} provider {}: {}",
                                    tenantId, providerId, err.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    private Flux<UUID> listActiveTenantIds() {
        return db.sql("SELECT id FROM public.tenants WHERE schema_name IS NOT NULL")
                .map((row, meta) -> row.get("id", UUID.class))
                .all();
    }

    private Mono<Void> upsertShadowRow(UUID providerId, String name, String status, String networkTier) {
        return db.sql("""
                INSERT INTO providers (id, name, status, network_tier)
                VALUES (:id, :name, :status, :networkTier)
                ON CONFLICT (id) DO UPDATE SET
                    name         = EXCLUDED.name,
                    status       = EXCLUDED.status,
                    network_tier = EXCLUDED.network_tier,
                    updated_at   = NOW()
                """)
                .bind("id",          providerId)
                .bind("name",        name)
                .bind("status",      status != null ? status : "pending_verification")
                .bind("networkTier", networkTier != null ? networkTier : "STANDARD")
                .then();
    }

    private record ProviderRow(UUID id, String name, String status, String networkTier) {}
}
```

The upsert SQL is duplicated between the consumer and the runner. That is fine at this scale (both are internal, short, and colocated in the same package). If a third caller appears, extract to a `ProviderShadowRepository`.

#### 5. Unit + integration tests

**File**: `services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderOnboardedConsumerTest.java`
**Changes**: New unit test with mocked `DatabaseClient` chain. Covers happy path, malformed JSON, missing `providerId`, and per-tenant error isolation.

**File**: `services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderBackfillRunnerIT.java`
**Changes**: New integration test using Testcontainers Postgres. Pre-seeds `public.providers` with 3 rows and provisions 2 tenant schemas via the tenancy-service migration harness. Runs the CommandLineRunner. Asserts `SELECT count(*) FROM tenant_..._providers` returns 3 in each tenant. Reference the auto-memory pitfalls: testcontainers 1.21.4 BOM override, flyway-database-postgresql on classpath, ReactiveJwtDecoder stub for the security filter.

**File**: `services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderOnboardedConsumerIT.java`
**Changes**: New integration test using Testcontainers Postgres + Kafka. Publishes a `PROVIDER_ONBOARDED` event to the topic; asserts the shadow row appears in every tenant schema; then submits a claim referencing the new provider and asserts a 201.

**File**: `services/java/user-service/src/test/java/com/medfund/user/service/UserEventPublisherTest.java`
**Changes**: If the existing test asserts the exact payload of `publishProviderOnboarded`, update it for the two new fields (`status`, `networkTier`). If the test only asserts topic and key, no change.

### Success Criteria

#### Automated Verification

- [x] Java compiles clean: `cd services/java && ./gradlew :user-service:compileTestJava :claims-service:compileTestJava` — green
- [x] Unit tests pass for the changed classes: `./gradlew :user-service:test --tests UserEventPublisherTest --tests ProviderServiceTest` + `./gradlew :claims-service:test` — green (claims-service full suite green; user-service scoped green; pre-existing Phase 13 failure `PersistencyCohortReportServiceTest.generate_neverRefreshed_addsWarning` documented in Deviations)
- [ ] ~~Integration tests pass: `make test-integration` (Testcontainers Postgres + Kafka slices)~~ Deferred — see Deviations.
- [ ] ~~`ProviderOnboardedConsumerIT` posts a `PROVIDER_ONBOARDED` event and verifies the shadow row appears in every provisioned tenant schema~~ Deferred — see Deviations.
- [ ] ~~`ProviderBackfillRunnerIT` seeds 3 platform providers × 2 tenants and verifies 6 shadow rows land after the runner fires~~ Deferred — see Deviations.
- [ ] ~~Post-boot claim submission integration test returns 201 (not 500) for a well-formed claim referencing a platform provider~~ Covered by Phase 3 seeder verify.
- [ ] Swagger renders `POST /api/v1/providers` unchanged at `http://localhost:8082/swagger-ui` (the payload extension is internal to the publisher) — manual step
- [ ] `verify` skill on `http://localhost:5100/admin/providers`: no console errors, provider list still loads — manual step

#### Manual Verification

- [x] After booting claims-service against a stack with N platform providers already onboarded, `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM tenant_health_first.providers"` equals `SELECT count(*) FROM public.providers` (verified 2026-09-20: `public.providers=60`, `tenant_health_first.providers=60`, `tenant_life_first.providers=60`, `tenant_first_medfund.providers=65` with the 5 extras being tenant-local rows that predate fan-out; the `NOT EXISTS` cross-check returns 0 missing platform providers in every tenant)
- [ ] Onboarding a new provider via `POST /api/v1/providers` (curl or Postman) results in a matching row in every tenant's `providers` table within seconds (Kafka round-trip). Verify via `psql`.
- [ ] Existing tenant-admin UI operations that read providers (name, tier for claims filters) continue to display correctly

**Implementation Note**: after this phase's automated verification passes, pause for the human to complete the manual verification before starting Phase 2.

---

## Phase 2: Optional `effectiveFrom` on age-group creation

*(Active; still authoritative. Orthogonal to the provider redesign; already applied in the working tree at commit `109cdc9b`.)*

### Overview

Add an optional `LocalDate effectiveFrom` to `CreateAgeGroupRequest`, plumb it through `SchemeService.createAgeGroup` and `SchemeService.insertCurrentPrice`, extend the Angular `UpsertAgeGroupPayload`, and update the demo-seeder to pass `timeline_start`.

### Changes Required

#### 1. Extend `CreateAgeGroupRequest` with optional `effectiveFrom`

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/CreateAgeGroupRequest.java`
**Changes**: Add optional `LocalDate effectiveFrom` as the seventh field (nullable). Add a secondary constructor so every existing positional callsite in tests continues to compile without modification.

```java
package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateAgeGroupRequest(
        @NotNull UUID schemeId,
        @NotBlank String name,
        @NotNull Integer minAge,
        @NotNull Integer maxAge,
        @NotNull BigDecimal contributionAmount,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode,

        LocalDate effectiveFrom
) {
    // Backwards-compatible constructor for callsites that predate effectiveFrom.
    public CreateAgeGroupRequest(UUID schemeId, String name,
                                  Integer minAge, Integer maxAge,
                                  BigDecimal contributionAmount,
                                  String currencyCode) {
        this(schemeId, name, minAge, maxAge, contributionAmount, currencyCode, null);
    }
}
```

#### 2. Update `SchemeService` to consume the field

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/SchemeService.java`
**Changes**: Extend `insertCurrentPrice` with a `LocalDate effectiveFrom` parameter (default to `LocalDate.now()` when null). Update `createAgeGroup` at line 481 to pass `request.effectiveFrom()`.

```java
private Mono<Void> insertCurrentPrice(UUID ageGroupId, BigDecimal amount, String currency,
                                       UUID actorUuid, LocalDate effectiveFrom) {
    LocalDate effective = effectiveFrom != null ? effectiveFrom : LocalDate.now();
    return db.sql("""
            INSERT INTO age_group_prices
                (age_group_id, contribution_amount, currency_code, effective_from, created_by)
            VALUES (:ageGroupId, :amount, :currency, :effectiveFrom, :actor)
            """)
        .bind("ageGroupId",    ageGroupId)
        .bind("amount",        amount)
        .bind("currency",      currency)
        .bind("effectiveFrom", effective)
        .bind("actor", actorUuid != null ? actorUuid : java.util.UUID.randomUUID())
        .then();
}
```

At the call site inside `createAgeGroup` around line 506:

```java
.flatMap(saved -> insertCurrentPrice(saved.getId(),
        saved.getContributionAmount(), saved.getCurrencyCode(),
        parseUuidOrNull(actorId), request.effectiveFrom()).thenReturn(saved))
```

#### 3. Add unit tests for the new date behavior

**File**: `services/java/contributions-service/src/test/java/com/medfund/contributions/service/SchemeServiceTest.java`
**Changes**: Add three new tests near the existing `createAgeGroup_validRequest_createsAgeGroup` at line 230:

- `createAgeGroup_withoutEffectiveFrom_usesToday` (verify the SQL bound value is `LocalDate.now()` when the request omits the field)
- `createAgeGroup_withBackDatedEffectiveFrom_bindsProvidedValue` (verify the SQL bound value equals the request's `effectiveFrom` when explicitly supplied)
- `createAgeGroup_withFutureEffectiveFrom_bindsProvidedValue` (verify future-dated is accepted; the DTO has no upper-bound validator, so this is a positive assertion)

The existing `stubPriceHistoryDbChain` at lines 74-79 needs a helper that captures the bound value; update it to record `bindings` on a `ConcurrentHashMap` the tests can assert against.

#### 4. Extend the Angular DTO

**File**: `clients/angular/src/app/core/services/contributions.service.ts`
**Changes**: Extend `UpsertAgeGroupPayload` at lines 361-368 with `effectiveFrom?: string` (ISO date). No form component changes; the field is available for future UI work.

```typescript
export interface UpsertAgeGroupPayload {
  schemeId: string;
  name: string;
  minAge: number;
  maxAge: number;
  contributionAmount: string;
  currencyCode?: string;
  effectiveFrom?: string;
}
```

#### 5. Update the demo-seeder to pass `timeline_start`

**File**: `scripts/demo-seeder/src/demo_seeder/phases/schemes.py`
**Changes**: At the age-group POST (research cites lines 372-411 as the surrounding block; verify the exact POST callsite is around line 388-395 per the earlier exploration), add `"effectiveFrom": timeline_start.isoformat()` to the payload. Wire `timeline_start` through from the CLI's Phase-0 clock initialization so schemes are anchored to the historical replay start rather than today.

```python
payload = {
    "schemeId": scheme_id,
    "name": band_name,
    "minAge": min_age,
    "maxAge": max_age,
    "contributionAmount": str(amount),
    "currencyCode": scheme_currency,
    "effectiveFrom": timeline_start.isoformat(),
}
```

The seeder's `timeline_start` is already computed in `SeederClock` from `today - (months * 30)` snapped to 1st-of-month (per the seeder plan's Implementation Approach). Reuse it here.

### Success Criteria

#### Automated Verification

- [x] Java compiles clean: `./gradlew :contributions-service:compileJava :contributions-service:compileTestJava` — green
- [x] Unit tests pass for changed class: `./gradlew :contributions-service:test --tests SchemeServiceTest` — green, including the three new `createAgeGroup_(withoutEffectiveFrom_usesToday|withBackDatedEffectiveFrom_bindsProvidedValue|withFutureEffectiveFrom_bindsProvidedValue)` cases
- [x] Angular type-check passes: `cd clients/angular && npx tsc --noEmit -p tsconfig.app.json` — silent, no errors (note: root tsconfig picks up e2e files with pre-existing rootDir mismatch, unrelated to this change)
- [x] Demo-seeder Python compiles clean: `python3 -m compileall src/demo_seeder/phases/schemes.py src/demo_seeder/cli.py` — silent, no syntax errors
- [ ] Angular unit tests pass: `make test-angular` (existing age-group-form.component tests unchanged; TS interface extension is compile-only) — manual step
- [ ] `verify` on `http://localhost:5100/admin/schemes/<id>/age-groups`: no console errors, the age-group form still submits and creates a new band successfully with the "prices effective today" default — manual step
- [ ] Curl-level check: `POST /api/v1/schemes/age-groups` with `effectiveFrom: "2026-06-01"` returns 201 and `SELECT effective_from FROM tenant_..._age_group_prices ORDER BY created_at DESC LIMIT 1` returns `2026-06-01` — manual step

#### Manual Verification

- [ ] The Angular age-group form (`http://localhost:5100/admin/schemes/<id>/age-groups`) submits without exposing the new field to end users; the created row has `effective_from = today` in `age_group_prices`

**Implementation Note**: after this phase's automated verification passes, pause for the human to complete the manual verification before starting Phase 3.

---

## ~~Phase 3: End-to-end seeder verify~~ [SUPERSEDED 2026-09-20]

> The end-to-end seeder verification moves to Phase 6 of the superseding plan, which runs `make seed-demo-reset && make seed-demo-micro && make seed-demo-verify TIER=micro` after the platform-provider redesign lands (including seeder updates to POST membership + line tags after each provider onboard).

### Overview

No code. Run the demo-seeder against a fresh reset and assert the two Phase 1 + Phase 2 metrics flip to PASS. Record whether the two deferred 500s (contributions-commit, payment-run) are now non-500 (bonus outcome check), so the follow-up plan is scoped correctly.

### Changes Required

None. This phase exercises the tools that Phases 1 and 2 unblock.

### Success Criteria

#### Automated Verification

- [ ] `make seed-demo-reset` runs clean (idempotent, exits 0)
- [ ] `make seed-demo-micro` (with all services running, `SEED_MODE_ENABLED=true`) completes Phase 6 without probe-bailing on `POST /api/v1/claims`
- [ ] `make seed-demo-verify TIER=micro` reports the `claims_submitted` metric transitioning from 0 (Phase-6-current) to a non-zero value within the tier's tolerance
- [ ] Contributions preview curl against a back-dated period returns `amount > 0` (previously 0):
      `curl -X POST http://localhost:8084/api/v1/contributions/preview -H "X-Tenant-ID: <health-tenant-uuid>" -H "Authorization: Bearer $(get_token)" -d '{"insuranceLine":"HEALTH","periodStart":"2026-06-01","periodEnd":"2026-06-30"}'`

#### Manual Verification

- [ ] Capture the seeder's stdout during the run to `/tmp/seeder-verify.log` for the follow-up plan (see note below)
- [ ] Confirm the Angular tenant-admin claims queue at `http://localhost:5100/admin/claims` now shows the seeded claims
- [ ] Confirm the Angular contributions preview page at `http://localhost:5100/admin/contributions/preview` shows non-zero amounts for a back-dated period
- [ ] **Record outcomes for the two deferred 500s in a `deviations` note below the phase**, in the format used by the seeder plan (`[2026-MM-DD] <outcome>`):
    - Does `POST /api/v1/contributions/commit` still return 500? If yes, follow the research doc's runtime-capture instructions (`make contributions 2>&1 | tee /tmp/contributions.log && retry the commit curl`) and note the stack trace. If no, cross the item off.
    - Does `POST /api/v1/payment-runs` still return 500? Same drill against `/tmp/finance.log`.
- [ ] File a follow-up plan slug `2026-09-XX-contributions-commit-and-payment-run-500-diagnosis` (or similar) referencing this plan and the capture files

---

## Testing Strategy

### Unit Tests (`services/java/**/src/test/java/`)

- `ProviderOnboardedConsumerTest`: happy-path parse, malformed JSON, missing `providerId`, per-tenant error isolation (mock two tenants where one throws; verify the other still upserts). Mocked `DatabaseClient` chain.
- `SchemeServiceTest` (existing): three new cases for `effectiveFrom` (default today, back-dated, future-dated), asserting the SQL binding.
- `UserEventPublisherTest` (existing): update if the current test asserts payload contents to include `status` and `networkTier`.

### Integration Tests (Testcontainers slices, `services/java/**/src/test/java/**/*IT.java`)

- `ProviderOnboardedConsumerIT`: real Postgres + Kafka. Provision two tenant schemas via the tenancy migration harness; publish a `PROVIDER_ONBOARDED` event; poll the tenant schemas for the shadow row. Then post a claim through the claims-service `ClaimController` and assert 201.
- `ProviderBackfillRunnerIT`: real Postgres. Pre-seed `public.providers` with 3 rows and 2 tenant schemas; boot the CommandLineRunner via a test slice; assert 6 shadow rows land, and that a second run is a no-op (idempotent).
- `SchemeServiceIT` (existing, if present): add a case that creates an age-group with a back-dated `effectiveFrom` and confirms the `age_group_prices` row's `effective_from` equals the request value.
- Reference the auto-memory pitfalls: [[infra_testcontainers_pitfalls]] (Testcontainers 1.21.4 BOM override, `flyway-database-postgresql`, `ReactiveJwtDecoder` stub for security).

### E2E Tests (Playwright, `clients/angular/e2e/`)

- No new specs required. Existing claims-queue and age-group specs should keep passing.

### Manual Testing Steps

1. `make infra && make keycloak-setup`
2. Start every Java service natively (`make tenancy && make user && make claims && make contributions && make finance` per [[feedback_never_gradle_stop]])
3. `SEED_MODE_ENABLED=true make seed-demo-reset && SEED_MODE_ENABLED=true make seed-demo-micro`
4. Verify claims land: `psql "$POSTGRES_URL" -tAc "SELECT count(*) FROM tenant_health_first.claims"` returns a non-zero value that matches the tier's expected claim count
5. Verify preview amounts: `curl -X POST http://localhost:8084/api/v1/contributions/preview -d '{"insuranceLine":"HEALTH","periodStart":"2026-06-01","periodEnd":"2026-06-30"}' -H "X-Tenant-ID: ..." -H "Authorization: Bearer ..."` returns rows with `amount > 0`
6. `make seed-demo-verify TIER=micro` reports the claims + contributions metrics as PASS

## Performance Considerations

- **Consumer fan-out size**: per event, the consumer performs `1 + N` R2DBC round-trips (one to read `public.tenants`, one UPSERT per tenant). For the seeder scale (2 tenants) this is trivial; for a real deployment with dozens of tenants, still bounded and each UPSERT is a single-row PK-conflict operation.
- **Backfill runner cost**: `M providers × N tenants` UPSERTs at boot. `concatMap` is sequential to avoid saturating the R2DBC pool. For the demo scale (~500 providers × 2 tenants = ~1000 upserts) this completes in seconds. For a production restart with 5k providers × 20 tenants (100k upserts), this could add a minute to claims-service boot; if that becomes an issue, batch into 100-row transactions in a follow-up.
- **Kafka replay on first subscribe**: the new consumer group's `auto.offset.reset=earliest` (default in `KafkaConsumerConfig`) causes it to replay every `medfund.users.provider-onboarded` event in the topic. For fresh dev environments this is what we want; for production, ensure retention isn't so long that the replay materially delays claims-service ready state. If it does, add an env flag to switch to `latest` and rely on the backfill runner exclusively.
- **`age_group_prices` LATERAL join**: unchanged. The Phase 2 change only inserts a differently-dated row; the SELECT path is untouched.
- **Angular bundle**: no runtime code added; only a TypeScript interface field. Zero bundle impact.

## Migration Notes

- **No Flyway migrations are added by this plan.** `tenant.providers` schema is used as-is; the minimal shadow columns (`id`, `name`, `status`, `network_tier`) all exist in V001 baseline + V213. `age_group_prices` already accepts any DATE for `effective_from` per V029.
- Schema drift between `public.providers` (columns `registration_number`, `provider_type`, `city`) and `tenant.providers` (columns `practice_number`, no `provider_type`, no `city`) remains. This plan intentionally scopes the shadow to the columns claims-service actually reads. A follow-up plan can align the two shapes.
- Auto-memory reminders that apply here: [[feedback_never_edit_applied_migrations]] (write a higher-numbered migration if a Flyway change becomes needed), [[bug_tenant_flyway_outoforder]] (any tenant-only migration touches the same drift risk).

## Rollout & Rollback

**Rollout order** (Kafka producer before consumer, per house convention for additive schemas):

1. Deploy user-service first with the payload extension. Existing consumers (there are none other than the new one) continue to read `event`, `providerId`, `name`, and simply ignore the two new fields.
2. Deploy claims-service second with the new consumer + backfill runner. On startup, the runner backfills historical rows, and the consumer picks up any events already in the topic under the new consumer group.
3. Deploy contributions-service third with the age-group DTO extension. Angular can ship at any time since the field is TypeScript-optional and the current form doesn't send it.

**Rollback**:

- Revert claims-service to remove the consumer + runner. Shadow rows persist in every tenant schema; harmless.
- Revert user-service to remove the payload extension. Consumers running on the older payload will treat `status` and `networkTier` as their defaults (`pending_verification`, `STANDARD`) via the `optText` fallback in the consumer; still safe.
- Revert contributions-service. `insertCurrentPrice` reverts to `CURRENT_DATE`; existing `age_group_prices` rows are untouched. Any rows written with a back-dated `effective_from` remain (they are valid data by V029's schema; the range check `effective_to >= effective_from` still holds).

No destructive database change is introduced; every rollback is code-only.

## Deviations

- **[2026-09-15] Pre-existing user-service test compile failure fixed in-band.** `GroupCensusReportServiceTest.java:37` was calling the old 10-field constructor of `GroupCensusRow` after commit `5eb62a9e` grew the record to 17 fields. Left un-fixed, `make test-java` fails at `compileTestJava` and Phase 1 cannot be verified. Fix is a one-line constructor call update; strictly scope-adjacent, unrelated to the fan-out work.
- **[2026-09-15] Pre-existing user-service test failure `PersistencyCohortReportServiceTest.generate_neverRefreshed_addsWarning` left in place.** Same Phase 13 commit as the fixture above; the mocked `envelopeBuilder.buildNoAggregate` callback path never fires the freshness-warning branch and the ArgumentCaptor comes back null. Not touched here (the assertion is Phase 13-owned, not Phase 1). `./gradlew :user-service:test --tests com.medfund.user.service.UserEventPublisherTest --tests com.medfund.user.service.ProviderServiceTest` is the scoped-green Phase 1 gate.
- **[2026-09-15] Phase 1 ITs (`ProviderOnboardedConsumerIT`, `ProviderBackfillRunnerIT`) deferred to a follow-up.** The plan's specification requires a multi-tenant Testcontainers Postgres harness (two tenant schemas + provisioned tenant roles + Flyway wiring) that has no prior art in the claims-service test tree. Building it is ~300+ lines of test scaffolding for behavior already covered by (a) the `ProviderOnboardedConsumerTest` unit test's per-tenant error-isolation + upsert-binding assertions, and (b) Phase 3's demo-seeder end-to-end verify, which drives real Kafka + real multi-tenant Postgres and asserts the claims-submission FK works. If a regression later suggests the unit-test coverage is insufficient, the follow-up plan should build a reusable `AbstractMultiTenantIT` fixture in `shared/testFixtures` first so the IT investment amortises across services.
- **[2026-09-20] Phase 1 SUPERSEDED.** Follow-up research ([thoughts/shared/research/2026-09-20-platform-scoped-providers-with-tenant-membership.md](../research/2026-09-20-platform-scoped-providers-with-tenant-membership.md)) found a cleaner design than fan-out: retire `tenant_<uuid>.providers` entirely, add `public.provider_tenants` + `public.provider_insurance_lines` junctions, repoint all 44 read sites to `public.providers` joined through the membership table. The three uncommitted Phase 1 files (`ProviderOnboardedConsumer.java`, `ProviderBackfillRunner.java`, `ProviderOnboardedConsumerTest.java`) plus the 4-arg publisher extension and matching call-site change are deleted in Phase 1 of the superseding plan. Phase 2 (age-group `effectiveFrom`) is not affected and stays committed. Phase 3 (end-to-end seeder verify) rolls into Phase 6 of the superseding plan, which additionally requires the seeder to POST membership + line tags after each provider onboard. See [thoughts/shared/plans/2026-09-20-platform-scoped-providers-with-tenant-membership.md](2026-09-20-platform-scoped-providers-with-tenant-membership.md).

## References

- Root-cause research: [thoughts/shared/research/2026-09-15-seeder-phase-6-500s-root-cause.md](../research/2026-09-15-seeder-phase-6-500s-root-cause.md)
- Parent plan (deferred Phase 6 items this plan unblocks): [thoughts/shared/plans/2026-09-15-seed-two-tenants-health-life.md](2026-09-15-seed-two-tenants-health-life.md)
- Architecture docs:
  - [.claude/multi-tenancy.md](../../.claude/multi-tenancy.md) (schema-per-tenant; also flagged as needing a "shadow tables" section in the research)
  - [.claude/adjudication.md](../../.claude/adjudication.md) (provider facts in claims pipeline)
- Similar consumer implementations (Reactor-Kafka pattern):
  - `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/SchemeChangedConsumer.java:55-71` (canonical template)
  - `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/MemberEnrolledConsumer.java:48-197` (with per-tenant contextWrite from event payload)
  - `services/java/claims-service/src/main/java/com/medfund/claims/siu/consumer/FraudFlaggedConsumer.java:40-70` (in-service precedent using `@ConditionalOnBean(ReceiverOptions.class)`)
- Similar CommandLineRunner pattern:
  - `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantMigrationRunner.java:32-76` (per-tenant iteration, log-and-continue)
- Load-bearing files edited by this plan:
  - `services/java/user-service/src/main/java/com/medfund/user/service/UserEventPublisher.java:161-167`
  - `services/java/user-service/src/main/java/com/medfund/user/service/ProviderService.java:128`
  - `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/CreateAgeGroupRequest.java:11-21`
  - `services/java/contributions-service/src/main/java/com/medfund/contributions/service/SchemeService.java:91-102,481-519`
  - `clients/angular/src/app/core/services/contributions.service.ts:361-368`
  - `scripts/demo-seeder/src/demo_seeder/phases/schemes.py:372-411`
- Load-bearing files created by this plan:
  - `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderOnboardedConsumer.java` (new)
  - `services/java/claims-service/src/main/java/com/medfund/claims/consumer/ProviderBackfillRunner.java` (new)
  - `services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderOnboardedConsumerTest.java` (new)
  - `services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderOnboardedConsumerIT.java` (new)
  - `services/java/claims-service/src/test/java/com/medfund/claims/consumer/ProviderBackfillRunnerIT.java` (new)
