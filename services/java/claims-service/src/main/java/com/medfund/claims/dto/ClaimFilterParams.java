package com.medfund.claims.dto;

import java.util.UUID;

/**
 * Query filters for {@code GET /api/v1/claims/page}. Sort keys are
 * translated to column names in {@link com.medfund.claims.repository.ClaimQueryRepository};
 * unknown keys fall back to {@code submission_date DESC}.
 */
public record ClaimFilterParams(
        String status,
        String claimType,
        String insuranceLine,
        UUID memberId,
        UUID providerId,
        UUID schemeId,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
