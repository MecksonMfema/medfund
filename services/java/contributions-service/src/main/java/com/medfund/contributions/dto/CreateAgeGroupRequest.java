package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code effectiveFrom} is the DATE the seed price row lands on in
 * {@code age_group_prices.effective_from}. Optional: real operator
 * flows omit it and the backend defaults to {@code LocalDate.now()},
 * preserving the pre-existing "price effective today" behavior. The
 * demo-seeder and any historical-replay caller pass their timeline
 * anchor so previews for back-dated periods find a matching row via
 * the {@code age_group_prices} LATERAL join.
 */
public record CreateAgeGroupRequest(
        @NotNull UUID schemeId,
        @NotBlank String name,
        @NotNull Integer minAge,
        @NotNull Integer maxAge,
        @NotNull BigDecimal contributionAmount,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode,

        LocalDate effectiveFrom
) {
    /**
     * Backwards-compatible constructor for callsites that predate
     * {@code effectiveFrom}. Every existing test that positionally
     * builds a {@code CreateAgeGroupRequest} continues to compile
     * without modification; the backend defaults the price date to
     * today when this constructor is used.
     */
    public CreateAgeGroupRequest(UUID schemeId, String name,
                                  Integer minAge, Integer maxAge,
                                  BigDecimal contributionAmount,
                                  String currencyCode) {
        this(schemeId, name, minAge, maxAge, contributionAmount, currencyCode, null);
    }
}
