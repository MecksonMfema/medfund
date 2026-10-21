package com.medfund.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload for POST /api/v1/payment-runs.
 *
 * <p>{@code payeeType} — V072. Defaults to {@code PROVIDER} for
 * backwards compatibility; requests with {@code MEMBER} produce a
 * homogeneous member-payee run whose items are enumerated from
 * {@code member_balances}. Phase 11 §A adds {@code PRODUCER} — items
 * come from {@code commission_transaction} rows in the requested period
 * whose producer's {@code home_currency} matches the run currency.
 *
 * <p>{@code sourceBankAccountId} — V075. The tenant bank account this
 * run debits. Must exist and its {@code currency_code} must match the
 * requested {@code currencyCode}.
 *
 * <p>{@code periodStart} / {@code periodEnd} — required when
 * {@code payeeType='PRODUCER'} (validated at the service layer with a
 * 400 response). Ignored for PROVIDER + MEMBER, which drain outstanding
 * balances irrespective of period. Snap to 1st-of-month /
 * last-day-of-month per {@code feedback_effective_date_snap}.
 */
public record CreatePaymentRunRequest(
        @NotBlank String currencyCode,
        String description,
        String payeeType,
        @NotNull UUID sourceBankAccountId,
        LocalDate periodStart,
        LocalDate periodEnd
) {
    public CreatePaymentRunRequest {
        if (payeeType == null || payeeType.isBlank()) {
            payeeType = "PROVIDER";
        } else {
            payeeType = payeeType.toUpperCase();
        }
    }

    /** Backwards-compatible 4-arg factory for callers/tests that predate
     *  the producer-payout period fields. */
    public CreatePaymentRunRequest(String currencyCode, String description,
                                   String payeeType, UUID sourceBankAccountId) {
        this(currencyCode, description, payeeType, sourceBankAccountId, null, null);
    }
}
