package com.medfund.finance.regulatory.naic;

import java.math.BigDecimal;

/**
 * NAIC Schedule F provision-for-reinsurance percentages resolved from bundled
 * YAML defaults ({@code regulatory-defaults/US_NAIC/*.yaml}) with a
 * rules-engine override slot planned for Phase 15's
 * {@code REGULATORY_PARAMETER} category (Phase 13 ships defaults only).
 *
 * <p>Provision for reinsurance is a statutory deduction from surplus that
 * reflects credit risk on ceded balances — authorized (rated) reinsurers
 * carry no provision, unauthorized reinsurers carry 100 % of the ceded
 * liability unless collateral is posted, and certified reinsurers carry a
 * graduated percentage depending on their AM Best / S&amp;P rating (the
 * synthetic default of 20 % here is a mid-range placeholder).
 *
 * <p>Both percentages are expressed as decimals (1.00 = 100 %; 0.20 = 20 %).
 */
public record NaicScheduleFParameters(
        BigDecimal unauthorizedReinsurerProvisionPercentage,
        BigDecimal certifiedReinsurerProvisionPercentage) {

    /** Parameter keys as they appear in the YAML {@code parameters:} block. */
    public static final String KEY_UNAUTHORIZED_PROVISION_PCT = "unauthorized_reinsurer_provision_percentage";
    public static final String KEY_CERTIFIED_PROVISION_PCT = "certified_reinsurer_provision_percentage";
}
