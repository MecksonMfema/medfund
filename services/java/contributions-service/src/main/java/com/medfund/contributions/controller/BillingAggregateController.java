package com.medfund.contributions.controller;

import com.medfund.contributions.dto.BillingAggregateRow;
import com.medfund.contributions.service.BillingReportService;
import com.medfund.shared.report.PerCurrencyTotal;
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
 * Cross-service aggregate endpoint for Phase 3 (collection-rate) and
 * Phase 5 (loss-ratio) reports. Deliberately narrow — just the
 * (scheme, currency, total-billed) shape needed to compose against
 * receipts and claims. Kept off the {@code /reports/billing} path so
 * consumers reading the API don't confuse it with the primary
 * scheme-report surface.
 *
 * <p>Not gated by {@link com.medfund.shared.report.RequiresReport} —
 * this is a service-to-service endpoint, not a user-facing report;
 * gating it would let a tenant admin accidentally disable Phase 3+5
 * reports across the platform by toggling {@code BILLING_REPORT} off.
 * The per-tenant surfaces they consume already carry their own gates.
 */
@RestController
@RequestMapping("/api/v1/reports/aggregate")
@RequiredArgsConstructor
@Tag(name = "Billing aggregate (cross-service)",
        description = "Narrow billing aggregate consumed by Phase 3 collection-rate and Phase 5 loss-ratio reports.")
@SecurityRequirement(name = "bearer-jwt")
public class BillingAggregateController {

    private final BillingReportService billingReportService;
    private final ReportEnvelopeBuilder envelopeBuilder;

    @GetMapping("/billing")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Cross-service billing aggregate — (scheme, currency, total-billed)",
            description = "Consumed by Phase 3 collection-rate and Phase 5 loss-ratio reports. "
                        + "Same 'committed contribution' semantics as /reports/billing/schemes.")
    public Mono<ReportResponse<List<BillingAggregateRow>>> aggregate(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return envelopeBuilder.build(
                ReportKey.BILLING_REPORT,
                period,
                reportingCurrency,
                billingReportService.aggregatePerScheme(period.periodStart(), period.periodEnd()),
                billingReportService.perSchemePerCurrencyTotals(period.periodStart(), period.periodEnd()));
    }
}
