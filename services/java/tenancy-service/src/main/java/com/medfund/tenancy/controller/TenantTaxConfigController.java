package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantTaxConfigRequest;
import com.medfund.tenancy.dto.TenantTaxConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantTaxConfigRequest;
import com.medfund.tenancy.service.TenantTaxConfigService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Admin CRUD for {@code public.tenant_tax_config} — per-tenant statutory
 * tax rates (VAT + Withholding) consumed by the VAT Return + TaxWithheldReturn
 * shapers (Phase 20 / 21). Not gated by {@code @RequiresCountry} —
 * tax rates exist for every country, and the applicability of any
 * specific tax return is enforced downstream on the report controllers.
 *
 * <p>Only tenant.settings admins with {@code manage_tax_config} can
 * mutate; the {@code list} endpoint additionally admits
 * {@code finance:view} + {@code admin:manage_settings} so the report
 * shaper and platform admin surfaces can read without holding the
 * write-scope permission.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/tax-config")
@RequiredArgsConstructor
@Tag(name = "Tenant Tax Config",
     description = "Per-tenant statutory tax rates (VAT + Withholding) per transaction category × currency. Consumed by the VAT Return + TaxWithheldReturn shapers (Phase 16 §C).")
@SecurityRequirement(name = "bearer-jwt")
public class TenantTaxConfigController {

    private final TenantTaxConfigService service;

    @GetMapping
    @RequiresPermission({
            Permissions.TENANT_SETTINGS_MANAGE_TAX_CONFIG,
            Permissions.ADMIN_MANAGE_SETTINGS,
            Permissions.FINANCE_VIEW})
    @Operation(summary = "List tax config rows for a tenant",
            description = "Filter by tax_type=VAT or tax_type=WITHHOLDING to narrow the list; "
                        + "omit the filter to return every row across both tax types.")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantTaxConfigResponse> list(@PathVariable UUID tenantId,
                                              @RequestParam(name = "taxType", required = false) String taxType) {
        return service.listByType(tenantId, taxType).map(TenantTaxConfigResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({Permissions.TENANT_SETTINGS_MANAGE_TAX_CONFIG})
    @Operation(summary = "Add a tax config row",
            description = "country_code must be a 2-letter ISO 3166-1 code; tax_type is VAT or "
                        + "WITHHOLDING; transaction_category is one of PREMIUM | CLAIM_PAID | "
                        + "ADMIN_FEE | COMMISSION | OTHER; currency is 3-letter ISO 4217; rate is "
                        + "a decimal in [0, 1). Uniqueness enforced on (tenant_id, tax_type, "
                        + "transaction_category, currency, effective_from) — conflicting inserts "
                        + "surface as HTTP 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate (tenant_id, tax_type, transaction_category, currency, effective_from)")
    })
    public Mono<TenantTaxConfigResponse> add(@PathVariable UUID tenantId,
                                             @Valid @RequestBody AddTenantTaxConfigRequest body,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantTaxConfigResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({Permissions.TENANT_SETTINGS_MANAGE_TAX_CONFIG})
    @Operation(summary = "Update a tax config row",
            description = "Fields left null on the request are unchanged. country_code, tax_type, "
                        + "transaction_category, currency and effective_from are immutable — a "
                        + "change to any of those requires adding a new row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantTaxConfigResponse> update(@PathVariable UUID tenantId,
                                                @PathVariable UUID id,
                                                @Valid @RequestBody UpdateTenantTaxConfigRequest body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantTaxConfigResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({Permissions.TENANT_SETTINGS_MANAGE_TAX_CONFIG})
    @Operation(summary = "Delete a tax config row")
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
