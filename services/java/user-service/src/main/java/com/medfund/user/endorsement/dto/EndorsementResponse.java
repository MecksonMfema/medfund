package com.medfund.user.endorsement.dto;

import com.medfund.user.endorsement.entity.Endorsement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record EndorsementResponse(
        UUID id,
        String reference,
        UUID policyId,
        String policySource,
        String insuranceLine,
        String changeType,
        LocalDate effectiveFrom,
        BigDecimal premiumDelta,
        String currencyCode,
        String reason,
        String status,
        UUID draftActorId,
        String draftActorEmail,
        Instant draftAt,
        UUID approveActorId,
        String approveActorEmail,
        Instant approveAt,
        UUID commitActorId,
        String commitActorEmail,
        Instant commitAt,
        String voidedReason,
        Instant voidedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static EndorsementResponse from(Endorsement e) {
        return new EndorsementResponse(
                e.getId(),
                e.getReference(),
                e.getPolicyId(),
                e.getPolicySource(),
                e.getInsuranceLine(),
                e.getChangeType(),
                e.getEffectiveFrom(),
                e.getPremiumDelta(),
                e.getCurrencyCode(),
                e.getReason(),
                e.getStatus(),
                e.getDraftActorId(),
                e.getDraftActorEmail(),
                e.getDraftAt(),
                e.getApproveActorId(),
                e.getApproveActorEmail(),
                e.getApproveAt(),
                e.getCommitActorId(),
                e.getCommitActorEmail(),
                e.getCommitAt(),
                e.getVoidedReason(),
                e.getVoidedAt(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
