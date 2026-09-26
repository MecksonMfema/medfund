-- Minimal test schema for TenantHighCostClaimantConfigService integration
-- tests. Mirrors the public-platform shapes the service touches (V132 config
-- table + the Tenant entity columns its repositories map) plus the shared
-- infra shims: a `public_role` granted CRUD on all public tables (so the
-- tenant-scoped SET ROLE doesn't 42501) and an empty `scheduled_job_configs`
-- so the shared scheduler no-ops.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Role for the tenant-scoped SET ROLE ─────────────────────────────
DO $$ BEGIN
    CREATE ROLE public_role;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
GRANT public_role TO CURRENT_USER;

-- ── tenants — full column set mapped by the Tenant entity ─────────────
CREATE TABLE tenants (
    id                    UUID         PRIMARY KEY,
    name                  VARCHAR(200),
    slug                  VARCHAR(63)  NOT NULL UNIQUE,
    domain                VARCHAR(200),
    schema_name           VARCHAR(63)  NOT NULL DEFAULT 'public',
    plan_id               UUID,
    status                VARCHAR(32)  NOT NULL DEFAULT 'active',
    settings              JSONB,
    branding              JSONB,
    contact_email         VARCHAR(255),
    country_code          VARCHAR(2),
    timezone              VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    membership_model      VARCHAR(32)  NOT NULL DEFAULT 'DEFAULT',
    pricing_model         VARCHAR(32)  NOT NULL DEFAULT 'AGE_GROUP',
    member_number_scheme  VARCHAR(32)  NOT NULL DEFAULT 'INDEPENDENT',
    keycloak_realm        VARCHAR(100),
    jurisdiction_code     VARCHAR(10),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

INSERT INTO tenants (id, slug, schema_name)
VALUES ('00000000-0000-4000-8000-000000000001', 'it', 'public');

-- ── V132 shape ──────────────────────────────────────────────────────
CREATE TABLE tenant_high_cost_claimant_config (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    threshold_amount  NUMERIC(19,4) NOT NULL,
    currency_code     CHAR(3)      NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    CONSTRAINT uq_tenant_high_cost_config UNIQUE (tenant_id)
);

GRANT USAGE ON SCHEMA public TO public_role;
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO public_role;
