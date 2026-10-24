package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.tenancy.dto.ModuleEntitlementResponse;
import com.medfund.tenancy.dto.UpdateModuleEntitlementRequest;
import com.medfund.tenancy.service.TenantModuleEntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Super-admin management of per-tenant module entitlements (V187) — the
 * sellable-horizontal seam. Routed behind {@code RequireSuperAdmin} at the
 * gateway (explicit proxy lines registered before the {@code /api/v1/platform}
 * group). A toggle upserts the row, audits it, broadcasts a Kafka invalidation,
 * and emits a security event (G5).
 */
@RestController
@RequestMapping("/api/v1/platform/module-entitlements")
@RequiredArgsConstructor
@Tag(name = "Platform Module Entitlements",
     description = "Per-tenant sellable-module entitlements (KYC and future horizontals)")
@SecurityRequirement(name = "bearer-jwt")
public class PlatformModuleEntitlementController {

    private final TenantModuleEntitlementService svc;

    @GetMapping
    @Operation(summary = "List module entitlements",
            description = "All entitlement rows, or just one tenant's when tenantId is supplied. "
                        + "A module with no row is NOT entitled (default off).")
    @ApiResponse(responseCode = "200", description = "Entitlement rows returned")
    public Flux<ModuleEntitlementResponse> list(
            @Parameter(description = "Optional tenant filter")
            @RequestParam(required = false) UUID tenantId) {
        return svc.list(tenantId);
    }

    @PutMapping("/{tenantId}/{moduleKey}")
    @Operation(summary = "Enable or disable a module for a tenant",
            description = "Upserts the (tenant, module) entitlement. Emits an audit event, a Kafka "
                        + "broadcast on platform.module-entitlements.v1, and an ENTITLEMENT_CHANGED "
                        + "security event.")
    @ApiResponse(responseCode = "200", description = "Entitlement set")
    @ApiResponse(responseCode = "400", description = "Unknown module key")
    public Mono<ModuleEntitlementResponse> set(@PathVariable UUID tenantId,
                                               @PathVariable String moduleKey,
                                               @Valid @RequestBody UpdateModuleEntitlementRequest req,
                                               @AuthenticationPrincipal Jwt jwt) {
        return svc.setEntitlement(tenantId, moduleKey, req.enabled(),
                AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
