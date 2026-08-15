# AGENTS.md — InsureFlow / medfund

Polyglot insurance-core monorepo. Java 21 Spring WebFlux/R2DBC services form the line-agnostic core (claims, contributions, finance, tenancy, rules, user); Go 1.23 Fiber services handle high-throughput (gateway, notification, audit, file, payments); Elixir Phoenix apps do real-time; Python FastAPI (`uv`) does AI; Angular 19 + Flutter are the clients. Medical aid (`HEALTH`) is the only production-ready insurance line — the rest are scaffolded.

## Read first

- `.claude/CLAUDE.md` — canonical architecture guide: insurance-line status, the "prefer line-neutral wording" framing rule, Java/Lombok conventions, and 9 critical rules (money, tenancy, Kafka, audit, Swagger, rules engine). The root `CLAUDE.md` is a condensed quick reference; the deep guide is here.
- `.claude/coding-standards.md` — per-language standards (Go, Python, Elixir, Angular, Flutter) plus audit-log and Swagger requirements.
- `.claude/coverage-backlog.md` — which services sit below the enforced coverage gate.

## Layout

- `services/java` — one Gradle multi-project (`./gradlew :<module>:<task>`). Modules: `shared` (TenantContext, InsuranceLine enum, audit publisher, test fixtures), `tenancy-service`, `user-service`, `claims-service`, `contributions-service`, `finance-service`, `rules-engine`. `keycloak-event-listener/` is a **standalone** Gradle build, not part of `settings.gradle.kts`.
- `services/go` — Go workspace (`go.work`, modules `github.com/medfund/*`). `shared/` has `httpserver`, tenant middleware, audit helpers.
- `services/elixir` — Mix umbrella: `apps/live_dashboard`, `apps/chat_service`.
- `services/python/ai-service` — FastAPI; managed with `uv` only (no pip/venv).
- `clients/angular` — single app for all portals, role-based routing; serves on **port 5100** (`angular.json`), not 4200.

## Commands

```bash
make infra                    # postgres(5433) redis(6380) kafka(9092) kafka-ui(8090) keycloak(9080) minio(9000)
make keycloak-setup           # bootstrap realms/clients — run once per fresh `make infra` or `make infra-reset`
# Java
cd services/java && ./gradlew :finance-service:bootRun
# Go (air = live reload; install: go install github.com/air-verse/air@latest)
cd services/go/gateway && air        # or go run ./cmd
# Python
cd services/python/ai-service && uv run uvicorn app.main:app --reload --port 8000
# Elixir
cd services/elixir && mix phx.server
# Angular
cd clients/angular && npm start
```

## Tests & enforced coverage gates (70%)

A PR that touches a service below the bar fails CI. Details per service in `.claude/coverage-backlog.md`.

- **Java**: integration tests are `*IT` classes (Testcontainers — need Docker). Unit-only: `./gradlew test --tests '!*IT'`. Integration: `./gradlew test --tests '*IT'`. Gate (`jacocoTestCoverageVerification`, 70% LINE/module) only runs via `./gradlew check`, not bare `test`.
- **Go**: `cd services/go && go test ./...`. Integration tests use build tag `integration` (dockertest) and are skipped by default. Gate is CI-only (parses `go tool cover` at 70%/service).
- **Python**: `uv run pytest` — the `--cov-fail-under=70` gate is baked into `pyproject.toml` `addopts`, so the gate runs locally too. Lint: `uv run ruff check .` (line-length 120).
- **Angular**: Karma/Jasmine (not Jest). `npx ng test --watch=false --code-coverage --browsers=ChromeHeadlessCI`; karma.conf.js `check.global` is 70/60/70/70. ESLint is not wired up.
- **Elixir**: `mix coveralls.json --umbrella` (ExCoveralls gate in `coveralls.json`); `mix test` for quick runs.
- **E2E**: Playwright under `clients/angular/e2e` (advisory, non-blocking). First time: `npm install && npx playwright install --with-deps chromium`.

## Java quirks

- Lombok is mandatory (root `build.gradle.kts`): `@Slf4j`, `@RequiredArgsConstructor` constructor injection, Java `record`s for request/response DTOs, `@Getter/@Setter` on R2DBC entities — never `@Data` on entities. Full table in `.claude/CLAUDE.md`.
- Everything is reactive (WebFlux/Mono/Flux, R2DBC). Tenancy is enforced via the `TenantContext` interceptor — every query must be tenant-scoped.
- **Testcontainers is pinned to 1.21.4** in the root build (the Spring Boot BOM pins 1.19.8 whose docker-java negotiates an API version modern Docker rejects). Do not "fix" this.
- ITs that load Spring Security config need a stub `ReactiveJwtDecoder` bean (see `SchemeServiceIT.SecurityStub`); Flyway-based ITs need `testRuntimeOnly("org.flywaydb:flyway-database-postgresql")`.
- `bootRun` JVMs are capped at `-Xmx384m` to avoid OOM on this machine — don't raise it casually.

## Architecture invariants (from `.claude/CLAUDE.md`)

- Money: `BigDecimal`/`shopspring/decimal`/`decimal.Decimal` — never floats; never mix currencies in arithmetic.
- Inter-service side effects go over Kafka events; synchronous calls only for reads.
- Every entity mutation emits an audit event to Kafka; every endpoint ships with Swagger/OpenAPI 3.1 (`/swagger-ui` Java, `/docs` Python).
- Rules engine compiles tenant-configured JSON `RuleDefinition`s to Drools DRL; each tenant gets its own `ReleaseId` `KieContainer` (cross-tenant shadowing is a known regression — don't undo the per-tenant minting).

## Git

- Conventional Commits (`feat:`, `fix:`, `chore:`, `refactor:`), small focused PRs (~≤400 lines). Branch naming: `feature/{ticket}-desc`, `fix/...`, `chore/...`.
- Commit workflow used in this repo: single commit, imperative mood, no attribution lines (see `.opencode/commands/commit.md`).
- Workflow commands for opencode live in `.opencode/commands/`: `/research-codebase` → `/create-plan` → `/implement-plan` (RPI loop), plus `/grilling` and `/commit`. Claude Code copies remain in `.claude/commands/`.
