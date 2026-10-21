-- Per-tenant morbidity basis selection + multiplier for MORBIDITY_STUDY (Phase 14).
-- basis_name references an ai-service YAML table (see services/python/ai-service/app/actuarial/basis_tables/morbidity/*.yaml).
-- morbidity_multiplier lets a tenant scale the raw basis for local experience overlay (default 1.0 = raw table).

CREATE TABLE IF NOT EXISTS public.tenant_morbidity_basis (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    insurance_line        VARCHAR(30)    NOT NULL,
    basis_name            VARCHAR(80)    NOT NULL,
    morbidity_multiplier  NUMERIC(5,4)   NOT NULL DEFAULT 1.0000 CHECK (morbidity_multiplier > 0),
    effective_from        DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to          DATE,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by            UUID,
    updated_by_email      VARCHAR(255),
    CONSTRAINT uq_tmbid UNIQUE (tenant_id, insurance_line, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_tmbid_lookup
    ON public.tenant_morbidity_basis (tenant_id, insurance_line, effective_from DESC);
