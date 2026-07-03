-- Add triggered_by_email so the JobCompleted event can carry the actor's
-- email address without a downstream lookup against public.staff_users.
-- Before this, notification-service had to convert the triggered_by UUID
-- (a Keycloak sub) to an email via a staff_users query — that query
-- required a locally-provisioned staff_user row whose id matched the
-- Keycloak sub, which the platform never guarantees. The result was
-- every commit-completed email silently dropping with "no rows in result
-- set" for tenants whose Keycloak users hadn't been mirrored yet.
--
-- The email is on the JWT the caller already presented; ContributionController
-- extracts it via AuditActor.email(jwt), passes it to JobDispatcher, and it
-- lands here for JobEventPublisher to include in the wire payload.
--
-- Nullable because scheduled (cron-driven) runs and background system
-- jobs have no human actor and therefore no email to stamp.

ALTER TABLE public.scheduled_job_runs
    ADD COLUMN IF NOT EXISTS triggered_by_email VARCHAR(255);
