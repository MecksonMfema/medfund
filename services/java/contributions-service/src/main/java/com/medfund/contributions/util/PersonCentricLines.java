package com.medfund.contributions.util;

import java.util.Set;

/**
 * Single source of truth for which insurance lines are person-centric — i.e.
 * priced and covered around an individual member — and therefore support
 * age-banded contributions and benefit caps. Asset-centric lines (VEHICLE,
 * PROPERTY) are priced from the insured asset's value, not the policyholder's
 * age, so age groups and scheme benefits do not apply and the corresponding
 * write endpoints reject with 422.
 *
 * <p>Mirrors {@code PERSON_CENTRIC_LINES} in
 * {@code clients/angular/src/app/core/models/insurance-lines.ts}. Keep the
 * two in sync.
 */
public final class PersonCentricLines {

    public static final Set<String> SET = Set.of(
        "HEALTH", "LIFE", "FUNERAL", "GROUP", "TRAVEL", "DISABILITY"
    );

    public static final Set<String> ALL_LINES = Set.of(
        "HEALTH", "LIFE", "FUNERAL", "GROUP", "TRAVEL", "DISABILITY",
        "VEHICLE", "PROPERTY"
    );

    private PersonCentricLines() {}

    public static boolean isPersonCentric(String line) {
        return line != null && SET.contains(line);
    }

    public static boolean isKnown(String line) {
        return line != null && ALL_LINES.contains(line);
    }
}
