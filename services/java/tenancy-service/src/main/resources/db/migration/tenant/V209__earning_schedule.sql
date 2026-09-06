-- ── Phase 12 §A: earning_schedule + executor progress + HEALTH new-business view ──
-- Per-policy-per-period grain (U7): one row per (policy, period, endorsement?)
-- The idempotency guard is a UNIQUE index that treats NULL endorsement_id as
-- a sentinel UUID so the executor + PolicyIssuedConsumer never write dupes on
-- replay (U10). Native-currency storage per U8 — currency is fixed at row
-- creation and never re-denominated; reports handle FX at query time.
--
-- earning_schedule_run tracks executor progress (grill note 5) so admins can
-- resume a canceled or crashed run from lastProcessedPolicyId.
--
-- member_first_contribution is a MATERIALIZED VIEW for HEALTH new-business
-- classification (grill note 7) — refreshed nightly by PremiumEarningExecutor.

CREATE TABLE IF NOT EXISTS earning_schedule (
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
    earned_at_period_end  NUMERIC(19, 4),                                 -- null until period closes
    currency_code         CHAR(3)        NOT NULL,
    is_endorsement        BOOLEAN        NOT NULL DEFAULT FALSE,
    endorsement_id        UUID,
    portfolio_id          UUID           REFERENCES ifrs17_portfolio(id),
    cohort_id             UUID           REFERENCES ifrs17_cohort(id),
    earning_method        VARCHAR(30)    NOT NULL,                        -- snapshot of DRL template that fired
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    actor_id              UUID,
    actor_email           VARCHAR(255),
    CONSTRAINT chk_period_bounds CHECK (period_end >= period_start),
    CONSTRAINT chk_written_nonneg CHECK (written_amount >= 0 OR is_endorsement = TRUE),
    CONSTRAINT chk_earned_valid CHECK (
        earned_at_period_end IS NULL
        OR is_endorsement = TRUE
        OR (earned_at_period_end BETWEEN 0 AND written_amount)
    )
);

-- Idempotency guard per U10 — one row per (policy, period, endorsement-if-any).
CREATE UNIQUE INDEX IF NOT EXISTS ux_earning_schedule_key ON earning_schedule
    (policy_id, policy_source, period_start,
     COALESCE(endorsement_id, '00000000-0000-0000-0000-000000000000'::uuid));

-- Query indexes.
CREATE INDEX IF NOT EXISTS ix_earning_schedule_period
    ON earning_schedule (period_start, period_end);
CREATE INDEX IF NOT EXISTS ix_earning_schedule_policy
    ON earning_schedule (policy_id, policy_source);
CREATE INDEX IF NOT EXISTS ix_earning_schedule_line_currency
    ON earning_schedule (insurance_line, currency_code, period_end);
CREATE INDEX IF NOT EXISTS ix_earning_schedule_portfolio
    ON earning_schedule (portfolio_id, period_end);
CREATE INDEX IF NOT EXISTS ix_earning_schedule_unclosed
    ON earning_schedule (period_end) WHERE earned_at_period_end IS NULL;

-- ── Executor progress tracking (grill note 5) ─────────────────────────
CREATE TABLE IF NOT EXISTS earning_schedule_run (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID         NOT NULL,
    run_kind                 VARCHAR(30)  NOT NULL CHECK (run_kind IN (
                                 'SCHEDULED', 'BACKFILL', 'ENDORSEMENT_RECOMPUTE'
                             )),
    trigger_reference        UUID,                        -- policyId (backfill) or endorsementId (recompute)
    status                   VARCHAR(20)  NOT NULL CHECK (status IN (
                                 'PENDING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED'
                             )),
    started_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at              TIMESTAMPTZ,
    last_heartbeat_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_processed_policy_id UUID,
    policies_processed       INT          NOT NULL DEFAULT 0,
    periods_written          INT          NOT NULL DEFAULT 0,
    error_message            TEXT
);
CREATE INDEX IF NOT EXISTS ix_earning_run_status
    ON earning_schedule_run (status, started_at DESC);

-- ── Materialized view for HEALTH new-business detection (grill note 7) ──
-- REFRESH runs nightly via PremiumEarningExecutor after the period-close pass.
-- NewBusinessRegisterReportService reads this to identify a member's first
-- Contribution ever within the tenant.
CREATE MATERIALIZED VIEW IF NOT EXISTS member_first_contribution AS
SELECT member_id, MIN(created_at) AS first_at
FROM contributions
WHERE member_id IS NOT NULL
GROUP BY member_id;

CREATE UNIQUE INDEX IF NOT EXISTS ux_member_first_contribution
    ON member_first_contribution (member_id);
