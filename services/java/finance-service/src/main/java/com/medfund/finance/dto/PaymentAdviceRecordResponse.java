package com.medfund.finance.dto;

import com.medfund.finance.entity.PaymentAdviceRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentAdviceRecordResponse(
    UUID id,
    String adviceNumber,
    UUID paymentRunId,
    String payeeType,
    UUID providerId,
    UUID memberId,
    String currencyCode,
    BigDecimal totalAmount,
    Integer claimCount,
    String documentUrl,
    String excelUrl,
    String status,
    Instant issuedAt,
    Instant periodStartAt,
    Instant periodEndAt,
    BigDecimal carriedInAmount,
    BigDecimal claimsPaidAmount,
    BigDecimal ctcAppliedAmount,
    BigDecimal advanceAppliedAmount,
    BigDecimal taxWithheldAmount,
    BigDecimal shortfallAmount,
    BigDecimal netDueAmount,
    Instant createdAt
) {
    public static PaymentAdviceRecordResponse from(PaymentAdviceRecord r) {
        return new PaymentAdviceRecordResponse(
            r.getId(), r.getAdviceNumber(), r.getPaymentRunId(), r.getPayeeType(),
            r.getProviderId(), r.getMemberId(), r.getCurrencyCode(),
            r.getTotalAmount(), r.getClaimCount(), r.getDocumentUrl(), r.getExcelUrl(),
            r.getStatus(), r.getIssuedAt(),
            r.getPeriodStartAt(), r.getPeriodEndAt(),
            r.getCarriedInAmount(), r.getClaimsPaidAmount(),
            r.getCtcAppliedAmount(), r.getAdvanceAppliedAmount(),
            r.getTaxWithheldAmount(), r.getShortfallAmount(),
            r.getNetDueAmount(),
            r.getCreatedAt()
        );
    }
}
