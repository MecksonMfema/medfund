package com.medfund.contributions.controller;

import com.medfund.contributions.dto.ReceiptsAggregateRow;
import com.medfund.contributions.service.ReceiptsReportService;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Cross-service aggregate endpoints for Phase 3 collection-rate and
 * Phase 5 loss-ratio reports. Deliberately narrow — the (dimension,
 * currency, net-received) shape needed to compose against billing +
 * claims. Kept off the {@code /reports/receipts} path so consumers
 * reading the API don't confuse it with the user-facing report surface.
 *
 * <p>Not gated by {@link com.medfund.shared.report.RequiresReport} —
 * this is a service-to-service endpoint. Gating it would let a tenant
 * admin accidentally disable Phase 3+5 reports across the platform by
 * toggling {@code RECEIPTS_REPORT} off. The per-tenant surfaces they
 * consume already carry their own gates.
 */
@RestController
@RequestMapping("/api/v1/reports/aggregate")
@RequiredArgsConstructor
@Tag(name = "Receipts aggregate (cross-service)",
        description = "Narrow receipts aggregates consumed by Phase 3 collection-rate and Phase 5 "
                    + "loss-ratio reports.")
@SecurityRequirement(name = "bearer-jwt")
public class ReceiptsAggregateController {

    private final ReceiptsReportService receiptsReportService;
    private final ReportEnvelopeBuilder envelopeBuilder;

    @GetMapping("/receipts")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Cross-service receipts aggregate — (scheme, currency, net-received)",
            description = "Same 'receipt' definition as the per-scheme report — completed money-flow "
                        + "transactions netted per transaction_types.sign.")
    public Mono<ReportResponse<List<ReceiptsAggregateRow>>> aggregate(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.RECEIPTS_REPORT,
                period,
                reportingCurrency,
                receiptsReportService.aggregatePerScheme(period.periodStart(), period.periodEnd()),
                receiptsReportService.perSchemePerCurrencyTotals(period.periodStart(), period.periodEnd()));
    }

    @GetMapping("/receipts/monthly")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Monthly-bucketed cross-service receipts aggregate",
            description = "Adds the month dimension so Phase 3 collection-rate + Phase 8 cash-flow "
                        + "forecast see drift over the period, not just period-totals. `dimension` is "
                        + "one of SCHEME / GROUP / MEMBER.")
    public Mono<ReportResponse<List<MonthlyAggregateRow>>> aggregateMonthly(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(defaultValue = "SCHEME") String dimension,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.RECEIPTS_REPORT,
                period,
                reportingCurrency,
                receiptsReportService.aggregateMonthly(dimension, period.periodStart(), period.periodEnd()));
    }
}
