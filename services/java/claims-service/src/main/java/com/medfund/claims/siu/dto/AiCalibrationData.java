package com.medfund.claims.siu.dto;

import java.util.List;

/**
 * AI calibration payload for the {@code /reports/fraud/ai-calibration}
 * endpoint (§B Phase 11). Per-risk-level precision + recall — computed
 * only when the tenant has ≥ 50 confirmed cases in the window; below
 * that threshold {@link #rows} is empty and {@link #warnings} carries
 * the small-N explanation per FR11.
 *
 * <p>Precision = TP / (TP + FP); recall approximated as
 * TP / (TP + missed) where {@code missed} is 0 in the MVP formulation
 * (all confirmed cases in the window originated from AI flags). Recall
 * lands with the Phase 12 scheduled-adapter delivery of ground-truth
 * false-negatives.
 */
public record AiCalibrationData(
        List<CalibrationRow> rows,
        List<String> warnings
) {
    public record CalibrationRow(
            String riskLevel,
            long truePositives,
            long falsePositives,
            long totalFlags,
            String precision4dp
    ) {
    }
}
