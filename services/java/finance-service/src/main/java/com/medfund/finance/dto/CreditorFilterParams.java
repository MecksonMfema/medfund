package com.medfund.finance.dto;

/**
 * Filters + paging + sort key/direction for GET /api/v1/creditors/page.
 * {@code subjectType} is nullable — null (or "BOTH") returns both
 * PROVIDER and MEMBER halves; "PROVIDER" or "MEMBER" pins the query
 * to one half.
 */
public record CreditorFilterParams(
        String subjectType,     // "PROVIDER" | "MEMBER" | "BOTH" — null = BOTH
        String currencyCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
