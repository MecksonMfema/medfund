package com.medfund.finance.regulatory.aml.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Staff-side raise payload for a new AML/STR alert. Backed by DB CHECK
 * constraints in V167 — the {@link Pattern} + {@link Size} validators
 * mirror them so we return 400 with a friendly message before the SQL
 * layer coughs 500.
 */
public record RaiseAmlAlertRequest(
        @NotBlank @Size(max = 120) String transactionRef,
        @NotBlank @Pattern(regexp = "PREMIUM|CLAIM_PAYOUT|ADVANCE_PAYMENT|REFUND|COMMISSION|ADJUSTMENT|OTHER",
                message = "must be one of PREMIUM|CLAIM_PAYOUT|ADVANCE_PAYMENT|REFUND|COMMISSION|ADJUSTMENT|OTHER")
        String transactionType,
        @DecimalMin(value = "0.01", message = "amountNative must be > 0")
        BigDecimal amountNative,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be ISO-4217 (3 uppercase letters)")
        String currency,
        UUID memberId,
        UUID providerId,
        @NotBlank @Size(min = 20, max = 2000,
                message = "description must be at least 20 characters (regulator narrative expected)")
        String description
) {}
