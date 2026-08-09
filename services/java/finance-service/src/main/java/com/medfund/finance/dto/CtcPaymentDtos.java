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

    /**
     * Create request. Member-only for MVP: {@code memberId} + {@code memberPayableId}
     * are required. {@code groupId} stays in the payload for schema stability but
     * the service 422s a group-only CTC — see design decision #1 in the plan.
     */
    public record CreateCtcPaymentRequest(
        UUID groupId,
        @NotNull UUID memberId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(max = 3) String currencyCode,
        UUID contributionId,
        @NotNull UUID memberPayableId
    ) {}

    public record CtcPaymentResponse(
        UUID id,
        String type,
        String status,
        UUID groupId,
        UUID memberId,
        UUID memberPayableId,
        UUID reversesCtcId,
        BigDecimal amount,
        String currencyCode,
        UUID contributionId,
        Instant createdAt,
        UUID createdBy,
        Instant committedAt,
        UUID committedBy,
        /** Derived from {@link #status} for one-release back-compat. */
        Boolean committed
    ) {
        public static CtcPaymentResponse from(CtcPayment c) {
            return new CtcPaymentResponse(
                c.getId(),
                c.getType(),
                c.getStatus(),
                c.getGroupId(),
                c.getMemberId(),
                c.getMemberPayableId(),
                c.getReversesCtcId(),
                c.getAmount(),
                c.getCurrencyCode(),
                c.getContributionId(),
                c.getCreatedAt(),
                c.getCreatedBy(),
                c.getCommittedAt(),
                c.getCommittedBy(),
                "committed".equals(c.getStatus())
            );
        }
    }

    /**
     * Reverse request. Operator-supplied justification lands on the
     * compensating row's audit event and drives the notification copy.
     */
    public record ReverseCtcPaymentRequest(
        @NotBlank @Size(max = 500) String reason
    ) {}
}
