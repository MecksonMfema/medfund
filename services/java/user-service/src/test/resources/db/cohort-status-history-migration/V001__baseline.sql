-- Phase 15 §4 (I11) IT baseline. Scoped to what CohortStatusHistoryIT drives:
-- the bare ifrs17_portfolio + ifrs17_cohort + cohort_status_history tables +
-- the two shims (tenants + scheduled_job_configs) every user-service IT
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
-- doesn't exercise them. Same reasoning as the sibling
-- cohort-loss-component-migration baseline — the shared JVM Postgres
-- container means the first IT to run its baseline "wins" the shape;
-- narrow shapes here poison Phase 6's downstream partial-index DDL.
-- Retro-fitted by Phase 7 (2026-08-28) after the full user-service
-- suite exposed the pre-existing collision.
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

CREATE TABLE IF NOT EXISTS cohort_status_history (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id          UUID        NOT NULL REFERENCES ifrs17_cohort(id) ON DELETE CASCADE,
    from_status        VARCHAR(20) NOT NULL,
    to_status          VARCHAR(20) NOT NULL
        CHECK (to_status IN ('ONEROUS', 'NON_ONEROUS', 'UNCERTAIN')),
    transition_reason  VARCHAR(50) NOT NULL
        CHECK (transition_reason IN (
            'AUTO_TEST_FAILED',
            'AUTO_TEST_RECOVERED',
            'MANUAL_OVERRIDE',
            'INITIAL_CLASSIFICATION')),
    transition_source  VARCHAR(10) NOT NULL
        CHECK (transition_source IN ('AUTO', 'MANUAL')),
    source_run_id      UUID,
    effective_at       TIMESTAMPTZ NOT NULL,
    reason_note        TEXT,
    actor_id           UUID,
    actor_email        VARCHAR(200),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_cohort_status_history_lookup
    ON cohort_status_history (cohort_id, effective_at DESC);
