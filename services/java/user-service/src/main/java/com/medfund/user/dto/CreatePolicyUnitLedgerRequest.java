package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Body for {@code POST /api/v1/underwriting/funds/{fundId}/ledger} — append
 * a unit ledger row for a policy on a fund. Called by the policy issuance
 * flow on initial VFA policy issuance and on subsequent fund switches.
 */
public record CreatePolicyUnitLedgerRequest(
        @Schema(description = "Policy the ledger row belongs to")
        @NotNull
        UUID policyId,

        @Schema(description = "Transaction date", example = "2026-08-27")
        @NotNull
        LocalDate transactionDate,

        @Schema(description = "PURCHASE | SALE | ROLLOVER | FEE_DEDUCTION | FUND_SWITCH_IN | FUND_SWITCH_OUT",
                example = "PURCHASE")
        @NotBlank
        @Pattern(regexp = "PURCHASE|SALE|ROLLOVER|FEE_DEDUCTION|FUND_SWITCH_IN|FUND_SWITCH_OUT")
        String transactionType,

        @Schema(description = "Units traded (positive number; type determines sign of balance impact)",
                example = "1000.000000")
        @NotNull
        @DecimalMin(value = "0.000001", message = "units must be > 0")
        BigDecimal units,

        @Schema(description = "Price per unit at transaction (must be > 0)", example = "1.2340")
        @NotNull
        @DecimalMin(value = "0.000001", message = "price must be > 0")
        BigDecimal price
) {
}
