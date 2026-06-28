package com.medfund.contributions.exception;

import java.time.LocalDate;

/**
 * Thrown when an operator tries to revoke a billing period that falls
 * outside the allowed window. Today, revoke is only permitted for the
 * calendar month immediately following the current month — once that
 * month starts, the contributions are "active" and any correction
 * has to go through the (out-of-scope, future) corrections flow.
 *
 * <p>Mapped to HTTP 422 with the requested window + the allowed
 * window so the wizard can explain the rule rather than just rejecting.
 */
public class BillingNotRevocableException extends RuntimeException {

    private final LocalDate requestedPeriodStart;
    private final LocalDate allowedPeriodStart;

    public BillingNotRevocableException(LocalDate requestedPeriodStart, LocalDate allowedPeriodStart) {
        super(buildMessage(requestedPeriodStart, allowedPeriodStart));
        this.requestedPeriodStart = requestedPeriodStart;
        this.allowedPeriodStart   = allowedPeriodStart;
    }

    private static String buildMessage(LocalDate requested, LocalDate allowed) {
        return String.format(
                "Revoke is only allowed for the month immediately following today (%s). "
                        + "The requested period starts %s — use the corrections flow for "
                        + "anything older than that.",
                allowed, requested);
    }

    public LocalDate getRequestedPeriodStart() { return requestedPeriodStart; }
    public LocalDate getAllowedPeriodStart()   { return allowedPeriodStart; }
}
