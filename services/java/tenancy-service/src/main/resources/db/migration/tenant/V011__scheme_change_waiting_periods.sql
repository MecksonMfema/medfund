-- Scheme-change waiting periods: applied when a member moves between
-- schemes. Different shape from waiting_period_rules (V001) which keys
-- on (scheme, condition_type) for fresh-enrolment waits. This one keys
-- on the change direction (UPGRADE / DOWNGRADE) plus an optional benefit
-- type so tenants can express rules like "30 days inpatient wait when
-- upgrading" or "no wait at all when downgrading".

CREATE TABLE IF NOT EXISTS scheme_change_waiting_period_rules (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    change_type   VARCHAR(20)  NOT NULL CHECK (change_type IN ('UPGRADE','DOWNGRADE')),
    benefit_type  VARCHAR(50),
    waiting_days  INTEGER      NOT NULL CHECK (waiting_days >= 0),
    description   TEXT,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scheme_change_waiting_active
    ON scheme_change_waiting_period_rules(change_type) WHERE is_active = TRUE;
