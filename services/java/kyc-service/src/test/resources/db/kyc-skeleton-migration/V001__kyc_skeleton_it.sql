-- Minimal test schema for KycSkeletonIT (Plan A Phase 2). kyc-service owns NO
-- tenant DDL in production (tenancy-service migrates kyc_verifications +
-- kyc_documents via the tenant-family V278); the IT harness therefore shapes
-- the subset it touches here. Follows the claims-service test-migration
-- pattern: a `tenants` row so TenantAwareConnectionFactory.lookupSchemaName
-- resolves to `public`, an empty `scheduled_job_configs` shim, and a
-- `public_role` (granted CRUD on all public tables) so the tenant-scoped
-- SET ROLE public_role on connection acquisition doesn't 500.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Role for the tenant-scoped SET ROLE ─────────────────────────────
-- The "tenant" IS the public schema here, so the role name is public_role
-- with broad grants (same collapse as the claims/finance test migrations).
DO $$ BEGIN
    CREATE ROLE public_role;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
GRANT public_role TO CURRENT_USER;

-- ── Shared infra shims ───────────────────────────────────────────────
CREATE TABLE tenants (
    id           UUID         PRIMARY KEY,
    slug         VARCHAR(63)  NOT NULL DEFAULT 'it',
    schema_name  VARCHAR(63)  NOT NULL DEFAULT 'public'
);

CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

-- Seed the IT tenant. KycSkeletonIT seeds every row against this id.
INSERT INTO tenants (id, slug, schema_name)
VALUES ('00000000-0000-4000-8000-000000000001', 'it', 'public');

-- ── Entitlement table the EntitlementAspect registry reads ────────────
-- Counterpart of public.V187; stripped like the tenancy platform-settings-it
-- migration (no FK to public.tenants). The @RequiresEntitlement(KYC) gate on
-- VerificationController reads this via the shared registry on every call.
CREATE TABLE public.tenant_module_entitlements (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL,
    module_key  VARCHAR(40) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  UUID,
    version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_module_it UNIQUE (tenant_id, module_key)
);

-- ── Party-neutral KYC tables (counterpart of tenant-family V278) ──────
CREATE TABLE kyc_verifications (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type       VARCHAR(24) NOT NULL,
    subject_id         UUID NOT NULL,
    insurance_line     VARCHAR(24),
    verification_status VARCHAR(24) NOT NULL DEFAULT 'unverified',
    verification_level  SMALLINT NOT NULL DEFAULT 0,
    method             VARCHAR(16),
    ocr_prediction_id       UUID,
    screening_prediction_id UUID,
    screening_result   VARCHAR(16),
    verified_at        TIMESTAMPTZ,
    verified_by        UUID,
    verified_by_email  VARCHAR(255),
    rejected_reason    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by         UUID,
    CONSTRAINT kyc_subject_type_chk CHECK (subject_type IN ('MEMBER','DEPENDANT','PROVIDER','POLICYHOLDER')),
    CONSTRAINT kyc_status_chk CHECK (verification_status IN ('unverified','pending_verification','verified','rejected','expired')),
    CONSTRAINT kyc_verifications_subject_uq UNIQUE (subject_type, subject_id)
);

CREATE TABLE kyc_documents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type      VARCHAR(24) NOT NULL,
    subject_id        UUID NOT NULL,
    document_type     VARCHAR(32) NOT NULL,
    file_service_ref  VARCHAR(255) NOT NULL,
    bucket            VARCHAR(128) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    sha256            VARCHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'uploaded',
    uploaded_by       UUID NOT NULL,
    uploaded_by_email VARCHAR(255) NOT NULL,
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT kyc_documents_type_chk CHECK (document_type IN
        ('NATIONAL_ID','PASSPORT','DRIVERS_LICENSE','PROOF_OF_ADDRESS','SELFIE','BIRTH_CERTIFICATE','OTHER')),
    CONSTRAINT kyc_documents_status_chk CHECK (status IN ('uploaded','verified','rejected','expired'))
);

-- ── Grants for the tenant role ───────────────────────────────────────
-- SET ROLE public_role must be able to read the entitlement row and the
-- verification tables. TRUNCATE is deliberately withheld (the IT truncates
-- on the session user, never the tenant role).
GRANT USAGE ON SCHEMA public TO public_role;
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO public_role;
