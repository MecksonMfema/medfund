-- =====================================================================
-- V179: Per-tenant sidebar-item visibility catalogue.
--
-- One row per (tenant, section_key) - the row's presence with
-- enabled=FALSE hides that sidebar item from the Angular operations
-- portal for every user in the tenant. Absent row = enabled by
-- default so a newly shipped sidebar item is visible without a
-- per-tenant migration.
--
-- Modelled directly on V130 (tenant_report_config): same shape,
-- same partial index on disabled rows, same "absent = enabled"
-- contract. The Angular sidebar consumer reads this alongside the
-- report-config catalogue and applies both as filters over the
-- static OPERATIONAL_NAV config.
--
-- The catalogue of valid section_key values is enforced at the API
-- layer (com.medfund.shared.sidebar.SidebarSectionKey enum) -
-- writing an unknown key from the tenant-admin UI is rejected with
-- HTTP 400. No DB-side CHECK constraint on section_key because the
-- enum evolves faster than the migration cadence; strict validation
-- happens where the write enters the system.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_sidebar_section_config (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    section_key  VARCHAR(80)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   UUID,
    CONSTRAINT uq_tenant_sidebar_section_config UNIQUE (tenant_id, section_key)
);

COMMENT ON TABLE public.tenant_sidebar_section_config IS
    'Per-tenant on/off toggle for each catalogued sidebar section_key. Absent row = enabled by default.';

-- Partial index - the enablement reader only ever queries by
-- (tenant_id, section_key) with an OFF-side lookup, so a partial
-- index on the disabled rows keeps this cheap regardless of
-- catalogue growth.
CREATE INDEX IF NOT EXISTS idx_tenant_sidebar_section_config_tenant_disabled
    ON public.tenant_sidebar_section_config (tenant_id)
    WHERE enabled = FALSE;
