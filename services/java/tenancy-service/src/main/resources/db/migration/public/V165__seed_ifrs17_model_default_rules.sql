-- Phase 15 §9 (I27): seed 8 industry-default IFRS17_MODEL rules per tenant so
-- every tenant has a working measurement-model selection before the first admin
-- edit. Idempotent: the tenant_rules(tenant_id, rule_key) UNIQUE constraint
-- + ON CONFLICT DO NOTHING makes re-runs a no-op — safe to re-apply.
--
-- Rule mapping (see Ifrs17ModelTemplates for template shape + priorities):
--   HEALTH / TRAVEL / VEHICLE / PROPERTY / GROUP → PAA (PL_ONLY finance expense)
--   LIFE / FUNERAL / DISABILITY                  → GMM (OCI_OPTION finance expense)
--
-- Each row's `definition` JSONB matches the RuleDefinition shape consumed by
-- DrlCompiler: `{"category","priority","conditions":{"operator","items":[{...}]},
-- "action":{"type":"SELECT_IFRS17_MODEL","value":"IFRS17_MODEL:<...>","message"}}`.
-- The value payload is decoded by SelectIfrs17ModelEmitter into a
-- `$portfolio.applyModel(...)` DRL consequence.

INSERT INTO public.tenant_rules
    (tenant_id, rule_key, name, description, category, template_id, priority, definition, enabled)
SELECT
    t.id,
    'ifrs17-default-' || lower(x.insurance_line),
    'IFRS 17 default — ' || x.insurance_line || ' → ' || x.model,
    'Industry-default IFRS 17 measurement-model selection seeded on tenant provisioning. '
        || 'Override or disable via the Rules Engine UI once actuarial signs off on a '
        || 'tenant-specific model choice.',
    'IFRS17_MODEL',
    x.template_id,
    x.priority,
    jsonb_build_object(
        'category',   'IFRS17_MODEL',
        'priority',   x.priority,
        'enabled',    true,
        'conditions', jsonb_build_object(
            'operator', 'AND',
            'items',    jsonb_build_array(
                jsonb_build_object(
                    'field',    'portfolio.insuranceLine',
                    'operator', 'EQUALS',
                    'value',    x.insurance_line))),
        'action',     jsonb_build_object(
            'type',    'SELECT_IFRS17_MODEL',
            'value',   x.action_value,
            'message', 'industry_default_v1 — ' || x.model || ' for ' || x.insurance_line)),
    true
FROM public.tenants t
CROSS JOIN (VALUES
    ('HEALTH',    'PAA', 'I90 - PAA (Premium Allocation Approach)',   100, 'IFRS17_MODEL:PAA:TIME:null:PL_ONLY'),
    ('TRAVEL',    'PAA', 'I90 - PAA (Premium Allocation Approach)',   100, 'IFRS17_MODEL:PAA:TIME:null:PL_ONLY'),
    ('VEHICLE',   'PAA', 'I90 - PAA (Premium Allocation Approach)',   100, 'IFRS17_MODEL:PAA:TIME:null:PL_ONLY'),
    ('PROPERTY',  'PAA', 'I90 - PAA (Premium Allocation Approach)',   100, 'IFRS17_MODEL:PAA:TIME:null:PL_ONLY'),
    ('GROUP',     'PAA', 'I90 - PAA (Premium Allocation Approach)',   100, 'IFRS17_MODEL:PAA:TIME:null:PL_ONLY'),
    ('LIFE',      'GMM', 'I91 - GMM (General Measurement Model)',      90, 'IFRS17_MODEL:GMM:TIME:null:OCI_OPTION'),
    ('FUNERAL',   'GMM', 'I91 - GMM (General Measurement Model)',      90, 'IFRS17_MODEL:GMM:TIME:null:OCI_OPTION'),
    ('DISABILITY','GMM', 'I91 - GMM (General Measurement Model)',      90, 'IFRS17_MODEL:GMM:TIME:null:OCI_OPTION')
) AS x(insurance_line, model, template_id, priority, action_value)
ON CONFLICT (tenant_id, rule_key) DO NOTHING;
