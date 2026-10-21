package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/underwriting/funds} — create a new
 * unit-linked fund (VFA underlying-item container).
 */
public record CreateUnitLinkedFundRequest(
        @Schema(description = "Fund name (unique across tenant)", example = "Balanced Growth USD")
        @NotBlank
        @Size(max = 200)
        String name,

        @Schema(description = "ISO-4217 currency code", example = "USD")
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}")
        String currency,

        @Schema(description = "Asset class: EQUITY | FIXED_INCOME | MULTI_ASSET | MONEY_MARKET | REAL_ESTATE | OTHER",
                example = "MULTI_ASSET")
        @NotBlank
        @Pattern(regexp = "EQUITY|FIXED_INCOME|MULTI_ASSET|MONEY_MARKET|REAL_ESTATE|OTHER")
        String baseAssetClass
) {
}
