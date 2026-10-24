-- Test-migration counterpart of V187__tenant_module_entitlements.sql. The IT
-- harness does not apply production migrations, so the table an IT touches has
-- to be shaped here. Stripped down: no FK to public.tenants and no
-- provision_tenant_role grant (the IT connects as the owner, not a tenant role).
CREATE TABLE IF NOT EXISTS public.tenant_module_entitlements (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL,
    module_key  VARCHAR(40) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  UUID,
    version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_module_it UNIQUE (tenant_id, module_key)
);
