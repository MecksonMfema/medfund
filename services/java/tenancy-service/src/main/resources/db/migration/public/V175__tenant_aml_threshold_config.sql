-- Phase 25 REG8: Per-tenant AML reporting thresholds.
--
-- The AML/STR periodic summary report needs to know "above what dollar
-- amount is this transaction type reportable in this jurisdiction?".
-- The threshold isn't fixed by country — it's a per-tenant admin
-- decision (a small mutual might use the ZW FIU's statutory USD 10,000
-- as-is; a larger group might tighten it to USD 5,000 to align with a
-- risk-based approach). Effective-dated rows so the threshold history
-- survives audit.
--
-- Composite UNIQUE avoids two overlapping rows for the same
-- (tenant, transaction_type, currency) starting on the same day. A
-- CHECK regex on transaction_type mirrors the values used by the AML
-- alert entity (V167) + the raise DTO.

CREATE TABLE IF NOT EXISTS public.tenant_aml_threshold_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    transaction_type VARCHAR(40) NOT NULL,
    threshold_amount NUMERIC(18,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    effective_to DATE NULL,
    source_note VARCHAR(500) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id UUID NOT NULL,
    actor_email VARCHAR(320) NOT NULL,
    CONSTRAINT tamlt_transaction_type_ck
        CHECK (transaction_type IN ('PREMIUM','CLAIM_PAYOUT','ADVANCE_PAYMENT',
                                    'REFUND','COMMISSION','ADJUSTMENT','OTHER')),
    CONSTRAINT tamlt_threshold_positive_ck CHECK (threshold_amount > 0),
    CONSTRAINT tamlt_currency_len_ck CHECK (length(currency) = 3),
    CONSTRAINT tamlt_effective_range_ck
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT uq_tenant_aml_threshold_effective
        UNIQUE (tenant_id, transaction_type, currency, effective_from)
);

CREATE INDEX IF NOT EXISTS ix_tenant_aml_threshold_config_tenant_from
    ON public.tenant_aml_threshold_config (tenant_id, effective_from DESC);
