-- Phase 15 §6 (I18) IT baseline. Scoped to what Ifrs17CohortLockInIT drives:
-- ifrs17_portfolio + ifrs17_cohort (widened with locked_in columns) plus a
-- public.tenant_yield_curve shim + the two boot shims (tenants +
-- scheduled_job_configs) every user-service IT needs.
--
-- Deliberately no policy tables — the live path is not exercised end-to-end
-- here; the IT drives Ifrs17CohortLockInService directly.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS tenants (
    id          UUID        PRIMARY KEY,
    schema_name VARCHAR(63) NOT NULL DEFAULT 'public'
);

CREATE TABLE IF NOT EXISTS scheduled_job_configs (
    id                 UUID        PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN     NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS ifrs17_portfolio (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    insurance_line  VARCHAR(50),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id        UUID,
    actor_email     VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS ifrs17_cohort (
    id                              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id                    UUID         NOT NULL REFERENCES ifrs17_portfolio(id) ON DELETE RESTRICT,
    cohort_year                     INT          NOT NULL,
    cohort_type                     VARCHAR(20)  NOT NULL CHECK (cohort_type IN ('ONEROUS', 'NON_ONEROUS', 'UNCERTAIN')),
    name                            VARCHAR(200) NOT NULL,
    is_active                       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id                        UUID,
    actor_email                     VARCHAR(255),
    locked_in_yield_curve_snapshot  JSONB,
    locked_in_at                    TIMESTAMPTZ,
    CONSTRAINT uq_ifrs17_cohort UNIQUE (portfolio_id, cohort_year, cohort_type)
);

CREATE INDEX IF NOT EXISTS ix_ifrs17_cohort_locked_in
    ON ifrs17_cohort (locked_in_at) WHERE locked_in_at IS NOT NULL;

-- public.tenant_yield_curve — Phase 2 V153 shape trimmed to what the lock-in
-- read needs; tenant scoping is by tenant_id column, no FK to public.tenants
-- so this IT can seed rows without a matching tenants row (we do seed one
-- but the FK would still add churn on schema drift).
CREATE TABLE IF NOT EXISTS public.tenant_yield_curve (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    tenor_months        INT          NOT NULL,
    spot_rate           NUMERIC(9,7) NOT NULL,
    effective_from      DATE         NOT NULL DEFAULT CURRENT_DATE,
    effective_to        DATE,
    source              VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by          UUID,
    updated_by_email    VARCHAR(200)
);
