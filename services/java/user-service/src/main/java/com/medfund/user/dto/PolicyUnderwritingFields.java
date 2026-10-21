package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Optional Phase 12 §A underwriting fields shared by every policy CreateXRequest
 * and UpdateXRequest. Populating {@code writtenPremium} + {@code currency} +
 * {@code coverageStart}/{@code coverageEnd} + {@code boundAt} is what triggers
 * {@code PolicyIssuedPublisher} on save; rows created without these are
 * treated as {@code LEGACY_NO_PREMIUM} backfill placeholders that don't earn.
 *
 * <p>All fields are nullable / optional at DTO time — Angular retrofit UI
 * populates them for legacy rows via update.
 */
public record PolicyUnderwritingFields(
        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal writtenPremium,

        @Size(min = 3, max = 3)
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code")
        String writtenPremiumCurrency,

        Instant boundAt,

        LocalDate coverageStart,

        LocalDate coverageEnd,

        UUID renewedFromPolicyId,

        UUID portfolioId,

        UUID cohortId
) {
    /** Convenience null-safe accessor for services that inline the wrapper as an optional field. */
    public static PolicyUnderwritingFields orEmpty(PolicyUnderwritingFields src) {
        return src != null ? src : new PolicyUnderwritingFields(
                null, null, null, null, null, null, null, null);
    }
}
