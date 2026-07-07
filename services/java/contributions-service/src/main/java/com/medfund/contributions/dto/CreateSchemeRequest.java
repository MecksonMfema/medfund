package com.medfund.contributions.dto;

import com.medfund.shared.validation.EndOfMonth;
import com.medfund.shared.validation.FirstOfMonth;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateSchemeRequest(
        @NotBlank @Size(max = 200) String name,
        String description,
        String schemeType,
        // TODO: cross-check against the tenant's configured insuranceLines once
        //  the contributions-service has a TenantClient. Today the API accepts
        //  any of the 8 known lines and trusts that the Angular form has
        //  restricted the dropdown to the tenant's enabled lines.
        String insuranceLine,
        @NotNull @FirstOfMonth LocalDate effectiveDate,
        @EndOfMonth LocalDate endDate,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode,

        /** V050 enrolment eligibility gate — inclusive lower bound. Null = unbounded. */
        @Min(0) @Max(120) Short minAge,
        /** V050 enrolment eligibility gate — inclusive upper bound. Null = unbounded. */
        @Min(0) @Max(120) Short maxAge
) {
    public String schemeTypeOrDefault() {
        return (schemeType == null || schemeType.isBlank()) ? "medical_aid" : schemeType;
    }
}
