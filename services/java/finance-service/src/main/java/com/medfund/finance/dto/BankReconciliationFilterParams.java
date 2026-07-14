package com.medfund.finance.dto;

public record BankReconciliationFilterParams(
        String status,
        String currencyCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
