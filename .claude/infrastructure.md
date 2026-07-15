# Infrastructure & DevOps

## Overview

InsureFlow v2 uses a GitOps-driven deployment model with containerized polyglot services orchestrated on Kubernetes.

## Cloud Architecture (AWS Target — Adaptable to GCP/Azure)

```
┌─────────────────────────────────────────────────────────────────┐
│                        AWS Account                               │
│                                                                  │
│  ┌─────────────────────────────────┐  ┌───────────────────────┐  │
│  │        EKS Cluster              │  │   Managed Services    │  │
│  │                                 │  │                       │  │
│  │  ┌──────────┐ ┌──────────┐     │  │  RDS PostgreSQL 17    │  │
│  │  │ Java Pods│ │ Go Pods  │     │  │  ElastiCache Redis 7  │  │
│  │  │ (Spring) │ │ (Fiber)  │     │  │  MSK (Kafka)          │  │
│  │  └──────────┘ └──────────┘     │  │  S3 (object storage)  │  │
│  │  ┌──────────┐ ┌──────────┐     │  │  SES (email)          │  │
│  │  │ Elixir   │ │ Python   │     │  │  CloudFront (CDN)     │  │
│  │  │ Pods     │ │ AI Pods  │     │  │  ACM (TLS certs)      │  │
│  │  └──────────┘ └──────────┘     │  │  Route 53 (DNS)       │  │
│  │  ┌──────────┐ ┌──────────┐     │  │  ECR (container reg.) │  │
│  │  │ Keycloak │ │ ArgoCD   │     │  │  Secrets Manager      │  │
│  │  └──────────┘ └──────────┘     │  └───────────────────────┘  │
│  │  ┌──────────────────────┐      │                              │
│  │  │ Grafana Stack        │      │                              │
│  │  │ (Grafana+Loki+Tempo+ │      │                              │
│  │  │  Mimir+OTel Collector│      │                              │
│  │  └──────────────────────┘      │                              │
│  └─────────────────────────────────┘                              │
│                                                                  │
│  ┌─────────────────────────────────┐                              │
│  │     CloudFront Distribution     │                              │
│  │  ┌──────────┐  ┌─────────────┐  │                              │
│  │  │ Angular  │  │ Flutter Web │  │                              │
│  │  │ (S3 SPA) │  │ (S3 SPA)   │  │                              │
│  │  └──────────┘  └─────────────┘  │                              │
│  └─────────────────────────────────┘                              │
└─────────────────────────────────────────────────────────────────┘
```

## Infrastructure as Code (Terraform)

```
infrastructure/
├── terraform/
│   ├── environments/
│   │   ├── staging/
│   │   │   ├── main.tf
│   │   │   ├── variables.tf
│   │   │   └── terraform.tfvars
│   │   └── production/
│   │       ├── main.tf
│   │       ├── variables.tf
│   │       └── terraform.tfvars
│   └── modules/
│       ├── eks/              # EKS cluster + node groups
│       ├── rds/              # PostgreSQL RDS (multi-AZ)
│       ├── elasticache/      # Redis cluster
│       ├── msk/              # Managed Kafka
│       ├── s3/               # Buckets (per-tenant media, exports, backups)
│       ├── ecr/              # Container registries
│       ├── cloudfront/       # CDN for frontend SPAs
│       ├── route53/          # DNS zones + tenant subdomain wildcards
│       ├── acm/              # TLS certificates
│       ├── ses/              # Email sending configuration
│       ├── secrets-manager/  # Application secrets
│       └── vpc/              # Network, subnets, security groups
```

## Kubernetes (Helm Charts)

Each service has its own Helm chart:

