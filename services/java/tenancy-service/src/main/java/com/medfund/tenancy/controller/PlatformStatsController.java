package com.medfund.tenancy.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

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
}
