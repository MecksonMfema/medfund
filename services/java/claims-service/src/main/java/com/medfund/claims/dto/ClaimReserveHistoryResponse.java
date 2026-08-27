package com.medfund.claims.dto;

import com.medfund.claims.entity.ClaimReserveHistory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read model for the reserve-history tab and the incurred-triangle feed.
 * {@code actorId} is deliberately not surfaced — the audit log carries the
 * UUID; the reserve UI only needs a human-readable actor.
 */
public record ClaimReserveHistoryResponse(
        UUID id,
        UUID claimId,
        BigDecimal reservedAmount,
        OffsetDateTime effectiveAt,
        String actorEmail,
        String reasonNote
) {
    public static ClaimReserveHistoryResponse from(ClaimReserveHistory row) {
        return new ClaimReserveHistoryResponse(
                row.getId(),
                row.getClaimId(),
                row.getReservedAmount(),
                row.getEffectiveAt(),
                row.getActorEmail(),
                row.getReasonNote()
        );
    }
}
