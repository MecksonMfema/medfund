package com.medfund.finance.dto;

import com.medfund.finance.entity.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String paymentNumber,
        UUID providerId,
        UUID memberId,
        String payeeType,
        BigDecimal amount,
        String currencyCode,
        String paymentType,
        String status,
        String paymentMethod,
        String reference,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPaymentNumber(),
                payment.getProviderId(),
                payment.getMemberId(),
                payment.getPayeeType(),
                payment.getAmount(),
                payment.getCurrencyCode(),
                payment.getPaymentType(),
                payment.getStatus(),
                payment.getPaymentMethod(),
                payment.getReference(),
                payment.getPaidAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getCreatedBy(),
                payment.getUpdatedBy()
        );
    }
}
