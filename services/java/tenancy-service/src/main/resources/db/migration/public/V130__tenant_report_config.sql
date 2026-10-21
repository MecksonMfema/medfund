-- =====================================================================
-- V130: Per-tenant report toggle catalogue.
--
-- One row per (tenant, report_key) — the row's presence with
-- enabled=FALSE hides that report from every finance-service /
-- contributions-service / claims-service endpoint annotated with
-- @RequiresReport(ReportKey.X), as well as from the Angular reports
-- hub and dynamic sidebar. Absent row = enabled by default so a newly
-- shipped report is visible without a per-tenant migration.
--
-- The catalogue of valid report_key values is enforced at the API layer
-- (com.medfund.shared.report.ReportKey enum) — writing an unknown key
-- from the tenant-admin UI is rejected with HTTP 400. No DB-side CHECK
-- constraint on report_key because the enum evolves faster than the
-- migration cadence; strict validation happens where the write enters
-- the system.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_report_config (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    report_key   VARCHAR(80)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   UUID,
    CONSTRAINT uq_tenant_report_config UNIQUE (tenant_id, report_key)
);

COMMENT ON TABLE public.tenant_report_config IS
    'Per-tenant on/off toggle for each catalogued report_key. Absent row = enabled by default.';

-- Partial index — the enablement reader only ever queries by
-- (tenant_id, report_key) with an OFF-side lookup, so a partial index on
-- the disabled rows keeps this cheap regardless of catalogue growth.
CREATE INDEX IF NOT EXISTS idx_tenant_report_config_tenant_disabled
    ON public.tenant_report_config (tenant_id)
    WHERE enabled = FALSE;
