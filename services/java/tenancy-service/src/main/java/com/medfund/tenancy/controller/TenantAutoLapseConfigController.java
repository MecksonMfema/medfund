package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.TenantAutoLapseConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantAutoLapseConfigRequest;
import com.medfund.tenancy.service.TenantAutoLapseConfigService;
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
 * Per-tenant auto-lapse configuration (V133). Wires the arrears pipeline
 * that flips members to LAPSED status after a tenant-configured window
 * of unbroken arrears. See {@code Phase 11 §B / P7 chain} in the
 * financial-reporting-suite plan for the full pipeline.
 *
 * <p>Absent row = auto-lapse disabled. When {@code enabled=true} the
 * {@code arrearsThresholdMonths} + {@code graceWindowDays} pair drive
 * the breach → grace → transition timing.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/auto-lapse-config")
@RequiredArgsConstructor
@Tag(name = "Tenant Auto-Lapse Config",
     description = "Per-tenant configuration for the automatic member-lapse pipeline. Absent = disabled.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantAutoLapseConfigController {

    private final TenantAutoLapseConfigService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_auto_lapse", "admin:manage_settings", "finance:view"})
    @Operation(summary = "Fetch the tenant's auto-lapse config",
            description = "Returns enabled=false + nulls when unconfigured.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config (or unconfigured defaults) returned")
    })
    public Mono<TenantAutoLapseConfigResponse> get(@PathVariable UUID tenantId) {
        return service.get(tenantId);
    }

    @PutMapping
    @RequiresPermission({"tenant.settings:manage_auto_lapse"})
    @Operation(summary = "Upsert the tenant's auto-lapse config",
            description = "Creates the row if absent, otherwise updates. Emits an audit event "
                        + "naming the tenant slug. Threshold + grace are required when enabled=true.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config saved"),
            @ApiResponse(responseCode = "400", description = "Invalid payload (out-of-range values, or enabled=true without threshold/grace)")
    })
    public Mono<TenantAutoLapseConfigResponse> update(@PathVariable UUID tenantId,
                                                       @Valid @RequestBody UpdateTenantAutoLapseConfigRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.upsert(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
