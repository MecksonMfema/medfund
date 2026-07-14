package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Row shape returned by GET /advance-payments/page. Provider + member
 *  names pre-joined server-side. */
public record AdvancePaymentRow(
        UUID id,
        UUID paymentId,
        UUID providerId,
        String providerName,
        UUID memberId,
        String memberName,
        String memberNumber,
        BigDecimal amount,
        String currencyCode,
        String paymentMethod,
        String reference,
        String comment,
        Instant recordedAt
) {
}
