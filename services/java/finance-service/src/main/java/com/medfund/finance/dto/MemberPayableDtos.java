package com.medfund.finance.dto;

import com.medfund.finance.entity.MemberPayable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** DTOs for the read-only member-payable API. */
public final class MemberPayableDtos {

    private MemberPayableDtos() {}

    /** Full row shape returned by the list endpoint. */
    public record MemberPayableResponse(
            UUID id,
            UUID memberId,
            UUID claimId,
            String claimNumber,
            BigDecimal amount,
            String currencyCode,
            String status,
            Instant recordedAt,
            UUID recordedBy
    ) {
        public static MemberPayableResponse from(MemberPayable mp) {
            return new MemberPayableResponse(
                mp.getId(), mp.getMemberId(), mp.getClaimId(), mp.getClaimNumber(),
                mp.getAmount(), mp.getCurrencyCode(), mp.getStatus(),
                mp.getRecordedAt(), mp.getRecordedBy());
        }
    }

    /** One row per currency the member has an outstanding balance in. */
    public record OutstandingBalanceResponse(String currencyCode, BigDecimal outstanding) {}
}
