package com.medfund.finance.dto;

import com.medfund.finance.entity.MemberCostShareLiability;
import com.medfund.finance.entity.MemberCostShareSettlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full detail payload for a single member cost-share liability, including
 * every settlement row applied against it. Drives the drill-down page.
 */
public record MemberCostShareLiabilityDetailResponse(
        UUID id,
        UUID memberId,
        UUID claimId,
        String claimNumber,
        BigDecimal deductible,
        BigDecimal copay,
        BigDecimal coinsurance,
        BigDecimal shortfall,
        BigDecimal notCovered,
        BigDecimal totalOwed,
        BigDecimal totalSettled,
        String currencyCode,
        String currencyCodeOriginal,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<SettlementView> settlements
) {
    public record SettlementView(
            UUID id,
            UUID receiptTransactionId,
            BigDecimal amount,
            String currencyCode,
            String source,
            Instant settledAt) {
        public static SettlementView from(MemberCostShareSettlement s) {
            return new SettlementView(s.getId(), s.getReceiptTransactionId(), s.getAmount(),
                    s.getCurrencyCode(), s.getSource(), s.getSettledAt());
        }
    }

    public static MemberCostShareLiabilityDetailResponse from(
            MemberCostShareLiability l, List<MemberCostShareSettlement> settlements) {
        return new MemberCostShareLiabilityDetailResponse(
                l.getId(), l.getMemberId(), l.getClaimId(), l.getClaimNumber(),
                l.getDeductible(), l.getCopay(), l.getCoinsurance(),
                l.getShortfall(), l.getNotCovered(),
                l.getTotalOwed(), l.getTotalSettled(),
                l.getCurrencyCode(), l.getCurrencyCodeOriginal(),
                l.getStatus(), l.getCreatedAt(), l.getUpdatedAt(),
                settlements.stream().map(SettlementView::from).toList());
    }
}
