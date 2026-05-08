package com.medfund.contributions.dto;

import com.medfund.contributions.entity.GroupRunningBalance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record GroupBalanceResponse(
        UUID groupId,
        String currencyCode,
        BigDecimal balance,
        Instant lastChargeAt,
        Instant lastPaymentAt,
        Instant updatedAt
) {
    public static GroupBalanceResponse from(GroupRunningBalance b) {
        return new GroupBalanceResponse(
                b.getGroupId(), b.getCurrencyCode(),
                b.getBalance() != null ? b.getBalance() : BigDecimal.ZERO,
                b.getLastChargeAt(), b.getLastPaymentAt(), b.getUpdatedAt());
    }

    public static GroupBalanceResponse zero(UUID groupId, String currencyCode) {
        return new GroupBalanceResponse(groupId, currencyCode, BigDecimal.ZERO, null, null, null);
    }
}
