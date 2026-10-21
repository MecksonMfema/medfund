-- Per-tenant tax configuration (Phase 16 §C / Phase 19 REG9).
-- Multi-row effective-dated admin surface: VAT + Withholding rates per
-- transaction category × currency. Consumed by the VAT Return shaper
-- (Phase 20) and the TaxWithheldReturn shaper (Phase 21) to source
-- statutory rates per (tenant, country, tax_type, category, currency).
--
-- Table lives in public schema (platform-wide config alongside
-- public.tenants) — cross-tenant queries never leak between tenants
-- because tenant_id is a hard FK and the admin controller applies the
-- Rule-2 tenant filter on every read.

CREATE TABLE IF NOT EXISTS public.tenant_tax_config (
    id                       UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    country_code             CHAR(2)        NOT NULL,
    tax_type                 VARCHAR(20)    NOT NULL,
    transaction_category     VARCHAR(20)    NOT NULL,
    currency                 VARCHAR(3)     NOT NULL,
    rate                     NUMERIC(6,5)   NOT NULL,
    is_registered            BOOLEAN        NOT NULL DEFAULT TRUE,
    registration_number      VARCHAR(80),
    effective_from           DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to             DATE,
    source_note              VARCHAR(200),
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by               UUID,
    updated_by_email         VARCHAR(255),
    CONSTRAINT tenant_tax_config_country_ck
        CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT tenant_tax_config_tax_type_ck
        CHECK (tax_type IN ('VAT', 'WITHHOLDING')),
    CONSTRAINT tenant_tax_config_txn_category_ck
        CHECK (transaction_category IN ('PREMIUM', 'CLAIM_PAID', 'ADMIN_FEE', 'COMMISSION', 'OTHER')),
    CONSTRAINT tenant_tax_config_currency_ck
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT tenant_tax_config_rate_ck
        CHECK (rate >= 0 AND rate < 1),
    CONSTRAINT uq_tenant_tax_config_effective
        UNIQUE (tenant_id, tax_type, transaction_category, currency, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_tenant_tax_config_lookup
    ON public.tenant_tax_config (tenant_id, tax_type, transaction_category, currency, effective_from DESC);

COMMENT ON TABLE public.tenant_tax_config IS
    'Per-tenant tax rates (VAT + Withholding) by transaction category and currency. Consumed by VAT Return + TaxWithheldReturn shapers. Phase 16 §C / Phase 19 REG9.';
