package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Read-only "what is today" fact, inserted by the engine consumer so date-
 * dependent rules don't need to read the system clock at evaluation time.
 *
 * <p>Useful for billing-day rules, payment-run schedules, year-boundary
 * resets — anywhere a rule wants to compare against "today" without having
 * the calling service substitute its own date.
 */
@Getter
@Setter
@NoArgsConstructor
public class TimeFact {

    /** The "now" date the engine is being asked to evaluate against. */
    private LocalDate today;

    /** 1..31. Convenient for "billed on the {@code N}th" rules. */
    private int dayOfMonth;
    /** 1..12. */
    private int monthOfYear;
    private int year;
    private DayOfWeek dayOfWeek;

    public static TimeFact of(LocalDate date) {
        TimeFact t = new TimeFact();
        t.today = date;
        t.dayOfMonth = date.getDayOfMonth();
        t.monthOfYear = date.getMonthValue();
        t.year = date.getYear();
        t.dayOfWeek = date.getDayOfWeek();
        return t;
    }
}
