-- Phase 14 §A test schema for ClaimReserveControllerIT + related.
-- Mirrors the prod tenant migration V139 shape (see
-- services/java/tenancy-service/src/main/resources/db/migration/tenant/V139__claim_reserve_history.sql).
-- No FK to claims(id) — the service enforces referential integrity at
-- insert time by reading the claim first, matching the prod-DDL rationale.
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
CREATE INDEX IF NOT EXISTS idx_crh_claim_time ON claim_reserve_history (claim_id, effective_at DESC);

-- Re-broaden the public_role grants to include the newly created table
-- (V001 only granted to the tables that existed at its snapshot).
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON claim_reserve_history TO public_role;
