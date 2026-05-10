-- The scheduled_job_configs.settings column was modelled as JSONB in V114
-- (mirroring the original V002 shape), but the Java entity stores it as a
-- plain String and every executor signature is `execute(String tenantId,
-- String settings)`. R2DBC binds the parameter as varchar, Postgres won't
-- implicitly cast varchar -> jsonb on save() / update(), and seedDefaults
-- (along with every JobDispatcher write-back via updateExecutionTime) blew
-- up with `column "settings" is of type jsonb but expression is of type
-- character varying`.
--
-- No code actually queries inside the JSON or relies on jsonb-specific
-- semantics. Switching the column to TEXT removes the bind mismatch and
-- requires zero Java changes. We keep a CHECK that the value parses as JSON
-- so accidental garbage doesn't slip through, but the storage type is now
-- the same as how the application treats it.

ALTER TABLE public.scheduled_job_configs
    ALTER COLUMN settings TYPE TEXT
        USING settings::text;

-- Re-assert the default expression in the right type after ALTER.
ALTER TABLE public.scheduled_job_configs
    ALTER COLUMN settings SET DEFAULT '{}';

-- Soft validation — bad values blow up on insert/update with a clear error
-- instead of being silently stored. Skipped on existing rows that may have
-- weird casing of NULL etc; only enforced for new writes.
ALTER TABLE public.scheduled_job_configs
    ADD CONSTRAINT scheduled_job_configs_settings_is_json
        CHECK (settings::json IS NOT NULL) NOT VALID;
