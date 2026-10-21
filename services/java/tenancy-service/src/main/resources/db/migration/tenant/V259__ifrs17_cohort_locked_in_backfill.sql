-- Phase 15 §6 (I18) backfill: for every cohort that has never been locked
-- but already carries policies, pick the earliest issuance date across the
-- six person / asset lines, take the tenant's USD curve at that date, and
-- write the snapshot.
--
-- Deliberately narrow:
--   • USD only. Per-currency lock-in for non-USD cohorts is deferred to an
--     application-level backfill (see Ifrs17CohortLockInService).
--   • Silent skip when no matching public.tenant_yield_curve row exists —
--     the report path treats a null snapshot as data quality, not a bug.
--   • Idempotent via `WHERE locked_in_at IS NULL`; safe to re-run.
--
-- Wrapped in a DO block so the migration is a no-op on migration-only test
-- slices (TenantMigrationFlywayIT) where public.tenants / tenant_yield_curve
-- are not present. Everywhere the tenant migrations run for real (dev,
-- staging, prod), the public schema has been provisioned first.
--
-- travel_policies is the odd line out — coverage window is trip_start_date
-- / trip_end_date, not coverage_start / coverage_end (see V102 §TravelPolicy).

DO $$
DECLARE
    v_tenant_id UUID;
BEGIN
    IF to_regclass('public.tenants') IS NULL
       OR to_regclass('public.tenant_yield_curve') IS NULL THEN
        RAISE NOTICE 'V159 skipped — public.tenants / tenant_yield_curve not present';
        RETURN;
    END IF;

    SELECT id INTO v_tenant_id
    FROM public.tenants
    WHERE schema_name = current_schema()
    LIMIT 1;

    IF v_tenant_id IS NULL THEN
        RAISE NOTICE 'V159 skipped — no public.tenants row for schema %', current_schema();
        RETURN;
    END IF;

    WITH cohort_first_issuance AS (
        SELECT
            c.id AS cohort_id,
            LEAST(
                (SELECT MIN(coverage_start)  FROM life_policies       WHERE cohort_id = c.id),
                (SELECT MIN(coverage_start)  FROM funeral_policies    WHERE cohort_id = c.id),
                (SELECT MIN(coverage_start)  FROM disability_policies WHERE cohort_id = c.id),
                (SELECT MIN(trip_start_date) FROM travel_policies     WHERE cohort_id = c.id),
                (SELECT MIN(coverage_start)  FROM vehicles            WHERE cohort_id = c.id),
                (SELECT MIN(coverage_start)  FROM properties          WHERE cohort_id = c.id)
            ) AS earliest_issuance
        FROM ifrs17_cohort c
        WHERE c.locked_in_at IS NULL
    ),
    curve_snapshot AS (
        SELECT
            cfi.cohort_id,
            cfi.earliest_issuance,
            (SELECT jsonb_agg(
                        jsonb_build_object(
                            'tenorMonths', yc.tenor_months,
                            'spotRate', yc.spot_rate::text)
                        ORDER BY yc.tenor_months)
             FROM public.tenant_yield_curve yc
             WHERE yc.tenant_id = v_tenant_id
               AND yc.currency = 'USD'
               AND yc.effective_from <= cfi.earliest_issuance
               AND (yc.effective_to IS NULL OR yc.effective_to > cfi.earliest_issuance)
            ) AS snapshot
        FROM cohort_first_issuance cfi
        WHERE cfi.earliest_issuance IS NOT NULL
    )
    UPDATE ifrs17_cohort c
    SET locked_in_yield_curve_snapshot = cs.snapshot,
        locked_in_at = cs.earliest_issuance::timestamptz
    FROM curve_snapshot cs
    WHERE c.id = cs.cohort_id
      AND c.locked_in_at IS NULL
      AND cs.snapshot IS NOT NULL;
END $$;
