package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /notes/page}. Member + provider
 * display fields pre-joined server-side so the operational tables
 * render inline without a second lookup.
 */
public record NoteRow(
        UUID id,
        String noteNumber,
        UUID providerId,
        String providerName,
        UUID memberId,
        String memberName,
        String memberNumber,
        String direction,
        String noteType,
        String type,
        UUID reversesNoteId,
        BigDecimal amount,
        String currencyCode,
        String reason,
        String status,
        UUID approvedBy,
        Instant approvedAt,
        Instant postedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy
) {
}
