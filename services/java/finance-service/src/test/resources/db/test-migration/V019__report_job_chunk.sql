-- Test-migration mirror for Phase 15 §3 report_job_chunk. Sibling of V018
-- (rename report_job) — shared test-migration folder per the Phase 14 §9
-- Deviation 2 rationale (avoids V001-vs-V001 checksum clashes).
--
-- Kept in lock-step with tenancy-service tenant V155.

CREATE TABLE IF NOT EXISTS report_job_chunk (
    chunk_id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_job_id      UUID          NOT NULL REFERENCES report_job(job_id) ON DELETE CASCADE,
    portfolio_id       UUID,
    cohort_id          UUID,
    currency           VARCHAR(3),
    status             VARCHAR(20)   NOT NULL DEFAULT 'requested'
        CHECK (status IN ('requested', 'processing', 'completed', 'failed')),
    params_json        JSONB         NOT NULL,
    params_ref         VARCHAR(300),
    result_json        JSONB,
    result_ref         VARCHAR(300),
    error_message      TEXT,
    requested_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS ix_report_job_chunk_parent_status
    ON report_job_chunk (parent_job_id, status);

CREATE INDEX IF NOT EXISTS ix_report_job_chunk_scope
    ON report_job_chunk (portfolio_id, cohort_id, currency);

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
