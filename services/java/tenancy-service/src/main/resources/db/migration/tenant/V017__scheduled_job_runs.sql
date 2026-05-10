-- scheduled_job_runs — per-execution log for the platform-admin job monitor.
-- The shared JobDispatcher writes a row at the start of every job tick and
-- updates it once the executor settles. Failures stay queryable so an
-- operator can see what broke without scraping stdout, and the run-now
-- endpoint records its own row with trigger_kind = 'manual'.

CREATE TABLE IF NOT EXISTS scheduled_job_runs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    config_id       UUID         NOT NULL
                      REFERENCES scheduled_job_configs(id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    duration_ms     BIGINT,
    status          VARCHAR(16)  NOT NULL DEFAULT 'RUNNING'
                      CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    trigger_kind    VARCHAR(16)  NOT NULL DEFAULT 'schedule'
                      CHECK (trigger_kind IN ('schedule', 'manual')),
    error_message   TEXT,
    triggered_by    UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_scheduled_job_runs_config
    ON scheduled_job_runs(config_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_scheduled_job_runs_status
    ON scheduled_job_runs(status, started_at DESC);
