package com.medfund.contributions.dto;

import com.medfund.contributions.entity.SchemeBenefit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        String usageMode,
        /**
         * V063 tariff-category coverage for this benefit — the list is
         * empty when the join rows haven't been fetched (list endpoint)
         * and populated on the detail endpoint. The Angular form uses
         * this to pre-populate the multi-select on edit.
         */
        List<UUID> categoryIds,
        Instant createdAt,
        Instant updatedAt
) {
    public static SchemeBenefitResponse from(SchemeBenefit benefit) {
        return from(benefit, List.of());
    }

    public static SchemeBenefitResponse from(SchemeBenefit benefit, List<UUID> categoryIds) {
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
                benefit.getUsageMode(),
                categoryIds != null ? categoryIds : List.of(),
                benefit.getCreatedAt(),
                benefit.getUpdatedAt()
        );
    }
}
