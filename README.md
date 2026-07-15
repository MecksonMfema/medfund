# InsureFlow

Core insurance operating system. Multi-tenant, multi-line, multi-currency, AI-powered.

InsureFlow's Java core (claims, contributions, finance, rules, tenancy) is line-agnostic. The `InsuranceLine` enum covers **HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY, VEHICLE, PROPERTY**, and dedicated entities exist for `LifePolicy`, `FuneralPolicy`, `DisabilityPolicy`, `TravelPolicy`, `Vehicle`, and `Property`. Medical aid is the currently production-ready vertical; the other lines have schema and enrollment scaffolding in place and are progressing.

## Architecture

InsureFlow is a polyglot microservices platform:

- **Java 21 + Spring Boot WebFlux** — Core domain: claims, contributions, finance, tenancy, rules engine, user/policy
- **Go 1.23 + Fiber v2** — High-throughput: API gateway, notifications, audit, file processing, payments
- **Elixir 1.17 + Phoenix 1.7** — Real-time: live dashboards, WebSocket channels, chat
- **Python 3.12 + FastAPI** — AI/ML: adjudication, fraud, OCR, chatbot, forecasting, analytics, pricing
- **Angular 19** — Web frontend (super admin, tenant admin, operations, providers)
- **Flutter 3.x** — Mobile + web (member portal, provider companion, group liaison)

### Infrastructure

- **Database**: Schema-per-tenant PostgreSQL 17
- **Message Broker**: Apache Kafka (event backbone) + Redis (caching, job queues)
- **Auth**: Keycloak (OIDC/OAuth2, per-tenant realms, MFA)
- **Storage**: S3-compatible object storage (MinIO local, AWS S3 prod)
- **Observability**: OpenTelemetry + Grafana stack (Loki, Tempo, Mimir)
- **Deployment**: Helm 3 + ArgoCD + GitHub Actions + Terraform (AWS)

## Repository Structure

```
medfund/
├── services/
│   ├── java/                   # Gradle multi-project (6 services + shared lib)
│   │   ├── tenancy-service/    # Tenant lifecycle, provisioning, plans, currencies, per-tenant proration
│   │   ├── user-service/       # Members, providers, dependants, groups, roles + policy entities (life, funeral, disability, travel, vehicle, property)
│   │   ├── claims-service/     # Claims (line-tagged), pre-auth, adjudication, tariffs, ICD codes, drug claims
│   │   ├── contributions-service/ # Schemes, benefits, age groups, billing cycles, invoices, group balances
│   │   ├── finance-service/    # Payments, payment runs, provider balances, adjustments, reconciliation
│   │   ├── rules-engine/       # Drools-backed engine with 15 rule-template categories (line-agnostic core)
│   │   └── shared/             # Tenant context, audit publisher, InsuranceLine enum, scheduling, RBAC
│   ├── go/                     # Go workspace (5 services + shared)
│   │   ├── gateway/            # API gateway, JWT validation, rate limiting
│   │   ├── notification-service/ # Email, SMS, push notifications
│   │   ├── audit-service/      # Audit event ingestion, security events
│   │   ├── file-service/       # S3 uploads, PDF/CSV generation, imports
│   │   ├── payment-gateway/    # Paynow, Stripe, Paystack, subscriptions
│   │   └── shared/             # Tenant middleware, audit helpers
│   ├── elixir/                 # Mix umbrella (2 apps)
│   │   ├── apps/live_dashboard/ # Real-time dashboards via Phoenix Channels
│   │   └── apps/chat_service/  # Member-staff chat, AI-assisted responses
│   └── python/
│       └── ai-service/         # FastAPI: adjudication AI, fraud, OCR, chatbot
├── clients/
│   ├── angular/                # Angular 19 web app (all portals)
│   └── flutter/                # Flutter mobile + web (member, provider, liaison)
├── infra/
│   ├── helm/                   # Helm charts per service
│   ├── terraform/              # AWS infrastructure (VPC, EKS, RDS, MSK, etc.)
│   ├── docker/                 # Docker configs, init scripts
│   └── argocd/                 # ArgoCD application manifests
├── proto/                      # Protobuf/gRPC service definitions
├── schemas/avro/               # Kafka event Avro schemas
├── .claude/                    # AI architecture guidelines (13 documents)
├── .github/workflows/          # CI per language (path-based triggers)
├── docker-compose.yml          # Local dev: PostgreSQL, Redis, Kafka, Keycloak, MinIO
└── CLAUDE.md                   # Quick reference for AI-assisted development
```

## Getting Started

### Prerequisites

- Docker & Docker Compose
- Java 21 (Temurin)
- Go 1.23+
- Elixir 1.17+ / OTP 27+
- Python 3.12+ with [uv](https://github.com/astral-sh/uv)
- Node.js 22+ (for Angular)
- Flutter 3.x

### Start Infrastructure

```bash
docker compose up -d
```

This starts PostgreSQL 17, Redis 7, Kafka (KRaft), Keycloak 26, and MinIO.

| Service | URL |
|---------|-----|
| PostgreSQL | `localhost:5433` (user: `medfund`, pass: `medfund`) |
| Redis | `localhost:6380` |
| Kafka | `localhost:9092` |
| Kafka UI | http://localhost:8090 |
| Keycloak | http://localhost:9080 (admin: `admin`/`admin`) |
| MinIO Console | http://localhost:9001 (user: `medfund`, pass: `medfund123`) |

### Run Services

```bash
# Java services (from services/java/)
./gradlew :tenancy-service:bootRun

# Go gateway (from services/go/gateway/)
go run ./cmd

# Elixir (from services/elixir/)
mix deps.get && mix phx.server

# Python AI service (from services/python/ai-service/)
uv sync && uv run uvicorn app.main:app --reload --port 8000

# Angular (from clients/angular/)
npm install && ng serve

# Flutter (from clients/flutter/)
flutter pub get && flutter run
```

### Service Ports

| Service | Port |
|---------|------|
| Tenancy Service | 8081 |
| User Service | 8082 |
| Claims Service | 8083 |
| Contributions Service | 8084 |
| Finance Service | 8085 |
| API Gateway | 3000 |
| Notification Service | 3001 |
| Audit Service | 3002 |
| File Service | 3003 |
| Payment Gateway | 3004 |
| Live Dashboard | 4000 |
| Chat Service | 4001 |
| AI Service | 8000 |
| Angular App | 5100 |

## Documentation

Architecture documents live in `.claude/`:

1. [Architecture Overview](.claude/architecture.md)
2. [Tech Stack](.claude/tech-stack.md)
3. [Build Strategy](.claude/migration-strategy.md)
4. [AI Integration](.claude/ai-integration.md)
5. [Multi-Currency](.claude/multi-currency.md)
6. [Multi-Tenancy](.claude/multi-tenancy.md)
7. [Claims Adjudication](.claude/adjudication.md)
8. [Rules Engine](.claude/rules-engine.md)
9. [Payment Gateway](.claude/payments.md)
10. [Infrastructure & DevOps](.claude/infrastructure.md)
11. [Coding Standards](.claude/coding-standards.md)
12. [Portals & Roles](.claude/portals.md)

## License

Proprietary. All rights reserved.
