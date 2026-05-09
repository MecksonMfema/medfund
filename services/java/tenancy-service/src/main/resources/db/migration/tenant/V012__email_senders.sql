-- Tenant-configured outbound email sender addresses.
--
-- Tenants register the addresses they want to send mail from (e.g.
-- billing@acme.health). The notification-service consults this table when
-- routing campaign / transactional mail; only `verified` rows are valid
-- senders. Verification today is admin-driven (an operator flips status
-- after performing DNS / domain checks); a future migration may add a
-- token-based verification mailer.
--
-- status     — pending → verified → revoked. Pending senders cannot send.
-- verified_at — wall-clock at which an operator marked the sender verified.

CREATE TABLE IF NOT EXISTS email_senders (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    address         VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    status          VARCHAR(32)  NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending', 'verified', 'revoked')),
    verified_at     TIMESTAMPTZ,
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT uq_email_senders_address UNIQUE (address)
);

CREATE INDEX IF NOT EXISTS idx_email_senders_status ON email_senders(status);
