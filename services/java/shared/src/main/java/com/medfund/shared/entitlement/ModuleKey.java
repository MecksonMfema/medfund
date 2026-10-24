package com.medfund.shared.entitlement;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * Catalogue of sellable platform modules. Mirrors {@code PlatformFlag}'s
 * {@code (displayName, description)} shape, but — unlike a platform flag,
 * which is global-by-design — a module is gated PER TENANT via
 * {@code public.tenant_module_entitlements}. An absent row means NOT entitled
 * (a module is off until it is sold), the inverse of the sidebar-config default.
 *
 * <p>KYC is the first consumer. {@code KYC_BIOMETRICS} is a separately-sellable
 * add-on SKU (G2) so base document-KYC can be sold without biometrics. The
 * remaining values are placeholders for the horizontal roadmap — safe to carry
 * here before a consumer exists because an unknown key is opaque everywhere
 * else (the registry simply reads it as not-entitled).
 */
@Getter
@RequiredArgsConstructor
public enum ModuleKey {
    KYC("KYC & Verification", "Identity verification, document capture, sanctions/PEP screening"),
    // G2: biometrics is a SEPARATE sellable add-on SKU — base KYC can be sold without it.
    KYC_BIOMETRICS("Biometric Verification", "Face-match + liveness (add-on to KYC)"),
    // placeholders for the horizontal roadmap — safe to add (opaque elsewhere):
    CLAIMS("Claims", "Claims management"),
    BILLING("Billing & Contributions", "Premium billing and collection");

    private final String displayName;
    private final String description;

    /**
     * Parse a wire value into a {@link ModuleKey}, tolerating enum drift
     * between services mid-deploy (an unknown key yields empty rather than
     * throwing).
     */
    public static Optional<ModuleKey> parse(String raw) {
        try {
            return Optional.of(ModuleKey.valueOf(raw));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
