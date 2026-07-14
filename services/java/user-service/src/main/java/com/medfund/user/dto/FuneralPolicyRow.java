package com.medfund.user.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FuneralPolicyRow(
        UUID id,
        UUID schemeId,
        String schemeName,
        UUID principalMemberId,
        String principalMemberName,
        String policyNumber,
        BigDecimal coverAmount,
        Integer livesCovered,
        String status,
        BigDecimal billingOverrideAmount,
        Instant createdAt,
        Instant updatedAt
) {
}
