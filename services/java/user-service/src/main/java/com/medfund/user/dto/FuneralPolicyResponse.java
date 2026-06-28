package com.medfund.user.dto;

import com.medfund.user.entity.FuneralPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FuneralPolicyResponse(
        UUID id,
        UUID schemeId,
        UUID groupId,
        UUID principalMemberId,
        String policyNumber,
        BigDecimal coverAmount,
        Integer livesCovered,
        String healthDeclaration,
        String status,
        BigDecimal billingOverrideAmount,
        String billingOverrideReason,
        LocalDate billingOverrideEffectiveFrom,
        Instant createdAt,
        Instant updatedAt
) {
    public static FuneralPolicyResponse from(FuneralPolicy p) {
        return new FuneralPolicyResponse(
                p.getId(), p.getSchemeId(), p.getGroupId(), p.getPrincipalMemberId(),
                p.getPolicyNumber(), p.getCoverAmount(), p.getLivesCovered(),
                p.getHealthDeclaration(), p.getStatus(),
                p.getBillingOverrideAmount(), p.getBillingOverrideReason(),
                p.getBillingOverrideEffectiveFrom(),
                p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
