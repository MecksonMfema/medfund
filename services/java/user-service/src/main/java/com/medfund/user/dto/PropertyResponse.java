package com.medfund.user.dto;

import com.medfund.user.entity.Property;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PropertyResponse(
        UUID id,
        UUID schemeId,
        UUID groupId,
        UUID ownerMemberId,
        String propertyName,
        String address,
        BigDecimal sumInsured,
        String constructionType,
        String roofType,
        String locationRiskBand,
        Integer securityFeaturesCount,
        Integer propertyAgeYears,
        String occupancy,
        String status,
        BigDecimal billingOverrideAmount,
        String billingOverrideReason,
        LocalDate billingOverrideEffectiveFrom,
        Instant createdAt,
        Instant updatedAt
) {
    public static PropertyResponse from(Property p) {
        return new PropertyResponse(
                p.getId(), p.getSchemeId(), p.getGroupId(), p.getOwnerMemberId(),
                p.getPropertyName(), p.getAddress(), p.getSumInsured(),
                p.getConstructionType(), p.getRoofType(), p.getLocationRiskBand(),
                p.getSecurityFeaturesCount(), p.getPropertyAgeYears(), p.getOccupancy(),
                p.getStatus(),
                p.getBillingOverrideAmount(), p.getBillingOverrideReason(),
                p.getBillingOverrideEffectiveFrom(),
                p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
