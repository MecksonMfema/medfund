-- Immutable exchange-rate snapshots. Once a row is recorded for a given
-- (base, quote, date, source) triple it is never updated — corrections are
-- modeled by inserting a new row with a different source or the same source
-- on a later rate_date. Conversion call sites store the row id alongside the
-- transaction so historical reports can be reconstructed exactly.

CREATE TABLE IF NOT EXISTS public.exchange_rates (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency   CHAR(3)        NOT NULL REFERENCES public.currencies(code),
    quote_currency  CHAR(3)        NOT NULL REFERENCES public.currencies(code),
    rate            DECIMAL(19,10) NOT NULL CHECK (rate > 0),
    rate_date       DATE           NOT NULL,
    source          VARCHAR(50)    NOT NULL DEFAULT 'manual',
    tenant_id       UUID           REFERENCES public.tenants(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by      UUID,
    CONSTRAINT chk_exchange_rates_distinct CHECK (base_currency <> quote_currency),
    CONSTRAINT uq_exchange_rates UNIQUE (base_currency, quote_currency, rate_date, source, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_exchange_rates_lookup
    ON public.exchange_rates (base_currency, quote_currency, rate_date DESC);

CREATE INDEX IF NOT EXISTS idx_exchange_rates_tenant
    ON public.exchange_rates (tenant_id) WHERE tenant_id IS NOT NULL;
