package com.medfund.contributions.dto;

import com.medfund.contributions.entity.SchemeCostShare;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SchemeCostShareResponse(
        UUID id,
        UUID schemeId,
        Integer policyYear,
        BigDecimal deductible,
        BigDecimal outOfPocketMax,
        String deductibleScope,
        String oopScope,
        String shortfallPolicy,
        String currencyCode,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {

    public static SchemeCostShareResponse from(SchemeCostShare s) {
        return new SchemeCostShareResponse(
                s.getId(),
                s.getSchemeId(),
                s.getPolicyYear(),
                s.getDeductible(),
                s.getOutOfPocketMax(),
                s.getDeductibleScope(),
                s.getOopScope(),
                s.getShortfallPolicy(),
                s.getCurrencyCode(),
                s.getEffectiveFrom(),
                s.getEffectiveTo()
        );
    }
}
