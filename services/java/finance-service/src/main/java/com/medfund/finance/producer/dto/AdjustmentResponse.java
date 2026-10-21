package com.medfund.finance.producer.dto;

import com.medfund.finance.producer.entity.CommissionAdjustment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AdjustmentResponse(
        UUID id,
        String reference,
        UUID targetCommissionTransactionId,
        String adjustmentType,
        BigDecimal adjustmentAmount,
        String nativeCurrency,
        String justification,
        String status,
        UUID actorId,
        String actorEmail,
        UUID approverActorId,
        String approverActorEmail,
        OffsetDateTime approvedAt,
        OffsetDateTime committedAt,
        UUID committedTxnId,
        OffsetDateTime voidedAt,
        String voidedReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static AdjustmentResponse from(CommissionAdjustment a) {
        return new AdjustmentResponse(
                a.getId(),
                a.getReference(),
                a.getTargetCommissionTransactionId(),
                a.getAdjustmentType(),
                a.getAdjustmentAmount(),
                a.getNativeCurrency(),
                a.getJustification(),
                a.getStatus(),
                a.getActorId(),
                a.getActorEmail(),
                a.getApproverActorId(),
                a.getApproverActorEmail(),
                a.getApprovedAt(),
                a.getCommittedAt(),
                a.getCommittedTxnId(),
                a.getVoidedAt(),
                a.getVoidedReason(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
