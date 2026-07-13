package com.medfund.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Pre-authorization request payload.
 *
 * <p>{@code memberId} is always the sponsoring member (for member drill-through
 * and billing). {@code dependantId} is optional — when present, the pre-auth
 * applies to that dependant of the member. The adjudication pipeline looks
 * pre-auth up by {@code (dependantId, tariffCode)} when the claim carries a
 * dependant, otherwise by {@code (memberId, tariffCode, dependant_id IS NULL)}
 * so a member and one of their dependants can each hold their own pre-auth
 * for the same tariff code without collision.
 */
public record PreAuthRequest(
        @NotNull UUID memberId,
        UUID dependantId,
        @NotNull UUID providerId,
        @NotNull UUID schemeId,
        @NotBlank String tariffCode,
        String diagnosisCode,
        @NotNull BigDecimal requestedAmount,
        @NotBlank String currencyCode,
        String notes
) {
}
