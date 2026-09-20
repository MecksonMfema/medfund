-- Mirror of public.provider_tenants + public.provider_insurance_lines
-- (tenancy-service public/V185) for the claims-service ITs. Every provider
-- join in the report + list repositories is now membership-guarded against
-- provider_tenants, so the fixtures need somewhere to put the membership
-- row that makes a seeded provider visible to the IT tenant.
--
-- The IT schema IS `public` (see V001 header), so the production SQL's
-- `public.provider_tenants` resolves to these tables verbatim — no
-- search_path trickery involved.
--
-- Shapes are trimmed to the columns the claims-service reads: the contract
-- metadata columns (credit limit, tariff agreement, effective dates) that
-- V185 carries have no reader in this service and no fixture needs them.

CREATE TABLE IF NOT EXISTS provider_tenants (
    provider_id   UUID        NOT NULL,
    tenant_id     UUID        NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    network_tier  VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    in_network    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (provider_id, tenant_id)
);

CREATE TABLE IF NOT EXISTS provider_insurance_lines (
    provider_id     UUID        NOT NULL,
    insurance_line  VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (provider_id, insurance_line)
);

-- No FK onto providers: AbstractClaimsReportIT TRUNCATEs `providers`
-- between tests without CASCADE, and an inbound FK would break that.
-- Same reasoning as V006's dependants table.

-- The tenant role established in V001 was granted CRUD on the tables that
-- existed then; new tables need their own grant so the tenant connection
-- (SET ROLE public_role) can read and seed them.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON provider_tenants, provider_insurance_lines TO public_role;
