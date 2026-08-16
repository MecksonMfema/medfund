-- Test-migration layer for V080's read path: minimal providers + payment_runs
-- tables so the balance-history IT can exercise the snapshot↔run join and the
-- payee-name lookup end-to-end. Mirrors the production columns the
-- BalanceHistoryQueryRepository actually selects.

CREATE TABLE providers (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE payment_runs (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    run_number      VARCHAR(50)    NOT NULL UNIQUE,
    status          VARCHAR(32)    NOT NULL DEFAULT 'draft',
    total_amount    NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency_code   VARCHAR(3)     NOT NULL,
    payment_count   INTEGER        NOT NULL DEFAULT 0,
    description     TEXT,
    executed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
