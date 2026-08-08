package com.medfund.claims.service;

import com.medfund.claims.dto.AdjudicationResult;
import com.medfund.claims.dto.AdjudicationResult.StageResult;
import com.medfund.claims.dto.AiSignals;
import com.medfund.claims.entity.Claim;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Combines the deterministic stage outcomes with AI signals to produce the
 * final {@link AdjudicationResult}. Pulled out of {@link AdjudicationPipeline}
 * so the decision matrix can be tested in isolation.
 *
 * <h3>Decision matrix</h3>
 *
 * <ul>
 *   <li><b>REJECTED</b> — any non-soft stage failed (Eligibility, PreAuth,
 *       TariffPricing, ClinicalValidation, TenantRules), OR the AI flagged
 *       the recommendation as REJECT with high confidence (&gt; 0.8).</li>
 *   <li><b>MANUAL_REVIEW</b> — only soft stages failed (WaitingPeriod,
 *       BenefitLimits — these can reflect waivers or partial approvals);
 *       OR the AI's confidence is &lt; 0.8; OR fraud risk is &gt; 0.6;
 *       OR a duplicate match was found.</li>
 *   <li><b>APPROVED</b> — every stage passed AND AI confidence &gt; 0.8
 *       (or AI signal absent — fail-open) AND fraud risk &lt; 0.6 (or
 *       absent) AND no duplicate match.</li>
 * </ul>
 *
 * <p>When the AI returns a {@code suggestedAmount} that's lower than the
 * claimed amount and the deterministic stages all pass, the decision is
 * promoted to <b>APPROVED</b> at the AI's suggested amount — this is how
 * upcoding gets handled automatically without needing manual review.
 */
@Service
public class AdjudicationDecisionEngine {

    /** Confidence threshold above which the AI is trusted to auto-approve. */
    static final double AUTO_APPROVE_CONFIDENCE = 0.8;

    /** Fraud-risk threshold above which manual review is forced. */
    static final double FRAUD_REVIEW_THRESHOLD = 0.6;

    /**
     * Stages whose failure flips a claim to MANUAL_REVIEW rather than
     * REJECTED. Tenants can override via waivers / partial approvals.
     */
    private static final List<String> SOFT_FAILURE_STAGES = List.of(
            "WaitingPeriod", "BenefitLimits");

    /**
     * Backwards-compat shim — callers without a rule-adjusted total fall through
     * to the claim's raw claimed amount as the base, matching pre-modifier behaviour.
     */
    public AdjudicationResult decide(Claim claim, List<StageResult> stages, AiSignals ai) {
        return decide(claim, stages, ai, null);
    }

    /**
     * @param ruleAdjustedTotal sum of per-line {@code ClaimDetailFact.approvedAmount}
     *                          after MODIFIER_ADJUSTMENT rules fired. When null (or
     *                          when no rule mutated the facts), the base for the
     *                          auto-approval amount falls back to
     *                          {@code claim.getClaimedAmount()}.
     */
    public AdjudicationResult decide(Claim claim, List<StageResult> stages, AiSignals ai,
                                     BigDecimal ruleAdjustedTotal) {
        AiSignals signals = ai != null ? ai : AiSignals.empty();

        boolean aiSaysReject = signals.recommendation() != null
                && "REJECT".equalsIgnoreCase(signals.recommendation())
                && signals.confidence() != null
                && signals.confidence() >= AUTO_APPROVE_CONFIDENCE;

        // Hard rejection — first non-soft stage failure or confident AI reject.
        StageResult hardFailure = firstHardFailure(stages);
        if (hardFailure != null) {
            return new AdjudicationResult(
                    "REJECTED", null,
                    hardFailure.stageName(),
                    hardFailure.details(),
                    stages, signals);
        }
        if (aiSaysReject) {
            return new AdjudicationResult(
                    "REJECTED", null,
                    "AI",
                    "AI flagged this claim for rejection: " + nullSafe(signals.reasoning()),
                    stages, signals);
        }

        // Soft failures — needs human eyes even when stages would otherwise pass.
        StageResult softFailure = firstSoftFailure(stages);
        if (softFailure != null) {
            return new AdjudicationResult(
                    "MANUAL_REVIEW", null,
                    softFailure.stageName(),
                    softFailure.details(),
                    stages, signals);
        }

        // AI guard rails — even with all stages passing, low confidence or
        // high fraud risk forces manual review.
        if (signals.duplicateMatchId() != null) {
            return new AdjudicationResult(
                    "MANUAL_REVIEW", null,
                    "DuplicateCheck",
                    "Possible duplicate of claim " + signals.duplicateMatchId(),
                    stages, signals);
        }
        if (signals.fraudRisk() != null && signals.fraudRisk() >= FRAUD_REVIEW_THRESHOLD) {
            return new AdjudicationResult(
                    "MANUAL_REVIEW", null,
                    "FraudRisk",
                    "Fraud risk " + signals.fraudRisk() + " (" + nullSafe(signals.fraudLevel())
                            + ") exceeds review threshold",
                    stages, signals);
        }
        if (signals.confidence() != null && signals.confidence() < AUTO_APPROVE_CONFIDENCE) {
            return new AdjudicationResult(
                    "MANUAL_REVIEW", null,
                    "AIConfidence",
                    "AI confidence " + signals.confidence() + " below auto-approve threshold "
                            + AUTO_APPROVE_CONFIDENCE,
                    stages, signals);
        }

        // All clear — auto-approve. Base amount is the modifier-adjusted total
        // when the tenant has MODIFIER_ADJUSTMENT rules that mutated any line's
        // approvedAmount; otherwise fall back to the raw claimed amount so
        // behaviour with no rules loaded is identical to the pre-modifier
        // pipeline. If the AI's suggestedAmount is lower still (typical upcoding
        // signal) it wins.
        BigDecimal baseAmount = ruleAdjustedTotal != null
                ? ruleAdjustedTotal
                : claim.getClaimedAmount();
        BigDecimal approved = (signals.suggestedAmount() != null
                && signals.suggestedAmount().compareTo(baseAmount) < 0)
                ? signals.suggestedAmount()
                : baseAmount;

        return new AdjudicationResult(
                "APPROVED", approved, null, null, stages, signals);
    }

    private StageResult firstHardFailure(List<StageResult> stages) {
        return stages.stream()
                .filter(s -> !s.passed())
                .filter(s -> !SOFT_FAILURE_STAGES.contains(s.stageName()))
                .findFirst().orElse(null);
    }

    private StageResult firstSoftFailure(List<StageResult> stages) {
        return stages.stream()
                .filter(s -> !s.passed())
                .filter(s -> SOFT_FAILURE_STAGES.contains(s.stageName()))
                .findFirst().orElse(null);
    }

    private String nullSafe(String s) {
        return s != null ? s : "";
    }
}
