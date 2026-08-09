package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.TenantCtcAutoConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantCtcAutoConfigRequest;
import com.medfund.tenancy.service.TenantCtcAutoConfigService;
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

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ctc-auto-config")
@RequiredArgsConstructor
@Tag(name = "Tenant CTC Auto Config",
     description = "Per-tenant Claims-to-Contributions auto-draft configuration. " +
                   "When enabled, finance-service auto-drafts a CTC row whenever a MEMBER-payee " +
                   "claim adjudicates and the member's outstanding contribution balance clears the " +
                   "threshold. Never auto-commits — operator review stays mandatory.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantCtcAutoConfigController {

    private final TenantCtcAutoConfigService service;

    @GetMapping
    @RequiresPermission({"finance:configure_auto_ctc", "finance:manage_ctc_payments"})
    @Operation(summary = "Fetch the tenant's auto-CTC config",
            description = "Returns the platform default (disabled) when no explicit row exists.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config (or platform default) returned")
    })
    public Mono<TenantCtcAutoConfigResponse> get(@PathVariable UUID tenantId) {
        return service.get(tenantId);
    }

    @PutMapping
    @RequiresPermission({"finance:configure_auto_ctc"})
    @Operation(summary = "Upsert the tenant's auto-CTC config",
            description = "Creates the row if absent, otherwise updates. Emits an audit event.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config saved"),
            @ApiResponse(responseCode = "400", description = "Invalid payload (missing enabled, negative threshold, non-ISO currency)")
    })
    public Mono<TenantCtcAutoConfigResponse> update(@PathVariable UUID tenantId,
                                                    @Valid @RequestBody UpdateTenantCtcAutoConfigRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return service.upsert(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
