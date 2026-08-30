package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantRaConfigRequest;
import com.medfund.tenancy.dto.TenantRaConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantRaConfigRequest;
import com.medfund.tenancy.service.TenantRaConfigService;
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
 * Per-portfolio IFRS 17 Risk Adjustment methodology (Phase 15 §2 / I6).
 * Multi-row CRUD mirroring {@link TenantPersistencyBasisController}: list /
 * add / update / delete keyed on (portfolio_id, effective_from). Each row
 * fixes one methodology (CoC or CI) and its numeric parameter for a
 * portfolio's reporting period.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ifrs17-ra-config")
@RequiredArgsConstructor
@Tag(name = "Tenant IFRS 17 RA Config",
     description = "Per-portfolio Risk Adjustment methodology (CoC or CI) consumed by the IFRS 17 compute pipeline.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantRaConfigController {

    private final TenantRaConfigService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_ifrs17_config", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List IFRS 17 RA config rows for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantRaConfigResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(TenantRaConfigResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Add an RA config row",
            description = "methodology must be COC or CI. When COC, cocRate is required and "
                        + "targetConfidenceLevel must be null; the reverse applies for CI. "
                        + "Uniqueness enforced on (portfolio_id, effective_from) — a conflicting "
                        + "insert surfaces as HTTP 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate (portfolio_id, effective_from)")
    })
    public Mono<TenantRaConfigResponse> add(@PathVariable UUID tenantId,
                                            @Valid @RequestBody AddTenantRaConfigRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantRaConfigResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Update an RA config row",
            description = "Only the numeric parameter matching the row's methodology, "
                        + "sourceNote and effectiveTo are mutable. The (portfolio_id, methodology, "
                        + "effective_from) tuple is immutable — a switch of methodology is a new row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantRaConfigResponse> update(@PathVariable UUID tenantId,
                                               @PathVariable UUID id,
                                               @Valid @RequestBody UpdateTenantRaConfigRequest body,
                                               @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantRaConfigResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Delete an RA config row")
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
