package com.medfund.user.dto;

import com.medfund.user.entity.TravelPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TravelPolicyResponse(
        UUID id,
        UUID schemeId,
        UUID groupId,
        UUID travelerMemberId,
        String policyNumber,
        LocalDate tripStartDate,
        LocalDate tripEndDate,
        String destinationBand,
        String coverageLevel,
        Boolean preExistingDeclared,
        String status,
        BigDecimal billingOverrideAmount,
        String billingOverrideReason,
        LocalDate billingOverrideEffectiveFrom,
        Instant createdAt,
        Instant updatedAt
) {
    public static TravelPolicyResponse from(TravelPolicy t) {
        return new TravelPolicyResponse(
                t.getId(), t.getSchemeId(), t.getGroupId(), t.getTravelerMemberId(),
                t.getPolicyNumber(), t.getTripStartDate(), t.getTripEndDate(),
                t.getDestinationBand(), t.getCoverageLevel(), t.getPreExistingDeclared(),
                t.getStatus(),
                t.getBillingOverrideAmount(), t.getBillingOverrideReason(),
                t.getBillingOverrideEffectiveFrom(),
                t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
