package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Drill-down payload for a single scheme / group / member's receipts. Same
 * shape for all three dimensions per G40 — the top strip is monthly
 * buckets, the bottom is a paginated transaction ledger with the fields a
 * treasurer needs for reconciliation (transaction number, date, type,
 * payment method, reference, amount, currency).
 *
 * <p>Ledger rows stay native-currency per G25. Cross-currency
 * stratification lives on the envelope's {@code perCurrency} +
 * {@code fxRates} maps that the composing controller wraps around this
 * response.
 */
public record ReceiptsDetailResponse(
        UUID dimensionId,
        String dimensionName,
        List<MonthlyBucket> monthlyBuckets,
        PageResponse<TransactionLedgerRow> transactions
) {

    public record MonthlyBucket(
            LocalDate month,
            String currencyCode,
            BigDecimal totalReceived,
            long transactionCount) {}

    public record TransactionLedgerRow(
            UUID id,
            String transactionNumber,
            Instant transactionDate,
            String transactionType,
            String paymentMethod,
            String reference,
            BigDecimal amount,
            String currencyCode) {}
}
