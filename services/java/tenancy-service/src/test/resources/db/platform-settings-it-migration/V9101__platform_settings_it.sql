-- Test-migration counterparts of V183__platform_settings.sql and
-- V184__platform_feature_flags.sql. The IT harness (spring.flyway.locations=
-- classpath:db/test-migration) does NOT apply production migrations, so
-- every table an IT touches has to be shaped here.

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

CREATE TABLE IF NOT EXISTS public.platform_feature_flags (
    key         VARCHAR(64)  PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(320),
    version     BIGINT       NOT NULL DEFAULT 0
);
