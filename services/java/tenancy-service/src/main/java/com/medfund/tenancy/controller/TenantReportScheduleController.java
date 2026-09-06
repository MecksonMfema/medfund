package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.CreateTenantReportScheduleRequest;
import com.medfund.tenancy.dto.TenantReportScheduleRecipientResponse;
import com.medfund.tenancy.dto.TenantReportScheduleResponse;
import com.medfund.tenancy.dto.UpdateTenantReportScheduleRequest;
import com.medfund.tenancy.service.TenantReportScheduleService;
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
 * Tenant admin surface for Phase 17 scheduled report delivery. CRUD on
 * {@code public.tenant_report_schedule} plus a public-ish {@code /active}
 * read endpoint used by the notification-service Go dispatcher over HTTP.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/report-schedules")
@RequiredArgsConstructor
@Tag(name = "Tenant Report Schedules",
     description = "Per-tenant scheduled report delivery configuration (Phase 17 §A).")
@SecurityRequirement(name = "bearer-jwt")
public class TenantReportScheduleController {

    private final TenantReportScheduleService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_report_schedules",
                         "admin:manage_settings", "finance:view"})
    @Operation(summary = "List all report schedules for a tenant")
    public Flux<TenantReportScheduleResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{scheduleId}")
    @RequiresPermission({"tenant.settings:manage_report_schedules",
                         "admin:manage_settings", "finance:view"})
    @Operation(summary = "Fetch one schedule by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Row returned"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<TenantReportScheduleResponse> get(@PathVariable UUID tenantId,
                                                  @PathVariable UUID scheduleId) {
        return service.get(tenantId, scheduleId);
    }

    @GetMapping("/{scheduleId}/recipients/active")
    @RequiresPermission({"tenant.settings:manage_report_schedules",
                         "admin:manage_settings", "finance:view",
                         "scheduled_report:render"})
    @Operation(summary = "List active recipients for a schedule",
            description = "Read path for the notification-service Go dispatcher. "
                        + "The dispatcher fetches this list to fan out delivery emails.")
    public Flux<TenantReportScheduleRecipientResponse> activeRecipients(
            @PathVariable UUID tenantId,
            @PathVariable UUID scheduleId) {
        return service.activeRecipients(tenantId, scheduleId)
                .map(TenantReportScheduleRecipientResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_report_schedules"})
    @Operation(summary = "Create a schedule",
            description = "Only Phase 17 whitelisted report keys are accepted "
                        + "(see ScheduledReportEligibility). Cadence + day/hour "
                        + "shape validation applied server-side.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Schedule created"),
            @ApiResponse(responseCode = "400", description = "Invalid payload / non-whitelisted key"),
            @ApiResponse(responseCode = "409", description = "Duplicate (tenant, reportKey)")
    })
    public Mono<TenantReportScheduleResponse> create(@PathVariable UUID tenantId,
                                                     @Valid @RequestBody CreateTenantReportScheduleRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.create(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{scheduleId}")
    @RequiresPermission({"tenant.settings:manage_report_schedules"})
    @Operation(summary = "Update a schedule (PATCH-shaped - omit unchanged fields)")
    public Mono<TenantReportScheduleResponse> update(@PathVariable UUID tenantId,
                                                     @PathVariable UUID scheduleId,
                                                     @Valid @RequestBody UpdateTenantReportScheduleRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, scheduleId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/{scheduleId}")
    @RequiresPermission({"tenant.settings:manage_report_schedules"})
    @Operation(summary = "Delete a schedule (recipients cascade via FK)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Row deleted"),
            @ApiResponse(responseCode = "404", description = "Row not found for tenant")
    })
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID tenantId,
                                             @PathVariable UUID scheduleId,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(tenantId, scheduleId, AuditActor.id(jwt), AuditActor.email(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }
}
