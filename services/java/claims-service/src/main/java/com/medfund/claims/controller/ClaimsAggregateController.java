package com.medfund.claims.controller;

import com.medfund.claims.dto.ClaimsAggregateRow;
import com.medfund.claims.service.ClaimsReportService;
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
 * Cross-service aggregate endpoints for Phase 5 loss-ratio and Phase 8
 * cash-flow / KPI-dashboard consumers. Deliberately narrow — the
 * (dimension, dimensionId, dimensionName, currency, claimed/approved/paid)
 * shape needed to compose against billing + receipts. Kept off the
 * {@code /reports/claims} path so consumers reading the API don't confuse
 * it with the user-facing report surface.
 *
 * <p>Not gated by {@link com.medfund.shared.report.RequiresReport} —
 * this is a service-to-service endpoint. Gating it would let a tenant
 * admin accidentally disable Phase 5 loss-ratio across the platform by
 * toggling {@code CLAIMS_SUMMARY} off. The per-tenant surfaces they
 * consume already carry their own gates (plan §A §3 rationale).
 */
@RestController
@RequestMapping("/api/v1/reports/aggregate")
@RequiredArgsConstructor
@Tag(name = "Claims aggregate (cross-service)",
        description = "Narrow claims aggregates consumed by Phase 5 loss-ratio and Phase 8 "
                    + "cash-flow / KPI-dashboard reports.")
@SecurityRequirement(name = "bearer-jwt")
public class ClaimsAggregateController {

    private final ClaimsReportService claimsReportService;
    private final ReportEnvelopeBuilder envelopeBuilder;

    @GetMapping("/claims")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Cross-service claims aggregate — narrow funnel rows per (dimension, currency)",
            description = "Period clock is adjudicated_at (G41). Dimension defaults to SCHEME — the "
                        + "Phase 5 loss-ratio consumer pairs it with the billing-vs-receipts per-scheme "
                        + "shape. Each row carries the full claimed / approved / paid funnel so loss-ratio "
                        + "can pick paid-ratio or approved-liability-ratio without a second round trip "
                        + "(G44).")
    public Mono<ReportResponse<List<ClaimsAggregateRow>>> aggregate(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.aggregate("SCHEME", period.periodStart(), period.periodEnd()),
                claimsReportService.claimsPerCurrencyTotals(period.periodStart(), period.periodEnd(), null));
    }

    @GetMapping("/claims/monthly")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Monthly-bucketed cross-service claims aggregate",
            description = "Adds the month dimension so Phase 8 cash-flow forecast + KPI dashboards see "
                        + "paid drift over the period, not just period-totals. `dimension` is one of "
                        + "SCHEME / GROUP / MEMBER / PROVIDER (G45); totalAmount carries total_paid "
                        + "(G44).")
    public Mono<ReportResponse<List<MonthlyAggregateRow>>> aggregateMonthly(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(defaultValue = "SCHEME") String dimension,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.buildNoAggregate(
                ReportKey.CLAIMS_SUMMARY,
                period,
                reportingCurrency,
                claimsReportService.aggregateMonthly(dimension, period.periodStart(), period.periodEnd()));
    }
}
