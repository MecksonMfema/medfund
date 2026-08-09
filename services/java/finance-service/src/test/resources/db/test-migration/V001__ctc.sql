-- Minimal test schema for CtcPaymentService integration tests. Mirrors the
-- columns the production tenant-side migrations create for `ctc_payments`
-- plus the two tables the CtcPaymentQueryRepository joins into every row
-- (`members`, `groups`). Lives in `public` so the test does not have to
-- spin a tenant schema; tenant isolation is asserted via the audit
-- envelope (which carries the tenant_id) rather than schema selection.
--
-- Also seeds the shared-infra shims that SchemeServiceIT calls out (see
-- contributions-service test-migration V001): a `tenants` row so
-- TenantAwareConnectionFactory.lookupSchemaName resolves to `public`, an
-- empty `scheduled_job_configs` so ScheduledJobRepository no-ops on the
-- dispatcher tick, and a `public_role` (granted CRUD on all public
-- tables) so SET ROLE public_role on the tenant-scoped connection
-- acquisition doesn't 500 or 42501 the first query.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Role for the tenant-scoped SET ROLE ─────────────────────────────
-- TenantAwareConnectionFactory issues `SET ROLE <schema>_role` for every
-- tenant-scoped connection acquisition; in this IT the schema is public
-- so the role name is public_role. Production V117 provisions per-tenant
-- roles with a whitelisted grant set — this IT collapses that down to
-- "all privileges on the whole public schema" because the "tenant" IS
-- the public schema here. All GRANTs run at the end of the migration so
-- they cover the tables created below.
DO $$ BEGIN
    CREATE ROLE public_role;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
GRANT public_role TO CURRENT_USER;

-- ── Shared infra shims ───────────────────────────────────────────────
CREATE TABLE tenants (
    id           UUID         PRIMARY KEY,
    schema_name  VARCHAR(63)  NOT NULL DEFAULT 'public'
);

CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

-- ── Join targets for CtcPaymentQueryRepository ───────────────────────
CREATE TABLE members (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name     VARCHAR(200),
    last_name      VARCHAR(200),
    member_number  VARCHAR(64),
    group_id       UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE groups (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── Table under test ─────────────────────────────────────────────────
CREATE TABLE ctc_payments (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id          UUID,
    member_id         UUID,
    amount            NUMERIC(19, 4) NOT NULL,
    currency_code     VARCHAR(3)     NOT NULL,
    contribution_id   UUID,
    committed         BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        UUID
);

-- Grant CRUD on all seeded tables to the tenant role. Sequences added
-- speculatively — none of the DDL above uses them today but pgcrypto's
-- gen_random_uuid path may pull one via extension internals.
GRANT USAGE ON SCHEMA public TO public_role;
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO public_role;
