package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.TenantSidebarSectionConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantSidebarSectionConfigRequest;
import com.medfund.tenancy.service.TenantSidebarSectionConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/sidebar-section-config")
@RequiredArgsConstructor
@Tag(name = "Tenant Sidebar Visibility",
     description = "Per-tenant on/off toggle catalogue for every togglable item in the operations-portal " +
                   "sidebar. Items default to enabled - an entry with enabled=false hides the item from " +
                   "the sidebar for every user in the tenant. Overview / Dashboard is always visible " +
                   "(no key), so a tenant admin can never lock themselves out of the portal.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantSidebarSectionConfigController {

    private final TenantSidebarSectionConfigService service;

    @GetMapping
    @RequiresPermission({"admin:manage_settings"})
    @Operation(summary = "List every catalogued sidebar section merged with the tenant's persisted overrides",
            description = "Returns one entry per known SidebarSectionKey. Rows the admin has never touched " +
                          "come back with id=null and enabled=true. Sorted by nav group then label.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full catalogue returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks admin:manage_settings")
    })
    public Flux<TenantSidebarSectionConfigResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId);
    }

    @PutMapping
    @RequiresPermission({"admin:manage_settings"})
    @Operation(summary = "Bulk-upsert on/off toggles for one or more sidebar sections",
            description = "Entries the payload doesn't mention are left untouched. Unknown section keys " +
                          "are rejected with 400 - the shared SidebarSectionKey enum is the source of truth.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upsert applied, updated rows returned"),
            @ApiResponse(responseCode = "400", description = "Payload contains an unknown sidebar section key"),
            @ApiResponse(responseCode = "403", description = "Caller lacks admin:manage_settings")
    })
    public Flux<TenantSidebarSectionConfigResponse> bulkUpsert(
            @PathVariable UUID tenantId,
            @Valid @RequestBody UpdateTenantSidebarSectionConfigRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.bulkUpsert(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @GetMapping("/enabled/{sectionKey}")
    @Operation(summary = "Point lookup for cross-service enablement check",
            description = "Returns true when no row exists (default enabled). Cheap enough for the " +
                          "sidebar hydration path to call on every tenant switch without caching.")
    @ApiResponse(responseCode = "200", description = "Enablement flag returned")
    public Mono<Boolean> isEnabled(@PathVariable UUID tenantId,
                                   @Parameter(description = "Sidebar section catalogue key")
                                   @PathVariable String sectionKey) {
        return service.isEnabled(tenantId, sectionKey);
    }
}
