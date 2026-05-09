-- Tenant-scoped email campaigns. Drafts are composed against a verified
-- sender and an audience filter (member status, scheme/group ids); when sent
-- the row records the recipient count and timestamp. Actual SMTP dispatch
-- happens out-of-band in notification-service once that grows a real outbound
-- layer; for now /send is a state flip + count.
--
-- audience_filter — JSONB shape:
--   { "memberStatus": "active",
--     "schemeIds":  ["..."],
--     "groupIds":   ["..."],
--     "enrolledAfter": "2026-01-01" }
--   Empty object {} targets every member.

CREATE TABLE IF NOT EXISTS email_campaigns (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id       UUID         REFERENCES email_senders(id) ON DELETE SET NULL,
    subject         VARCHAR(255) NOT NULL,
    body_html       TEXT         NOT NULL,
    body_text       TEXT,
    audience_filter JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(32)  NOT NULL DEFAULT 'draft'
                      CHECK (status IN ('draft', 'sending', 'sent', 'failed')),
    scheduled_for   TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    recipient_count INTEGER,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID
);

CREATE INDEX IF NOT EXISTS idx_email_campaigns_status ON email_campaigns(status);
CREATE INDEX IF NOT EXISTS idx_email_campaigns_sender ON email_campaigns(sender_id);
CREATE INDEX IF NOT EXISTS idx_email_campaigns_created ON email_campaigns(created_at DESC);
