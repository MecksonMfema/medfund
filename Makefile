# MedFund — Developer Makefile (Linux)
#
# Workflow: run infrastructure in Docker, run application services natively.
#
#   1.  make infra          — start postgres, redis, kafka, keycloak, minio
#   2a. make tenancy        — run the tenancy service (Spring Boot, port 8081)
#   2b. make gateway        — run the API gateway (Go/air, port 3000)
#   2c. make ai             — run the AI service (Python/uvicorn, port 8000)
#   2d. make web            — run the Angular app (ng serve, port 4200)
#   ... (see targets below)

SHELL := /bin/bash
.SHELLFLAGS := -ec

# Elixir/mix is expected on PATH (e.g. `apt install elixir` or asdf).
MIX := mix

COMPOSE := docker compose

# ── Infrastructure (Docker) ───────────────────────────────────────────────────

## Start all infrastructure services (detached)
infra:
	$(COMPOSE) up postgres redis kafka kafka-ui keycloak minio

## Stop infrastructure services (keep volumes)
infra-down:
	$(COMPOSE) stop postgres redis kafka kafka-ui keycloak minio

## Full reset — stop infra and wipe all volumes (fresh database, Kafka, etc.)
infra-reset:
	$(COMPOSE) down -v postgres redis kafka kafka-ui keycloak minio

## Show infrastructure container status
infra-ps:
	$(COMPOSE) ps postgres redis kafka kafka-ui keycloak minio

## Tail infrastructure logs (Ctrl+C to stop)
infra-logs:
	$(COMPOSE) logs -f postgres redis kafka keycloak

## Bootstrap Keycloak realms and clients (run once after first `make infra`)
keycloak-setup:
	bash scripts/bootstrap-keycloak.sh

# ── Demo-data seeder (Python CLI, two tenants: HEALTH + LIFE) ───────────────
# Gated behind SEED_MODE_ENABLED=true; the Makefile exports it for you.

## Install seeder Python deps (uv sync). Run once.
seed-demo-setup:
	cd scripts/demo-seeder && uv sync

## Drop seed tenants + Keycloak realms + seeder-tagged FX rates (idempotent)
seed-demo-reset:
	SEED_MODE_ENABLED=true bash scripts/reset-tenant-schemas.sh

## Seed (micro: 500 members x 3 months, ~15 min, ~$5-$15 in AI cost)
seed-demo-micro: seed-demo-setup
	cd scripts/demo-seeder && SEED_MODE_ENABLED=true uv run demo-seeder seed --tier micro

## Seed (fast: 2k members x 6 months, ~2-3 h, ~$100-$400)
seed-demo-fast: seed-demo-setup
	cd scripts/demo-seeder && SEED_MODE_ENABLED=true uv run demo-seeder seed --tier fast

## Seed (full: 20k members x 24 months, days, ~$6k-$25k — confirm budget first)
seed-demo-full: seed-demo-setup
	cd scripts/demo-seeder && SEED_MODE_ENABLED=true uv run demo-seeder seed --tier full

## Post-run row-count verification. Usage: make seed-demo-verify TIER=micro
seed-demo-verify:
	cd scripts/demo-seeder && SEED_MODE_ENABLED=true uv run demo-seeder verify --tier $(TIER)

## Probe every Java service, Keycloak, and the AI service. Exits non-zero on failure.
seed-demo-preflight: seed-demo-setup
	cd scripts/demo-seeder && SEED_MODE_ENABLED=true uv run demo-seeder preflight

# ── Java services (Spring Boot) — cd services/java first ─────────────────────
# Spring Boot DevTools is on classpath — the JVM restarts automatically when
# Gradle recompiles changed classes (triggered by your IDE on save, or Gradle -t).

tenancy:
	cd services/java && ./gradlew :tenancy-service:bootRun

user:
	cd services/java && ./gradlew :user-service:bootRun

claims:
	cd services/java && ./gradlew :claims-service:bootRun

contributions:
	cd services/java && ./gradlew :contributions-service:bootRun

finance:
	cd services/java && ./gradlew :finance-service:bootRun

rules:
	cd services/java && ./gradlew :rules-engine:bootRun

## Run all Java services in parallel (each in the background)
java-all:
	$(COMPOSE) up -d postgres redis kafka keycloak
	cd services/java && ./gradlew :tenancy-service:bootRun & \
	cd services/java && ./gradlew :user-service:bootRun & \
	cd services/java && ./gradlew :claims-service:bootRun & \
	cd services/java && ./gradlew :contributions-service:bootRun & \
	cd services/java && ./gradlew :finance-service:bootRun

# ── Go services (air live reload) ────────────────────────────────────────────
# 'air' watches *.go files and rebuilds on save. Install: go install github.com/air-verse/air@latest

gateway:
	cd services/go/gateway && air

notification:
	cd services/go/notification-service && air

audit:
	cd services/go/audit-service && air

file-svc:
	cd services/go/file-service && air

payment:
	cd services/go/payment-gateway && air

market-data:
	cd services/go/market-data-service && air

# ── Elixir services (Phoenix live reload — built-in to dev mode) ─────────────

live-dashboard:
	cd services/elixir && MIX_ENV=dev $(MIX) phx.server

chat:
	cd services/elixir && MIX_ENV=dev $(MIX) phx.server

# First-time Elixir setup (fetch deps + create DB)
elixir-setup:
	cd services/elixir && $(MIX) deps.get && MIX_ENV=dev $(MIX) deps.compile && MIX_ENV=dev $(MIX) compile

# ── Python AI service (uvicorn --reload) ─────────────────────────────────────

ai:
	cd services/python/ai-service && uv run uvicorn app.main:app --reload --port 8000

# First-time Python setup
ai-setup:
	cd services/python/ai-service && uv sync

# ── Angular web app ───────────────────────────────────────────────────────────

web:
	cd clients/angular && npm start

# First-time Angular setup
web-setup:
	cd clients/angular && npm install

# ── Tests ─────────────────────────────────────────────────────────────────────

test-java:
	cd services/java && ./gradlew test

test-go:
	cd services/go && go test ./...

test-elixir:
	cd services/elixir && $(MIX) test

test-python:
	cd services/python/ai-service && uv run pytest

test-angular:
	cd clients/angular && npx ng test --watch=false

test-flutter:
	cd clients/flutter && flutter test --coverage

# Java integration tests (Testcontainers slices, Phase 3 shared fixtures).
# Filters to *IT — keeps `make test-java` fast (unit-only) and `make test-integration`
# focused on the longer Postgres/Kafka container tests.
test-integration:
	cd services/java && ./gradlew test --tests '*IT'

# Playwright E2E suite under clients/angular/e2e/.
# First-time machine setup:
#   cd clients/angular/e2e && npm install && npx playwright install --with-deps chromium
test-e2e:
	cd clients/angular/e2e && npm test

# Per-language coverage summary.
test-coverage:
	bash scripts/coverage-summary.sh

.PHONY: infra infra-down infra-reset infra-ps infra-logs keycloak-setup \
        seed-demo-setup seed-demo-reset seed-demo-micro seed-demo-fast \
        seed-demo-full seed-demo-verify seed-demo-preflight \
        tenancy user claims contributions finance java-all \
        gateway notification audit file-svc payment market-data \
        live-dashboard chat elixir-setup \
        ai ai-setup \
        web web-setup \
        test-java test-go test-elixir test-python test-angular \
        test-flutter test-integration test-e2e test-coverage
