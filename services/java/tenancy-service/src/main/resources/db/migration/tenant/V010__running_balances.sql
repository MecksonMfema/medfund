-- Running balance ledger — one row per (entity, currency_code).
-- Replaces legacy MASCA's dual-column usd_balance/zwl_balance. Updated
-- transactionally by BalanceService whenever a contribution is created,
-- a contribution is paid, or a transaction is recorded. Aging is driven
-- by last_payment_at (cleaner than legacy MASCA's
-- (debt / monthly_contribution × 30) formula).

CREATE TABLE IF NOT EXISTS member_running_balance (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id       UUID          NOT NULL REFERENCES members(id),
    currency_code   CHAR(3)       NOT NULL,
    balance         DECIMAL(19,4) NOT NULL DEFAULT 0,
    last_charge_at  TIMESTAMPTZ,
    last_payment_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (member_id, currency_code)
);

CREATE INDEX IF NOT EXISTS idx_member_balance_currency
    ON member_running_balance(currency_code);

CREATE INDEX IF NOT EXISTS idx_member_balance_outstanding
    ON member_running_balance(currency_code, balance) WHERE balance > 0;

CREATE TABLE IF NOT EXISTS group_running_balance (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id        UUID          NOT NULL REFERENCES groups(id),
    currency_code   CHAR(3)       NOT NULL,
    balance         DECIMAL(19,4) NOT NULL DEFAULT 0,
    last_charge_at  TIMESTAMPTZ,
    last_payment_at TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (group_id, currency_code)
);

CREATE INDEX IF NOT EXISTS idx_group_balance_currency
    ON group_running_balance(currency_code);

CREATE INDEX IF NOT EXISTS idx_group_balance_outstanding
    ON group_running_balance(currency_code, balance) WHERE balance > 0;
