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
        AiSignals aiSignals
) {
    /** Convenience constructor for callers that don't have AI signals. */
    public AdjudicationResult(String decision, BigDecimal approvedAmount,
                               String rejectionCode, String rejectionNotes,
                               List<StageResult> stageResults) {
        this(decision, approvedAmount, rejectionCode, rejectionNotes, stageResults, null);
    }

    public record StageResult(
            String stageName,
            boolean passed,
            String details
    ) {
    }
}
