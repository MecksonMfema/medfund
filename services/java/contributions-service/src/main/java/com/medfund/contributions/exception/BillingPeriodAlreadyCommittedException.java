package com.medfund.contributions.exception;

import java.time.LocalDate;

/**
 * Thrown when a billing commit is attempted for a (period, line) pair
 * that already has contribution rows from a prior commit. The
 * commit-cooldown timer (see {@link BillingCooldownException}) only
 * stops accidental double-clicks; this exception stops a deliberate
 * second commit for the same month even if hours have passed.
 *
 * <p>Mapped to HTTP 409 with an {@code existingCount} property so the
 * wizard can show "this month already has N contributions — view them
 * instead of re-committing."
 */
public class BillingPeriodAlreadyCommittedException extends RuntimeException {

    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final String insuranceLine;
    private final long existingCount;

    public BillingPeriodAlreadyCommittedException(LocalDate periodStart, LocalDate periodEnd,
                                                   String insuranceLine, long existingCount) {
        super(buildMessage(periodStart, periodEnd, insuranceLine, existingCount));
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.insuranceLine = insuranceLine;
        this.existingCount = existingCount;
    }

    private static String buildMessage(LocalDate start, LocalDate end, String line, long count) {
        String lineLabel = (line == null || line.isBlank()) ? "this line" : line;
        return String.format(
                "Billing already committed for %s — %s to %s already has %d contribution row(s). "
                        + "Delete or reverse the existing commit before generating again.",
                lineLabel, start, end, count);
    }

    public LocalDate getPeriodStart()  { return periodStart; }
    public LocalDate getPeriodEnd()    { return periodEnd; }
    public String getInsuranceLine()   { return insuranceLine; }
    public long getExistingCount()     { return existingCount; }
}
