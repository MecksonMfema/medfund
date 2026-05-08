package com.medfund.contributions.dto;

import com.medfund.contributions.entity.MemberRunningBalance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MemberBalanceResponse(
        UUID memberId,
        String currencyCode,
        BigDecimal balance,
        Instant lastChargeAt,
        Instant lastPaymentAt,
        Instant updatedAt
) {
    public static MemberBalanceResponse from(MemberRunningBalance b) {
        return new MemberBalanceResponse(
                b.getMemberId(), b.getCurrencyCode(),
                b.getBalance() != null ? b.getBalance() : BigDecimal.ZERO,
                b.getLastChargeAt(), b.getLastPaymentAt(), b.getUpdatedAt());
    }

    public static MemberBalanceResponse zero(UUID memberId, String currencyCode) {
        return new MemberBalanceResponse(memberId, currencyCode, BigDecimal.ZERO, null, null, null);
    }
}
