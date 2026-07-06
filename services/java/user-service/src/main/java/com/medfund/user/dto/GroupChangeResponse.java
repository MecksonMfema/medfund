package com.medfund.user.dto;

import com.medfund.user.entity.PendingGroupChange;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record GroupChangeResponse(
        UUID id,
        UUID memberId,
        UUID fromGroupId,
        UUID toGroupId,
        String status,
        LocalDate requestedDate,
        LocalDate effectiveDate,
        String reason,
        String rejectionReason,
        Instant appliedAt,
        boolean backdated
) {
    public static GroupChangeResponse from(PendingGroupChange e) {
        return new GroupChangeResponse(
                e.getId(),
                e.getMemberId(),
                e.getFromGroupId(),
                e.getToGroupId(),
                e.getStatus(),
                e.getRequestedDate(),
                e.getEffectiveDate(),
                e.getReason(),
                e.getRejectionReason(),
                e.getAppliedAt(),
                e.getEffectiveDate() != null
                        && e.getEffectiveDate().isBefore(LocalDate.now().withDayOfMonth(1))
        );
    }
}
