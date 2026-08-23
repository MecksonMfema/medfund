-- Test-migration layer for Phase 11 §B Phase 8 commission-adjustment tests.
-- Mirrors production tenant migration V097 (commission_adjustment) in
-- tenancy-service. Applies after V012 so commission_transaction exists as
-- the FK target for target_commission_transaction_id + committed_txn_id.

CREATE TABLE commission_adjustment (
    id                               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    reference                        VARCHAR(40)   NOT NULL,
    target_commission_transaction_id UUID          NOT NULL REFERENCES commission_transaction(id) ON DELETE RESTRICT,
    adjustment_type                  VARCHAR(20)   NOT NULL,
    adjustment_amount                NUMERIC(19,4) NOT NULL,
    native_currency                  CHAR(3)       NOT NULL,
    justification                    TEXT          NOT NULL,
    status                           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    approver_actor_id                UUID,
    approver_actor_email             VARCHAR(255),
    approved_at                      TIMESTAMPTZ,
    committed_at                     TIMESTAMPTZ,
    committed_txn_id                 UUID          REFERENCES commission_transaction(id) ON DELETE RESTRICT,
    voided_at                        TIMESTAMPTZ,
    voided_reason                    TEXT,
    created_at                       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id                         UUID,
    actor_email                      VARCHAR(255),
    CONSTRAINT ca_status_ck            CHECK (status IN ('DRAFT','APPROVED','COMMITTED','VOIDED')),
    CONSTRAINT ca_type_ck              CHECK (adjustment_type IN
        ('EX_GRATIA','VOID','MANUAL_CLAWBACK','MANUAL_REVERSAL')),
    CONSTRAINT ca_reference_uq         UNIQUE (reference),
    CONSTRAINT ca_justification_len_ck CHECK (char_length(justification) >= 20)
);
CREATE INDEX ix_commission_adjustment_target ON commission_adjustment (target_commission_transaction_id);
CREATE INDEX ix_commission_adjustment_status ON commission_adjustment (status, created_at)
    WHERE status IN ('DRAFT','APPROVED');

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
