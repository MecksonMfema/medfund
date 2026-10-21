-- Stripped-down mirror of the platform tables the provider-membership
-- repositories touch. Mirrors public/V101 (tenants), V105+V106 (providers),
-- V111 (currencies) and V185 (the two junctions) closely enough that the
-- repository SQL, the composite PKs and the CHECK constraints are exercised
-- against real Postgres.
--
-- Shared by ProviderTenantRepositoryIT and ProviderInsuranceLineRepositoryIT:
-- one location, one checksum, so both classes can run on the shared
-- per-JVM container without a Flyway validate collision.

CREATE TABLE IF NOT EXISTS public.tenants (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(200) NOT NULL DEFAULT 'IT Tenant',
    slug         VARCHAR(100) NOT NULL DEFAULT 'it-tenant',
    schema_name  VARCHAR(100),
    status       VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.currencies (
    code            CHAR(3)      PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    symbol          VARCHAR(10)  NOT NULL,
    decimal_places  SMALLINT     NOT NULL DEFAULT 2,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO public.currencies (code, name, symbol) VALUES ('USD', 'United States Dollar', '$')
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS public.providers (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(200) NOT NULL,
    provider_type       VARCHAR(50),
    registration_number VARCHAR(50),
    specialty           VARCHAR(100),
    email               VARCHAR(255),
    phone               VARCHAR(50),
    city                VARCHAR(100),
    address             TEXT,
    banking_details     TEXT,
    keycloak_user_id    VARCHAR(255),
    status              VARCHAR(30)  NOT NULL DEFAULT 'pending_verification',
    network_tier        VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID
);

CREATE TABLE IF NOT EXISTS public.provider_tenants (
    provider_id              UUID          NOT NULL REFERENCES public.providers(id) ON DELETE RESTRICT,
    tenant_id                UUID          NOT NULL REFERENCES public.tenants(id)   ON DELETE RESTRICT,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'active',
    network_tier             VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    in_network               BOOLEAN       NOT NULL DEFAULT TRUE,
    contract_effective_from  DATE          NOT NULL DEFAULT CURRENT_DATE,
    contract_effective_to    DATE          NULL,
    credit_limit             NUMERIC(15,2) NULL,
    credit_limit_currency    CHAR(3)       NULL REFERENCES public.currencies(code),
    tariff_agreement_id      UUID          NULL,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by               UUID          NULL,
    updated_by               UUID          NULL,
    PRIMARY KEY (provider_id, tenant_id),
    CONSTRAINT provider_tenants_status_ck CHECK (status IN ('active','pending','terminated','suspended')),
    CONSTRAINT provider_tenants_tier_ck   CHECK (network_tier IN ('STANDARD','TIER_1','TIER_2','TIER_3')),
    CONSTRAINT provider_tenants_dates_ck  CHECK (contract_effective_to IS NULL OR contract_effective_to >= contract_effective_from)
);

CREATE INDEX IF NOT EXISTS ix_provider_tenants_tenant ON public.provider_tenants (tenant_id);

CREATE TABLE IF NOT EXISTS public.provider_insurance_lines (
    provider_id     UUID        NOT NULL REFERENCES public.providers(id) ON DELETE CASCADE,
    insurance_line  VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (provider_id, insurance_line),
    CONSTRAINT provider_insurance_lines_ck CHECK (insurance_line IN
        ('HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'))
);
