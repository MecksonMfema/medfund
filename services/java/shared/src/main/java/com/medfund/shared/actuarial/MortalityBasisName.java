package com.medfund.shared.actuarial;

/**
 * Canonical catalogue of mortality reference tables the platform recognises.
 * Phase 3 ships a hardcoded starter set — Phase 6 flips the mortality-basis
 * admin dropdown to a live query against ai-service's
 * {@code /actuarial/basis-tables/list} endpoint. Keep this enum in sync with
 * the YAML files under {@code services/python/ai-service/app/actuarial/basis_tables/mortality/}
 * so the Java-side name validation and the Python compute layer agree.
 *
 * <p>Mirror in {@code clients/angular/src/app/shared/constants/actuarial-basis-names.ts}.
 */
public enum MortalityBasisName {
    A1949_52,
    A67_70,
    SA85_90,
    CSO_2017;

    /** Human-readable label used by the tenant-admin dropdown and audit lists. */
    public String displayName() {
        return switch (this) {
            case A1949_52 -> "A1949-52 Ultimate (ZW LIFE)";
            case A67_70   -> "A67-70 Ultimate (ZW LIFE alt)";
            case SA85_90  -> "SA85-90 (ZA group life)";
            case CSO_2017 -> "CSO 2017 (US NAIC)";
        };
    }

    /** Case-insensitive resolver; returns {@code null} for unknown codes so
     *  callers can log + skip rather than 500. */
    public static MortalityBasisName from(String code) {
        if (code == null) return null;
        try {
            return MortalityBasisName.valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isKnown(String code) {
        return from(code) != null;
    }
}
