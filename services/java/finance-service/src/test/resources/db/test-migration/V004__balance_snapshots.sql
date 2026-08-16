-- Test-migration layer for V080: adds the balance-snapshot tables
-- (see services/java/tenancy-service/src/main/resources/db/migration/tenant/
-- V080__balance_snapshots.sql for the production shape). Mirror DDL only —
-- this IT schema has no payment_runs execution pipeline beyond what the
-- test seeds directly.

CREATE TABLE provider_balance_snapshot (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_run_id      UUID           NOT NULL,
    provider_id         UUID           NOT NULL,
    currency_code       VARCHAR(3)     NOT NULL,
    opening_balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    closing_balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_claimed       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_approved      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_paid          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    net_due             NUMERIC(19, 4) NOT NULL DEFAULT 0,
    taken_at            TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_provider_balance_snapshot UNIQUE (payment_run_id, provider_id, currency_code)
);

CREATE INDEX idx_provider_balance_snapshot_provider
    ON provider_balance_snapshot(provider_id, taken_at DESC);

CREATE TABLE member_balance_snapshot (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_run_id      UUID           NOT NULL,
    member_id           UUID           NOT NULL,
    currency_code       VARCHAR(3)     NOT NULL,
    opening_balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    closing_balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_claimed       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_approved      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_paid          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    net_due             NUMERIC(19, 4) NOT NULL DEFAULT 0,
    taken_at            TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_member_balance_snapshot UNIQUE (payment_run_id, member_id, currency_code)
);

CREATE INDEX idx_member_balance_snapshot_member
    ON member_balance_snapshot(member_id, taken_at DESC);

-- Refresh grants for the newly created tables.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
