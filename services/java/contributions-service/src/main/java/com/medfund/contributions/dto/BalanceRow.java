package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projection row used by both the creditor list and the bad-debt aging list.
 * {@code subjectType} is "MEMBER" or "GROUP" — the UI renders one combined
 * list so the user sees every outstanding balance regardless of who owes it.
 */
public record BalanceRow(
        String subjectType,
        UUID subjectId,
        String subjectCode,
        String subjectName,
        String subjectEmail,
        String currencyCode,
        BigDecimal balance,
        Instant lastChargeAt,
        Instant lastPaymentAt
) {
    public Long daysSinceLastActivity() {
        Instant ref = lastPaymentAt != null ? lastPaymentAt : lastChargeAt;
        if (ref == null) return null;
        return java.time.Duration.between(ref, Instant.now()).toDays();
    }
}
