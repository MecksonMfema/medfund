package com.medfund.user.dto;

import com.medfund.user.entity.CohortLossComponentHistory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CohortLossComponentHistoryResponse(
        UUID id,
        UUID cohortId,
        Instant effectiveAt,
        String movementType,
        BigDecimal amount,
        String currency,
        UUID sourceRunId,
        String reasonNote,
        UUID actorId,
        String actorEmail,
        Instant createdAt
) {
    public static CohortLossComponentHistoryResponse from(CohortLossComponentHistory h) {
        return new CohortLossComponentHistoryResponse(
                h.getId(),
                h.getCohortId(),
                h.getEffectiveAt(),
                h.getMovementType(),
                h.getAmount(),
                h.getCurrency(),
                h.getSourceRunId(),
                h.getReasonNote(),
                h.getActorId(),
                h.getActorEmail(),
                h.getCreatedAt()
        );
    }
}
