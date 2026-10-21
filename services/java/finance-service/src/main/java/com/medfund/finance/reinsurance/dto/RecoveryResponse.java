package com.medfund.finance.reinsurance.dto;

import com.medfund.finance.reinsurance.entity.Recovery;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RecoveryResponse(
        UUID id,
        UUID cessionId,
        String status,
        BigDecimal expectedAmount,
        BigDecimal receivedAmount,
        String currencyCode,
        OffsetDateTime invoicedAt,
        OffsetDateTime receivedAt,
        String writeOffReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static RecoveryResponse from(Recovery r) {
        return new RecoveryResponse(
                r.getId(),
                r.getCessionId(),
                r.getStatus(),
                r.getExpectedAmount(),
                r.getReceivedAmount(),
                r.getCurrencyCode(),
                r.getInvoicedAt(),
                r.getReceivedAt(),
                r.getWriteOffReason(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