```
infrastructure/
├── helm/
│   ├── charts/
│   │   ├── claims-service/
│   │   │   ├── Chart.yaml
│   │   │   ├── values.yaml           # Default values
│   │   │   ├── values-staging.yaml   # Staging overrides
│   │   │   ├── values-prod.yaml      # Production overrides
│   │   │   └── templates/
│   │   │       ├── deployment.yaml
│   │   │       ├── service.yaml
│   │   │       ├── hpa.yaml          # Horizontal pod autoscaler
│   │   │       ├── pdb.yaml          # Pod disruption budget
│   │   │       ├── configmap.yaml
│   │   │       └── ingress.yaml
│   │   ├── contributions-service/
│   │   ├── finance-service/
│   │   ├── tenancy-service/
│   │   ├── rules-engine/
│   │   ├── user-service/
│   │   ├── api-gateway/
│   │   ├── notification-service/
│   │   ├── audit-service/
│   │   ├── file-service/
│   │   ├── live-dashboard/
│   │   ├── chat-service/
│   │   ├── ai-service/
│   │   └── keycloak/
│   └── umbrella/
│       └── medfund-platform/          # Umbrella chart that depends on all service charts
│           ├── Chart.yaml
│           └── values.yaml
```

### Resource Sizing (Production Starting Point)

| Service | Replicas | CPU Request | Memory Request | CPU Limit | Memory Limit |
|---------|----------|-------------|----------------|-----------|--------------|
| API Gateway (Go) | 3 | 250m | 128Mi | 1000m | 512Mi |
| Claims Service (Java) | 2 | 500m | 1Gi | 2000m | 2Gi |
| Contributions Service (Java) | 2 | 500m | 1Gi | 2000m | 2Gi |
| Finance Service (Java) | 2 | 500m | 1Gi | 2000m | 2Gi |
| Tenancy Service (Java) | 1 | 250m | 512Mi | 1000m | 1Gi |
| Rules Engine (Java) | 2 | 500m | 1Gi | 2000m | 2Gi |
| User Service (Java) | 2 | 500m | 1Gi | 2000m | 2Gi |
| Notification Service (Go) | 2 | 250m | 128Mi | 500m | 256Mi |
| Audit Service (Go) | 2 | 250m | 256Mi | 1000m | 512Mi |
| File Service (Go) | 2 | 250m | 256Mi | 1000m | 512Mi |
| Live Dashboard (Elixir) | 2 | 250m | 256Mi | 1000m | 1Gi |
| Chat Service (Elixir) | 2 | 250m | 256Mi | 1000m | 1Gi |
| AI Service (Python) | 2 | 1000m | 2Gi | 4000m | 8Gi |
| Keycloak | 2 | 500m | 1Gi | 2000m | 2Gi |

## CI/CD Pipeline

### GitHub Actions (CI)

```yaml
# Triggered on push to any branch
# .github/workflows/ci.yml

jobs:
  detect-changes:
    # Determines which services changed (path-based)
    # Only builds/tests changed services

  java-services:
    needs: detect-changes
    if: needs.detect-changes.outputs.java == 'true'
    steps:
      - Checkout
      - Setup JDK 21
      - Gradle build + test (with Testcontainers)
      - Checkstyle + SpotBugs
      - Build Docker image
      - Trivy vulnerability scan
      - Push to ECR (if main/staging branch)

  go-services:
    needs: detect-changes
    if: needs.detect-changes.outputs.go == 'true'
    steps:
      - Checkout
      - Setup Go 1.23
      - go build + go test
      - golangci-lint
      - Build Docker image
      - Trivy scan
      - Push to ECR

  elixir-services:
    needs: detect-changes
    if: needs.detect-changes.outputs.elixir == 'true'
    steps:
      - Checkout
      - Setup Elixir 1.17
      - mix deps.get + mix test
      - mix credo (linting)
      - Build Docker image
      - Trivy scan
      - Push to ECR

  python-service:
    needs: detect-changes
    if: needs.detect-changes.outputs.python == 'true'
    steps:
      - Checkout
      - Setup Python 3.12
      - uv install
      - pytest
      - ruff check + ruff format
      - Build Docker image
      - Trivy scan
      - Push to ECR

  angular-web:
    needs: detect-changes
    if: needs.detect-changes.outputs.angular == 'true'
    steps:
      - Checkout
      - Setup Node 22
      - npm ci
      - ng lint
      - ng test (Karma)
      - ng build --configuration production
      - Upload to S3 + invalidate CloudFront (if main/staging)

  flutter-app:
    needs: detect-changes
    if: needs.detect-changes.outputs.flutter == 'true'
    steps:
      - Checkout
      - Setup Flutter
      - flutter analyze
      - flutter test
      - flutter build web (PWA)
      - flutter build apk / flutter build ios (mobile)
      - Upload web to S3
      - Upload mobile to TestFlight / Play Console (staging)
```

