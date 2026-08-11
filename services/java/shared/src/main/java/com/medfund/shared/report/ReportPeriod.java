package com.medfund.shared.report;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Value object describing the reporting window a report was generated for.
 * Carried on every {@link ReportResponse} so the client can render the
 * "As of period X-Y" header without re-echoing the query params.
 *
 * <p>Range semantics are inclusive on both sides — {@code periodStart} is
 * the first day covered, {@code periodEnd} is the last day covered.
 */
public record ReportPeriod(
        LocalDate periodStart,
        LocalDate periodEnd,
        PeriodGrain grain
) {

    /** Aggregation grain — hint to the client for chart bucketing. */
    public enum PeriodGrain { DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY, CUSTOM }

    /**
     * Build a period from raw request-param strings. Both dates required;
     * grain is inferred from the span when omitted (day → DAILY, ≤ 7d →
     * WEEKLY, ≤ 31d → MONTHLY, ≤ 92d → QUARTERLY, else YEARLY).
     */
    public static ReportPeriod parseFromQueryParams(String periodStart, String periodEnd, String grain) {
        if (periodStart == null || periodStart.isBlank() || periodEnd == null || periodEnd.isBlank()) {
            throw new IllegalArgumentException("periodStart and periodEnd are required");
        }
        LocalDate start = LocalDate.parse(periodStart.trim());
        LocalDate end   = LocalDate.parse(periodEnd.trim());
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("periodEnd must not be before periodStart");
        }
        PeriodGrain g = grain != null && !grain.isBlank()
                ? PeriodGrain.valueOf(grain.trim().toUpperCase())
                : inferGrain(start, end);
        return new ReportPeriod(start, end, g);
    }

    private static PeriodGrain inferGrain(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days <= 1)  return PeriodGrain.DAILY;
        if (days <= 7)  return PeriodGrain.WEEKLY;
        if (days <= 31) return PeriodGrain.MONTHLY;
        if (days <= 92) return PeriodGrain.QUARTERLY;
        return PeriodGrain.YEARLY;
    }
}
