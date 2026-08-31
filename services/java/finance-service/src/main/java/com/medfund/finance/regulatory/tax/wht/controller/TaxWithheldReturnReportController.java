package com.medfund.finance.regulatory.tax.wht.controller;

import com.medfund.finance.regulatory.tax.wht.TaxWithheldXlsxService;
import com.medfund.finance.regulatory.tax.wht.dto.TaxWithheldReturnJobSubmissionResponse;
import com.medfund.finance.regulatory.tax.wht.dto.TaxWithheldReturnReportRequest;
import com.medfund.finance.regulatory.tax.wht.service.TaxWithheldReturnJobService;
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
 * REST surface for the Withholding-Tax Return (Phase 16 §C, Phase 21).
 *
 * <p>Gate stack:
 * <ol>
 *   <li>{@link RequiresCountry}({"ZW","ZA"}) — other jurisdictions have
 *       dedicated regimes.</li>
 *   <li>{@link RequiresReport}(TAX_WITHHELD_RETURN) — tenant admin toggle.</li>
 *   <li>{@link RequiresPermission} — {@code finance:view} or
 *       {@code finance:export_regulatory}.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/reports/regulatory/tax/withheld-return")
@RequiredArgsConstructor
@Tag(name = "Withholding-Tax Return",
        description = "Country-native withholding-tax return (ZIMRA ITF12B / SARS IRP5-shape) "
                + "(Phase 16 §C). Async submit → poll → XLSX. Reporting currency follows "
                + "tenant.country_code; a non-blank override is rejected 422 upstream. Bundled "
                + "templates are SYNTHETIC in Phase 21.")
@SecurityRequirement(name = "bearer-jwt")
public class TaxWithheldReturnReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TaxWithheldReturnJobService jobService;
    private final TaxWithheldXlsxService xlsxService;
    private final SecurityEventPublisher securityEventPublisher;

    @PostMapping("/submit")
    @RequiresCountry({"ZW", "ZA"})
    @RequiresReport(ReportKey.TAX_WITHHELD_RETURN)
    @RequiresPermission({Permissions.FINANCE_VIEW, Permissions.FINANCE_EXPORT_REGULATORY})
    @Operation(summary = "Submit a Withholding-Tax Return compute job",
            description = "Creates a report_job row + kicks off the async shape → serialise → "
                    + "optional archive chain. Poll /api/v1/reports/jobs/{jobId} for terminal, "
                    + "then GET the /jobs/{jobId}/xlsx sibling to download. "
                    + "Set submit=true to also archive to regulatory_submission (requires fresh MFA).")
    @ApiResponse(responseCode = "201", description = "Job created (or in-flight duplicate reused)")
    @ApiResponse(responseCode = "403", description = "Country / report / permission gate rejected")
    @ApiResponse(responseCode = "422", description = "Client attempted to override reportingCurrency")
    public Mono<ResponseEntity<TaxWithheldReturnJobSubmissionResponse>> submit(
            @Valid @RequestBody TaxWithheldReturnReportRequest body,
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
    @RequiresCountry({"ZW", "ZA"})
    @RequiresReport(ReportKey.TAX_WITHHELD_RETURN)
    @RequiresPermission({Permissions.FINANCE_VIEW, Permissions.FINANCE_EXPORT_REGULATORY})
    @Operation(summary = "Download the Withholding-Tax Return XLSX",
            description = "Re-shapes from the job's persisted params + composes the country-appropriate "
                    + "bundled (or tenant-override) template. Emits DATA_ACCESS SecurityEvent (Rule 9).")
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
                        if (!TaxWithheldReturnJobService.STATUS_COMPLETED.equals(job.getStatus())
                                && !TaxWithheldReturnJobService.STATUS_FAILED.equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Job is not in a terminal state yet: " + job.getStatus()));
                        }
                        if (TaxWithheldReturnJobService.STATUS_FAILED.equals(job.getStatus())) {
                            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                                    "Job failed: " + (job.getErrorMessage() != null ? job.getErrorMessage() : "unknown")));
                        }
                        return jobService.reshapeFromJob(job)
                                .flatMap(data -> xlsxService.render(tenantId, data)
                                        .flatMap(rendered -> emitExportSecurityEvent(tenantStr, jobId, jwt)
                                                .thenReturn(rendered)))
                                .map(rendered -> {
                                    String filename = "tax-withheld-return-" + jobId + ".xlsx";
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
                ReportKey.TAX_WITHHELD_RETURN.name(),
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
