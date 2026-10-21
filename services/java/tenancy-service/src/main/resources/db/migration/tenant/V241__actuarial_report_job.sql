-- Durable job/result table for the async Kafka actuarial pipeline (Phase 14 §A/B).
-- finance-service inserts a row on submit, publishes to medfund.actuarial.job-requested;
-- ai-service publishes back to medfund.actuarial.job-completed; the consumer terminal-writes
-- status + result_json here. Append-only after terminal status per Grill note 21.
--
-- params_hash + partial UNIQUE index makes duplicate submits within the requested/processing
-- window return the existing job_id rather than kick off a second compute.

CREATE TABLE IF NOT EXISTS actuarial_report_job (
    job_id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID          NOT NULL,
    report_key         VARCHAR(80)   NOT NULL,
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('requested','processing','completed','failed')),
    params_json        JSONB         NOT NULL,
    params_hash        VARCHAR(64)   NOT NULL,
    result_json        JSONB,
    error_message      TEXT,
    requested_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at       TIMESTAMPTZ,
    requested_by       UUID,
    requested_by_email VARCHAR(255)  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_arj_lookup
    ON actuarial_report_job (tenant_id, report_key, params_hash);

CREATE UNIQUE INDEX IF NOT EXISTS ux_arj_inflight
    ON actuarial_report_job (tenant_id, params_hash)
    WHERE status IN ('requested', 'processing');

-- Append-only guard: once a row lands in 'completed' or 'failed', subsequent UPDATEs
-- raise so an operator/consumer bug can't quietly rewrite an audit-trail row.
-- The single terminal write from 'processing' → 'completed'/'failed' is permitted.
CREATE OR REPLACE FUNCTION actuarial_report_job_no_reupdate() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status IN ('completed', 'failed') THEN
        RAISE EXCEPTION 'actuarial_report_job is append-only after terminal status (job_id=%)', OLD.job_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_arj_no_reupdate ON actuarial_report_job;
CREATE TRIGGER trg_arj_no_reupdate
    BEFORE UPDATE ON actuarial_report_job
    FOR EACH ROW EXECUTE FUNCTION actuarial_report_job_no_reupdate();
