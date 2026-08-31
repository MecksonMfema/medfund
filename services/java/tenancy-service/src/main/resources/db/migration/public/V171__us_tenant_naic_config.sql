-- US-specific tenant NAIC company identity config (Phase 16 §A / Phase 14 REG16).
-- Single-row-per-tenant admin surface: NAIC company code + optional group
-- code, FEIN, state of domicile — pulled by the NAIC Schedule P / F shapers
-- (Phase 12 / 13) to populate the report's identity META section. A US
-- tenant must have an effective row on file before NAIC report submit is
-- accepted; the finance-service UsTenantNaicConfigReader bean gates
-- Schedule P / F submit on the presence of an effective row.
--
-- Table lives in public schema (platform-wide config table alongside
-- public.tenants) — cross-tenant queries never leak between tenants
-- because tenant_id is a hard FK and the admin controller applies the
-- Rule-2 tenant filter on every read.

CREATE TABLE IF NOT EXISTS public.us_tenant_naic_config (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    state_domicile        CHAR(2)        NOT NULL,
    naic_company_code     VARCHAR(10)    NOT NULL,
    naic_group_code       VARCHAR(10),
    fein                  VARCHAR(20)    NOT NULL,
    effective_from        DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to          DATE,
    source_note           VARCHAR(200),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by            UUID,
    updated_by_email      VARCHAR(255),
    CONSTRAINT us_tenant_naic_config_state_domicile_ck
        CHECK (state_domicile ~ '^[A-Z]{2}$'),
    CONSTRAINT us_tenant_naic_config_naic_code_ck
        CHECK (naic_company_code ~ '^[0-9]{1,10}$'),
    CONSTRAINT us_tenant_naic_config_naic_group_code_ck
        CHECK (naic_group_code IS NULL OR naic_group_code ~ '^[0-9]{1,10}$'),
    CONSTRAINT us_tenant_naic_config_fein_ck
        CHECK (fein ~ '^[0-9]{2}-?[0-9]{7}$'),
    CONSTRAINT uq_us_tenant_naic_config_effective UNIQUE (tenant_id, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_us_tenant_naic_config_lookup
    ON public.us_tenant_naic_config (tenant_id, effective_from DESC);

COMMENT ON TABLE public.us_tenant_naic_config IS
    'Per-tenant NAIC company identity (state of domicile, company code, group code, FEIN). US-only. Consumed by NAIC Schedule P/F shapers. Phase 16 §A / Phase 14 REG16.';
