-- ============================================================
-- V275: Retarget the 3 hard provider FKs to public.providers
-- ============================================================
-- claims.provider_id, payments.provider_id and quotations.provider_id
-- currently REFERENCE the tenant-local providers table. This migration
-- retargets them to public.providers. Same pattern as tenant/V268:33-41:
-- ALTER TABLE ADD CONSTRAINT inside a guarded DO $$ IF NOT EXISTS $$.
--
-- Preconditions:
--   - public.provider_tenants populated (public V185 backfilled it).
--   - No orphaned provider_id values in claims / payments / quotations
--     (verified 2026-09-20: zero rows across all live tenants).
--
-- The tenant-local providers table stays alive; V276 drops it.
--
-- The DROP side is driven from pg_constraint rather than a hard-coded
-- constraint name: schemas provisioned at different times can carry
-- name drift (claims_provider_id_fkey vs claims_provider_id_fkey1),
-- and a name-based DROP would silently leave the old FK in place.

DO $$
DECLARE
    r record;
BEGIN
    -- Drop every FK on the three tables that still points at the
    -- tenant-local providers table, whatever it happens to be called.
    FOR r IN
        SELECT con.conname, rel.relname AS table_name
          FROM pg_constraint con
          JOIN pg_class     rel  ON rel.oid  = con.conrelid
          JOIN pg_namespace ns   ON ns.oid   = rel.relnamespace
          JOIN pg_class     fref ON fref.oid = con.confrelid
          JOIN pg_namespace fns  ON fns.oid  = fref.relnamespace
         WHERE con.contype  = 'f'
           AND ns.nspname   = current_schema()
           AND fns.nspname  = current_schema()
           AND fref.relname = 'providers'
           AND rel.relname IN ('claims', 'payments', 'quotations')
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.table_name, r.conname);
    END LOOP;

    -- claims.provider_id (NOT NULL)
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'claims_provider_id_public_fkey'
                      AND connamespace = current_schema()::regnamespace) THEN
        ALTER TABLE claims
            ADD CONSTRAINT claims_provider_id_public_fkey
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE RESTRICT;
    END IF;

    -- payments.provider_id (nullable)
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'payments_provider_id_public_fkey'
                      AND connamespace = current_schema()::regnamespace) THEN
        ALTER TABLE payments
            ADD CONSTRAINT payments_provider_id_public_fkey
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE SET NULL;
    END IF;

    -- quotations.provider_id (NOT NULL)
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'quotations_provider_id_public_fkey'
                      AND connamespace = current_schema()::regnamespace) THEN
        ALTER TABLE quotations
            ADD CONSTRAINT quotations_provider_id_public_fkey
            FOREIGN KEY (provider_id) REFERENCES public.providers(id) ON DELETE RESTRICT;
    END IF;
END $$;
