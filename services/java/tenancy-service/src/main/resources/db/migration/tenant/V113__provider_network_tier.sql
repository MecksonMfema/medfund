-- ── Phase 13 per L1: provider network tier ───────────────────────────
-- Single-column network dimension for PROVIDER_NETWORK_UTILIZATION.
-- Populated via in-line dropdown per L17; default STANDARD for existing
-- rows. Vocab matches the ProviderFact.networkTier convention already
-- used by rules-engine co-pay templates.

ALTER TABLE providers
    ADD COLUMN IF NOT EXISTS network_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

-- Drop-then-readd keeps re-runs idempotent (ADD CONSTRAINT has no IF NOT EXISTS).
ALTER TABLE providers DROP CONSTRAINT IF EXISTS chk_providers_network_tier;
ALTER TABLE providers
    ADD CONSTRAINT chk_providers_network_tier
    CHECK (network_tier IN ('STANDARD', 'TIER_1', 'TIER_2', 'TIER_3'));

CREATE INDEX IF NOT EXISTS ix_providers_network_tier ON providers (network_tier);

COMMENT ON COLUMN providers.network_tier IS
    'Phase 13 per L1: network tier grouping for PROVIDER_NETWORK_UTILIZATION report.
     Default STANDARD; tenant admin sets per L17 in-line dropdown.';
