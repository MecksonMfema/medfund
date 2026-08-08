-- =====================================================================
-- V066: claims.payee_type — separate service provider from payment routing
-- =====================================================================
-- Historically we inferred payee from provider_id being null: no provider
-- meant "reimburse the member". That collapsed two distinct concepts and
-- made it impossible to capture the service provider on a claim that was
-- paid out-of-pocket by the member. The adjudicator needs provider info
-- (specialty, network status) regardless of who receives the payout.
--
-- payee_type makes the payment routing explicit:
--   PROVIDER — approved amount is paid directly to the service provider
--   MEMBER   — approved amount is reimbursed to the sponsoring member
--
-- ClaimService now enforces per-line rules: HEALTH / GROUP / TRAVEL /
-- VEHICLE / PROPERTY require a provider on submit (REQUIRED); FUNERAL
-- keeps its OPTIONAL mode (tenants that run their own funeral parlour
-- act as the provider themselves); LIFE / DISABILITY remain FORBIDDEN.
-- =====================================================================

ALTER TABLE claims
    ADD COLUMN IF NOT EXISTS payee_type text NOT NULL DEFAULT 'PROVIDER';

-- Backfill: rows with no provider on file were member-reimbursement
-- claims under the old model — flip them to MEMBER so the finance
-- consumer keeps paying the right party after the app upgrade.
UPDATE claims
   SET payee_type = 'MEMBER'
 WHERE provider_id IS NULL
   AND payee_type = 'PROVIDER';

-- Sanity constraint: PROVIDER payment routing requires a provider on the
-- claim. MEMBER routing accepts either shape (provider captured but
-- member paid; or no provider at all for FUNERAL/LIFE/DISABILITY).
ALTER TABLE claims
    ADD CONSTRAINT claims_payee_type_requires_provider
    CHECK (payee_type = 'MEMBER' OR provider_id IS NOT NULL);
