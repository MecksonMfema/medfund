-- Add result_payload to scheduled_job_runs so executors can hand back a
-- structured result (e.g. billing preview totals, commit counts) that the
-- UI can fetch by short-polling /api/v1/scheduled-jobs/{id}/runs. Without
-- this column the only signal a poll would get is start/end/duration, which
-- isn't enough to render the wizard's preview/commit screens.
--
-- Stored as TEXT (with a JSON validity check) for the same reason as
-- scheduled_job_configs.settings (see V115): R2DBC binds Strings as varchar
-- and Postgres won't implicitly cast varchar → jsonb on save().

ALTER TABLE public.scheduled_job_runs
    ADD COLUMN IF NOT EXISTS result_payload TEXT;

ALTER TABLE public.scheduled_job_runs
    ADD CONSTRAINT scheduled_job_runs_result_payload_is_json
        CHECK (result_payload IS NULL OR result_payload::json IS NOT NULL) NOT VALID;
