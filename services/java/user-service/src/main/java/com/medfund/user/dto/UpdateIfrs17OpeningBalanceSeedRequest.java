package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body for {@code PUT /api/v1/underwriting/opening-balances/{id}}. Only
 * the amount + reasonNote are editable — the (portfolio, cohort, currency,
 * balance_type, effective_from) tuple is the seed's identity and cannot
 * be mutated. To retarget a different tuple, delete + re-add.
 */
public record UpdateIfrs17OpeningBalanceSeedRequest(
        @Schema(description = "Opening balance amount", example = "60000.00")
        @NotNull
        @DecimalMin(value = "0.00", message = "amount must be non-negative")
        BigDecimal amount,

        @Schema(description = "Operator rationale for the edit - captured on the audit event")
        @NotBlank
        @Size(max = 2000)
        String reasonNote
) {
}
