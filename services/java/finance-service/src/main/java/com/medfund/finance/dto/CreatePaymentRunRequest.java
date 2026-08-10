package com.medfund.finance.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for POST /api/v1/payment-runs.
 *
 * <p>{@code payeeType} — V072. Defaults to {@code PROVIDER} for
 * backwards compatibility; requests with {@code MEMBER} produce a
 * homogeneous member-payee run whose items are enumerated from
 * {@code member_balances}.
 */
public record CreatePaymentRunRequest(
        @NotBlank String currencyCode,
        String description,
        String payeeType
) {
    public CreatePaymentRunRequest {
        if (payeeType == null || payeeType.isBlank()) {
            payeeType = "PROVIDER";
        } else {
            payeeType = payeeType.toUpperCase();
        }
    }
}
