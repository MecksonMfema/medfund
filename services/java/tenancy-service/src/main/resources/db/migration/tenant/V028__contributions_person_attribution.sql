-- =====================================================================
-- V028: Per-person attribution + audit columns on contributions
-- =====================================================================
-- Until V027 we billed one row per member. With age-band billing the
-- dependants also get their own line, so a contribution row needs to
-- identify which insured person it's for:
--
--   * member_id is ALWAYS set — it is the parent member used for invoice
--     routing (group invoice via member.group_id, individual invoice via
--     member.id). It does NOT mean "this row is for the member".
--   * dependant_id is set ONLY when the row is for the dependant. The
--     member's own line has dependant_id = NULL.
--
-- An invoice is therefore identified by (group_id | member_id) and its
-- line items are the contributions that share that invoice key — with
-- the line itself attributed to either the member (dependant_id NULL) or
-- a specific dependant.
--
-- age_group_id captures the bucket that was used to price the row at the
-- time of the run. Storing it keeps the audit trail honest: a person
-- whose canonical bucket later changes (Child → Adult) still shows the
-- old amount priced against the OLD bucket for historical invoices.
-- =====================================================================

ALTER TABLE contributions
    ADD COLUMN dependant_id uuid REFERENCES dependants(id),
    ADD COLUMN age_group_id uuid REFERENCES age_groups(id);

CREATE INDEX idx_contributions_dependant   ON contributions(dependant_id);
CREATE INDEX idx_contributions_age_group   ON contributions(age_group_id);

COMMENT ON COLUMN contributions.dependant_id IS 'Set only when this row is for the dependant; NULL means it is the parent member''s own line.';
COMMENT ON COLUMN contributions.age_group_id IS 'Bucket used to price this row at run time. Frozen — never updated even if the person''s canonical bucket changes later.';
