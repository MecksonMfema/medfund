-- Minimal test schema for the Phase 4 financial-reporting integration
-- tests. Mirrors the columns the claims-service report repositories join
-- (claims, schemes, providers, groups, members, rejection_reasons,
-- pre_authorizations) plus the shared-infra tables the report stack reads
-- directly (tenant_report_config, tenant_currency_config, exchange_rates,
-- tenant_high_cost_claimant_config).
--
-- Lives in `public` so tests do not have to spin a tenant schema; tenant
-- isolation is asserted via the audit envelope / report warnings rather
-- than schema selection. Follows the finance-service test-migration
-- pattern: a `tenants` row so TenantAwareConnectionFactory.lookupSchemaName
-- resolves to `public`, an empty `scheduled_job_configs` so
-- ScheduledJobRepository no-ops on the dispatcher tick, and a
-- `public_role` (granted CRUD on all public tables) so SET ROLE
-- public_role on the tenant-scoped connection acquisition doesn't 500.
--
-- The ITs TRUNCATE the report tables between tests (public_role lacks
-- TRUNCATE, so truncation runs on the session user, never the tenant
-- role) and reseed the tenant default currency.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Role for the tenant-scoped SET ROLE ─────────────────────────────
-- Same collapse as finance-service V001: the "tenant" IS the public
-- schema here, so the role name is public_role with broad grants.
DO $$ BEGIN
    CREATE ROLE public_role;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
GRANT public_role TO CURRENT_USER;

-- ── Shared infra shims ───────────────────────────────────────────────
CREATE TABLE tenants (
    id           UUID         PRIMARY KEY,
    slug         VARCHAR(63)  NOT NULL DEFAULT 'it',
    schema_name  VARCHAR(63)  NOT NULL DEFAULT 'public'
);

CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

-- Seed the IT tenant. Every seeded row carries this tenant id.
INSERT INTO tenants (id, slug, schema_name)
VALUES ('00000000-0000-4000-8000-000000000001', 'it', 'public');

-- ── Currency registry + per-tenant currency config ───────────────────
CREATE TABLE currencies (
    code            CHAR(3)      PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    symbol          VARCHAR(10)  NOT NULL,
    decimal_places  SMALLINT     NOT NULL DEFAULT 2,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO currencies (code, name, symbol, decimal_places) VALUES
    ('USD', 'United States Dollar', '$', 2),
    ('ZMW', 'Zambian Kwacha',       'K', 2),
    ('EUR', 'Euro',                 '€', 2),
    ('KES', 'Kenyan Shilling',      'KSh', 2);

CREATE TABLE tenant_currency_config (
    id            UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID    NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    currency_code CHAR(3) NOT NULL,
    is_default    BOOLEAN NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, currency_code)
);

INSERT INTO tenant_currency_config (tenant_id, currency_code, is_default, is_active)
VALUES ('00000000-0000-4000-8000-000000000001', 'USD', TRUE, TRUE);

-- ── FX rates (V112 shape; read by FxRateReader / FxConverter) ────────
CREATE TABLE exchange_rates (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency   CHAR(3)        NOT NULL REFERENCES currencies(code),
    quote_currency  CHAR(3)        NOT NULL REFERENCES currencies(code),
    rate            DECIMAL(19,10) NOT NULL CHECK (rate > 0),
    rate_date       DATE           NOT NULL,
    source          VARCHAR(50)    NOT NULL DEFAULT 'manual',
    tenant_id       UUID           REFERENCES tenants(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by      UUID,
    CONSTRAINT chk_exchange_rates_distinct CHECK (base_currency <> quote_currency),
    CONSTRAINT uq_exchange_rates UNIQUE (base_currency, quote_currency, rate_date, source, tenant_id)
);

-- ── Report enablement + high-cost threshold (V130 / V132 shapes) ─────
CREATE TABLE tenant_report_config (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    report_key   VARCHAR(80)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   UUID,
    CONSTRAINT uq_tenant_report_config UNIQUE (tenant_id, report_key)
);

CREATE TABLE tenant_high_cost_claimant_config (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    threshold_amount  NUMERIC(19,4) NOT NULL,
    currency_code     CHAR(3)      NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    CONSTRAINT uq_tenant_high_cost_config UNIQUE (tenant_id)
);

-- ── Claims-domain join targets ───────────────────────────────────────
CREATE TABLE schemes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE providers (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE groups (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE members (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name     VARCHAR(200),
    last_name      VARCHAR(200),
    member_number  VARCHAR(64),
    group_id       UUID,
    scheme_id      UUID,
    status         VARCHAR(32)  NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE rejection_reasons (
    code         VARCHAR(20) PRIMARY KEY,
    description  TEXT        NOT NULL,
    category     VARCHAR(50)
);

INSERT INTO rejection_reasons (code, category, description) VALUES
    ('R01', 'ELIGIBILITY', 'Member not active'),
    ('R02', 'WAITING_PERIOD', 'Within waiting period for benefit category'),
    ('R03', 'BENEFIT', 'Benefit limit exhausted');

-- ── pre_authorizations (V014 shape, columns used by the repo) ────────
CREATE TABLE pre_authorizations (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_number      VARCHAR(50)   NOT NULL UNIQUE,
    member_id        UUID          NOT NULL,
    provider_id      UUID          NOT NULL,
    status           VARCHAR(32)   NOT NULL DEFAULT 'pending'
                       CHECK (status IN ('pending', 'approved', 'rejected', 'expired')),
    requested_amount NUMERIC(19,4),
    approved_amount  NUMERIC(19,4),
    currency_code    VARCHAR(3),
    requested_date   DATE          NOT NULL DEFAULT CURRENT_DATE,
    decision_date    DATE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ── claims ───────────────────────────────────────────────────────────
CREATE TABLE claims (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_number     VARCHAR(64)   NOT NULL UNIQUE,
    member_id        UUID          NOT NULL,
    provider_id      UUID          NOT NULL,
    scheme_id        UUID,
    status           VARCHAR(32)   NOT NULL DEFAULT 'submitted',
    rejection_reason VARCHAR(20),
    currency_code    VARCHAR(3)    NOT NULL,
    claimed_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
    approved_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,
    paid_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    service_date     DATE          NOT NULL,
    submission_date  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    adjudicated_at   TIMESTAMPTZ,
    insurance_line   VARCHAR(32)   NOT NULL DEFAULT 'HEALTH',
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_claims_member     ON claims(member_id);
CREATE INDEX idx_claims_scheme     ON claims(scheme_id);
CREATE INDEX idx_claims_adjudicated ON claims(adjudicated_at);

-- Grant CRUD on all seeded tables to the tenant role. Sequences added
-- speculatively — none of the DDL above uses them today but pgcrypto's
-- gen_random_uuid path may pull one via extension internals.
GRANT USAGE ON SCHEMA public TO public_role;
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO public_role;
