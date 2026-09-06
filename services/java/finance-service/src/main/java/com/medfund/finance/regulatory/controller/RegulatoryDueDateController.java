package com.medfund.finance.regulatory.controller;

import com.medfund.finance.regulatory.dto.DueDateBannerResponse;
import com.medfund.finance.regulatory.service.RegulatoryDueDateService;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Read-only endpoint that feeds the Angular reports-hub due-date banner
 * (Phase 6). Returns one row per Phase-16 regulator report the tenant is
 * eligible to file, together with the currently-due filing period, its
 * due date, days-until-due and current submission status.
 */
@RestController
@RequestMapping("/api/v1/reports/regulatory/due-dates")
@RequiredArgsConstructor
@Tag(name = "Regulatory Due Dates",
     description = "Reports-hub banner rows - one per applicable Phase-16 regulator report.")
@SecurityRequirement(name = "bearer-jwt")
public class RegulatoryDueDateController {

    private final RegulatoryDueDateService service;

    @GetMapping
    @RequiresPermission({"finance:view"})
    @Operation(summary = "List due-date banner rows for the calling tenant",
            description = "For every Phase-16 report applicable to the tenant's jurisdiction "
                        + "or country, resolves the most recent completed filing period, looks "
                        + "up the last submission for that slot, and computes days-until-due "
                        + "plus a severity (INFO / AMBER / RED) for banner styling.")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Banner rows returned")})
    public Flux<DueDateBannerResponse> list() {
        return currentTenantId().flatMapMany(service::bannerRowsFor);
    }

    private static Mono<UUID> currentTenantId() {
        return Mono.deferContextual(ctx -> {
            String raw = TenantContext.get(ctx);
            if (raw == null || raw.isBlank()) {
                return Mono.error(new IllegalStateException("No tenant in context"));
            }
            try {
                return Mono.just(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                return Mono.error(new IllegalArgumentException("Invalid tenant id in context: " + raw));
            }
        });
    }
}
