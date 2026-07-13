package com.medfund.claims.dto;

/**
 * Query filters for {@code GET /api/v1/tariffs/modifiers/page}. Sort
 * keys are translated in
 * {@link com.medfund.claims.repository.TariffModifierQueryRepository};
 * unknown keys fall back to {@code code ASC}.
 */
public record TariffModifierFilterParams(
        Boolean activeOnly,
        String adjustmentType,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
