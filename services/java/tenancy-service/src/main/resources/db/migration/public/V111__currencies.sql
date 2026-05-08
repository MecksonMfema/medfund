-- Master currency registry (ISO 4217). Tenants choose from this catalogue.
-- Seeded with the target-market set; super admin can add more via the
-- /api/v2/currencies/import-iso4217 action.

CREATE TABLE IF NOT EXISTS public.currencies (
    code            CHAR(3)      PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    symbol          VARCHAR(10)  NOT NULL,
    decimal_places  SMALLINT     NOT NULL DEFAULT 2 CHECK (decimal_places BETWEEN 0 AND 4),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_currencies_active ON public.currencies(is_active) WHERE is_active = TRUE;

INSERT INTO public.currencies (code, name, symbol, decimal_places) VALUES
    ('USD', 'United States Dollar', '$',    2),
    ('ZWL', 'Zimbabwean Dollar',     'ZWL$', 2),
    ('ZAR', 'South African Rand',    'R',    2),
    ('BWP', 'Botswanan Pula',        'P',    2),
    ('EUR', 'Euro',                  '€',    2),
    ('GBP', 'British Pound Sterling', '£',   2),
    ('KES', 'Kenyan Shilling',       'KSh',  2),
    ('NGN', 'Nigerian Naira',        '₦',    2),
    ('ZMW', 'Zambian Kwacha',        'K',    2)
ON CONFLICT (code) DO NOTHING;
