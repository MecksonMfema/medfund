-- Platform-wide provider registry.
-- Providers are not tenant-scoped — they are onboarded once and shared across
-- all tenants. Each tenant references providers when processing claims.

-- Create table for fresh installs.
-- For existing databases this is a no-op; the ALTER TABLE statements below
-- handle adding any columns that were introduced after the table was first created.
CREATE TABLE IF NOT EXISTS public.providers (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(200) NOT NULL,
    provider_type    VARCHAR(50),
    practice_number  VARCHAR(50),
    ahfoz_number     VARCHAR(50),
    specialty        VARCHAR(100),
    email            VARCHAR(255),
    phone            VARCHAR(50),
    city             VARCHAR(100),
    address          TEXT,
    banking_details  TEXT,
    keycloak_user_id VARCHAR(255),
    status           VARCHAR(30)  NOT NULL DEFAULT 'pending_verification',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       UUID,
    updated_by       UUID
);

-- Idempotent column additions for databases where the table already existed
-- without these columns (pre-dates this migration).
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS provider_type    VARCHAR(50);
ALTER TABLE public.providers ADD COLUMN IF NOT EXISTS city             VARCHAR(100);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_providers_status        ON public.providers(status);
CREATE INDEX IF NOT EXISTS idx_providers_provider_type ON public.providers(provider_type);
CREATE INDEX IF NOT EXISTS idx_providers_name          ON public.providers(LOWER(name));
