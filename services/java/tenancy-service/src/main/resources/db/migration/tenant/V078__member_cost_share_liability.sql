-- =====================================================================
-- V078: Member cost-share liability + settlement ledger
-- =====================================================================
-- Phase 4 of the copayments standard flow. Creates the finance-side
-- record of "what the member owes" (one row per adjudicated claim) and
-- the settlement sub-ledger (one row per receipt or write-off applied
-- against a liability).
--
-- Written by finance-service's ClaimAdjudicatedConsumer when the
-- enriched medfund.claims.adjudicated payload includes a non-null
-- memberResponsibility. Cash-first path (payeeType=MEMBER) pre-sets
-- status='SETTLED' + writes a synthetic settlement row (G12); the
-- MemberPayable write path is unchanged and continues to record the
-- fund → member reimbursement side.

CREATE TABLE member_cost_share_liability (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id                 UUID NOT NULL,
    -- UNIQUE on claim_id gives us idempotent replays — a Kafka redelivery
    -- of the same medfund.claims.adjudicated event bounces off the index
    -- rather than duplicating the row (handled in ClaimAdjudicatedConsumer).
    claim_id                  UUID NOT NULL UNIQUE,
    claim_number              VARCHAR(50),
    deductible                DECIMAL(19,4) NOT NULL DEFAULT 0,
    copay                     DECIMAL(19,4) NOT NULL DEFAULT 0,
    coinsurance               DECIMAL(19,4) NOT NULL DEFAULT 0,
    shortfall                 DECIMAL(19,4) NOT NULL DEFAULT 0,
    not_covered               DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_owed                DECIMAL(19,4) NOT NULL,
    total_settled             DECIMAL(19,4) NOT NULL DEFAULT 0,
    currency_code             CHAR(3) NOT NULL,
    -- G6: benefit-currency capture for reporting; total_owed / total_settled
    -- above are in claim currency (the fx-converted number the calculator
    -- produced). Null when the benefit and claim currencies match.
    currency_code_original    CHAR(3),
    status                    VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT member_cost_share_liability_status_ck
        CHECK (status IN ('OPEN','PARTIALLY_SETTLED','SETTLED','WRITTEN_OFF'))
);

CREATE INDEX ix_mcsl_member ON member_cost_share_liability (member_id, status);
CREATE INDEX ix_mcsl_status ON member_cost_share_liability (status)
    WHERE status IN ('OPEN','PARTIALLY_SETTLED');

-- Settlement sub-ledger. One row per receipt / member-paid-provider event
-- / write-off applied against a liability. RESTRICT on the FK — a liability
-- with settlements should not vanish silently; operators reverse it via
-- a note-like flow that leaves the trail intact.
CREATE TABLE member_cost_share_settlement (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    liability_id              UUID NOT NULL REFERENCES member_cost_share_liability(id) ON DELETE RESTRICT,
    -- Links to transactions.id when the member paid via the tenant's cash
    -- receipt path. NULL for MEMBER_PAID_PROVIDER (cash-first, member paid
    -- the provider directly) and WRITE_OFF (no receipt exists).
    receipt_transaction_id    UUID,
    amount                    DECIMAL(19,4) NOT NULL,
    currency_code             CHAR(3) NOT NULL,
    source                    VARCHAR(30) NOT NULL,
    settled_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                UUID,
    CONSTRAINT member_cost_share_settlement_source_ck
        CHECK (source IN ('MEMBER_PAYMENT','MEMBER_PAID_PROVIDER','WRITE_OFF'))
);

CREATE INDEX ix_mcs_settlement_liability ON member_cost_share_settlement (liability_id);
CREATE INDEX ix_mcs_settlement_source    ON member_cost_share_settlement (source);
