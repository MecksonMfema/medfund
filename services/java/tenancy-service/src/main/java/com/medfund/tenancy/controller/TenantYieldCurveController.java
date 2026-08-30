package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantYieldCurveRequest;
import com.medfund.tenancy.dto.TenantYieldCurveResponse;
import com.medfund.tenancy.dto.UpdateTenantYieldCurveRequest;
import com.medfund.tenancy.service.TenantYieldCurveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Per-tenant yield curve points for IFRS 17 discounting (Phase 15 §2 /
 * I5 + I12). Multi-row CRUD mirroring {@link TenantPersistencyBasisController}
 * plus a CSV upload endpoint for bulk curve import — each row is inserted
 * independently so per-row failures surface with the offending payload
 * rather than aborting the whole file.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ifrs17-yield-curves")
@RequiredArgsConstructor
@Tag(name = "Tenant IFRS 17 Yield Curves",
     description = "Per-tenant yield curve points consumed by IFRS 17 GMM/VFA discounting.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantYieldCurveController {

    private final TenantYieldCurveService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_ifrs17_config", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List yield curve rows for a tenant",
            description = "Optionally filter by currency (ISO 4217 code). Rows are ordered by "
                        + "currency, then effective_from DESC, then tenor.")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantYieldCurveResponse> list(@PathVariable UUID tenantId,
                                               @Parameter(description = "Optional ISO 4217 currency filter")
                                               @RequestParam(required = false) String currency) {
        var stream = (currency == null || currency.isBlank())
                ? service.list(tenantId)
                : service.listForCurrency(tenantId, currency);
        return stream.map(TenantYieldCurveResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Add a yield curve row (single tenor point)",
            description = "Uniqueness enforced on (currency, tenor_months, effective_from). "
                        + "For bulk upload use the /csv-upload endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate (currency, tenor, effective_from)")
    })
    public Mono<TenantYieldCurveResponse> add(@PathVariable UUID tenantId,
                                              @Valid @RequestBody AddTenantYieldCurveRequest body,
                                              @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantYieldCurveResponse::from);
    }

    @PostMapping("/csv-upload")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Bulk-add yield curve rows",
            description = "Each row is inserted in its own audit envelope; a per-row error does not "
                        + "abort accepted rows. The gateway/Angular layer parses CSV → this list.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "All accepted rows persisted"),
            @ApiResponse(responseCode = "400", description = "One or more rows invalid")
    })
    public Flux<TenantYieldCurveResponse> bulkAdd(@PathVariable UUID tenantId,
                                                  @Valid @RequestBody List<AddTenantYieldCurveRequest> rows,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return service.addAll(tenantId, rows, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantYieldCurveResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Update a yield curve row",
            description = "Only spotRate and effectiveTo are mutable; the (currency, tenor_months, "
                        + "effective_from) tuple is immutable — a change to any of those is a new row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantYieldCurveResponse> update(@PathVariable UUID tenantId,
                                                 @PathVariable UUID id,
                                                 @Valid @RequestBody UpdateTenantYieldCurveRequest body,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantYieldCurveResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Delete a yield curve row")
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
