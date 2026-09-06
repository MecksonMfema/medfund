package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresCountry;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddUsTenantNaicConfigRequest;
import com.medfund.tenancy.dto.UpdateUsTenantNaicConfigRequest;
import com.medfund.tenancy.dto.UsTenantNaicConfigResponse;
import com.medfund.tenancy.service.UsTenantNaicConfigService;
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
 * Admin CRUD for {@code public.us_tenant_naic_config} — per-tenant US
 * NAIC company identity used by the Schedule P / F shapers. Gated by
 * {@link RequiresCountry} to {@code US} so non-US tenants can't see or
 * mutate their (nonexistent) NAIC identity; the aspect emits 403 on
 * mismatch matching the sibling NAIC report controllers (Phase 12 / 13).
 *
 * <p>Only tenant.settings admins with {@code manage_naic_config} can
 * mutate; the {@code list} endpoint additionally admits
 * {@code finance:view} + {@code admin:manage_settings} so the report
 * shaper and platform admin surfaces can read without holding the
 * write-scope permission.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/us-naic-config")
@RequiredArgsConstructor
@Tag(name = "Tenant US NAIC Config",
     description = "Per-tenant NAIC company identity (state of domicile, company code, group code, FEIN) consumed by the NAIC Schedule P/F shapers. US-only surface.")
@SecurityRequirement(name = "bearer-jwt")
public class UsTenantNaicConfigController {

    private final UsTenantNaicConfigService service;

    @GetMapping
    @RequiresCountry({"US"})
    @RequiresPermission({
            Permissions.TENANT_SETTINGS_MANAGE_NAIC_CONFIG,
            Permissions.ADMIN_MANAGE_SETTINGS,
            Permissions.FINANCE_VIEW})
    @Operation(summary = "List US NAIC config rows for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    @ApiResponse(responseCode = "403", description = "Tenant is not US")
    public Flux<UsTenantNaicConfigResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(UsTenantNaicConfigResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresCountry({"US"})
    @RequiresPermission({Permissions.TENANT_SETTINGS_MANAGE_NAIC_CONFIG})
    @Operation(summary = "Add a US NAIC config row",
            description = "state_domicile must be a 2-letter US subdivision code; naic_company_code "
                        + "and naic_group_code must be 1-10 digits (group is optional); fein is 9 "
                        + "digits optionally hyphenated. Uniqueness enforced on (tenant_id, "
                        + "effective_from) - conflicting inserts surface as HTTP 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "403", description = "Tenant is not US"),
            @ApiResponse(responseCode = "409", description = "Duplicate (tenant_id, effective_from)")
    })
    public Mono<UsTenantNaicConfigResponse> add(@PathVariable UUID tenantId,
                                                @Valid @RequestBody AddUsTenantNaicConfigRequest body,
                                                @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(UsTenantNaicConfigResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresCountry({"US"})
    @RequiresPermission({Permissions.TENANT_SETTINGS_MANAGE_NAIC_CONFIG})
    @Operation(summary = "Update a US NAIC config row",
            description = "Fields left null on the request are unchanged. effective_from is "
                        + "immutable - a change to the effective date requires adding a new row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "403", description = "Tenant is not US"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<UsTenantNaicConfigResponse> update(@PathVariable UUID tenantId,
                                                    @PathVariable UUID id,
                                                    @Valid @RequestBody UpdateUsTenantNaicConfigRequest body,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(UsTenantNaicConfigResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresCountry({"US"})
    @RequiresPermission({Permissions.TENANT_SETTINGS_MANAGE_NAIC_CONFIG})
    @Operation(summary = "Delete a US NAIC config row")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Row deleted"),
            @ApiResponse(responseCode = "403", description = "Tenant is not US"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID tenantId,
                                             @PathVariable UUID id,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(tenantId, id, AuditActor.id(jwt), AuditActor.email(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }
}
