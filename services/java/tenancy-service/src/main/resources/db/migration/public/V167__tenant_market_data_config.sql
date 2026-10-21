-- Per-tenant opt-in for auto-fetching yield curves from a jurisdictional
-- market-data source (Phase 15 §24 / I12 + I16). Tenant admin picks a
-- currency + source (RBZ_AUTO for Zimbabwe or SARB_AUTO for South Africa)
-- and the market-data-service (services/go/market-data-service) fetches
-- the curve daily and publishes to medfund.market-data.yield-curve-updated;
-- tenancy-service's YieldCurveConsumer upserts the resulting rows into
-- public.tenant_yield_curve with source = RBZ_AUTO or SARB_AUTO.
--
-- Uniqueness on (tenant_id, currency): one enrolment per currency per
-- tenant. Admin-uploaded rows in tenant_yield_curve continue to coexist
-- with auto-fetched rows — the consumer only touches AUTO-source rows.
--
-- Renumbered from plan V142 to V167 at implement time: the plan's original
-- numbering assumed the market-data table sat between the §2 admin-config
-- migrations (V139-V141) and the §4+ tenant migrations (V143+), but every
-- one of those slots was consumed by earlier phase renumbers. V167 is the
-- next free slot in the shared Flyway history (V166 was the §19
-- notification-config table).

CREATE TABLE IF NOT EXISTS public.tenant_market_data_config (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    currency          VARCHAR(3)     NOT NULL,
    auto_fetch_enabled BOOLEAN       NOT NULL DEFAULT TRUE,
    source            VARCHAR(20)    NOT NULL
        CHECK (source IN ('RBZ_AUTO', 'SARB_AUTO')),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    updated_by_email  VARCHAR(255),
    CONSTRAINT uq_tenant_market_data_config UNIQUE (tenant_id, currency)
);

CREATE INDEX IF NOT EXISTS idx_tenant_market_data_config_lookup
    ON public.tenant_market_data_config (tenant_id, auto_fetch_enabled);
