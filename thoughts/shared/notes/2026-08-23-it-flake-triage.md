---
date: 2026-08-23
branch: rename-adjustments-to-notes
phase: 12 §0
owner: methuseli
---

# Phase 12 §0 — IT flake triage log

This is the working log for the pre-existing Java IT flakes carried into
Phase 12 (see [plan](../plans/2026-08-23-upr-earning-schedule-and-premium-register.md#phase-1-0--testcontainers-harness-stabilisation)).

## Baseline pass — 2026-08-23

**Command:** `cd services/java && ./gradlew :finance-service:test --tests '*IT'`

**Baseline outcome:** OOM.

The test worker was launched with Gradle's default `-Xmx512m`. Under the
finance-service IT load — Spring Boot 3.3 context + Testcontainers
Postgres + Kafka producer/consumer buffers + Jacoco agent + Drools
KieContainer classloader — the heap saturated. Kafka
`kafka-coordinator-heartbeat-thread | finance-service` and Reactor Netty
worker threads started throwing `OutOfMemoryError` in the middle of the
run; the JVM never recovered and the Gradle worker exited with code 144
(SIGKILL after ~15 minutes of thrashing).

### Root-cause fix

Applied on 2026-08-23 in `services/java/build.gradle.kts`:

```kotlin
tasks.test {
    maxHeapSize = "1536m"
    ...
}
```

Rationale for 1536 MiB: Testcontainers Kafka client's default 32 MiB per
producer × several producers per Spring context, plus a large Drools
KieContainer classloader in tenant-rules-engine, plus every consumer's
receiver buffer, historically pushes a single-context IT above 800 MB.
1.5 GiB gives headroom for the Reactor Netty allocator's occasional
2x spikes without dipping into swap.

## Second pass — after heap fix (2026-08-23, 09:07)

**Command:** `./gradlew :finance-service:test --tests '*IT'`

**Outcome:** 74 tests completed, **8 failed**, no OOM. Duration 2m 46s.

Root cause of the 8 failures: Testcontainers Postgres launched with
default `max_connections=100`. Under the full IT suite, every
SpringBootTest class stacks R2DBC pool (max-size=20 after the initial
test-profile edit) + Flyway JDBC connections + Redis clients + occasional
bare JDBC probes against the same shared container. 100 evaporates
partway through the run; late-loading contexts fail with
`FATAL: sorry, too many clients already` on Flyway's connection acquire.

### Root-cause fix

Applied on 2026-08-23:

1. Raised Postgres `max_connections=300` (+ modest `shared_buffers=64MB`)
   in `AbstractIntegrationTest`, `AbstractPostgresIntegrationTest`,
   `AbstractDedicatedPostgresIntegrationTest`.
2. Tightened per-service test-profile pool `max-size` from 20 → 10 to
   leave more headroom.

## Third pass — after Postgres ceiling fix (2026-08-23, 09:10)

**Command:** `./gradlew :finance-service:test --tests '*IT'`

**Outcome:** 74 tests completed, **2 failed**, no OOM. Duration 2m 22s.

Down from 8 to 2. The remaining failures are not connection or pool
issues — they reproduce when run in isolation, so they are real
correctness bugs, not test-isolation flakes.

| Test | Root cause | Class |
|---|---|---|
| `CommissionCalcIT.reprocessingSameContribution_isIdempotent()` | `PessimisticLockingFailureException: R2DBC commit; The database returned ROLLBACK`. Reproduces in isolation. Idempotency guard on commission recompute is fumbling a UNIQUE constraint. | Bug — file follow-up |
| `CommissionClawbackIT.memberLapse_withinWindow_writesClawbackAndReversedTxn()` | `AssertionError: Expecting actual not to be null`. Reproduces in isolation — asserted row not written. Clawback service not persisting the reversal record. | Bug — file follow-up |

Both are Phase 11-shape correctness issues in the commission surface
that landed 55c3689/e6907ec. They are **not** blocking Phase 12 §A/§B/§C
(which is a new premium module orthogonal to commission calc); the plan's
§0 close-out policy excludes real correctness bugs from the harness
gate. They should be filed as [flake-triage] follow-up tickets.

### Classification bins

1. **Bug** — real correctness bug. File follow-up ticket tagged
   `[flake-triage]`. Exclude from §0 close-out (don't disable to make
   the suite green — track separately).
2. **Pool pressure** — `Failed to obtain R2DBC Connection` or
   `sorry, too many clients`. Fixed by the Phase 1 pool ceilings
   (`application.yml` + `application-test.yml`). Confirm the fix by
   re-running.
3. **Test isolation drift** — data-cleanup issue between tests. Wrap in
   a `@DirtiesContext(AFTER_EACH_TEST_METHOD)` or better per-test
   cleanup (`@Sql(scripts = "cleanup.sql", executionPhase = BEFORE_TEST_METHOD)`
   or an explicit `TRUNCATE ... CASCADE` in `@BeforeEach`).
4. **Non-fixable flake** — usually a race in an async assertion.
   Guard with `@Disabled("flake-triage-2026-08-23: see thoughts/shared/notes/2026-08-23-it-flake-triage.md")`
   plus a linked ticket.

## Actions taken during §0

- **`tasks.test.maxHeapSize = 1536m`** (root subprojects block) — fixes
  finance-service IT baseline OOM.
- **R2DBC pool ceilings** — added to tenancy, user, finance, claims
  `application.yml` (contributions kept its own tuned block with
  `validation-depth=REMOTE`). Test-profile `application-test.yml`
  overrides added for all five services (max-size=20, max-acquire-time=5s).
- **`R2dbcPoolHealthIndicator`** — shared health indicator reports DOWN
  when pool utilisation > 90% and idle=0. Wired via
  `@ConditionalOnBean(ConnectionFactory.class)` so services opting out
  of pooling don't get the indicator.
- **`AbstractUserServiceIT`** — marker base class extending
  `AbstractPostgresIntegrationTest` (not the combined Postgres+Kafka
  `AbstractIntegrationTest`, per the shared base's own javadoc — no
  point spinning up unused Kafka per class). Migrated
  `GroupNumberServiceIT` onto it. `GroupServiceCreateIT` stayed on
  `AbstractDedicatedPostgresIntegrationTest` because its Flyway
  migration path collides with `GroupNumberServiceIT`'s if they share a
  container.

## Reference

- Plan Phase 1 §0: [../plans/2026-08-23-upr-earning-schedule-and-premium-register.md#phase-1-0--testcontainers-harness-stabilisation](../plans/2026-08-23-upr-earning-schedule-and-premium-register.md)
- Parent plan grill U15: rooted the Testcontainers debt claim
- Memory `infra_testcontainers_pitfalls`: BOM override + flyway-database-postgresql + ReactiveJwtDecoder stub — all still current
