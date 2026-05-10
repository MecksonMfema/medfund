-- Move scheduled-job tables from tenant schemas to public.
--
-- Background: V002 created scheduled_job_configs in every tenant schema, but
-- the /api/v1/scheduled-jobs endpoint sits in TenantWebFilter.PLATFORM_PATHS
-- and runs against the public schema — the tables were never reachable. The
-- platform-admin job monitor (Slice 31) added scheduled_job_runs (V017) on
-- top of the same broken assumption.
--
-- This migration sets things right: one shared table per concept in the
-- public schema, every row tagged with tenant_id (nullable for truly
-- platform-global jobs like tenant-onboarding sweeps). Mirrors the
-- audit-service pattern. No data preservation needed — neither table was
-- read from in production.

CREATE TABLE IF NOT EXISTS public.scheduled_job_configs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID,
    job_type            VARCHAR(50) NOT NULL,
    name                VARCHAR(200) NOT NULL,
    cron_expression     VARCHAR(100) NOT NULL,
    is_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    settings            JSONB NOT NULL DEFAULT '{}',
    last_executed_at    TIMESTAMPTZ,
    next_execution_at   TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID
);

CREATE INDEX IF NOT EXISTS idx_scheduled_job_configs_tenant
    ON public.scheduled_job_configs(tenant_id, next_execution_at);
CREATE INDEX IF NOT EXISTS idx_scheduled_job_configs_type
    ON public.scheduled_job_configs(job_type);
CREATE INDEX IF NOT EXISTS idx_scheduled_job_configs_due
    ON public.scheduled_job_configs(next_execution_at) WHERE is_enabled = TRUE;

CREATE TABLE IF NOT EXISTS public.scheduled_job_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_id       UUID NOT NULL
                      REFERENCES public.scheduled_job_configs(id) ON DELETE CASCADE,
    tenant_id       UUID,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ,
    duration_ms     BIGINT,
    status          VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
                      CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    trigger_kind    VARCHAR(16) NOT NULL DEFAULT 'schedule'
                      CHECK (trigger_kind IN ('schedule', 'manual')),
    error_message   TEXT,
    triggered_by    UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_scheduled_job_runs_config
    ON public.scheduled_job_runs(config_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_scheduled_job_runs_tenant
    ON public.scheduled_job_runs(tenant_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_scheduled_job_runs_status
    ON public.scheduled_job_runs(status, started_at DESC);
