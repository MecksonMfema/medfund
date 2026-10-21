-- ── Phase 13 §A per L1: network_tier on the platform-wide providers table ─
-- V113 added network_tier to the tenant-schema providers table (used by the
-- claims-service PROVIDER_NETWORK_UTILIZATION report in Phase 9). The
-- Provider entity in user-service reads from public.providers, so the
-- admin PATCH endpoint + inline dropdown (Phase 4) needs the column on
-- public.providers as well. Vocab + default mirror V113 verbatim.

ALTER TABLE public.providers
    ADD COLUMN IF NOT EXISTS network_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

-- Drop-then-readd keeps re-runs idempotent (ADD CONSTRAINT has no IF NOT EXISTS).
ALTER TABLE public.providers DROP CONSTRAINT IF EXISTS chk_public_providers_network_tier;
ALTER TABLE public.providers
    ADD CONSTRAINT chk_public_providers_network_tier
    CHECK (network_tier IN ('STANDARD', 'TIER_1', 'TIER_2', 'TIER_3'));

CREATE INDEX IF NOT EXISTS ix_public_providers_network_tier
    ON public.providers (network_tier);

COMMENT ON COLUMN public.providers.network_tier IS
    'Phase 13 §A per L1: network tier grouping. Written from the admin
     PATCH endpoint; default STANDARD. Mirrors the tenant-schema column.';
