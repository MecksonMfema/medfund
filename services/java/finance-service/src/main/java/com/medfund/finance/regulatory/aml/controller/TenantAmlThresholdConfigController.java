package com.medfund.finance.regulatory.aml.controller;

import com.medfund.finance.regulatory.aml.dto.AddTenantAmlThresholdConfigRequest;
import com.medfund.finance.regulatory.aml.dto.TenantAmlThresholdConfigResponse;
import com.medfund.finance.regulatory.aml.dto.UpdateTenantAmlThresholdConfigRequest;
import com.medfund.finance.regulatory.aml.service.TenantAmlThresholdConfigService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
 * Admin CRUD REST surface for AML reporting thresholds (Phase 25 REG8).
 *
 * <p>Not country-gated — every jurisdiction has some form of AML
 * reporting threshold, and refusing US/other tenants would push them
 * into ad-hoc side channels. The list endpoint is permissive
 * ({@code compliance:aml_review}) so triage staff can see the threshold
 * they're evaluating against; mutations require the dedicated
 * {@code compliance:aml_configure_thresholds} permission.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/aml-threshold-config")
@RequiredArgsConstructor
@Tag(name = "Regulatory — AML thresholds",
        description = "Per-tenant reporting-thresholds admin CRUD. Effective-dated rows so the "
                + "audit trail can reconstruct 'what was our threshold on this date?'. "
                + "Consumed by the AML/STR periodic summary calculator.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantAmlThresholdConfigController {

    private final TenantAmlThresholdConfigService service;

    @GetMapping
    @RequiresPermission({Permissions.COMPLIANCE_AML_REVIEW,
                         Permissions.COMPLIANCE_AML_CONFIGURE_THRESHOLDS})
    @Operation(summary = "List AML thresholds for a tenant, newest effective-from first")
    public Flux<TenantAmlThresholdConfigResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.COMPLIANCE_AML_CONFIGURE_THRESHOLDS)
    @Operation(summary = "Add a new effective-dated AML threshold row",
            description = "409 on UNIQUE collision (tenant + transactionType + currency + effectiveFrom).")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "Duplicate effective-dated row")
    public Mono<TenantAmlThresholdConfigResponse> add(@PathVariable UUID tenantId,
                                                       @Valid @RequestBody AddTenantAmlThresholdConfigRequest body,
                                                       @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.COMPLIANCE_AML_CONFIGURE_THRESHOLDS)
    @Operation(summary = "Update an existing AML threshold row (rate + effective-to + note only)")
    @ApiResponse(responseCode = "404", description = "Row not found or belongs to another tenant")
    public Mono<TenantAmlThresholdConfigResponse> update(@PathVariable UUID tenantId,
                                                          @PathVariable UUID id,
                                                          @Valid @RequestBody UpdateTenantAmlThresholdConfigRequest body,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.COMPLIANCE_AML_CONFIGURE_THRESHOLDS)
    @Operation(summary = "Delete an AML threshold row")
    public Mono<Void> delete(@PathVariable UUID tenantId,
                             @PathVariable UUID id,
                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(tenantId, id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
