-- Grant every tenant role SELECT on public.staff_users.
--
-- Groups whose liaison_kind = 'STAFF' resolve the liaison's email via
-- LEFT JOIN public.staff_users (see BalanceQueryRepository.groupBlock —
-- creditors + bad-debts + statement recipient-name lookups all traverse
-- this join). Before this migration the tenant role's whitelist was
-- missing staff_users, so any /api/v1/billing/balances/creditors call
-- against a tenant that ever created a STAFF-liaison group returned:
--
--   500 PermissionDeniedDataAccessException: permission denied for
--   table staff_users
--
-- Fix: append staff_users to the readable-tables list in
-- provision_tenant_role, and re-run the function for every existing
-- tenant so already-provisioned roles get the grant without waiting
-- for the next re-provision.
--
-- Same pattern as V123 — see there for the "add to whitelist +
-- backfill" template.

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
        -- Added V124: creditor / bad-debt / recipient-name lookups
        -- LEFT JOIN this for STAFF-kind liaisons.
        'staff_users'
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

-- Backfill: re-run for every existing tenant so their role gets the
-- staff_users SELECT grant without waiting for the next re-provision.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        PERFORM public.provision_tenant_role(t.schema_name);
    END LOOP;
END $$;
