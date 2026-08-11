package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-scheme billing aggregate row, grouped by (scheme, currency) so the
 * report never sums across currencies. Age-band revenue is bucketed at
 * the row level by the beneficiary's age at {@code period_start}
 * (0-18 / 19-35 / 36-55 / 56+). {@code totalBilled} is the sum of every
 * contribution row that landed on an invoice in the window (i.e. any
 * row where {@code invoice_id IS NOT NULL}); {@code totalPaid} narrows
 * to {@code status='paid'}.
 */
public record SchemeBillingSummaryRow(
        UUID schemeId,
        String schemeName,
        String insuranceLine,
        String currencyCode,
        long principalCount,
        long dependantCount,
        long livesCovered,
        BigDecimal ageBand0_18,
        BigDecimal ageBand19_35,
        BigDecimal ageBand36_55,
        BigDecimal ageBand56Plus,
        BigDecimal totalBilled,
        BigDecimal totalPaid
) {
}
