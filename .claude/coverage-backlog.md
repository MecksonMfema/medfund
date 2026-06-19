# Coverage backlog — toward 100% line coverage

Stabilised baseline as of 2026-06-19. All suites green. This file lists the residual gaps per service, biggest first within each module, so the next session can systematically chip at them.

## ⚠️ Coverage gate is now ENFORCED at 70% line coverage per service

A PR that touches any of the services listed in red below will fail CI until that service climbs to 70%. The gates live in:

- **Java**: `services/java/build.gradle.kts` — `jacocoTestCoverageVerification` wired into `check`
- **Go**: `.github/workflows/go.yml` — per-service `go tool cover -func` parsing with hard exit
- **Python**: `services/python/ai-service/pyproject.toml` — `--cov-fail-under=70` in `addopts`
- **Elixir**: `services/elixir/mix.exs` (Mix summary) + each app's `coveralls.json` (ExCoveralls)
- **Angular**: `clients/angular/karma.conf.js` — `check.global` at 70/60/70/70

Services currently below the bar (will fail on first CI run after this change):
- Java: user-service (32.5%), claims-service (47.5%), finance-service (61.6%), tenancy-service (43.4%), contributions-service (33.6%), rules-engine (47.4%), shared (30.4%)
- Go: gateway (20.5%), audit-service (14.8% default-mode), notification-service (64.8%)
- Python: ai-service (69.8% — barely under)
- Elixir: live_dashboard (53.4%), chat_service (20.4%)
- Angular: gate raised from 35→70; current measured coverage on touched files exceeds 35% but full-suite is unknown

Services already meeting the bar: file-service (76.2%), payment-gateway (79.8%).


## Java (JaCoCo line coverage)

| Module | Baseline | Largest unmissed-line packages |
|---|---|---|
| **finance-service** | 61.6% | controller 12% (-204), service 76% (-175), dto 63% (-46), exception 15% (-34) |
| **claims-service** | 47.5% | service 51% (-445), client 2% (-122), entity 60% (-112), controller 21% (-93), exception 24% (-44), dto 72% (-38) |
| **rules-engine** | 47.4% | fact 16% (-227), service 32% (-114), compiler 60% (-91), controller 0% (-47), exception 0% (-30), consumer 0% (-29), model 78% (-24), config 0% (-21), engine 79% (-12), template 81% (-10), dto 0% (-6), root 0% (-3) |
| **tenancy-service** | 43.4% | service 48% (-317), controller 19% (-73), repository 0% (-50), exception 29% (-37), dto 61% (-31), config 37% (-19), util 38% (-5) |
| **contributions-service** | 33.6% | service 28% (-1138), dto 43% (-122), repository 0% (-109), entity 60% (-107), controller 28% (-97), exception 15% (-45), consumer 79% (-7) |
| **user-service** | 32.5% | service 37% (-889), controller 7% (-588), dto 43% (-56), consumer 17% (-44), entity 83% (-29), exception 48% (-25), config 29% (-24) |
| **shared** | 30.4% | security 0% (-133), scheduler 28% (-159), currency 55% (-17), audit 88% (-2), tenant 97% (-1) |

**Priority hot-spots (by line count):**
1. `contributions-service/service` — 1138 missing lines (mostly contribution-cycle services with no IT yet)
2. `user-service/service` — 889 missing
3. `user-service/controller` — 588 missing (most controllers have only happy-path WebFluxTest)
4. `claims-service/service` — 445
5. `tenancy-service/service` — 317
6. `rules-engine/fact` — 227 (DRL fact classes — many setters that getter-tests would close)
7. `finance-service/controller` — 204
8. `shared/scheduler` — 159
9. `shared/security` — 133 (likely Keycloak event-listener — needs a Keycloak Testcontainer)
10. `claims-service/client` — 122 (HTTP client; needs WireMock or Wiremock-Testcontainer)

## Go (`go test -cover` line coverage)

| Service | Baseline | Uncovered packages |
|---|---|---|
| **gateway** | 20.5% | cmd, events, platform, routes all 0% — needs an httptest harness for routes.Register + middleware orchestration |
| **audit-service** | 14.8% (default) — much higher with `-tags=integration` | db package 0% in default mode; covered via the new IT path |
| **notification-service** | 64.8% | minor — handler 91%, notification 85%, config 75%, cmd 0% |
| **file-service** | 76.2% | cmd 0%; handler/storage/export already ~100% |
| **payment-gateway** | 79.8% | cmd 0%; handler 94%, payment 100% |

**Recurring pattern:** every Go service has a `cmd` package at 0% — that's `main()` glue. Pragmatic choice: exclude `cmd/**` from the coverage target rather than test it.

## Python (`pytest --cov` line coverage)

ai-service baseline: **70%**. Per-file gaps:

