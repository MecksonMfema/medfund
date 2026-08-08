package com.medfund.finance.dto;

import com.medfund.finance.entity.PaymentRun;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentRunResponse(
        UUID id,
        String runNumber,
        String status,
        BigDecimal totalAmount,
        String currencyCode,
        Integer paymentCount,
        String description,
        Instant executedAt,
        UUID executedBy,
        /** V067 — unpaid balance rolled in from prior runs at generation time. */
        BigDecimal carriedInAmount,
        /** V067 — unpaid balance remaining when the run was executed. */
        BigDecimal carriedOutAmount,
        /** V067 — moment the last item in the run transitioned to paid.
         *  Null until every item is settled. */
        Instant settlementDate,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy
) {
    public static PaymentRunResponse from(PaymentRun run) {
        return new PaymentRunResponse(
                run.getId(),
                run.getRunNumber(),
                run.getStatus(),
                run.getTotalAmount(),
                run.getCurrencyCode(),
                run.getPaymentCount(),
                run.getDescription(),
                run.getExecutedAt(),
                run.getExecutedBy(),
                run.getCarriedInAmount(),
                run.getCarriedOutAmount(),
                run.getSettlementDate(),
                run.getCreatedAt(),
                run.getUpdatedAt(),
                run.getCreatedBy()
        );
    }
}
