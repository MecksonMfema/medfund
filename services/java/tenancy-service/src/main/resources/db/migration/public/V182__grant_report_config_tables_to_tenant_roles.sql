-- Grant every tenant role SELECT on:
--   - public.tenant_report_config          (V130, per-report on/off toggle)
--   - public.tenant_high_cost_claimant_config (V132, threshold config)
--
-- Both tables are read directly from each tenant-scoped service:
--
--   ReportEnablementReader (shared) hits public.tenant_report_config on
--   every @RequiresReport-annotated endpoint. Without the grant it logs
--   WARN [report-config] enabled lookup failed ... permission denied for
--   table tenant_report_config and defaults to "enabled" (safe fallback,
--   but noisy).
--
--   TenantConfigClient in claims-service hits
--   public.tenant_high_cost_claimant_config for the HIGH_COST_CLAIMANT
--   report. Permission denied there is treated as "unconfigured",
--   which then flips the envelope warning "High-cost threshold not
--   configured for tenant" and toasts on every page load in dev.
--
-- Fix: extend provision_tenant_role's readable-tables list with these
-- two tables and re-run for every existing tenant so already-provisioned
-- roles get the grant without waiting for the next re-provision.
--
-- Same "add to whitelist + backfill" pattern as V123 / V124.

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
        -- Added V182: report catalogue + high-cost-claimant threshold
        -- lookups fire on every report-hub load and on the HIGH_COST
        -- report itself.
        'tenant_report_config',
        'tenant_high_cost_claimant_config'
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

-- Backfill: re-run for every existing tenant so their role gets the two
-- new SELECT grants without waiting for the next re-provision.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        PERFORM public.provision_tenant_role(t.schema_name);
    END LOOP;
END $$;
