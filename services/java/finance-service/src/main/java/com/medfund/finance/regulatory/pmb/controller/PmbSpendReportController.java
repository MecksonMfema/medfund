package com.medfund.finance.regulatory.pmb.controller;

import com.medfund.finance.regulatory.pmb.PmbSpendXlsxService;
import com.medfund.finance.regulatory.pmb.dto.PmbSpendJobSubmissionResponse;
import com.medfund.finance.regulatory.pmb.dto.PmbSpendReportRequest;
import com.medfund.finance.regulatory.pmb.service.PmbSpendJobService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresCountry;
import com.medfund.shared.security.RequiresJurisdiction;
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
 * REST surface for the PMB Spend report (Phase 16 §B, Phase 18).
 *
 * <ul>
 *   <li>{@code POST /submit} — enqueue an async compute + optional archive.</li>
 *   <li>{@code GET /jobs/{jobId}/xlsx} — download the composed XLSX for a
 *       terminal-state job. Emits a {@code DATA_ACCESS} SecurityEvent
 *       (Rule 9). Polling for the job's status uses the shared canonical
 *       {@code GET /api/v1/reports/jobs/{jobId}} endpoint.</li>
 * </ul>
 *
 * <p>Gate stack (all must pass):
 * <ol>
 *   <li>{@link RequiresJurisdiction} — tenant must be {@code ZA_CMS_MEDICAL_SCHEME}.</li>
 *   <li>{@link RequiresCountry} — tenant must be South Africa ({@code ZA}) —
 *       belt-and-braces alongside the jurisdiction gate; a mis-set
 *       jurisdiction on a non-ZA tenant still can't reach the endpoint.</li>
 *   <li>{@link RequiresReport} — the PMB spend toggle in tenant admin must
 *       be enabled.</li>
 *   <li>{@link RequiresPermission} — the calling user must hold either
 *       {@code finance:view} or {@code finance:export_regulatory}.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/reports/regulatory/pmb/spend")
@RequiredArgsConstructor
@Tag(name = "PMB Spend",
        description = "CMS Prescribed Minimum Benefit spend report (Phase 16 §B). Async submit → "
                + "poll → XLSX. Reporting currency is fixed at ZAR; a non-blank override is rejected "
                + "with 422 upstream by the shape service. Bundled template is SYNTHETIC in Phase 18; "
                + "a downstream sub-phase swaps in a real template once CMS publishes canonical "
                + "reporting guidance.")
@SecurityRequirement(name = "bearer-jwt")
public class PmbSpendReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final PmbSpendJobService jobService;
    private final PmbSpendXlsxService xlsxService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping("/submit")
    @RequiresJurisdiction({"ZA_CMS_MEDICAL_SCHEME"})
    @RequiresCountry({"ZA"})
    @RequiresReport(ReportKey.PMB_SPEND)
    @RequiresPermission({Permissions.FINANCE_VIEW, Permissions.FINANCE_EXPORT_REGULATORY})
    @Operation(summary = "Submit a PMB Spend compute job",
            description = "Creates a report_job row for the requested period and kicks off the async "
                    + "shape → serialise → optional archive chain. Returns the jobId immediately; "
                    + "poll /api/v1/reports/jobs/{jobId} for the terminal state, then download via "
                    + "/api/v1/reports/regulatory/pmb/spend/jobs/{jobId}/xlsx. "
                    + "Set request.submit=true to also archive the composed XLSX under "
                    + "regulatory_submission (requires fresh MFA on the JWT).")
    @ApiResponse(responseCode = "201", description = "Job created (or in-flight duplicate reused)")
    @ApiResponse(responseCode = "403", description = "Jurisdiction / country / report / permission gate rejected")
    @ApiResponse(responseCode = "422", description = "Client attempted to override reportingCurrency")
    public Mono<ResponseEntity<PmbSpendJobSubmissionResponse>> submit(
            @Valid @RequestBody PmbSpendReportRequest body,
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

    @GetMapping(value = "/jobs/{jobId}/xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RequiresJurisdiction({"ZA_CMS_MEDICAL_SCHEME"})
    @RequiresCountry({"ZA"})
    @RequiresReport(ReportKey.PMB_SPEND)
    @RequiresPermission({Permissions.FINANCE_VIEW, Permissions.FINANCE_EXPORT_REGULATORY})
    @Operation(summary = "Download the PMB Spend XLSX",
            description = "Re-shapes the report from the job's persisted params and composes the "
                    + "bundled (or tenant-override) template. Emits a DATA_ACCESS SecurityEvent per "
                    + "Rule 9. Returns 409 if the job has not reached a terminal state, 404 if it "
                    + "belongs to another tenant or is not a PMB Spend job.")
    @ApiResponse(responseCode = "200", description = "XLSX bytes")
    @ApiResponse(responseCode = "404", description = "Job not found or belongs to another tenant")
    @ApiResponse(responseCode = "409", description = "Job is not yet in a terminal state")
    public Mono<ResponseEntity<byte[]>> exportXlsx(@PathVariable UUID jobId,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = resolveTenant(ctx);
            String tenantStr = TenantContext.get(ctx);
            return jobService.get(jobId, tenantId)
                    .flatMap(job -> {
                        if (!PmbSpendJobService.STATUS_COMPLETED.equals(job.getStatus())
                                && !PmbSpendJobService.STATUS_FAILED.equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Job is not in a terminal state yet: " + job.getStatus()));
                        }
                        if (PmbSpendJobService.STATUS_FAILED.equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Job failed: " + (job.getErrorMessage() != null ? job.getErrorMessage() : "unknown")));
                        }
                        return jobService.reshapeFromJob(job)
                                .flatMap(data -> xlsxService.render(tenantId, data)
                                        .flatMap(rendered -> emitExportSecurityEvent(tenantStr, jobId, jwt)
                                                .thenReturn(rendered)))
                                .map(rendered -> {
                                    String filename = "pmb-spend-" + jobId + ".xlsx";
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

    private Mono<Void> emitExportSecurityEvent(String tenantId, UUID jobId, Jwt jwt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("jobId", jobId.toString());
        return securityEventPublisher.publishDataAccess(
                tenantId,
                AuditActor.id(jwt),
                AuditActor.email(jwt),
                ReportKey.PMB_SPEND.name(),
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
