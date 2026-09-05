package com.medfund.claims.siu.dto;

/**
 * One point in the 12-month {@code /reports/fraud/trend} series (§B Phase 11).
 * Cases opened / confirmed / dismissed per calendar month keyed on
 * {@code opened_at} (opened) or {@code closed_at} (confirmed/dismissed).
 *
 * <p>{@code month} is the first day of the month as {@code YYYY-MM-01}
 * so the Angular line-chart component can round-trip it as an ISO date
 * on the x-axis. Blank months in the range still emit a row with zeroes
 * so the trend line doesn't render as a gap.
 */
public record TrendPoint(
        String month,
        long casesOpened,
        long confirmedCount,
        long dismissedCount
) {
}
