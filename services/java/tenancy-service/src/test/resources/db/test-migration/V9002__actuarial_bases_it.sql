-- Adds the Phase 14 §2 actuarial basis tables to the shared test-migration
-- schema used by the tenancy-service integration tests. Sits alongside V001
-- (high-cost claimant config) so both share one Flyway history against the
-- common Testcontainers Postgres. Mirrors production V136/V137/V138 shapes.

-- Extra tenant used by cross-tenant-rejection assertions in the basis ITs.
-- Idempotent — ON CONFLICT keeps the earlier V001 rows intact.
INSERT INTO tenants (id, slug, schema_name)
VALUES ('00000000-0000-4000-8000-000000000099', 'it-other', 'public')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE tenant_persistency_basis (
    id                       UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
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
CREATE INDEX idx_tpb_lookup ON tenant_persistency_basis (tenant_id, insurance_line, effective_from DESC);

CREATE TABLE tenant_mortality_basis (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
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
CREATE INDEX idx_tmb_lookup ON tenant_mortality_basis (tenant_id, insurance_line, effective_from DESC);

CREATE TABLE tenant_morbidity_basis (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
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
CREATE INDEX idx_tmbid_lookup ON tenant_morbidity_basis (tenant_id, insurance_line, effective_from DESC);

-- Extend the GRANTs so the tenant-scoped SET ROLE can read/write the new tables.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON tenant_persistency_basis, tenant_mortality_basis, tenant_morbidity_basis
    TO public_role;
