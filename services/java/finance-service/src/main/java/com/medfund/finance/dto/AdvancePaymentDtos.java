package com.medfund.finance.dto;

import com.medfund.finance.entity.AdvancePayment;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@SuppressWarnings("deprecation") // response mirrors legacy paymentId column
public final class AdvancePaymentDtos {

    private AdvancePaymentDtos() {}

    public record CreateAdvancePaymentRequest(
        UUID providerId,
        UUID memberId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(max = 3) String currencyCode,
        @Size(max = 50) String paymentMethod,
        @Size(max = 255) String reference,
        String comment
    ) {}

    public record ReverseAdvancePaymentRequest(
        @NotBlank @Size(max = 500) String reason
    ) {}

    public record AdvancePaymentResponse(
        UUID id,
        UUID paymentId,
        UUID providerId,
        UUID memberId,
        BigDecimal amount,
        String currencyCode,
        String paymentMethod,
        String reference,
        String comment,
        Instant recordedAt,
        UUID recordedBy,
        String type,
        String status,
        UUID approvedBy,
        Instant approvedAt,
        UUID reversesAdvanceId
    ) {
        public static AdvancePaymentResponse from(AdvancePayment a) {
            return new AdvancePaymentResponse(
                a.getId(), a.getPaymentId(), a.getProviderId(), a.getMemberId(),
                a.getAmount(), a.getCurrencyCode(), a.getPaymentMethod(),
                a.getReference(), a.getComment(), a.getRecordedAt(), a.getRecordedBy(),
                a.getType(), a.getStatus(), a.getApprovedBy(), a.getApprovedAt(),
                a.getReversesAdvanceId()
            );
        }
    }
}
