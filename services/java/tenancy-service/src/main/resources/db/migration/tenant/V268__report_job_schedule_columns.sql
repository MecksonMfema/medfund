-- Phase 17 §0.2 (S2): add scheduled-run columns to report_job.
--
-- source distinguishes ad-hoc user-triggered exports from probe-fired runs;
-- schedule_id back-references the public.tenant_report_schedule row.
-- period_start / period_end record the (previous complete period) or
-- (as-of fire time) window the run covered — driven by ReportPeriodShape
-- on the ReportKey (shared module Phase 1).
--
-- The partial UNIQUE index prevents multi-instance probe duplicate fires
-- for the same (tenant, key, schedule, period) while leaving ad-hoc rows
-- (schedule_id IS NULL, source='ADHOC') alone — those are outside the
-- WHERE clause and always accepted.
--
-- FK to public.tenant_report_schedule: cross-schema FK is legal in Postgres
-- (existing precedent: several tenant tables FK to public.tenants(id)).
-- ON DELETE SET NULL preserves history if a schedule is later removed.

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
