package com.medfund.user.endorsement.controller;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.endorsement.dto.EndorsementRegisterRow;
import com.medfund.user.endorsement.service.EndorsementRegisterReportService;
import com.medfund.user.endorsement.service.EndorsementRegisterWorkbookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 12 §C Phase 9 — Endorsement Register report. Sits in user-service
 * (data ownership per G2: endorsements live here) rather than
 * contributions-service. GET short-circuits with HTTP 403 when the tenant
 * admin has disabled the report via {@code public.tenant_report_config};
 * XLSX export emits a {@code DATA_ACCESS} security event before returning
 * bytes (parent-plan invariant #3).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports/premium/endorsements")
@RequiredArgsConstructor
@Tag(name = "Endorsement Register",
        description = "Phase 12 §C Phase 9 - per-endorsement register for a reporting window. "
                + "Native-currency rows, best-effort FX to reporting currency, XLSX export with "
                + "security-event trail. Data ownership: endorsements live in user-service.")
@SecurityRequirement(name = "bearer-jwt")
public class EndorsementRegisterReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final EndorsementRegisterReportService reportService;
    private final EndorsementRegisterWorkbookService workbookService;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.ENDORSEMENT_REGISTER)
    @Operation(summary = "Endorsement register for a period",
            description = "One row per endorsement whose effectiveFrom falls in [periodStart, "
                    + "periodEnd]. Optional insuranceLine + status filters narrow the set. "
                    + "Envelope carries |premiumDelta| per-currency subtotals + best-effort FX.")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping the endorsement rows")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<List<EndorsementRegisterRow>>> register(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reportingCurrency) {
        return reportService.register(periodStart, periodEnd, insuranceLine, status, reportingCurrency);
    }

    @GetMapping("/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.ENDORSEMENT_REGISTER)
    @Operation(summary = "Export endorsement register as XLSX",
            description = "Endorsements sheet + Summary. Emits DATA_ACCESS security event with "
                    + "the reportKey + filters before returning bytes.")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        Map<String, Object> details = exportDetails(periodStart, periodEnd, insuranceLine, status,
                reportingCurrency);
        String file = filename(periodStart, periodEnd, insuranceLine, status);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            return workbookService.workbook(periodStart, periodEnd, insuranceLine, status,
                            reportingCurrency, tenantId)
                    .flatMap(bytes -> securityEventPublisher.publishDataAccess(tenantIdStr, actorId,
                                    actorEmail, ReportKey.ENDORSEMENT_REGISTER.name(), details)
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + file + "\"")
                            .body(bytes));
        });
    }

    private static Map<String, Object> exportDetails(LocalDate periodStart, LocalDate periodEnd,
                                                     String insuranceLine, String status,
                                                     String reportingCurrency) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", periodStart.toString());
        details.put("periodEnd", periodEnd.toString());
        if (insuranceLine != null && !insuranceLine.isBlank()) details.put("insuranceLine", insuranceLine);
        if (status != null && !status.isBlank()) details.put("status", status);
        if (reportingCurrency != null && !reportingCurrency.isBlank())
            details.put("reportingCurrency", reportingCurrency);
        return details;
    }

    private static String filename(LocalDate start, LocalDate end, String insuranceLine, String status) {
        StringBuilder sb = new StringBuilder("endorsement-register-").append(start).append("_").append(end);
        if (insuranceLine != null && !insuranceLine.isBlank()) sb.append("-").append(insuranceLine.toLowerCase());
        if (status != null && !status.isBlank()) sb.append("-").append(status.toLowerCase());
        return sb.append(".xlsx").toString();
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
