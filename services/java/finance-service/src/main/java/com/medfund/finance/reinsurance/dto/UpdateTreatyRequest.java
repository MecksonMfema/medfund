package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateTreatyRequest(
        @NotBlank @Size(max = 120) String treatyRef,
        @NotBlank @Pattern(regexp = "QUOTA_SHARE|SURPLUS_SHARE|EXCESS_OF_LOSS|STOP_LOSS") String treatyType,
        @NotBlank @Size(min = 3, max = 3) String declaredCurrency,
        @NotNull LocalDate inceptionDate,
        @NotNull LocalDate expiryDate,
        @DecimalMin("0.00") BigDecimal aggregateLimit,
        @Size(min = 3, max = 3) String aggregateLimitCurrency,
        @DecimalMin("0.00") BigDecimal expectedAnnualPremium,
        @Size(max = 120) String producerRef
) {}
