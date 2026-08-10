package com.medfund.finance.dto;

import java.util.UUID;

/**
 * Query filters for {@code GET /api/v1/notes/page}. Sort keys are
 * translated in {@link com.medfund.finance.repository.NoteQueryRepository};
 * unknown keys fall back to {@code created_at DESC}.
 */
public record NoteFilterParams(
        String status,
        String direction,
        String noteType,
        UUID providerId,
        UUID memberId,
        String currencyCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
