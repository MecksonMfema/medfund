package com.medfund.shared.actuarial;

/**
 * Canonical catalogue of morbidity / disability-incidence reference tables the
 * platform recognises. Same lifecycle as {@link MortalityBasisName} — Phase 3
 * hardcoded, Phase 6 flips the dropdown to
 * {@code /actuarial/basis-tables/list?category=morbidity}. Keep in sync with
 * {@code services/python/ai-service/app/actuarial/basis_tables/morbidity/*.yaml}.
 *
 * <p>Mirror in {@code clients/angular/src/app/shared/constants/actuarial-basis-names.ts}.
 */
public enum MorbidityBasisName {
    CIDA,
    GLTD87;

    public String displayName() {
        return switch (this) {
            case CIDA   -> "CIDA (SA Continuous Investigation of Disability, 2001)";
            case GLTD87 -> "GLTD87 (SOA Group Long-Term Disability, 1987)";
        };
    }

    public static MorbidityBasisName from(String code) {
        if (code == null) return null;
        try {
            return MorbidityBasisName.valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isKnown(String code) {
        return from(code) != null;
    }
}
