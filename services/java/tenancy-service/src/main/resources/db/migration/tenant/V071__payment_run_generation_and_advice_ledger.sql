-- =====================================================================
-- V071: Payment-run item population, member-payee support, and per-payee
-- ledger-style payment advices.
--
-- Additive on top of:
--   * V001 baseline (payments)
--   * V016 finance schema (payment_run_items, payment_advices)
--   * V067 carry-forward (payment_runs.carried_in/out)
--   * V069 member-payable ledger (member_payables, member_payable_applications)
--
-- See thoughts/shared/plans/2026-08-09-payment-run-generation-and-payee-support.md.
-- =====================================================================

-- ── 1. Payments: add payee_type + nullable member_id ─────────────────
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS payee_type VARCHAR(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (payee_type IN ('PROVIDER','MEMBER')),
    ADD COLUMN IF NOT EXISTS member_id  UUID;

-- provider_id in V001 baseline was already nullable; call is defensive
-- + idempotent.
ALTER TABLE payments
    ALTER COLUMN provider_id DROP NOT NULL;

ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS payments_payee_xor;
ALTER TABLE payments
    ADD CONSTRAINT payments_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL AND member_id IS NOT NULL AND payee_type = 'MEMBER'));

CREATE INDEX IF NOT EXISTS idx_payments_member ON payments(member_id);
CREATE INDEX IF NOT EXISTS idx_payments_payee_type ON payments(payee_type, status);

-- ── 2. Payment-run items: same additive XOR ──────────────────────────
ALTER TABLE payment_run_items
    ADD COLUMN IF NOT EXISTS payee_type VARCHAR(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (payee_type IN ('PROVIDER','MEMBER')),
    ADD COLUMN IF NOT EXISTS member_id  UUID;

ALTER TABLE payment_run_items
    ALTER COLUMN provider_id DROP NOT NULL;

ALTER TABLE payment_run_items
    DROP CONSTRAINT IF EXISTS payment_run_items_payee_xor;
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL AND member_id IS NOT NULL AND payee_type = 'MEMBER'));

CREATE INDEX IF NOT EXISTS idx_pri_member ON payment_run_items(member_id);

-- ── 3. Payment advices: per-payee, ledger-style ──────────────────────
ALTER TABLE payment_advices
    ADD COLUMN IF NOT EXISTS payee_type             VARCHAR(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (payee_type IN ('PROVIDER','MEMBER')),
    ADD COLUMN IF NOT EXISTS member_id              UUID,
    ADD COLUMN IF NOT EXISTS period_start_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS period_end_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS carried_in_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS claims_paid_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ctc_applied_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS advance_applied_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_withheld_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS shortfall_amount       NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS net_due_amount         NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS advice_number          VARCHAR(20);

-- Loosen the V016 NOT NULL on provider_id and add the payee XOR.
ALTER TABLE payment_advices
    ALTER COLUMN provider_id DROP NOT NULL;

ALTER TABLE payment_advices
    DROP CONSTRAINT IF EXISTS payment_advices_payee_xor;
ALTER TABLE payment_advices
    ADD CONSTRAINT payment_advices_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL AND member_id IS NOT NULL AND payee_type = 'MEMBER'));

-- One advice per (run, payee). Partial UNIQUE so provider/member cases
-- don't collide on their NULL columns.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_advices_run_provider
    ON payment_advices(payment_run_id, provider_id) WHERE provider_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_advices_run_member
    ON payment_advices(payment_run_id, member_id)   WHERE member_id   IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_advices_member ON payment_advices(member_id);

-- Backfill advice_number for any pre-existing rows so we can mark the
-- column NOT NULL. Going forward the service writes ADV-<6-digit-random>.
UPDATE payment_advices
   SET advice_number = 'ADV-LEGACY-' || SUBSTR(id::TEXT, 1, 8)
 WHERE advice_number IS NULL;

ALTER TABLE payment_advices
    DROP CONSTRAINT IF EXISTS payment_advices_advice_number_not_null;
ALTER TABLE payment_advices
    ADD CONSTRAINT payment_advices_advice_number_not_null CHECK (advice_number IS NOT NULL);

-- ── 4. Payment-advice lines: typed ledger rows ───────────────────────
CREATE TABLE IF NOT EXISTS payment_advice_lines (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_advice_id   UUID NOT NULL REFERENCES payment_advices(id) ON DELETE CASCADE,
    line_type           VARCHAR(24) NOT NULL
                          CHECK (line_type IN ('CARRY_FORWARD','CLAIM_PAID',
                                               'CTC_APPLIED','ADVANCE_APPLIED',
                                               'TAX_WITHHELD','SHORTFALL')),
    reference_type      VARCHAR(32),
    reference_id        UUID,
    description         TEXT,
    debit_amount        NUMERIC(19,4) NOT NULL DEFAULT 0,
    credit_amount       NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency_code       VARCHAR(3)   NOT NULL,
    posted_at           TIMESTAMPTZ  NOT NULL,
    sequence            INTEGER      NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payment_advice_lines_amount_sign
        CHECK ((debit_amount = 0 OR credit_amount = 0)
           AND (debit_amount >= 0 AND credit_amount >= 0))
);

CREATE INDEX IF NOT EXISTS idx_pal_advice_seq  ON payment_advice_lines(payment_advice_id, sequence);
CREATE INDEX IF NOT EXISTS idx_pal_reference   ON payment_advice_lines(reference_type, reference_id);

-- ── 5. Permission for manual advice regeneration ─────────────────────
-- No standalone permissions catalogue table (see V069 header); permissions
-- are code-defined and granted here via role_permissions.
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), r.id, 'finance:generate_payment_advice', 'full'
  FROM roles r
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;
