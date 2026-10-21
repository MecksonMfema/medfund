-- ── Phase 13 §B Phase 6 — earning_schedule slice for the closure lifecycle IT ──
-- Mirrors the shape of tenancy-service V109__earning_schedule.sql +
-- V115__earning_schedule_closure_ref.sql. Scoped to the columns the
-- {@code EarningScheduleClosureService} lifecycle methods (closeOut / freeze
-- / resume / reinstate) actually touch — no IFRS 17 references, no matview.
--
-- Lives in `public` so the IT can talk to the tables without a tenant-schema
-- routing dance; tenant-context is asserted at the reactor level via
-- {@code TenantTestContext}. See BalanceQueryRepositoryBadDebtsIT for the
-- same slice-test pattern.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Platform shims for the shared/ infra bits that boot with the app ──
CREATE TABLE tenants (
    id           UUID         PRIMARY KEY,
    schema_name  VARCHAR(63)  NOT NULL DEFAULT 'public'
);

CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

-- TenantRuleLoader.ensureLoaded reads this on every priced contribution;
-- empty tables → the loader falls through cleanly.
CREATE TABLE tenant_rules (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID          NOT NULL,
    enabled      BOOLEAN       NOT NULL DEFAULT true,
    priority     INTEGER       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    rule_type    VARCHAR(64),
    name         VARCHAR(200),
    description  TEXT,
    definition   TEXT
);

CREATE TABLE staff_users (
    id     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    email  VARCHAR(200)
);

-- ── earning_schedule slice ────────────────────────────────────────────
-- Column set matches tenancy-service V109 + V115. Constraints kept 1:1 so
-- the IT catches a check-constraint regression (e.g. earned=0 must satisfy
-- chk_earned_valid for closure rows).
CREATE TABLE earning_schedule (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id             UUID           NOT NULL,
    policy_source         VARCHAR(30)    NOT NULL CHECK (policy_source IN (
                              'LIFE_POLICY', 'FUNERAL_POLICY', 'DISABILITY_POLICY',
                              'TRAVEL_POLICY', 'VEHICLE_POLICY', 'PROPERTY_POLICY',
                              'CONTRIBUTION'
                          )),
    insurance_line        VARCHAR(20)    NOT NULL,
    period_start          DATE           NOT NULL,
    period_end            DATE           NOT NULL,
    written_amount        NUMERIC(19, 4) NOT NULL,
    earned_at_period_end  NUMERIC(19, 4),
    currency_code         CHAR(3)        NOT NULL,
    is_endorsement        BOOLEAN        NOT NULL DEFAULT FALSE,
    endorsement_id        UUID,
    portfolio_id          UUID,
    cohort_id             UUID,
    earning_method        VARCHAR(30)    NOT NULL,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    actor_id              UUID,
    actor_email           VARCHAR(255),
    -- V115 additions:
    closure_ref           UUID,
    is_closure            BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_period_bounds CHECK (period_end >= period_start),
    CONSTRAINT chk_written_nonneg CHECK (written_amount >= 0 OR is_endorsement = TRUE),
    CONSTRAINT chk_earned_valid CHECK (
        earned_at_period_end IS NULL
        OR is_endorsement = TRUE
        OR (earned_at_period_end BETWEEN 0 AND written_amount)
    )
);

CREATE UNIQUE INDEX ux_earning_schedule_key ON earning_schedule
    (policy_id, policy_source, period_start,
     COALESCE(endorsement_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX ix_earning_schedule_period       ON earning_schedule (period_start, period_end);
CREATE INDEX ix_earning_schedule_policy       ON earning_schedule (policy_id, policy_source);
CREATE INDEX ix_earning_schedule_unclosed
    ON earning_schedule (period_end) WHERE earned_at_period_end IS NULL;
CREATE INDEX ix_earning_schedule_closure_ref
    ON earning_schedule (closure_ref) WHERE closure_ref IS NOT NULL;
CREATE INDEX ix_earning_schedule_closure_policy
    ON earning_schedule (policy_id, policy_source) WHERE is_closure = TRUE;

-- earning_schedule_run — closure lifecycle methods don't write here (only the
-- nightly executor does), but the repository bean loads with the service and
-- needs the table to bind at startup. Phase 13 §C Phase 7 adds
-- contrib_presence_refresh_at so refreshMemberContributionPresence can stamp
-- freshness on the newest run row.
CREATE TABLE earning_schedule_run (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID         NOT NULL,
    run_kind                    VARCHAR(30)  NOT NULL,
    trigger_reference           UUID,
    status                      VARCHAR(20)  NOT NULL,
    started_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at                 TIMESTAMPTZ,
    last_heartbeat_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_processed_policy_id    UUID,
    policies_processed          INT          NOT NULL DEFAULT 0,
    periods_written             INT          NOT NULL DEFAULT 0,
    error_message               TEXT,
    contrib_presence_refresh_at TIMESTAMPTZ                     -- V114
);

-- ── Phase 13 §C Phase 7 — contributions + member_contribution_presence ──
-- Slim contributions table for the matview to project. Only the columns
-- the matview SELECTs (member_id, period_start) are needed; the rest are
-- deliberately absent to keep this IT baseline scoped.
CREATE TABLE contributions (
    id            UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id     UUID,
    period_start  DATE,
    period_end    DATE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_contributions_member ON contributions (member_id);

-- Matview + UNIQUE composite index — same shape as tenancy-service V114.
CREATE MATERIALIZED VIEW member_contribution_presence AS
SELECT DISTINCT
    member_id,
    DATE_TRUNC('month', period_start)::DATE AS contribution_month
FROM contributions
WHERE member_id  IS NOT NULL
  AND period_start IS NOT NULL;

CREATE UNIQUE INDEX ux_member_contribution_presence
    ON member_contribution_presence (member_id, contribution_month);
