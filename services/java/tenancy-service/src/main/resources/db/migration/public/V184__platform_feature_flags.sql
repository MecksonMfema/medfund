-- Platform-wide feature flags. One row per catalogue key defined in the
-- PlatformFlag enum (shared module). PlatformFlagSeeder inserts any missing
-- rows on tenancy-service startup so a rollout that adds a new enum value
-- doesn't require a migration.
CREATE TABLE IF NOT EXISTS public.platform_feature_flags (
    key         VARCHAR(64)  PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(320),
    version     BIGINT       NOT NULL DEFAULT 0
);
