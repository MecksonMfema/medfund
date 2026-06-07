-- Two related additions that unblock the membership-model-aware billing
-- flow (BillingService → generates Invoices grouped by liaison routing):
--
-- 1. groups.liaison_user_id — nullable FK placeholder for the Keycloak
--    user that owns this group. Set to NULL today; the liaison portal
--    provisioning flow (separate phase) will populate it. Storing it on
--    the group lets us bill the liaison's account once the portal exists,
--    without a schema migration at the time of cutover.
--
-- 2. contributions.invoice_id — back-link from each per-member
--    contribution to the invoice that aggregated it. For GROUP_ONLY /
--    BOTH tenants, N contributions roll up into one invoice for the
--    group; for INDIVIDUAL_ONLY, it's a 1-to-1 mapping. Either way
--    finance and audit can answer "which invoice covered this
--    contribution?" without a heuristic join on member+period.

ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS liaison_user_id UUID;

ALTER TABLE contributions
    ADD COLUMN IF NOT EXISTS invoice_id UUID REFERENCES invoices(id);

CREATE INDEX IF NOT EXISTS idx_groups_liaison       ON groups(liaison_user_id) WHERE liaison_user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_contributions_invoice ON contributions(invoice_id) WHERE invoice_id IS NOT NULL;
