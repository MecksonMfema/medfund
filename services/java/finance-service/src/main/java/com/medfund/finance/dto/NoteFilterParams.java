package com.medfund.finance.dto;

public record NoteFilterParams(
        String currencyCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
