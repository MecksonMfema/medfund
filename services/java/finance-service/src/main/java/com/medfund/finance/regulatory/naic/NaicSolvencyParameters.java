package com.medfund.finance.regulatory.naic;

import java.math.BigDecimal;

/**
 * Named NAIC solvency + loss-development parameters resolved from bundled
 * YAML defaults ({@code regulatory-defaults/US_NAIC/*.yaml}) with a
 * rules-engine override slot planned for Phase 15's
 * {@code REGULATORY_PARAMETER} category (Phase 12 ships defaults only).
 *
 * <p>NAIC Risk-Based Capital pegs Company Action Level at 200% of
 * required capital; loss-ratio watermarks are informational (not
 * statutory) and flag accident years for actuarial review.
 */
public record NaicSolvencyParameters(
        BigDecimal minRbcRatioCompanyActionLevel,
        BigDecimal ulaeRatio,
        BigDecimal lossRatioHighWatermark) {

    /** Parameter keys as they appear in the YAML {@code parameters:} block. */
    public static final String KEY_MIN_RBC_RATIO_COMPANY_ACTION_LEVEL = "min_rbc_ratio_company_action_level";
    public static final String KEY_ULAE_RATIO = "ulae_ratio";
    public static final String KEY_LOSS_RATIO_HIGH_WATERMARK = "loss_ratio_high_watermark";
}
