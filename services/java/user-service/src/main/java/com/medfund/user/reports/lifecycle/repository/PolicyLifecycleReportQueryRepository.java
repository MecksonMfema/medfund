package com.medfund.user.reports.lifecycle.repository;

import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.user.reports.lifecycle.dto.GroupCensusRow;
import com.medfund.user.reports.lifecycle.dto.MorbidityExposureRow;
import com.medfund.user.reports.lifecycle.dto.MortalityExposureRow;
import com.medfund.user.reports.lifecycle.dto.PersistencyCohortRow;
import com.medfund.user.reports.lifecycle.dto.PolicyMovementRow;
import io.r2dbc.spi.Parameters;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 13 §C Phase 8: SQL projections for the three POLICY_LIFECYCLE
 * reports (POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS).
 *
 * <p>All queries read tenant-schema tables via unqualified names
 * (per {@code bug_public_prefix_silent_rollback} — never prefix
 * {@code public.} on tenant tables). Movement rows are native-currency
 * per parent-plan invariant #1; the workbook does FX to reporting
 * currency best-effort.
 */
@Repository
@RequiredArgsConstructor
public class PolicyLifecycleReportQueryRepository {

    private final DatabaseClient db;

    private static final List<PolicySource> POLICY_SOURCES = List.of(
        new PolicySource("LIFE_POLICY",       "LIFE",       "life_policies"),
        new PolicySource("FUNERAL_POLICY",    "FUNERAL",    "funeral_policies"),
        new PolicySource("DISABILITY_POLICY", "DISABILITY", "disability_policies"),
        new PolicySource("TRAVEL_POLICY",     "TRAVEL",     "travel_policies"),
        new PolicySource("VEHICLE_POLICY",    "VEHICLE",    "vehicles"),
        new PolicySource("PROPERTY_POLICY",   "PROPERTY",   "properties")
    );

    private record PolicySource(String source, String line, String table) {}

    // ── POLICY_MOVEMENT ─────────────────────────────────────────────────

    /**
     * One row per (policy_source, currency_code):
     *   • openingCount:      policies bound before periodStart still active at periodStart
     *   • newBusinessCount:  policies bound in [periodStart, periodEnd] with no renewed_from
     *   • renewedCount:      policies bound in [periodStart, periodEnd] with a renewed_from
     *   • lapsedCount:       policy_status_history rows to='lapsed' in window
     *   • terminatedCount:   policy_status_history rows to='terminated' in window
     *   • closingCount:      policies active at periodEnd
     *   • writtenPremiumAdded:   SUM(|written_premium|) of newly-bound policies
     *   • writtenPremiumRemoved: SUM(|written_premium|) of lapsed+terminated policies
     */
    public Flux<PolicyMovementRow> movementRows(LocalDate periodStart, LocalDate periodEnd) {
        String sql = buildMovementSql();
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map((row, meta) -> new PolicyMovementRow(
                        row.get("policy_source", String.class),
                        row.get("insurance_line", String.class),
                        row.get("currency_code", String.class),
                        nonNull(row.get("opening_count", Long.class)),
                        nonNull(row.get("new_business_count", Long.class)),
                        nonNull(row.get("renewed_count", Long.class)),
                        nonNull(row.get("lapsed_count", Long.class)),
                        nonNull(row.get("terminated_count", Long.class)),
                        nonNull(row.get("closing_count", Long.class)),
                        row.get("written_added", BigDecimal.class),
                        row.get("written_removed", BigDecimal.class)))
                .all();
    }

