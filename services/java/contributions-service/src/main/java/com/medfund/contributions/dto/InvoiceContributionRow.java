package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Row shape for {@code GET /api/v1/invoices/{id}/contributions} — the
 * per-scheme member/dependant breakdown that the statement detail page
 * renders. All names joined server-side per {@code feedback_stats_serverside}.
 */
public record InvoiceContributionRow(
        UUID contributionId,
        String memberNumber,
        String memberName,
        String personType,         // "MEMBER" | "DEPENDANT"
        String dependantName,      // null when personType == MEMBER
        String schemeName,
        String insuranceLine,
        String ageBand,            // null for non-HEALTH lines
        BigDecimal amount,
        String currencyCode
) {}
