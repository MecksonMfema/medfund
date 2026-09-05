package com.medfund.finance.kpi.controller;

import com.medfund.finance.kpi.dto.KpiDashboardResponse;
import com.medfund.finance.kpi.dto.KpiReportData;
import com.medfund.finance.kpi.dto.KpiRequest;
import com.medfund.finance.kpi.dto.KpiTrendPoint;
import com.medfund.finance.kpi.service.KpiComposerService;
import com.medfund.finance.kpi.service.KpiWorkbookService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.ReportEnablementReader;
import com.medfund.shared.report.ReportFamily;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 18 executive KPI dashboard endpoints — five individual composers +
 * one batch surface. Each individual endpoint is gated by
 * {@link RequiresReport} so a tenant admin can toggle any KPI off in
 * {@code /tenant/admin/settings/reports}; the batch endpoint requires all
 * five gates to pass and short-circuits with a 403 whenever any of them
 * fires (K17). Cadenced=true keys ({@link ReportKey#LOSS_RATIO_KPI},
 * {@link ReportKey#EXPENSE_RATIO}, {@link ReportKey#COMBINED_RATIO},
 * {@link ReportKey#CLAIMS_FREQUENCY}, {@link ReportKey#AVERAGE_SEVERITY})
 * feed the Phase 17 scheduled-email path via Phase-8 adapters.
 */
@RestController
@RequestMapping("/api/v1/reports/kpi")
@RequiredArgsConstructor
@Tag(name = "Executive KPI dashboard",
        description = "Phase 18 — LOSS_RATIO_KPI, EXPENSE_RATIO (Acquisition Ratio), COMBINED_RATIO, "
                    + "CLAIMS_FREQUENCY, AVERAGE_SEVERITY. Cross-service composer with 15-minute Redis "
                    + "cache; peer downtime populates envelope warnings (invariant #7).")
@SecurityRequirement(name = "bearer-jwt")
public class ExecutiveKpiController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final KpiComposerService composer;
    private final ReportEnablementReader reportEnablementReader;
    private final KpiWorkbookService workbookService;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping("/loss-ratio")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.LOSS_RATIO_KPI)
    @Operation(summary = "Loss ratio KPI",
            description = "Composite = (paid + Δreserve + IBNR) / earned premium in the reporting "
                        + "currency; per-currency breakdown stays native (G34). IBNR missing → "
                        + "warning + zero-IBNR fallback (K9).")
    public Mono<ReportResponse<KpiReportData>> lossRatio(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId) {
        return Mono.deferContextual(ctx -> composer.lossRatio(
                buildRequest(TenantContext.get(ctx), periodStart, periodEnd,
                        reportingCurrency, insuranceLine, schemeId, producerId)));
    }

    @GetMapping("/expense-ratio")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.EXPENSE_RATIO)
    @Operation(summary = "Expense ratio KPI (UI label: 'Acquisition Ratio')",
            description = "K4/K5: paid commission / written premium. v1 sums all PAID commission "
                        + "without acquisition/servicing split (K7 deferred). insuranceLine filter "
                        + "is ignored on the billing denominator until per-line billing lands.")
    public Mono<ReportResponse<KpiReportData>> expenseRatio(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId) {
        return Mono.deferContextual(ctx -> composer.expenseRatio(
                buildRequest(TenantContext.get(ctx), periodStart, periodEnd,
                        reportingCurrency, insuranceLine, schemeId, producerId)));
    }

    @GetMapping("/combined-ratio")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.COMBINED_RATIO)
    @Operation(summary = "Combined ratio KPI",
            description = "K6: additive mixed-basis sum of LOSS_RATIO_KPI + EXPENSE_RATIO. "
                        + "basisNote=MIXED_LOSS_EARNED_EXPENSE_WRITTEN — NAIC convention.")
    public Mono<ReportResponse<KpiReportData>> combinedRatio(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId) {
        return Mono.deferContextual(ctx -> composer.combinedRatio(
                buildRequest(TenantContext.get(ctx), periodStart, periodEnd,
                        reportingCurrency, insuranceLine, schemeId, producerId)));
    }

    @GetMapping("/claims-frequency")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CLAIMS_FREQUENCY)
    @Operation(summary = "Claims frequency KPI",
            description = "K1: count of claims / policy-months-in-force. Dimensionless — a single "
                        + "reporting-currency-labelled entry keeps the tile UI shape uniform.")
    public Mono<ReportResponse<KpiReportData>> claimsFrequency(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId) {
        return Mono.deferContextual(ctx -> composer.claimsFrequency(
                buildRequest(TenantContext.get(ctx), periodStart, periodEnd,
                        reportingCurrency, insuranceLine, schemeId, producerId)));
    }

    @GetMapping("/average-severity")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.AVERAGE_SEVERITY)
    @Operation(summary = "Average severity KPI",
            description = "K1: paid / claim count in the reporting currency. Per-currency "
                        + "breakdown stays native.")
    public Mono<ReportResponse<KpiReportData>> averageSeverity(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId) {
        return Mono.deferContextual(ctx -> composer.averageSeverity(
                buildRequest(TenantContext.get(ctx), periodStart, periodEnd,
                        reportingCurrency, insuranceLine, schemeId, producerId)));
    }

    @GetMapping("/{key}/trend")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "12- or 24-month trend for the given KPI",
            description = "K14: {windowMonths} monthly buckets ending at the last complete month, "
                        + "oldest first. Each element carries the composite ratio + per-currency "
                        + "breakdown + warnings for that bucket. Gate check runs imperatively "
                        + "against ReportEnablementReader because @RequiresReport cannot accept a "
                        + "runtime path variable.")
    public Mono<List<KpiTrendPoint>> trend(
            @Parameter(description = "Dashboard KPI key — LOSS_RATIO_KPI, EXPENSE_RATIO, COMBINED_RATIO, "
                    + "CLAIMS_FREQUENCY, AVERAGE_SEVERITY")
            @PathVariable String key,
            @RequestParam(defaultValue = "12") int windowMonths,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId) {
        ReportKey rk = ReportKey.parse(key)
                .filter(k -> k.getFamily() == ReportFamily.DASHBOARD)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown or non-dashboard KPI key: " + key));
        if (windowMonths != 12 && windowMonths != 24) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "windowMonths must be 12 or 24 (was " + windowMonths + ")");
        }
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            LocalDate anchor = LocalDate.now().withDayOfMonth(1);
            KpiRequest baseReq = new KpiRequest(
                    tenantId,
                    anchor.minusMonths(windowMonths),
                    anchor,
                    blankToNull(reportingCurrency),
                    blankToNull(insuranceLine),
                    schemeId,
                    producerId);
            return reportEnablementReader.isEnabled(tenantId, rk)
                    .flatMap(enabled -> enabled
                            ? composer.trend(rk, baseReq, windowMonths)
                            : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "Report is disabled for this tenant: " + rk.name())));
        });
    }

    @GetMapping("/{key}/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Export a single KPI as XLSX (Summary + 12-month Trend sheets)",
            description = "Phase 8 on-demand export. Same tenant-enablement gate as the JSON "
                        + "endpoints (imperative because the key is a runtime path variable); "
                        + "publishes a DATA_ACCESS security event with the export filters "
                        + "before returning the bytes. Two sheets: Summary carries composite "
                        + "ratio + per-currency breakdown + warnings; Trend carries the "
                        + "12 monthly composite ratios.")
    public Mono<ResponseEntity<byte[]>> exportExcel(
            @Parameter(description = "Dashboard KPI key")
            @PathVariable String key,
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId,
            @AuthenticationPrincipal Jwt jwt) {
        ReportKey rk = ReportKey.parse(key)
                .filter(k -> k.getFamily() == ReportFamily.DASHBOARD)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown or non-dashboard KPI key: " + key));
        String actorId    = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseTenantId(tenantIdStr);
            KpiRequest req = new KpiRequest(
                    tenantId,
                    LocalDate.parse(periodStart),
                    LocalDate.parse(periodEnd),
                    blankToNull(reportingCurrency),
                    blankToNull(insuranceLine),
                    schemeId,
                    producerId);
            return reportEnablementReader.isEnabled(tenantId, rk)
                    .flatMap(enabled -> enabled
                            ? workbookService.workbook(rk, req)
                            : Mono.<byte[]>error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                    "Report is disabled for this tenant: " + rk.name())))
                    .flatMap(bytes -> securityEventPublisher.publishDataAccess(tenantIdStr,
                                    actorId, actorEmail, rk.name(),
                                    exportDetails(periodStart, periodEnd, reportingCurrency,
                                            insuranceLine, schemeId, producerId))
                            .thenReturn(bytes))
                    .map(bytes -> ResponseEntity.ok()
                            .contentType(XLSX)
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + filename(rk, periodStart, periodEnd) + "\"")
                            .body(bytes));
        });
    }

    private static Map<String, Object> exportDetails(String periodStart, String periodEnd,
                                                     String reportingCurrency, String insuranceLine,
                                                     UUID schemeId, UUID producerId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("periodStart",       periodStart);
        d.put("periodEnd",         periodEnd);
        d.put("reportingCurrency", Objects.toString(blankToNull(reportingCurrency), ""));
        d.put("insuranceLine",     Objects.toString(blankToNull(insuranceLine),     ""));
        d.put("schemeId",          schemeId   != null ? schemeId.toString()   : "");
        d.put("producerId",        producerId != null ? producerId.toString() : "");
        d.put("source",            "MANUAL_EXPORT");
        return d;
    }

    private static String filename(ReportKey key, String periodStart, String periodEnd) {
        return key.name().toLowerCase() + "-" + periodStart + "-to-" + periodEnd + ".xlsx";
    }

    @GetMapping("/dashboard")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Batch — five KPI envelopes in one round-trip",
            description = "K16: individual @RequiresReport gates re-check inside the composer; "
                        + "any disabled key surfaces as 403 for the whole payload.")
    public Mono<KpiDashboardResponse> dashboard(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId) {
        return Mono.deferContextual(ctx -> composer.dashboard(
                buildRequest(TenantContext.get(ctx), periodStart, periodEnd,
                        reportingCurrency, insuranceLine, schemeId, producerId)));
    }

    private static KpiRequest buildRequest(String tenantIdStr,
                                           String periodStart, String periodEnd,
                                           String reportingCurrency, String insuranceLine,
                                           UUID schemeId, UUID producerId) {
        return new KpiRequest(
                parseTenantId(tenantIdStr),
                LocalDate.parse(periodStart),
                LocalDate.parse(periodEnd),
                blankToNull(reportingCurrency),
                blankToNull(insuranceLine),
                schemeId,
                producerId);
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
