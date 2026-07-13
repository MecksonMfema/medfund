package com.medfund.claims.dto;

import java.util.UUID;

/**
 * Query filters for {@code GET /api/v1/tariffs/codes/page}. Sort keys are
 * translated to column names in {@link com.medfund.claims.repository.TariffCodeQueryRepository};
 * unknown keys fall back to {@code code ASC}.
 */
public record TariffCodeFilterParams(
        UUID scheduleId,
        String q,
        UUID categoryId,
        Boolean requiresPreAuth,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
