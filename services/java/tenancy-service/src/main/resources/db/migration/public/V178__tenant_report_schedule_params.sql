-- Phase 19 §B Phase 12 — per-schedule sensitive-sheet opt-in for FRAUD_SIU_REPORT
-- (FR12). Generic JSONB `params` column supports future per-key schedule flags
-- without new migrations per key.
--
-- For FRAUD_SIU_REPORT today: {"includeSensitiveSheets": true|false}. Default
-- object {} keeps existing rows valid + means "no per-key tunables" — the
-- adapter treats missing/false as "sensitive sheets omitted" (default per FR12).

ALTER TABLE public.tenant_report_schedule
    ADD COLUMN IF NOT EXISTS params JSONB NOT NULL DEFAULT '{}'::JSONB;

COMMENT ON COLUMN public.tenant_report_schedule.params IS
    'Per-key schedule opt-ins. For FRAUD_SIU_REPORT: {"includeSensitiveSheets": true|false}.';
