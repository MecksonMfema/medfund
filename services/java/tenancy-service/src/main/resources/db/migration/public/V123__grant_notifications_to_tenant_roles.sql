-- Grant every tenant role read/write access to public.notifications.
--
-- V117's provision_tenant_role whitelists a fixed set of public tables
-- for tenant roles — anything not on the list is implicitly denied.
-- V122 added public.notifications but predates a role update, so any
-- tenant-context write from JobEventPublisher.writeBellNotification
-- hits "permission denied for table notifications" and the notification
-- row is silently dropped.
--
-- This migration:
--   1. Redefines provision_tenant_role with notifications appended to
--      the writable table list so future tenants inherit the grant.
--   2. Loops through existing tenants and re-runs the function so
--      already-provisioned roles get the grant too.

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
        'plans'
    ];
    v_writable_tables text[] := ARRAY[
        'scheduled_job_configs',
        'scheduled_job_runs',
        -- Added V123: in-app bell rows land here on every JobCompleted
        -- fan-out. Tenant roles need INSERT/UPDATE/DELETE (mark-seen).
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

-- Backfill: re-run for every existing tenant so their role gets the
-- notifications grant without waiting for the next re-provision.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        PERFORM public.provision_tenant_role(t.schema_name);
    END LOOP;
END $$;
