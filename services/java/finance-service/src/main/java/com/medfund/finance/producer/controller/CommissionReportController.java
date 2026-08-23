package com.medfund.finance.producer.controller;

import com.medfund.finance.producer.dto.ClawbackRegisterRow;
import com.medfund.finance.producer.dto.CommissionStatementRow;
import com.medfund.finance.producer.service.CommissionClawbackReportService;
import com.medfund.finance.producer.service.CommissionStatementService;
import com.medfund.finance.producer.service.CommissionWorkbookService;
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
 * Two commission report endpoint families — Statement + Clawback Register.
 * Every JSON GET short-circuits with HTTP 403 when the tenant admin has
 * disabled the report via {@code public.tenant_report_config} (through
 * {@link RequiresReport}). Every XLSX export additionally emits a
 * {@code DATA_ACCESS} security event carrying the report key + filters
 * before returning the workbook bytes (parent-plan invariant #3).
 *
 * <p>Rows in the JSON envelope stay native-currency (parent-plan invariant
 * #1); the {@code perCurrency} + {@code fxRates} on {@link ReportResponse}
 * carry the metadata a client uses to convert. The XLSX renders the
 * per-currency subtotals inline and a best-effort converted grand total in
 * the Summary sheet (fallback = "FX unavailable" warning line, matching the
 * reinsurance bordereau precedent).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/reports/commission")
@RequiredArgsConstructor
@Tag(name = "Commission Reports",
     description = "Producer commission statement + clawback register — native-currency rows, "
                 + "best-effort FX rates to reporting currency, XLSX exports with security-event trail.")
@SecurityRequirement(name = "bearer-jwt")
public class CommissionReportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CommissionStatementService statementService;
    private final CommissionClawbackReportService clawbackService;
    private final CommissionWorkbookService workbookService;
    private final SecurityEventPublisher securityEventPublisher;

    // ── Commission statement ────────────────────────────────────────────────

    @GetMapping("/statement")
    @RequiresPermission(Permissions.COMMISSION_VIEW)
    @RequiresReport(ReportKey.COMMISSION_STATEMENT)
    @Operation(summary = "Commission statement for a period",
            description = "One row per commission_transaction between periodStart and periodEnd. "
                        + "Optional producer filter narrows to a single producer; optional "
                        + "reportingCurrency overrides the tenant default. Envelope carries "
                        + "per-currency native subtotals + best-effort FX to reportingCurrency.")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping the list of commission statement rows")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<List<CommissionStatementRow>>> statement(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) UUID producerId,
            @RequestParam(required = false) String reportingCurrency) {
        return statementService.statement(periodStart, periodEnd, producerId, reportingCurrency);
    }

    @GetMapping("/statement/export/excel")
    @RequiresPermission(Permissions.COMMISSION_VIEW)
    @RequiresReport(ReportKey.COMMISSION_STATEMENT)
    @Operation(summary = "Export commission statement as XLSX",
            description = "Multi-sheet: one per currency + Summary with per-currency subtotals + "
                        + "best-effort converted grand total. Emits DATA_ACCESS security event.")
    public Mono<ResponseEntity<byte[]>> statementExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) UUID producerId,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            return workbookService
                    .statementWorkbook(periodStart, periodEnd, producerId, reportingCurrency, tenantId)
                    .flatMap(bytes -> securityEventPublisher.publishDataAccess(tenantIdStr, actorId,
                                    actorEmail, ReportKey.COMMISSION_STATEMENT.name(),
                                    statementExportDetails(periodStart, periodEnd, producerId,
                                            reportingCurrency))
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\""
                                    + statementFilename(periodStart, periodEnd, producerId) + "\"")
                            .body(bytes));
        });
    }

    // ── Clawback register ───────────────────────────────────────────────────

    @GetMapping("/clawback-register")
    @RequiresPermission(Permissions.COMMISSION_VIEW)
    @RequiresReport(ReportKey.COMMISSION_CLAWBACK)
    @Operation(summary = "Clawback register for a period",
            description = "One row per clawback_event. Optional source filter narrows to "
                        + "MEMBER_LAPSE or CONTRIBUTION_REVOKE; optional producer filter and "
                        + "reportingCurrency behave as on the statement endpoint.")
    @ApiResponse(responseCode = "200", description = "Envelope wrapping the list of clawback register rows")
    @ApiResponse(responseCode = "403", description = "Report disabled by tenant admin")
    public Mono<ReportResponse<List<ClawbackRegisterRow>>> clawbackRegister(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) UUID producerId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String reportingCurrency) {
        return clawbackService.register(periodStart, periodEnd, producerId, source, reportingCurrency);
    }

    @GetMapping("/clawback-register/export/excel")
    @RequiresPermission(Permissions.COMMISSION_VIEW)
    @RequiresReport(ReportKey.COMMISSION_CLAWBACK)
    @Operation(summary = "Export clawback register as XLSX",
            description = "Multi-sheet: one per source + Summary. Emits DATA_ACCESS security event.")
    public Mono<ResponseEntity<byte[]>> clawbackRegisterExport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) UUID producerId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId    = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            return workbookService
                    .clawbackWorkbook(periodStart, periodEnd, producerId, source, reportingCurrency, tenantId)
                    .flatMap(bytes -> securityEventPublisher.publishDataAccess(tenantIdStr, actorId,
                                    actorEmail, ReportKey.COMMISSION_CLAWBACK.name(),
                                    clawbackExportDetails(periodStart, periodEnd, producerId, source,
                                            reportingCurrency))
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\""
                                    + clawbackFilename(periodStart, periodEnd, producerId, source) + "\"")
                            .body(bytes));
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    private static Map<String, Object> statementExportDetails(LocalDate periodStart, LocalDate periodEnd,
                                                              UUID producerId, String reportingCurrency) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", periodStart.toString());
        details.put("periodEnd",   periodEnd.toString());
        if (producerId != null) details.put("producerId", producerId.toString());
        if (reportingCurrency != null && !reportingCurrency.isBlank())
            details.put("reportingCurrency", reportingCurrency);
        return details;
    }

    private static Map<String, Object> clawbackExportDetails(LocalDate periodStart, LocalDate periodEnd,
                                                             UUID producerId, String source,
                                                             String reportingCurrency) {
        Map<String, Object> details = statementExportDetails(periodStart, periodEnd, producerId, reportingCurrency);
        if (source != null && !source.isBlank()) details.put("source", source);
        return details;
    }

    private static String statementFilename(LocalDate start, LocalDate end, UUID producerId) {
        StringBuilder sb = new StringBuilder("commission-statement-")
                .append(start).append("_").append(end);
        if (producerId != null) sb.append("-p").append(producerId.toString().substring(0, 8));
        return sb.append(".xlsx").toString();
    }

    private static String clawbackFilename(LocalDate start, LocalDate end, UUID producerId, String source) {
        StringBuilder sb = new StringBuilder("commission-clawback-")
                .append(start).append("_").append(end);
        if (producerId != null) sb.append("-p").append(producerId.toString().substring(0, 8));
        if (source != null && !source.isBlank()) sb.append("-").append(source.toLowerCase());
        return sb.append(".xlsx").toString();
    }
}
