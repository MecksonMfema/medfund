-- Per-tenant expected retention curves for PERSISTENCY_STUDY (actuarial Phase 14).
-- Multi-row config keyed by (insurance_line, cohort_months, effective_from).
-- Seeded with industry-average curves so every tenant has a working default
-- before the first admin edit (see Grill note 10).

CREATE TABLE IF NOT EXISTS public.tenant_persistency_basis (
    id                       UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    insurance_line           VARCHAR(30)    NOT NULL,
    cohort_months            INT            NOT NULL,
    expected_retention_pct   NUMERIC(5,4)   NOT NULL CHECK (expected_retention_pct BETWEEN 0 AND 1),
    source_note              VARCHAR(200),
    effective_from           DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to             DATE,
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by               UUID,
    updated_by_email         VARCHAR(255),
    CONSTRAINT uq_tpb UNIQUE (tenant_id, insurance_line, cohort_months, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_tpb_lookup
    ON public.tenant_persistency_basis (tenant_id, insurance_line, effective_from DESC);

-- Seed industry-average retention curves for every existing tenant.
-- Idempotent: UNIQUE constraint + ON CONFLICT DO NOTHING makes re-runs a no-op.
INSERT INTO public.tenant_persistency_basis
    (tenant_id, insurance_line, cohort_months, expected_retention_pct, source_note, effective_from)
SELECT t.id, x.insurance_line, x.cohort_months, x.expected_retention_pct,
       'industry_default_v1', CURRENT_DATE
FROM public.tenants t
CROSS JOIN (VALUES
    ('HEALTH',   3,  0.90), ('HEALTH',   6,  0.85), ('HEALTH',  12, 0.75), ('HEALTH',  24, 0.65), ('HEALTH',  36, 0.55),
    ('LIFE',     12, 0.92), ('LIFE',     24, 0.85), ('LIFE',    36, 0.78), ('LIFE',    60, 0.65),
    ('FUNERAL',  12, 0.88), ('FUNERAL',  24, 0.78), ('FUNERAL', 36, 0.70), ('FUNERAL', 60, 0.55)
) AS x(insurance_line, cohort_months, expected_retention_pct)
ON CONFLICT ON CONSTRAINT uq_tpb DO NOTHING;
