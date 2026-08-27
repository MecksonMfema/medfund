-- Point-in-time case-reserve history per claim, feeds the incurred triangle
-- for the IBNR study (actuarial Phase 14 §A). Append-only in intent; the row
-- reflects the reserve as of effective_at. Auto-zero rows are written by
-- ClaimReserveHistoryService.autoZero() when a claim transitions to
-- REJECTED / CANCELLED so the incurred sum stops trailing residual reserve.

CREATE TABLE IF NOT EXISTS claim_reserve_history (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id          UUID          NOT NULL,
    reserved_amount   NUMERIC(18,2) NOT NULL CHECK (reserved_amount >= 0),
    effective_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255)  NOT NULL,
    reason_note       TEXT          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_crh_claim_time
    ON claim_reserve_history (claim_id, effective_at DESC);
