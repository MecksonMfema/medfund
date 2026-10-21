-- Test-migration layer for the Phase 8 cash-flow forecast (D8-4):
-- minimal `invoices` table mirroring the production V001 shape (the
-- columns CashFlowForecastQueryRepository actually selects) so the
-- CashFlowForecastControllerIT can exercise the inflow query end-to-end.
-- Lives in `public` like the other test-migration files; the tenant
-- search-path interceptor falls back to `public` when no tenants row
-- exists.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE invoices (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number  VARCHAR(50),
    group_id        UUID,
    member_id       UUID,
    scheme_id       UUID,
    period_start    DATE,
    period_end      DATE,
    due_date        DATE,
    total_amount    NUMERIC(18,2),
    currency_code   VARCHAR(3),
    status          VARCHAR(20),
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    committed_at    TIMESTAMPTZ,
    created_by      UUID
);
