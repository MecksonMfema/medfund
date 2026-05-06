# Local Development Setup

This guide covers running the full MedFund stack locally for development and testing.

## Prerequisites

Install the following before starting:

| Tool | Version | Install |
|------|---------|---------|
| Docker | Latest | Docker Desktop **or** Docker Engine in WSL2 |
| Java JDK | 21 | https://adoptium.net |
| Go | 1.23+ | https://go.dev/dl |
| Elixir + Erlang | 1.17 / OTP 27 | https://elixir-lang.org/install.html |
| Python | 3.12 | https://www.python.org/downloads |
| uv (Python pkg manager) | Latest | `pip install uv` |
| Node.js | 20 LTS | https://nodejs.org |
| Angular CLI | 19 | `npm install -g @angular/cli` |
| air (Go live reload) | Latest | `go install github.com/air-verse/air@latest` |
| make | — | Windows: `choco install make` or use Git Bash |

> **Windows users:** All `make` commands assume Git Bash or MSYS2. Run them in a Unix-compatible shell.

---

## Step 1 — Start Infrastructure

All infrastructure services run in Docker. Start them first:

```bash
make infra
```

This starts:

| Service | URL / Port | Credentials |
|---------|-----------|-------------|
| PostgreSQL 17 | `localhost:5433` | `medfund` / `medfund` |
| Redis 7 | `localhost:6380` | — |
| Apache Kafka | `localhost:9092` | — |
| Kafka UI | http://localhost:8090 | — |
| Keycloak 26 | http://localhost:9080 | `admin` / `admin` |
| MinIO (S3-compatible) | http://localhost:9001 (console) | `medfund` / `medfund123` |
| Mailpit (email catcher) | http://localhost:8026 | — |

Check that all containers are healthy before proceeding:

```bash
make infra-ps
# or
docker compose ps
```

Wait until Keycloak shows `healthy` — it takes 30–60 seconds on first boot.

---

## Step 2 — Bootstrap Keycloak (first time only)

Run once after the first `make infra`:

```bash
make keycloak-setup
```

This script creates:
- Realm: `medfund-platform`
- OIDC client: `medfund-web` (public client, PKCE)
- Realm roles: `super_admin`, `tenant_admin`, `claims_clerk`, `claims_assessor`, `finance_officer`, `contributions_officer`, `provider`, `member`, `group_liaison`
- Protocol mapper: `tenant_id` claim on JWTs

**Test users created by the script:**

| Username | Password | Role |
|----------|----------|------|
| `superadmin` | `admin123` | `super_admin` |
| `claimsclerk` | `test123` | `claims_clerk` |
| `financeofficer` | `test123` | `finance_officer` |
| `contribofficer` | `test123` | `contributions_officer` |
| `testmember` | `test123` | `member` |
| `testprovider` | `test123` | `provider` |

> **Troubleshooting:** If the script fails with a connection error, Keycloak is still starting. Wait 30 seconds and retry. See [keycloak-setup.md](keycloak-setup.md) for CSP and iframe issues.

---

## Step 3 — Start Application Services

Each service runs natively (not in Docker) with live reload. Open a terminal for each service you need.

### Java Services (Spring Boot WebFlux)

Spring Boot DevTools is on the classpath — the JVM restarts automatically when Gradle recompiles changes (triggered by your IDE on save).

```bash
make tenancy        # Tenancy Service   → http://localhost:8081
make user           # User Service      → http://localhost:8082
make claims         # Claims Service    → http://localhost:8083
make contributions  # Contributions     → http://localhost:8084
make finance        # Finance Service   → http://localhost:8085
```

Swagger UI for each service: `http://localhost:<port>/swagger-ui`

### Go Services (air live reload)

`air` watches `*.go` files and rebuilds on save.

```bash
make gateway        # API Gateway       → http://localhost:3000
make notification   # Notifications     → http://localhost:3001
make audit          # Audit Service     → http://localhost:3002
make file-svc       # File Service      → http://localhost:3003
make payment        # Payment Gateway   → http://localhost:3004
```

### Elixir Services (Phoenix)

First-time setup (run once):

```bash
make elixir-setup   # fetch deps + create + migrate DB
```

Then start:

```bash
make live-dashboard  # Live Dashboard  → http://localhost:4000
make chat            # Chat Service    → http://localhost:4001
```

### Python AI Service

First-time setup (run once):

```bash
make ai-setup        # uv sync — installs dependencies
```

Set your Anthropic API key (optional — service falls back to rule-based logic without it):

```bash
export MEDFUND_ANTHROPIC_API_KEY=sk-ant-...
```

Start the service:

```bash
make ai              # AI Service      → http://localhost:8000
```

API docs: http://localhost:8000/docs

### Angular Web App

First-time setup (run once):

```bash
make web-setup       # npm install
```

Start:

```bash
make web             # Angular app     → http://localhost:4200
```

---

## Step 4 — Verify Everything Is Running

