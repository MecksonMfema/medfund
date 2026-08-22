package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload for creating a successor treaty from an existing one. Backend copies
 * the treaty type + declared currency + producer ref forward automatically;
 * the client only supplies the new period + the (usually revised) financial
 * numbers.
 */
public record RenewTreatyRequest(
        @NotBlank @Size(max = 120) String treatyRef,
        @NotNull LocalDate inceptionDate,
        @NotNull LocalDate expiryDate,
        @DecimalMin("0.00") BigDecimal aggregateLimit,
        @Size(min = 3, max = 3) String aggregateLimitCurrency,
        @DecimalMin("0.00") BigDecimal expectedAnnualPremium
) {}
