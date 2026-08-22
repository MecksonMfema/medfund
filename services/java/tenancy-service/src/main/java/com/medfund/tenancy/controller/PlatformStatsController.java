package com.medfund.tenancy.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@Tag(name = "Platform Stats", description = "Aggregate platform-wide tenancy statistics — no tenant context required")
public class PlatformStatsController {

    private final DatabaseClient db;

    @GetMapping("/tenant-count")
    @Operation(summary = "Total tenant count", description = "Returns the exact number of tenants in the public schema.")
    public Mono<Map<String, Long>> getTenantCount() {
        return db
                .sql("SELECT COUNT(*) FROM public.tenants")
                .map(row -> row.get(0, Long.class))
                .one()
                .onErrorReturn(0L)
                .map(count -> Map.of("totalTenants", count));
    }

    /**
     * Raw {@code created_at} timestamps from {@code public.tenants}, one row
     * per tenant. Gateway buckets these into the requested period on the way
     * out (Phase 9 D9-8). Replaces the paginated {@code /api/v1/tenants?size=1000}
     * feed that could truncate past 1000 tenants.
     */
    @GetMapping("/tenant-growth")
    @Operation(summary = "Raw tenant creation timestamps for the platform analytics tenant-growth chart")
    public Flux<Map<String, Object>> getTenantGrowth() {
        return db
                .sql("SELECT created_at FROM public.tenants WHERE created_at IS NOT NULL")
                .map(row -> {
                    Instant ts = row.get("created_at", Instant.class);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ts", ts != null ? ts.toString() : null);
                    return out;
                })
                .all()
                .onErrorResume(err -> {
                    log.warn("[platform-analytics] tenant-growth query failed: {}", err.getMessage());
                    return Flux.empty();
                });
    }
}
