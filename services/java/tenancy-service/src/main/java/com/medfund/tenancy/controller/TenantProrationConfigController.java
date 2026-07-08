package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.tenancy.dto.TenantProrationConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantProrationConfigRequest;
import com.medfund.tenancy.service.TenantProrationConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/proration-config")
@RequiredArgsConstructor
@Tag(name = "Tenant proration config",
     description = "Per-tenant benefit-limit proration strategy on scheme change. " +
                   "Consumed by claims-service ProrationService in adjudication stage 3. " +
                   "Seven baked-in strategies + agenda-gated rule override.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantProrationConfigController {

    private final TenantProrationConfigService service;

    @GetMapping
    @Operation(summary = "Fetch the tenant's current proration config",
            description = "Returns the platform default (defaultStrategy=NONE, all overrides null) " +
                          "when no explicit row exists — safe for a fresh tenant.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config (or platform default) returned")
    })
    public Mono<TenantProrationConfigResponse> get(@PathVariable UUID tenantId) {
        return service.get(tenantId);
    }

    @PutMapping
    @Operation(summary = "Upsert the tenant's proration config",
            description = "Creates the row if absent, otherwise updates. Emits an audit event. " +
                          "Strategy names are validated against the CHECK constraint on the table.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config saved"),
            @ApiResponse(responseCode = "400", description = "Invalid strategy name or wait-days value")
    })
    public Mono<TenantProrationConfigResponse> update(@PathVariable UUID tenantId,
                                                     @Valid @RequestBody UpdateTenantProrationConfigRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.upsert(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
