package com.medfund.tenancy.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.tenancy.dto.AddTenantReportScheduleRecipientRequest;
import com.medfund.tenancy.dto.TenantReportScheduleRecipientResponse;
import com.medfund.tenancy.dto.UpdateTenantReportScheduleRecipientRequest;
import com.medfund.tenancy.service.TenantReportScheduleRecipientService;
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
 * Recipient CRUD nested under a schedule. Every mutation flows through
 * {@link TenantReportScheduleRecipientService} which enforces tenant scope
 * via the parent schedule, so cross-tenant recipientId spoofing is blocked.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/report-schedules/{scheduleId}/recipients")
@RequiredArgsConstructor
@Tag(name = "Tenant Report Schedule Recipients",
     description = "Per-schedule email recipient list for Phase 17 delivery.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantReportScheduleRecipientController {

    private final TenantReportScheduleRecipientService service;

    @GetMapping
    @RequiresPermission({"tenant.settings:manage_report_schedules",
                         "admin:manage_settings", "finance:view"})
    @Operation(summary = "List every recipient for a schedule")
    public Flux<TenantReportScheduleRecipientResponse> list(@PathVariable UUID tenantId,
                                                            @PathVariable UUID scheduleId) {
        return service.list(tenantId, scheduleId)
                .map(TenantReportScheduleRecipientResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"tenant.settings:manage_report_schedules"})
    @Operation(summary = "Add a recipient",
            description = "Uniqueness is (schedule_id, LOWER(email)) — 409 on conflict.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Row added"),
            @ApiResponse(responseCode = "409", description = "Duplicate (schedule, email)")
    })
    public Mono<TenantReportScheduleRecipientResponse> add(
            @PathVariable UUID tenantId,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody AddTenantReportScheduleRecipientRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.add(tenantId, scheduleId, body, AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantReportScheduleRecipientResponse::from);
    }

    @PutMapping("/{recipientId}")
    @RequiresPermission({"tenant.settings:manage_report_schedules"})
    @Operation(summary = "Update recipient — email is immutable, delete + re-add to change it")
    public Mono<TenantReportScheduleRecipientResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID scheduleId,
            @PathVariable UUID recipientId,
            @Valid @RequestBody UpdateTenantReportScheduleRecipientRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, scheduleId, recipientId, body,
                        AuditActor.id(jwt), AuditActor.email(jwt))
                .map(TenantReportScheduleRecipientResponse::from);
    }

    @DeleteMapping("/{recipientId}")
    @RequiresPermission({"tenant.settings:manage_report_schedules"})
    @Operation(summary = "Delete a recipient")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID tenantId,
                                             @PathVariable UUID scheduleId,
                                             @PathVariable UUID recipientId,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(tenantId, scheduleId, recipientId,
                        AuditActor.id(jwt), AuditActor.email(jwt))
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }
}
