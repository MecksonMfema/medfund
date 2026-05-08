package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditorRow(
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
    public static CreditorRow from(BalanceRow r) {
        return new CreditorRow(
                r.subjectType(), r.subjectId(), r.subjectCode(), r.subjectName(), r.subjectEmail(),
                r.currencyCode(), r.balance(), r.lastChargeAt(), r.lastPaymentAt(),
                r.daysSinceLastActivity());
    }
}
