package com.medfund.contributions.dto;

import com.medfund.contributions.entity.AgeGroup;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AgeGroupResponse(
        UUID id,
        UUID schemeId,
        String name,
        Integer minAge,
        Integer maxAge,
        BigDecimal contributionAmount,
        String currencyCode,
        String status,
        Instant createdAt
) {
    public static AgeGroupResponse from(AgeGroup ageGroup) {
        return new AgeGroupResponse(
                ageGroup.getId(),
                ageGroup.getSchemeId(),
                ageGroup.getName(),
                ageGroup.getMinAge(),
                ageGroup.getMaxAge(),
                ageGroup.getContributionAmount(),
                ageGroup.getCurrencyCode(),
                ageGroup.getStatus(),
                ageGroup.getCreatedAt()
        );
    }
}
