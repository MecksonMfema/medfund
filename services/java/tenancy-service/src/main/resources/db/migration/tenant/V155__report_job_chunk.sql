-- Phase 15 §3: fan-out chunk row for the async report pipeline (I14 chunking
-- + I13 envelope shape + I25 MinIO oversize fallback). Every IFRS 17 parent
-- report_job produces N chunk rows — one per portfolio × cohort × currency.
-- ai-service compute publishes result per chunk; Ifrs17JobAggregator (§18)
-- rolls them up into the parent report_job.result_json when all are terminal.
--
-- parent_job_id FK cascades on delete so the ReportJobRetentionJob purge in
-- §3 cleans chunks + parent atomically. params_ref / result_ref carry the
-- MinIO s3:// URIs when payload size exceeds SIZE_LIMIT (§10).
--
-- Renumbered from the plan's V152 → V155 to sit above the public V152-V154
-- allocated by Phase 2's deviation (dev shares Flyway history across
-- {public, tenant}; version numbers must be unique across both folders).

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
