package com.medfund.contributions.dto;

import com.medfund.contributions.entity.BenefitCostShare;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BenefitCostShareResponse(
        UUID id,
        UUID schemeBenefitId,
        String copayType,
        BigDecimal copayAmount,
        BigDecimal copayPercentage,
        BigDecimal copayMax,
        BigDecimal coinsuranceRate,
        Boolean appliesToDeductible,
        Boolean appliesToOopMax,
        String basis,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {

    public static BenefitCostShareResponse from(BenefitCostShare b) {
        return new BenefitCostShareResponse(
                b.getId(),
                b.getSchemeBenefitId(),
                b.getCopayType(),
                b.getCopayAmount(),
                b.getCopayPercentage(),
                b.getCopayMax(),
                b.getCoinsuranceRate(),
                b.getAppliesToDeductible(),
                b.getAppliesToOopMax(),
                b.getBasis(),
                b.getEffectiveFrom(),
                b.getEffectiveTo()
        );
    }
}
