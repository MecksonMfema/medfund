package com.medfund.finance.regulatory.aml.controller;

import com.medfund.finance.regulatory.aml.AmlXlsxService;
import com.medfund.finance.regulatory.aml.dto.AmlSummaryJobSubmissionResponse;
import com.medfund.finance.regulatory.aml.dto.AmlSummaryReportRequest;
import com.medfund.finance.regulatory.aml.service.AmlAlertService;
import com.medfund.finance.regulatory.aml.service.AmlStrFilingService;
import com.medfund.finance.regulatory.aml.service.AmlSummaryJobService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresCountry;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST surface for the AML/STR report (Phase 25 + Phase 26 REG8). Three endpoints:
 *
 * <ul>
 *   <li>{@code POST /periodic/submit} — periodic summary compute + optional
 *       archive. Same async-job pattern as PMB / VAT / WHT.</li>
 *   <li>{@code GET  /periodic/jobs/{jobId}/xlsx} — download the periodic
 *       summary XLSX once the job is terminal.</li>
 *   <li>{@code POST /per-str/{alertId}/xlsx} — per-STR filing XLSX for a
 *       REVIEWED or FILED alert. Renders the country-specific ZW FIU goAML /
 *       ZA FIC / US FinCEN SAR template on-demand (Phase 26).</li>
 * </ul>
 *
 * <p>Gate stack: {@code @RequiresCountry({"ZW","ZA","US"})} +
 * {@code @RequiresReport(ReportKey.AML_STR)} + a per-endpoint permission.
 */
@RestController
@RequestMapping("/api/v1/reports/regulatory/aml-str")
@RequiredArgsConstructor
@Tag(name = "AML/STR",
        description = "AML/STR periodic summary + per-STR filing (per-STR deferred to Phase 26). "
                + "Async submit → poll → XLSX for the periodic side; per-STR XLSX is invoked from "
                + "the alert workflow FILED transition once Phase 26 templates land.")
