package com.medfund.shared.insurance;

import java.util.Set;

/**
 * Single source of truth for the insurance lines this platform supports
 * and the rating/coverage shape each carries. Replaces
 * {@code contributions.util.PersonCentricLines} so tenancy-service,
 * user-service, claims-service and contributions-service all route
 * on the same enum without cross-service imports.
 *
 * <p>Person-centric lines (HEALTH, LIFE, FUNERAL, GROUP, TRAVEL,
 * DISABILITY) are priced around a member's identity — age, gender,
 * medical history, occupation. Asset-centric lines (MOTOR=VEHICLE,
 * PROPERTY) price the insured asset (a vehicle's value, a building's
 * sum insured) and only optionally reference an owner member for
 * invoice routing.
 *
 * <p>Mirror values in {@code clients/angular/src/app/core/models/insurance-lines.ts}
 * — keep the two in sync. The Angular constant {@code MOTOR} is a UI alias
 * for {@code VEHICLE} (legacy schemas use VEHICLE); both code values
 * resolve to the same enum here via {@link #from(String)}.
 */
public enum InsuranceLine {
    HEALTH(true),
    LIFE(true),
    FUNERAL(true),
    GROUP(true),
    TRAVEL(true),
    DISABILITY(true),
    VEHICLE(false),
    PROPERTY(false);

    private final boolean personCentric;

    InsuranceLine(boolean personCentric) {
        this.personCentric = personCentric;
    }

    public String code() {
        return name();
    }

    public boolean isPersonCentric() {
        return personCentric;
    }

    // ── Static helpers ────────────────────────────────────────────────

    /**
     * Resolve a string line code to the enum. Returns {@code null}
     * (rather than throwing) when the code isn't a known line — call
     * sites usually want to log + skip rather than 500.
     *
     * <p>Accepts the UI alias {@code MOTOR} as a synonym for
     * {@link #VEHICLE} so the Angular dropdown can use the friendlier
     * label without the storage layer having to migrate.
     */
    public static InsuranceLine from(String code) {
        if (code == null) return null;
        String normalised = code.trim().toUpperCase();
        if (normalised.equals("MOTOR")) return VEHICLE;
        try {
            return InsuranceLine.valueOf(normalised);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isPersonCentric(String code) {
        InsuranceLine line = from(code);
        return line != null && line.personCentric;
    }

    public static boolean isKnown(String code) {
        return from(code) != null;
    }

    /** Convenience for callers that want the set of line codes. */
    public static Set<String> allCodes() {
        return Set.of("HEALTH", "LIFE", "FUNERAL", "GROUP", "TRAVEL",
                "DISABILITY", "VEHICLE", "PROPERTY");
    }
}
