-- Per-tenant recipients for the Phase 16 §0 REG20 regulatory due-date
-- reminders. A tenant admin picks an email + a subset of event tiers
-- (DUE_DATE_7D, DUE_DATE_1D, DUE_DATE_0D, DUE_DATE_OVERDUE). The
-- notification-service Go dispatcher (Phase 8 §5) reads active rows and
-- fans out email + in-app notification per tier match.

CREATE TABLE IF NOT EXISTS public.tenant_regulatory_recipient (
    id                       UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID           NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    email                    VARCHAR(320)   NOT NULL,
    display_name             VARCHAR(200),
    subscribed_event_tiers   VARCHAR(30)[]  NOT NULL DEFAULT ARRAY[
        'DUE_DATE_7D',
        'DUE_DATE_1D',
        'DUE_DATE_0D',
        'DUE_DATE_OVERDUE'
    ]::VARCHAR[],
    is_active                BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    actor_id                 UUID           NOT NULL,
    actor_email              VARCHAR(320)   NOT NULL,
    CONSTRAINT uq_tenant_regulatory_recipient_email
        UNIQUE (tenant_id, email)
);

CREATE INDEX IF NOT EXISTS ix_tenant_regulatory_recipient_active
    ON public.tenant_regulatory_recipient (tenant_id)
    WHERE is_active = TRUE;

COMMENT ON TABLE public.tenant_regulatory_recipient IS
    'Phase 16 §0 REG20: per-tenant email recipients for regulator report due-date reminders. subscribed_event_tiers is a subset of {DUE_DATE_7D, DUE_DATE_1D, DUE_DATE_0D, DUE_DATE_OVERDUE}; a recipient only receives tiers they''re subscribed to.';
