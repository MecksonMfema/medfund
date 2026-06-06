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
	cd clients/angular && ng serve

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
	cd clients/angular && ng test --watch=false

.PHONY: infra infra-down infra-reset infra-ps infra-logs keycloak-setup \
        tenancy user claims contributions finance java-all \
        gateway notification audit file-svc payment \
        live-dashboard chat elixir-setup \
        ai ai-setup \
        web web-setup \
        test-java test-go test-elixir test-python test-angular
