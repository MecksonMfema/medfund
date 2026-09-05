-- Phase 19 §A test schema for the SIU repository ITs.
-- Mirrors the prod tenant migrations
--   V169__fraud_flag.sql, V170__siu_case.sql, V171__siu_case_note.sql
-- collapsed into a single file for the flat `public`-schema IT world.
-- No FK to claims(id) here — the IT harness inserts synthetic claim rows
-- via seed helpers and the service enforces referential integrity at
-- insert time (same rationale as claim_reserve_history in V002).

CREATE TABLE IF NOT EXISTS fraud_flag (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id           UUID NOT NULL,
    siu_case_id        UUID,
    flag_source        VARCHAR(32) NOT NULL,
    model_version      VARCHAR(64),
    risk_score         NUMERIC(4,3),
    risk_level         VARCHAR(16),
    indicators         JSONB NOT NULL DEFAULT '[]'::JSONB,
    flagged_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    correlation_id     VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fraud_flag_flag_source_chk CHECK (flag_source IN ('AI_MODEL','MANUAL_OFFICER')),
    CONSTRAINT fraud_flag_risk_level_chk  CHECK (risk_level IS NULL OR risk_level IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT fraud_flag_ai_fields_chk   CHECK (
        flag_source = 'MANUAL_OFFICER' OR
        (model_version IS NOT NULL AND risk_score IS NOT NULL AND risk_level IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS fraud_flag_claim_id_idx    ON fraud_flag (claim_id);
CREATE INDEX IF NOT EXISTS fraud_flag_siu_case_id_idx ON fraud_flag (siu_case_id) WHERE siu_case_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS fraud_flag_flagged_at_idx  ON fraud_flag (flagged_at);
CREATE INDEX IF NOT EXISTS fraud_flag_purge_idx       ON fraud_flag (flagged_at) WHERE siu_case_id IS NULL;

CREATE TABLE IF NOT EXISTS siu_case (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_number           VARCHAR(32) NOT NULL,
    status                VARCHAR(48) NOT NULL,
    priority              VARCHAR(16),
    tags                  TEXT[] NOT NULL DEFAULT '{}',
    assigned_to           UUID,
    opened_by             UUID NOT NULL,
    opened_by_email       VARCHAR(255) NOT NULL,
    opened_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_by             UUID,
    closed_by_email       VARCHAR(255),
    closed_at             TIMESTAMPTZ,
    closure_reason        TEXT,
    outcome               VARCHAR(48),
    saved_amount          NUMERIC(19,4),
    saved_currency        VARCHAR(3),
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

CREATE TABLE IF NOT EXISTS siu_case_note (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id      UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    author_id    UUID NOT NULL,
    author_email VARCHAR(255) NOT NULL,
    note_type    VARCHAR(32) NOT NULL,
    body         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT siu_case_note_type_chk CHECK (note_type IN (
        'COMMENT','STATUS_CHANGE','EVIDENCE_ADDED','ASSIGNED','REFERRAL_ADDED','FLAG_LINKED'))
);

CREATE INDEX IF NOT EXISTS siu_case_note_case_id_idx    ON siu_case_note (case_id);
CREATE INDEX IF NOT EXISTS siu_case_note_created_at_idx ON siu_case_note (created_at);

-- Re-broaden the public_role grants to include the newly created tables
-- (V001 only granted to the tables that existed at its snapshot).
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON fraud_flag, siu_case, siu_case_note TO public_role;
