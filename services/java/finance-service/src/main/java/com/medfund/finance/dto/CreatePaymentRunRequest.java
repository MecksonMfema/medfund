package com.medfund.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Payload for POST /api/v1/payment-runs.
 *
 * <p>{@code payeeType} — V072. Defaults to {@code PROVIDER} for
 * backwards compatibility; requests with {@code MEMBER} produce a
 * homogeneous member-payee run whose items are enumerated from
 * {@code member_balances}.
 *
 * <p>{@code sourceBankAccountId} — V075. The tenant bank account this
 * run debits. Must exist and its {@code currency_code} must match the
 * requested {@code currencyCode}.
 */
public record CreatePaymentRunRequest(
        @NotBlank String currencyCode,
        String description,
        String payeeType,
        @NotNull UUID sourceBankAccountId
) {
    public CreatePaymentRunRequest {
        if (payeeType == null || payeeType.isBlank()) {
            payeeType = "PROVIDER";
        } else {
            payeeType = payeeType.toUpperCase();
        }
    }
}
