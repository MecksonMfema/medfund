package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/underwriting/opening-balances} — creates
 * a tenant-admin override on the auto-derived opening balance for a
 * (portfolio, cohort, currency, balance_type, effective_from) tuple.
 *
 * <p>{@code reasonNote} is compulsory because the seed row displaces an
 * auto-derived balance — the audit trail needs the operator's rationale
 * captured at write time (per parent-plan Invariant #8: every entity
 * mutation must be audit-logged with why).
 */
public record CreateIfrs17OpeningBalanceSeedRequest(
        @Schema(description = "IFRS 17 portfolio the balance belongs to")
        @NotNull
        UUID portfolioId,

        @Schema(description = "IFRS 17 cohort the balance belongs to")
        @NotNull
        UUID cohortId,

        @Schema(description = "ISO-4217 currency code", example = "USD")
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}")
        String currency,

        @Schema(description = "LRC | LIC", example = "LRC")
        @NotBlank
        @Pattern(regexp = "LRC|LIC")
        String balanceType,

        @Schema(description = "Opening balance amount", example = "50000.00")
        @NotNull
        @DecimalMin(value = "0.00", message = "amount must be non-negative")
        BigDecimal amount,

        @Schema(description = "Effective date of the seed", example = "2026-01-01")
        @NotNull
        LocalDate effectiveFrom,

        @Schema(description = "Operator rationale for the manual override — captured on the audit event")
        @NotBlank
        @Size(max = 2000)
        String reasonNote
) {
}
