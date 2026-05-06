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

        return Mono.zip(staffCounts, memberCounts).map(t -> {
            var stats = new java.util.LinkedHashMap<String, Object>();
            stats.putAll(t.getT1());
            stats.putAll(t.getT2());
            return (Map<String, Object>) stats;
        });
    }

    private static long orZero(Long v) {
        return v == null ? 0L : v;
    }

    private static Map<String, Object> emptyStats() {
        return Map.of(
                "totalStaff", 0L, "activeStaff", 0L, "suspendedStaff", 0L, "pendingStaff", 0L,
                "totalMembers", 0L, "activeMembers", 0L, "enrolledMembers", 0L
        );
    }
}
