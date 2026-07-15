# Multi-Tenancy Architecture

## Overview

InsureFlow uses **schema-per-tenant** multi-tenancy in PostgreSQL. Each tenant (an insurance carrier — a medical aid society, life/funeral insurer, short-term insurer, or a combined multi-line carrier) gets its own database schema with identical table structures but completely isolated data. Shared platform data lives in the `public` schema.

> Many examples in this doc use "medical aid" because that is the vertical currently in production. Everything about the schema-per-tenant design, tenant resolution, and provider multi-tenancy applies equally to any insurance line the platform supports.

## Why Schema-Per-Tenant

| Approach | Isolation | Complexity | Performance | Chosen? |
|----------|-----------|-----------|-------------|---------|
| Shared tables (row-level) | Low — one bad query leaks data | Low | Degrades as tenants grow | No |
| Schema-per-tenant | High — schema boundary enforced by DB | Medium | Good — indexes are per-schema | **Yes** |
| Database-per-tenant | Highest | High — connection management nightmare | Best | No (overkill for now) |

Schema-per-tenant gives strong data isolation without the operational overhead of separate databases. PostgreSQL handles hundreds of schemas efficiently.

## Tenant Lifecycle

### Provisioning (New Tenant)

```
Super Admin creates tenant via /super-admin/tenants/new
      │
      ▼
  Tenancy Service (Java):
      │
      ├── 1. Insert tenant record in public.tenants
      ├── 2. CREATE SCHEMA tenant_{uuid}
      ├── 3. Run Flyway migrations against new schema
      ├── 4. Seed default data:
      │       - Default schemes and benefits
      │       - Default business rules (Drools)
      │       - Default notification templates
      │       - Default currency configuration
      ├── 5. Create Keycloak realm for tenant
      │       - Configure OIDC client for Angular + Flutter
      │       - Create default roles
      │       - Create tenant admin user
      ├── 6. Publish medfund.tenants.provisioned Kafka event
      └── 7. Return tenant credentials to super admin
```

### Tenant Resolution

Every request must be resolved to a tenant before processing. Resolution order:

```
Request arrives at API Gateway (Go/Fiber)
      │
      ▼
  1. Check X-Tenant-ID header (service-to-service calls)
      │ Not found?
      ▼
  2. Extract from JWT claim (tenant_id in Keycloak token)
      │ Not found?
      ▼
  3. Extract from subdomain (e.g., acme.medfund.healthcare → lookup tenant by domain)
      │ Not found?
      ▼
  4. Reject request with 400 "Tenant not resolved"
```

The resolved `tenant_id` is:
- Set as a request header (`X-Tenant-ID`) for downstream services
- Available in every service's request context
- Used to set the PostgreSQL `search_path` for the duration of the request

### Per-Service Tenant Context

#### Java (Spring Boot WebFlux)
```java
// Reactive WebFilter sets tenant context per request
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantWebFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String tenantId = exchange.getRequest().getHeaders().getFirst("X-Tenant-ID");
        if (tenantId == null) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }
        // Store tenant in reactive context (not ThreadLocal — WebFlux is non-blocking)
        return chain.filter(exchange)
            .contextWrite(ctx -> ctx.put(TenantContext.KEY, tenantId));
    }
}

// R2DBC connection factory sets schema from reactive context
@Component
public class TenantAwareConnectionFactory implements ConnectionFactory {
    private final ConnectionFactory delegate;

    @Override
    public Publisher<? extends Connection> create() {
        return Mono.deferContextual(ctx -> {
            String tenantId = ctx.get(TenantContext.KEY);
            return Mono.from(delegate.create())
                .flatMap(conn -> Mono.from(
                    conn.createStatement("SET search_path TO tenant_" + tenantId)
                        .execute()
                ).thenReturn(conn));
        });
    }
}
```

#### Go (Fiber)
```go
// Middleware extracts tenant and sets DB schema
func TenantMiddleware() fiber.Handler {
    return func(c *fiber.Ctx) error {
        tenantID := c.Get("X-Tenant-ID")
        if tenantID == "" {
            return c.Status(400).JSON(fiber.Map{"error": "tenant not resolved"})
        }
        c.Locals("tenant_id", tenantID)
        // DB queries use: SET search_path TO tenant_{tenantID}
        return c.Next()
    }
}
```

