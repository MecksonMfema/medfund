package com.medfund.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Adds one row to {@code public.us_tenant_naic_config} — the per-tenant
 * NAIC identity used by the Schedule P / F report shapers. Regex
 * validators mirror the DB CHECK constraints so callers get a clean 400
 * rather than a Postgres constraint violation.
 *
 * <p>{@code effectiveFrom} defaults to today when null; {@code effectiveTo}
 * is optional and null means "still effective".
 */
public record AddUsTenantNaicConfigRequest(
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$",
                message = "state_domicile must be 2 uppercase letters (ISO 3166-2 subdivision code)")
        String stateDomicile,

        @NotBlank @Pattern(regexp = "^[0-9]{1,10}$",
                message = "naic_company_code must be 1-10 digits")
        String naicCompanyCode,

        @Pattern(regexp = "^[0-9]{1,10}$",
                message = "naic_group_code must be 1-10 digits when supplied")
        String naicGroupCode,

        @NotBlank @Pattern(regexp = "^[0-9]{2}-?[0-9]{7}$",
                message = "fein must be 9 digits, optionally hyphenated as NN-NNNNNNN")
        String fein,

        LocalDate effectiveFrom,
        LocalDate effectiveTo,

        @Size(max = 200) String sourceNote
) {}
