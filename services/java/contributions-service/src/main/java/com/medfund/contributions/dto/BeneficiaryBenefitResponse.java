package com.medfund.contributions.dto;

import com.medfund.contributions.entity.BeneficiaryBenefit;
import com.medfund.contributions.entity.SchemeBenefit;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Denormalized utilization row for the claim-detail page: the counters
 * from {@link BeneficiaryBenefit} joined with the benefit metadata
 * from {@link SchemeBenefit} so the UI can render "used $180 of $500"
 * without a second round trip per row.
 */
public record BeneficiaryBenefitResponse(
        UUID id,
        UUID memberId,
        UUID dependantId,
        UUID benefitId,
        String benefitName,
        String benefitType,
        Integer policyYear,
        BigDecimal annualLimit,
        BigDecimal eventLimit,
        BigDecimal dailyLimit,
        Integer waitingPeriodDays,
        BigDecimal consumedAmount,
        Integer consumedCount,
        String currencyCode
) {
    public static BeneficiaryBenefitResponse from(BeneficiaryBenefit b, SchemeBenefit sb) {
        return new BeneficiaryBenefitResponse(
                b.getId(),
                b.getMemberId(),
                b.getDependantId(),
                b.getBenefitId(),
                sb != null ? sb.getName() : null,
                sb != null ? sb.getBenefitType() : null,
                b.getPolicyYear(),
                sb != null ? sb.getAnnualLimit() : null,
                sb != null ? sb.getEventLimit()  : null,
                sb != null ? sb.getDailyLimit()  : null,
                sb != null ? sb.getWaitingPeriodDays() : null,
                b.getConsumedAmount(),
                b.getConsumedCount(),
                b.getCurrencyCode()
        );
    }
}