#### Elixir (Phoenix)
```elixir
# Plug sets the Ecto prefix for the connection
defmodule MascaWeb.TenantPlug do
  def call(conn, _opts) do
    tenant_id = get_req_header(conn, "x-tenant-id") |> List.first()
    Ecto.put_meta(conn, prefix: "tenant_#{tenant_id}")
    assign(conn, :tenant_id, tenant_id)
  end
end
```

#### Python (FastAPI)
```python
# Dependency injects tenant-scoped DB session
async def get_tenant_session(request: Request) -> AsyncSession:
    tenant_id = request.headers.get("X-Tenant-ID")
    session = get_session()
    await session.execute(text(f"SET search_path TO tenant_{tenant_id}"))
    return session
```

## Schema Management

### Flyway Migrations

Each migration is run against **every tenant schema** during deployment:

```
flyway/
├── V001__baseline.sql              # Initial schema (all tables)
├── V002__add_ai_predictions.sql    # New feature
├── V003__add_currency_fields.sql   # Multi-currency support
└── R__seed_default_data.sql        # Repeatable: default data
```

Deployment pipeline:
```
1. Apply migration to public schema (shared tables)
2. For each tenant in public.tenants WHERE is_active = TRUE:
     SET search_path TO tenant_{id};
     Run pending Flyway migrations;
3. Log migration results per tenant
4. Alert on any tenant migration failure (do not block others)
```

### Row-Level Security (Defense in Depth)

Even with schema isolation, RLS policies provide a second layer of protection:

```sql
-- Applied to every table in every tenant schema
ALTER TABLE claims ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON claims
    USING (current_setting('app.current_tenant')::uuid = tenant_id);
```

This catches bugs where a service accidentally connects to the wrong schema.

## Portal Architecture

### Super Admin Portal

**Access**: Platform operators only (not tenant users). Separate Keycloak realm.

| Feature | Description |
|---------|-------------|
| **Tenant Management** | Create, suspend, delete tenants. View tenant health/usage metrics |
| **Platform Analytics** | Cross-tenant metrics: total claims, total members, revenue, growth |
| **Feature Flags** | Enable/disable features per tenant (e.g., AI auto-adjudication, chat) |
| **Plan Management** | Define tenant subscription tiers (member limits, storage limits, feature access) |
| **System Configuration** | Global settings, exchange rate sources, email provider config |
| **User Impersonation** | Log into any tenant as their admin (for support, with audit trail) |
| **Platform Health** | Service health, Kafka lag, database metrics, error rates |

### Tenant Admin Portal

**Access**: Tenant administrators. Scoped to their tenant only.

| Feature | Description |
|---------|-------------|
| **Tenant Settings** | Organization details, branding (logo, colors), custom domain |
| **User Management** | Create/manage staff users (claims clerks, finance, etc.) |
| **Role Configuration** | Assign roles and permissions to staff |
| **Business Rules** | Configure adjudication rules, billing rules, waiting periods, benefit limits via UI |
| **Scheme Management** | Create/edit schemes, benefits, age groups, pricing |
| **Currency Settings** | Configure supported currencies, default currency, exchange rate sources |
| **Notification Templates** | Customize email/SMS templates with tenant branding |
| **Provider Network** | Manage which providers are in-network for this tenant |
| **Membership Model** | Configure `GROUP_ONLY`, `INDIVIDUAL_ONLY`, or `BOTH` — controls enrollment flows, billing, and portal access |
| **AI Configuration** | Set auto-adjudication thresholds, enable/disable AI features |
| **Audit Log** | View all actions taken within the tenant |
| **Reports** | Tenant-level analytics, financial summaries, member statistics |
| **Data Export** | GDPR/compliance data export for members |

### Provider Portal (Angular Web + Flutter Mobile/Web)

**Access**: Healthcare providers. A provider can serve multiple tenants (medical aids).

