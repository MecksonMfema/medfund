package com.medfund.contributions.dto;

import com.medfund.contributions.entity.SchemeBenefit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SchemeBenefitResponse(
        UUID id,
        UUID schemeId,
        String name,
        String benefitType,
        BigDecimal annualLimit,
        BigDecimal dailyLimit,
        BigDecimal eventLimit,
        String currencyCode,
        Integer waitingPeriodDays,
        String description,
        String status,
        Short minAge,
        Short maxAge,
        Boolean cashClaimAllowed,
        Instant createdAt,
        Instant updatedAt
) {
    public static SchemeBenefitResponse from(SchemeBenefit benefit) {
        return new SchemeBenefitResponse(
                benefit.getId(),
                benefit.getSchemeId(),
                benefit.getName(),
                benefit.getBenefitType(),
                benefit.getAnnualLimit(),
                benefit.getDailyLimit(),
                benefit.getEventLimit(),
                benefit.getCurrencyCode(),
                benefit.getWaitingPeriodDays(),
                benefit.getDescription(),
                benefit.getStatus(),
                benefit.getMinAge(),
                benefit.getMaxAge(),
                benefit.getCashClaimAllowed(),
                benefit.getCreatedAt(),
                benefit.getUpdatedAt()
        );
    }
}
