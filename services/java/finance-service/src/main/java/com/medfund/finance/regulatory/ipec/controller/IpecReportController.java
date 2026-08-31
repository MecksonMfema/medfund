package com.medfund.finance.regulatory.ipec.controller;

import com.medfund.finance.regulatory.ipec.IpecXlsxService;
import com.medfund.finance.regulatory.ipec.dto.IpecJobSubmissionResponse;
import com.medfund.finance.regulatory.ipec.dto.IpecReportRequest;
import com.medfund.finance.regulatory.ipec.service.IpecJobService;
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
 * REST surface for the IPEC quarterly return (Phase 16 §A, Phase 10).
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
 *   <li>{@link RequiresJurisdiction} — tenant must be {@code ZW_IPEC_SHORT_TERM}.</li>
 *   <li>{@link RequiresCountry} — tenant must be Zimbabwe ({@code ZW}) —
 *       belt-and-braces alongside the jurisdiction gate; a mis-set jurisdiction
 *       on a non-ZW tenant still can't reach the endpoint.</li>
 *   <li>{@link RequiresReport} — the IPEC report toggle in tenant admin
 *       must be enabled (Rule from Phase 15).</li>
 *   <li>{@link RequiresPermission} — the calling user must hold either
 *       {@code finance:view} or {@code finance:export_regulatory}.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/reports/regulatory/ipec/quarterly-return")
@RequiredArgsConstructor
@Tag(name = "IPEC Quarterly Return",
        description = "Zimbabwe IPEC short-term insurance quarterly return (Phase 16 §A). Async submit "
                + "→ poll → XLSX. Reporting currency is fixed at ZWL; a non-blank override is rejected "
                + "with 422 upstream by the shape service. Bundled template is SYNTHETIC in Phase 10; "
                + "downstream sub-phase swaps in the real template once obtained from ipec.co.zw.")
@SecurityRequirement(name = "bearer-jwt")
public class IpecReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final IpecJobService jobService;
    private final IpecXlsxService xlsxService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping("/submit")
    @RequiresJurisdiction({"ZW_IPEC_SHORT_TERM"})
    @RequiresCountry({"ZW"})
    @RequiresReport(ReportKey.IPEC_QUARTERLY_RETURN)
    @RequiresPermission({Permissions.FINANCE_VIEW, Permissions.FINANCE_EXPORT_REGULATORY})
    @Operation(summary = "Submit an IPEC quarterly return compute job",
            description = "Creates a report_job row for the requested quarter and kicks off the "
                    + "async shape → serialise → optional archive chain. Returns the jobId immediately; "
                    + "poll /api/v1/reports/jobs/{jobId} for the terminal state, then download via "
                    + "/api/v1/reports/regulatory/ipec/quarterly-return/jobs/{jobId}/xlsx. "
                    + "Set request.submit=true to also archive the composed XLSX under "
                    + "regulatory_submission (requires fresh MFA on the JWT).")
    @ApiResponse(responseCode = "201", description = "Job created (or in-flight duplicate reused)")
    @ApiResponse(responseCode = "403", description = "Jurisdiction / country / report / permission gate rejected")
    @ApiResponse(responseCode = "422", description = "Client attempted to override reportingCurrency")
    public Mono<ResponseEntity<IpecJobSubmissionResponse>> submit(
            @Valid @RequestBody IpecReportRequest body,
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
    @RequiresJurisdiction({"ZW_IPEC_SHORT_TERM"})
    @RequiresCountry({"ZW"})
    @RequiresReport(ReportKey.IPEC_QUARTERLY_RETURN)
    @RequiresPermission({Permissions.FINANCE_VIEW, Permissions.FINANCE_EXPORT_REGULATORY})
    @Operation(summary = "Download the IPEC quarterly return XLSX",
            description = "Re-shapes the report from the job's persisted params and composes the "
                    + "bundled (or tenant-override) template. Emits a DATA_ACCESS SecurityEvent per "
                    + "Rule 9. Returns 409 if the job has not reached a terminal state, 404 if it "
                    + "belongs to another tenant or is not an IPEC job.")
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
                        if (!IpecJobService.STATUS_COMPLETED.equals(job.getStatus())
                                && !IpecJobService.STATUS_FAILED.equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Job is not in a terminal state yet: " + job.getStatus()));
                        }
                        if (IpecJobService.STATUS_FAILED.equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Job failed: " + (job.getErrorMessage() != null ? job.getErrorMessage() : "unknown")));
                        }
                        return jobService.reshapeFromJob(job)
                                .flatMap(data -> xlsxService.render(tenantId, data)
                                        .flatMap(rendered -> emitExportSecurityEvent(tenantStr, jobId, jwt)
                                                .thenReturn(rendered)))
                                .map(rendered -> {
                                    String filename = "ipec-quarterly-return-" + jobId + ".xlsx";
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
                ReportKey.IPEC_QUARTERLY_RETURN.name(),
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
