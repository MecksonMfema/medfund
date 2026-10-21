package com.medfund.finance.ifrs17.controller;

import com.medfund.finance.ifrs17.dto.Ifrs17JobSubmissionResponse;
import com.medfund.finance.ifrs17.dto.Ifrs17ReportRequest;
import com.medfund.finance.ifrs17.service.Ifrs17JobService;
import com.medfund.finance.ifrs17.service.Ifrs17XlsxService;
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
 * IFRS 17 async report submission endpoints (Phase 15 §17). Two POSTs — one
 * per {@link ReportKey} — both wire through {@link Ifrs17JobService#submit}
 * which fans out into per (portfolio × cohort × currency) chunks and publishes
 * one Kafka event per chunk to {@code medfund.report.job-requested}.
 *
 * <p>Each endpoint carries {@link RequiresReport} so the tenant admin's report
 * on/off switch takes effect without a redeploy (Rule 7). Matches the
 * actuarial-controller convention — the report toggle is the enable-gate;
 * finer-grained per-role permission enforcement lives on the tenant admin's
 * report-config screen rather than a hard-coded @PreAuthorize.
 *
 * <p>Polling for these jobs reuses the shared {@code ReportJobController}
 * {@code /api/v1/reports/jobs/*} endpoints — no need for an IFRS17-specific
 * status endpoint since {@link com.medfund.finance.report.entity.ReportJob} is
 * the same table for actuarial + IFRS 17.
 */
@RestController
@RequestMapping("/api/v1/reports/ifrs17")
@RequiredArgsConstructor
@Tag(name = "IFRS 17 Reports",
        description = "Async submit endpoints for IFRS 17 measurement reports - LRC/LIC "
                + "reconciliation and insurance revenue + service result. POST returns the parent "
                + "jobId; chunks fan out per (portfolio × cohort × currency) and Kafka events fire "
                + "to the ai-service compute path. Poll via /api/v1/reports/jobs/{jobId} for the "
                + "terminal state.")
@SecurityRequirement(name = "bearer-jwt")
public class Ifrs17ReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final Ifrs17JobService jobService;
    private final Ifrs17XlsxService xlsxService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping("/lrc-lic-reconciliation")
    @RequiresReport(ReportKey.IFRS17_LRC_LIC_RECONCILIATION)
    @Operation(summary = "Submit an IFRS 17 LRC/LIC reconciliation report",
            description = "Fans out one compute chunk per (portfolio × cohort × currency) for the "
                    + "requested period. Each chunk fires the IFRS17_MODEL rule to pick PAA / GMM / "
                    + "VFA + coverage-unit + finance-expense presentation, then publishes to the "
                    + "ai-service compute path via Kafka. Result envelope aggregates in §18. "
                    + "STATUTORY_7Y retention per I28.")
    @ApiResponse(responseCode = "201", description = "Parent job created (or in-flight duplicate reused)")
    public Mono<ResponseEntity<Ifrs17JobSubmissionResponse>> submitLrcLicReconciliation(
            @Valid @RequestBody Ifrs17ReportRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return submitWithReportKey(ReportKey.IFRS17_LRC_LIC_RECONCILIATION, body, jwt);
    }

    @PostMapping("/insurance-revenue-service-result")
    @RequiresReport(ReportKey.IFRS17_INSURANCE_REVENUE_SERVICE_RESULT)
    @Operation(summary = "Submit an IFRS 17 insurance revenue & service result report",
            description = "Same chunk fan-out as LRC/LIC reconciliation; the report_key differs so "
                    + "the aggregator (§18) shapes the envelope for revenue + service result "
                    + "rather than LRC/LIC roll-forward.")
    @ApiResponse(responseCode = "201", description = "Parent job created (or in-flight duplicate reused)")
    public Mono<ResponseEntity<Ifrs17JobSubmissionResponse>> submitInsuranceRevenueServiceResult(
            @Valid @RequestBody Ifrs17ReportRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return submitWithReportKey(ReportKey.IFRS17_INSURANCE_REVENUE_SERVICE_RESULT, body, jwt);
    }

    @GetMapping(value = "/jobs/{jobId}/export.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RequiresReport(ReportKey.IFRS17_LRC_LIC_RECONCILIATION)
    @Operation(summary = "Download an IFRS 17 report job as an XLSX workbook",
            description = "Renders the aggregated {@code result_json} envelope into a multi-sheet "
                    + "workbook: Summary sheet at position 0, one sheet per portfolio with LRC-top / "
                    + "LIC-below (or revenue / service-result blocks) per the I21 layout. Emits a "
                    + "DATA_ACCESS SecurityEvent per Rule 9. Report key is derived from the job row so "
                    + "the caller doesn't have to disambiguate.")
    @ApiResponse(responseCode = "200", description = "XLSX bytes")
    @ApiResponse(responseCode = "404", description = "Job not found (or belongs to another tenant)")
    @ApiResponse(responseCode = "409", description = "Job is not yet in a terminal state")
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
                                .flatMap(bytes -> emitExportSecurityEvent(tenantStr,
                                                job.getReportKey(), jobId, jwt)
                                        .thenReturn(bytes))
                                .map(bytes -> {
                                    String filename = "ifrs17-" + job.getReportKey().toLowerCase()
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

    private Mono<ResponseEntity<Ifrs17JobSubmissionResponse>> submitWithReportKey(
            ReportKey reportKey, Ifrs17ReportRequest body, Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = resolveTenant(ctx);
            if (tenantId == null) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tenant context missing on request"));
            }
            return jobService.submit(reportKey, body, tenantId,
                            AuditActor.id(jwt), AuditActor.email(jwt))
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
