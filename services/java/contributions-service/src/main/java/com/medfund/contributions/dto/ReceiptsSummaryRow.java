package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-dimension summary row for the receipts family. One row per
 * (dimension, currency) — receipt netting via
 * {@code SUM(CASE tt.sign WHEN '-' THEN amount ELSE -amount END)} per F25.
 *
 * <p>{@code dimensionId} may be {@code null} for the synthetic
 * "Unallocated group payments" scheme bucket (G33) — group-owned rows
 * without a {@code contribution_id} back-link land there because a group
 * can span multiple schemes so there's no honest scheme attribution.
 *
 * <p>{@code insuranceLine} is populated on the per-member surface only
 * (G36); left {@code null} for scheme and group summaries.
 */
public record ReceiptsSummaryRow(
        UUID dimensionId,
        String dimensionName,
        String insuranceLine,
        String currencyCode,
        BigDecimal totalReceived,
        long transactionCount
) {
}
