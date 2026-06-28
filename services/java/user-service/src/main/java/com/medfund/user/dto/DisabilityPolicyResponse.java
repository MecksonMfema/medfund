package com.medfund.user.dto;

import com.medfund.user.entity.DisabilityPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DisabilityPolicyResponse(
        UUID id,
        UUID schemeId,
        UUID groupId,
        UUID insuredMemberId,
        String policyNumber,
        String occupationHazardClass,
        Integer waitingPeriodDays,
        String benefitPeriod,
        BigDecimal monthlyBenefit,
        String status,
        BigDecimal billingOverrideAmount,
        String billingOverrideReason,
        LocalDate billingOverrideEffectiveFrom,
        Instant createdAt,
        Instant updatedAt
) {
    public static DisabilityPolicyResponse from(DisabilityPolicy d) {
        return new DisabilityPolicyResponse(
                d.getId(), d.getSchemeId(), d.getGroupId(), d.getInsuredMemberId(),
                d.getPolicyNumber(), d.getOccupationHazardClass(), d.getWaitingPeriodDays(),
                d.getBenefitPeriod(), d.getMonthlyBenefit(), d.getStatus(),
                d.getBillingOverrideAmount(), d.getBillingOverrideReason(),
                d.getBillingOverrideEffectiveFrom(),
                d.getCreatedAt(), d.getUpdatedAt()
        );
    }
}
