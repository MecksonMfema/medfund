-- PlatformFlagSeeder (a CommandLineRunner) blocks on repo.findById(...) against
-- public.platform_feature_flags on every context boot, unconditionally. The
-- db/test-migration set otherwise never creates that table, so every IT that
-- stacks on classpath:db/test-migration would fail at startup before running a
-- single assertion. Mirrors the DDL in
-- db/platform-settings-it-migration/V9101__platform_settings_it.sql.
--
-- Test-only migration. Renumbered into the V9xxx band (see the CI-honesty plan,
-- 2026-09-20) so it can never collide with the real V001-V276 trees or the
-- platform-settings-it set in the shared IT flyway_schema_history.
CREATE TABLE IF NOT EXISTS public.platform_feature_flags (
    key         VARCHAR(64)  PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(320),
    version     BIGINT       NOT NULL DEFAULT 0
);
