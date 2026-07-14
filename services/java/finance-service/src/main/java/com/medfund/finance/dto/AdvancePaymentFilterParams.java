package com.medfund.finance.dto;

import java.util.UUID;

public record AdvancePaymentFilterParams(
        UUID providerId,
        UUID memberId,
        String currencyCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
