-- Adds the Phase 15 §19 IFRS 17 material-event notification config table
-- to the shared test-migration schema used by the tenancy-service ITs.
-- Mirrors production V166.

CREATE TABLE tenant_ifrs17_notification_config (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID           NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    event_type        VARCHAR(50)    NOT NULL
        CHECK (event_type IN (
            'ONEROUS_TRANSITION',
            'CSM_NEGATIVE',
            'LOCKED_IN_CURVE_FALLBACK',
            'IBNR_SUB_JOB_STALE',
            'OPENING_BALANCE_AUTO_DERIVED',
            'ALL')),
    delivery_method   VARCHAR(20)    NOT NULL
        CHECK (delivery_method IN ('EMAIL', 'WEBHOOK', 'BOTH')),
    recipient         VARCHAR(500)   NOT NULL,
    throttle_minutes  INT            NOT NULL DEFAULT 15
        CHECK (throttle_minutes >= 0 AND throttle_minutes <= 1440),
    is_active         BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by        UUID,
    updated_by_email  VARCHAR(255),
    CONSTRAINT uq_tenant_ifrs17_notification_config
        UNIQUE (tenant_id, event_type, recipient)
);
CREATE INDEX idx_tenant_ifrs17_notification_config_lookup
    ON tenant_ifrs17_notification_config (tenant_id, event_type, is_active);

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON tenant_ifrs17_notification_config
    TO public_role;
