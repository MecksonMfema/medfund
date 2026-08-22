package com.medfund.claims.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@Tag(name = "Platform Stats", description = "Aggregate platform-wide claims statistics — no tenant context required")
public class PlatformStatsController {

    private final DatabaseClient db;

    @GetMapping("/claims-distribution")
    @Operation(summary = "Platform-wide claims count grouped by status across all tenant schemas")
    public Mono<List<Map<String, Object>>> getClaimsDistribution() {
        String[] statuses = {"submitted", "approved", "rejected", "in_adjudication", "pending"};
        Map<String, String> colors = Map.of(
                "approved",        "#2EC4B6",
                "pending",         "#FF9F1C",
                "rejected",        "#E71D36",
                "in_adjudication", "#00B4D8",
                "submitted",       "#90E0EF"
        );

        return db.sql(
                        "SELECT schema_name FROM information_schema.schemata " +
                        "WHERE schema_name LIKE 'tenant_%' ORDER BY schema_name")
                .map(row -> row.get("schema_name", String.class))
                .all()
                .flatMap(schema -> db
                        .sql("SELECT status, COUNT(*) AS cnt FROM \"" + schema + "\".claims GROUP BY status")
                        .map(row -> Map.entry(
                                row.get("status", String.class),
                                row.get("cnt", Long.class)))
                        .all()
                        .onErrorResume(e -> reactor.core.publisher.Flux.empty()))
                .collectMultimap(Map.Entry::getKey, Map.Entry::getValue)
                .map(multimap -> java.util.Arrays.stream(statuses)
                        .map(s -> {
                            long total = multimap.getOrDefault(s, java.util.List.of())
                                    .stream().mapToLong(v -> v == null ? 0L : v).sum();
                            return Map.<String, Object>of(
                                    "status", s,
                                    "count",  total,
                                    "color",  colors.getOrDefault(s, "#cccccc"));
                        })
                        .collect(java.util.stream.Collectors.toList()));
    }

    @GetMapping("/claims-stats")
    @Operation(summary = "Platform-wide claims count")
    public Mono<Map<String, Long>> getClaimsStats() {

        // Iterate every tenant schema and sum exact COUNT(*) values.
        // pg_class.reltuples is intentionally avoided — it is -1 for tables
        // that have never been ANALYZEd, which is common on a fresh install.
        return db
                .sql("SELECT schema_name FROM information_schema.schemata " +
                     "WHERE schema_name LIKE 'tenant_%' ORDER BY schema_name")
                .map(row -> row.get("schema_name", String.class))
                .all()
                .flatMap(schema -> db
                        .sql("SELECT COUNT(*) FROM \"" + schema + "\".claims")
                        .map(row -> row.get(0, Long.class))
                        .one()
                        .onErrorReturn(0L))
                .reduce(0L, Long::sum)
                .onErrorReturn(0L)
                .map(count -> Map.of("totalClaims", count));
    }

    /**
     * Raw per-claim {@code created_at} timestamps across every tenant schema
     * for the platform analytics {@code claims-over-time} chart. Each row is
     * one claim; the gateway buckets and counts on the way out (Phase 9 D9-5).
     * Optional {@code periodStart}/{@code periodEnd} narrow the SQL scan.
     */
    @GetMapping("/claims-over-time")
    @Operation(summary = "Raw claim creation timestamps for cross-tenant claims-over-time chart")
    public Flux<Map<String, Object>> getClaimsOverTime(
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd) {

        return db.sql(
                        "SELECT schema_name FROM information_schema.schemata " +
                        "WHERE schema_name LIKE 'tenant_%' ORDER BY schema_name")
                .map(row -> row.get("schema_name", String.class))
                .all()
                .flatMap(schema -> {
                    StringBuilder sql = new StringBuilder(
                            "SELECT created_at FROM \"" + schema + "\".claims " +
                            "WHERE created_at IS NOT NULL");
                    if (periodStart != null) {
                        sql.append(" AND created_at >= :periodStart");
                    }
                    if (periodEnd != null) {
                        sql.append(" AND created_at < (:periodEnd::date + INTERVAL '1 day')");
                    }
                    var spec = db.sql(sql.toString());
                    if (periodStart != null) spec = spec.bind("periodStart", periodStart);
                    if (periodEnd != null)   spec = spec.bind("periodEnd", periodEnd);
                    return spec
                            .map(row -> {
                                Instant ts = row.get("created_at", Instant.class);
                                Map<String, Object> out = new LinkedHashMap<>();
                                out.put("ts", ts != null ? ts.toString() : null);
                                return out;
                            })
                            .all()
                            .onErrorResume(e -> {
                                log.debug("[platform-analytics] claims-over-time failed for {}: {}",
                                        schema, e.getMessage());
                                return Flux.empty();
                            });
                });
    }
}
