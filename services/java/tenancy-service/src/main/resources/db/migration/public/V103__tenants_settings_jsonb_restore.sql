-- Restore settings and branding to JSONB now that R2DBC codec is configured.
-- The values are still valid JSON text so the cast is safe.
ALTER TABLE public.tenants
    ALTER COLUMN settings TYPE JSONB USING settings::JSONB,
    ALTER COLUMN branding TYPE JSONB USING branding::JSONB;
