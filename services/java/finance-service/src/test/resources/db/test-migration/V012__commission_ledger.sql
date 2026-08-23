-- Test-migration layer for Phase 11 §A Phase 3 commission-ledger tests.
-- Mirrors production tenant migrations V095 (commission_transaction) and
-- V096 (clawback_event) in tenancy-service. Applies after V011 so the
-- producer + rate-card tables exist as FK targets.

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
CREATE UNIQUE INDEX ux_commission_txn_source ON commission_transaction
    (contribution_id, producer_id, COALESCE(rate_card_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE reversal_of_txn_id IS NULL;
CREATE INDEX ix_commission_txn_producer_period ON commission_transaction
    (producer_id, occurred_at);
CREATE INDEX ix_commission_txn_member_period   ON commission_transaction (member_id, occurred_at);
CREATE INDEX ix_commission_txn_status          ON commission_transaction (status)
    WHERE status IN ('ACCRUED');

CREATE TABLE clawback_event (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    source                    VARCHAR(30)   NOT NULL,
    triggering_event_ref      VARCHAR(120)  NOT NULL,
    member_id                 UUID          NOT NULL,
    producer_id               UUID          NOT NULL REFERENCES producer(id) ON DELETE RESTRICT,
    commission_transaction_id UUID          NOT NULL REFERENCES commission_transaction(id) ON DELETE RESTRICT,
    reversal_txn_id           UUID          REFERENCES commission_transaction(id)         ON DELETE RESTRICT,
    native_amount             NUMERIC(19,4) NOT NULL,
    native_currency           CHAR(3)       NOT NULL,
    reason                    TEXT,
    occurred_at               TIMESTAMPTZ   NOT NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id                  UUID,
    actor_email               VARCHAR(255),
    CONSTRAINT cb_source_ck CHECK (source IN ('MEMBER_LAPSE','CONTRIBUTION_REVOKE'))
);
CREATE UNIQUE INDEX ux_clawback_by_source ON clawback_event
    (source, triggering_event_ref, commission_transaction_id);
CREATE INDEX ix_clawback_producer_period ON clawback_event (producer_id, occurred_at);
CREATE INDEX ix_clawback_member          ON clawback_event (member_id);

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
