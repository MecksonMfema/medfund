package com.medfund.finance.dto;

/**
 * Query filters for {@code GET /api/v1/ctc-payments/page}. Sort keys are
 * translated to column names in
 * {@link com.medfund.finance.repository.CtcPaymentQueryRepository};
 * unknown keys fall back to {@code created_at DESC}.
 */
public record CtcPaymentFilterParams(
        Boolean committed,
        String currencyCode,
        String q,
        Boolean systemDrafted,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
