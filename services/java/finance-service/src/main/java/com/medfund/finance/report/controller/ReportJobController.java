package com.medfund.finance.report.controller;

import com.medfund.finance.actuarial.dto.JobStatusResponse;
import com.medfund.finance.actuarial.service.ActuarialJobService;
import com.medfund.finance.actuarial.service.ActuarialXlsxService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical report-job status + export alias per Phase 15 §1 rename (I10 +
 * I22). Serves {@code /api/v1/reports/jobs/{jobId}} and
 * {@code /api/v1/reports/jobs/{jobId}/export.xlsx} — same handlers as the
 * legacy {@code /api/v1/reports/actuarial/jobs/*} endpoints on
 * {@link com.medfund.finance.actuarial.controller.ActuarialReportController},
 * exposed under a report-key-agnostic URL so IFRS 17 and any future report
 * family polls the same shape.
 *
 * <p>Only the poll + export endpoints alias; submit endpoints stay on
 * report-family-specific URLs (POST {@code /reports/actuarial/ibnr},
 * {@code /reports/ifrs17/lrc-lic-reconciliation} etc.). §22 Phase B keeps
 * this controller as the canonical URL and redirects the legacy path on
 * the gateway.
 */
@RestController
@RequestMapping("/api/v1/reports/jobs")
@RequiredArgsConstructor
@Tag(name = "Report Jobs (canonical alias)",
        description = "Report-key-agnostic status + XLSX export endpoints for any async report job "
                + "(actuarial or IFRS 17). Aliases the same handlers as the legacy "
                + "/api/v1/reports/actuarial/jobs/* paths during the Phase 15 §1 rename window.")
@SecurityRequirement(name = "bearer-jwt")
public class ReportJobController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ActuarialJobService jobService;
    private final ActuarialXlsxService xlsxService;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping("/{jobId}")
    @Operation(summary = "Poll an async report job's status",
            description = "Canonical status endpoint for any report family. Rule-2 enforced — a "
                        + "jobId owned by another tenant returns 404, not the row.")
    public Mono<JobStatusResponse> status(@PathVariable UUID jobId) {
        return Mono.deferContextual(ctx -> jobService.status(jobId, resolveTenant(ctx)));
    }

    @GetMapping(value = "/{jobId}/export.xlsx", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "Download the report job result as an XLSX workbook",
            description = "Delegates to the report-family-specific renderer keyed off the job's "
                        + "report_key. Emits a DATA_ACCESS SecurityEvent per Rule 9.")
    public Mono<ResponseEntity<byte[]>> exportXlsx(@PathVariable UUID jobId,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = resolveTenant(ctx);
            String tenantStr = TenantContext.get(ctx);
            return jobService.get(jobId, tenantId)
                    .flatMap(job -> {
                        if (!"completed".equals(job.getStatus()) && !"failed".equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Job is not in a terminal state yet: " + job.getStatus()));
                        }
                        return xlsxService.render(job)
                                .flatMap(bytes -> emitExportSecurityEvent(tenantStr, job.getReportKey(), jobId, jwt)
                                        .thenReturn(bytes))
                                .map(bytes -> {
                                    String filename = "report-" + job.getReportKey().toLowerCase()
                                            + "-" + jobId + ".xlsx";
                                    return ResponseEntity.ok()
                                            .contentType(XLSX)
                                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                                    "attachment; filename=\"" + filename + "\"")
                                            .body(bytes);
                                });
                    });
        });
    }

    private Mono<Void> emitExportSecurityEvent(String tenantId, String reportKey, UUID jobId, Jwt jwt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", jobId.toString());
        details.put("aliasedFrom", "/api/v1/reports/jobs");
        return securityEventPublisher.publishDataAccess(
                tenantId,
                AuditActor.id(jwt),
                AuditActor.email(jwt),
                reportKey,
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
