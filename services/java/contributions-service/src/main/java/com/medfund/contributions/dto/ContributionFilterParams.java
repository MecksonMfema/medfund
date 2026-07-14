package com.medfund.contributions.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Query filters for {@code GET /api/v1/contributions/page}. Sort keys
 * are translated in
 * {@link com.medfund.contributions.repository.ContributionQueryRepository};
 * unknown keys fall back to {@code period_start DESC}.
 */
public record ContributionFilterParams(
        String status,
        UUID memberId,
        UUID groupId,
        UUID schemeId,
        String currencyCode,
        LocalDate periodStartFrom,
        LocalDate periodStartTo,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
