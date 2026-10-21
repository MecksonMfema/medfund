-- Per-tenant IFRS 17 material-event notification recipients (Phase 15 §19 / I30).
-- A tenant admin picks one or more event types (ONEROUS_TRANSITION,
-- CSM_NEGATIVE, LOCKED_IN_CURVE_FALLBACK, IBNR_SUB_JOB_STALE,
-- OPENING_BALANCE_AUTO_DERIVED, or ALL) plus a delivery channel (EMAIL,
-- WEBHOOK, BOTH) plus a per-tuple throttle. The notification-service Go
-- dispatcher (§20) reads active rows for the event type and fans out.
--
-- Renumbered from plan V155 to V166 at implement time: V155-V164 were
-- consumed by tenant migrations from §3-§8 renumbers (shared dev Flyway
-- history across public + tenant folders) and V165 already lands the
-- IFRS17_MODEL rule seed (§9).

CREATE TABLE IF NOT EXISTS public.tenant_ifrs17_notification_config (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
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

CREATE INDEX IF NOT EXISTS idx_tenant_ifrs17_notification_config_lookup
    ON public.tenant_ifrs17_notification_config (tenant_id, event_type, is_active);
