package com.medfund.finance.dto;

import com.medfund.finance.entity.PaymentAdviceRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentAdviceRecordResponse(
    UUID id,
    UUID paymentRunId,
    UUID providerId,
    String currencyCode,
    BigDecimal totalAmount,
    Integer claimCount,
    String documentUrl,
    String excelUrl,
    String status,
    Instant issuedAt,
    Instant createdAt
) {
    public static PaymentAdviceRecordResponse from(PaymentAdviceRecord r) {
        return new PaymentAdviceRecordResponse(
            r.getId(), r.getPaymentRunId(), r.getProviderId(), r.getCurrencyCode(),
            r.getTotalAmount(), r.getClaimCount(), r.getDocumentUrl(), r.getExcelUrl(),
            r.getStatus(), r.getIssuedAt(), r.getCreatedAt()
        );
    }
}
