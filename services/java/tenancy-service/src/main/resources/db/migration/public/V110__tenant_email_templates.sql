-- Per-tenant email template overrides. The platform ships a fixed catalogue of
-- transactional email templates (welcome, invite, password reset, claim status,
-- payment received, MFA OTP, etc.); a tenant can override any of them with
-- custom subject + body. Notification-service merges these overrides with the
-- platform defaults at send time — when a row is missing for a (tenant, key)
-- pair, the platform default is used.
--
-- key      — canonical template identifier (e.g. WELCOME_EMAIL, INVITE_USER).
--            Defined in TenantEmailTemplateService.CATALOGUE; NOT enforced in
--            the database so the catalogue can grow without a migration.
-- enabled  — when false, the override row is ignored and the platform default
--            is used instead (lets a tenant temporarily revert without losing
--            the customised body).
-- version  — bumped on every UPDATE so consumers can detect drift.

CREATE TABLE IF NOT EXISTS public.tenant_email_templates (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    template_key   VARCHAR(100) NOT NULL,
    subject        VARCHAR(255) NOT NULL,
    html_body      TEXT         NOT NULL,
    text_body      TEXT,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    version        INTEGER      NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_by     UUID,
    UNIQUE (tenant_id, template_key)
);

CREATE INDEX IF NOT EXISTS idx_tenant_email_templates_tenant
    ON public.tenant_email_templates(tenant_id);
