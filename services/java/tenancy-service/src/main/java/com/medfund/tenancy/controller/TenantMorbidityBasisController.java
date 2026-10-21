package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantMorbidityBasisRequest;
import com.medfund.tenancy.dto.TenantMorbidityBasisResponse;
import com.medfund.tenancy.dto.UpdateTenantMorbidityBasisRequest;
import com.medfund.tenancy.service.TenantMorbidityBasisService;
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
 * Per-tenant morbidity basis (MORBIDITY_STUDY). Multi-row CRUD — one row
 * per (insurance_line, effective_from). Chosen basisName is loaded from the
 * ai-service YAML catalogue (Phase 6) and scaled by morbidityMultiplier.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/morbidity-basis")
@RequiredArgsConstructor
@Tag(name = "Tenant Morbidity Basis",
     description = "Per-tenant morbidity/incidence reference-table selections consumed by the actuarial MORBIDITY_STUDY.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantMorbidityBasisController {

    private final TenantMorbidityBasisService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_actuarial_bases", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List morbidity basis rows for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantMorbidityBasisResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(TenantMorbidityBasisResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_actuarial_bases"})
    @Operation(summary = "Add a morbidity basis row")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate row for (line, effectiveFrom)")
    })
    public Mono<TenantMorbidityBasisResponse> add(@PathVariable UUID tenantId,
                                                  @Valid @RequestBody AddTenantMorbidityBasisRequest body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantMorbidityBasisResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_actuarial_bases"})
    @Operation(summary = "Update a morbidity basis row",
            description = "Only basisName, morbidityMultiplier and effectiveTo are mutable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantMorbidityBasisResponse> update(@PathVariable UUID tenantId,
                                                     @PathVariable UUID id,
                                                     @Valid @RequestBody UpdateTenantMorbidityBasisRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantMorbidityBasisResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_actuarial_bases"})
    @Operation(summary = "Delete a morbidity basis row")
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
