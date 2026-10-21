-- Platform-wide settings — a single-row config table keyed to the super-admin
-- portal's /platform/settings page. Singleton is enforced by a partial-unique
-- index on the boolean singleton column.
--
-- Logo bytes live in the row itself (bytea) rather than in MinIO: tenancy-service
-- has no MinIO client wiring today, and V168 already codifies "bytea keeps it
-- simple" as the convention for bounded binary here. Logos are capped at 2MB
-- server-side, so the row stays well within Postgres' toast threshold.
CREATE TABLE IF NOT EXISTS public.platform_settings (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton          BOOLEAN NOT NULL DEFAULT TRUE,
    platform_name      VARCHAR(200),
    support_email      VARCHAR(320),
    logo_bytes         BYTEA,
    logo_mime          VARCHAR(64),
    theme_template_id  VARCHAR(50)  NOT NULL DEFAULT 'ocean',
    dark_mode          BOOLEAN      NOT NULL DEFAULT FALSE,
    hero_title         VARCHAR(200),
    hero_subtitle      VARCHAR(500),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by         VARCHAR(320),
    version            BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_platform_settings_singleton
    ON public.platform_settings (singleton) WHERE singleton = TRUE;

INSERT INTO public.platform_settings (platform_name, theme_template_id)
VALUES ('MedFund', 'ocean')
ON CONFLICT DO NOTHING;
