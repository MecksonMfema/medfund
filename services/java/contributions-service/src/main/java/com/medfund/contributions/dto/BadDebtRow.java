package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bad-debt row — derived from an aged running-balance entry. The
 * {@code agingStatus} is computed from {@code dunning_config} thresholds:
 * GRACE / SUSPENDED / WRITE_OFF.
 */
public record BadDebtRow(
        String subjectType,
        UUID subjectId,
        String subjectCode,
        String subjectName,
        String subjectEmail,
        String currencyCode,
        BigDecimal balance,
        Instant lastChargeAt,
        Instant lastPaymentAt,
        Long daysSinceLastActivity,
        String agingStatus
) {
    public static BadDebtRow from(BalanceRow r, String agingStatus) {
        return new BadDebtRow(
                r.subjectType(), r.subjectId(), r.subjectCode(), r.subjectName(), r.subjectEmail(),
                r.currencyCode(), r.balance(), r.lastChargeAt(), r.lastPaymentAt(),
                r.daysSinceLastActivity(), agingStatus);
    }
}
