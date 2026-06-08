-- The group liaison (V023 / V024) is now the canonical source of contact
-- info for a group. The three legacy contact_* fields on `groups` are
-- redundant — every consumer (group-charge picker, creditor reports,
-- dunning) should resolve through the liaison's underlying record
-- (members.email, staff_users.email, or group_liaisons.email).
--
-- Migration order: this is intentionally V025, AFTER V023 + V024 added
-- the liaison kind + the group_liaisons table. By the time we drop these
-- columns the new contact pathway is fully in place.

ALTER TABLE groups DROP COLUMN IF EXISTS contact_person;
ALTER TABLE groups DROP COLUMN IF EXISTS contact_email;
ALTER TABLE groups DROP COLUMN IF EXISTS contact_phone;
