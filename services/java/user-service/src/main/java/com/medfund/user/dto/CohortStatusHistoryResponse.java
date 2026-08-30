package com.medfund.user.dto;

import com.medfund.user.entity.CohortStatusHistory;

import java.time.Instant;
import java.util.UUID;

public record CohortStatusHistoryResponse(
        UUID id,
        UUID cohortId,
        String fromStatus,
        String toStatus,
        String transitionReason,
        String transitionSource,
        UUID sourceRunId,
        Instant effectiveAt,
        String reasonNote,
        UUID actorId,
        String actorEmail,
        Instant createdAt
) {
    public static CohortStatusHistoryResponse from(CohortStatusHistory h) {
        return new CohortStatusHistoryResponse(
                h.getId(),
                h.getCohortId(),
                h.getFromStatus(),
                h.getToStatus(),
                h.getTransitionReason(),
                h.getTransitionSource(),
                h.getSourceRunId(),
                h.getEffectiveAt(),
                h.getReasonNote(),
                h.getActorId(),
                h.getActorEmail(),
                h.getCreatedAt()
        );
    }
}
