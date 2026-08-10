package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /payments/page}. Payee name is
 * pre-joined server-side (provider display name OR member first + last)
 * so the operational payments table renders inline without a lookup.
 * {@code payeeType} disambiguates which side of the XOR is populated.
 */
public record PaymentRow(
        UUID id,
        String paymentNumber,
        UUID providerId,
        String providerName,
        UUID memberId,
        String memberName,
        String payeeType,
        String payeeName,
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