    private String buildMovementSql() {
        // Emit a per-source SELECT of six aggregate columns keyed by (source, line, currency_code).
        // policy_status_history keys the mid-term lapses / terminations for the window.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < POLICY_SOURCES.size(); i++) {
            PolicySource s = POLICY_SOURCES.get(i);
            if (i > 0) sb.append("UNION ALL\n");
            sb.append("""
                SELECT '__SRC__'::varchar AS policy_source,
                       '__LINE__'::varchar AS insurance_line,
                       COALESCE(p.written_premium_currency, 'USD') AS currency_code,
                       SUM(CASE WHEN p.bound_at < :periodStart AND (p.status IN ('active','suspended')) THEN 1 ELSE 0 END) AS opening_count,
                       SUM(CASE WHEN p.bound_at >= :periodStart AND p.bound_at <= (:periodEnd::timestamp + interval '1 day')
                                 AND p.renewed_from_policy_id IS NULL THEN 1 ELSE 0 END) AS new_business_count,
                       SUM(CASE WHEN p.bound_at >= :periodStart AND p.bound_at <= (:periodEnd::timestamp + interval '1 day')
                                 AND p.renewed_from_policy_id IS NOT NULL THEN 1 ELSE 0 END) AS renewed_count,
                       COALESCE((
                           SELECT COUNT(*) FROM policy_status_history h
                            WHERE h.policy_source = '__SRC__'
                              AND h.to_status = 'lapsed'
                              AND h.effective_at >= :periodStart::timestamp
                              AND h.effective_at <  (:periodEnd::timestamp + interval '1 day')
                              AND h.policy_id IN (SELECT id FROM __TABLE__ WHERE COALESCE(written_premium_currency, 'USD') = COALESCE(p.written_premium_currency, 'USD'))
                       ), 0) AS lapsed_count,
                       COALESCE((
                           SELECT COUNT(*) FROM policy_status_history h
                            WHERE h.policy_source = '__SRC__'
                              AND h.to_status = 'terminated'
                              AND h.effective_at >= :periodStart::timestamp
                              AND h.effective_at <  (:periodEnd::timestamp + interval '1 day')
                              AND h.policy_id IN (SELECT id FROM __TABLE__ WHERE COALESCE(written_premium_currency, 'USD') = COALESCE(p.written_premium_currency, 'USD'))
                       ), 0) AS terminated_count,
                       SUM(CASE WHEN (p.status IN ('active','suspended')) AND p.bound_at <= (:periodEnd::timestamp + interval '1 day') THEN 1 ELSE 0 END) AS closing_count,
                       COALESCE(SUM(CASE WHEN p.bound_at >= :periodStart AND p.bound_at <= (:periodEnd::timestamp + interval '1 day')
                                          THEN ABS(COALESCE(p.written_premium, 0)) ELSE 0 END), 0) AS written_added,
                       COALESCE(SUM(CASE WHEN p.status IN ('lapsed','terminated') AND p.bound_at <= (:periodEnd::timestamp + interval '1 day')
                                          THEN ABS(COALESCE(p.written_premium, 0)) ELSE 0 END), 0) AS written_removed
                  FROM __TABLE__ p
                 GROUP BY COALESCE(p.written_premium_currency, 'USD')
                """
                .replace("__SRC__", s.source())
                .replace("__LINE__", s.line())
                .replace("__TABLE__", s.table()));
        }
        return sb.toString();
    }

    /**
     * Per-currency roll for the envelope — sums |written_added| across all
     * six policy sources so the envelope's perCurrency map is coherent
     * regardless of how many lines contributed.
     */
    public Mono<Map<String, PerCurrencyTotal>> movementPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        return movementRows(periodStart, periodEnd)
                .collectList()
                .map(rows -> {
                    Map<String, BigDecimal> sums = new LinkedHashMap<>();
                    Map<String, Long> counts = new LinkedHashMap<>();
                    for (PolicyMovementRow r : rows) {
                        String cur = r.currencyCode() != null ? r.currencyCode() : "USD";
                        BigDecimal added = r.writtenPremiumAdded() != null ? r.writtenPremiumAdded() : BigDecimal.ZERO;
                        BigDecimal removed = r.writtenPremiumRemoved() != null ? r.writtenPremiumRemoved() : BigDecimal.ZERO;
                        sums.merge(cur, added.add(removed), BigDecimal::add);
                        counts.merge(cur, r.newBusinessCount() + r.renewedCount() + r.lapsedCount() + r.terminatedCount(),
                                Long::sum);
                    }
                    Map<String, PerCurrencyTotal> out = new LinkedHashMap<>();
                    sums.forEach((cur, sum) -> out.put(cur, new PerCurrencyTotal(sum, counts.getOrDefault(cur, 0L))));
                    return out;
                });
    }

    // ── PERSISTENCY_COHORT ───────────────────────────────────────────────

    /**
     * Cohort persistency rows per L9 + L16. Cohort = policies bound in
     * cohortMonth (annual lines) or members whose first contribution
     * landed in cohortMonth (HEALTH — via
     * {@code member_first_contribution} matview). Still-active is measured
     * at (cohortMonth + checkpointMonths). For HEALTH the extra check is
     * "row present in {@code member_contribution_presence} for the
     * checkpoint month" per L16.
     *
     * <p>Emits one row per (cohortMonth, insuranceLine, checkpoint).
     */
    public Flux<PersistencyCohortRow> persistencyCohortRows(LocalDate periodStart, LocalDate periodEnd,
                                                            List<Integer> checkpoints, String insuranceLine) {
        String sql = buildPersistencySql(checkpoints);
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("insuranceLine", nullable(insuranceLine));
        return spec
                .map((row, meta) -> {
                    BigDecimal rate = BigDecimal.ZERO;
                    long size = nonNull(row.get("cohort_size", Long.class));
                    long active = nonNull(row.get("still_active", Long.class));
                    if (size > 0) {
                        rate = BigDecimal.valueOf(active)
                                .multiply(BigDecimal.valueOf(100))
                                .divide(BigDecimal.valueOf(size), 2, RoundingMode.HALF_EVEN);
                    }
                    return new PersistencyCohortRow(
                            row.get("cohort_month", LocalDate.class),
                            row.get("insurance_line", String.class),
                            nonNullInt(row.get("checkpoint_months", Integer.class)),
                            size, active, rate);
                })
                .all();
    }

    private String buildPersistencySql(List<Integer> checkpoints) {
        // Per-line cohort SELECT with a UNION for HEALTH; then a checkpoint join.
        String checkpointValues = checkpoints.stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + "), (" + b)
                .map(s -> "(" + s + ")")
                .orElse("(12)");

        StringBuilder cohorts = new StringBuilder("WITH cohort AS (\n");
        for (int i = 0; i < POLICY_SOURCES.size(); i++) {
            PolicySource s = POLICY_SOURCES.get(i);
            if (i > 0) cohorts.append("  UNION ALL\n");
            cohorts.append("  SELECT id AS policy_id, '").append(s.source())
                    .append("'::varchar AS policy_source, '").append(s.line())
                    .append("'::varchar AS insurance_line, ")
                    .append("DATE_TRUNC('month', bound_at)::date AS cohort_month, ")
                    .append("status, renewed_from_policy_id FROM ").append(s.table())
                    .append(" WHERE bound_at::date BETWEEN :periodStart AND :periodEnd\n");
        }
        cohorts.append("""
                ) ,
                health_cohort AS (
                    SELECT NULL::uuid AS policy_id, 'CONTRIBUTION'::varchar AS policy_source,
                           'HEALTH'::varchar AS insurance_line,
                           DATE_TRUNC('month', mfc.first_at)::date AS cohort_month,
                           'active'::varchar AS status, NULL::uuid AS renewed_from_policy_id,
                           mfc.member_id AS member_id
                      FROM member_first_contribution mfc
                     WHERE mfc.first_at::date BETWEEN :periodStart AND :periodEnd
                ),
                cp AS (
                    SELECT checkpoint_months FROM (VALUES __CHECKPOINTS__) AS v(checkpoint_months)
                ),
                all_cohorts AS (
                    SELECT policy_id, policy_source, insurance_line, cohort_month, status,
                           renewed_from_policy_id, NULL::uuid AS member_id FROM cohort
                    UNION ALL
                    SELECT policy_id, policy_source, insurance_line, cohort_month, status,
                           renewed_from_policy_id, member_id FROM health_cohort
                )
                SELECT c.cohort_month,
                       c.insurance_line,
                       cp.checkpoint_months,
                       COUNT(*) AS cohort_size,
                       SUM(CASE
                           WHEN c.insurance_line = 'HEALTH' THEN (
                               CASE WHEN EXISTS (
                                   SELECT 1 FROM member_contribution_presence mcp
                                    WHERE mcp.member_id = c.member_id
                                      AND mcp.contribution_month =
                                          (c.cohort_month + (cp.checkpoint_months || ' months')::interval)::date
                               ) THEN 1 ELSE 0 END
                           )
                           ELSE (
                               CASE WHEN c.status = 'active'
                                     AND CURRENT_DATE >= (c.cohort_month
                                             + (cp.checkpoint_months || ' months')::interval)::date
                                    THEN 1 ELSE 0 END
                           )
                           END) AS still_active
                  FROM all_cohorts c
                  CROSS JOIN cp
                 WHERE (:insuranceLine::varchar IS NULL OR c.insurance_line = :insuranceLine::varchar)
                 GROUP BY c.cohort_month, c.insurance_line, cp.checkpoint_months
                 ORDER BY c.cohort_month, c.insurance_line, cp.checkpoint_months
                """.replace("__CHECKPOINTS__", checkpointValues));
        return cohorts.toString();
    }

    // ── GROUP_CENSUS ────────────────────────────────────────────────────

    /**
     * Per-group census snapshot at {@code asOf}. Status is the members'
     * current {@code status} column — this trades the strict "latest
     * {@code member_status_history} row with effective_at &le; asOf"
     * projection for a much simpler query. asOf in the future would need
     * the history-based projection; the current implementation only
     * supports asOf &le; today (validated at the controller).
     *
     * <p>{@code groupId} filter narrows to a single group; NULL returns
     * every group.
     */
    public Flux<GroupCensusRow> groupCensusRows(LocalDate asOf, UUID groupId, String statusFilter) {
        String sql = """
                SELECT g.id AS group_id,
                       g.name AS group_name,
                       g.registration_number,
                       g.contact_person,
                       g.contact_email,
                       COUNT(*) FILTER (WHERE m.status = 'active')     AS active_members,
                       COUNT(*) FILTER (WHERE m.status = 'suspended')  AS suspended_members,
                       COUNT(*) FILTER (WHERE m.status = 'lapsed')     AS lapsed_members,
                       COUNT(*) FILTER (WHERE m.status = 'terminated') AS terminated_members,
                       COUNT(m.id)                                      AS total_members
                  FROM groups g
                  LEFT JOIN members m ON m.group_id = g.id
                                     AND m.enrollment_date <= :asOf
                 WHERE (:groupId::uuid IS NULL OR g.id = :groupId::uuid)
                   AND (:statusFilter::varchar IS NULL OR m.status = :statusFilter::varchar OR m.id IS NULL)
                 GROUP BY g.id, g.name, g.registration_number, g.contact_person, g.contact_email
                 ORDER BY g.name
                """;
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("asOf", asOf)
                .bind("statusFilter", nullable(statusFilter));
        spec = groupId == null
                ? spec.bindNull("groupId", UUID.class)
                : spec.bind("groupId", groupId);
        return spec
                .map((row, meta) -> new GroupCensusRow(
                        row.get("group_id", UUID.class),
                        row.get("group_name", String.class),
                        row.get("registration_number", String.class),
                        row.get("contact_person", String.class),
                        row.get("contact_email", String.class),
                        nonNull(row.get("active_members", Long.class)),
                        nonNull(row.get("suspended_members", Long.class)),
                        nonNull(row.get("lapsed_members", Long.class)),
                        nonNull(row.get("terminated_members", Long.class)),
                        nonNull(row.get("total_members", Long.class))))
                .all();
    }

    // ── MORTALITY_STUDY exposure feed (Phase 14 §Actuarial Phase 13) ────

    /**
     * Aggregate member exposure over {@code [periodStart, periodEnd]} into
     * (age_band, sex) rows. Feeds finance-service's
     * {@code MortalityExposureShapingService}, which pivots this into the
     * exposure payload slot on {@code ActuarialJobRequestedEvent} for the
     * Python MORTALITY_STUDY compute.
     *
     * <p>Age bands are 5-year buckets from 0-4 up to 85+; the bucket is
     * taken at {@code periodEnd} (i.e. the member's age on the last day
     * of the window), which matches the SOA convention for cohort-year
     * exposure studies. Exposure days = days between (max enrollment or
     * periodStart) and (min termination-or-death-or-periodEnd), floor 0.
     * Deaths count members whose {@code death_date} lands in the window
     * (inclusive on both ends).
     *
     * <p>{@code insuranceLine} filter narrows the population — HEALTH
     * uses the {@code member_first_contribution} matview (Phase-13 signal
     * that a member has an active health cover); LIFE / FUNERAL /
     * DISABILITY / TRAVEL join their respective policy tables via
     * {@code insured_member_id}. NULL / blank returns every member with
     * a valid date_of_birth.
     */
    public Flux<MortalityExposureRow> mortalityExposureRows(LocalDate periodStart, LocalDate periodEnd,
                                                            String insuranceLine) {
        String lineNorm = insuranceLine == null || insuranceLine.isBlank() ? null : insuranceLine.trim();
        String populationSql = mortalityPopulationSql(lineNorm);
        String sql = """
                WITH population AS (%s),
                per_member AS (
                    SELECT
                        FLOOR(EXTRACT(EPOCH FROM AGE(:periodEnd::date, m.date_of_birth::date))
                              / (86400 * 365.25))::int AS age_years,
                        LOWER(COALESCE(NULLIF(m.gender, ''), 'unknown')) AS sex,
                        GREATEST(0,
                            LEAST(:periodEnd::date,
                                  COALESCE(m.death_date, m.termination_date, :periodEnd::date))
                            - GREATEST(:periodStart::date, m.enrollment_date)
                        ) AS exposure_days,
                        CASE
                            WHEN m.death_date IS NOT NULL
                             AND m.death_date >= :periodStart::date
                             AND m.death_date <= :periodEnd::date THEN 1
                            ELSE 0
                        END AS died
                      FROM members m
                     WHERE m.id IN (SELECT id FROM population)
                       AND m.date_of_birth IS NOT NULL
                       AND m.enrollment_date IS NOT NULL
                       AND m.enrollment_date <= :periodEnd::date
                )
                SELECT
                    CASE
                        WHEN age_years < 0 THEN NULL
                        WHEN age_years >= 85 THEN '85+'
                        ELSE (FLOOR(age_years / 5) * 5)::text
                             || '-' || (FLOOR(age_years / 5) * 5 + 4)::text
                    END AS age_band,
                    sex,
                    SUM(exposure_days)::numeric / 365.25 AS exposure_years,
                    SUM(died)::bigint AS deaths
                  FROM per_member
                 WHERE exposure_days > 0
                 GROUP BY age_band, sex
                HAVING SUM(exposure_days) > 0 AND age_band IS NOT NULL
                 ORDER BY age_band, sex
                """.formatted(populationSql);
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd);
        if (lineNorm != null && requiresLineBind(lineNorm)) {
            spec = spec.bind("insuranceLine", lineNorm);
        }
        return spec
                .map((row, meta) -> new MortalityExposureRow(
                        row.get("age_band", String.class),
                        row.get("sex", String.class),
                        row.get("exposure_years", BigDecimal.class) == null
                                ? 0.0
                                : row.get("exposure_years", BigDecimal.class).doubleValue(),
                        nonNull(row.get("deaths", Long.class))))
                .all();
    }

    private static String mortalityPopulationSql(String insuranceLine) {
        // Every branch returns "id" from the members table; the outer query
        // joins members back to pull dob/gender/exposure/death fields.
        if (insuranceLine == null) {
            return "SELECT id FROM members";
        }
        return switch (insuranceLine) {
            case "HEALTH" -> "SELECT member_id AS id FROM member_first_contribution";
            case "LIFE" -> "SELECT DISTINCT insured_member_id AS id FROM life_policies "
                        + "WHERE insured_member_id IS NOT NULL "
                        + "  AND bound_at <= (:periodEnd::timestamp + interval '1 day')";
            case "FUNERAL" -> "SELECT DISTINCT insured_member_id AS id FROM funeral_policies "
                        + "WHERE insured_member_id IS NOT NULL "
                        + "  AND bound_at <= (:periodEnd::timestamp + interval '1 day')";
            case "DISABILITY" -> "SELECT DISTINCT insured_member_id AS id FROM disability_policies "
                        + "WHERE insured_member_id IS NOT NULL "
                        + "  AND bound_at <= (:periodEnd::timestamp + interval '1 day')";
            case "TRAVEL" -> "SELECT DISTINCT insured_member_id AS id FROM travel_policies "
                        + "WHERE insured_member_id IS NOT NULL "
                        + "  AND bound_at <= (:periodEnd::timestamp + interval '1 day')";
            default -> "SELECT id FROM members WHERE FALSE";  // unknown line → empty population
        };
    }

    private static boolean requiresLineBind(String insuranceLine) {
        // Every current branch has the line baked in as literal text (matched
        // via switch), not a bind. Reserved for future extension.
        return false;
    }

    // ── MORBIDITY_STUDY exposure feed (Phase 14 §Actuarial Phase 14) ─────

    /**
     * Per-tenant morbidity exposure feed. Mirrors
     * {@link #mortalityExposureRows} — same population, same age-band
     * bucketing, same exposure-years denominator — but the numerator is
     * {@code incidents} instead of {@code deaths}.
     *
     * <p>An "incident" is a {@code member_status_history} transition in
     * the requested window whose {@code reason_code} matches the morbidity
     * vocabulary ({@code illness_onset}, {@code disability_onset},
     * {@code hospitalization}). Distinct per member per window — a member
     * who transitions in and out repeatedly counts once. Absent codes yield
     * zero incidents, mirroring the Phase-13 "empty envelope with warnings"
     * acceptance for tenants with no configured signal.
     */
    public Flux<MorbidityExposureRow> morbidityIncidenceRows(LocalDate periodStart, LocalDate periodEnd,
                                                              String insuranceLine) {
        String lineNorm = insuranceLine == null || insuranceLine.isBlank() ? null : insuranceLine.trim();
        String populationSql = mortalityPopulationSql(lineNorm);
        String sql = """
                WITH population AS (%s),
                per_member AS (
                    SELECT
                        FLOOR(EXTRACT(EPOCH FROM AGE(:periodEnd::date, m.date_of_birth::date))
                              / (86400 * 365.25))::int AS age_years,
                        LOWER(COALESCE(NULLIF(m.gender, ''), 'unknown')) AS sex,
                        GREATEST(0,
                            LEAST(:periodEnd::date,
                                  COALESCE(m.death_date, m.termination_date, :periodEnd::date))
                            - GREATEST(:periodStart::date, m.enrollment_date)
                        ) AS exposure_days,
                        CASE
                            WHEN EXISTS (
                                SELECT 1 FROM member_status_history h
                                 WHERE h.member_id = m.id
                                   AND h.reason_code IN (
                                       'illness_onset', 'disability_onset', 'hospitalization'
                                   )
                                   AND h.transitioned_at >= :periodStart::timestamp
                                   AND h.transitioned_at <  (:periodEnd::timestamp + interval '1 day')
                            ) THEN 1
                            ELSE 0
                        END AS had_incident
                      FROM members m
                     WHERE m.id IN (SELECT id FROM population)
                       AND m.date_of_birth IS NOT NULL
                       AND m.enrollment_date IS NOT NULL
                       AND m.enrollment_date <= :periodEnd::date
                )
                SELECT
                    CASE
                        WHEN age_years < 0 THEN NULL
                        WHEN age_years >= 85 THEN '85+'
                        ELSE (FLOOR(age_years / 5) * 5)::text
                             || '-' || (FLOOR(age_years / 5) * 5 + 4)::text
                    END AS age_band,
                    sex,
                    SUM(exposure_days)::numeric / 365.25 AS exposure_years,
                    SUM(had_incident)::bigint AS incidents
                  FROM per_member
                 WHERE exposure_days > 0
                 GROUP BY age_band, sex
                HAVING SUM(exposure_days) > 0 AND age_band IS NOT NULL
                 ORDER BY age_band, sex
                """.formatted(populationSql);
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd);
        if (lineNorm != null && requiresLineBind(lineNorm)) {
            spec = spec.bind("insuranceLine", lineNorm);
        }
        return spec
                .map((row, meta) -> new MorbidityExposureRow(
                        row.get("age_band", String.class),
                        row.get("sex", String.class),
                        row.get("exposure_years", BigDecimal.class) == null
                                ? 0.0
                                : row.get("exposure_years", BigDecimal.class).doubleValue(),
                        nonNull(row.get("incidents", Long.class))))
                .all();
    }

    // ── freshness monitor for PERSISTENCY_COHORT ────────────────────────

    /**
     * Read the newest run row's {@code contrib_presence_refresh_at}
     * timestamp. Returns Mono.empty() when no run has ever recorded a
     * refresh. PersistencyCohortReportService compares this against NOW
     * and emits a freshness warning when the newest refresh is more than
     * 24 hours stale.
     */
    public Mono<java.time.Instant> latestContribPresenceRefreshAt() {
        // Cross-schema read: the earning_schedule_run table sits in the
        // contributions-service's tenant schema. User-service and
        // contributions-service both bind to the same tenant schema at
        // runtime, so an unqualified read is safe (per
        // bug_public_prefix_silent_rollback).
        return db.sql("""
                SELECT contrib_presence_refresh_at
                  FROM earning_schedule_run
                 WHERE contrib_presence_refresh_at IS NOT NULL
                 ORDER BY contrib_presence_refresh_at DESC
                 LIMIT 1
                """)
                .map((row, meta) -> row.get("contrib_presence_refresh_at", java.time.Instant.class))
                .one()
                .onErrorResume(err -> Mono.empty());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static long nonNull(Long v) { return v != null ? v : 0L; }
    private static int nonNullInt(Integer v) { return v != null ? v : 0; }

    private static io.r2dbc.spi.Parameter nullable(String value) {
        return value == null || value.isBlank()
                ? Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR)
                : Parameters.in(value);
    }
}
