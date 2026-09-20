-- ============================================================
-- V186: provider_tenants.tenant_id cascades on tenant delete
-- ============================================================
-- V185 created both of provider_tenants' foreign keys as ON DELETE RESTRICT.
-- That is right for provider_id (a provider under contract must not vanish
-- from the registry) but wrong for tenant_id: it makes deleting a tenant
-- impossible the moment a single provider is linked to it, which blocks
-- tenant offboarding and aborts scripts/reset-tenant-schemas.sh with
--   violates foreign key constraint "provider_tenants_tenant_id_fkey"
--
-- Every other table referencing public.tenants already cascades (27 of them
-- as of this migration; only staff_users differs, with SET NULL). A
-- membership row has no meaning without its tenant, so it follows the house
-- convention: the tenant goes, its memberships go with it. The provider row
-- itself is untouched, since it is platform-scoped and shared.
--
-- Idempotent: re-running against a database already on CASCADE is a no-op.

DO $$
DECLARE
    v_deltype CHAR(1);
BEGIN
    SELECT confdeltype INTO v_deltype
      FROM pg_constraint
     WHERE conname  = 'provider_tenants_tenant_id_fkey'
       AND conrelid = 'public.provider_tenants'::regclass
       AND contype  = 'f';

    IF v_deltype IS NULL THEN
        RAISE NOTICE 'V186: provider_tenants_tenant_id_fkey absent, nothing to retarget';
        RETURN;
    END IF;

    IF v_deltype = 'c' THEN
        RAISE NOTICE 'V186: provider_tenants_tenant_id_fkey already ON DELETE CASCADE';
        RETURN;
    END IF;

    ALTER TABLE public.provider_tenants
        DROP CONSTRAINT provider_tenants_tenant_id_fkey;

    ALTER TABLE public.provider_tenants
        ADD CONSTRAINT provider_tenants_tenant_id_fkey
        FOREIGN KEY (tenant_id) REFERENCES public.tenants(id) ON DELETE CASCADE;

    RAISE NOTICE 'V186: provider_tenants_tenant_id_fkey now ON DELETE CASCADE';
END
$$;
