-- The scheduled-job tables created by V002 (configs) and V017 (runs) were
-- never reachable in practice — the controller path is in
-- TenantWebFilter.PLATFORM_PATHS so requests run against the public schema
-- where these tenant copies don't exist. Public V114 creates the unified
-- public.scheduled_job_configs and public.scheduled_job_runs with a
-- tenant_id column; this migration removes the dead tenant copies so that
-- search_path resolution doesn't shadow the public table for tenant-scoped
-- requests.
--
-- DROP CASCADE because V017's scheduled_job_runs FK references the now-gone
-- scheduled_job_configs in the same schema.

DROP TABLE IF EXISTS scheduled_job_runs CASCADE;
DROP TABLE IF EXISTS scheduled_job_configs CASCADE;
