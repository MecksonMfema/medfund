package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Unified creditor row shape returned by GET /api/v1/creditors/page.
 * {@code subjectType} disambiguates PROVIDER vs MEMBER for the UI —
 * both halves render the same four money columns, so an operator sees
 * everything the fund owes in one list, regardless of who's owed.
 */
public record CreditorRow(
        String subjectType,            // "PROVIDER" | "MEMBER"
        UUID subjectId,
        String subjectCode,            // provider.code / member.member_number
        String subjectName,
        String subjectEmail,
        String currencyCode,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid,
        BigDecimal outstandingBalance,
        Instant lastActivityAt
) {
}
