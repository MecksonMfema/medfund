-- =====================================================================
-- V117: Per-tenant database roles for hard cross-tenant isolation
-- =====================================================================
-- Schema-per-tenant was meant to keep tenants apart, but it was being
-- enforced only by convention — the single 'medfund' DB user had USAGE
-- on every tenant_* schema, so any code path that referenced
-- tenant_other.<table> would have read it. The bug we just fixed (writes
-- silently landing in public because search_path resolved to a phantom
-- schema) is one symptom; SQL injection at any tenant endpoint would
-- have been another.
--
-- This migration installs a tenant-scoped role per tenant. The
-- TenantAwareConnectionFactory will SET ROLE into it after SET
-- search_path so the connection inherits ONLY that role's grants — the
-- DB then refuses any access outside the tenant's own schema and a
-- whitelisted set of platform-wide reads (tenants, providers,
-- currencies, exchange_rates, …) and the scheduler tables.
--
-- The grant matrix lives in a reusable function so the
-- SchemaProvisioningService can call the same logic when a new tenant
-- is created — keeps "what tenants can touch" in one place.
-- =====================================================================

CREATE OR REPLACE FUNCTION public.provision_tenant_role(p_schema_name text) RETURNS void AS $$
DECLARE
    v_role text := p_schema_name || '_role';
    v_table text;
    v_readable_tables text[] := ARRAY[
        -- Platform metadata the tenant legitimately needs to read.
        -- Anything NOT on this list is implicitly denied because the
        -- role has no other public-schema grants.
        'tenants',                    -- membership_model lookup, branding
        'providers',                  -- cross-tenant provider registry
        'currencies',                 -- ISO 4217 master
        'exchange_rates',             -- FX rates (per-tenant or platform-wide)
        'tenant_currency_config',     -- enabled / default currencies per tenant
        'tenant_rules',               -- per-tenant Drools rule definitions
        'tenant_email_templates',     -- per-tenant notification templates
        'branding_config',            -- per-tenant logo / palette / domain
        'payment_methods',            -- catalog of payment methods
        'transaction_types',          -- catalog of accounting types
        'benefit_types',              -- catalog of benefit types
        'notification_templates',     -- shared template fallbacks
        'plans'                       -- subscription plan tiers
    ];
    v_writable_tables text[] := ARRAY[
        -- The dispatcher reads + writes these from tenant contexts to
        -- enqueue and poll background jobs (billing previews/commits etc.).
        'scheduled_job_configs',
        'scheduled_job_runs'
    ];
BEGIN
    -- ── Role ─────────────────────────────────────────────────────────
    -- NOLOGIN: the role is only ever entered via SET ROLE from the app
    --          user — no one can `psql -U tenant_xyz_role`.
    -- NOINHERIT: when the app SET ROLE's into this role, ONLY its
    --            explicit grants apply (not PUBLIC's grants etc.).
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) THEN
        EXECUTE format('CREATE ROLE %I NOLOGIN NOINHERIT', v_role);
    END IF;

    -- Allow the connection-pool user (medfund) to SET ROLE into the
    -- tenant role. Repeat GRANTs are no-ops, so safe to re-run.
    EXECUTE format('GRANT %I TO %I', v_role, current_user);

    -- ── Tenant schema: full access (existing + future tables) ───────
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('GRANT ALL ON ALL TABLES    IN SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('GRANT ALL ON ALL SEQUENCES IN SCHEMA %I TO %I', p_schema_name, v_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON TABLES    TO %I', p_schema_name, v_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT ALL ON SEQUENCES TO %I', p_schema_name, v_role);

    -- ── Public schema: USAGE only, then specific table grants ───────
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', v_role);

    -- Platform-wide reads. We guard each grant with an EXISTS check so
    -- a fresh DB (where some optional catalog table hasn't been
    -- migrated yet) doesn't break the function — missing tables are
    -- skipped silently.
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

REVOKE EXECUTE ON FUNCTION public.provision_tenant_role(text) FROM PUBLIC;

-- ── Backfill: provision a role for every tenant that exists today ──
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        PERFORM public.provision_tenant_role(t.schema_name);
    END LOOP;
END $$;
