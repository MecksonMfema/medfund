-- Adds the Phase 15 §2 IFRS 17 admin-basis tables to the shared
-- test-migration schema used by the tenancy-service integration tests.
-- Sits alongside V001 (high-cost claimant config) and V002 (Phase 14
-- actuarial bases) so all four share one Flyway history against the common
-- Testcontainers Postgres. Mirrors production V152/V153/V154 shapes.

-- ── tenant_ra_config (mirror of V152) ──────────────────────────────────
CREATE TABLE tenant_ra_config (
    id                        UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    portfolio_id              UUID           NOT NULL,
    methodology               VARCHAR(10)    NOT NULL CHECK (methodology IN ('COC', 'CI')),
    coc_rate                  NUMERIC(5,4),
    target_confidence_level   NUMERIC(5,4),
    source_note               VARCHAR(200),
    effective_from            DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to              DATE,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by                UUID,
    updated_by_email          VARCHAR(255),
    CONSTRAINT tenant_ra_config_methodology_params_ck
        CHECK ((methodology = 'COC' AND coc_rate IS NOT NULL AND target_confidence_level IS NULL)
            OR (methodology = 'CI' AND target_confidence_level IS NOT NULL AND coc_rate IS NULL)),
    CONSTRAINT tenant_ra_config_coc_range_ck
        CHECK (coc_rate IS NULL OR (coc_rate > 0 AND coc_rate < 1)),
    CONSTRAINT tenant_ra_config_ci_range_ck
        CHECK (target_confidence_level IS NULL OR (target_confidence_level > 0 AND target_confidence_level < 1)),
    CONSTRAINT uq_tenant_ra_config UNIQUE (tenant_id, portfolio_id, effective_from)
);
CREATE INDEX idx_tenant_ra_config_lookup
    ON tenant_ra_config (tenant_id, portfolio_id, effective_from DESC);

-- ── tenant_yield_curve (mirror of V153) ────────────────────────────────
CREATE TABLE tenant_yield_curve (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    currency          VARCHAR(3)     NOT NULL,
    tenor_months      INT            NOT NULL CHECK (tenor_months BETWEEN 1 AND 600),
    spot_rate         NUMERIC(9,7)   NOT NULL,
    source            VARCHAR(20)    NOT NULL DEFAULT 'ADMIN'
        CHECK (source IN ('ADMIN', 'RBZ_AUTO', 'SARB_AUTO', 'BACKFILL_FALLBACK')),
    effective_from    DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to      DATE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    updated_by_email  VARCHAR(255),
    CONSTRAINT uq_tenant_yield_curve UNIQUE (tenant_id, currency, tenor_months, effective_from)
);
CREATE INDEX idx_tenant_yield_curve_lookup
    ON tenant_yield_curve (tenant_id, currency, effective_from DESC, tenor_months);

-- ── tenant_expense_assumption (mirror of V154) ─────────────────────────
CREATE TABLE tenant_expense_assumption (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    insurance_line      VARCHAR(30)    NOT NULL,
    expense_type        VARCHAR(30)    NOT NULL
        CHECK (expense_type IN ('ACQUISITION', 'MAINTENANCE', 'CLAIMS_HANDLING', 'OVERHEAD', 'OTHER')),
    amount_per_policy   NUMERIC(18,2)  NOT NULL CHECK (amount_per_policy >= 0),
    currency            VARCHAR(3)     NOT NULL,
    source_note         VARCHAR(200),
    effective_from      DATE           NOT NULL DEFAULT CURRENT_DATE,
    effective_to        DATE,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by          UUID,
    updated_by_email    VARCHAR(255),
    CONSTRAINT uq_tenant_expense_assumption
        UNIQUE (tenant_id, insurance_line, expense_type, currency, effective_from)
);
CREATE INDEX idx_tenant_expense_assumption_lookup
    ON tenant_expense_assumption (tenant_id, insurance_line, effective_from DESC);

-- Extend the GRANTs so the tenant-scoped SET ROLE can read/write the new tables.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON tenant_ra_config, tenant_yield_curve, tenant_expense_assumption
    TO public_role;
