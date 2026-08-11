package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.TenantReportConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantReportConfigRequest;
import com.medfund.tenancy.service.TenantReportConfigService;
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
@RequestMapping("/api/v1/tenants/{tenantId}/report-config")
@RequiredArgsConstructor
@Tag(name = "Tenant Report Config",
     description = "Per-tenant on/off toggle catalogue for every report the platform ships. " +
                   "Reports default to enabled — an entry with enabled=false hides the report from " +
                   "the operational hub, sidebar, and returns 403 from every @RequiresReport endpoint.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantReportConfigController {

    private final TenantReportConfigService service;

    @GetMapping
    @RequiresPermission({"admin:manage_settings", "finance:view"})
    @Operation(summary = "List every catalogued report merged with the tenant's persisted overrides",
            description = "Returns one entry per known ReportKey. Rows the admin has never touched " +
                          "come back with id=null and enabled=true. Sorted by report family then label.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full catalogue returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks admin:manage_settings")
    })
    public Flux<TenantReportConfigResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId);
    }

    @PutMapping
    @RequiresPermission({"admin:manage_settings"})
    @Operation(summary = "Bulk-upsert on/off toggles for one or more reports",
            description = "Entries the payload doesn't mention are left untouched. Unknown report keys " +
                          "are rejected with 400 — the shared ReportKey enum is the source of truth.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Upsert applied, updated rows returned"),
            @ApiResponse(responseCode = "400", description = "Payload contains an unknown report key"),
            @ApiResponse(responseCode = "403", description = "Caller lacks admin:manage_settings")
    })
    public Flux<TenantReportConfigResponse> bulkUpsert(@PathVariable UUID tenantId,
                                                       @Valid @RequestBody UpdateTenantReportConfigRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.bulkUpsert(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @GetMapping("/enabled/{reportKey}")
    @Operation(summary = "Point lookup for cross-service enablement check",
            description = "Returns true when no row exists (default enabled). Cheap enough for the " +
                          "@RequiresReport aspect to call on every gated request without caching.")
    @ApiResponse(responseCode = "200", description = "Enablement flag returned")
    public Mono<Boolean> isEnabled(@PathVariable UUID tenantId,
                                    @Parameter(description = "Report catalogue key")
                                    @PathVariable String reportKey) {
        return service.isEnabled(tenantId, reportKey);
    }
}
