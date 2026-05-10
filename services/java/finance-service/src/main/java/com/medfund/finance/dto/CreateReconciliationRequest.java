package com.medfund.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateReconciliationRequest(
        @NotBlank String referenceNumber,
        @NotNull BigDecimal statementAmount,
        // Optional — when omitted, the service auto-resolves the system
        // amount from paid payments in the same currency on or before
        // statementDate. Operators can still pass an override.
        BigDecimal systemAmount,
        @NotNull String currencyCode,
        @NotNull LocalDate statementDate,
        String notes
) {}
