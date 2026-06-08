-- Add a discriminator alongside groups.liaison_user_id (V020) so we can tell
-- whether the FK target is in the tenant-scoped `members` table or the
-- platform-wide `staff_users` table. Both kinds of person can act as a group
-- liaison: an enrolled member of the scheme (typical for funeral / SACCO
-- groups), or a tenant staff user who manages the group's invoices on behalf
-- of the employer. No single foreign key would cover both, so we model it as
-- (liaison_kind, liaison_user_id) and enforce consistency with CHECK
-- constraints rather than a FK reference.
--
-- liaison_kind is intentionally NULL-able — a group can exist without a
-- liaison until the operator assigns one. The CHECK guarantees the kind and
-- id are set or cleared together.

ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS liaison_kind VARCHAR(16);

-- Both null OR both non-null — never one without the other.
ALTER TABLE groups
    ADD CONSTRAINT groups_liaison_pair_consistent
        CHECK (
            (liaison_kind IS NULL AND liaison_user_id IS NULL)
         OR (liaison_kind IS NOT NULL AND liaison_user_id IS NOT NULL)
        );

-- Restrict the discriminator vocabulary.
ALTER TABLE groups
    ADD CONSTRAINT groups_liaison_kind_valid
        CHECK (liaison_kind IS NULL OR liaison_kind IN ('MEMBER', 'STAFF'));
