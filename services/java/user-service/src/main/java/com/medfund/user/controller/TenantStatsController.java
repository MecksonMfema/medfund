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
        // but since /api/v1/tenant-stats is in the platform bypass list we resolve the schema
        // directly using the tenant UUID).
        String schema = "tenant_" + tenantId.toString().replace("-", "_");
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
                "  COALESCE(SUM(amount) FILTER (WHERE status = 'completed' AND created_at >= date_trunc('year',  NOW())), 0) AS amt_year " +
                "FROM \"" + schema + "\".payments")
                .map(row -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("paymentsPending",              orZero(row.get("payments_pending", Long.class)));
                    m.put("paymentsAmountThisMonth",      orZeroBig(row.get("amt_month",     java.math.BigDecimal.class)));
                    m.put("paymentsAmountThisYear",       orZeroBig(row.get("amt_year",      java.math.BigDecimal.class)));
                    return (Map<String, Object>) m;
                })
                .one()
                .onErrorReturn(Map.of(
                        "paymentsPending", 0L,
                        "paymentsAmountThisMonth", java.math.BigDecimal.ZERO,
                        "paymentsAmountThisYear",  java.math.BigDecimal.ZERO));

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
                .map(pair -> {
                    var stats = new java.util.LinkedHashMap<String, Object>();
                    stats.putAll(pair.getT1().getT1());
                    stats.putAll(pair.getT1().getT2());
                    stats.putAll(pair.getT1().getT3());
                    stats.putAll(pair.getT1().getT4());
                    stats.putAll(pair.getT2().getT1());
                    stats.putAll(pair.getT2().getT2());
                    return (Map<String, Object>) stats;
                });
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
               description = "12-month trend series for claims, contributions, and payments — drives the operational dashboard line charts.")
    public Mono<Map<String, Object>> getCharts(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantHeader) {
        if (tenantHeader == null || tenantHeader.isBlank()) {
            return Mono.just(emptyCharts());
        }
        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantHeader);
        } catch (IllegalArgumentException e) {
            return Mono.just(emptyCharts());
        }
        String schema = "tenant_" + tenantId.toString().replace("-", "_");

        // The CTE generates the trailing 12 months; LEFT JOINs preserve months
        // with no activity so the chart x-axis is always complete.
        String monthsCte =
                "WITH months AS (" +
                "  SELECT generate_series(" +
                "    date_trunc('month', NOW()) - INTERVAL '11 months', " +
                "    date_trunc('month', NOW()), " +
                "    INTERVAL '1 month') AS bucket" +
                ") ";

        Mono<List<Map<String, Object>>> claimsTrend = db.sql(monthsCte +
                "SELECT to_char(m.bucket, 'YYYY-MM') AS bucket_label, " +
                "       COALESCE(COUNT(c.id), 0)     AS value " +
                "FROM months m " +
                "LEFT JOIN \"" + schema + "\".claims c " +
                "  ON date_trunc('month', c.created_at) = m.bucket " +
                "GROUP BY m.bucket ORDER BY m.bucket")
                .map(row -> point(row.get("bucket_label", String.class),
                                  row.get("value", Long.class)))
                .all().collectList()
                .onErrorReturn(List.of());

        Mono<List<Map<String, Object>>> contributionsTrend = db.sql(monthsCte +
                "SELECT to_char(m.bucket, 'YYYY-MM') AS bucket_label, " +
                "       COALESCE(SUM(c.amount), 0)   AS value " +
                "FROM months m " +
                "LEFT JOIN \"" + schema + "\".contributions c " +
                "  ON date_trunc('month', c.created_at) = m.bucket AND c.status = 'paid' " +
                "GROUP BY m.bucket ORDER BY m.bucket")
                .map(row -> point(row.get("bucket_label", String.class),
                                  row.get("value", java.math.BigDecimal.class)))
                .all().collectList()
                .onErrorReturn(List.of());

        Mono<List<Map<String, Object>>> paymentsTrend = db.sql(monthsCte +
                "SELECT to_char(m.bucket, 'YYYY-MM') AS bucket_label, " +
                "       COALESCE(SUM(p.amount), 0)   AS value " +
                "FROM months m " +
                "LEFT JOIN \"" + schema + "\".payments p " +
                "  ON date_trunc('month', p.created_at) = m.bucket AND p.status = 'completed' " +
                "GROUP BY m.bucket ORDER BY m.bucket")
                .map(row -> point(row.get("bucket_label", String.class),
                                  row.get("value", java.math.BigDecimal.class)))
                .all().collectList()
                .onErrorReturn(List.of());

        return Mono.zip(claimsTrend, contributionsTrend, paymentsTrend)
                .map(t -> Map.of(
                        "claimsByMonth",              (Object) t.getT1(),
                        "contributionsAmountByMonth", (Object) t.getT2(),
                        "paymentsAmountByMonth",      (Object) t.getT3()
                ));
    }

    private static Map<String, Object> point(String name, Number value) {
        return Map.of(
                "name",  name == null ? "" : name,
                "value", value == null ? 0 : value
        );
    }

    private static Map<String, Object> emptyCharts() {
        return Map.of(
                "claimsByMonth",              List.of(),
                "contributionsAmountByMonth", List.of(),
                "paymentsAmountByMonth",      List.of()
        );
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
        m.put("newMembersThisMonth", 0L); m.put("newGroupsThisMonth", 0L);
        return m;
    }
}
