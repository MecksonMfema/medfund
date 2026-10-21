package com.medfund.claims.siu.controller;

import com.medfund.claims.siu.dto.AiCalibrationData;
import com.medfund.claims.siu.dto.FraudReportData;
import com.medfund.claims.siu.dto.InvestigatorProductivityRow;
import com.medfund.claims.siu.dto.MemberTopNRow;
import com.medfund.claims.siu.dto.ProviderTopNRow;
import com.medfund.claims.siu.dto.TrendPoint;
import com.medfund.claims.siu.service.FraudReportService;
import com.medfund.claims.siu.service.FraudReportWorkbookService;
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
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fraud / SIU report endpoints (Phase 19 §A Phase 6 MVP). Ships two
 * routes — the 4-KPI-tile summary and the 2-sheet XLSX export. §B Phase 11
 * widens the summary to 6 tiles + trend + top-N + AI calibration; §B
 * Phase 12 wires the same summary into the Phase 17 scheduled dispatch.
 *
 * <p>Both endpoints are gated by {@link RequiresReport} on
 * {@link ReportKey#FRAUD_SIU_REPORT} — the tenant admin toggle in
 * {@code /tenant/admin/settings/reports} turns them off entirely (403).
 * The XLSX export additionally emits a {@code SecurityEvent} with
 * {@code reportKey=FRAUD_SIU_REPORT} per Rule 9.
 */
@Tag(name = "Fraud / SIU Report",
        description = "Phase 19 §A MVP - 4-tile summary + XLSX export.")
@SecurityRequirement(name = "bearer-jwt")
@RestController
@RequestMapping("/api/v1/reports/fraud")
@RequiredArgsConstructor
public class FraudReportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final FraudReportService reportService;
    private final FraudReportWorkbookService workbookService;
    private final SecurityEventPublisher securityEventPublisher;

    @Operation(summary = "Fraud / SIU summary - 6 KPI tiles (opened, confirmed, savings, rate, "
            + "avg cycle days, reopened count)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "envelope with FraudReportData"),
            @ApiResponse(responseCode = "403", description = "tenant has FRAUD_SIU_REPORT toggle off")
    })
    @GetMapping("/summary")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<ReportResponse<FraudReportData>> summary(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        return reportService.summary(periodStart, periodEnd, reportingCurrency);
    }

    @Operation(summary = "Export the fraud / SIU report as XLSX (Summary + Cases detail)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "XLSX bytes"),
            @ApiResponse(responseCode = "403", description = "tenant has FRAUD_SIU_REPORT toggle off")
    })
    @GetMapping(value = "/summary/export",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<ResponseEntity<byte[]>> exportXlsx(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String filename = "fraud-siu-report-" + periodStart + "-to-" + periodEnd + ".xlsx";
        FraudReportWorkbookService.WorkbookOptions opts =
                new FraudReportWorkbookService.WorkbookOptions(true);
        return reportService.summary(periodStart, periodEnd, reportingCurrency)
                .flatMap(env -> workbookService.render(env, opts, jwt)
                        .flatMap(bytes -> publishExportEvent(periodStart, periodEnd,
                                reportingCurrency, jwt).thenReturn(bytes)))
                .map(bytes -> attach(bytes, filename));
    }

    private Mono<Void> publishExportEvent(String periodStart, String periodEnd,
                                           String reportingCurrency, Jwt jwt) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", periodStart);
        details.put("periodEnd", periodEnd);
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        return Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                TenantContext.get(ctx),
                AuditActor.id(jwt),
                AuditActor.email(jwt),
                ReportKey.FRAUD_SIU_REPORT.name(),
                details));
    }

    // ── §B Phase 11 — trend + top-N + AI calibration + productivity ────

    @Operation(summary = "Monthly trend - cases opened / confirmed / dismissed per month")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "trend points, one per month")})
    @GetMapping("/trend")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<List<TrendPoint>> trend(
            @RequestParam(required = false, defaultValue = "12") int months) {
        return reportService.trend(months);
    }

    @Operation(summary = "Top-N providers ranked by composite confirmed savings")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "top-N provider rows")})
    @GetMapping("/top-providers")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<List<ProviderTopNRow>> topProviders(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false, defaultValue = "10") int n) {
        return reportService.topProviders(n, periodStart, periodEnd, reportingCurrency);
    }

    @Operation(summary = "Top-N members ranked by composite confirmed savings")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "top-N member rows")})
    @GetMapping("/top-members")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<List<MemberTopNRow>> topMembers(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false, defaultValue = "10") int n) {
        return reportService.topMembers(n, periodStart, periodEnd, reportingCurrency);
    }

    @Operation(summary = "AI calibration - precision per risk_level (empty + warnings under N=50)")
    @ApiResponses({@ApiResponse(responseCode = "200",
            description = "calibration rows or insufficient-data warning")})
    @GetMapping("/ai-calibration")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<AiCalibrationData> aiCalibration(
            @RequestParam String periodStart,
            @RequestParam String periodEnd) {
        return reportService.aiCalibration(periodStart, periodEnd);
    }

    @Operation(summary = "Investigator productivity - role-gated: officer sees own; supervisor sees all")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "productivity rows (row-filtered per Rule 4)")})
    @GetMapping("/investigator-productivity")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.FRAUD_SIU_REPORT)
    public Mono<List<InvestigatorProductivityRow>> investigatorProductivity(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @AuthenticationPrincipal Jwt jwt) {
        return reportService.investigatorProductivity(periodStart, periodEnd, jwt);
    }

    private static ResponseEntity<byte[]> attach(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
