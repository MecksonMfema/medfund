-- Pre-authorization for a dependant. Mirrors the Claim entity's optional
-- dependant_id — a pre-auth for a dependant is always attached to the
-- sponsoring member (for member drill-through + billing) with the
-- dependant_id set to identify which covered person the auth applies to.
-- When the claim under adjudication carries a dependant_id, the pipeline
-- looks up the dependant's pre-auth by (dependant_id, tariff_code);
-- otherwise it looks up the member's by (member_id, tariff_code) with
-- dependant_id IS NULL — kept separate so a member and one of their
-- dependants can each hold their own pre-auth for the same tariff code
-- without collision.

ALTER TABLE pre_authorizations
    ADD COLUMN IF NOT EXISTS dependant_id UUID;

CREATE INDEX IF NOT EXISTS idx_pre_authorizations_dependant
    ON pre_authorizations(dependant_id)
    WHERE dependant_id IS NOT NULL;
