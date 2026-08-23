-- =====================================================================
-- V095: Commission transaction ledger (Phase 11 §A P5/P6/P13)
-- =====================================================================
-- One row per (contribution × producer × rate_card) accrual. Idempotency
-- via the partial UNIQUE index ux_commission_txn_source — a replay of the
-- same medfund.contributions.paid event never writes a duplicate row.
-- Reversals: compensating REVERSED row links to the original via
-- reversal_of_txn_id; the original's reversed_by_txn_id points back.
-- =====================================================================

CREATE TABLE commission_transaction (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    reference              VARCHAR(40)   NOT NULL,
    producer_id            UUID          NOT NULL REFERENCES producer(id)              ON DELETE RESTRICT,
    contribution_id        UUID          NOT NULL,
    member_id              UUID          NOT NULL,
    insurance_line         VARCHAR(20)   NOT NULL,
    rate_card_id           UUID          REFERENCES commission_rate_card(id)           ON DELETE RESTRICT,
    native_amount          NUMERIC(19,4) NOT NULL,
    native_currency        CHAR(3)       NOT NULL,
    contribution_amount    NUMERIC(19,4) NOT NULL,
    applied_rate_pct       NUMERIC(7,4)  NOT NULL,
    status                 VARCHAR(20)   NOT NULL DEFAULT 'ACCRUED',
    paid_run_id            UUID,
    paid_at                TIMESTAMPTZ,
    reversed_by_txn_id     UUID          REFERENCES commission_transaction(id)         ON DELETE RESTRICT,
    reversal_of_txn_id     UUID          REFERENCES commission_transaction(id)         ON DELETE RESTRICT,
    occurred_at            TIMESTAMPTZ   NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id               UUID,
    actor_email            VARCHAR(255),
    CONSTRAINT ct_status_ck CHECK (status IN
        ('ACCRUED','PAID','REVERSED','CLAWED_BACK','VOIDED'))
);

CREATE UNIQUE INDEX ux_commission_txn_reference ON commission_transaction (reference);

-- Idempotency guard: one accrual per (contribution, producer, rate_card).
-- COALESCE the rate_card_id so multiple accruals with NULL card still collide.
CREATE UNIQUE INDEX ux_commission_txn_source ON commission_transaction
    (contribution_id, producer_id, COALESCE(rate_card_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE reversal_of_txn_id IS NULL;

CREATE INDEX ix_commission_txn_producer_period ON commission_transaction
    (producer_id, occurred_at);
CREATE INDEX ix_commission_txn_member_period   ON commission_transaction (member_id, occurred_at);
CREATE INDEX ix_commission_txn_status          ON commission_transaction (status)
    WHERE status IN ('ACCRUED');
