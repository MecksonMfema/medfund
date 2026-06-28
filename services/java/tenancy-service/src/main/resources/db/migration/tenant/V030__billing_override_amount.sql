-- =====================================================================
-- V030: Per-person pricing override (data-driven individual pricing)
-- =====================================================================
-- Today's overrides work by pointing a member at a different
-- age_group via billing_age_group_id — so a high-risk member needs
-- a dedicated "high-risk" age_group row created for them. Clunky
-- (one age_group per unique premium) and not data-driven.
--
-- billing_override_amount lets a tenant set an explicit per-person
-- premium without ginning up custom age_groups. The price-resolution
-- order in BillingService.resolveCandidates becomes:
--   1. If billing_override_amount is set AND
--      billing_override_effective_from is on/before periodEnd,
--      use that amount.
--   2. Else use the age_group price (via billing_age_group_id
--      override or canonical age_group_id), resolved against
--      age_group_prices for the run's period (see V029).
--
-- The override's currency is implicitly the resolved age_group's
-- currency — billing_override_amount alone is just the number. A
-- member in a USD scheme is overridden in USD; storing a separate
-- currency would invite a per-tenant FX risk this MVP doesn't need.
--
-- billing_override_reason + billing_override_effective_from already
-- exist alongside (added in earlier migrations) so this is a single
-- new column per table.
-- =====================================================================

ALTER TABLE members
    ADD COLUMN IF NOT EXISTS billing_override_amount NUMERIC(19,4) NULL,
    ADD CONSTRAINT members_billing_override_amount_positive
        CHECK (billing_override_amount IS NULL OR billing_override_amount > 0);

ALTER TABLE dependants
    ADD COLUMN IF NOT EXISTS billing_override_amount NUMERIC(19,4) NULL,
    ADD CONSTRAINT dependants_billing_override_amount_positive
        CHECK (billing_override_amount IS NULL OR billing_override_amount > 0);

-- An override amount with no effective_from is ambiguous — the
-- billing query wouldn't know when to start applying it. Require
-- effective_from whenever amount is set. Reason is recommended but
-- not required (some overrides are policy decisions with no narrative).
ALTER TABLE members
    ADD CONSTRAINT members_billing_override_amount_requires_effective_from
        CHECK (billing_override_amount IS NULL OR billing_override_effective_from IS NOT NULL);

ALTER TABLE dependants
    ADD CONSTRAINT dependants_billing_override_amount_requires_effective_from
        CHECK (billing_override_amount IS NULL OR billing_override_effective_from IS NOT NULL);
