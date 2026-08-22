package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.ReinsuranceReviewTask;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReinsuranceReviewTaskResponse(
        UUID id,
        String taskType,
        UUID cessionId,
        UUID recoveryId,
        UUID claimId,
        UUID treatyId,
        String status,
        UUID assigneeUserId,
        OffsetDateTime dueBy,
        String createReason,
        String resolutionNotes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ReinsuranceReviewTaskResponse from(ReinsuranceReviewTask t) {
        return new ReinsuranceReviewTaskResponse(
                t.getId(),
                t.getTaskType(),
                t.getCessionId(),
                t.getRecoveryId(),
                t.getClaimId(),
                t.getTreatyId(),
                t.getStatus(),
                t.getAssigneeUserId(),
                t.getDueBy(),
                t.getCreateReason(),
                t.getResolutionNotes(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
