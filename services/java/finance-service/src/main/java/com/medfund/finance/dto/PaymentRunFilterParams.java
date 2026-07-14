package com.medfund.finance.dto;

public record PaymentRunFilterParams(
        String status,
        String currencyCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