| Feature | Description |
|---------|-------------|
| **Multi-Tenant Dashboard** | See all medical aids the provider is registered with, switch context between them |
| **Claim Submission** | Submit claims against any of their registered medical aids |
| **Claim Tracking** | Track claim status per medical aid, see adjudication outcomes |
| **Pre-Authorization** | Request pre-authorization per medical aid's rules |
| **Payment Tracking** | View payments from each medical aid, download remittance advice |
| **Provider Balance** | See outstanding balance per medical aid |
| **Member Verification** | Verify member eligibility and benefits in real-time (scan QR from digital card) |
| **Tariff Lookup** | Search tariff codes and rates per medical aid |
| **AI Insights** | View claim approval rate, common rejections, tariff suggestions |
| **Documents** | Upload/download claim supporting documents |
| **Profile Management** | Banking details, tax clearance, practice information |
| **Notifications** | Payment confirmations, claim status updates |

**Provider Multi-Tenancy Note**: A provider exists in multiple tenant schemas. The provider's profile is replicated across tenant schemas they're registered with. When a provider logs in, they see a tenant switcher to select which medical aid context they're working in.

### Member Portal (Flutter Mobile + Web)

**Access**: Insurance members and their dependants.

See architecture.md Flutter section for full feature list. Key tenant-aware features:
- Member sees only their tenant's branding and schemes
- Benefits and balances reflect their specific scheme
- Digital membership card includes tenant logo and QR code
- Support chat connects to their tenant's staff

## Tenant Data Model

### public.tenants
```sql
CREATE TABLE public.tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,          -- "Zimbabwe Medical Aid Society"
    slug            VARCHAR(100) NOT NULL UNIQUE,    -- "zmmas" (used in schema name + subdomain)
    domain          VARCHAR(255),                    -- "zmmas.medfund.healthcare" or custom domain
    schema_name     VARCHAR(100) NOT NULL UNIQUE,    -- "tenant_<uuid>"
    plan_id         UUID REFERENCES plans(id),       -- Subscription tier
    status          VARCHAR(20) NOT NULL DEFAULT 'active',  -- active, suspended, archived
    settings        JSONB NOT NULL DEFAULT '{}',     -- Extensible tenant settings
    branding        JSONB NOT NULL DEFAULT '{}',     -- Logo URL, colors, fonts
    contact_email   VARCHAR(255) NOT NULL,
    country_code    CHAR(2) NOT NULL,               -- ISO 3166-1 (ZW, BW, ZA, etc.)
    timezone        VARCHAR(50) NOT NULL DEFAULT 'UTC',
    membership_model VARCHAR(20) NOT NULL DEFAULT 'BOTH',
                    -- 'GROUP_ONLY': only corporate/employer group enrollment
                    -- 'INDIVIDUAL_ONLY': only individual self-registration
                    -- 'BOTH': supports group and individual members
    keycloak_realm  VARCHAR(100) NOT NULL,          -- Keycloak realm name
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### public.plans
```sql
CREATE TABLE public.plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,   -- "Starter", "Professional", "Enterprise"
    max_members     INTEGER,                 -- NULL = unlimited
    max_providers   INTEGER,
    max_storage_gb  INTEGER,
    features        JSONB NOT NULL,          -- {"ai_adjudication": true, "chat": false, ...}
    price           DECIMAL(19,4),
    currency_code   CHAR(3),
    billing_cycle   VARCHAR(20),             -- "monthly", "annual"
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

## Cross-Tenant Queries (Super Admin Only)

For platform-level analytics, use the `analytics` schema on a read replica:

```sql
-- Materialized view refreshed daily
CREATE MATERIALIZED VIEW analytics.platform_summary AS
SELECT
    t.id AS tenant_id,
    t.name AS tenant_name,
    (SELECT count(*) FROM tenant_schema.members) AS member_count,
    (SELECT count(*) FROM tenant_schema.claims WHERE created_at > NOW() - INTERVAL '30 days') AS claims_30d,
    (SELECT sum(amount) FROM tenant_schema.payments WHERE committed = TRUE AND created_at > NOW() - INTERVAL '30 days') AS payments_30d
FROM public.tenants t
WHERE t.status = 'active';
```

In practice, this is implemented as a scheduled job that iterates over tenant schemas and aggregates into the analytics schema.
