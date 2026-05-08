package com.medfund.contributions.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Filter set for the operational transactions list. Every field is optional
 * — null fields are skipped from the WHERE clause server-side. Page and size
 * are bounded by the controller (page ≥ 0, 1 ≤ size ≤ 100).
 */
public record TransactionFilterParams(
        String currencyCode,
        String transactionType,
        String paymentMethod,
        LocalDate periodStart,
        LocalDate periodEnd,
        UUID contributionId,
        UUID invoiceId,
        String q,
        int page,
        int size
) {}
