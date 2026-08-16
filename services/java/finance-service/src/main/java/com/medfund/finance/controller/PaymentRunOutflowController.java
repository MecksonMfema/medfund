package com.medfund.finance.controller;

import com.medfund.finance.dto.PlannedOutflowRow;
import com.medfund.finance.repository.PaymentRunQueryRepository;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportingCurrencyResolver;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import com.medfund.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8 cash-flow feed — planned payouts from draft/approved payment
 * runs, item level. Consumed only by the contributions-service
 * 13-week cash-flow forecast (D8-5): all ISO-week bucketing happens on
 * the consuming side so inflow and outflow both use the same week
 * boundaries.
 *
 * <p>Deliberately not a report-gated endpoint: this is a narrow
 * service-to-service aggregate, not an operator-facing report, and a
 * {@link RequiresReport} check against {@code report_access} would break
 * the cross-service fanout (mirrors the Phase 3 receipts-aggregate
 * rationale). Permission is still enforced per tenant; every tenant
 * gets the same narrow feed.
 */
@RestController
@RequestMapping("/api/v1/reports/aggregate/outflows")
@RequiredArgsConstructor
@Tag(name = "Cash-flow outflows",
        description = "Planned payouts from draft/approved payment runs, item level — the Phase 8 "
                    + "cash-flow forecast's outflow side.")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentRunOutflowController {

    private final PaymentRunQueryRepository paymentRunQueryRepository;
    private final ReportingCurrencyResolver currencyResolver;

    @GetMapping
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Planned outflows for a window",
            description = "Item-level rows for draft/approved runs whose created_at falls in "
                        + "[periodStart, periodEnd]. No aggregation here — the consuming forecast "
                        + "buckets by ISO week.")
    public Mono<ReportResponse<List<PlannedOutflowRow>>> plannedOutflows(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency) {
        ReportPeriod period = ReportPeriod.parseFromQueryParams(periodStart, periodEnd, null);
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return currencyResolver.resolve(tenantId, reportingCurrency)
                    .flatMap(currency -> paymentRunQueryRepository
                            .plannedOutflows(period.periodStart(), period.periodEnd())
                            .collectList()
                            .map(rows -> new ReportResponse<>(
                                    ReportKey.CASH_FLOW_FORECAST_13W.name(),
                                    period,
                                    currency,
                                    rows,
                                    Map.of(),
                                    Map.of(),
                                    List.of(),
                                    OffsetDateTime.now())));
        });
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }
}
