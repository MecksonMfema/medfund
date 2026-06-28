package com.medfund.user.dto;

import com.medfund.user.entity.Vehicle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        UUID schemeId,
        UUID groupId,
        UUID ownerMemberId,
        String registrationNumber,
        String make,
        String model,
        Integer year,
        BigDecimal vehicleValue,
        String bodyType,
        String usageType,
        String status,
        BigDecimal billingOverrideAmount,
        String billingOverrideReason,
        LocalDate billingOverrideEffectiveFrom,
        Instant createdAt,
        Instant updatedAt
) {
    public static VehicleResponse from(Vehicle v) {
        return new VehicleResponse(
                v.getId(), v.getSchemeId(), v.getGroupId(), v.getOwnerMemberId(),
                v.getRegistrationNumber(), v.getMake(), v.getModel(), v.getYear(),
                v.getVehicleValue(), v.getBodyType(), v.getUsageType(), v.getStatus(),
                v.getBillingOverrideAmount(), v.getBillingOverrideReason(),
                v.getBillingOverrideEffectiveFrom(),
                v.getCreatedAt(), v.getUpdatedAt()
        );
    }
}
