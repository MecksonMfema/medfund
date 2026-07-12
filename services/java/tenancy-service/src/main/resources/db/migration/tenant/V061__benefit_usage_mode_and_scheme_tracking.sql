-- =====================================================================
-- V061: benefit usage_mode + scheme.tracks_member_balances
-- =====================================================================
-- MedFund is a generic claims platform: medical aid + dental + optical
-- accrue running balances; funeral / life / critical illness pay out
-- once per beneficiary; travel / maternity pay out once per policy
-- period; personal accident / hospital cash have per-event counters;
-- pure indemnity property/vehicle products don't need per-member
-- ledger rows at all. A single classification is not enough — we
-- carry a usage_mode per benefit AND a tracks_member_balances flag
-- on the whole scheme.
--
-- Also adds claim_line_consumption_log so the decrement consumer can
-- be idempotent on replay without relying on the beneficiary row
-- being locked during a Kafka handler.
-- =====================================================================

-- 1) scheme_benefits.usage_mode ---------------------------------------
ALTER TABLE scheme_benefits
    ADD COLUMN IF NOT EXISTS usage_mode VARCHAR(32) NOT NULL DEFAULT 'RUNNING_BALANCE';

ALTER TABLE scheme_benefits
    ADD CONSTRAINT scheme_benefits_usage_mode_ck
    CHECK (usage_mode IN (
        'RUNNING_BALANCE',
        'ONE_TIME_PER_BENEFICIARY',
        'ONE_TIME_PER_PERIOD',
        'PER_EVENT_COUNTER',
        'NO_TRACKING'
    ));

CREATE INDEX IF NOT EXISTS idx_scheme_benefits_usage_mode
    ON scheme_benefits(usage_mode);

-- 2) schemes.tracks_member_balances -----------------------------------
ALTER TABLE schemes
    ADD COLUMN IF NOT EXISTS tracks_member_balances BOOLEAN NOT NULL DEFAULT TRUE;

-- Asset-centric insurance lines never need per-member ledger rows.
-- Every claim on VEHICLE / PROPERTY is settled against scheme-level
-- limits (indemnity model): there is no meaningful annual quota per
-- policyholder. Turn tracking off so the enrolment seeder skips them.
UPDATE schemes
   SET tracks_member_balances = FALSE
 WHERE insurance_line IN ('VEHICLE', 'PROPERTY');

-- 3) claim_line_consumption_log ---------------------------------------
-- Decrement consumer idempotency table. First step of the consumer is
-- INSERT INTO claim_line_consumption_log(claim_line_id) — on unique
-- violation the message is a replay and the consumer acks + skips.
-- This is more robust than trying to compare deltas on the ledger.
CREATE TABLE IF NOT EXISTS claim_line_consumption_log (
    claim_line_id UUID PRIMARY KEY,
    benefit_id    UUID NOT NULL,
    member_id     UUID NOT NULL,
    dependant_id  UUID,
    policy_year   INT  NOT NULL,
    applied_delta DECIMAL(19, 4) NOT NULL,
    applied_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ccl_benefit
    ON claim_line_consumption_log(benefit_id, policy_year);
