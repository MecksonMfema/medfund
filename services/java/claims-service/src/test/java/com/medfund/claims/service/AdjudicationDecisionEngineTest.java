package com.medfund.claims.service;

import com.medfund.claims.dto.AdjudicationResult.StageResult;
import com.medfund.claims.dto.AiSignals;
import com.medfund.claims.entity.Claim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision-matrix unit tests. Pure logic; no DB or HTTP mocks needed.
 */
class AdjudicationDecisionEngineTest {

    private AdjudicationDecisionEngine engine;
    private Claim claim;

    @BeforeEach
    void setUp() {
        engine = new AdjudicationDecisionEngine();
        claim = new Claim();
        claim.setClaimedAmount(new BigDecimal("100.00"));
    }

    private static List<StageResult> allPass() {
        return List.of(
                new StageResult("Eligibility", true, "ok"),
                new StageResult("WaitingPeriod", true, "ok"),
                new StageResult("BenefitLimits", true, "ok"),
                new StageResult("PreAuthorization", true, "ok"),
                new StageResult("TariffPricing", true, "ok"),
                new StageResult("ClinicalValidation", true, "ok"),
                new StageResult("TenantRules", true, "ok"));
    }

    @Test
    void allStagesPass_highConfidenceLowFraud_autoApproves() {
        var ai = new AiSignals("APPROVE", 0.95, null, "looks fine", List.of(),
                0.1, "LOW", List.of(), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai);

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.approvedAmount()).isEqualByComparingTo("100.00");
        assertThat(result.aiSignals()).isEqualTo(ai);
    }

    @Test
    void allStagesPass_aiAbsent_autoApprovesViaFailOpen() {
        var result = engine.decide(claim, allPass(), AiSignals.empty());

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.approvedAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void allStagesPass_aiSuggestsLowerAmount_approvesAtSuggestedAmount() {
        var ai = new AiSignals("APPROVE", 0.92,
                new BigDecimal("75.00"), "possible upcoding on line 2", List.of("POSSIBLE_UPCODING"),
                0.2, "LOW", List.of(), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai);

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.approvedAmount()).isEqualByComparingTo("75.00");
    }

    @Test
    void hardStageFailure_rejects() {
        var stages = List.of(
                new StageResult("Eligibility", false, "R01: member inactive"),
                new StageResult("WaitingPeriod", true, "ok"),
                new StageResult("BenefitLimits", true, "ok"),
                new StageResult("PreAuthorization", true, "ok"),
                new StageResult("TariffPricing", true, "ok"),
                new StageResult("ClinicalValidation", true, "ok"),
                new StageResult("TenantRules", true, "ok"));

        var result = engine.decide(claim, stages, AiSignals.empty());

        assertThat(result.decision()).isEqualTo("REJECTED");
        assertThat(result.rejectionCode()).isEqualTo("Eligibility");
        assertThat(result.rejectionNotes()).contains("R01");
    }

    @Test
    void confidentAiReject_rejects() {
        var ai = new AiSignals("REJECT", 0.91, null, "diagnosis-procedure mismatch", List.of(),
                0.2, "LOW", List.of(), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai);

        assertThat(result.decision()).isEqualTo("REJECTED");
        assertThat(result.rejectionCode()).isEqualTo("AI");
        assertThat(result.rejectionNotes()).contains("diagnosis-procedure mismatch");
    }

    @Test
    void unconfidentAiReject_doesNotReject() {
        var ai = new AiSignals("REJECT", 0.5, null, "uncertain", List.of(),
                0.2, "LOW", List.of(), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai);

        // Confidence below auto-approve threshold → manual review, not reject.
        assertThat(result.decision()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.rejectionCode()).isEqualTo("AIConfidence");
    }

    @Test
    void onlySoftFailure_routesToManualReview() {
        var stages = List.of(
                new StageResult("Eligibility", true, "ok"),
                new StageResult("WaitingPeriod", false, "R02: 60 days into 90-day wait"),
                new StageResult("BenefitLimits", true, "ok"),
                new StageResult("PreAuthorization", true, "ok"),
                new StageResult("TariffPricing", true, "ok"),
                new StageResult("ClinicalValidation", true, "ok"),
                new StageResult("TenantRules", true, "ok"));

        var result = engine.decide(claim, stages, AiSignals.empty());

        assertThat(result.decision()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.rejectionCode()).isEqualTo("WaitingPeriod");
    }

    @Test
    void hardFailureAmongSoftFailures_stillRejects() {
        var stages = List.of(
                new StageResult("Eligibility", true, "ok"),
                new StageResult("WaitingPeriod", false, "R02"),
                new StageResult("BenefitLimits", false, "R03"),
                new StageResult("PreAuthorization", false, "R04: missing pre-auth"),
                new StageResult("TariffPricing", true, "ok"),
                new StageResult("ClinicalValidation", true, "ok"),
                new StageResult("TenantRules", true, "ok"));

        var result = engine.decide(claim, stages, AiSignals.empty());

        assertThat(result.decision()).isEqualTo("REJECTED");
        assertThat(result.rejectionCode()).isEqualTo("PreAuthorization");
    }

    @Test
    void highFraudRisk_routesToManualReview() {
        var ai = new AiSignals("APPROVE", 0.9, null, "ok", List.of(),
                0.75, "HIGH", List.of("provider_outlier"), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai);

        assertThat(result.decision()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.rejectionCode()).isEqualTo("FraudRisk");
        assertThat(result.rejectionNotes()).contains("HIGH");
    }

    @Test
    void duplicateMatch_routesToManualReview() {
        var ai = new AiSignals("APPROVE", 0.9, null, "ok", List.of(),
                0.1, "LOW", List.of(),
                "00000000-0000-0000-0000-000000000099", 0.92, "1.0.0");

        var result = engine.decide(claim, allPass(), ai);

        assertThat(result.decision()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.rejectionCode()).isEqualTo("DuplicateCheck");
        assertThat(result.rejectionNotes()).contains("00000000-0000-0000-0000-000000000099");
    }

    @Test
    void lowAiConfidence_routesToManualReview() {
        var ai = new AiSignals("APPROVE", 0.55, null, "uncertain", List.of(),
                0.1, "LOW", List.of(), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai);

        assertThat(result.decision()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.rejectionCode()).isEqualTo("AIConfidence");
    }

    // ── MODIFIER_ADJUSTMENT wiring — ruleAdjustedTotal parameter ──────────

    @Test
    void ruleAdjustedTotal_overridesClaimedAmountAsBase() {
        // Simulates a MODIFIER_ADJUSTMENT rule that halved a secondary
        // procedure — the pipeline sums per-line adjusted amounts to 50.00
        // and hands that to decide as the base. No AI cap ⇒ base wins.
        var ai = new AiSignals("APPROVE", 0.95, null, "looks fine", List.of(),
                0.1, "LOW", List.of(), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai, new BigDecimal("50.00"));

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.approvedAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void ruleAdjustedTotal_null_fallsBackToClaimedAmount() {
        // Pre-modifier behaviour: null ruleAdjustedTotal (no rule fired /
        // no rules loaded / no lines) means the base amount is the claim's
        // claimedAmount — identical to what the 3-arg overload does.
        var result = engine.decide(claim, allPass(), AiSignals.empty(), null);

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.approvedAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void ruleAdjustedTotal_aiSuggestionLower_aiSuggestionWins() {
        // Upcoding still trumps modifier adjustment when it comes in lower.
        var ai = new AiSignals("APPROVE", 0.92,
                new BigDecimal("30.00"), "possible upcoding", List.of("POSSIBLE_UPCODING"),
                0.2, "LOW", List.of(), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai, new BigDecimal("50.00"));

        assertThat(result.decision()).isEqualTo("APPROVED");
        assertThat(result.approvedAmount()).isEqualByComparingTo("30.00");
    }

    @Test
    void ruleAdjustedTotal_belowAiSuggestion_baseWins() {
        // Symmetric: when the rule-adjusted total is lower than the AI's
        // suggested cap, the rule wins.
        var ai = new AiSignals("APPROVE", 0.92,
                new BigDecimal("80.00"), "possible upcoding", List.of("POSSIBLE_UPCODING"),
                0.2, "LOW", List.of(), null, null, "1.0.0");

        var result = engine.decide(claim, allPass(), ai, new BigDecimal("50.00"));

        assertThat(result.approvedAmount()).isEqualByComparingTo("50.00");
    }
}
