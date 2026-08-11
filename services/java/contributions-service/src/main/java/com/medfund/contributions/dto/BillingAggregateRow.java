package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cross-service billing aggregate row consumed by Phase 3 (receipts-vs-billing
 * collection rate) and Phase 5 (loss ratio). Deliberately narrow: just the
 * (scheme, currency) → total-billed pair. Anything richer belongs on the
 * per-scheme summary endpoint.
 */
public record BillingAggregateRow(
        UUID schemeId,
        String schemeName,
        String currencyCode,
        BigDecimal totalBilled
) {
}
