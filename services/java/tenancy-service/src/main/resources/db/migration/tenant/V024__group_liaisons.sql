-- Third liaison kind: a "pure" group liaison who is neither a tenant
-- staff user nor an enrolled member, but still authenticates against the
-- group portal (added in a later phase). Stored in its own table so the
-- staff/members lists stay clean and the record only carries the small
-- shape a liaison actually needs.
--
-- Authentication: a Keycloak account is provisioned on create (in this
-- tenant's realm) carrying the 'group_liaison' realm role. Member or staff
-- liaisons keep their existing Keycloak account; the role is added to
-- their existing user record by GroupService.assign — see V023 for the
-- discriminator column it switches on.

CREATE TABLE IF NOT EXISTS group_liaisons (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    phone             VARCHAR(50),
    address           TEXT,
    keycloak_user_id  VARCHAR(255),
    status            VARCHAR(20)  NOT NULL DEFAULT 'invited',
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by        UUID,
    updated_by        UUID
);

-- Case-insensitive email uniqueness — matches the StaffUser pattern.
CREATE UNIQUE INDEX IF NOT EXISTS idx_group_liaisons_email_lower
    ON group_liaisons (LOWER(email));

CREATE INDEX IF NOT EXISTS idx_group_liaisons_status
    ON group_liaisons (status);

-- Extend the liaison_kind vocabulary to include LIAISON (the new pure
-- kind). The pair-consistency CHECK from V023 is unchanged — kind + id
-- are still set or cleared together.
ALTER TABLE groups DROP CONSTRAINT IF EXISTS groups_liaison_kind_valid;
ALTER TABLE groups
    ADD CONSTRAINT groups_liaison_kind_valid
        CHECK (liaison_kind IS NULL OR liaison_kind IN ('MEMBER', 'STAFF', 'LIAISON'));
