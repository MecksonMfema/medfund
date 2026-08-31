package com.medfund.finance.regulatory.cms;

import java.math.BigDecimal;

/**
 * Named CMS solvency + cost-ratio parameters resolved from bundled YAML
 * defaults ({@code regulatory-defaults/ZA_CMS_MEDICAL_SCHEME/*.yaml}) with
 * a rules-engine override slot planned for Phase 15's
 * {@code REGULATORY_PARAMETER} category (Phase 11 ships defaults only).
 *
 * <p>The Medical Schemes Act 131 of 1998 fixes {@code min_solvency_ratio}
 * at 25%; the other targets are CMS-recommended guidance rather than
 * statutory minima. Tenant admins can override to tighten (never loosen)
 * the ratio via the rules-engine path.
 */
public record CmsSolvencyParameters(
        BigDecimal minSolvencyRatio,
        BigDecimal nonHealthcareCostTarget,
        BigDecimal brokerFeesCap,
        BigDecimal managedCareFeesCap) {

    /** Parameter keys as they appear in the YAML {@code parameters:} block. */
    public static final String KEY_MIN_SOLVENCY_RATIO = "min_solvency_ratio";
    public static final String KEY_NON_HEALTHCARE_COST_TARGET = "non_healthcare_cost_target";
    public static final String KEY_BROKER_FEES_CAP = "broker_fees_cap";
    public static final String KEY_MANAGED_CARE_FEES_CAP = "managed_care_fees_cap";
}
