package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Updates the mutable fields of a {@code us_tenant_naic_config} row. The
 * (tenant_id, effective_from) tuple is immutable — a change to any of
 * those requires adding a new effective-dated row rather than editing in
 * place, which preserves the historical audit trail of which identity
 * was applied to which reporting period.
 *
 * <p>All fields are optional on update; nulls mean "leave the value as-is".
 * Blank values are rejected upstream — pass null explicitly to clear an
 * optional field (only {@code naicGroupCode}, {@code effectiveTo} and
 * {@code sourceNote} are nullable in the DB).
 */
public record UpdateUsTenantNaicConfigRequest(
        @Pattern(regexp = "^[A-Z]{2}$",
                message = "state_domicile must be 2 uppercase letters when supplied")
        String stateDomicile,

        @Pattern(regexp = "^[0-9]{1,10}$",
                message = "naic_company_code must be 1-10 digits when supplied")
        String naicCompanyCode,

        @Pattern(regexp = "^[0-9]{1,10}$",
                message = "naic_group_code must be 1-10 digits when supplied")
        String naicGroupCode,

        @Pattern(regexp = "^[0-9]{2}-?[0-9]{7}$",
                message = "fein must be 9 digits when supplied")
        String fein,

        LocalDate effectiveTo,

        @Size(max = 200) String sourceNote
) {}