| File | Coverage | Missing |
|---|---|---|
| `app/core/kafka_consumer.py` | **0%** | 57 lines — needs aiokafka-backed test or full mock |
| `app/core/tenant.py` | **0%** | 10 lines — trivial, just needs basic call coverage |
| `app/core/gemini_client.py` | 30% | 37 lines — gated by `GOOGLE_API_KEY`; needs a fake client |
| `app/core/anthropic_client.py` | 49% | 22 lines — same shape as gemini; needs mocked happy path |
| `app/core/database.py` | 42% | 15 lines — connect/disconnect; needs in-memory engine test |
| `app/services/ocr_service.py` | 45% | 21 lines — tesseract calls; mock pytesseract |
| `app/services/chatbot_service.py` | 64% | 14 lines |
| `app/api/analytics.py` | 61% | 9 lines |
| `app/api/ocr.py` | 60% | 8 lines |
| `app/api/adjudication.py` | 83% | 7 lines |

## Elixir

Baseline (Elixir 1.18 / OTP 27 / `mix coveralls`): **25 tests passing across the umbrella.**

| App | Coverage | Lowest-cov modules |
|---|---|---|
| **live_dashboard** | 50.98% | `Kafka.EventConsumer` 0%, `ClaimsChannel` 0%, `FinanceChannel` 0%, `HealthController` 0%, `Router` 0%, `DashboardSocket` 12.5% |
| **chat_service** | 20.44% | `AiProxy` 0%, `Chat.ReadReceipt` 0%, `Chat.Room` 0%, `HealthController` 0%, `RoomController` 0%, `Router` 0%, `ChatChannel` 14.6%, `Chat` 20% |

**Infrastructure set up to get here (kept for memory):**
- Three required apt packages on a fresh Ubuntu: `elixir`, `cmake`, `erlang-dev` (last one provides `erl_nif.h` for the `crc32cer` NIF that `broadway_kafka` → `brod` builds).
- Added `pubsub_server:` to both Endpoint configs in `services/elixir/config/config.exs` — without it `Phoenix.ChannelTest.subscribe_and_join/4` errors with `"no :pubsub_server configured"`.
- Added `ecto_repos:` per app (otherwise Mix tasks emit "could not find Ecto repos" warnings).
- Test config (`services/elixir/config/test.exs`) now points both repos at a dedicated `medfund_elixir_test` database, sets `pool: Ecto.Adapters.SQL.Sandbox`, sets `server: false` on both endpoints, and quiets the logger.
- Wrote first chat_service migration (`apps/chat_service/priv/repo/migrations/20260619000001_create_chat_tables.exs`) creating `chat_rooms`, `chat_messages`, `chat_read_receipts`. The existing umbrella `test` alias auto-creates+migrates on `mix test`.
- `apps/chat_service/test/support/channel_case.ex` now checks out a sandbox owner per test so channel processes can read the repo.
- Fixed a latent test bug in `apps/chat_service/test/channels/chat_channel_test.exs` — tests used `"chat:room-123"` topics but `room_id` is `:binary_id`; the channel's `after_join` always queries the DB. Now uses `Ecto.UUID.generate()` and exercises the typing broadcast through `Phoenix.PubSub` properly.

## Angular

Existing JaCoCo-style gate set to 35%; current actuals on touched files exceed that. Coverage push to 100% is multi-day work; recommended deferral until backend is closer to target.

---

## Infrastructure landmines fixed this session (kept for memory)

- **Testcontainers 1.19.x / 1.20.x** ships `docker-java` that negotiates Docker API 1.32, which modern Docker Engines (1.44+ minimum) reject with "client version is too old". The fix lives in `services/java/build.gradle.kts`: override the BOM property `testcontainers.version = "1.21.4"` and import the matching BOM. Any future Spring Boot upgrade that pins testcontainers will re-introduce this.
- **Flyway 10** split DB support out of `flyway-core`. Every IT that triggers Flyway needs `testRuntimeOnly("org.flywaydb:flyway-database-postgresql")` + `testRuntimeOnly("org.postgresql:postgresql")`. Currently only contributions-service has this set; future ITs in other modules will hit the same "Unsupported Database: PostgreSQL 17" error.
- **SecurityConfig + IT**: any IT loading the full Spring context against a service with `oauth2.resourceserver.jwt` needs a stub `ReactiveJwtDecoder` bean. Pattern: see `SchemeServiceIT.SecurityStub` for the minimal `@TestConfiguration` shape. Worth extracting to `services/java/shared/testFixtures` as `OAuth2TestSecurityConfig` when more ITs need it.
- **`docker-java` Postgres precision**: `TIMESTAMPTZ` is microsecond-precision; nanosecond-precision `before`/`after` bounds in IT assertions can flake. See the fix in `services/go/audit-service/internal/audit/store_integration_test.go` (truncate-to-micros). Same caveat applies on the Java side.

## Real production bugs found while stabilising (do not regress)

- **`TenantRuleEngine` cross-tenant `KieContainer` shadowing** — fixed by minting per-tenant `ReleaseId`. The concurrency IT exists specifically to guard this; do not relax it.
- **`TenantAwareConnectionFactory` `search_path` reset** — when no tenant is in context, the factory now always resets `search_path` to `public` to defend against pooled-connection leakage. Test was rewritten to match the new contract.
