-- Phase 16 §C / Phase 19 REG9: seed per-country default tax rates per tenant
-- so a first-time tax return doesn't 500 on a missing rate row. Idempotent
-- via UNIQUE (tenant_id, tax_type, transaction_category, currency,
-- effective_from) + ON CONFLICT DO NOTHING — safe to re-run.
--
-- Coverage rationale (v1 defaults):
--   Two active countries in v1 — ZW and ZA. Both regulators exempt
--   insurance premiums from VAT (per ZIMRA + SARS guidance) but tax
--   admin fees + non-insurance services at the standard rate; the two
--   countries also apply withholding tax on cross-border commissions
--   and professional fees paid to non-residents. Tenants top the
--   defaults up (or override) via /tenant/admin/settings/tax-config
--   once their local tax adviser confirms their registration status.
--
-- Rates below are the statutory defaults as of 2026-08 — tenant admins
-- are responsible for supersession when ZIMRA/SARS revises. Rate is a
-- decimal (0.15 = 15 %).

INSERT INTO public.tenant_tax_config
    (tenant_id, country_code, tax_type, transaction_category, currency, rate,
     is_registered, registration_number, effective_from, source_note)
SELECT
    t.id,
    x.country_code,
    x.tax_type,
    x.transaction_category,
    x.currency,
    x.rate,
    TRUE,
    NULL,
    DATE '2026-01-01',
    'industry_default_v1 — replace with your registered rate + registration_number'
FROM public.tenants t
CROSS JOIN (VALUES
    -- ── Zimbabwe (ZWL) ─────────────────────────────────────────────────────
    ('ZW', 'VAT',         'PREMIUM',    'ZWL', 0.00000),
    ('ZW', 'VAT',         'ADMIN_FEE',  'ZWL', 0.15000),
    ('ZW', 'VAT',         'COMMISSION', 'ZWL', 0.15000),
    ('ZW', 'VAT',         'OTHER',      'ZWL', 0.15000),
    ('ZW', 'WITHHOLDING', 'COMMISSION', 'ZWL', 0.10000),
    ('ZW', 'WITHHOLDING', 'OTHER',      'ZWL', 0.15000),
    -- ── South Africa (ZAR) ─────────────────────────────────────────────────
    ('ZA', 'VAT',         'PREMIUM',    'ZAR', 0.00000),
    ('ZA', 'VAT',         'ADMIN_FEE',  'ZAR', 0.15000),
    ('ZA', 'VAT',         'COMMISSION', 'ZAR', 0.15000),
    ('ZA', 'VAT',         'OTHER',      'ZAR', 0.15000),
    ('ZA', 'WITHHOLDING', 'COMMISSION', 'ZAR', 0.15000),
    ('ZA', 'WITHHOLDING', 'OTHER',      'ZAR', 0.15000)
) AS x(country_code, tax_type, transaction_category, currency, rate)
WHERE t.country_code = x.country_code
ON CONFLICT (tenant_id, tax_type, transaction_category, currency, effective_from) DO NOTHING;
