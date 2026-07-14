package com.medfund.user.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TravelPolicyRow(
        UUID id,
        UUID schemeId,
        String schemeName,
        UUID travelerMemberId,
        String travelerMemberName,
        String policyNumber,
        LocalDate tripStartDate,
        LocalDate tripEndDate,
        String destinationBand,
        String coverageLevel,
        String status,
        BigDecimal billingOverrideAmount,
        Instant createdAt,
        Instant updatedAt
) {
}
