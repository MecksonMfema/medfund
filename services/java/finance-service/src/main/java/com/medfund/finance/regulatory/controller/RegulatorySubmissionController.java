package com.medfund.finance.regulatory.controller;

import com.medfund.finance.regulatory.dto.FilingRefRequest;
import com.medfund.finance.regulatory.dto.RegulatorySubmissionResponse;
import com.medfund.finance.regulatory.dto.SubmitRegulatoryReportRequest;
import com.medfund.finance.regulatory.service.RegulatorySubmissionService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

/**
 * Regulator submission API (Phase 16 §0 REG12 + REG13). Every submit is
 * MFA-guarded — a 401 with {@code x-mfa-required: true} triggers the
 * Angular re-auth modal.
 */
@RestController
@RequestMapping("/api/v1/reports/regulatory/submissions")
@RequiredArgsConstructor
@Tag(name = "Regulatory Submissions",
     description = "Auditor-grade record of every regulator report submission with amendment chain.")
@SecurityRequirement(name = "bearer-jwt")
public class RegulatorySubmissionController {

    private final RegulatorySubmissionService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission({"finance:view", "finance:export_regulatory"})
    @Operation(summary = "Record a regulator report submission",
            description = "MFA-gated: the JWT must carry a fresh MFA amr claim (mfa/otp/totp/hwk) within "
                        + "5 minutes. A 401 with x-mfa-required=true is the trigger for the Angular "
                        + "re-auth modal to prompt Keycloak step-up. If a prior submission exists for the "
                        + "same (tenant, reportKey, periodStart), it is automatically marked SUPERSEDED "
                        + "and this row's submission_number is incremented + supersedes_id linked.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Submission recorded"),
            @ApiResponse(responseCode = "400", description = "Invalid request"),
            @ApiResponse(responseCode = "401", description = "MFA step-up required (x-mfa-required header set)")
    })
    public Mono<RegulatorySubmissionResponse> submit(
            @Valid @RequestBody SubmitRegulatoryReportRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        byte[] xlsxBytes;
        try {
            xlsxBytes = Base64.getDecoder().decode(body.xlsxBase64());
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalArgumentException("xlsxBase64 is not valid base64"));
        }
        return currentTenantId()
                .flatMap(tenantId -> service.submit(
                        tenantId,
                        body.reportKey(),
                        body.periodStart(),
                        body.periodEnd(),
                        body.sourceRunId(),
                        xlsxBytes,
                        jwt,
                        AuditActor.id(jwt),
                        AuditActor.email(jwt),
                        body.attestationNote(),
                        body.reasonNote()))
                .map(RegulatorySubmissionResponse::from);
    }

    @GetMapping
    @RequiresPermission({"finance:view"})
    @Operation(summary = "List submissions for a (report_key, period_start), newest submission_number first")
    public Flux<RegulatorySubmissionResponse> list(
            @RequestParam String reportKey,
            @RequestParam LocalDate periodStart) {
        return currentTenantId()
                .flatMapMany(tenantId -> service.list(tenantId, reportKey, periodStart))
                .map(RegulatorySubmissionResponse::from);
    }

    @PutMapping("/{id}/filing-ref")
    @RequiresPermission({"finance:view", "finance:export_regulatory"})
    @Operation(summary = "Capture the regulator-assigned filing reference for a submission")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Filing reference recorded"),
            @ApiResponse(responseCode = "404", description = "Submission not found for tenant")
    })
    public Mono<RegulatorySubmissionResponse> setFilingRef(
            @PathVariable UUID id,
            @Valid @RequestBody FilingRefRequest body,
            @AuthenticationPrincipal Jwt jwt) {
        return currentTenantId()
                .flatMap(tenantId -> service.setFilingRef(tenantId, id, body.filingRef(),
                        AuditActor.id(jwt), AuditActor.email(jwt)))
                .map(RegulatorySubmissionResponse::from);
    }

    @GetMapping("/{id}/download")
    @RequiresPermission({"finance:view"})
    @Operation(summary = "Download the XLSX bytes for a submission")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID id) {
        return currentTenantId()
                .flatMap(tenantId -> service.get(tenantId, id))
                .map(row -> {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
                    headers.setContentDisposition(org.springframework.http.ContentDisposition
                            .attachment()
                            .filename(row.getReportKey() + "-" + row.getPeriodStart()
                                    + "-n" + row.getSubmissionNumber() + ".xlsx")
                            .build());
                    return new ResponseEntity<>(row.getXlsxBytes(), headers, HttpStatus.OK);
                });
    }

    private static Mono<UUID> currentTenantId() {
        return Mono.deferContextual(ctx -> {
            String raw = TenantContext.get(ctx);
            if (raw == null || raw.isBlank()) {
                return Mono.error(new IllegalStateException("No tenant in context"));
            }
            try {
                return Mono.just(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                return Mono.error(new IllegalArgumentException("Invalid tenant id in context: " + raw));
            }
        });
    }
}
