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
 *
 * <p>V061: {@code usageMode} drives conditional rendering (progress bar
 * vs. one-time chip vs. per-event counter). {@code remaining} + {@code status}
 * are computed here so the UI has no branching arithmetic to do.
 */
public record BeneficiaryBenefitResponse(
        UUID id,
        UUID memberId,
        UUID dependantId,
        UUID benefitId,
        String benefitName,
        String benefitType,
        String usageMode,
        Integer policyYear,
        BigDecimal annualLimit,
        BigDecimal eventLimit,
        BigDecimal dailyLimit,
        Integer waitingPeriodDays,
        BigDecimal consumedAmount,
        Integer consumedCount,
        BigDecimal remaining,
        String status,
        String currencyCode
) {
    public static BeneficiaryBenefitResponse from(BeneficiaryBenefit b, SchemeBenefit sb) {
        String mode = sb != null && sb.getUsageMode() != null ? sb.getUsageMode() : "RUNNING_BALANCE";
        BigDecimal limit = sb != null ? sb.getAnnualLimit() : null;
        BigDecimal consumed = b.getConsumedAmount() != null ? b.getConsumedAmount() : BigDecimal.ZERO;
        int count = b.getConsumedCount() != null ? b.getConsumedCount() : 0;

        BigDecimal remaining = remainingFor(mode, limit, consumed);
        String status = statusFor(mode, limit, remaining, count);

        return new BeneficiaryBenefitResponse(
                b.getId(),
                b.getMemberId(),
                b.getDependantId(),
                b.getBenefitId(),
                sb != null ? sb.getName() : null,
                sb != null ? sb.getBenefitType() : null,
                mode,
                b.getPolicyYear(),
                limit,
                sb != null ? sb.getEventLimit()  : null,
                sb != null ? sb.getDailyLimit()  : null,
                sb != null ? sb.getWaitingPeriodDays() : null,
                consumed,
                count,
                remaining,
                status,
                b.getCurrencyCode()
        );
    }

    private static BigDecimal remainingFor(String mode, BigDecimal limit, BigDecimal consumed) {
        if ("NO_TRACKING".equals(mode)) return null;
        if (limit == null || limit.signum() <= 0) return null;
        BigDecimal r = limit.subtract(consumed);
        return r.signum() < 0 ? BigDecimal.ZERO : r;
    }

    private static String statusFor(String mode, BigDecimal limit, BigDecimal remaining, int count) {
        if ("NO_TRACKING".equals(mode)) return "untracked";
        boolean oneTime = "ONE_TIME_PER_BENEFICIARY".equals(mode) || "ONE_TIME_PER_PERIOD".equals(mode);
        if (oneTime) return count > 0 ? "exhausted" : "available";
        if (limit == null || limit.signum() <= 0) return "unlimited";
        return remaining != null && remaining.signum() <= 0 ? "exhausted" : "available";
    }
}
