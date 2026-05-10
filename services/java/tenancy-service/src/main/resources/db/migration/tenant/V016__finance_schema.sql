-- Finance-domain tables. Five of these match entities + repositories that
-- already exist in finance-service but had no backing schema; the rest are
-- parity tables for the legacy MASCA finance surface (advance payments,
-- CTC payments, payment advices, MASCA bank accounts, debit/credit notes).
--
-- All columns mirror the @Table / @Column annotations on the existing
-- entities exactly so the repositories light up without further changes.

-- ── payment_runs ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_runs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    run_number      VARCHAR(50)  NOT NULL UNIQUE,
    status          VARCHAR(32)  NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft', 'approved', 'executing', 'executed', 'cancelled')),
    total_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency_code   VARCHAR(3)   NOT NULL,
    payment_count   INTEGER      NOT NULL DEFAULT 0,
    description     TEXT,
    executed_at     TIMESTAMPTZ,
    executed_by     UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID
);

CREATE INDEX IF NOT EXISTS idx_payment_runs_status   ON payment_runs(status);
CREATE INDEX IF NOT EXISTS idx_payment_runs_currency ON payment_runs(currency_code);
CREATE INDEX IF NOT EXISTS idx_payment_runs_created  ON payment_runs(created_at DESC);

