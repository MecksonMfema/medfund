package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Fact for the {@code PMB_CLASSIFICATION} rule category (Phase 17 §B REG7).
 * Carries the single (diagnosis, procedure) pair currently being evaluated
 * against the tenant's seeded PMB rules; the classifier iterates the claim's
 * diagnosis / procedure code sets and calls the rule engine once per pair,
 * short-circuiting on the first match.
 *
 * <p>Read-only inputs (populated by the classifier) describe the code pair
 * being probed; the rule filters on {@code diagnosisCode} and (optionally)
 * {@code procedureCode} and mutates {@code isPmb} + {@code pmbConditionCode}
 * via {@link #setPmbClassification(String, String)}.
 *
 * <p>Rules with {@code SET_PMB_CLASSIFICATION} actions run inside the
 * {@code PMB_CLASSIFICATION} agenda group and only fire when the classifier
 * focuses that group via
 * {@code RuleEvaluationService.evaluateInGroup("PMB_CLASSIFICATION", …)}
 * — see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The caller reads the
 * mutated fact after the fire.
 */
@Getter
@Setter
@NoArgsConstructor
public class PmbClassificationFact {

    // ── Read-only inputs ────────────────────────────────────────────────────

    /** Claim id as string — for audit trace. */
    private String claimId;

    /** ICD-10 diagnosis code currently under evaluation (single value; the
     *  classifier iterates the claim's diagnosis list). */
    private String diagnosisCode;

    /** Tariff / procedure code currently under evaluation (single value;
     *  nullable when the claim has no lines). */
    private String procedureCode;

    // ── Rule-mutated outputs ────────────────────────────────────────────────

    /** TRUE once a rule fires. Read back by the classifier after the fire. */
    private boolean isPmb;

    /** CMS PMB condition code recorded by the winning rule. Null when
     *  {@code isPmb} is FALSE. */
    private String pmbConditionCode;

    /** Name of the rule that fired — captured for audit + envelope disclosure. */
    private String appliedRuleName;

    /** Trace of every rule mutation. Useful in dry-runs and audit. */
    private List<RuleResult> results = new ArrayList<>();

    /**
     * SET_PMB_CLASSIFICATION action — flag the fact as PMB and record the
     * matched condition code. Called from DRL by
     * {@code SetPmbClassificationEmitter}.
     *
     * @param conditionCode CMS PMB condition code (e.g. "PMB-001"); a null or
     *   blank value skips the mutation entirely so a typo in a tenant rule
     *   can't silently mark a claim as PMB without a code
     * @param ruleName name of the firing rule, captured for audit
     */
    public void setPmbClassification(String conditionCode, String ruleName) {
        if (conditionCode == null || conditionCode.isBlank()) {
            this.results.add(new RuleResult("SET_PMB_CLASSIFICATION", null, ruleName));
            return;
        }
        this.isPmb = true;
        this.pmbConditionCode = conditionCode;
        this.appliedRuleName = ruleName;
        this.results.add(new RuleResult("SET_PMB_CLASSIFICATION", conditionCode, ruleName));
    }
}
