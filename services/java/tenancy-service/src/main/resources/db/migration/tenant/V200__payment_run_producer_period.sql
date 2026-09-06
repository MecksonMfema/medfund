-- Phase 11 §A Phase 6 — PRODUCER-payee payment runs must remember which
-- commission-accrual window they cover so execute() can flip
-- commission_transaction.status ACCRUED → PAID for that period only.
-- PROVIDER + MEMBER runs leave both columns NULL.

ALTER TABLE payment_runs
    ADD COLUMN IF NOT EXISTS period_start DATE,
    ADD COLUMN IF NOT EXISTS period_end   DATE;

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
