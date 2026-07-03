-- Flip the billing-commit cooldown to disabled-by-default. V008 originally
-- shipped a 3-hour cooldown which blocks back-to-back regeneration during
-- development and demoes. Tenants who want a cooldown can raise it via
-- Settings → Billing → Cycle. Runtime already treats 0 as "no cooldown"
-- (BillingService.cooldownRemainingMinutes) so no service-code change is
-- coupled to this migration.

ALTER TABLE billing_cycle_config
    ALTER COLUMN commit_cooldown_hours SET DEFAULT 0;

-- Reset the singleton row on tenants that were seeded with the old default.
-- Anyone who explicitly configured a non-3 value in the admin UI keeps
-- their setting; only the untouched seed value is cleared. New tenants
-- will pick up the DEFAULT 0 above on first row insert.
UPDATE billing_cycle_config
   SET commit_cooldown_hours = 0,
       updated_at            = NOW()
 WHERE id = '00000000-0000-0000-0000-000000000001'::uuid
   AND commit_cooldown_hours = 3;
