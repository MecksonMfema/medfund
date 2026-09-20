-- ============================================================
-- V185: Platform provider membership + insurance-line tagging
-- ============================================================
-- Providers are platform-scoped (public.providers) but their relationship
-- to each tenant is a first-class entity: contract terms, network tier,
-- in-network status. This migration adds both membership junctions and
-- backfills them from every tenant's providers shadow table (which still
-- exists at this point; tenant V276 drops it later in the same PR series,
-- after every service has repointed reads).

-- == public.provider_tenants =================================
CREATE TABLE IF NOT EXISTS public.provider_tenants (
    provider_id              UUID          NOT NULL REFERENCES public.providers(id) ON DELETE RESTRICT,
    tenant_id                UUID          NOT NULL REFERENCES public.tenants(id)   ON DELETE RESTRICT,
    status                   VARCHAR(20)   NOT NULL DEFAULT 'active',
    network_tier             VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    in_network               BOOLEAN       NOT NULL DEFAULT TRUE,
    contract_effective_from  DATE          NOT NULL DEFAULT CURRENT_DATE,
    contract_effective_to    DATE          NULL,
    credit_limit             NUMERIC(15,2) NULL,
    credit_limit_currency    CHAR(3)       NULL REFERENCES public.currencies(code),
    tariff_agreement_id      UUID          NULL,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by               UUID          NULL,
    updated_by               UUID          NULL,
    PRIMARY KEY (provider_id, tenant_id),
    CONSTRAINT provider_tenants_status_ck CHECK (status IN ('active','pending','terminated','suspended')),
    CONSTRAINT provider_tenants_tier_ck   CHECK (network_tier IN ('STANDARD','TIER_1','TIER_2','TIER_3')),
    CONSTRAINT provider_tenants_dates_ck  CHECK (contract_effective_to IS NULL OR contract_effective_to >= contract_effective_from)
);

CREATE INDEX IF NOT EXISTS ix_provider_tenants_tenant ON public.provider_tenants (tenant_id);
CREATE INDEX IF NOT EXISTS ix_provider_tenants_status ON public.provider_tenants (status);

COMMENT ON TABLE public.provider_tenants IS
    'V185: (provider, tenant) membership. Every tenant-scoped read of
     public.providers must join through this table (see CLAUDE.md Critical
     Rule 2, "platform tables with tenant membership").';

-- == public.provider_insurance_lines ==========================
CREATE TABLE IF NOT EXISTS public.provider_insurance_lines (
    provider_id     UUID        NOT NULL REFERENCES public.providers(id) ON DELETE CASCADE,
    insurance_line  VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (provider_id, insurance_line),
    CONSTRAINT provider_insurance_lines_ck CHECK (insurance_line IN
        ('HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'))
);

CREATE INDEX IF NOT EXISTS ix_provider_insurance_lines_line
    ON public.provider_insurance_lines (insurance_line);

COMMENT ON TABLE public.provider_insurance_lines IS
    'V185: (provider, line) tag. CHECK-constrained to the InsuranceLine enum
     in services/java/shared. A claim whose scheme line is missing here is
     rejected by ClaimService.validateProviderMembership.';

-- == Extend provision_tenant_role =============================
-- Body identical to V182 (including SECURITY DEFINER); only v_readable_tables
-- gained the two new junctions.
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
        -- Added V185: provider membership + line tagging. Tenant-scoped
        -- reads join public.providers through provider_tenants.
        'provider_tenants',
        'provider_insurance_lines'
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
-- pick up the two new grants without waiting for the next re-provision.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        PERFORM public.provision_tenant_role(t.schema_name);
    END LOOP;
END $$;

-- == Backfill provider_tenants from existing tenant shadows ===
-- Every tenant's providers table (which still exists at this point) contains
-- exactly the rows that tenant is contracted with. Copy them across,
-- preserving the shadow's status and network_tier where they fit the CHECK
-- vocab. Anything unmatched in public.providers is skipped (tenant V276's
-- destructive migration handles orphans). Schemas that predate the shadow
-- table, or brand-new schemas whose tenant migrations have not run yet, are
-- skipped by the information_schema guard.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN SELECT id AS tenant_id, schema_name FROM public.tenants WHERE schema_name IS NOT NULL LOOP
        CONTINUE WHEN NOT EXISTS (
            SELECT 1 FROM information_schema.tables
             WHERE table_schema = t.schema_name AND table_name = 'providers');

        EXECUTE format($f$
            INSERT INTO public.provider_tenants (provider_id, tenant_id, status, network_tier, in_network)
            SELECT tp.id, %L::uuid,
                   CASE WHEN tp.status IN ('active','pending','terminated','suspended')
                        THEN tp.status ELSE 'active' END,
                   CASE WHEN tp.network_tier IN ('STANDARD','TIER_1','TIER_2','TIER_3')
                        THEN tp.network_tier ELSE 'STANDARD' END,
                   TRUE
              FROM %I.providers tp
             WHERE EXISTS (SELECT 1 FROM public.providers pp WHERE pp.id = tp.id)
            ON CONFLICT (provider_id, tenant_id) DO NOTHING
        $f$, t.tenant_id, t.schema_name);
    END LOOP;
END $$;

-- == Backfill provider_insurance_lines ========================
-- Every existing provider gets a line tag inferred from
-- public.providers.provider_type. Default HEALTH;
-- BROKER/ADVISOR/FINANCIAL -> LIFE; FUNERAL_PARLOUR/FUNERAL -> FUNERAL.
-- The catch-all at the end guarantees no provider ends up untagged
-- (ClaimService.validateProviderMembership would 422 every claim otherwise).
INSERT INTO public.provider_insurance_lines (provider_id, insurance_line)
SELECT id, 'HEALTH' FROM public.providers
 WHERE provider_type IS NULL
    OR provider_type IN ('HEALTH','HEALTHCARE','GP','SPECIALIST','HOSPITAL',
                         'PHARMACY','LAB','DENTAL','OPTICAL')
ON CONFLICT DO NOTHING;

INSERT INTO public.provider_insurance_lines (provider_id, insurance_line)
SELECT id, 'LIFE' FROM public.providers
 WHERE provider_type IN ('BROKER','ADVISOR','FINANCIAL')
ON CONFLICT DO NOTHING;

INSERT INTO public.provider_insurance_lines (provider_id, insurance_line)
SELECT id, 'FUNERAL' FROM public.providers
 WHERE provider_type IN ('FUNERAL_PARLOUR','FUNERAL')
ON CONFLICT DO NOTHING;

INSERT INTO public.provider_insurance_lines (provider_id, insurance_line)
SELECT p.id, 'HEALTH' FROM public.providers p
 WHERE NOT EXISTS (SELECT 1 FROM public.provider_insurance_lines l
                    WHERE l.provider_id = p.id)
ON CONFLICT DO NOTHING;
