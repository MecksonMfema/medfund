package com.medfund.tenancy.controller;

import com.medfund.tenancy.service.TenantModuleEntitlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Internal read-only entitlement snapshot for the Go gateway, which has no
 * R2DBC access to {@code public.tenant_module_entitlements} and therefore
 * cannot use the shared Java {@code TenantEntitlementRegistry}. Polled once at
 * startup to seed the gateway's in-process cache; from then on
 * {@code platform.module-entitlements.v1} keeps it current.
 *
 * <p>Path-level auth is relaxed to {@code permitAll} via {@code /internal/**}
 * in {@code SecurityConfig}, matching {@code InternalFeatureFlagController} —
 * this is a service-to-service call on the internal network, not routed through
 * the API gateway.
 */
@RestController
@RequestMapping("/internal/v1/module-entitlements")
@RequiredArgsConstructor
@Tag(name = "Internal Module Entitlements",
     description = "Startup entitlement snapshot for the Go gateway. Internal-only.")
public class InternalModuleEntitlementController {

    private final TenantModuleEntitlementService svc;

    @GetMapping
    @Operation(summary = "List every per-tenant module entitlement and its enabled state",
            description = "Polled once at startup by the gateway to seed its in-process entitlement "
                        + "cache. Live changes arrive on the platform.module-entitlements.v1 topic.")
    @ApiResponse(responseCode = "200", description = "Entitlements returned")
    public Flux<InternalEntitlement> list() {
        return svc.list(null)
                .map(r -> new InternalEntitlement(r.tenantId().toString(), r.moduleKey(), r.enabled()));
    }

    /** Deliberately narrower than the admin DTO: the gateway only needs the key triple. */
    public record InternalEntitlement(String tenantId, String moduleKey, boolean enabled) {}
}
