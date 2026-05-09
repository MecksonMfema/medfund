package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI-derived signals consulted by {@link com.medfund.claims.service.AdjudicationDecisionEngine}
 * alongside the deterministic stage results. All fields are nullable — the
 * pipeline fails open when the AI service is unavailable, so the decision
 * engine treats nulls as "no signal" rather than negative.
 *
 * @param recommendation     APPROVE | REJECT | REVIEW (free-form from the
 *                           AI service; the engine only special-cases REJECT).
 * @param confidence         0.0–1.0; how confident the AI is in its
 *                           recommendation. Auto-approve requires &gt; 0.8.
 * @param suggestedAmount    AI's suggested approved amount (may be lower than
 *                           the claimed amount if it spotted upcoding).
 * @param reasoning          Free-form rationale; surfaced on the detail page.
 * @param flags              Coarse tags from the AI ("HIGH_VALUE",
 *                           "POSSIBLE_UPCODING", etc.).
 * @param fraudRisk          0.0–1.0; high values trigger manual review.
 * @param fraudLevel         LOW | MEDIUM | HIGH.
 * @param fraudIndicators    Specific indicators ("high_value_claim", etc.).
 * @param duplicateMatchId   Claim id of the suspected duplicate, or null.
 * @param duplicateScore     Match score 0.0–1.0; null when no candidate.
 * @param modelVersion       Model fingerprint for the audit trail.
 */
public record AiSignals(
        String recommendation,
        Double confidence,
        BigDecimal suggestedAmount,
        String reasoning,
        List<String> flags,
        Double fraudRisk,
        String fraudLevel,
        List<String> fraudIndicators,
        String duplicateMatchId,
        Double duplicateScore,
        String modelVersion
) {
    /** Empty signal — used when the AI service is unreachable. */
    public static AiSignals empty() {
        return new AiSignals(null, null, null, null, List.of(),
                null, null, List.of(), null, null, null);
    }
}
