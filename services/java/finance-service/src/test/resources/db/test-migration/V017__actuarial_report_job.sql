-- Test-migration mirror for Phase 14 §A actuarial job pipeline.
-- Mirrors production tenant migration V141 in tenancy-service so the
-- finance-service IT can round-trip through the append-only + partial-UNIQUE
-- guards without pulling in the tenancy migration set.
--
-- Consolidated into the shared test-migration folder rather than a
-- dedicated db/actuarial-phase9-migration/V001 to avoid the V001-vs-V001
-- checksum clash pattern documented in Phases 2/4/5 deviations.

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

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
