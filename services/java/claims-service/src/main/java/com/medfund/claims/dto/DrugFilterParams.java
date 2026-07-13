package com.medfund.claims.dto;

/**
 * Query filters for {@code GET /api/v1/drugs/page}. Sort keys are
 * translated in {@link com.medfund.claims.repository.DrugQueryRepository};
 * unknown keys fall back to {@code drug_name ASC}.
 */
public record DrugFilterParams(
        Boolean activeOnly,
        String drugType,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
