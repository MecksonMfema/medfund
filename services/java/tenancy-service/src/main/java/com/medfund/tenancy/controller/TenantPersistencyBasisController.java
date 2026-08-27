package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantPersistencyBasisRequest;
import com.medfund.tenancy.dto.TenantPersistencyBasisResponse;
import com.medfund.tenancy.dto.UpdateTenantPersistencyBasisRequest;
import com.medfund.tenancy.service.TenantPersistencyBasisService;
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
 * Per-tenant expected retention curves (PERSISTENCY_STUDY). Multi-row CRUD
 * modeled on {@link TenantCurrencyController} — one row per (insurance_line,
 * cohort_months, effective_from). Seeded with industry-default curves by
 * V136 so an admin sees a working default before any manual edits.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/persistency-basis")
@RequiredArgsConstructor
@Tag(name = "Tenant Persistency Basis",
     description = "Per-tenant expected-retention curves consumed by the actuarial PERSISTENCY_STUDY.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantPersistencyBasisController {

    private final TenantPersistencyBasisService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_actuarial_bases", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List persistency basis rows for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantPersistencyBasisResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(TenantPersistencyBasisResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_actuarial_bases"})
    @Operation(summary = "Add a persistency basis row",
            description = "Uniqueness enforced on (insurance_line, cohort_months, effective_from). "
                        + "effective_from defaults to today when omitted.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate row for the same key")
    })
    public Mono<TenantPersistencyBasisResponse> add(@PathVariable UUID tenantId,
                                                    @Valid @RequestBody AddTenantPersistencyBasisRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantPersistencyBasisResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_actuarial_bases"})
    @Operation(summary = "Update a persistency basis row",
            description = "Only expectedRetentionPct, sourceNote and effectiveTo are mutable — "
                        + "the (line, cohort, effectiveFrom) key is immutable.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantPersistencyBasisResponse> update(@PathVariable UUID tenantId,
                                                       @PathVariable UUID id,
                                                       @Valid @RequestBody UpdateTenantPersistencyBasisRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantPersistencyBasisResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_actuarial_bases"})
    @Operation(summary = "Delete a persistency basis row")
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
