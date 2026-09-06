package com.medfund.claims.reports.provider.controller;

import com.medfund.claims.reports.provider.dto.ProviderUtilizationResult;
import com.medfund.claims.reports.provider.service.ProviderNetworkUtilizationReportService;
import com.medfund.claims.reports.provider.service.ProviderNetworkUtilizationWorkbookService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
import java.util.Map;

/**
 * Phase 13 §C Phase 9 per L12 + L13 — PROVIDER_NETWORK_UTILIZATION report
 * (stays under CLAIMS_FINANCIAL family per L8). Two-level payload; XLSX
 * export emits {@code DATA_ACCESS} security event before returning bytes.
 */
@RestController
@RequestMapping("/api/v1/reports/claims")
@RequiredArgsConstructor
@Tag(name = "Provider network utilization",
        description = "Phase 13 §C Phase 9 - per-provider claim aggregates enriched with "
                + "network_tier via a batched user-service lookup. Peer-down: placeholder "
                + "names + envelope warnings.")
@SecurityRequirement(name = "bearer-jwt")
public class ProviderNetworkUtilizationReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ProviderNetworkUtilizationReportService service;
    private final ProviderNetworkUtilizationWorkbookService workbook;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping("/provider-network-utilization")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PROVIDER_NETWORK_UTILIZATION)
    @Operation(summary = "Provider network utilization for a window")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping the two-level result")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<ProviderUtilizationResult>> generate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String networkTier,
            @RequestParam(required = false) String reportingCurrency) {
        return service.generate(periodStart, periodEnd, insuranceLine, networkTier, reportingCurrency);
    }

    @GetMapping("/provider-network-utilization/export")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.PROVIDER_NETWORK_UTILIZATION)
    @Operation(summary = "Export provider network utilization as XLSX")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) String networkTier,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        Map<String, Object> details = details(periodStart, periodEnd, insuranceLine,
                networkTier, reportingCurrency);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            return workbook.workbook(periodStart, periodEnd, insuranceLine, networkTier, reportingCurrency)
                    .flatMap(bytes -> securityEventPublisher
                            .publishDataAccess(tenantIdStr, actorId, actorEmail,
                                    ReportKey.PROVIDER_NETWORK_UTILIZATION.name(), details)
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"provider-network-utilization-"
                                            + periodStart + "_" + periodEnd + ".xlsx\"")
                            .body(bytes));
        });
    }

    private static Map<String, Object> details(LocalDate ps, LocalDate pe, String line, String tier, String rc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("periodStart", ps.toString());
        m.put("periodEnd",   pe.toString());
        if (line != null && !line.isBlank()) m.put("insuranceLine", line);
        if (tier != null && !tier.isBlank()) m.put("networkTier", tier);
        if (rc != null && !rc.isBlank())     m.put("reportingCurrency", rc);
        return m;
    }
}
