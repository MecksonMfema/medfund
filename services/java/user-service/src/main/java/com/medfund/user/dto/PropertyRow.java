package com.medfund.user.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PropertyRow(
        UUID id,
        UUID schemeId,
        String schemeName,
        UUID ownerMemberId,
        String ownerMemberName,
        String propertyName,
        String address,
        BigDecimal sumInsured,
        String constructionType,
        String occupancy,
        String status,
        BigDecimal billingOverrideAmount,
        Instant createdAt,
        Instant updatedAt
) {
}
