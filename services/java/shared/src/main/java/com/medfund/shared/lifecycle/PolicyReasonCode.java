package com.medfund.shared.lifecycle;

import java.util.Map;
import java.util.Set;

/**
 * Phase 13 §A per L5 (grill note 1 resolution): per-line reason-code vocab for
 * policy status transitions. Central registry with a per-line valid-set map —
 * callers validate with {@link #isValid(String, String)} before writing a
 * {@code policy_status_history} row.
 *
 * <p>Codes are UPPER_SNAKE and stable: they land in the history table's
 * {@code reason_code} column and surface in operator tooling. Renaming one is
 * a breaking change.
 */
public final class PolicyReasonCode {

    public static final String NON_PAYMENT           = "NON_PAYMENT";
    public static final String POLICYHOLDER_CANCEL   = "POLICYHOLDER_CANCEL";
    public static final String INSURED_EVENT         = "INSURED_EVENT";
    public static final String MORTALITY             = "MORTALITY";
    public static final String RECOVERY              = "RECOVERY";
    public static final String TRIP_CANCELLED        = "TRIP_CANCELLED";
    public static final String SOLD                  = "SOLD";
    public static final String TOTAL_LOSS            = "TOTAL_LOSS";
    public static final String STORAGE_SUSPEND       = "STORAGE_SUSPEND";
    public static final String ADMIN_CORRECTION      = "ADMIN_CORRECTION";

    /**
     * Valid reason vocab per policy source. ADMIN_CORRECTION is universally
     * valid (the "other" arm every line shares).
     */
    public static final Map<String, Set<String>> VALID_REASONS_BY_SOURCE = Map.of(
        "LIFE_POLICY",       Set.of(NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT, MORTALITY, ADMIN_CORRECTION),
        "FUNERAL_POLICY",    Set.of(NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT, ADMIN_CORRECTION),
        "DISABILITY_POLICY", Set.of(NON_PAYMENT, POLICYHOLDER_CANCEL, INSURED_EVENT, RECOVERY, ADMIN_CORRECTION),
        "TRAVEL_POLICY",     Set.of(NON_PAYMENT, TRIP_CANCELLED, INSURED_EVENT, ADMIN_CORRECTION),
        "VEHICLE_POLICY",    Set.of(NON_PAYMENT, SOLD, TOTAL_LOSS, STORAGE_SUSPEND, POLICYHOLDER_CANCEL, ADMIN_CORRECTION),
        "PROPERTY_POLICY",   Set.of(NON_PAYMENT, SOLD, TOTAL_LOSS, POLICYHOLDER_CANCEL, ADMIN_CORRECTION)
    );

    public static boolean isValid(String source, String reasonCode) {
        Set<String> valid = VALID_REASONS_BY_SOURCE.get(source);
        return valid != null && valid.contains(reasonCode);
    }

    private PolicyReasonCode() {}
}
