package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Row shape returned by GET /provider-balances/page. Provider name pre-joined. */
public record ProviderBalanceRow(
        UUID id,
        UUID providerId,
        String providerName,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid,
        BigDecimal outstandingBalance,
        String currencyCode,
        Instant updatedAt
) {
}