-- ── payment_run_items ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_run_items (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_run_id  UUID         NOT NULL REFERENCES payment_runs(id) ON DELETE CASCADE,
    payment_id      UUID,
    provider_id     UUID         NOT NULL,
    amount          NUMERIC(19,4) NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending', 'scheduled', 'paid', 'withheld', 'skipped')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payment_run_items_run      ON payment_run_items(payment_run_id);
CREATE INDEX IF NOT EXISTS idx_payment_run_items_provider ON payment_run_items(provider_id);
CREATE INDEX IF NOT EXISTS idx_payment_run_items_status   ON payment_run_items(status);

-- ── provider_balances ─────────────────────────────────────────────────────
-- One row per (provider, currency) — running ledger updated by the claim
-- adjudication consumer + payment commits.
CREATE TABLE IF NOT EXISTS provider_balances (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id          UUID         NOT NULL,
    total_claimed        NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_approved       NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_paid           NUMERIC(19,4) NOT NULL DEFAULT 0,
    outstanding_balance  NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency_code        VARCHAR(3)   NOT NULL,
    last_updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_balances UNIQUE (provider_id, currency_code)
);

CREATE INDEX IF NOT EXISTS idx_provider_balances_outstanding
    ON provider_balances(outstanding_balance DESC) WHERE outstanding_balance > 0;

-- ── adjustments ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS adjustments (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    adjustment_number   VARCHAR(50)  NOT NULL UNIQUE,
    provider_id         UUID,
    member_id           UUID,
    adjustment_type     VARCHAR(32)  NOT NULL
                          CHECK (adjustment_type IN ('IN_PAYMENT','PAYOUT','NON_CASH_IN','NON_CASH_OUT','TAX_WITHHELD')),
    amount              NUMERIC(19,4) NOT NULL,
    currency_code       VARCHAR(3)   NOT NULL,
    reason              TEXT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending','approved','applied','cancelled')),
    approved_by         UUID,
    approved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          UUID,
    CHECK (provider_id IS NOT NULL OR member_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_adjustments_provider ON adjustments(provider_id);
CREATE INDEX IF NOT EXISTS idx_adjustments_member   ON adjustments(member_id);
CREATE INDEX IF NOT EXISTS idx_adjustments_status   ON adjustments(status);
CREATE INDEX IF NOT EXISTS idx_adjustments_type     ON adjustments(adjustment_type);

-- ── bank_reconciliations ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS bank_reconciliations (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_number  VARCHAR(100) NOT NULL UNIQUE,
    statement_amount  NUMERIC(19,4) NOT NULL,
    system_amount     NUMERIC(19,4) NOT NULL DEFAULT 0,
    difference        NUMERIC(19,4) NOT NULL DEFAULT 0,
    currency_code     VARCHAR(3)   NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'unmatched'
                        CHECK (status IN ('unmatched','matched','investigating','resolved')),
    notes             TEXT,
    statement_date    DATE         NOT NULL,
    reconciled_at     TIMESTAMPTZ,
    reconciled_by     UUID,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID
);

CREATE INDEX IF NOT EXISTS idx_bank_reconciliations_status ON bank_reconciliations(status);
CREATE INDEX IF NOT EXISTS idx_bank_reconciliations_date   ON bank_reconciliations(statement_date DESC);

-- ── payment_advices ───────────────────────────────────────────────────────
-- Document trail of what each provider was paid for in a given run. Generated
-- on demand and reused for re-print / re-export.
CREATE TABLE IF NOT EXISTS payment_advices (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_run_id  UUID         REFERENCES payment_runs(id) ON DELETE SET NULL,
    provider_id     UUID         NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,
    total_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
    claim_count     INTEGER      NOT NULL DEFAULT 0,
    document_url    VARCHAR(500),
    excel_url       VARCHAR(500),
    status          VARCHAR(32)  NOT NULL DEFAULT 'generated'
                      CHECK (status IN ('generated','sent','failed')),
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_payment_advices_run      ON payment_advices(payment_run_id);
CREATE INDEX IF NOT EXISTS idx_payment_advices_provider ON payment_advices(provider_id);

-- ── advance_payments ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS advance_payments (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID         REFERENCES payments(id) ON DELETE SET NULL,
    provider_id     UUID,
    member_id       UUID,
    amount          NUMERIC(19,4) NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,
    payment_method  VARCHAR(50),
    reference       VARCHAR(255),
    comment         TEXT,
    recorded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    recorded_by     UUID,
    CHECK (provider_id IS NOT NULL OR member_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_advance_payments_provider ON advance_payments(provider_id);
CREATE INDEX IF NOT EXISTS idx_advance_payments_member   ON advance_payments(member_id);

-- ── ctc_payments ──────────────────────────────────────────────────────────
-- Cash-to-cardholder transfers at group / member level (legacy MASCA
-- contribution-side payment).
CREATE TABLE IF NOT EXISTS ctc_payments (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id        UUID,
    member_id       UUID,
    amount          NUMERIC(19,4) NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,
    contribution_id UUID,
    committed       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    CHECK (group_id IS NOT NULL OR member_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_ctc_payments_group    ON ctc_payments(group_id);
CREATE INDEX IF NOT EXISTS idx_ctc_payments_member   ON ctc_payments(member_id);
CREATE INDEX IF NOT EXISTS idx_ctc_payments_committed ON ctc_payments(committed);

-- ── masca_bank_accounts ───────────────────────────────────────────────────
-- The tenant's own bank accounts used for outbound disbursements. Distinct
-- from provider / member bank details (those live elsewhere). Only one
-- nominated account per currency at a time.
CREATE TABLE IF NOT EXISTS masca_bank_accounts (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_name       VARCHAR(200) NOT NULL,
    account_number  VARCHAR(50)  NOT NULL,
    branch_code     VARCHAR(50),
    swift_code      VARCHAR(50),
    account_name    VARCHAR(200) NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,
    is_nominated    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_masca_bank_accounts_number UNIQUE (account_number, currency_code)
);

-- Partial unique index enforces "at most one nominated account per currency"
-- without blocking multiple non-nominated rows.
CREATE UNIQUE INDEX IF NOT EXISTS uq_masca_bank_accounts_nominated_per_currency
    ON masca_bank_accounts(currency_code) WHERE is_nominated = TRUE;

-- ── debit_notes / credit_notes ────────────────────────────────────────────
-- Audit-trail entries for manual finance adjustments outside the
-- adjustments table (e.g. one-off bank-fee write-offs, goodwill credits).
CREATE TABLE IF NOT EXISTS debit_notes (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    amount        NUMERIC(19,4) NOT NULL,
    currency_code VARCHAR(3)   NOT NULL,
    reference     VARCHAR(255),
    task_id       UUID,
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    UUID
);

CREATE TABLE IF NOT EXISTS credit_notes (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    amount        NUMERIC(19,4) NOT NULL,
    currency_code VARCHAR(3)   NOT NULL,
    reference     VARCHAR(255),
    task_id       UUID,
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    UUID
);
