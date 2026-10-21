-- Per-tenant yield curve points for IFRS 17 discounting (Phase 15 §2 / I5 + I12).
-- Multi-row config keyed by (tenant_id, currency, tenor_months, effective_from).
-- Curves may be admin-uploaded (source='ADMIN'), auto-fetched by the Phase 24
-- market-data-service from RBZ or SARB (source='RBZ_AUTO'/'SARB_AUTO'), or
-- populated by the Phase 6 cohort-lock-in backfill when no historic point
-- exists (source='BACKFILL_FALLBACK').
--
-- Renumbered from plan V140 to V153 at implement time (same reason as V152).

CREATE TABLE IF NOT EXISTS public.tenant_yield_curve (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    currency          VARCHAR(3)     NOT NULL,
    tenor_months      INT            NOT NULL CHECK (tenor_months BETWEEN 1 AND 600),
    spot_rate         NUMERIC(9,7)   NOT NULL,
    source            VARCHAR(20)    NOT NULL DEFAULT 'ADMIN'
        CHECK (source IN ('ADMIN', 'RBZ_AUTO', 'SARB_AUTO', 'BACKFILL_FALLBACK')),
    effective_from    DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to      DATE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    updated_by_email  VARCHAR(255),
    CONSTRAINT uq_tenant_yield_curve UNIQUE (tenant_id, currency, tenor_months, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_tenant_yield_curve_lookup
    ON public.tenant_yield_curve (tenant_id, currency, effective_from DESC, tenor_months);
