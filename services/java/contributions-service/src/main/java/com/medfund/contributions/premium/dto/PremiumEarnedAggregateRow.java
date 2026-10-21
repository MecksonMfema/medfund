package com.medfund.contributions.premium.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the /aggregate/premium-earned feed — a group-by result from
 * SUM(earning_schedule.earned_at_period_end) over the requested period. Rows
 * stay native-currency (never converted) per parent-plan G25; the KPI
 * composer performs any reporting-currency conversion downstream via
 * FxRateReader.convert. schemeId / schemeName are nullable when the query
 * does not group by scheme (dimension = TENANT or LINE); insuranceLine is
 * nullable when dimension = TENANT.
 */
public record PremiumEarnedAggregateRow(
        UUID schemeId,
        String schemeName,
        String insuranceLine,
        String currencyCode,
        BigDecimal earnedPremium,
        long rowCount) {
}
