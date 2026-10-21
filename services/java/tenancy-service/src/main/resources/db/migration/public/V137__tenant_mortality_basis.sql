-- Per-tenant mortality basis selection + multiplier for MORTALITY_STUDY (Phase 14).
-- basis_name references an ai-service YAML table (see services/python/ai-service/app/actuarial/basis_tables/mortality/*.yaml).
-- mortality_multiplier lets a tenant scale the raw basis for local experience overlay (default 1.0 = raw table).

CREATE TABLE IF NOT EXISTS public.tenant_mortality_basis (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    insurance_line        VARCHAR(30)    NOT NULL,
    basis_name            VARCHAR(80)    NOT NULL,
    mortality_multiplier  NUMERIC(5,4)   NOT NULL DEFAULT 1.0000 CHECK (mortality_multiplier > 0),
    effective_from        DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to          DATE,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by            UUID,
    updated_by_email      VARCHAR(255),
    CONSTRAINT uq_tmb UNIQUE (tenant_id, insurance_line, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_tmb_lookup
    ON public.tenant_mortality_basis (tenant_id, insurance_line, effective_from DESC);
