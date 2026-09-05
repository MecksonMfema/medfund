-- Phase 17 §0.2: per-schedule recipient list. Mirrors V169
-- tenant_regulatory_recipient shape, with two differences:
--   (a) no subscribed_event_tiers — scheduled report delivery does not tier
--       events (regulator due-date scanner in Phase 16 REG20 does).
--   (b) unsubscribe_token is a per-row UUID surfaced in the delivery email
--       footer. Clicking hits the public gateway route which resolves the
--       token, flips is_active=FALSE, and audits with actor="system:unsubscribe".
--
-- ON DELETE CASCADE on schedule_id: removing a schedule wipes its recipients;
-- audit trail lives in the AuditEvent stream, so no data loss beyond the
-- soft-referenced row.

CREATE TABLE IF NOT EXISTS public.tenant_report_schedule_recipient (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id        UUID          NOT NULL REFERENCES public.tenant_report_schedule(id) ON DELETE CASCADE,
    email              VARCHAR(255)  NOT NULL,
    display_name       VARCHAR(160)  NULL,
    is_active          BOOLEAN       NOT NULL DEFAULT TRUE,
    unsubscribe_token  UUID          NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id           UUID          NOT NULL,
    actor_email        VARCHAR(255)  NOT NULL,
    CONSTRAINT uq_trsr_schedule_email UNIQUE (schedule_id, email),
    CONSTRAINT trsr_email_ck CHECK (email LIKE '%@%')
);

CREATE INDEX IF NOT EXISTS idx_trsr_schedule_active
    ON public.tenant_report_schedule_recipient(schedule_id)
    WHERE is_active = TRUE;
