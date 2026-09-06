package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantExpenseAssumptionRequest;
import com.medfund.tenancy.dto.TenantExpenseAssumptionResponse;
import com.medfund.tenancy.dto.UpdateTenantExpenseAssumptionRequest;
import com.medfund.tenancy.service.TenantExpenseAssumptionService;
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
 * Per-tenant, per-line, per-expense-type unit costs for IFRS 17 GMM
 * fulfilment cash-flow projection (Phase 15 §2 / I5 + I23). Multi-row CRUD
 * mirroring {@link TenantPersistencyBasisController}; every row carries
 * its own currency + effective_from window.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ifrs17-expense-assumptions")
@RequiredArgsConstructor
@Tag(name = "Tenant IFRS 17 Expense Assumptions",
     description = "Per-tenant, per-line, per-expense-type unit costs consumed by IFRS 17 GMM cash-flow projection.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantExpenseAssumptionController {

    private final TenantExpenseAssumptionService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_ifrs17_config", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List expense assumption rows for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantExpenseAssumptionResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(TenantExpenseAssumptionResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Add an expense assumption row",
            description = "Uniqueness enforced on (insurance_line, expense_type, currency, "
                        + "effective_from). expense_type must be one of ACQUISITION, MAINTENANCE, "
                        + "CLAIMS_HANDLING, OVERHEAD or OTHER.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate row for the same key")
    })
    public Mono<TenantExpenseAssumptionResponse> add(@PathVariable UUID tenantId,
                                                     @Valid @RequestBody AddTenantExpenseAssumptionRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantExpenseAssumptionResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Update an expense assumption row",
            description = "Only amountPerPolicy, sourceNote and effectiveTo are mutable. The "
                        + "(insurance_line, expense_type, currency, effective_from) tuple is "
                        + "immutable - a shift is a new row rather than an edit.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantExpenseAssumptionResponse> update(@PathVariable UUID tenantId,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody UpdateTenantExpenseAssumptionRequest body,
                                                        @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantExpenseAssumptionResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Delete an expense assumption row")
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
