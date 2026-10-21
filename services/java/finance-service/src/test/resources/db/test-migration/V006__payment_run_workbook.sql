-- Test-migration layer for the Phase 7 payment-run workbook export:
-- minimal payment_run_items + payments tables mirroring the post-V071
-- production shape (payee XOR, payment join columns) so the
-- PaymentRunWorkbookControllerIT can exercise the run-item join and the
-- payee-name lookup end-to-end. payments carries the columns the
-- workbook query selects (payment_number, payment_method, reference,
-- paid_at) plus the payee XOR columns used by production V071.

CREATE TABLE payment_run_items (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_run_id UUID           NOT NULL REFERENCES payment_runs(id) ON DELETE CASCADE,
    payment_id     UUID,
    provider_id    UUID,
    member_id      UUID,
    payee_type     VARCHAR(10)    NOT NULL DEFAULT 'PROVIDER'
                       CHECK (payee_type IN ('PROVIDER', 'MEMBER')),
    amount         NUMERIC(19, 4) NOT NULL,
    currency_code  VARCHAR(3)     NOT NULL,
    status         VARCHAR(32)    NOT NULL DEFAULT 'pending'
                       CHECK (status IN ('pending', 'scheduled', 'paid', 'withheld', 'skipped')),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_run_items_run ON payment_run_items(payment_run_id);

CREATE TABLE payments (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_number VARCHAR(50)    NOT NULL UNIQUE,
    provider_id    UUID,
    member_id      UUID,
    payee_type     VARCHAR(10)    NOT NULL DEFAULT 'PROVIDER'
                       CHECK (payee_type IN ('PROVIDER', 'MEMBER')),
    amount         NUMERIC(19, 4) NOT NULL,
    currency_code  VARCHAR(3)     NOT NULL DEFAULT 'USD',
    payment_type   VARCHAR(50)    NOT NULL,
    status         VARCHAR(20)    NOT NULL DEFAULT 'pending',
    payment_method VARCHAR(50),
    reference      VARCHAR(200),
    paid_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
