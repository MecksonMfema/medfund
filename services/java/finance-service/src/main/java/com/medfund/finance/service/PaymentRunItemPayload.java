package com.medfund.finance.service;

/**
 * V075 — inline item payload for the fat {@code medfund.payments.run.executed}
 * event. Consumed by the Go payment-gateway to build one outbound
 * {@code Initiate} call per item; the settled-response replies keyed off
 * {@code itemId} and {@code paymentId}.
 *
 * <p>{@code amount} is a plain-string (not a JSON number) so callers on
 * both sides deserialise without floating-point precision loss.
 */
public record PaymentRunItemPayload(
        String itemId,
        String paymentId,     // may be empty when the item has no linked payment
        String providerId,    // may be empty (member-payee runs)
        String memberId,      // may be empty (provider-payee runs)
        String amount,        // BigDecimal.toPlainString()
        String currencyCode
) {}
