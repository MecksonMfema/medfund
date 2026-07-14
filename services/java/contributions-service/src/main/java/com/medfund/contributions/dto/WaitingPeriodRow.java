package com.medfund.contributions.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /waiting-periods/page}. The scheme
 * name is pre-joined server-side so the operational catalogue table
 * renders inline without a lookup.
 */
public record WaitingPeriodRow(
        UUID id,
        UUID schemeId,
        String schemeName,
        String conditionType,
        Integer waitingDays,
        Short minAge,
        Short maxAge,
        String description,
        Instant createdAt
) {
}
