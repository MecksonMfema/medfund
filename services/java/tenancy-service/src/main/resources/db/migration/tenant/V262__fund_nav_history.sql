-- Phase 15 §8 (I4): NAV history per fund. Per-day valuation series consumed
-- by §16 VFA fair-value share compute. Read-only after insert — corrections
-- go via a new row on the next valuation_date (never edit).
--
-- source='ADMIN' for manual entry / CSV upload; 'AUTO' reserved for a
-- future auto-fetch adapter (out of Phase 15 scope).

CREATE TABLE IF NOT EXISTS fund_nav_history (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    fund_id         UUID          NOT NULL REFERENCES unit_linked_fund(id) ON DELETE CASCADE,
    valuation_date  DATE          NOT NULL,
    nav_per_unit    NUMERIC(18,6) NOT NULL CHECK (nav_per_unit > 0),
    source          VARCHAR(20)   NOT NULL DEFAULT 'ADMIN'
        CHECK (source IN ('ADMIN', 'AUTO')),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id        UUID,
    actor_email     VARCHAR(200),
    CONSTRAINT fund_nav_history_uq UNIQUE (fund_id, valuation_date)
);

CREATE INDEX IF NOT EXISTS ix_fund_nav_history_lookup
    ON fund_nav_history (fund_id, valuation_date DESC);
