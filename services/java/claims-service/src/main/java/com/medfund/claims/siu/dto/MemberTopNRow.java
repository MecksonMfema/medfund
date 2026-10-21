package com.medfund.claims.siu.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Top-N member row for the {@code /reports/fraud/top-members} endpoint
 * (§B Phase 11). Same shape as {@link ProviderTopNRow} — ranked
 * descending by {@code savingsComposite}. Member name lookup is
 * client-side via the beneficiary picker cache.
 */
public record MemberTopNRow(
        UUID memberId,
        String memberName,
        String memberNumber,
        long confirmedCases,
        Map<String, BigDecimal> savingsNative,
        BigDecimal savingsComposite
) {
}
