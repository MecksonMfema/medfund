-- =====================================================================
-- V168: Tenant-uploaded regulator XLSX template overrides.
--
-- Phase 16 §0 REG3 — every regulator report (IPEC quarterly, CMS ASR,
-- NAIC Schedule P/F, PMB spend, AML/STR, VAT return, tax-withheld
-- return) has a canonical bundled template shipped inside the shared
-- module. Tenants that have a newer portal-fetched version override
-- the bundle via a row here; highest effective_from ≤ report period
-- wins.
--
-- xlsx_bytes storage rationale (deviation from plan's MinIO):
-- templates are ≤2MB per the RegulatoryTemplateService validation;
-- tenancy-service has no existing MinIO wiring; Postgres bytea keeps
-- backup/restore in one place with the row metadata. Phase 5's
-- regulatory_submission archive uses MinIO — that's per-tenant
-- 7-year retention of bulk XLSX exports and is the correct place
-- for the object store, not this small config surface.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_regulatory_template (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    regulator         VARCHAR(40)   NOT NULL,
    report_key        VARCHAR(80)   NOT NULL,
    version_label     VARCHAR(80)   NOT NULL,
    effective_from    DATE          NOT NULL DEFAULT CURRENT_DATE,
    effective_to      DATE          NULL,
    xlsx_bytes        BYTEA         NOT NULL,
    file_size_bytes   BIGINT        NOT NULL,
    content_hash      VARCHAR(64)   NULL,
    uploaded_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id          UUID          NOT NULL,
    actor_email       VARCHAR(320)  NOT NULL,
    notes             TEXT          NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_regulatory_template_effective
    ON public.tenant_regulatory_template (tenant_id, regulator, report_key, effective_from);

-- Lookup pattern used by RegulatoryTemplateService.load(): highest
-- effective_from ≤ target date for a given tenant/regulator/report_key.
CREATE INDEX IF NOT EXISTS ix_tenant_regulatory_template_lookup
    ON public.tenant_regulatory_template (tenant_id, regulator, report_key, effective_from DESC);

COMMENT ON TABLE public.tenant_regulatory_template IS
    'Tenant-uploaded regulator XLSX overrides. Phase 16 §0 REG3.';
COMMENT ON COLUMN public.tenant_regulatory_template.xlsx_bytes IS
    'Raw XLSX bytes; ≤2MB enforced at upload time by TenantRegulatoryTemplateService.';
COMMENT ON COLUMN public.tenant_regulatory_template.content_hash IS
    'Optional SHA-256 of xlsx_bytes for change-detection / dedupe. Nullable — legacy rows populate on first read.';
