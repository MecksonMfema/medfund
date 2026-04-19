package com.medfund.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@Tag(name = "Platform Stats", description = "Aggregate platform-wide user statistics — no tenant context required")
public class PlatformStatsController {

    private final DatabaseClient db;

    @GetMapping("/member-growth")
    @Operation(summary = "Monthly member growth across all tenants for the last 12 months")
    public Mono<List<Map<String, Object>>> getMemberGrowth() {
        String[] months = {"Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"};

        // Count members created in each calendar month of the current year across all tenant schemas.
        return db.sql(
                        "SELECT schema_name FROM information_schema.schemata " +
                        "WHERE schema_name LIKE 'tenant_%' ORDER BY schema_name")
                .map(row -> row.get("schema_name", String.class))
                .all()
                .flatMap(schema -> db
                        .sql("SELECT TO_CHAR(created_at, 'Mon') AS month, COUNT(*) AS cnt " +
                             "FROM \"" + schema + "\".members " +
                             "WHERE created_at >= NOW() - INTERVAL '12 months' " +
                             "GROUP BY month")
                        .map(row -> Map.entry(
                                row.get("month", String.class),
                                row.get("cnt", Long.class)))
                        .all()
                        .onErrorResume(e -> reactor.core.publisher.Flux.empty()))
                .collectMultimap(Map.Entry::getKey, Map.Entry::getValue)
                .map(multimap -> {
                    return java.util.Arrays.stream(months)
                            .map(m -> {
                                long total = multimap.getOrDefault(m, java.util.List.of())
                                        .stream().mapToLong(v -> v == null ? 0L : v).sum();
                                Map<String, Object> entry = new java.util.LinkedHashMap<>();
                                entry.put("month", m);
                                entry.put("count", total);
                                return entry;
                            })
                            .collect(java.util.stream.Collectors.toList());
                });
    }

    @GetMapping("/user-stats")
    @Operation(summary = "Platform-wide user counts")
    public Mono<Map<String, Long>> getUserStats() {

        // Exact count of active platform staff users (public schema — always fast)
        Mono<Long> staffCount = db
                .sql("SELECT COUNT(*) FROM public.staff_users WHERE status = 'active'")
                .map(row -> row.get(0, Long.class))
                .one()
                .onErrorReturn(0L);

        // Exact member count across every tenant schema.
        // We first fetch all tenant schema names, then issue one COUNT(*) per schema.
        // reltuples from pg_class is avoided because it returns -1 on unanalysed tables.
        Mono<Long> memberCount = db
                .sql("SELECT schema_name FROM information_schema.schemata " +
                     "WHERE schema_name LIKE 'tenant_%' ORDER BY schema_name")
                .map(row -> row.get("schema_name", String.class))
                .all()
                .flatMap(schema -> db
                        .sql("SELECT COUNT(*) FROM \"" + schema + "\".members")
                        .map(row -> row.get(0, Long.class))
                        .one()
                        .onErrorReturn(0L))
                .reduce(0L, Long::sum)
                .onErrorReturn(0L);

        return Mono.zip(staffCount, memberCount)
                .map(t -> Map.of(
                        "staffUsers",    t.getT1(),
                        "tenantMembers", t.getT2(),
                        "totalUsers",    t.getT1() + t.getT2()
                ));
    }
}
