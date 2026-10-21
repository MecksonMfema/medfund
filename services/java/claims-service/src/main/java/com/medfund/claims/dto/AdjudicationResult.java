package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.List;

public record AdjudicationResult(
        String decision,
        BigDecimal approvedAmount,
        String rejectionCode,
        String rejectionNotes,
        List<StageResult> stageResults,
        /** Nullable — the pipeline fails open if the AI service is unreachable. */
        AiSignals aiSignals,
        /**
         * Nullable — populated by {@code CostShareCalculator} on the auto-approve
         * branch only (Phase 2 copayments, G3). Reject / manual-review branches
         * skip the calculator so this stays null.
         */
        CostShareBreakdown costShare
) {
    /** Convenience constructor for callers that don't have AI signals. */
    public AdjudicationResult(String decision, BigDecimal approvedAmount,
                               String rejectionCode, String rejectionNotes,
                               List<StageResult> stageResults) {
        this(decision, approvedAmount, rejectionCode, rejectionNotes, stageResults, null, null);
    }

    /** Convenience constructor for callers with AI signals but no cost-share breakdown. */
    public AdjudicationResult(String decision, BigDecimal approvedAmount,
                               String rejectionCode, String rejectionNotes,
                               List<StageResult> stageResults, AiSignals aiSignals) {
        this(decision, approvedAmount, rejectionCode, rejectionNotes, stageResults, aiSignals, null);
    }

    public record StageResult(
            String stageName,
            boolean passed,
            String details
    ) {
    }

    /**
     * The 7 additive cost-share buckets computed at adjudication time (G3).
     * {@code approvedAmount} above equals {@code allowedAmount - memberResponsibility}
     * — the plan-paid portion. Consumers of the {@code medfund.claims.adjudicated}
     * event that only read {@code approvedAmount} continue to work unchanged.
     */
    public record CostShareBreakdown(
            BigDecimal allowedAmount,
            BigDecimal deductibleApplied,
            BigDecimal copayAmount,
            BigDecimal coinsuranceAmount,
            BigDecimal notCoveredAmount,
            BigDecimal shortfallAmount,
            BigDecimal memberResponsibility
    ) {
    }
}
