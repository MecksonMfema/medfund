-- Phase 11 §A Phase 6 — mirror tenancy-service V099 (payee_type='PRODUCER'
-- widening + producer_id FK + withholding_tax_pct) and V100 (payment_runs
-- period columns) into the finance-service test schema so ProducerPayoutIT
-- and updated PaymentRunServiceIT can exercise the producer branch.

ALTER TABLE payment_run_items
    ADD COLUMN IF NOT EXISTS producer_id         UUID REFERENCES producer(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS withholding_tax_pct NUMERIC(5, 2);

ALTER TABLE payment_run_items
    DROP CONSTRAINT IF EXISTS payment_run_items_payee_type_check;
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_payee_type_check
        CHECK (payee_type IN ('PROVIDER', 'MEMBER', 'PRODUCER'));

ALTER TABLE payment_run_items
    DROP CONSTRAINT IF EXISTS payment_run_items_wht_range_ck;
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_wht_range_ck
        CHECK (withholding_tax_pct IS NULL
               OR (withholding_tax_pct >= 0 AND withholding_tax_pct <= 100));

CREATE INDEX IF NOT EXISTS ix_payment_run_items_producer
    ON payment_run_items (producer_id) WHERE producer_id IS NOT NULL;

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS producer_id UUID REFERENCES producer(id) ON DELETE RESTRICT;
ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS payments_payee_type_check;
ALTER TABLE payments
    ADD CONSTRAINT payments_payee_type_check
        CHECK (payee_type IN ('PROVIDER', 'MEMBER', 'PRODUCER'));
CREATE INDEX IF NOT EXISTS ix_payments_producer ON payments (producer_id)
    WHERE producer_id IS NOT NULL;

ALTER TABLE payment_runs
    ADD COLUMN IF NOT EXISTS payee_type   VARCHAR(10) NOT NULL DEFAULT 'PROVIDER',
    ADD COLUMN IF NOT EXISTS period_start DATE,
    ADD COLUMN IF NOT EXISTS period_end   DATE;

ALTER TABLE payment_runs
    DROP CONSTRAINT IF EXISTS payment_runs_payee_type_check;
ALTER TABLE payment_runs
    ADD CONSTRAINT payment_runs_payee_type_check
        CHECK (payee_type IN ('PROVIDER', 'MEMBER', 'PRODUCER'));

ALTER TABLE payment_runs
    DROP CONSTRAINT IF EXISTS ck_payment_runs_producer_period;
ALTER TABLE payment_runs
    ADD CONSTRAINT ck_payment_runs_producer_period
        CHECK (payee_type <> 'PRODUCER'
               OR (period_start IS NOT NULL AND period_end IS NOT NULL
                   AND period_end >= period_start));

CREATE INDEX IF NOT EXISTS ix_payment_runs_producer_period
    ON payment_runs (payee_type, period_start, period_end)
    WHERE payee_type = 'PRODUCER';

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
