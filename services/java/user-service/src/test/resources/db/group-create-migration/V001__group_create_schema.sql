-- Fixture schema for GroupServiceCreateIT. Includes every table
-- GroupService.create touches directly or transitively during a
-- create-with-liaison flow:
--
--   • public.tenants — with V125 columns so the number generator can
--     read the tenant's configured shape. Individual tests can drop
--     the columns to reproduce the pre-migration state.
--   • public.staff_users — the STAFF liaison FK target and the shared
--     search table for admins.
--   • public.scheduled_job_configs / tenant_rules — shims so the
--     scheduler + rules-loader startup ticks don't 42P01.
--   • groups, members, dependants, group_liaisons — the tenant-schema
--     side. Only the columns GroupService.create writes are present.
--
-- Deliberately minimal: this IT tests the create + tx-safety seam, not
-- the wider domain graph. Extras (running-balance, contribution, etc.)
-- are not needed.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Platform tables ───────────────────────────────────────────────────
CREATE TABLE tenants (
    id                           UUID          PRIMARY KEY,
    schema_name                  VARCHAR(63)   NOT NULL DEFAULT 'public',
    group_number_prefix          VARCHAR(20)   NOT NULL DEFAULT 'GRP-',
    group_number_suffix          VARCHAR(20)   NOT NULL DEFAULT '',
    group_number_random_length   INTEGER       NOT NULL DEFAULT 6
        CHECK (group_number_random_length BETWEEN 3 AND 12)
);

CREATE TABLE staff_users (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_user_id  VARCHAR(200),
    first_name        VARCHAR(120),
    last_name         VARCHAR(120),
    email             VARCHAR(200),
    status            VARCHAR(20)   NOT NULL DEFAULT 'active'
);

CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

CREATE TABLE tenant_rules (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID          NOT NULL,
    enabled      BOOLEAN       NOT NULL DEFAULT true,
    priority     INTEGER       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    rule_type    VARCHAR(64),
    name         VARCHAR(200),
    description  TEXT,
    definition   TEXT
);

-- ── Tenant-schema tables (living in public for the IT) ───────────────

CREATE TABLE group_liaisons (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name   VARCHAR(120),
    last_name    VARCHAR(120),
    email        VARCHAR(200),
    phone        VARCHAR(50),
    address      TEXT,
    keycloak_user_id VARCHAR(200),
    status       VARCHAR(20)   NOT NULL DEFAULT 'invited',
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE groups (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(200)  NOT NULL,
    registration_number   VARCHAR(100),
    address               TEXT,
    email                 VARCHAR(200),
    liaison_kind          VARCHAR(20),
    liaison_user_id       UUID,
    status                VARCHAR(20)   NOT NULL DEFAULT 'active',
    suspend_reason        VARCHAR(64),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by            UUID,
    updated_by            UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS groups_registration_number_unique
    ON groups (registration_number)
 WHERE registration_number IS NOT NULL;

CREATE TABLE members (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_user_id  VARCHAR(200),
    member_number     VARCHAR(50)   UNIQUE,
    first_name        VARCHAR(100),
    last_name         VARCHAR(100),
    email             VARCHAR(200),
    phone             VARCHAR(50),
    date_of_birth     DATE,
    group_id          UUID          REFERENCES groups(id),
    scheme_id         UUID,
    status            VARCHAR(20)   NOT NULL DEFAULT 'active',
    enrollment_date   DATE,
    termination_date  DATE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE dependants (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id    UUID         NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    first_name   VARCHAR(120),
    last_name    VARCHAR(120),
    status       VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    member_number VARCHAR(50)  UNIQUE
);
