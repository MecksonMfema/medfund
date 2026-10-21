-- Test-migration mirror for Phase 19 §B Phase 12 public V178
-- tenant_report_schedule.params column. See the production migration for
-- rationale — this file only exists so finance-service ITs that read the
-- probe SELECT (which now pulls s.params) don't fail with "column params
-- does not exist".

ALTER TABLE public.tenant_report_schedule
    ADD COLUMN IF NOT EXISTS params JSONB NOT NULL DEFAULT '{}'::JSONB;
