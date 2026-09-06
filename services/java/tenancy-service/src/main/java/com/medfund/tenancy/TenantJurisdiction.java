package com.medfund.tenancy;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Regulator + insurance-line combined jurisdiction identifier, referenced from
 * {@code V131__tenant_jurisdiction.sql}. Persisted as free-form VARCHAR in
 * {@code public.tenants.jurisdiction_code} — the enum evolves faster than the
 * migration cadence and a DB CHECK constraint would force a migration on every
 * enum add.
 *
 * <p>Validation happens at tenancy-service PUT-tenant time; unknown values yield
 * 422. Existing rows carrying now-unknown values are tolerated on read (returned
 * as-is) so retired jurisdictions don't break tenant load.
 *
 * <p>Cross-regulator reports (AML/STR, TAX_WITHHELD_RETURN, VAT_RETURN) gate on
 * {@code tenant.country_code} via {@code @RequiresCountry} — not on this enum.
 */
public enum TenantJurisdiction {
    ZW_IPEC_SHORT_TERM("Zimbabwe - IPEC short-term insurance"),
    ZW_IPEC_LIFE("Zimbabwe - IPEC life insurance"),
    ZA_CMS_MEDICAL_SCHEME("South Africa - CMS medical scheme"),
    ZA_FSCA_SHORT_TERM("South Africa - FSCA short-term insurance"),
    ZA_FSCA_LONG_TERM("South Africa - FSCA long-term (life) insurance"),
    US_NAIC("United States - NAIC");

    private final String displayLabel;

    TenantJurisdiction(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }

    /** Parse a stored code; returns empty for null/blank or unknown values. */
    public static Optional<TenantJurisdiction> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(TenantJurisdiction.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Comma-separated list of enum names, for use in validation error messages. */
    public static String validValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
