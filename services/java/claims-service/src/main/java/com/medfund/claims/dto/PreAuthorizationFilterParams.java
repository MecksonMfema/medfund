package com.medfund.claims.dto;

import java.util.UUID;

/**
 * Query filters for {@code GET /api/v1/pre-authorizations/page}. Sort
 * keys are translated in
 * {@link com.medfund.claims.repository.PreAuthorizationQueryRepository};
 * unknown keys fall back to {@code created_at DESC}.
 */
public record PreAuthorizationFilterParams(
        String status,
        UUID memberId,
        UUID providerId,
        UUID schemeId,
        String tariffCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
