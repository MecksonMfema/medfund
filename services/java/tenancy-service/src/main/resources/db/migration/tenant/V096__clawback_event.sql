-- =====================================================================
-- V096: Clawback events (Phase 11 §A P6/P7/P13)
-- =====================================================================
-- One row per clawback trigger. Source discriminator folds two triggers
-- (MEMBER_LAPSE + CONTRIBUTION_REVOKE) into one register.
-- Idempotency via ux_clawback_by_source — a replay of the same triggering
-- event never writes a duplicate row.
-- =====================================================================

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
