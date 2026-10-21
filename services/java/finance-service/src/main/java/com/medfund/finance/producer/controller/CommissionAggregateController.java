package com.medfund.finance.producer.controller;

import com.medfund.finance.producer.dto.CommissionAggregateRow;
import com.medfund.finance.producer.repository.CommissionAggregateQueryRepository.AggregateDimension;
import com.medfund.finance.producer.service.CommissionAggregateService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 18 K7 — paid-commission aggregate feed for the executive KPI
 * composer's EXPENSE_RATIO ("Acquisition Ratio") numerator. Gated by
 * {@link Permissions#FINANCE_VIEW_SUBLEDGER} because the feed lives inside
 * finance-service (local composer feed) — cross-service peer aggregates
 * (Phases 2/3) use their own service's read-aggregate constants.
 *
 * <p>Native-currency per {@code (currency, [insurance line], [producer])};
 * K7 defers the acquisition-vs-servicing classifier so this endpoint sums
 * all PAID rows regardless of type.
 */
@RestController
@RequestMapping("/api/v1/reports/aggregate")
@RequiredArgsConstructor
@Tag(name = "Commission aggregates (cross-service)",
        description = "Paid-commission aggregate consumed by the Phase 18 executive KPI composer's "
                    + "acquisition-ratio (EXPENSE_RATIO) numerator.")
@SecurityRequirement(name = "bearer-jwt")
public class CommissionAggregateController {

    private final CommissionAggregateService service;

    @GetMapping("/commissions")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Paid commission per (currency[, line][, producer]) for a period",
            description = "K7 aggregate. Sums commission_transaction WHERE status='PAID' AND "
                        + "paid_at in [periodStart, periodEnd). Native per-currency; no conversion. "
                        + "Acquisition-vs-servicing classifier deferred to Phase 18.5.")
    public Mono<List<CommissionAggregateRow>> commissions(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(defaultValue = "TENANT") AggregateDimension dimension,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID producerId) {
        return service.aggregatePaid(
                LocalDate.parse(periodStart),
                LocalDate.parse(periodEnd),
                dimension,
                insuranceLine,
                producerId);
    }
}
