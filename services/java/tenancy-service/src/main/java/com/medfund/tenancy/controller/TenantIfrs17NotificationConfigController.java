package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantIfrs17NotificationConfigRequest;
import com.medfund.tenancy.dto.TenantIfrs17NotificationConfigResponse;
import com.medfund.tenancy.dto.UpdateTenantIfrs17NotificationConfigRequest;
import com.medfund.tenancy.service.TenantIfrs17NotificationConfigService;
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
 * Per-tenant recipient list for IFRS 17 material events (Phase 15 §19 / I30).
 * CRUD mirroring {@link TenantRaConfigController}: list / add / update /
 * delete on {@code /api/v1/tenants/{tenantId}/ifrs17-notification-config}.
 * The Go notification-service dispatcher (§20) reads active rows via
 * {@code /active} at fan-out time.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ifrs17-notification-config")
@RequiredArgsConstructor
@Tag(name = "Tenant IFRS 17 Notification Config",
     description = "Per-tenant recipients for IFRS 17 material events (onerous transition, CSM negative etc).")
@SecurityRequirement(name = "bearer-jwt")
public class TenantIfrs17NotificationConfigController {

    private final TenantIfrs17NotificationConfigService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_ifrs17_config", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List IFRS 17 notification config rows for a tenant")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantIfrs17NotificationConfigResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId).map(TenantIfrs17NotificationConfigResponse::from);
    }

    @GetMapping("/active")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config", "admin:manage_settings", "finance:view"})
    @Operation(summary = "List active recipients for a given event type",
            description = "Read path for the notification-service dispatcher. "
                        + "Returns rows matching the concrete event type PLUS rows subscribed to ALL.")
    @ApiResponse(responseCode = "200", description = "Rows returned")
    public Flux<TenantIfrs17NotificationConfigResponse> activeFor(
            @PathVariable UUID tenantId,
            @RequestParam("eventType") String eventType) {
        return service.activeFor(tenantId, eventType)
                .map(TenantIfrs17NotificationConfigResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Add a notification config row",
            description = "eventType must be one of ONEROUS_TRANSITION | CSM_NEGATIVE | "
                        + "LOCKED_IN_CURVE_FALLBACK | IBNR_SUB_JOB_STALE | "
                        + "OPENING_BALANCE_AUTO_DERIVED | ALL. deliveryMethod is EMAIL | WEBHOOK | BOTH. "
                        + "Uniqueness enforced on (event_type, recipient) per tenant — conflict surfaces as 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "400", description = "Invalid payload"),
            @ApiResponse(responseCode = "409", description = "Duplicate (event_type, recipient)")
    })
    public Mono<TenantIfrs17NotificationConfigResponse> add(
            @PathVariable UUID tenantId,
            @Valid @RequestBody AddTenantIfrs17NotificationConfigRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantIfrs17NotificationConfigResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Update a notification config row",
            description = "Only deliveryMethod, throttleMinutes and isActive are mutable. "
                        + "The (event_type, recipient) tuple is immutable — swapping either "
                        + "field is a new row (add) at a fresh recipient, not an in-place edit.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row updated"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantIfrs17NotificationConfigResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTenantIfrs17NotificationConfigRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, id, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantIfrs17NotificationConfigResponse::from);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission({"tenant.settings:manage_ifrs17_config"})
    @Operation(summary = "Delete a notification config row")
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
