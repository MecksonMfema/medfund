package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.TenantEndorsementConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantEndorsementConfigRequest;
import com.medfund.tenancy.service.TenantEndorsementConfigService;
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
 * Per-tenant endorsement four-eyes configuration (V134). Wires the DRAFT
 * gating threshold: absent / disabled → user-service auto-commits every
 * endorsement; enabled + threshold → premium_delta ≥ threshold parks at
 * DRAFT for supervisor approval.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/endorsement-config")
@RequiredArgsConstructor
@Tag(name = "Tenant Endorsement Config",
     description = "Per-tenant configuration for the policy-endorsement four-eyes gate. Absent = disabled.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantEndorsementConfigController {

    private final TenantEndorsementConfigService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_endorsement_config", "admin:manage_settings", "finance:view"})
    @Operation(summary = "Fetch the tenant's endorsement config",
            description = "Returns enabled=false + nulls when unconfigured.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config (or unconfigured defaults) returned")
    })
    public Mono<TenantEndorsementConfigResponse> get(@PathVariable UUID tenantId) {
        return service.get(tenantId);
    }

    @PutMapping
    @RequiresPermission({"tenant.settings:manage_endorsement_config"})
    @Operation(summary = "Upsert the tenant's endorsement config",
            description = "Creates the row if absent, otherwise updates. Emits an audit event "
                        + "naming the tenant slug. Threshold + currency are required when enabled=true.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config saved"),
            @ApiResponse(responseCode = "400", description = "Invalid payload (missing threshold/currency when enabled=true, or unpaired amount/currency)")
    })
    public Mono<TenantEndorsementConfigResponse> update(@PathVariable UUID tenantId,
                                                       @Valid @RequestBody UpdateTenantEndorsementConfigRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.upsert(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
