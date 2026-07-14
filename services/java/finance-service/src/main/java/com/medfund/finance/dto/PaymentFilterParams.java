package com.medfund.finance.dto;

import java.util.UUID;

public record PaymentFilterParams(
        String status,
        String paymentType,
        UUID providerId,
        String currencyCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
