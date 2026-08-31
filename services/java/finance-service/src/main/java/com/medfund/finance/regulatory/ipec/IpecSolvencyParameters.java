package com.medfund.finance.regulatory.ipec;

import java.math.BigDecimal;

/**
 * Named IPEC solvency parameters resolved from bundled YAML defaults
 * ({@code report-templates}... no wait, {@code regulatory-defaults/ZW_IPEC_SHORT_TERM/*.yaml})
 * with a rules-engine override slot planned for Phase 15's
 * {@code REGULATORY_PARAMETER} category (Phase 10 ships defaults only).
 */
public record IpecSolvencyParameters(
        BigDecimal minSolvencyRatio,
        BigDecimal minRequiredCapitalMultiplier,
        BigDecimal reserveHaircutPct,
        BigDecimal reinsuranceRecoverableHaircutPct) {

    /** Parameter keys as they appear in the YAML {@code parameters:} block. */
    public static final String KEY_MIN_SOLVENCY_RATIO = "min_solvency_ratio";
    public static final String KEY_MIN_REQUIRED_CAPITAL_MULTIPLIER = "min_required_capital_multiplier";
    public static final String KEY_RESERVE_HAIRCUT_PCT = "reserve_haircut_pct";
    public static final String KEY_REINSURANCE_RECOVERABLE_PCT_HAIRCUT = "reinsurance_recoverable_pct_haircut";
}
