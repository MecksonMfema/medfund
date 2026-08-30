-- Phase 15 §8 (I4): per-policy unit ledger. Append-only journal of
-- unit purchases / sales / rollovers / fund switches / fee deductions for
-- unit-linked (VFA) policies. §16 VFA reads the running-balance snapshot
-- at the reporting-period boundary.
--
-- No FK to policy tables — policy_id references any of the six line-specific
-- policy tables (life_policies, funeral_policies, etc.). Foreign key would
-- pin us to one table; keep this generic per parent-plan line-agnostic
-- invariant.

CREATE TABLE IF NOT EXISTS policy_unit_ledger (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id         UUID          NOT NULL,
    fund_id           UUID          NOT NULL REFERENCES unit_linked_fund(id),
    transaction_date  DATE          NOT NULL,
    transaction_type  VARCHAR(30)   NOT NULL
        CHECK (transaction_type IN ('PURCHASE', 'SALE', 'ROLLOVER',
                                    'FEE_DEDUCTION', 'FUND_SWITCH_IN', 'FUND_SWITCH_OUT')),
    units             NUMERIC(18,6) NOT NULL,
    price             NUMERIC(18,6) NOT NULL CHECK (price > 0),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(200)
);

CREATE INDEX IF NOT EXISTS ix_policy_unit_ledger_policy
    ON policy_unit_ledger (policy_id, transaction_date DESC);

CREATE INDEX IF NOT EXISTS ix_policy_unit_ledger_fund
    ON policy_unit_ledger (fund_id, transaction_date DESC);
