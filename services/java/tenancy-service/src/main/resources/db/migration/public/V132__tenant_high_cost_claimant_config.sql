-- =====================================================================
-- V132: Per-tenant high-cost claimant threshold
-- =====================================================================
-- Threshold above which a member's cumulative PAID claims across a report
-- window flag them as a "high-cost claimant" (Phase 4 CLAIMS_FINANCIAL
-- HIGH_COST_CLAIMANT report, G46).
--
-- Deliberately NOT seeded for existing tenants (unlike V128/V129): there is
-- no sensible platform default threshold. When the row is absent, the report
-- renders an empty result with warnings:
--   ["High-cost threshold not configured for tenant"]
-- (config gap → best-effort-with-warnings per G28, not fail-loud).
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_high_cost_claimant_config (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    threshold_amount  NUMERIC(19,4) NOT NULL,
    currency_code     CHAR(3)      NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    CONSTRAINT uq_tenant_high_cost_config UNIQUE (tenant_id)
);

COMMENT ON TABLE  public.tenant_high_cost_claimant_config IS
    'Per-tenant threshold above which a member''s cumulative paid claims flag them as high-cost.';
COMMENT ON COLUMN public.tenant_high_cost_claimant_config.threshold_amount IS
    'The cumulative-paid threshold. Denominated in currency_code; converted to report currency at report time via FxRateReader.convert (fail-loud on missing rate per G28).';
