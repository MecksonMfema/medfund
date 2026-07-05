-- Minimal schema for the GroupNumberService IT. Only the columns the
-- service actually reads / writes are present:
--
--   • public.tenants — three group_number_* columns matching the
--     production V125 migration. Defaults match too so the "no
--     tenant row" fallback path in loadScheme() still resolves to
--     the same shape as a fresh tenant with the default row.
--
--   • public.tenants shims for the platform infra that queries this
--     table on startup (schema_name for TenantAwareConnectionFactory).
--
--   • groups — needs registration_number + the shape GroupRepository
--     scans. Only the columns GroupNumberService.existsBy… reads.
--
-- Everything else that GroupService also writes to (members,
-- dependants, keycloak sync tables, etc.) is out of scope for this
-- IT — we're testing the NUMBER generation seam, not group create
-- end-to-end.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenants (
    id                           UUID          PRIMARY KEY,
    schema_name                  VARCHAR(63)   NOT NULL DEFAULT 'public',
    group_number_prefix          VARCHAR(20)   NOT NULL DEFAULT 'GRP-',
    group_number_suffix          VARCHAR(20)   NOT NULL DEFAULT '',
    group_number_random_length   INTEGER       NOT NULL DEFAULT 6
        CHECK (group_number_random_length BETWEEN 3 AND 12)
);

CREATE TABLE groups (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(200)  NOT NULL,
    registration_number   VARCHAR(100)
);

-- Mirrors the tenant-side V045 partial UNIQUE index so a race-window
-- probe miss doesn't slip a duplicate through even in the IT.
CREATE UNIQUE INDEX IF NOT EXISTS groups_registration_number_unique
    ON groups (registration_number)
 WHERE registration_number IS NOT NULL;

-- Empty scheduled_job_configs shim so ScheduledJobRepository's
-- startup tick doesn't 42P01 during context boot — same reason
-- SchemeServiceIT + BillingServiceChargePreviewIT each carry the shim.
CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);
