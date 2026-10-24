-- ============================================================
-- V187: Per-tenant module entitlement (the sellable-horizontal seam)
-- ============================================================
-- One row per (tenant, module). An ABSENT row means NOT entitled — a module
-- is off until it is sold, the inverse of tenant_sidebar_section_config's
-- default-on. This is the first genuine per-tenant entitlement gate on the
-- platform (Plan.features is inert; platform_feature_flags is global).
--
-- (F1) V186 is taken by provider_tenants_cascade_on_tenant_delete.sql, so the
-- entitlements table lands at V187.
CREATE TABLE IF NOT EXISTS public.tenant_module_entitlements (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    module_key  VARCHAR(40) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  UUID,
    version     BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_module UNIQUE (tenant_id, module_key)
);

CREATE INDEX IF NOT EXISTS tenant_module_entitlements_enabled_idx
    ON public.tenant_module_entitlements (tenant_id) WHERE enabled = TRUE;

COMMENT ON TABLE public.tenant_module_entitlements IS
    'V187: per-tenant module entitlement. Absent row = NOT entitled. Read by the
     shared TenantEntitlementRegistry (Java) and, over HTTP, the gateway
     EntitlementGate (Go). The @RequiresEntitlement aspect reads this under the
     tenant role, so the table is granted SELECT to tenant roles below.';

-- == Extend provision_tenant_role ============================
-- The shared TenantEntitlementRegistry queries this table inside the reactive
-- chain, which runs under SET ROLE <schema>_role (TenantAwareConnectionFactory).
-- Without a SELECT grant the aspect's lookup would hit "permission denied",
-- fail safe to FALSE, and 403 every entitled tenant. Body identical to V185;
-- only v_readable_tables gains tenant_module_entitlements.
CREATE OR REPLACE FUNCTION public.provision_tenant_role(p_schema_name text) RETURNS void AS $$
DECLARE
    v_role text := p_schema_name || '_role';
    v_table text;
    v_readable_tables text[] := ARRAY[
        'tenants',
        'providers',
        'currencies',
        'exchange_rates',
        'tenant_currency_config',
        'tenant_rules',
        'tenant_email_templates',
        'branding_config',
        'payment_methods',
        'transaction_types',
        'benefit_types',
        'notification_templates',
        'plans',
        'staff_users',
        'tenant_report_config',
        'tenant_high_cost_claimant_config',
        'provider_tenants',
        'provider_insurance_lines',
        -- Added V187: per-tenant module entitlement. The @RequiresEntitlement
        -- aspect reads this under the tenant role.
        'tenant_module_entitlements'
    ];
    v_writable_tables text[] := ARRAY[
        'scheduled_job_configs',
        'scheduled_job_runs',
        'notifications'
    ];
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
        EXECUTE format('CREATE ROLE %I NOLOGIN NOINHERIT', v_role);
    END IF;

    EXECUTE format('GRANT %I TO %I', v_role, current_user);

    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('GRANT ALL ON ALL TABLES    IN SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('GRANT ALL ON ALL SEQUENCES IN SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON TABLES    TO %I', p_schema_name, v_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON SEQUENCES TO %I', p_schema_name, v_role);

    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', v_role);

    FOREACH v_table IN ARRAY v_readable_tables LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema='public' AND table_name=v_table) THEN
            EXECUTE format('GRANT SELECT ON public.%I TO %I', v_table, v_role);
        END IF;
    END LOOP;

    FOREACH v_table IN ARRAY v_writable_tables LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema='public' AND table_name=v_table) THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON public.%I TO %I', v_table, v_role);
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Backfill: re-provision every existing tenant so already-provisioned roles
-- pick up the new SELECT grant without waiting for the next re-provision.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        PERFORM public.provision_tenant_role(t.schema_name);
    END LOOP;
END $$;
