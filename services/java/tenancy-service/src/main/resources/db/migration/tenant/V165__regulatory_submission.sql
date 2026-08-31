-- =====================================================================
-- V165: Auditor-grade record of every regulator report submission.
--
-- Phase 16 §0 REG12 — one row per (tenant, report_key, period, submission_number).
-- First export in submit mode = SUBMITTED / submission_number=1; a second
-- export for the same (tenant, key, period) auto-increments submission_number
-- and links back via supersedes_id, marking the prior row SUPERSEDED. Every
-- transition into SUBMITTED is MFA-gated at the service layer.
--
-- xlsx_bytes storage rationale (deviation from plan's MinIO):
-- finance-service has no existing MinIO wiring; the exported XLSX is bounded
-- by our per-report POI limits (~2-5 MB for the largest regulator returns);
-- Postgres bytea keeps the row + payload atomic under STATUTORY_7Y retention.
-- If XLSX size grows or the row count crosses the millions we can migrate
-- to MinIO by swapping xlsx_bytes for an xlsx_ref VARCHAR in a new
-- higher-numbered tenant migration.
-- =====================================================================

CREATE TABLE IF NOT EXISTS regulatory_submission (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID          NOT NULL,
    report_key                VARCHAR(80)   NOT NULL,
    period_start              DATE          NOT NULL,
    period_end                DATE          NOT NULL,
    submission_number         INT           NOT NULL,
    supersedes_id             UUID          NULL REFERENCES regulatory_submission(id),
    source_run_id             UUID          NOT NULL,
    submitted_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    submitted_by_actor_id     UUID          NOT NULL,
    submitted_by_actor_email  VARCHAR(320)  NOT NULL,
    xlsx_bytes                BYTEA         NOT NULL,
    xlsx_size_bytes           BIGINT        NOT NULL,
    xlsx_content_hash         VARCHAR(64)   NULL,
    filing_ref                VARCHAR(200)  NULL,
    status                    VARCHAR(20)   NOT NULL
        CHECK (status IN ('DRAFT','SUBMITTED','AMENDED','SUPERSEDED')),
    attestation_note          TEXT          NULL,
    reason_note               TEXT          NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_regulatory_submission_slot
    ON regulatory_submission (tenant_id, report_key, period_start, submission_number);

CREATE INDEX IF NOT EXISTS ix_regulatory_submission_lookup
    ON regulatory_submission (tenant_id, report_key, period_start DESC, submitted_at DESC);

-- Rapid supersedes-chain walking from either end.
CREATE INDEX IF NOT EXISTS ix_regulatory_submission_supersedes
    ON regulatory_submission (supersedes_id) WHERE supersedes_id IS NOT NULL;

COMMENT ON TABLE regulatory_submission IS
    'Auditor-grade record of every regulator report submission. Supersedes chain via supersedes_id. Phase 16 §0 REG12.';
COMMENT ON COLUMN regulatory_submission.status IS
    'DRAFT (pre-MFA) / SUBMITTED (post-MFA, current) / AMENDED (post-MFA, replaced by newer submission_number) / SUPERSEDED (auto-marked on the prior row when a new submission_number is created)';
COMMENT ON COLUMN regulatory_submission.filing_ref IS
    'Regulator-assigned filing reference captured after portal upload. Nullable — populated post-hoc via the admin form.';
