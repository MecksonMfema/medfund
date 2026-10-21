-- Phase 15 §7 (I29) IT baseline. Scoped to what
-- Ifrs17OpeningBalanceSeedIT drives: ifrs17_portfolio + ifrs17_cohort +
-- ifrs17_opening_balance_seed, plus the two boot shims (tenants +
-- scheduled_job_configs) every user-service IT needs.
--
-- Distinct flyway.table (see IT header) lets this IT coexist with peer
-- ITs on the shared JVM-scoped Postgres testcontainer without a
-- boot-order race.

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

-- Wider shape carries the Phase 6 locked_in_* columns even though this IT
-- doesn't exercise them. Reason: the shared JVM-scoped Postgres container
-- means one IT's `CREATE TABLE IF NOT EXISTS ifrs17_cohort` becomes a
-- no-op for every peer IT that reuses the same table, so we must land
-- the widest shape (Phase 6) here or peer ITs' downstream DDL (like the
-- Phase 6 partial index on locked_in_at) breaks when this IT runs first.
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

CREATE TABLE IF NOT EXISTS ifrs17_opening_balance_seed (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id   UUID          NOT NULL,
    cohort_id      UUID          NOT NULL REFERENCES ifrs17_cohort(id) ON DELETE CASCADE,
    currency       VARCHAR(3)    NOT NULL,
    balance_type   VARCHAR(3)    NOT NULL CHECK (balance_type IN ('LRC', 'LIC')),
    amount         NUMERIC(18,2) NOT NULL,
    effective_from DATE          NOT NULL,
    reason_note    TEXT          NOT NULL,
    actor_id       UUID          NOT NULL,
    actor_email    VARCHAR(200)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ifrs17_opening_balance_seed_uq
        UNIQUE (portfolio_id, cohort_id, currency, balance_type, effective_from)
);

CREATE INDEX IF NOT EXISTS ix_ifrs17_opening_balance_seed_lookup
    ON ifrs17_opening_balance_seed (cohort_id, currency, balance_type, effective_from DESC);
