-- Track when each staff user was invited so the UI can show invitation
-- expiry and hide "Resend Invite" once the user has accepted.
--
-- Newly created staff users get status='invited' and invited_at=now().
-- A Kafka consumer flips status='active' when Keycloak emits UPDATE_PASSWORD,
-- at which point invited_at is no longer used for gating.
--
-- Existing rows (created before this migration) had status='active' applied
-- on insert, so they remain active and invited_at stays NULL.

ALTER TABLE public.staff_users
    ADD COLUMN IF NOT EXISTS invited_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_staff_users_invited_at
    ON public.staff_users(invited_at)
    WHERE invited_at IS NOT NULL;
