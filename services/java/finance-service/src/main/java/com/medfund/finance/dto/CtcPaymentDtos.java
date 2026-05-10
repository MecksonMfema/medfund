package com.medfund.finance.dto;

import com.medfund.finance.entity.CtcPayment;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class CtcPaymentDtos {

    private CtcPaymentDtos() {}

    public record CreateCtcPaymentRequest(
        UUID groupId,
        UUID memberId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(max = 3) String currencyCode,
        UUID contributionId
    ) {}

    public record CtcPaymentResponse(
        UUID id,
        UUID groupId,
        UUID memberId,
        BigDecimal amount,
        String currencyCode,
        UUID contributionId,
        Boolean committed,
        Instant createdAt,
        UUID createdBy
    ) {
        public static CtcPaymentResponse from(CtcPayment c) {
            return new CtcPaymentResponse(
                c.getId(), c.getGroupId(), c.getMemberId(), c.getAmount(),
                c.getCurrencyCode(), c.getContributionId(), c.getCommitted(),
                c.getCreatedAt(), c.getCreatedBy()
            );
        }
    }
}
