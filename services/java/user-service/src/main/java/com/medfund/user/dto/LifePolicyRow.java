package com.medfund.user.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LifePolicyRow(
        UUID id,
        UUID schemeId,
        String schemeName,
        UUID insuredMemberId,
        String insuredMemberName,
        String policyNumber,
        BigDecimal sumAssured,
        String occupationHazardClass,
        Integer termMonths,
        String status,
        BigDecimal billingOverrideAmount,
        Instant createdAt,
        Instant updatedAt
) {
}
