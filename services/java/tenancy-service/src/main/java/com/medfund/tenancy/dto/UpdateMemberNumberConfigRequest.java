package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * PUT body for /api/v1/tenants/{tenantId}/member-number-config.
 * The bounds mirror the CHECK constraints in V126 so a malformed
 * request is rejected with a friendly 422 before it ever hits the DB.
 */
public record UpdateMemberNumberConfigRequest(
        @NotBlank
        @Pattern(regexp = "INDEPENDENT|SHARED_WITH_SUFFIX",
                 message = "must be INDEPENDENT or SHARED_WITH_SUFFIX")
        String memberNumberScheme,

        @NotBlank String memberNumberPrefix,
        @NotBlank String dependantNumberPrefix,

        @Min(3) @Max(12)
        int memberNumberRandomLength,

        @NotBlank String memberNumberSuffixSeparator,

        @Min(1) @Max(4)
        int memberNumberSuffixPadding,

        @Min(0)
        int memberNumberSuffixStart
) {}
