package com.medfund.user.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DisabilityPolicyRow(
        UUID id,
        UUID schemeId,
        String schemeName,
        UUID insuredMemberId,
        String insuredMemberName,
        String policyNumber,
        String occupationHazardClass,
        Integer waitingPeriodDays,
        String benefitPeriod,
        BigDecimal monthlyBenefit,
        String status,
        BigDecimal billingOverrideAmount,
        Instant createdAt,
        Instant updatedAt
) {
}
