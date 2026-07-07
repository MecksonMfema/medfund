package com.medfund.shared.validation;

import java.time.LocalDate;

/**
 * Cycle-boundary snap helpers for service-layer persistence. Belt-and-braces
 * with {@link FirstOfMonth} / {@link EndOfMonth}: the annotations reject
 * mis-shaped API input at the boundary; these helpers normalize legacy
 * data + values assembled inside the service (e.g. defaulting to today).
 *
 * <p>Null in → null out on every helper so callers don't need to guard.
 */
public final class DateSnaps {
    private DateSnaps() { }

    /** Snap to the 1st of the month; null passes through. */
    public static LocalDate toFirstOfMonth(LocalDate d) {
        return d == null ? null : d.withDayOfMonth(1);
    }

    /** Snap to the last day of the month; null passes through. */
    public static LocalDate toEndOfMonth(LocalDate d) {
        return d == null ? null : d.withDayOfMonth(d.lengthOfMonth());
    }
}
