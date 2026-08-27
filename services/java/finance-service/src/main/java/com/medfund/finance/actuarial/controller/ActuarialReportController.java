package com.medfund.finance.actuarial.controller;

import com.medfund.finance.actuarial.dto.IbnrJobRequest;
import com.medfund.finance.actuarial.dto.JobStatusResponse;
import com.medfund.finance.actuarial.dto.JobSubmissionResponse;
import com.medfund.finance.actuarial.dto.PersistencyStudyJobRequest;
import com.medfund.finance.actuarial.service.ActuarialJobService;
import com.medfund.finance.actuarial.service.ActuarialXlsxService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.RequiresReport;
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
 * Async job orchestrator endpoints for actuarial reports (Phase 9 ships
 * IBNR + LOSS_TRIANGLE — Phases 11-14 add persistency, lapse, mortality,
 * morbidity onto the same shape).
 *
 * <p>Every submit is guarded by {@link RequiresReport} against its
 * {@code ReportKey} so the tenant admin's on/off switch takes effect
 * without a redeploy. Every XLSX export emits a
 * {@code DATA_ACCESS} SecurityEvent per Rule 9.
 */
@RestController
@RequestMapping("/api/v1/reports/actuarial")
@RequiredArgsConstructor
@Tag(name = "Actuarial Reports",
        description = "IBNR triangle + loss triangle + study jobs. Async — POST returns a jobId, "
                + "poll /jobs/{jobId} for the terminal state, GET /jobs/{jobId}/export.xlsx for the workbook.")
@SecurityRequirement(name = "bearer-jwt")
public class ActuarialReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ActuarialJobService jobService;
    private final ActuarialXlsxService xlsxService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping("/ibnr")
    @RequiresReport(ReportKey.IBNR_TRIANGLE)
    @Operation(summary = "Submit an IBNR triangle job",
            description = "Shapes the paid/incurred/reported triangle from adjudicated claims, "
                        + "publishes to the ai-service compute pipeline via Kafka, and returns "
                        + "the jobId for polling.")
    @ApiResponse(responseCode = "201", description = "Job submitted (or existing in-flight duplicate reused)")
    public Mono<ResponseEntity<JobSubmissionResponse>> submitIbnr(
            @Valid @RequestBody IbnrJobRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return submitWithReportKey(ReportKey.IBNR_TRIANGLE, body, jwt);
    }

    @PostMapping("/loss-triangle")
    @RequiresReport(ReportKey.LOSS_TRIANGLE)
    @Operation(summary = "Submit a loss triangle job")
    @ApiResponse(responseCode = "201", description = "Job submitted (or existing in-flight duplicate reused)")
    public Mono<ResponseEntity<JobSubmissionResponse>> submitLoss(
            @Valid @RequestBody IbnrJobRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return submitWithReportKey(ReportKey.LOSS_TRIANGLE, body, jwt);
    }

    @PostMapping("/persistency-study")
    @RequiresReport(ReportKey.PERSISTENCY_STUDY)
    @Operation(summary = "Submit a persistency study job",
            description = "Shapes cohorts of newly-active members/policies against the tenant's "
                        + "expected retention curves and returns actual/expected + A/E ratios per "
                        + "checkpoint. Async — POST returns jobId; poll /jobs/{jobId}.")
    @ApiResponse(responseCode = "201", description = "Job submitted (or existing in-flight duplicate reused)")
    public Mono<ResponseEntity<JobSubmissionResponse>> submitPersistencyStudy(
            @Valid @RequestBody PersistencyStudyJobRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = resolveTenant(ctx);
            if (tenantId == null) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tenant context missing on request"));
            }
            return jobService.submitPersistencyStudy(body, tenantId,
                            AuditActor.id(jwt), AuditActor.email(jwt))
                    .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp));
        });
    }

    @GetMapping("/jobs/{jobId}")
    @RequiresReport(ReportKey.IBNR_TRIANGLE)
    @Operation(summary = "Poll an actuarial job's status",
            description = "Returns the current terminal-or-processing state for jobId. Rule-2 "
                        + "enforced — a jobId owned by another tenant returns 404, not the row.")
    public Mono<JobStatusResponse> status(@PathVariable UUID jobId) {
        return Mono.deferContextual(ctx -> jobService.status(jobId, resolveTenant(ctx)));
    }

    @GetMapping(value = "/jobs/{jobId}/export.xlsx", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @RequiresReport(ReportKey.IBNR_TRIANGLE)
    @Operation(summary = "Download the actuarial job result as an XLSX workbook",
            description = "Three sheets: triangle input, development factors, summary. Emits a "
                        + "DATA_ACCESS SecurityEvent per Rule 9.")
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
                                    String filename = "actuarial-" + job.getReportKey().toLowerCase()
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

    private Mono<ResponseEntity<JobSubmissionResponse>> submitWithReportKey(
            ReportKey reportKey, IbnrJobRequest body, Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = resolveTenant(ctx);
            if (tenantId == null) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tenant context missing on request"));
            }
            return jobService.submit(reportKey, body, tenantId, AuditActor.id(jwt), AuditActor.email(jwt))
                    .map(resp -> ResponseEntity.status(HttpStatus.CREATED).body(resp));
        });
    }

    private Mono<Void> emitExportSecurityEvent(String tenantId, String reportKey, UUID jobId, Jwt jwt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", jobId.toString());
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
