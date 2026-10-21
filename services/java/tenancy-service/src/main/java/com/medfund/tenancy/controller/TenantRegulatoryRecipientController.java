package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantRegulatoryRecipientRequest;
import com.medfund.tenancy.dto.TenantRegulatoryRecipientResponse;
import com.medfund.tenancy.dto.UpdateTenantRegulatoryRecipientRequest;
import com.medfund.tenancy.service.TenantRegulatoryRecipientService;
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
 * Per-tenant recipient list for Phase 16 §0 REG20 regulator-due-date
 * reminders. CRUD mirroring {@code TenantIfrs17NotificationConfigController}:
 * list / add / update / delete on {@code /api/v1/tenants/{tenantId}/regulatory-recipients}
 * plus an {@code /active} read path for the notification-service dispatcher.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/regulatory-recipients")
@RequiredArgsConstructor
@Tag(name = "Tenant Regulatory Recipients",
     description = "Per-tenant email recipients for regulator report due-date reminders (Phase 16 §0 REG20).")
@SecurityRequirement(name = "bearer-jwt")
public class TenantRegulatoryRecipientController {

    private final TenantRegulatoryRecipientService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_regulatory_notifications",
                         "admin:manage_settings", "finance:view"})
    @Operation(summary = "List every recipient for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantRegulatoryRecipientResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(TenantRegulatoryRecipientResponse::from);
    }

    @GetMapping("/active")
    @RequiresPermission({"tenant.settings:manage_regulatory_notifications",
                         "admin:manage_settings", "finance:view"})
    @Operation(summary = "List active recipients - read path for the notification dispatcher",
            description = "The notification-service Go dispatcher pulls active rows and does "
                        + "the per-event-tier subscription filter Go-side so the filter logic "
                        + "stays close to the fan-out.")
    public Flux<TenantRegulatoryRecipientResponse> activeFor(@PathVariable UUID tenantId) {
        return service.activeFor(tenantId).map(TenantRegulatoryRecipientResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_regulatory_notifications"})
    @Operation(summary = "Add a recipient",
            description = "Email is required; subscribedEventTiers defaults to all four tiers when null/empty. "
                        + "Uniqueness is (tenant_id, email) - 409 on conflict.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate (tenant, email)")
    })
    public Mono<TenantRegulatoryRecipientResponse> add(
            @PathVariable UUID tenantId,
            @Valid @RequestBody AddTenantRegulatoryRecipientRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantRegulatoryRecipientResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_regulatory_notifications"})
    @Operation(summary = "Update recipient - email is immutable, delete + re-add to change it")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantRegulatoryRecipientResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantRegulatoryRecipientRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantRegulatoryRecipientResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_regulatory_notifications"})
    @Operation(summary = "Delete a recipient")
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
