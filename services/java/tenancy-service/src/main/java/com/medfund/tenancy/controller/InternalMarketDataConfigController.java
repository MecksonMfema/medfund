package com.medfund.tenancy.controller;

import com.medfund.tenancy.dto.TenantMarketDataConfigResponse;
import com.medfund.tenancy.service.TenantMarketDataConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Internal read-only endpoint the Phase 24 Go market-data-service polls
 * once per fetch tick to build its per-tenant schedule (Phase 15 §24 /
 * I12 + I16). Cross-tenant by design — the daemon needs every enabled
 * (tenant, currency, source) tuple to iterate its adapters over.
 *
 * <p>Path-level auth is intentionally relaxed to {@code permitAll} in
 * {@link com.medfund.tenancy.config.SecurityConfig} because this is a
 * service-to-service call on the internal network — the same pattern
 * the notification-service uses to poll IFRS 17 notification configs.
 * A future service-mesh identity story would replace the network
 * boundary with per-service tokens; that wiring is out of scope for
 * this phase.
 */
@RestController
@RequestMapping("/internal/v1/market-data-config")
@RequiredArgsConstructor
@Tag(name = "Internal Market Data Config",
     description = "Cross-tenant read path for the Go market-data-service (Phase 24). Internal-only.")
public class InternalMarketDataConfigController {

    private final TenantMarketDataConfigService service;

    @GetMapping("/enabled")
    @Operation(summary = "List every auto-fetch-enabled market-data config across all tenants",
            description = "Called by market-data-service on each fetch tick. Ordered by tenantId, "
                        + "then currency, so the daemon can page consistently.")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantMarketDataConfigResponse> listEnabled() {
        return service.listAllEnabled().map(TenantMarketDataConfigResponse::from);
    }
}
