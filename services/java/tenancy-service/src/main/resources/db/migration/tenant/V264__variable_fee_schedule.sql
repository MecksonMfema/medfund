-- Phase 15 §8 (I4): effective-date-versioned variable fee % per fund.
-- Consumed by §16 VFA compute for the fee portion of the CSM release
-- (IFRS 17.B119). Fee is stored as a decimal fraction (e.g. 0.0150 for
-- 1.50% p.a.).

CREATE TABLE IF NOT EXISTS variable_fee_schedule (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    fund_id         UUID          NOT NULL REFERENCES unit_linked_fund(id) ON DELETE CASCADE,
    effective_from  DATE          NOT NULL DEFAULT CURRENT_DATE,
    effective_to    DATE,
    fee_percentage  NUMERIC(5,4)  NOT NULL CHECK (fee_percentage >= 0 AND fee_percentage <= 1),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id        UUID,
    actor_email     VARCHAR(200),
    CONSTRAINT variable_fee_schedule_uq UNIQUE (fund_id, effective_from),
    CONSTRAINT variable_fee_schedule_range_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX IF NOT EXISTS ix_variable_fee_schedule_lookup
    ON variable_fee_schedule (fund_id, effective_from DESC);
