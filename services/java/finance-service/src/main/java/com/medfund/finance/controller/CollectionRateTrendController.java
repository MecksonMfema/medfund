package com.medfund.finance.controller;

import com.medfund.finance.dto.CollectionRateTrendResponse;
import com.medfund.finance.service.CollectionRateTrendExcelService;
import com.medfund.finance.service.CollectionRateTrendService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.report.FxRateReader;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8 portfolio-level collection-rate trend — the per-dimension
 * {@code /reports/collection-rate} stacks collapsed to one monthly strip
 * per currency (D8-3). Distinct endpoint because the two reports answer
 * different questions; both share the same billing/receipts fanout and
 * rate arithmetic.
 *
 * <p>Envelope is hand-built (like {@link CollectionRateReportController})
 * so the cross-service fanout warnings survive, then a best-effort FX
 * pass via {@link FxRateReader#findRate} fills {@code fxRates} — a
 * missing historical rate names itself in warnings but never fails the
 * report (G28).
 */
@RestController
@RequestMapping("/api/v1/reports/collection-rate-trend")
@RequiredArgsConstructor
@Tag(name = "Collection-rate trend",
        description = "Portfolio-level monthly collection-rate trend per currency — receipts vs "
                    + "billing summed across all dimensions. Never cross-currency conversion.")
@SecurityRequirement(name = "bearer-jwt")
public class CollectionRateTrendController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final CollectionRateTrendService trendService;
    private final CollectionRateTrendExcelService trendExcelService;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.COLLECTION_RATE_TREND)
    @Operation(summary = "Portfolio-level collection-rate trend",
            description = "Monthly billed / received / rate per currency, summed across all "
                        + "dimensions. Peer downtime populates envelope warnings.")
    public Mono<ReportResponse<CollectionRateTrendResponse>> report(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            List<String> warnings = new ArrayList<>();
            return currencyResolver.resolve(tenantId, reportingCurrency)
                    .flatMap(currency -> trendService.compute(period.periodStart(), period.periodEnd(), warnings)
                            .flatMap(trend -> buildEnvelope(tenantId, trend, currency, period, warnings)));
        });
    }

    @GetMapping("/export/excel")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.COLLECTION_RATE_TREND)
    @Operation(summary = "Download the collection-rate trend as XLSX",
            description = "One Trend sheet with a meta strip (period, rows, warnings).")
    public Mono<ResponseEntity<byte[]>> exportExcel(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        String filename = "collection-rate-trend-" + period.periodStart() + "-to-" + period.periodEnd() + ".xlsx";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("periodStart", period.periodStart().toString());
        details.put("periodEnd",   period.periodEnd().toString());
        if (reportingCurrency != null && !reportingCurrency.isBlank()) {
            details.put("reportingCurrency", reportingCurrency);
        }
        List<String> warnings = new ArrayList<>();
        return trendExcelService.workbook(period.periodStart(), period.periodEnd(), warnings)
                .flatMap(bytes -> Mono.deferContextual(ctx -> securityEventPublisher.publishDataAccess(
                                TenantContext.get(ctx),
                                AuditActor.id(jwt),
                                AuditActor.email(jwt),
                                ReportKey.COLLECTION_RATE_TREND.name(),
                                details))
                        .thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .body(bytes));
    }

    private Mono<ReportResponse<CollectionRateTrendResponse>> buildEnvelope(
            UUID tenantId, CollectionRateTrendResponse trend, String reportingCurrency,
            ReportPeriod period, List<String> warnings) {
        Map<String, PerCurrencyTotal> perCurrency = new LinkedHashMap<>();
        Map<String, BigDecimal> fxRates = new LinkedHashMap<>();
        for (String code : trend.months().stream().map(CollectionRateTrendResponse.MonthRow::currencyCode)
                .distinct().sorted().toList()) {
            long count = trend.months().stream()
                    .filter(m -> code.equals(m.currencyCode())).count();
            BigDecimal received = trend.months().stream()
                    .filter(m -> code.equals(m.currencyCode()))
                    .map(CollectionRateTrendResponse.MonthRow::received)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            perCurrency.put(code, new PerCurrencyTotal(received, count));
        }
        return Flux.fromIterable(perCurrency.keySet())
                .concatMap(code -> fxRateReader.findRate(code, reportingCurrency, period.periodEnd(), tenantId)
                        .doOnNext(rate -> fxRates.put(code, rate))
                        .switchIfEmpty(Mono.fromRunnable(() -> {
                            if (!code.equals(reportingCurrency)) {
                                warnings.add("FX not available for " + code + "->" + reportingCurrency
                                        + " as of " + period.periodEnd());
                            }
                        })))
                .then(Mono.fromCallable(() -> new ReportResponse<>(
                        ReportKey.COLLECTION_RATE_TREND.name(),
                        period,
                        reportingCurrency,
                        trend,
                        perCurrency,
                        fxRates,
                        List.copyOf(warnings),
                        OffsetDateTime.now())));
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }
}
