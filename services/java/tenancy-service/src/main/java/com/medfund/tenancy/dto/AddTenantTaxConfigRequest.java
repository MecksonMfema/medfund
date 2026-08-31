package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Adds one row to {@code public.tenant_tax_config} — a statutory tax
 * rate for one (tax_type, transaction_category, currency, effective_from)
 * tuple. Regex + range validators mirror the DB CHECK constraints so
 * callers get a clean 400 rather than a Postgres constraint violation.
 *
 * <p>{@code effectiveFrom} defaults to today when null; {@code effectiveTo}
 * is optional and null means "still effective".
 */
public record AddTenantTaxConfigRequest(
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$",
                message = "country_code must be 2 uppercase letters (ISO 3166-1 alpha-2)")
        String countryCode,

        @NotBlank @Pattern(regexp = "^(VAT|WITHHOLDING)$",
                message = "tax_type must be one of VAT | WITHHOLDING")
        String taxType,

        @NotBlank @Pattern(regexp = "^(PREMIUM|CLAIM_PAID|ADMIN_FEE|COMMISSION|OTHER)$",
                message = "transaction_category must be one of PREMIUM | CLAIM_PAID | ADMIN_FEE | COMMISSION | OTHER")
        String transactionCategory,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$",
                message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        @NotNull @DecimalMin(value = "0.00000", inclusive = true)
        @DecimalMax(value = "0.99999", inclusive = true)
        BigDecimal rate,

        Boolean registered,

        @Size(max = 80) String registrationNumber,

        LocalDate effectiveFrom,
        LocalDate effectiveTo,

        @Size(max = 200) String sourceNote
) {}