### ArgoCD (CD)

```
ArgoCD watches the infrastructure/argocd/ directory:

infrastructure/argocd/
├── staging/
│   ├── claims-service.yaml      # Points to Helm chart + values-staging.yaml
│   ├── contributions-service.yaml
│   └── ...
└── production/
    ├── claims-service.yaml      # Points to Helm chart + values-prod.yaml
    ├── contributions-service.yaml
    └── ...
```

**Deployment flow**:
1. CI builds container image, pushes to ECR with tag `sha-<commit>`
2. CI updates the image tag in `values-staging.yaml` (automated PR)
3. ArgoCD detects change, syncs staging
4. After staging validation, promote to production (manual approval, updates `values-prod.yaml`)
5. ArgoCD syncs production

## Observability Stack

### OpenTelemetry

All services export traces, metrics, and logs via OpenTelemetry SDKs:

| Language | SDK |
|----------|-----|
| Java | opentelemetry-java-agent (auto-instrumentation) |
| Go | go.opentelemetry.io/otel |
| Elixir | opentelemetry_api + opentelemetry_exporter_otlp |
| Python | opentelemetry-python (auto-instrumentation) |

Collected by **OTel Collector** deployed as a DaemonSet, forwarded to:
- **Grafana Mimir** — Metrics (Prometheus-compatible)
- **Grafana Tempo** — Distributed traces
- **Grafana Loki** — Log aggregation

### Key Dashboards
- **Platform Overview**: Request rate, error rate, latency (RED metrics) per service
- **Tenant Health**: Per-tenant request volume, error rates, latency
- **Claims Pipeline**: Claim submission rate, adjudication time, approval rate
- **Finance**: Payment run duration, pending amounts, provider balance distribution
- **AI Service**: Prediction latency, confidence distribution, override rate
- **Infrastructure**: Pod CPU/memory, Kafka lag, PostgreSQL connections, Redis hit rate

### Alerting
- Service error rate > 1% → PagerDuty
- Kafka consumer lag > 10k messages → Slack
- PostgreSQL connection pool > 80% → Slack
- AI Service latency p99 > 5s → Slack
- Failed tenant schema migration → PagerDuty

## Security

### Network
- VPC with private subnets for all services
- Public subnets only for ALB/CloudFront
- Security groups: least-privilege inter-service access
- All traffic encrypted in transit (TLS 1.3)

### Secrets
- **AWS Secrets Manager** for production secrets
- **SOPS + age** for secrets in Git (development)
- Kubernetes **ExternalSecrets** operator syncs AWS Secrets → K8s Secrets

### Data
- PostgreSQL encryption at rest (AWS RDS default)
- S3 server-side encryption (SSE-S3)
- PHI/PII columns encrypted at application level (AES-256-GCM) using per-tenant encryption keys
- Per-tenant encryption keys stored in AWS KMS

### Authentication & Authorization
- **Keycloak** manages all identity
- **Per-tenant Keycloak realm** isolates user directories
- **JWT in HTTP-only cookies** (`__Host-at`, `__Host-rt`) — tokens never in localStorage. See architecture.md for full auth flow
- **Mobile fallback**: Flutter apps use `Authorization: Bearer` header with tokens in `flutter_secure_storage`
- **CSRF protection**: `SameSite=Strict` cookies + double-submit CSRF token for `Lax` scenarios
- **MFA** enforced for admin roles
- Service-to-service auth via **mTLS** (Kubernetes service mesh) or **OAuth2 client credentials**
- **Security event logging**: All auth events (login, logout, failed attempts, MFA, password changes, role changes, impersonation) logged to per-tenant `security_events` table via Keycloak event listener SPI + Kafka

### Compliance
- Immutable audit logs (append-only, retained for 7 years minimum)
- Data residency: tenant data can be pinned to specific regions via RDS configuration
- GDPR: tenant data export and deletion workflows
- Regular penetration testing schedule
