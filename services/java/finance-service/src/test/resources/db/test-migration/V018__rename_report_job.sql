-- Test-migration mirror for Phase 15 §1 rename. Sibling of V017 (Phase 14
-- actuarial_report_job creation) — same shared test-migration folder, chosen
-- over a dedicated slice folder for the same V001-vs-V001 checksum-clash
-- reasons that Phases 2/4/5 documented.
--
-- Kept in lock-step with tenancy-service tenant V151.

ALTER TABLE actuarial_report_job RENAME TO report_job;

DROP TRIGGER IF EXISTS trg_arj_no_reupdate ON report_job;
DROP FUNCTION IF EXISTS actuarial_report_job_no_reupdate();

CREATE OR REPLACE FUNCTION report_job_no_reupdate() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status IN ('completed', 'failed') THEN
        RAISE EXCEPTION 'report_job is append-only after terminal status (job_id=%)', OLD.job_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_report_job_no_reupdate ON report_job;
CREATE TRIGGER trg_report_job_no_reupdate
    BEFORE UPDATE ON report_job
    FOR EACH ROW EXECUTE FUNCTION report_job_no_reupdate();

ALTER TABLE report_job
    ADD COLUMN IF NOT EXISTS retention_class VARCHAR(20) NOT NULL DEFAULT 'OPERATIONAL_90D',
    ADD COLUMN IF NOT EXISTS parent_job_id UUID NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'report_job_retention_class_ck'
    ) THEN
        ALTER TABLE report_job
            ADD CONSTRAINT report_job_retention_class_ck
            CHECK (retention_class IN ('OPERATIONAL_90D', 'STATUTORY_7Y'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'report_job_parent_fk'
    ) THEN
        ALTER TABLE report_job
            ADD CONSTRAINT report_job_parent_fk
            FOREIGN KEY (parent_job_id) REFERENCES report_job(job_id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_report_job_parent ON report_job (parent_job_id);
CREATE INDEX IF NOT EXISTS ix_report_job_retention ON report_job (retention_class, completed_at);

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