@SecurityRequirement(name = "bearer-jwt")
public class AmlStrReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final AmlSummaryJobService jobService;
    private final AmlXlsxService xlsxService;
    private final AmlAlertService alertService;
    private final AmlStrFilingService strFilingService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping("/periodic/submit")
    @RequiresCountry({"ZW", "ZA", "US"})
    @RequiresReport(ReportKey.AML_STR)
    @RequiresPermission({Permissions.COMPLIANCE_AML_REVIEW, Permissions.FINANCE_EXPORT_REGULATORY})
    @Operation(summary = "Submit an AML/STR periodic summary compute job")
    @ApiResponse(responseCode = "201", description = "Job created (or in-flight duplicate reused)")
    @ApiResponse(responseCode = "403", description = "Country / report / permission gate rejected")
    @ApiResponse(responseCode = "422", description = "Client attempted to override reportingCurrency")
    public Mono<ResponseEntity<AmlSummaryJobSubmissionResponse>> submitPeriodic(
            @Valid @RequestBody AmlSummaryReportRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = resolveTenant(ctx);
            if (tenantId == null) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tenant context missing on request"));
            }
            return jobService.submit(body, tenantId, jwt, AuditActor.id(jwt), AuditActor.email(jwt))
                    .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp));
        });
    }

    @GetMapping(value = "/periodic/jobs/{jobId}/xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RequiresCountry({"ZW", "ZA", "US"})
    @RequiresReport(ReportKey.AML_STR)
    @RequiresPermission({Permissions.COMPLIANCE_AML_REVIEW, Permissions.FINANCE_EXPORT_REGULATORY})
    @Operation(summary = "Download the AML/STR periodic summary XLSX")
    @ApiResponse(responseCode = "200", description = "XLSX bytes")
    @ApiResponse(responseCode = "404", description = "Job not found or belongs to another tenant")
    @ApiResponse(responseCode = "409", description = "Job is not yet in a terminal state")
    public Mono<ResponseEntity<byte[]>> exportPeriodicXlsx(@PathVariable UUID jobId,
                                                            @AuthenticationPrincipal Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = resolveTenant(ctx);
            String tenantStr = TenantContext.get(ctx);
            return jobService.get(jobId, tenantId)
                    .flatMap(job -> {
                        if (!AmlSummaryJobService.STATUS_COMPLETED.equals(job.getStatus())
                                && !AmlSummaryJobService.STATUS_FAILED.equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Job is not in a terminal state yet: " + job.getStatus()));
                        }
                        if (AmlSummaryJobService.STATUS_FAILED.equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Job failed: " + (job.getErrorMessage() != null ? job.getErrorMessage() : "unknown")));
                        }
                        return jobService.reshapeFromJob(job)
                                .flatMap(data -> xlsxService.render(tenantId, data)
                                        .flatMap(rendered -> emitExportSecurityEvent(tenantStr, jobId, jwt)
                                                .thenReturn(rendered)))
                                .map(rendered -> {
                                    String filename = "aml-summary-" + jobId + ".xlsx";
                                    return ResponseEntity.ok()
                                            .contentType(XLSX)
                                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                                    "attachment; filename=\"" + filename + "\"")
                                            .header("x-regulatory-template-source",
                                                    rendered.source().name())
                                            .header("x-regulatory-template-version",
                                                    rendered.versionLabel() != null ? rendered.versionLabel() : "")
                                            .body(rendered.bytes());
                                });
                    });
        });
    }

    /**
     * Per-STR filing XLSX endpoint (Phase 26). Re-renders the country-specific
     * filing template on-demand from the alert row — idempotent since the
     * shape is a pure function of the alert. Available on FILED alerts (the
     * primary use case is downloading the same XLSX that was auto-uploaded
     * at file-time) but also on REVIEWED alerts so compliance can preview
     * before filing. Rejects RAISED / CLOSED with HTTP 409.
     */
    @PostMapping("/per-str/{alertId}/xlsx")
    @RequiresCountry({"ZW", "ZA", "US"})
    @RequiresReport(ReportKey.AML_STR)
    @RequiresPermission(Permissions.COMPLIANCE_AML_FILE)
    @Operation(summary = "Generate a per-STR filing XLSX for a REVIEWED or FILED alert")
    @ApiResponse(responseCode = "200", description = "XLSX bytes")
    @ApiResponse(responseCode = "404", description = "Alert not found")
    @ApiResponse(responseCode = "409", description = "Alert is in a state that cannot be filed (RAISED / CLOSED)")
    public Mono<ResponseEntity<byte[]>> exportPerStrXlsx(
            @PathVariable UUID alertId,
            @AuthenticationPrincipal Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = resolveTenant(ctx);
            String tenantStr = TenantContext.get(ctx);
            return alertService.findEntity(alertId)
                    .flatMap(alert -> {
                        String status = alert.getStatus();
                        if (!AmlAlertService.STATUS_REVIEWED.equals(status)
                                && !AmlAlertService.STATUS_FILED.equals(status)) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Cannot export per-STR XLSX for alert in status " + status
                                            + " - must be REVIEWED or FILED."));
                        }
                        return strFilingService.render(tenantId, alert)
                                .flatMap(rendered -> emitPerStrExportSecurityEvent(tenantStr, alertId, jwt)
                                        .thenReturn(rendered))
                                .map(rendered -> {
                                    String filename = "aml-str-" + alertId + ".xlsx";
                                    return ResponseEntity.ok()
                                            .contentType(XLSX)
                                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                                    "attachment; filename=\"" + filename + "\"")
                                            .header("x-regulatory-template-source",
                                                    rendered.source().name())
                                            .header("x-regulatory-template-version",
                                                    rendered.versionLabel() != null ? rendered.versionLabel() : "")
                                            .body(rendered.bytes());
                                });
                    });
        });
    }

    private Mono<Void> emitPerStrExportSecurityEvent(String tenantId, UUID alertId, Jwt jwt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("alertId", alertId.toString());
        details.put("perStr", true);
        return securityEventPublisher.publishDataAccess(
                tenantId,
                AuditActor.id(jwt),
                AuditActor.email(jwt),
                ReportKey.AML_STR.name(),
                details);
    }

    private Mono<Void> emitExportSecurityEvent(String tenantId, UUID jobId, Jwt jwt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", jobId.toString());
        return securityEventPublisher.publishDataAccess(
                tenantId,
                AuditActor.id(jwt),
                AuditActor.email(jwt),
                ReportKey.AML_STR.name(),
                details);
    }

    private static UUID resolveTenant(reactor.util.context.ContextView ctx) {
        String raw = TenantContext.get(ctx);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
