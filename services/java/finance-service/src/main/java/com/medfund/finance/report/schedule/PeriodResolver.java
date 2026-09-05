package com.medfund.finance.report.schedule;

import com.medfund.shared.report.ReportPeriodShape;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Phase 17 §A.2 — derives (periodStart, periodEnd, asOf) from the fire time
 * given the report's {@link ReportPeriodShape} and the schedule's cadence.
 * Deliberately a stateless static helper — the resolver is pure logic and
 * needs no injection.
 *
 * <p>{@code PREVIOUS_COMPLETE_PERIOD}: the fire covers the most-recently-
 * completed calendar unit (last week Mon..Sun, last month, last quarter,
 * last year). {@code AS_OF_FIRE_TIME}: all three dates equal firedAt (local).
 */
public final class PeriodResolver {

    private PeriodResolver() {}

    public static PeriodTriple resolve(ReportPeriodShape shape, String cadence,
                                       OffsetDateTime firedAt, ZoneId zone) {
        ZonedDateTime local = firedAt.atZoneSameInstant(zone);
        LocalDate today = local.toLocalDate();
        return switch (shape) {
            case AS_OF_FIRE_TIME -> new PeriodTriple(today, today, today);
            case PREVIOUS_COMPLETE_PERIOD -> switch (cadence) {
                case "WEEKLY" -> weeklyPrevious(today);
                case "MONTHLY" -> monthlyPrevious(today);
                case "QUARTERLY" -> quarterlyPrevious(today);
                case "ANNUAL" -> annualPrevious(today);
                default -> throw new IllegalArgumentException(
                        "Unknown cadence for PREVIOUS_COMPLETE_PERIOD: " + cadence);
            };
        };
    }

    /** Previous complete Mon..Sun. asOf = today. */
    private static PeriodTriple weeklyPrevious(LocalDate today) {
        LocalDate thisMonday = today.with(DayOfWeek.MONDAY);
        LocalDate lastMonday = thisMonday.minusWeeks(1);
        LocalDate lastSunday = lastMonday.plusDays(6);
        return new PeriodTriple(lastMonday, lastSunday, today);
    }

    /** Previous complete calendar month. asOf = today. */
    private static PeriodTriple monthlyPrevious(LocalDate today) {
        LocalDate firstOfThisMonth = today.withDayOfMonth(1);
        LocalDate firstOfLastMonth = firstOfThisMonth.minusMonths(1);
        LocalDate lastOfLastMonth = firstOfThisMonth.minusDays(1);
        return new PeriodTriple(firstOfLastMonth, lastOfLastMonth, today);
    }

    /** Previous complete calendar quarter. asOf = today. */
    private static PeriodTriple quarterlyPrevious(LocalDate today) {
        int m = today.getMonthValue();
        int currentQuarterFirstMonth = ((m - 1) / 3) * 3 + 1;
        LocalDate firstOfCurrentQuarter = LocalDate.of(today.getYear(), currentQuarterFirstMonth, 1);
        LocalDate firstOfPrevQuarter = firstOfCurrentQuarter.minusMonths(3);
        LocalDate lastOfPrevQuarter = firstOfCurrentQuarter.minusDays(1);
        return new PeriodTriple(firstOfPrevQuarter, lastOfPrevQuarter, today);
    }

    /** Previous complete calendar year. asOf = today. */
    private static PeriodTriple annualPrevious(LocalDate today) {
        LocalDate firstOfThisYear = today.withDayOfYear(1);
        LocalDate firstOfLastYear = firstOfThisYear.minusYears(1);
        LocalDate lastOfLastYear = firstOfThisYear.minusDays(1);
        return new PeriodTriple(firstOfLastYear, lastOfLastYear, today);
    }

    public record PeriodTriple(LocalDate periodStart, LocalDate periodEnd, LocalDate asOf) {}
}
