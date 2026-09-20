-- ============================================================
-- V276: Drop the tenant-local providers table
-- ============================================================
-- Providers are now platform-scoped (public.providers) with per-tenant
-- membership in public.provider_tenants. The shadow table in this schema
-- was populated by an Option A fan-out that has since been retired. All
-- code has repointed reads to public.providers as of Phase 5.
--
-- Preconditions:
--   1. V185 (public) landed and backfilled public.provider_tenants +
--      public.provider_insurance_lines.
--   2. V275 (tenant) retargeted the 3 hard FKs (claims, payments,
--      quotations) to public.providers.
--   3. Every service has been redeployed with the read-repointing:
--      claims-service (Phase 3), finance-service (Phase 4), user-service
--      and notification-service (Phase 5).
--
-- Safety guard: fail the migration if any orphan row in this tenant's
-- providers table has a keycloak_user_id set. That would indicate real
-- operator-created data that was never mirrored to public.providers,
-- which the drop would destroy silently.

DO $$
DECLARE
    orphan_count integer;
    orphan_names text;
BEGIN
    -- Never fire against the platform schema. Local dev points Flyway at
    -- BOTH db/migration/public and db/migration/tenant with schemas: public
    -- (application.yml:26-33), so every tenant migration also runs once with
    -- current_schema() = 'public'. There, the unqualified `providers` below
    -- resolves to public.providers: the platform registry this migration
    -- exists to migrate *towards*. The orphan guard cannot catch it either,
    -- because it would be comparing public.providers to itself and always
    -- find zero orphans. Same RAISE NOTICE + RETURN shape as tenant V259.
    IF current_schema() = 'public' THEN
        RAISE NOTICE 'V276 skipped in public schema: the providers table there is the platform registry, not a tenant shadow';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                    WHERE table_schema = current_schema() AND table_name = 'providers') THEN
        RETURN;
    END IF;

    SELECT count(*), string_agg(name, ', ')
      INTO orphan_count, orphan_names
      FROM providers
     WHERE keycloak_user_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM public.providers pp WHERE pp.id = providers.id);

    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'Cannot drop % providers: % row(s) with keycloak_user_id set are missing from public.providers. Names: %. Resolve manually (either INSERT them into public.providers or NULL their keycloak_user_id) before retrying.',
            current_schema(), orphan_count, orphan_names;
    END IF;

    -- CASCADE is safe: the 3 hard FKs were retargeted to public.providers
    -- by V275, and the 8 soft provider_id columns (pre_authorizations,
    -- payment_run_items, provider_balances, notes, payment_advices,
    -- advance_payments, provider_balance_snapshot,
    -- suspicious_transaction_alert) are bare UUIDs with no constraint.
    DROP TABLE providers CASCADE;
END $$;
