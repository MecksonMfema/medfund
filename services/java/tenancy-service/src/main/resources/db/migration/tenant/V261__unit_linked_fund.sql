-- Phase 15 §8 (I4): VFA underlying-item entity — unit-linked fund catalog.
-- Fund is the container; NAV history (V162), policy unit ledger (V163) and
-- variable fee schedule (V164) hang off it. §16 VFA compute reads all four
-- for direct-participation contracts under IFRS 17.71.
--
-- Numbering deviation: plan cites V148 but V148..V160 have all been
-- consumed by concurrent Phase 3/4/5/6/7 renumbers. V161 is the next free
-- slot in the shared Flyway history (per bug_public_flyway_history_load_bearing
-- + the plan's "bump forward" rule).

CREATE TABLE IF NOT EXISTS unit_linked_fund (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    base_asset_class  VARCHAR(30)  NOT NULL
        CHECK (base_asset_class IN ('EQUITY', 'FIXED_INCOME', 'MULTI_ASSET', 'MONEY_MARKET', 'REAL_ESTATE', 'OTHER')),
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(200),
    CONSTRAINT unit_linked_fund_uq_name UNIQUE (name)
);

CREATE INDEX IF NOT EXISTS ix_unit_linked_fund_active
    ON unit_linked_fund (is_active, name);
