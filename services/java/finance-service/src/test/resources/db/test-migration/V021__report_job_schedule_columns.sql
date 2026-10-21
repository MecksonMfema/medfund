-- Test-migration mirror for Phase 17 §0.2 tenant V168 report_job schedule
-- columns. Kept in lock-step with tenancy-service tenant V168.
--
-- FK to public.tenant_report_schedule is preserved because V020 creates
-- that table in the same test schema. The partial UNIQUE dedup index is
-- the invariant the ReportJobScheduleDedupIT exercises.

ALTER TABLE report_job
    ADD COLUMN IF NOT EXISTS source       VARCHAR(20)  NOT NULL DEFAULT 'ADHOC',
    ADD COLUMN IF NOT EXISTS schedule_id  UUID         NULL,
    ADD COLUMN IF NOT EXISTS period_start DATE         NULL,
    ADD COLUMN IF NOT EXISTS period_end   DATE         NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'report_job_source_ck'
    ) THEN
        ALTER TABLE report_job
            ADD CONSTRAINT report_job_source_ck
            CHECK (source IN ('ADHOC','SCHEDULED'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'report_job_schedule_fk'
    ) THEN
        ALTER TABLE report_job
            ADD CONSTRAINT report_job_schedule_fk
            FOREIGN KEY (schedule_id) REFERENCES public.tenant_report_schedule(id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_report_job_schedule_dedup
    ON report_job (tenant_id, report_key, schedule_id, period_start)
    WHERE schedule_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_report_job_schedule
    ON report_job (schedule_id, requested_at DESC)
    WHERE schedule_id IS NOT NULL;
