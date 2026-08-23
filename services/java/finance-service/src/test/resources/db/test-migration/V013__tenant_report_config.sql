-- Test-migration layer for Phase 11 §A Phase 4 commission-report tests.
-- Mirrors production public migration V130 in tenancy-service. The absent
-- table would let ReportEnablementReader fall back to enabled=TRUE (its
-- onErrorResume default), which prevents the IT from asserting the
-- disabled-via-config 403 case. Bringing the table in gives the IT a
-- controllable feature-toggle surface without the tenancy dependency.
--
-- No FK to public.tenants (tenancy is the source of truth; the finance
-- test schema has no tenants table). Same shape everywhere else.

CREATE TABLE IF NOT EXISTS public.tenant_report_config (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL,
    report_key   VARCHAR(80)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   UUID,
    CONSTRAINT uq_tenant_report_config UNIQUE (tenant_id, report_key)
);

CREATE INDEX IF NOT EXISTS idx_tenant_report_config_tenant_disabled
    ON public.tenant_report_config (tenant_id)
    WHERE enabled = FALSE;

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
