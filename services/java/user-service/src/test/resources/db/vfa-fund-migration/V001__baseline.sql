-- Phase 15 §8 (I4) IT baseline. Scoped to what UnitLinkedFundIT,
-- FundNavHistoryIT, PolicyUnitLedgerIT and VariableFeeScheduleIT drive:
-- the four §8 tables plus the two boot shims (tenants +
-- scheduled_job_configs) every user-service IT needs.
--
-- Distinct flyway.table (see IT headers) lets these ITs coexist with peer
-- ITs on the shared JVM-scoped Postgres testcontainer without a
-- boot-order race, matching the Phase 4/5/6/7 precedent.

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
