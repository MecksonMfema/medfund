-- Soft-delete support for scheme benefits and age groups. Both tables
-- now carry a `status` column so we can deactivate without losing the
-- historical record (which is referenced by contributions, invoices,
-- and claims for the period during which it was active).
--
-- `schemes` already has this column from V001; this migration brings the
-- child tables to parity so the operational UI can offer a single
-- "Deactivate" affordance on all three.

ALTER TABLE scheme_benefits
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'active';

ALTER TABLE age_groups
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'active';

CREATE INDEX IF NOT EXISTS idx_scheme_benefits_status ON scheme_benefits(status);
CREATE INDEX IF NOT EXISTS idx_age_groups_status      ON age_groups(status);
