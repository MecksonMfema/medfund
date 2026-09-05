-- Phase 19 §A Phase 2 — SIU case: one row per investigation.
-- MVP state machine (3-state) per parent-plan FR16 §A carve-out:
--   OPEN → UNDER_REVIEW → (CLOSED_CONFIRMED_FRAUD | CLOSED_DISMISSED_FALSE_POSITIVE)
-- Full 5-state expansion (ASSIGNED, PENDING_APPROVAL, REOPENED,
-- CLOSED_REFERRED_LAW_ENFORCEMENT, CLOSED_ACTION_TAKEN) lands in §B Phase 8
-- via V174 status-widen migration.
--
-- Rule-2 guard: tenant-schema table — queries must NOT use `public.`
-- prefix per bug_public_prefix_silent_rollback.

CREATE TABLE IF NOT EXISTS siu_case (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_number           VARCHAR(32) NOT NULL,                     -- tenant-scoped human-readable e.g. SIU-2026-000123
    status                VARCHAR(48) NOT NULL,                     -- 'OPEN' | 'UNDER_REVIEW' | 'CLOSED_CONFIRMED_FRAUD' | 'CLOSED_DISMISSED_FALSE_POSITIVE'
    priority              VARCHAR(16),                              -- 'LOW' | 'MEDIUM' | 'HIGH'
    tags                  TEXT[] NOT NULL DEFAULT '{}',
    assigned_to           UUID,                                     -- user id; nullable for OPEN cases
    opened_by             UUID NOT NULL,                            -- 'SYSTEM' UUID if opened by FRAUD_TRIAGE rules
    opened_by_email       VARCHAR(255) NOT NULL,                    -- feedback_audit_actor_email
    opened_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_by             UUID,
    closed_by_email       VARCHAR(255),
    closed_at             TIMESTAMPTZ,
    closure_reason        TEXT,                                     -- free-text investigator narrative
    outcome               VARCHAR(48),                              -- mirrors terminal status enum
    saved_amount          NUMERIC(19,4),                            -- investigator-entered per FR8
    saved_currency        VARCHAR(3),                               -- ISO-4217 per FR14
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT siu_case_case_number_uk    UNIQUE (case_number),
    CONSTRAINT siu_case_status_chk        CHECK (status IN (
                                            'OPEN','UNDER_REVIEW',
                                            'CLOSED_CONFIRMED_FRAUD','CLOSED_DISMISSED_FALSE_POSITIVE')),
    CONSTRAINT siu_case_savings_pair_chk  CHECK (
                                            (saved_amount IS NULL AND saved_currency IS NULL) OR
                                            (saved_amount IS NOT NULL AND saved_currency IS NOT NULL AND saved_amount >= 0))
);

CREATE INDEX IF NOT EXISTS siu_case_status_idx       ON siu_case (status);
CREATE INDEX IF NOT EXISTS siu_case_assigned_to_idx  ON siu_case (assigned_to) WHERE assigned_to IS NOT NULL;
CREATE INDEX IF NOT EXISTS siu_case_opened_at_idx    ON siu_case (opened_at);
CREATE INDEX IF NOT EXISTS siu_case_closed_at_idx    ON siu_case (closed_at) WHERE closed_at IS NOT NULL;

-- Back-fill the fraud_flag FK now that siu_case exists.
-- Idempotent add — repeat runs on a partially-migrated tenant are safe.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fraud_flag_siu_case_id_fk'
    ) THEN
        ALTER TABLE fraud_flag
            ADD CONSTRAINT fraud_flag_siu_case_id_fk
            FOREIGN KEY (siu_case_id) REFERENCES siu_case(id) ON DELETE SET NULL;
    END IF;
END$$;

COMMENT ON TABLE  siu_case                     IS 'One row per SIU investigation (Phase 19 §A MVP; §B Phase 8 widens status enum).';
COMMENT ON COLUMN siu_case.case_number         IS 'Tenant-scoped human-readable identifier (SIU-YYYY-NNNNNN).';
COMMENT ON COLUMN siu_case.saved_amount        IS 'Investigator-entered on closure per FR8; defaults to SUM(claimed − paid) across flagged claims.';
COMMENT ON COLUMN siu_case.saved_currency      IS 'ISO-4217; one currency per case per FR14 (investigator picks majority-flagged-claim currency).';
