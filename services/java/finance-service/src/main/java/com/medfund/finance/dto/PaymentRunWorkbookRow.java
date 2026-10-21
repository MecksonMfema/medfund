package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One workbook row for a payment run — the run item joined to its payment
 * and payee so the export carries human-friendly context (payment number,
 * payee name) rather than raw UUIDs (Phase 7, D7-4). Rows are native
 * per-currency; grouping and cross-currency conversion happen in
 * {@code PaymentRunWorkbookService}.
 */
public record PaymentRunWorkbookRow(
        UUID itemId,
        UUID paymentId,
        String paymentNumber,
        String payeeType,
        UUID providerId,
        UUID memberId,
        String payeeName,
        BigDecimal amount,
        String currencyCode,
        String status,
        String paymentMethod,
        String reference,
        Instant paidAt,
        Instant createdAt
) {
}
