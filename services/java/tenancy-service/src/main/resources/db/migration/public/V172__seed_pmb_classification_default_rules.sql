-- Phase 17 §B REG7: seed a representative sample of CMS Prescribed Minimum
-- Benefit rules per tenant so every scheme has classification coverage before
-- the first admin edit. Idempotent: tenant_rules(tenant_id, rule_key) UNIQUE
-- + ON CONFLICT DO NOTHING makes re-runs a no-op — safe to re-apply.
--
-- Coverage rationale (industry_default_v1):
--   The full CMS PMB catalogue is ~270 conditions; seeding all of them via
--   a migration is unwieldy (large squash-migration, per-row edits break
--   Flyway checksums, and the CMS revises the list annually). Ship a
--   representative sample of high-frequency PMBs covering the six top-of-mind
--   condition families (respiratory, cardiac, metabolic, oncology, mental
--   health, chronic kidney). Tenants top this up per their scheme mix via
--   the Rules Engine UI; PmbBackfillJob re-classifies historical claims
--   after each edit.
--
-- Rule shape (matches DrlCompiler + SetPmbClassificationEmitter):
--   {"category":"PMB_CLASSIFICATION","priority":100,"enabled":true,
--    "conditions":{"operator":"AND",
--                  "items":[{"field":"pmbClassification.diagnosisCode",
--                            "operator":"EQUALS","value":"<ICD-10>"}]},
--    "action":{"type":"SET_PMB_CLASSIFICATION",
--              "value":"PMB_CONDITION_CODE:<code>",
--              "message":"industry_default_v1 — <condition-name>"}}
--
-- Auto-fires only when claims-service's RulesEnginePmbClassifier focuses the
-- PMB_CLASSIFICATION agenda group at adjudication + backfill time (see
-- DrlCompiler.AGENDA_GATED_CATEGORIES).

INSERT INTO public.tenant_rules
    (tenant_id, rule_key, name, description, category, template_id, priority, definition, enabled)
SELECT
    t.id,
    'pmb-default-' || lower(replace(x.icd_code, '.', '-')),
    'PMB — ' || x.condition_name || ' (' || x.icd_code || ')',
    'Industry-default PMB classification seeded on tenant provisioning. '
        || 'Sourced from CMS Prescribed Minimum Benefit list (representative sample; '
        || 'the full ~270-condition catalogue is authored via the Rules Engine UI). '
        || 'Override or disable via /tenant/admin/rules once your scheme mix is confirmed. '
        || 'Runs agenda-gated at adjudication (post rule-sweep) and via PmbBackfillJob.',
    'PMB_CLASSIFICATION',
    'PMB1 - Match by ICD diagnosis code',
    100,
    jsonb_build_object(
        'category',   'PMB_CLASSIFICATION',
        'priority',   100,
        'enabled',    true,
        'conditions', jsonb_build_object(
            'operator', 'AND',
            'items',    jsonb_build_array(
                jsonb_build_object(
                    'field',    'pmbClassification.diagnosisCode',
                    'operator', 'EQUALS',
                    'value',    x.icd_code))),
        'action',     jsonb_build_object(
            'type',    'SET_PMB_CLASSIFICATION',
            'value',   'PMB_CONDITION_CODE:' || x.pmb_code,
            'message', 'industry_default_v1 — ' || x.condition_name)),
    true
FROM public.tenants t
CROSS JOIN (VALUES
    -- ── Respiratory ────────────────────────────────────────────────────────
    ('A15.0', 'PMB-001', 'Pulmonary tuberculosis'),
    ('J45.0', 'PMB-013', 'Predominantly allergic asthma'),
    ('J44.9', 'PMB-014', 'Chronic obstructive pulmonary disease'),
    -- ── Cardiac ────────────────────────────────────────────────────────────
    ('I10',   'PMB-020', 'Essential hypertension'),
    ('I21.9', 'PMB-021', 'Acute myocardial infarction'),
    ('I50.9', 'PMB-022', 'Congestive heart failure'),
    -- ── Metabolic ──────────────────────────────────────────────────────────
    ('E10.9', 'PMB-030', 'Type 1 diabetes mellitus'),
    ('E11.9', 'PMB-031', 'Type 2 diabetes mellitus'),
    -- ── Oncology ───────────────────────────────────────────────────────────
    ('C50.9', 'PMB-040', 'Malignant neoplasm of breast'),
    ('C61',   'PMB-041', 'Malignant neoplasm of prostate'),
    ('C34.9', 'PMB-042', 'Malignant neoplasm of bronchus / lung'),
    -- ── Mental health ──────────────────────────────────────────────────────
    ('F20.9', 'PMB-050', 'Schizophrenia'),
    ('F31.9', 'PMB-051', 'Bipolar affective disorder'),
    ('F33.9', 'PMB-052', 'Major depressive disorder recurrent'),
    -- ── Chronic kidney ─────────────────────────────────────────────────────
    ('N18.6', 'PMB-060', 'End-stage renal disease')
) AS x(icd_code, pmb_code, condition_name)
ON CONFLICT (tenant_id, rule_key) DO NOTHING;
