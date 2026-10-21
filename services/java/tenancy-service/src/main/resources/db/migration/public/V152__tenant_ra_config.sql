-- Per-tenant IFRS 17 Risk Adjustment methodology (Phase 15 §2 / I6).
-- Multi-row config keyed by (tenant_id, portfolio_id, effective_from). Each
-- portfolio can pick between Cost of Capital (CoC) and Confidence Interval
-- (CI). The CHECK constraint enforces the mutually-exclusive params: CoC
-- requires coc_rate and forbids target_confidence_level; CI is the reverse.
--
-- Renumbered from plan V139 to V152 at implement time: Phase 14 tenant
-- migrations already occupy V139..V142 in the shared dev flyway history,
-- and Phase 1 landed V151 (rename_report_job) in the tenant folder.

CREATE TABLE IF NOT EXISTS public.tenant_ra_config (
    id                        UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    portfolio_id              UUID           NOT NULL,
    methodology               VARCHAR(10)    NOT NULL CHECK (methodology IN ('COC', 'CI')),
    coc_rate                  NUMERIC(5,4),
    target_confidence_level   NUMERIC(5,4),
    source_note               VARCHAR(200),
    effective_from            DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to              DATE,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by                UUID,
    updated_by_email          VARCHAR(255),
    CONSTRAINT tenant_ra_config_methodology_params_ck
        CHECK ((methodology = 'COC' AND coc_rate IS NOT NULL AND target_confidence_level IS NULL)
            OR (methodology = 'CI' AND target_confidence_level IS NOT NULL AND coc_rate IS NULL)),
    CONSTRAINT tenant_ra_config_coc_range_ck
        CHECK (coc_rate IS NULL OR (coc_rate > 0 AND coc_rate < 1)),
    CONSTRAINT tenant_ra_config_ci_range_ck
        CHECK (target_confidence_level IS NULL OR (target_confidence_level > 0 AND target_confidence_level < 1)),
    CONSTRAINT uq_tenant_ra_config UNIQUE (tenant_id, portfolio_id, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_tenant_ra_config_lookup
    ON public.tenant_ra_config (tenant_id, portfolio_id, effective_from DESC);
