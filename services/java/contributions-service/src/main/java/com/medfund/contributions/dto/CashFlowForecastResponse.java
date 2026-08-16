package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase 8 13-week cash-flow forecast payload. Per currency, a
 * {@link CurrencySeries} over the window's ISO weeks — inflow from
 * unpaid invoices (D8-4), outflow from draft/approved payment runs
 * (D8-5). Never cross-currency: each series is native-currency only,
 * and the envelope carries no FX rates because forward rates don't
 * exist in {@code public.exchange_rates} (D8-7).
 */
public record CashFlowForecastResponse(
        LocalDate asOf,
        int rollingWeeks,
        LocalDate windowStart,
        LocalDate windowEnd,
        List<CurrencySeries> series
) {

    /**
     * One currency's weekly strip. Buckets cover the whole window so the
     * treasurer sees a full axis even where a week has zero inflow or
     * zero outflow; {@code totalNet} is {@code totalInflow - totalOutflow}.
     */
    public record CurrencySeries(
            String currencyCode,
            BigDecimal totalInflow,
            BigDecimal totalOutflow,
            BigDecimal totalNet,
            List<WeekBucket> buckets
    ) {}

    public record WeekBucket(
            LocalDate weekStart,
            BigDecimal inflow,
            BigDecimal outflow,
            BigDecimal net
    ) {}
}
