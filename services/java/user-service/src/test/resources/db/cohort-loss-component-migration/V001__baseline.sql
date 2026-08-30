-- Phase 15 §5 (I19) IT baseline. Scoped to what CohortLossComponentIT drives:
-- ifrs17_portfolio + ifrs17_cohort + cohort_loss_component_history + matview,
-- plus the two shims (tenants + scheduled_job_configs) every user-service IT
-- needs so security wiring + startup ticks don't error out.
--
-- Deliberately no policy FK cascades — this IT never touches life_policies
-- et al, and force-widening the shared db/policy-lifecycle-migration folder
-- would couple unrelated ITs together.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS tenants (
    id          UUID        PRIMARY KEY,
    schema_name VARCHAR(63) NOT NULL DEFAULT 'public'
);

-- Empty shim so ScheduledJobRepository's startup tick doesn't 42P01
-- during context boot.
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
-- Phase 6 partial index on locked_in_at) breaks when this IT runs first
-- alphabetically. Retro-fitted by Phase 7 (2026-08-28) after the full
-- user-service suite exposed the pre-existing collision.
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

CREATE TABLE IF NOT EXISTS cohort_loss_component_history (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id     UUID         NOT NULL REFERENCES ifrs17_cohort(id) ON DELETE CASCADE,
    effective_at  TIMESTAMPTZ  NOT NULL,
    movement_type VARCHAR(50)  NOT NULL
        CHECK (movement_type IN (
            'INITIAL_RECOGNITION',
            'RELEASE',
            'REVERSAL',
            'RECLASSIFICATION_TO_NON_ONEROUS')),
    amount        NUMERIC(18,2) NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    source_run_id UUID,
    reason_note   TEXT,
    actor_id      UUID,
    actor_email   VARCHAR(200),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_cohort_loss_component_history_lookup
    ON cohort_loss_component_history (cohort_id, effective_at DESC);

CREATE MATERIALIZED VIEW IF NOT EXISTS cohort_loss_component_current AS
SELECT
    c.portfolio_id,
    h.cohort_id,
    h.currency,
    COALESCE(SUM(
        CASE h.movement_type
            WHEN 'INITIAL_RECOGNITION' THEN h.amount
            WHEN 'RELEASE' THEN -h.amount
            WHEN 'REVERSAL' THEN -h.amount
            WHEN 'RECLASSIFICATION_TO_NON_ONEROUS' THEN -h.amount
            ELSE 0
        END
    ), 0) AS balance
FROM cohort_loss_component_history h
JOIN ifrs17_cohort c ON c.id = h.cohort_id
GROUP BY c.portfolio_id, h.cohort_id, h.currency;

CREATE UNIQUE INDEX IF NOT EXISTS ux_cohort_loss_component_current
    ON cohort_loss_component_current (cohort_id, currency);
