package com.medfund.finance.dto;

import com.medfund.finance.entity.AdvancePaymentApplication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response shape for GET /advance-payments/{id}/applications — the list of
 * payment-run items that consumed the advance. Enriched fields
 * (paymentNumber, runNumber) are populated by a later join in the query
 * repository; for the base row response they may be null.
 */
public record AdvancePaymentApplicationResponse(
        UUID id,
        UUID advancePaymentId,
        UUID paymentId,
        UUID paymentRunId,
        UUID paymentRunItemId,
        BigDecimal amountApplied,
        String currencyCode,
        Instant appliedAt,
        UUID appliedBy
) {
    public static AdvancePaymentApplicationResponse from(AdvancePaymentApplication a) {
        return new AdvancePaymentApplicationResponse(
                a.getId(),
                a.getAdvancePaymentId(),
                a.getPaymentId(),
                a.getPaymentRunId(),
                a.getPaymentRunItemId(),
                a.getAmountApplied(),
                a.getCurrencyCode(),
                a.getAppliedAt(),
                a.getAppliedBy()
        );
    }
}
