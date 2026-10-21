-- Phase 19 §A Phase 2 — fraud_flag audit-of-record for AI predictions.
-- Every classified claim (LOW/MEDIUM/HIGH) writes one row (Rule 3).
-- Rows with siu_case_id IS NULL and flagged_at older than 1y are
-- purged by FraudFlagRetentionJob (Phase 19 §A retention split).
--
-- Rule-2 guard: tenant-schema table — queries must NOT use `public.`
-- prefix per bug_public_prefix_silent_rollback.

CREATE TABLE IF NOT EXISTS fraud_flag (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id           UUID NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
    siu_case_id        UUID,                                         -- FK added in V170 once siu_case exists
    flag_source        VARCHAR(32) NOT NULL,                         -- 'AI_MODEL' | 'MANUAL_OFFICER'
    model_version      VARCHAR(64),                                  -- null for MANUAL_OFFICER rows
    risk_score         NUMERIC(4,3),                                 -- [0.000, 1.000]; null for MANUAL
    risk_level         VARCHAR(16),                                  -- 'LOW' | 'MEDIUM' | 'HIGH'; null for MANUAL
    indicators         JSONB NOT NULL DEFAULT '[]'::JSONB,           -- top-N features per FR10; ~5 entries
    flagged_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    correlation_id     VARCHAR(64),                                  -- links to submission event
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

COMMENT ON TABLE  fraud_flag              IS 'Immutable audit-of-record for AI + manual fraud flags (Rule 3, Phase 19 §A).';
COMMENT ON COLUMN fraud_flag.indicators   IS 'Top-N feature attributions (~5 entries) — full feature vector deferred to Phase 19.5 ML-ops.';
COMMENT ON COLUMN fraud_flag.siu_case_id  IS 'Nullable — set when FRAUD_TRIAGE rules-engine promotes flag to case. FK backfilled by V170.';
