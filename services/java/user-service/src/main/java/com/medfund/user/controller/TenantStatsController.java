package com.medfund.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/tenant-stats")
@RequiredArgsConstructor
@Tag(name = "Tenant Stats", description = "Tenant-scoped aggregate statistics for the tenant dashboard")
@SecurityRequirement(name = "bearer-jwt")
public class TenantStatsController {

    private final DatabaseClient db;

    /**
     * Resolves the physical Postgres schema name for a tenant by looking up
     * {@code public.tenants.schema_name}. Empty Mono when no row matches —
     * callers should treat that as "tenant not found" and fall back to the
     * empty response shape.
     */
    private Mono<String> resolveTenantSchema(UUID tenantId) {
        return db.sql("SELECT schema_name FROM public.tenants WHERE id = :tenantId")
                .bind("tenantId", tenantId)
                .map(row -> row.get("schema_name", String.class))
                .one();
    }

    @GetMapping
    @Operation(summary = "Tenant dashboard stats",
               description = "Returns staff and member counts scoped to the tenant identified by X-Tenant-ID.")
    public Mono<Map<String, Object>> getStats(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {

        if (tenantHeader == null || tenantHeader.isBlank()) {
            return Mono.just(emptyStats());
        }

        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantHeader);
        } catch (IllegalArgumentException e) {
            return Mono.just(emptyStats());
        }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> buildStats(tenantId, schema))
                .switchIfEmpty(Mono.just(emptyStats()));
    }

    private Mono<Map<String, Object>> buildStats(UUID tenantId, String schema) {
        // Staff counts — public schema, filtered by tenant_id
        Mono<Map<String, Long>> staffCounts = db.sql(
                "SELECT " +
                "  COUNT(*) FILTER (WHERE status = 'active')              AS active_staff, " +
                "  COUNT(*) FILTER (WHERE status = 'suspended')           AS suspended_staff, " +
                "  COUNT(*) FILTER (WHERE status IN ('invited','pending')) AS pending_staff, " +
                "  COUNT(*)                                                AS total_staff " +
                "FROM public.staff_users WHERE tenant_id = :tenantId")
                .bind("tenantId", tenantId)
                .map(row -> Map.of(
                        "totalStaff",     orZero(row.get("total_staff",     Long.class)),
                        "activeStaff",    orZero(row.get("active_staff",    Long.class)),
                        "suspendedStaff", orZero(row.get("suspended_staff", Long.class)),
                        "pendingStaff",   orZero(row.get("pending_staff",   Long.class))
                ))
                .one()
                .onErrorReturn(Map.of("totalStaff", 0L, "activeStaff", 0L, "suspendedStaff", 0L, "pendingStaff", 0L));

        // Member counts — tenant schema (TenantWebFilter sets search_path for tenant routes,
        // but since /api/v1/tenant-stats is in the platform bypass list we resolve the
        // schema name from public.tenants.schema_name keyed by tenant UUID.
        Mono<Map<String, Long>> memberCounts = db.sql(
                "SELECT " +
                "  COUNT(*) FILTER (WHERE status = 'active')   AS active_members, " +
                "  COUNT(*) FILTER (WHERE status = 'enrolled') AS enrolled_members, " +
                "  COUNT(*)                                     AS total_members " +
                "FROM \"" + schema + "\".members")
                .map(row -> Map.of(
                        "totalMembers",    orZero(row.get("total_members",    Long.class)),
                        "activeMembers",   orZero(row.get("active_members",   Long.class)),
                        "enrolledMembers", orZero(row.get("enrolled_members", Long.class))
                ))
                .one()
                .onErrorReturn(Map.of("totalMembers", 0L, "activeMembers", 0L, "enrolledMembers", 0L));

        // Claims — mirrors the legacy Masca-Claims-Admin dashboard (Statistics.jsx):
        //   "New Tasks" (=pending), "This Month Claims" (=submitted this month),
        //   "Claims Accepted" / "Claims Rejected" (this month) per masca-admin-dashboard.
        Mono<Map<String, Object>> claimsCounts = db.sql(
                "SELECT " +
                "  COUNT(*) FILTER (WHERE status IN ('submitted','in_adjudication','pending')) AS pending, " +
                "  COUNT(*) FILTER (WHERE created_at >= date_trunc('month', NOW())) AS this_month, " +
                "  COUNT(*) FILTER (WHERE status = 'adjudicated' AND created_at >= date_trunc('month', NOW())) AS accepted_month, " +
                "  COUNT(*) FILTER (WHERE status = 'rejected'    AND created_at >= date_trunc('month', NOW())) AS rejected_month " +
                "FROM \"" + schema + "\".claims")
                .map(row -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("claimsNewTasks",            orZero(row.get("pending",        Long.class)));
                    m.put("claimsThisMonth",           orZero(row.get("this_month",     Long.class)));
                    m.put("claimsAcceptedThisMonth",   orZero(row.get("accepted_month", Long.class)));
                    m.put("claimsRejectedThisMonth",   orZero(row.get("rejected_month", Long.class)));
                    return (Map<String, Object>) m;
                })
                .one()
                .onErrorReturn(Map.of(
                        "claimsNewTasks", 0L, "claimsThisMonth", 0L,
                        "claimsAcceptedThisMonth", 0L, "claimsRejectedThisMonth", 0L));

        // Billing — mirrors MASCA-Frontend dashboard (statistics.js):
        //   "Outstanding Creditors", "This Month Transactions", "This Year Transactions"
        //   plus our scheme count.
        Mono<Map<String, Object>> billingCounts = db.sql(
                "SELECT " +
                "  (SELECT COUNT(*) FROM \"" + schema + "\".schemes WHERE status = 'active') AS schemes_active, " +
                "  (SELECT COUNT(*) FROM \"" + schema + "\".contributions WHERE status = 'pending') AS contrib_pending, " +
                "  (SELECT COALESCE(SUM(amount),0) FROM \"" + schema + "\".contributions WHERE status = 'paid' AND created_at >= date_trunc('month', NOW())) AS contrib_amt_month, " +
                "  (SELECT COALESCE(SUM(amount),0) FROM \"" + schema + "\".contributions WHERE status = 'paid' AND created_at >= date_trunc('year',  NOW())) AS contrib_amt_year")
                .map(row -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("schemesActive",                   orZero(row.get("schemes_active", Long.class)));
                    m.put("contributionsPending",            orZero(row.get("contrib_pending", Long.class)));
                    m.put("contributionsAmountThisMonth",    orZeroBig(row.get("contrib_amt_month", java.math.BigDecimal.class)));
                    m.put("contributionsAmountThisYear",     orZeroBig(row.get("contrib_amt_year",  java.math.BigDecimal.class)));
                    return (Map<String, Object>) m;
                })
                .one()
                .onErrorReturn(Map.of(
                        "schemesActive", 0L, "contributionsPending", 0L,
                        "contributionsAmountThisMonth", java.math.BigDecimal.ZERO,
                        "contributionsAmountThisYear",  java.math.BigDecimal.ZERO));

        // Finance — mirrors the legacy Masca-Finance-Typescript dashboard
        // (topSection.tsx): "Latest Payment Run", "Total Payments (this month)",
        // "Pending Payments". Currency totals stay un-grouped here; tenants with
        // multi-currency portfolios can drill into the Finance section.
        Mono<Map<String, Object>> financeCounts = db.sql(
                "SELECT " +
                "  COUNT(*) FILTER (WHERE status = 'pending') AS payments_pending, " +
                "  COALESCE(SUM(amount) FILTER (WHERE status = 'completed' AND created_at >= date_trunc('month', NOW())), 0) AS amt_month, " +
                "  COALESCE(SUM(amount) FILTER (WHERE status = 'completed' AND created_at >= date_trunc('year',  NOW())), 0) AS amt_year, " +
                // Advance payments: payment_type='advance' tracks money paid
                // to providers/members ahead of a claim being adjudicated.
                "  COUNT(*) FILTER (WHERE payment_type = 'advance' AND status NOT IN ('failed','cancelled')) AS advance_count, " +
                "  COALESCE(SUM(amount) FILTER (WHERE payment_type = 'advance' AND status = 'completed'), 0) AS advance_amount, " +
                "  COUNT(*) FILTER (WHERE status = 'failed') AS payments_failed " +
                "FROM \"" + schema + "\".payments")
                .map(row -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("paymentsPending",              orZero(row.get("payments_pending", Long.class)));
                    m.put("paymentsAmountThisMonth",      orZeroBig(row.get("amt_month",     java.math.BigDecimal.class)));
                    m.put("paymentsAmountThisYear",       orZeroBig(row.get("amt_year",      java.math.BigDecimal.class)));
                    m.put("paymentsAdvanceCount",         orZero(row.get("advance_count",    Long.class)));
                    m.put("paymentsAdvanceAmount",        orZeroBig(row.get("advance_amount", java.math.BigDecimal.class)));
                    m.put("paymentsFailedCount",          orZero(row.get("payments_failed",  Long.class)));
                    return (Map<String, Object>) m;
                })
                .one()
                .onErrorReturn(Map.of(
                        "paymentsPending", 0L,
                        "paymentsAmountThisMonth", java.math.BigDecimal.ZERO,
                        "paymentsAmountThisYear",  java.math.BigDecimal.ZERO,
                        "paymentsAdvanceCount", 0L,
                        "paymentsAdvanceAmount", java.math.BigDecimal.ZERO,
                        "paymentsFailedCount", 0L));

        // Shortfall — sum of (claimed - paid) for adjudicated claims that
        // haven't been fully reimbursed. This is the insurer's outstanding
        // liability across the whole claims book and a key finance KPI.
        Mono<java.math.BigDecimal> shortfallAmount = db.sql(
                "SELECT COALESCE(SUM(claimed_amount - COALESCE(paid_amount, 0)), 0) AS shortfall " +
                "FROM \"" + schema + "\".claims " +
                "WHERE status = 'adjudicated' AND COALESCE(paid_amount, 0) < claimed_amount")
                .map(row -> orZeroBig(row.get("shortfall", java.math.BigDecimal.class)))
                .one()
                .onErrorReturn(java.math.BigDecimal.ZERO);

        // ── Per-currency amount breakdowns ──────────────────────────────────
        // Tenants in multiple currencies (e.g. USD + ZAR) get one entry per
        // active currency code; single-currency tenants get one entry. The
        // existing blended SUM() fields stay populated for backward compat.
        Mono<Map<String, java.math.BigDecimal>> contribAmtMonthByCcy = sumByCurrency(
                schema, "contributions", "status = 'paid' AND created_at >= date_trunc('month', NOW())");
        Mono<Map<String, java.math.BigDecimal>> contribAmtYearByCcy  = sumByCurrency(
                schema, "contributions", "status = 'paid' AND created_at >= date_trunc('year',  NOW())");
        Mono<Map<String, java.math.BigDecimal>> paymentsAmtMonthByCcy = sumByCurrency(
                schema, "payments",     "status = 'completed' AND created_at >= date_trunc('month', NOW())");
        Mono<Map<String, java.math.BigDecimal>> paymentsAmtYearByCcy  = sumByCurrency(
                schema, "payments",     "status = 'completed' AND created_at >= date_trunc('year',  NOW())");

        // New-this-month signals lifted from masca-admin-dashboard:
        //   "New Members" + "New Groups" — both monthly counts.
        Mono<Map<String, Object>> newCounts = db.sql(
                "SELECT " +
                "  (SELECT COUNT(*) FROM \"" + schema + "\".members WHERE created_at >= date_trunc('month', NOW())) AS new_members, " +
                "  (SELECT COUNT(*) FROM \"" + schema + "\".groups  WHERE created_at >= date_trunc('month', NOW())) AS new_groups")
                .map(row -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("newMembersThisMonth", orZero(row.get("new_members", Long.class)));
                    m.put("newGroupsThisMonth",  orZero(row.get("new_groups",  Long.class)));
                    return (Map<String, Object>) m;
                })
                .one()
                .onErrorReturn(Map.of("newMembersThisMonth", 0L, "newGroupsThisMonth", 0L));

        return Mono.zip(staffCounts, memberCounts, claimsCounts, billingCounts)
                .zipWith(Mono.zip(financeCounts, newCounts))
                .zipWith(Mono.zip(contribAmtMonthByCcy, contribAmtYearByCcy,
                                  paymentsAmtMonthByCcy, paymentsAmtYearByCcy))
                .zipWith(shortfallAmount)
                .map(quad -> {
                    var stats = new java.util.LinkedHashMap<String, Object>();
                    var t = quad.getT1();
                    stats.putAll(t.getT1().getT1().getT1());
                    stats.putAll(t.getT1().getT1().getT2());
                    stats.putAll(t.getT1().getT1().getT3());
                    stats.putAll(t.getT1().getT1().getT4());
                    stats.putAll(t.getT1().getT2().getT1());
                    stats.putAll(t.getT1().getT2().getT2());
                    stats.put("contributionsAmountThisMonthByCurrency", t.getT2().getT1());
                    stats.put("contributionsAmountThisYearByCurrency",  t.getT2().getT2());
                    stats.put("paymentsAmountThisMonthByCurrency",      t.getT2().getT3());
                    stats.put("paymentsAmountThisYearByCurrency",       t.getT2().getT4());
                    stats.put("claimsShortfallAmount",                  quad.getT2());
                    return (Map<String, Object>) stats;
                });
    }

    /**
     * SUM(amount) over a tenant-schema table, grouped by {@code currency_code}
     * and filtered by the given WHERE clause. Empty Map on error so callers
     * can render a clean "no data" state without short-circuiting other
     * fields on the dashboard.
     */
    private Mono<Map<String, java.math.BigDecimal>> sumByCurrency(
            String schema, String table, String whereClause) {
        return db.sql(
                "SELECT currency_code, COALESCE(SUM(amount), 0) AS amt " +
                "FROM \"" + schema + "\"." + table + " " +
                "WHERE " + whereClause + " " +
                "GROUP BY currency_code ORDER BY currency_code")
                .map(row -> Map.entry(
                        row.get("currency_code", String.class),
                        orZeroBig(row.get("amt", java.math.BigDecimal.class))))
                .all().collectList()
                .map(entries -> {
                    var out = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
                    for (var e : entries) out.put(e.getKey(), e.getValue());
                    return (Map<String, java.math.BigDecimal>) out;
                })
                .onErrorReturn(Map.of());
    }

    private static java.math.BigDecimal orZeroBig(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    /**
     * 12-month trend data for the operational dashboard charts. Mirrors the
     * legacy line charts: claims-per-month (Masca-Claims-Admin), contributions
     * and payments per month (Masca-Finance-Typescript / MASCA-Frontend).
     *
     * <p>Each series is an array of {@code {name, value}} points (ngx-charts
     * compatible). The trailing 12 months are always present — months with no
     * activity surface as zero via {@code generate_series}.
     */
    @GetMapping("/charts")
    @Operation(summary = "Tenant dashboard chart data",
               description = "Trend series for claims (granularity per ?period=day|week|month), plus 12-month contributions and payments series.")
    public Mono<Map<String, Object>> getCharts(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader,
            @RequestParam(value = "period", defaultValue = "month") String period) {
        if (tenantHeader == null || tenantHeader.isBlank()) {
            return Mono.just(emptyCharts());
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantHeader);
        } catch (IllegalArgumentException e) {
            return Mono.just(emptyCharts());
        }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> buildCharts(tenantId, schema, normalizePeriod(period)))
                .switchIfEmpty(Mono.just(emptyCharts()));
    }

    /** Whitelist period values; default to month so legacy callers keep their shape. */
    private static String normalizePeriod(String raw) {
        if (raw == null) return "month";
        return switch (raw.toLowerCase()) {
            case "day", "week", "month" -> raw.toLowerCase();
            default -> "month";
        };
    }

    private Mono<Map<String, Object>> buildCharts(UUID tenantId, String schema, String period) {

        // The CTE generates the trailing 12 months; LEFT JOINs preserve months
        // with no activity so the chart x-axis is always complete.
        String monthsCte =
                "WITH months AS (" +
                "  SELECT generate_series(" +
                "    date_trunc('month', NOW()) - INTERVAL '11 months', " +
                "    date_trunc('month', NOW()), " +
                "    INTERVAL '1 month') AS bucket" +
                ") ";

        // Claims trend is filterable by ?period=day|week|month — uses a separate
        // CTE that spans 12 buckets of the requested granularity. Contributions
        // and payments remain on the monthly cadence below.
        String periodCte = "WITH buckets AS (" +
                "  SELECT generate_series(" +
                "    date_trunc('" + period + "', NOW()) - INTERVAL '11 " + period + "s', " +
                "    date_trunc('" + period + "', NOW()), " +
                "    INTERVAL '1 " + period + "') AS bucket" +
                ") ";
        String claimsLabel = switch (period) {
            case "day"   -> "FMDD Mon";
            case "week"  -> "\"Wk\" IW";
            default      -> "FMMon";
        };
        Mono<List<Map<String, Object>>> claimsTrend = db.sql(periodCte +
                "SELECT to_char(b.bucket, '" + claimsLabel + "') AS bucket_label, " +
                "       COALESCE(COUNT(c.id), 0)                 AS value " +
                "FROM buckets b " +
                "LEFT JOIN \"" + schema + "\".claims c " +
                "  ON date_trunc('" + period + "', c.created_at) = b.bucket " +
                "GROUP BY b.bucket ORDER BY b.bucket")
                .map(row -> point(row.get("bucket_label", String.class),
                                  row.get("value", Long.class)))
                .all().collectList()
                .onErrorReturn(List.of());

        // Contributions trend also follows ?period — same CTE + label format
        // as the claims trend so the dashboard's chart-period toggle controls
        // both consistently.
        Mono<List<Map<String, Object>>> contributionsTrend = db.sql(periodCte +
                "SELECT to_char(b.bucket, '" + claimsLabel + "') AS bucket_label, " +
                "       COALESCE(SUM(c.amount), 0)               AS value " +
                "FROM buckets b " +
                "LEFT JOIN \"" + schema + "\".contributions c " +
                "  ON date_trunc('" + period + "', c.created_at) = b.bucket AND c.status = 'paid' " +
                "GROUP BY b.bucket ORDER BY b.bucket")
                .map(row -> point(row.get("bucket_label", String.class),
                                  row.get("value", java.math.BigDecimal.class)))
                .all().collectList()
                .onErrorReturn(List.of());

        // Payments trend matches the period CTE so the finance dashboard's
        // chart-period toggle works the same way as claims and contributions.
        Mono<List<Map<String, Object>>> paymentsTrend = db.sql(periodCte +
                "SELECT to_char(b.bucket, '" + claimsLabel + "') AS bucket_label, " +
                "       COALESCE(SUM(p.amount), 0)               AS value " +
                "FROM buckets b " +
                "LEFT JOIN \"" + schema + "\".payments p " +
                "  ON date_trunc('" + period + "', p.created_at) = b.bucket AND p.status = 'completed' " +
                "GROUP BY b.bucket ORDER BY b.bucket")
                .map(row -> point(row.get("bucket_label", String.class),
                                  row.get("value", java.math.BigDecimal.class)))
                .all().collectList()
                .onErrorReturn(List.of());

        // Per-currency variants drive the legacy MASCA dashboards' grouped-bar
        // and multi-line charts. We CROSS JOIN tenant_currency_config so every
        // active currency contributes one full 12-month series — including
        // months that had no activity, which surface as zero. The number of
        // series scales with what the tenant admin has configured: a default
        // tenant (USD only) gets one series, multi-currency tenants get one
        // per code. is_default DESC orders the primary currency first, so the
        // first colour in the legacy palette always lands on the tenant's
        // chosen primary.
        String monthsAndCodesCte =
                "WITH months AS (" +
                "  SELECT generate_series(" +
                "    date_trunc('month', NOW()) - INTERVAL '11 months', " +
                "    date_trunc('month', NOW()), " +
                "    INTERVAL '1 month') AS bucket" +
                "), codes AS (" +
                "  SELECT currency_code, is_default " +
                "  FROM public.tenant_currency_config " +
                "  WHERE tenant_id = :tenantId AND is_active = TRUE" +
                ") ";

        Mono<Map<String, List<Map<String, Object>>>> claimsByCurrency = db.sql(monthsAndCodesCte +
                "SELECT to_char(m.bucket, 'FMMon') AS bucket_label, " +
                "       cc.currency_code            AS code, " +
                "       COALESCE(COUNT(c.id), 0)    AS value " +
                "FROM months m " +
                "CROSS JOIN codes cc " +
                "LEFT JOIN \"" + schema + "\".claims c " +
                "  ON date_trunc('month', c.created_at) = m.bucket " +
                "  AND c.currency_code = cc.currency_code " +
                "GROUP BY m.bucket, cc.currency_code, cc.is_default " +
                "ORDER BY cc.is_default DESC, cc.currency_code, m.bucket")
                .bind("tenantId", tenantId)
                .map(row -> currencyPoint(row.get("code", String.class),
                                           row.get("bucket_label", String.class),
                                           row.get("value", Long.class)))
                .all().collectList()
                .map(TenantStatsController::groupByCurrency)
                .onErrorReturn(Map.of());

        Mono<Map<String, List<Map<String, Object>>>> contributionsByCurrency = db.sql(monthsAndCodesCte +
                "SELECT to_char(m.bucket, 'FMMon') AS bucket_label, " +
                "       cc.currency_code            AS code, " +
                "       COALESCE(SUM(c.amount), 0)  AS value " +
                "FROM months m " +
                "CROSS JOIN codes cc " +
                "LEFT JOIN \"" + schema + "\".contributions c " +
                "  ON date_trunc('month', c.created_at) = m.bucket " +
                "  AND c.status = 'paid' " +
                "  AND c.currency_code = cc.currency_code " +
                "GROUP BY m.bucket, cc.currency_code, cc.is_default " +
                "ORDER BY cc.is_default DESC, cc.currency_code, m.bucket")
                .bind("tenantId", tenantId)
                .map(row -> currencyPoint(row.get("code", String.class),
                                           row.get("bucket_label", String.class),
                                           row.get("value", java.math.BigDecimal.class)))
                .all().collectList()
                .map(TenantStatsController::groupByCurrency)
                .onErrorReturn(Map.of());

        Mono<Map<String, List<Map<String, Object>>>> paymentsByCurrency = db.sql(monthsAndCodesCte +
                "SELECT to_char(m.bucket, 'FMMon') AS bucket_label, " +
                "       cc.currency_code            AS code, " +
                "       COALESCE(SUM(p.amount), 0)  AS value " +
                "FROM months m " +
                "CROSS JOIN codes cc " +
                "LEFT JOIN \"" + schema + "\".payments p " +
                "  ON date_trunc('month', p.created_at) = m.bucket " +
                "  AND p.status = 'completed' " +
                "  AND p.currency_code = cc.currency_code " +
                "GROUP BY m.bucket, cc.currency_code, cc.is_default " +
                "ORDER BY cc.is_default DESC, cc.currency_code, m.bucket")
                .bind("tenantId", tenantId)
                .map(row -> currencyPoint(row.get("code", String.class),
                                           row.get("bucket_label", String.class),
                                           row.get("value", java.math.BigDecimal.class)))
                .all().collectList()
                .map(TenantStatsController::groupByCurrency)
                .onErrorReturn(Map.of());

        return Mono.zip(claimsTrend, contributionsTrend, paymentsTrend)
                .zipWith(Mono.zip(claimsByCurrency, contributionsByCurrency, paymentsByCurrency))
                .map(pair -> {
                    var out = new java.util.LinkedHashMap<String, Object>();
                    out.put("claimsByMonth",                          pair.getT1().getT1());
                    out.put("contributionsAmountByMonth",             pair.getT1().getT2());
                    out.put("paymentsAmountByMonth",                  pair.getT1().getT3());
                    out.put("claimsByMonthByCurrency",                pair.getT2().getT1());
                    out.put("contributionsAmountByMonthByCurrency",   pair.getT2().getT2());
                    out.put("paymentsAmountByMonthByCurrency",        pair.getT2().getT3());
                    return (Map<String, Object>) out;
                });
    }

    /**
     * Claims grouped by status across the entire tenant — drives the pipeline
     * pie chart. Returns counts and the total so the frontend can render
     * percentages without recomputing.
     */
    @GetMapping("/claims-status-distribution")
    @Operation(summary = "Claims grouped by status",
               description = "Per-status counts + total for the pipeline pie chart on the operational dashboard.")
    public Mono<Map<String, Object>> getClaimsStatusDistribution(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(emptyDistribution());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(emptyDistribution()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT status, COUNT(*) AS count " +
                        "FROM \"" + schema + "\".claims " +
                        "GROUP BY status ORDER BY status")
                        .map(row -> Map.of(
                                "status", row.get("status", String.class),
                                "count",  orZero(row.get("count", Long.class))))
                        .all().collectList()
                        .map(rows -> {
                            long total = rows.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
                            var out = new java.util.LinkedHashMap<String, Object>();
                            out.put("total",   total);
                            out.put("buckets", rows);
                            return (Map<String, Object>) out;
                        })
                        .onErrorReturn(emptyDistribution()))
                .switchIfEmpty(Mono.just(emptyDistribution()));
    }

    /**
     * The 10 most recently created claims, ordered by {@code created_at DESC}.
     * Drives the recent-claims table on the operational dashboard.
     */
    @GetMapping("/recent-claims")
    @Operation(summary = "10 most recent claims",
               description = "Recent claims for the operational dashboard table.")
    public Mono<List<Map<String, Object>>> getRecentClaims(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(List.of());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(List.of()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT id, claim_number, status, claimed_amount, currency_code, " +
                        "       service_date, created_at " +
                        "FROM \"" + schema + "\".claims " +
                        "ORDER BY created_at DESC LIMIT 10")
                        .map(row -> {
                            var m = new java.util.LinkedHashMap<String, Object>();
                            m.put("id",             row.get("id",             UUID.class));
                            m.put("claimNumber",    row.get("claim_number",   String.class));
                            m.put("status",         row.get("status",         String.class));
                            m.put("claimedAmount",  row.get("claimed_amount", java.math.BigDecimal.class));
                            m.put("currencyCode",   row.get("currency_code",  String.class));
                            m.put("serviceDate",    row.get("service_date",   java.time.LocalDate.class));
                            m.put("createdAt",      row.get("created_at",     java.time.OffsetDateTime.class));
                            return (Map<String, Object>) m;
                        })
                        .all().collectList()
                        .onErrorReturn(List.of()))
                .switchIfEmpty(Mono.just(List.of()));
    }

    /**
     * Adjudicator workload — count of in-flight ({@code in_adjudication}) claims
     * assigned to each staff member, plus the unassigned tail of submitted/pending
     * claims with no {@code adjudicated_by} owner yet. Cross-schema join: staff
     * users live in {@code public.staff_users} but claims live in the tenant
     * schema, joined by adjudicated_by → staff_users.id.
     */
    @GetMapping("/adjudicator-workload")
    @Operation(summary = "Adjudicator workload",
               description = "Unassigned pending count + per-adjudicator in-progress counts.")
    public Mono<Map<String, Object>> getAdjudicatorWorkload(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(emptyWorkload());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(emptyWorkload()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> {
                    Mono<Long> unassigned = db.sql(
                            "SELECT COUNT(*) AS n FROM \"" + schema + "\".claims " +
                            "WHERE status IN ('submitted', 'pending') AND adjudicated_by IS NULL")
                            .map(row -> orZero(row.get("n", Long.class)))
                            .one()
                            .onErrorReturn(0L);

                    Mono<List<Map<String, Object>>> perAdjudicator = db.sql(
                            "SELECT s.id, s.first_name, s.last_name, s.email, " +
                            "       COUNT(c.id) AS count " +
                            "FROM public.staff_users s " +
                            "LEFT JOIN \"" + schema + "\".claims c " +
                            "       ON c.adjudicated_by = s.id " +
                            "      AND c.status = 'in_adjudication' " +
                            "WHERE s.tenant_id = :tenantId " +
                            "GROUP BY s.id, s.first_name, s.last_name, s.email " +
                            "HAVING COUNT(c.id) > 0 " +
                            "ORDER BY COUNT(c.id) DESC")
                            .bind("tenantId", tenantId)
                            .map(row -> {
                                var m = new java.util.LinkedHashMap<String, Object>();
                                m.put("id",    row.get("id", UUID.class));
                                m.put("name",  String.format("%s %s",
                                        nullSafe(row.get("first_name", String.class)),
                                        nullSafe(row.get("last_name",  String.class))).trim());
                                m.put("email", nullSafe(row.get("email", String.class)));
                                m.put("count", orZero(row.get("count", Long.class)));
                                return (Map<String, Object>) m;
                            })
                            .all().collectList()
                            .onErrorReturn(List.of());

                    return Mono.zip(unassigned, perAdjudicator)
                            .map(t -> {
                                var out = new java.util.LinkedHashMap<String, Object>();
                                out.put("unassigned",   t.getT1());
                                out.put("adjudicators", t.getT2());
                                return (Map<String, Object>) out;
                            });
                })
                .switchIfEmpty(Mono.just(emptyWorkload()));
    }

    /** Contributions grouped by status (paid / pending / failed / ...) — pipeline pie for the Billing tab. */
    @GetMapping("/contributions-status-distribution")
    @Operation(summary = "Contributions grouped by status",
               description = "Per-status counts + total for the billing pipeline pie chart.")
    public Mono<Map<String, Object>> getContributionsStatusDistribution(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(emptyDistribution());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(emptyDistribution()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT status, COUNT(*) AS count " +
                        "FROM \"" + schema + "\".contributions " +
                        "GROUP BY status ORDER BY status")
                        .map(row -> Map.of(
                                "status", row.get("status", String.class),
                                "count",  orZero(row.get("count", Long.class))))
                        .all().collectList()
                        .map(rows -> {
                            long total = rows.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
                            var out = new java.util.LinkedHashMap<String, Object>();
                            out.put("total",   total);
                            out.put("buckets", rows);
                            return (Map<String, Object>) out;
                        })
                        .onErrorReturn(emptyDistribution()))
                .switchIfEmpty(Mono.just(emptyDistribution()));
    }

    /** 10 most recent contributions for the Billing tab table. */
    @GetMapping("/recent-contributions")
    @Operation(summary = "10 most recent contributions",
               description = "Recent contributions for the operational dashboard table.")
    public Mono<List<Map<String, Object>>> getRecentContributions(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(List.of());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(List.of()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT c.id, c.amount, c.currency_code, c.status, c.payment_method, " +
                        "       c.period_start, c.period_end, c.created_at, " +
                        "       m.member_number, m.first_name, m.last_name " +
                        "FROM \"" + schema + "\".contributions c " +
                        "LEFT JOIN \"" + schema + "\".members m ON m.id = c.member_id " +
                        "ORDER BY c.created_at DESC LIMIT 10")
                        .map(row -> {
                            var m = new java.util.LinkedHashMap<String, Object>();
                            m.put("id",             row.get("id",             UUID.class));
                            m.put("amount",         row.get("amount",         java.math.BigDecimal.class));
                            m.put("currencyCode",   row.get("currency_code",  String.class));
                            m.put("status",         row.get("status",         String.class));
                            m.put("paymentMethod",  nullSafe(row.get("payment_method", String.class)));
                            m.put("periodStart",    row.get("period_start",   java.time.LocalDate.class));
                            m.put("periodEnd",      row.get("period_end",     java.time.LocalDate.class));
                            m.put("createdAt",      row.get("created_at",     java.time.OffsetDateTime.class));
                            m.put("memberNumber",   nullSafe(row.get("member_number", String.class)));
                            m.put("memberName",     String.format("%s %s",
                                    nullSafe(row.get("first_name", String.class)),
                                    nullSafe(row.get("last_name",  String.class))).trim());
                            return (Map<String, Object>) m;
                        })
                        .all().collectList()
                        .onErrorReturn(List.of()))
                .switchIfEmpty(Mono.just(List.of()));
    }

    /** Top 10 members by outstanding (pending) contribution amount — side card on Billing tab. */
    @GetMapping("/top-debtors")
    @Operation(summary = "Top debtors by outstanding contributions",
               description = "Members with the largest unpaid contribution totals.")
    public Mono<List<Map<String, Object>>> getTopDebtors(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(List.of());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(List.of()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT m.id, m.member_number, m.first_name, m.last_name, " +
                        "       COALESCE(SUM(c.amount), 0) AS outstanding, " +
                        "       COUNT(c.id)                AS count " +
                        "FROM \"" + schema + "\".members m " +
                        "JOIN \"" + schema + "\".contributions c " +
                        "  ON c.member_id = m.id AND c.status = 'pending' " +
                        "GROUP BY m.id, m.member_number, m.first_name, m.last_name " +
                        "ORDER BY outstanding DESC LIMIT 10")
                        .map(row -> {
                            var m = new java.util.LinkedHashMap<String, Object>();
                            m.put("id",            row.get("id",            UUID.class));
                            m.put("memberNumber",  nullSafe(row.get("member_number", String.class)));
                            m.put("name",          String.format("%s %s",
                                    nullSafe(row.get("first_name", String.class)),
                                    nullSafe(row.get("last_name",  String.class))).trim());
                            m.put("outstanding",   row.get("outstanding",   java.math.BigDecimal.class));
                            m.put("count",         orZero(row.get("count",  Long.class)));
                            return (Map<String, Object>) m;
                        })
                        .all().collectList()
                        .onErrorReturn(List.of()))
                .switchIfEmpty(Mono.just(List.of()));
    }

    /** Payments grouped by status — pipeline pie chart for the Finance tab. */
    @GetMapping("/payments-status-distribution")
    @Operation(summary = "Payments grouped by status",
               description = "Per-status counts + total for the finance pipeline pie chart.")
    public Mono<Map<String, Object>> getPaymentsStatusDistribution(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(emptyDistribution());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(emptyDistribution()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT status, COUNT(*) AS count " +
                        "FROM \"" + schema + "\".payments " +
                        "GROUP BY status ORDER BY status")
                        .map(row -> Map.of(
                                "status", row.get("status", String.class),
                                "count",  orZero(row.get("count", Long.class))))
                        .all().collectList()
                        .map(rows -> {
                            long total = rows.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
                            var out = new java.util.LinkedHashMap<String, Object>();
                            out.put("total",   total);
                            out.put("buckets", rows);
                            return (Map<String, Object>) out;
                        })
                        .onErrorReturn(emptyDistribution()))
                .switchIfEmpty(Mono.just(emptyDistribution()));
    }

    /** 10 most recent payments for the Finance tab table. */
    @GetMapping("/recent-payments")
    @Operation(summary = "10 most recent payments",
               description = "Recent payments for the operational dashboard table.")
    public Mono<List<Map<String, Object>>> getRecentPayments(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(List.of());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(List.of()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT p.id, p.payment_number, p.amount, p.currency_code, " +
                        "       p.payment_type, p.payment_method, p.status, p.reference, " +
                        "       p.paid_at, p.created_at, " +
                        "       pr.name AS provider_name " +
                        "FROM \"" + schema + "\".payments p " +
                        "LEFT JOIN \"" + schema + "\".providers pr ON pr.id = p.provider_id " +
                        "ORDER BY p.created_at DESC LIMIT 10")
                        .map(row -> {
                            var m = new java.util.LinkedHashMap<String, Object>();
                            m.put("id",             row.get("id",             UUID.class));
                            m.put("paymentNumber",  nullSafe(row.get("payment_number", String.class)));
                            m.put("amount",         row.get("amount",         java.math.BigDecimal.class));
                            m.put("currencyCode",   row.get("currency_code",  String.class));
                            m.put("paymentType",    nullSafe(row.get("payment_type",   String.class)));
                            m.put("paymentMethod",  nullSafe(row.get("payment_method", String.class)));
                            m.put("status",         row.get("status",         String.class));
                            m.put("reference",      nullSafe(row.get("reference",      String.class)));
                            m.put("paidAt",         row.get("paid_at",        java.time.OffsetDateTime.class));
                            m.put("createdAt",      row.get("created_at",     java.time.OffsetDateTime.class));
                            m.put("providerName",   nullSafe(row.get("provider_name",  String.class)));
                            return (Map<String, Object>) m;
                        })
                        .all().collectList()
                        .onErrorReturn(List.of()))
                .switchIfEmpty(Mono.just(List.of()));
    }

    /** Top 10 payees (providers receiving the most money). */
    @GetMapping("/top-payees")
    @Operation(summary = "Top 10 payees by total received",
               description = "Providers ranked by total completed-payment amount.")
    public Mono<List<Map<String, Object>>> getTopPayees(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(List.of());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(List.of()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT pr.id, pr.name, " +
                        "       COALESCE(SUM(p.amount), 0) AS received, " +
                        "       COUNT(p.id)                AS count " +
                        "FROM \"" + schema + "\".providers pr " +
                        "JOIN \"" + schema + "\".payments p " +
                        "  ON p.provider_id = pr.id AND p.status = 'completed' " +
                        "GROUP BY pr.id, pr.name " +
                        "ORDER BY received DESC LIMIT 10")
                        .map(row -> {
                            var m = new java.util.LinkedHashMap<String, Object>();
                            m.put("id",       row.get("id",       UUID.class));
                            m.put("name",     nullSafe(row.get("name", String.class)));
                            m.put("received", row.get("received", java.math.BigDecimal.class));
                            m.put("count",    orZero(row.get("count", Long.class)));
                            return (Map<String, Object>) m;
                        })
                        .all().collectList()
                        .onErrorReturn(List.of()))
                .switchIfEmpty(Mono.just(List.of()));
    }

    /** Payment volume grouped by payment_method (bank transfer / EFT / mobile money / etc). */
    @GetMapping("/payment-method-distribution")
    @Operation(summary = "Payments grouped by method",
               description = "Per-method counts + total for the finance method breakdown chart.")
    public Mono<Map<String, Object>> getPaymentMethodDistribution(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) return Mono.just(emptyDistribution());
        UUID tenantId;
        try { tenantId = UUID.fromString(tenantHeader); }
        catch (IllegalArgumentException e) { return Mono.just(emptyDistribution()); }

        return resolveTenantSchema(tenantId)
                .flatMap(schema -> db.sql(
                        "SELECT COALESCE(payment_method, 'unknown') AS method, " +
                        "       COUNT(*) AS count, " +
                        "       COALESCE(SUM(amount), 0) AS amount " +
                        "FROM \"" + schema + "\".payments " +
                        "WHERE status = 'completed' " +
                        "GROUP BY payment_method ORDER BY amount DESC")
                        .map(row -> Map.of(
                                "method", row.get("method", String.class),
                                "count",  orZero(row.get("count", Long.class)),
                                "amount", orZeroBig(row.get("amount", java.math.BigDecimal.class))))
                        .all().collectList()
                        .map(rows -> {
                            long total = rows.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
                            var out = new java.util.LinkedHashMap<String, Object>();
                            out.put("total",   total);
                            out.put("buckets", rows);
                            return (Map<String, Object>) out;
                        })
                        .onErrorReturn(emptyDistribution()))
                .switchIfEmpty(Mono.just(emptyDistribution()));
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static Map<String, Object> emptyDistribution() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("total", 0L);
        m.put("buckets", List.of());
        return m;
    }

    private static Map<String, Object> emptyWorkload() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("unassigned", 0L);
        m.put("adjudicators", List.of());
        return m;
    }

    /**
     * Carries a {@code (currencyCode, name, value)} triple from the
     * per-currency trend queries through to {@link #groupByCurrency}, where
     * we collapse the flat row stream into the
     * {@code Map<currency, [{ name, value }]>} shape the frontend chart
     * components expect.
     */
    private static Map<String, Object> currencyPoint(String code, String name, Number value) {
        return Map.of(
                "_code", code == null ? "" : code,
                "name",  name == null ? "" : name,
                "value", value == null ? 0 : value
        );
    }

    /**
     * Group flat per-currency rows into a {@code Map<currency, points[]>}
     * preserving the order the SQL handed us (is_default first, then
     * currency code), so the chart legend renders the primary currency
     * first.
     */
    private static Map<String, List<Map<String, Object>>> groupByCurrency(List<Map<String, Object>> rows) {
        var out = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
        for (var row : rows) {
            String code = (String) row.get("_code");
            if (code == null || code.isBlank()) continue;
            var bucket = out.computeIfAbsent(code, k -> new java.util.ArrayList<>());
            bucket.add(Map.of("name", row.get("name"), "value", row.get("value")));
        }
        return out;
    }

    private static Map<String, Object> point(String name, Number value) {
        return Map.of(
                "name",  name == null ? "" : name,
                "value", value == null ? 0 : value
        );
    }

    private static Map<String, Object> emptyCharts() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("claimsByMonth",                          List.of());
        m.put("contributionsAmountByMonth",             List.of());
        m.put("paymentsAmountByMonth",                  List.of());
        m.put("claimsByMonthByCurrency",                Map.of());
        m.put("contributionsAmountByMonthByCurrency",   Map.of());
        m.put("paymentsAmountByMonthByCurrency",        Map.of());
        return m;
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }

    private static Map<String, Object> emptyStats() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("totalStaff", 0L); m.put("activeStaff", 0L);
        m.put("suspendedStaff", 0L); m.put("pendingStaff", 0L);
        m.put("totalMembers", 0L); m.put("activeMembers", 0L); m.put("enrolledMembers", 0L);
        m.put("claimsNewTasks", 0L); m.put("claimsThisMonth", 0L);
        m.put("claimsAcceptedThisMonth", 0L); m.put("claimsRejectedThisMonth", 0L);
        m.put("schemesActive", 0L); m.put("contributionsPending", 0L);
        m.put("contributionsAmountThisMonth", java.math.BigDecimal.ZERO);
        m.put("contributionsAmountThisYear",  java.math.BigDecimal.ZERO);
        m.put("paymentsPending", 0L);
        m.put("paymentsAmountThisMonth", java.math.BigDecimal.ZERO);
        m.put("paymentsAmountThisYear",  java.math.BigDecimal.ZERO);
        m.put("paymentsAdvanceCount", 0L);
        m.put("paymentsAdvanceAmount", java.math.BigDecimal.ZERO);
        m.put("paymentsFailedCount", 0L);
        m.put("claimsShortfallAmount", java.math.BigDecimal.ZERO);
        m.put("contributionsAmountThisMonthByCurrency", Map.of());
        m.put("contributionsAmountThisYearByCurrency",  Map.of());
        m.put("paymentsAmountThisMonthByCurrency",      Map.of());
        m.put("paymentsAmountThisYearByCurrency",       Map.of());
        m.put("newMembersThisMonth", 0L); m.put("newGroupsThisMonth", 0L);
        return m;
    }
}
