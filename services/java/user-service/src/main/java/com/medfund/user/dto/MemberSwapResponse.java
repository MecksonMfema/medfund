package com.medfund.user.dto;

import com.medfund.user.entity.MemberDependantSwap;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MemberSwapResponse(
        UUID id,
        UUID oldMemberId,
        UUID dependantId,
        UUID newMemberId,
        UUID oldDependantId,
        String status,
        LocalDate requestedDate,
        LocalDate effectiveDate,
        String reason,
        String rejectionReason,
        Instant appliedAt
) {
    public static MemberSwapResponse from(MemberDependantSwap e) {
        return new MemberSwapResponse(
                e.getId(),
                e.getOldMemberId(),
                e.getDependantId(),
                e.getNewMemberId(),
                e.getOldDependantId(),
                e.getStatus(),
                e.getRequestedDate(),
                e.getEffectiveDate(),
                e.getReason(),
                e.getRejectionReason(),
                e.getAppliedAt()
        );
    }
}
