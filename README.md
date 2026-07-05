# MedFund

Healthcare claims management SaaS platform. Multi-tenant, multi-currency, AI-powered.

## Architecture

MedFund is a polyglot microservices platform:

- **Java 21 + Spring Boot WebFlux** — Core domain: claims, contributions, finance, tenancy, rules engine
- **Go 1.23 + Fiber v2** — High-throughput: API gateway, notifications, audit, file processing, payments
- **Elixir 1.17 + Phoenix 1.7** — Real-time: live dashboards, WebSocket channels, chat
- **Python 3.12 + FastAPI** — AI/ML: adjudication AI, fraud detection, OCR, chatbot
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
│   │   ├── tenancy-service/    # Tenant lifecycle, provisioning, plans
│   │   ├── user-service/       # Members, providers, groups, roles
│   │   ├── claims-service/     # Claims, adjudication, tariffs, ICD-10
│   │   ├── contributions-service/ # Schemes, billing, contributions
│   │   ├── finance-service/    # Payments, payment runs, reconciliation
│   │   ├── rules-engine/       # Drools-based per-tenant business rules
│   │   └── shared/             # Tenant context, audit publisher, security
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

Using the following reference:
  /home/methuseli-mfema/Documents/personal/MASCA-Backend we need to add member
  operations. The one that I can think of is a group change, scheme upgrade, or
  downgrade, currency change, assigning every member and dependant benefit balances
  on creation and update, dependant and member swap, member number suffix
  definition, check for other gaps from the reference. For now concentrate on
  features that affect the billing section of the application the other sections
  will be added later. Add a strong test
  coverage as there is a lot of moving parts. Take note some of the member
  operations do not take effect immediately, for example a member can change a
  group and it will take effect the following month, when the month is reached this
  will trigger the actual change. Some of the operations are back dated which
  should trigger some financial adjustments. Another missing section in the application are waiting
  periods which should be added to schemes similar to benefits.


  The page /tenant/dashboard is broken, the data is incorrect, the payment requests should show number invoices not contributions.
  Received payments are also not showing both the chart and payment received card. We also need to clean the invoice table in the page. Remove the Quick filters and status
  Then move the filters and put them in the header of the table next to the search input. This should match the table in the page /tenant/billing/transactions 


We are left with standardizing the group edit page. Make the Form match the rest
  of the UI pattern in the application. Standardize the back button in the
  following pages /tenant/billing/view/{id}, /tenant/members/{id}, and
  /tenant/groups/{id}.