package com.medfund.contributions.controller;

import com.medfund.contributions.dto.CashFlowForecastResponse;
import com.medfund.contributions.dto.CashFlowForecastResponse.CurrencySeries;
import com.medfund.contributions.service.CashFlowForecastExcelService;
import com.medfund.contributions.service.CashFlowForecastService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportingCurrencyResolver;
import com.medfund.shared.report.RequiresReport;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.security.SecurityEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8 13-week cash-flow forecast (D8-1..D8-7). Lives here in
 * contributions-service (not finance) per the plan's structure — inflow
 * is computed locally from the invoice ledger while outflow is composed
 * from the finance-service planned-outflow feed, reversing the usual
 * aggregator direction (Deviations Phase 8 §1).
 *
 * <p>Envelope is hand-built (warnings from the finance-service fanout
 * must survive) with {@code fxRates} deliberately empty: forward rates
 * don't exist, so there is nothing to convert into and the reporting
 * currency is purely informational (D8-7).
 */
@RestController
@RequestMapping("/api/v1/reports/cash-flow-forecast")
@RequiredArgsConstructor
@Tag(name = "Cash-flow forecast",
        description = "13-week rolling cash-flow forecast per currency — inflow from unpaid invoices "
                    + "(due-date bucketed), outflow from draft/approved payment runs. Never "
                    + "cross-currency conversion.")
@SecurityRequirement(name = "bearer-jwt")
public class CashFlowForecastController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CashFlowForecastService cashFlowForecastService;
    private final CashFlowForecastExcelService cashFlowForecastExcelService;
    private final ReportingCurrencyResolver currencyResolver;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CASH_FLOW_FORECAST_13W)
    @Operation(summary = "13-week rolling cash-flow forecast",
            description = "Window is [asOf, asOf + rollingWeeks*7). Inflow = unpaid invoices by "
                        + "due_date; outflow = draft/approved payment runs by created_at. Both "
                        + "bucketed by ISO weeks. Finance-service downtime populates envelope "
                        + "warnings; outflow then renders as all-zero.")
    public Mono<ReportResponse<CashFlowForecastResponse>> forecast(
            @RequestParam(required = false) String asOf,
            @RequestParam(required = false, defaultValue = "13") int rollingWeeks,
            @RequestParam(required = false) String reportingCurrency) {
        LocalDate asOfDate = asOf != null && !asOf.isBlank() ? LocalDate.parse(asOf) : LocalDate.now();
        List<String> warnings = new ArrayList<>();
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return currencyResolver.resolve(tenantId, reportingCurrency)
                    .flatMap(currency -> cashFlowForecastService.compute(asOfDate, rollingWeeks, warnings)
                            .map(forecast -> buildEnvelope(currency, forecast, warnings)));
        });
    }

    @GetMapping("/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.CASH_FLOW_FORECAST_13W)
    @Operation(summary = "Download the cash-flow forecast as XLSX",
            description = "One Summary sheet plus one sheet per currency with the weekly "
                        + "inflow / outflow / net strip.")
    public Mono<ResponseEntity<byte[]>> exportExcel(
            @RequestParam(required = false) String asOf,
            @RequestParam(required = false, defaultValue = "13") int rollingWeeks,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        LocalDate asOfDate = asOf != null && !asOf.isBlank() ? LocalDate.parse(asOf) : LocalDate.now();
        List<String> warnings = new ArrayList<>();
        return cashFlowForecastExcelService.workbook(asOfDate, rollingWeeks, warnings)
                .flatMap(bytes -> Mono.deferContextual(ctx -> {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("asOf", asOfDate.toString());
                    details.put("rollingWeeks", rollingWeeks);
                    if (reportingCurrency != null && !reportingCurrency.isBlank()) {
                        details.put("reportingCurrency", reportingCurrency);
                    }
                    return securityEventPublisher.publishDataAccess(
                                    TenantContext.get(ctx),
                                    AuditActor.id(jwt),
                                    AuditActor.email(jwt),
                                    ReportKey.CASH_FLOW_FORECAST_13W.name(),
                                    details)
                            .thenReturn(bytes);
                }))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"cash-flow-forecast-" + asOfDate + ".xlsx\"")
                        .body(bytes));
    }

    private ReportResponse<CashFlowForecastResponse> buildEnvelope(
            String reportingCurrency, CashFlowForecastResponse forecast, List<String> warnings) {
        // periodStart = window start; periodEnd = last covered day (inclusive).
        ReportPeriod period = new ReportPeriod(
                forecast.windowStart(),
                forecast.windowEnd().minusDays(1),
                ReportPeriod.PeriodGrain.WEEKLY);
        Map<String, PerCurrencyTotal> perCurrency = new LinkedHashMap<>();
        for (CurrencySeries s : forecast.series()) {
            perCurrency.put(s.currencyCode(), new PerCurrencyTotal(s.totalNet(), s.buckets().size()));
        }
        return new ReportResponse<>(
                ReportKey.CASH_FLOW_FORECAST_13W.name(),
                period,
                reportingCurrency,
                forecast,
                perCurrency,
                Map.of(),
                List.copyOf(warnings),
                OffsetDateTime.now());
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }
}
