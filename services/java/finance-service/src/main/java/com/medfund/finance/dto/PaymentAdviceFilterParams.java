package com.medfund.finance.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentAdviceFilterParams(
        UUID paymentRunId,
        UUID providerId,
        UUID memberId,
        String payeeType,
        String status,
        String currencyCode,
        Instant periodStart,
        Instant periodEnd,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
