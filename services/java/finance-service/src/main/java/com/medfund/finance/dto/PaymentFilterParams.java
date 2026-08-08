package com.medfund.finance.dto;

import java.util.UUID;

public record PaymentFilterParams(
        String status,
        String paymentType,
        UUID providerId,
        String currencyCode,
        /** V067 — scope payments to a single payment run via the
         *  payment_run_items join. Null = all runs. */
        UUID paymentRunId,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
