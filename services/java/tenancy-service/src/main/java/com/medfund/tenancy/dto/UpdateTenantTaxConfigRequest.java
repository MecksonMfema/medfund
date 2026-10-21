package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Updates the mutable fields of a {@code tenant_tax_config} row. The
 * (tenant_id, tax_type, transaction_category, currency, effective_from)
 * tuple is immutable — a change to any of those requires adding a new
 * effective-dated row rather than editing in place, which preserves the
 * historical audit trail of which rate was applied to which reporting
 * period.
 *
 * <p>All fields are optional on update; nulls mean "leave the value
 * as-is". Blank values are rejected upstream — pass null explicitly to
 * clear an optional field (only {@code registrationNumber},
 * {@code effectiveTo} and {@code sourceNote} are nullable in the DB).
 */
public record UpdateTenantTaxConfigRequest(
        @DecimalMin(value = "0.00000", inclusive = true)
        @DecimalMax(value = "0.99999", inclusive = true)
        BigDecimal rate,

        Boolean registered,

        @Size(max = 80) String registrationNumber,

        LocalDate effectiveTo,

        @Size(max = 200) String sourceNote
) {}
