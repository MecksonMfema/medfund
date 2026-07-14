package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /payments/page}. Provider name
 * pre-joined server-side so the operational payments table renders
 * inline without a lookup.
 */
public record PaymentRow(
        UUID id,
        String paymentNumber,
        UUID providerId,
        String providerName,
        BigDecimal amount,
        String currencyCode,
        String paymentType,
        String status,
        String paymentMethod,
        String reference,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {
}
