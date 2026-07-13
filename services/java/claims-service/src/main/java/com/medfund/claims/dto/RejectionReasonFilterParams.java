package com.medfund.claims.dto;

/**
 * Query filters for {@code GET /api/v1/rejection-reasons/page}. Sort
 * keys are translated in
 * {@link com.medfund.claims.repository.RejectionReasonQueryRepository};
 * unknown keys fall back to {@code code ASC}.
 */
public record RejectionReasonFilterParams(
        Boolean activeOnly,
        String category,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
