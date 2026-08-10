package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DebtorRow(
        String subjectType,
        UUID subjectId,
        String subjectCode,
        String subjectName,
        String subjectEmail,
        String currencyCode,
        BigDecimal balance,
        Instant lastChargeAt,
        Instant lastPaymentAt,
        Long daysSinceLastActivity
) {
    public static DebtorRow from(BalanceRow r) {
        return new DebtorRow(
                r.subjectType(), r.subjectId(), r.subjectCode(), r.subjectName(), r.subjectEmail(),
                r.currencyCode(), r.balance(), r.lastChargeAt(), r.lastPaymentAt(),
                r.daysSinceLastActivity());
    }
}
