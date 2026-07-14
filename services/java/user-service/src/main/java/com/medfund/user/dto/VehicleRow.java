package com.medfund.user.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by the paginated vehicles list. Carries pre-joined
 * scheme + owner-member display fields so the Angular table never has to
 * render raw UUIDs or fan out a second request per row.
 */
public record VehicleRow(
        UUID id,
        UUID schemeId,
        String schemeName,
        UUID ownerMemberId,
        String ownerMemberName,
        String registrationNumber,
        String make,
        String model,
        Integer year,
        BigDecimal vehicleValue,
        String bodyType,
        String usageType,
        String status,
        BigDecimal billingOverrideAmount,
        Instant createdAt,
        Instant updatedAt
) {
}