Open http://localhost:4200 in your browser. The app redirects to Keycloak login. Log in with any test user (e.g., `superadmin` / `admin123`). You should land on the dashboard.

Check individual service health endpoints:

```bash
# Java services (Spring Boot Actuator)
curl http://localhost:8081/actuator/health             # Tenancy Service
curl http://localhost:8082/actuator/health             # User Service
curl http://localhost:8083/actuator/health             # Claims Service
curl http://localhost:8084/actuator/health             # Contributions Service
curl http://localhost:8085/actuator/health             # Finance Service

# Go services
curl http://localhost:3000/health                      # API Gateway
curl http://localhost:3001/health                      # Notification Service
curl http://localhost:3002/health                      # Audit Service
curl http://localhost:3003/health                      # File Service
curl http://localhost:3004/health                      # Payment Gateway

# Elixir services (versioned health paths)
curl http://localhost:4000/api/v1/dashboard/health     # Live Dashboard
curl http://localhost:4001/api/v1/chat/health          # Chat Service

# Python AI Service
curl http://localhost:8000/health                      # AI Service
```

---

## Running Tests

```bash
make test-java       # Gradle test (all Java services)
make test-go         # go test ./... (all Go services)
make test-elixir     # mix test
make test-python     # pytest (uv run)
make test-angular    # ng test --watch=false
```

---

## Minimum Stack for Common Tasks

You don't need every service running at once. Start only what you need:

| Task | Required services |
|------|-----------------|
| Frontend UI work | `make infra` + `make keycloak-setup` + `make web` |
| Claims API work | `make infra` + `make tenancy` + `make claims` + `make gateway` |
| Finance work | `make infra` + `make tenancy` + `make finance` + `make gateway` |
| AI / adjudication | `make infra` + `make tenancy` + `make claims` + `make ai` |
| Full stack | all of the above |

---

## Environment Configuration

All services read configuration from environment variables. Local defaults are baked into each service's `application.yml` / `config.go` / `config.exs` / `config.py` — no `.env` file is needed for basic local dev.

Override any default by setting the environment variable before running the service:

**Java (Spring Boot):**
```bash
SPRING_R2DBC_URL=r2dbc:postgresql://custom-host:5432/medfund make tenancy
```

**Go:**
```bash
GATEWAY_PORT=3001 make gateway
```

**Python:**
```bash
MEDFUND_DATABASE_URL=postgresql+asyncpg://... make ai
```

**Angular** — edit `clients/angular/src/environments/environment.ts` directly for local overrides.

---

## Resetting the Environment

```bash
# Stop infra (keep data volumes)
make infra-down

# Full reset — wipe all volumes (fresh DB, Kafka, Keycloak state)
make infra-reset

# After infra-reset, re-run Keycloak setup
make infra && make keycloak-setup
```

---

## Service Port Reference

| Service | Port | Language |
|---------|------|----------|
| AI Service | 8000 | Python / FastAPI |
| Tenancy Service | 8081 | Java / Spring Boot |
| User Service | 8082 | Java / Spring Boot |
| Claims Service | 8083 | Java / Spring Boot |
| Contributions Service | 8084 | Java / Spring Boot |
| Finance Service | 8085 | Java / Spring Boot |
| API Gateway | 3000 | Go / Fiber |
| Notification Service | 3001 | Go / Fiber |
| Audit Service | 3002 | Go / Fiber |
| File Service | 3003 | Go / Fiber |
| Payment Gateway | 3004 | Go / Fiber |
| Live Dashboard | 4000 | Elixir / Phoenix |
| Chat Service | 4001 | Elixir / Phoenix |
| Angular Web App | 4200 | Angular 19 |
| PostgreSQL | 5433 | — |
| Redis | 6380 | — |
| Kafka | 9092 | — |
| Kafka UI | 8090 | — |
| Keycloak | 9080 | — |
| MinIO API | 9000 | — |
| MinIO Console | 9001 | — |

---

## Common Issues

**Keycloak health never becomes `healthy`**
— Docker Desktop may not have enough memory. Go to Docker Desktop → Settings → Resources and allocate at least 4 GB RAM.

**Java service fails with `Connection refused` to Postgres/Kafka**
— Infrastructure is still starting. Wait for `make infra-ps` to show all services as `healthy`.

**Invitation emails not arriving**
— Open Mailpit at http://localhost:8026. All outbound emails (Keycloak invites, password resets) are caught there. Emails never reach real inboxes in local dev.

**`air: command not found`**
— Run `go install github.com/air-verse/air@latest` and ensure `$GOPATH/bin` is on your `PATH`.

**Angular login loop / iframe CSP error**
— See [keycloak-setup.md](keycloak-setup.md) for the full fix (realm CSP settings + `checkLoginIframe: false`).

**`uv: command not found`**
— Run `pip install uv` or follow https://docs.astral.sh/uv/getting-started/installation/.

**Elixir `mix ecto.create` fails**
— Ensure `make infra` is running first and PostgreSQL is accepting connections on `localhost:5433`.
