package com.medfund.contributions.premium.controller;

import com.medfund.contributions.premium.dto.PremiumEarnedAggregateRow;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository.Dimension;
import com.medfund.contributions.premium.service.PremiumAggregateService;
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
 * Phase 18 K8 — earned-premium aggregate feed for the executive KPI
 * composer in finance-service. Ungated by {@code @RequiresReport} per
 * parent-plan invariant #2 exception for cross-service data feeds
 * (mirrors {@code BillingAggregateController} + {@code
 * ReceiptsAggregateController}). Toggle enforcement lives on the consumer
 * side (finance-service ExecutiveKpiController).
 *
 * <p>Rows are native-currency (parent-plan G25); the composer converts
 * downstream via FxRateReader.convert. Fully-closed periods only —
 * unclosed earning_schedule rows contribute zero rows and the KPI page
 * surfaces the caveat.
 */
@RestController
@RequestMapping("/api/v1/reports/aggregate")
@RequiredArgsConstructor
@Tag(name = "Premium aggregates (cross-service)",
        description = "Cross-service aggregate feed consumed by the Phase 18 executive KPI composer. "
                    + "Earned-premium sums come from Phase 12 earning_schedule closed rows.")
@SecurityRequirement(name = "bearer-jwt")
public class PremiumAggregateController {

    private final PremiumAggregateService service;

    @GetMapping("/premium-earned")
    @RequiresPermission(Permissions.CONTRIBUTIONS_READ_AGGREGATE)
    @Operation(summary = "Earned premium per (currency[, line][, scheme]) for a period",
            description = "SUM(earning_schedule.earned_at_period_end) for fully-closed periods "
                        + "with period_end in [periodStart, periodEnd). Native per-currency; no "
                        + "conversion. Nightly PremiumEarningExecutor guarantees closed periods "
                        + "are populated — unclosed periods contribute zero rows.")
    public Mono<List<PremiumEarnedAggregateRow>> earnedPremium(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(defaultValue = "TENANT") Dimension dimension,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId) {
        return service.earnedPremium(
                LocalDate.parse(periodStart),
                LocalDate.parse(periodEnd),
                dimension,
                insuranceLine,
                schemeId);
    }
}
