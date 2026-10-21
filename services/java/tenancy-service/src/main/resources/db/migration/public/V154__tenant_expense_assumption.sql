-- Per-tenant, per-line expense assumptions for IFRS 17 GMM fulfilment cash
-- flow projection (Phase 15 §2 / I5 + I23). Multi-row config keyed by
-- (tenant_id, insurance_line, expense_type, currency, effective_from) with
-- amounts stored in the tenant's chosen assumption currency.
--
-- Renumbered from plan V141 to V154 at implement time (same reason as V152).

CREATE TABLE IF NOT EXISTS public.tenant_expense_assumption (
    id                  UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
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

CREATE INDEX IF NOT EXISTS idx_tenant_expense_assumption_lookup
    ON public.tenant_expense_assumption (tenant_id, insurance_line, effective_from DESC);
