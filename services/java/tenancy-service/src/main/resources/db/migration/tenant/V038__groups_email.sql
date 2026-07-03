-- Re-introduce a group-level email as the fallback recipient when a group
-- has no liaison assigned. Contribution statements and dunning notices need
-- somewhere to land — the liaison-user model (V024) is the primary path,
-- but "no liaison" was silently swallowing every group invoice.
--
-- V025 dropped the older `contact_email` / `contact_phone` columns in
-- favour of the liaison-user model. We are NOT resurrecting those names —
-- `email` is the singular contact address the recipient resolver falls
-- back to. Kept nullable at the DB level so existing rows migrate cleanly;
-- new groups are gated at the application layer (@NotBlank @Email on
-- CreateGroupRequest) so freshly-created groups always carry one.

ALTER TABLE groups ADD COLUMN IF NOT EXISTS email VARCHAR(255);

-- Case-insensitive uniqueness is a nice-to-have but not required — tenant
-- admins may legitimately reuse an inbox across two subsidiaries. Skipping
-- the unique index to avoid future migration friction.
