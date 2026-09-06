package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateTreatyRequest(
        @NotBlank @Size(max = 120) String treatyRef,
        @NotBlank @Pattern(regexp = "QUOTA_SHARE|SURPLUS_SHARE|EXCESS_OF_LOSS|STOP_LOSS") String treatyType,
        @NotBlank @Size(min = 3, max = 3) String declaredCurrency,
        @NotNull LocalDate inceptionDate,
        @NotNull LocalDate expiryDate,
        @DecimalMin("0.00") BigDecimal aggregateLimit,
        @Size(min = 3, max = 3) String aggregateLimitCurrency,
        @DecimalMin("0.00") BigDecimal expectedAnnualPremium,
        @Size(max = 120) String producerRef,
        UUID producerId
) {
    /**
     * Legacy 9-arg overload — new callers should pass an explicit
     * {@code producerId}. Kept so existing tests / clients that only
     * provided a free-text {@code producerRef} continue to compile.
     */
    public CreateTreatyRequest(String treatyRef, String treatyType, String declaredCurrency,
                                LocalDate inceptionDate, LocalDate expiryDate,
                                BigDecimal aggregateLimit, String aggregateLimitCurrency,
                                BigDecimal expectedAnnualPremium, String producerRef) {
        this(treatyRef, treatyType, declaredCurrency, inceptionDate, expiryDate,
                aggregateLimit, aggregateLimitCurrency, expectedAnnualPremium,
                producerRef, null);
    }
}
