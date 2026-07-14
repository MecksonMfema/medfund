# MedFund Platform

Core insurance operating system — multi-tenant, multi-line SaaS. Greenfield polyglot build.

The platform is line-agnostic at the core (claims, contributions, finance, rules, tenancy). The `InsuranceLine` enum today covers **HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY, VEHICLE, PROPERTY**. Medical aid is the production-ready vertical; life, funeral, disability, travel, and asset lines have entities and enrollment scaffolding but are not yet end-to-end.

See [.claude/CLAUDE.md](.claude/CLAUDE.md) for full architecture guidelines, tech stack, and critical rules.

## Quick Reference

| Service | Language | Port | Path |
|---------|----------|------|------|
| Tenancy Service | Java 21 / Spring Boot WebFlux | 8081 | `services/java/tenancy-service` |
| User Service | Java 21 / Spring Boot WebFlux | 8082 | `services/java/user-service` |
| Claims Service | Java 21 / Spring Boot WebFlux | 8083 | `services/java/claims-service` |
| Contributions Service | Java 21 / Spring Boot WebFlux | 8084 | `services/java/contributions-service` |
| Finance Service | Java 21 / Spring Boot WebFlux | 8085 | `services/java/finance-service` |
| Rules Engine | Java 21 / Drools 9 | (library) | `services/java/rules-engine` |
| API Gateway | Go 1.23 / Fiber v2 | 3000 | `services/go/gateway` |
| Notification Service | Go 1.23 / Fiber v2 | 3001 | `services/go/notification-service` |
| Audit Service | Go 1.23 / Fiber v2 | 3002 | `services/go/audit-service` |
| File Service | Go 1.23 / Fiber v2 | 3003 | `services/go/file-service` |
| Payment Gateway | Go 1.23 / Fiber v2 | 3004 | `services/go/payment-gateway` |
| Live Dashboard | Elixir 1.17 / Phoenix 1.7 | 4000 | `services/elixir/apps/live_dashboard` |
| Chat Service | Elixir 1.17 / Phoenix 1.7 | 4001 | `services/elixir/apps/chat_service` |
| AI Service | Python 3.12 / FastAPI | 8000 | `services/python/ai-service` |
| Angular Web App | Angular 19 | 5100 | `clients/angular` |
| Flutter App | Flutter 3.x | — | `clients/flutter` |

## Common Commands

```bash
# Start infrastructure
docker compose up -d

# Java services
cd services/java && ./gradlew build

# Go services
cd services/go/gateway && go run ./cmd

# Elixir services
cd services/elixir && mix deps.get && mix phx.server

# Python AI service
cd services/python/ai-service && uv sync && uv run uvicorn app.main:app --reload

# Angular
cd clients/angular && npm install && ng serve

# Flutter
cd clients/flutter && flutter pub get && flutter run
```
