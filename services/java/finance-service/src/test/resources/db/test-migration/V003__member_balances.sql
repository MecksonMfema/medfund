-- Test-migration layer for V072: adds the member_balances snapshot table
-- (see services/java/tenancy-service/src/main/resources/db/migration/tenant/
-- V072__creditors_unification_and_member_settlement.sql for production shape).
-- Only the schema is created here — the backfill in V072 depends on tables
-- (claims, member_payables) that this IT doesn't seed, so it is skipped.

CREATE TABLE member_balances (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id            UUID           NOT NULL,
    total_claimed        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_approved       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_paid           NUMERIC(19, 4) NOT NULL DEFAULT 0,
    outstanding_balance  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency_code        VARCHAR(3)     NOT NULL,
    last_updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_member_balances UNIQUE (member_id, currency_code)
);

CREATE INDEX idx_member_balances_outstanding
    ON member_balances(outstanding_balance DESC)
    WHERE outstanding_balance > 0;
CREATE INDEX idx_member_balances_member
    ON member_balances(member_id);

-- Extend the applications CHECK to include 'PAYMENT' (V072 does the same in
-- production); avoids CtcPaymentServiceIT touching CTC-only rows breaking
-- because V072's constraint tightening happens on the shared migration path.
ALTER TABLE member_payable_applications
    DROP CONSTRAINT IF EXISTS mpa_source_type_check;
ALTER TABLE member_payable_applications
    ADD CONSTRAINT mpa_source_type_check CHECK (source_type IN ('CTC', 'PAYMENT'));

-- Refresh grants for the newly created table.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
