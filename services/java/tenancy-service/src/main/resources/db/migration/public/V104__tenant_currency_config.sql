-- Platform-level currency configuration per tenant.
CREATE TABLE IF NOT EXISTS public.tenant_currency_config (
    id            UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID    NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    currency_code CHAR(3) NOT NULL,
    is_default    BOOLEAN NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, currency_code)
);

CREATE INDEX IF NOT EXISTS idx_tenant_currency_tenant ON public.tenant_currency_config(tenant_id);
