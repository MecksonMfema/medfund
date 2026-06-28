package com.medfund.user.dto;

import com.medfund.user.entity.LifePolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LifePolicyResponse(
        UUID id,
        UUID schemeId,
        UUID groupId,
        UUID insuredMemberId,
        String policyNumber,
        BigDecimal sumAssured,
        String occupationHazardClass,
        Integer termMonths,
        String status,
        BigDecimal billingOverrideAmount,
        String billingOverrideReason,
        LocalDate billingOverrideEffectiveFrom,
        Instant createdAt,
        Instant updatedAt
) {
    public static LifePolicyResponse from(LifePolicy p) {
        return new LifePolicyResponse(
                p.getId(), p.getSchemeId(), p.getGroupId(), p.getInsuredMemberId(),
                p.getPolicyNumber(), p.getSumAssured(), p.getOccupationHazardClass(),
                p.getTermMonths(), p.getStatus(),
                p.getBillingOverrideAmount(), p.getBillingOverrideReason(),
                p.getBillingOverrideEffectiveFrom(),
                p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
