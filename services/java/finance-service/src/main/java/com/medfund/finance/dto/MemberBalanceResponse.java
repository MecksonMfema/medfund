package com.medfund.finance.dto;

import com.medfund.finance.entity.MemberBalance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response shape for GET /api/v1/creditors/member/{memberId}. Mirrors
 * {@link ProviderBalanceResponse} but with member-side identity fields
 * so the member-balance detail page can render the summary card without
 * a second lookup.
 */
public record MemberBalanceResponse(
        UUID id,
        UUID memberId,
        String memberName,
        String memberCode,
        String memberEmail,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid,
        BigDecimal outstandingBalance,
        String currencyCode,
        Instant lastUpdatedAt,
        Instant createdAt
) {
    public static MemberBalanceResponse from(MemberBalance balance,
                                              String memberName, String memberCode, String memberEmail) {
        return new MemberBalanceResponse(
                balance.getId(),
                balance.getMemberId(),
                memberName,
                memberCode,
                memberEmail,
                balance.getTotalClaimed(),
                balance.getTotalApproved(),
                balance.getTotalPaid(),
                balance.getOutstandingBalance(),
                balance.getCurrencyCode(),
                balance.getLastUpdatedAt(),
                balance.getCreatedAt()
        );
    }
}
