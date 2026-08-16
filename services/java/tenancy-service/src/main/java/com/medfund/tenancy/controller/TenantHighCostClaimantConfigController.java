package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.TenantHighCostClaimantConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantHighCostClaimantConfigRequest;
import com.medfund.tenancy.service.TenantHighCostClaimantConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Per-tenant high-cost claimant threshold. The HIGH_COST_CLAIMANT report
 * (claims-service, Phase 4) flags any member whose cumulative PAID claims
 * across the report window clear this threshold. Absent row = report renders
 * empty with a "threshold not configured" warning (G46).
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/high-cost-claimant-config")
@RequiredArgsConstructor
@Tag(name = "Tenant High Cost Claimant Config",
     description = "Per-tenant threshold above which a member's cumulative paid claims flag "
                   + "them as a high-cost claimant on the HIGH_COST_CLAIMANT report.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantHighCostClaimantConfigController {

    private final TenantHighCostClaimantConfigService service;

    @GetMapping
    @RequiresPermission({"admin:manage_settings", "finance:view"})
    @Operation(summary = "Fetch the tenant's high-cost claimant threshold config",
            description = "Returns nulls (unconfigured) when no row exists.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config (or unconfigured nulls) returned")
    })
    public Mono<TenantHighCostClaimantConfigResponse> get(@PathVariable UUID tenantId) {
        return service.get(tenantId);
    }

    @PutMapping
    @RequiresPermission({"admin:manage_settings"})
    @Operation(summary = "Upsert the tenant's high-cost claimant threshold",
            description = "Creates the row if absent, otherwise updates. Emits an audit event "
                        + "naming the tenant slug.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config saved"),
            @ApiResponse(responseCode = "400", description = "Invalid payload (missing threshold, negative value, non-ISO currency)")
    })
    public Mono<TenantHighCostClaimantConfigResponse> update(@PathVariable UUID tenantId,
                                                             @Valid @RequestBody UpdateTenantHighCostClaimantConfigRequest body,
                                                             @AuthenticationPrincipal Jwt jwt) {
        return service.upsert(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
