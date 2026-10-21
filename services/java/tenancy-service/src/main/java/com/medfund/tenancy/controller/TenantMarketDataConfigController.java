package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantMarketDataConfigRequest;
import com.medfund.tenancy.dto.TenantMarketDataConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantMarketDataConfigRequest;
import com.medfund.tenancy.service.TenantMarketDataConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Per-tenant enrolment for the Phase 24 market-data-service yield-curve
 * auto-fetch (Phase 15 §24 / I12 + I16). CRUD mirroring
 * {@link TenantYieldCurveController} plus a service-wide {@code /enabled}
 * list endpoint that the Go daemon polls each fetch tick.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ifrs17-market-data-config")
@RequiredArgsConstructor
@Tag(name = "Tenant IFRS 17 Market Data Config",
     description = "Per-tenant opt-in for automated yield-curve fetches (RBZ / SARB).")
@SecurityRequirement(name = "bearer-jwt")
public class TenantMarketDataConfigController {

    private final TenantMarketDataConfigService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_ifrs17_config", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List market-data auto-fetch enrolments for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantMarketDataConfigResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(TenantMarketDataConfigResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Enrol a currency for auto-fetch",
            description = "source must be RBZ_AUTO or SARB_AUTO. Uniqueness on (tenant, currency); "
                        + "a duplicate surfaces as 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Enrolled"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate (tenant, currency)")
    })
    public Mono<TenantMarketDataConfigResponse> add(
            @PathVariable UUID tenantId,
            @Valid @RequestBody AddTenantMarketDataConfigRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantMarketDataConfigResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Update a market-data enrolment",
            description = "Only source and autoFetchEnabled are mutable - the (tenant, currency) "
                        + "tuple is immutable, a currency swap is add + delete.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantMarketDataConfigResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantMarketDataConfigRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantMarketDataConfigResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Delete a market-data enrolment")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Row deleted"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID tenantId,
                                             @PathVariable UUID id,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(tenantId, id, AuditActor.id(jwt), AuditActor.email(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }
}
