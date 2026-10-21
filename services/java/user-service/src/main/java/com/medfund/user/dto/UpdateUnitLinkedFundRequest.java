package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code PUT /api/v1/underwriting/funds/{id}} — update a fund's
 * name, asset class or active flag. Currency is immutable once created
 * (would invalidate NAV history + policy unit ledger denominations).
 */
public record UpdateUnitLinkedFundRequest(
        @Schema(description = "Fund name")
        @NotBlank
        @Size(max = 200)
        String name,

        @Schema(description = "Asset class")
        @NotBlank
        @Pattern(regexp = "EQUITY|FIXED_INCOME|MULTI_ASSET|MONEY_MARKET|REAL_ESTATE|OTHER")
        String baseAssetClass,

        @Schema(description = "Active flag - inactive funds are hidden from new-policy pickers but retain history")
        @NotNull
        Boolean isActive
) {
}
