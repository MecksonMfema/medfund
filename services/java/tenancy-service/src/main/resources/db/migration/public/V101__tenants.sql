-- Platform-level tenants table — stored once in the public schema.
-- Each row represents an organization (tenant) on the MedFund platform.

CREATE TABLE IF NOT EXISTS public.tenants (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(200) NOT NULL,
    slug                VARCHAR(100) NOT NULL UNIQUE,
    domain              VARCHAR(255),
    schema_name         VARCHAR(100),
    plan_id             UUID,
    status              VARCHAR(20)  NOT NULL DEFAULT 'active',
    settings            JSONB        NOT NULL DEFAULT '{}',
    branding            JSONB        NOT NULL DEFAULT '{}',
    contact_email       VARCHAR(255),
    country_code        CHAR(2),
    timezone            VARCHAR(100) NOT NULL DEFAULT 'UTC',
    membership_model    VARCHAR(50)  NOT NULL DEFAULT 'individual',
    keycloak_realm      VARCHAR(100),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_tenants_slug        ON public.tenants(slug);
CREATE INDEX        IF NOT EXISTS idx_tenants_status      ON public.tenants(status);
CREATE INDEX        IF NOT EXISTS idx_tenants_domain      ON public.tenants(domain) WHERE domain IS NOT NULL;
CREATE INDEX        IF NOT EXISTS idx_tenants_keycloak    ON public.tenants(keycloak_realm) WHERE keycloak_realm IS NOT NULL;
