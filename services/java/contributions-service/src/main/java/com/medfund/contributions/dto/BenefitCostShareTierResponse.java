package com.medfund.contributions.dto;

import com.medfund.contributions.entity.BenefitCostShareTier;

import java.math.BigDecimal;
import java.util.UUID;

public record BenefitCostShareTierResponse(
        UUID id,
        UUID benefitCostShareId,
        String tierName,
        BigDecimal copayAmount,
        BigDecimal copayPercentage,
        BigDecimal copayMax) {

    public static BenefitCostShareTierResponse from(BenefitCostShareTier t) {
        return new BenefitCostShareTierResponse(
                t.getId(),
                t.getBenefitCostShareId(),
                t.getTierName(),
                t.getCopayAmount(),
                t.getCopayPercentage(),
                t.getCopayMax()
        );
    }
}
