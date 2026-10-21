package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Fact for the {@code IFRS17_MODEL} rule category. Populated at report-submit
 * time by finance-service's {@code Ifrs17ShapingService}; the
 * {@code SelectIfrs17ModelEmitter} fires rules that pick an IFRS 17
 * measurement model and record it on this fact for downstream consumption
 * by the IFRS 17 chunk-shaping pipeline.
 *
 * <p>Read-only inputs (populated by the shaping service) describe the
 * portfolio; mutator methods invoked from DRL:
 * <ul>
 *   <li>{@link #applyModel(String, String, String, String, String)} — set the
 *       measurement model + supporting shape choices chosen by the fired rule
 *       (e.g. {@code PAA} / {@code GMM} / {@code VFA} plus coverage-unit
 *       pattern, variable-fee pattern, finance-expense presentation).</li>
 * </ul>
 *
 * <p>Rules with {@code SELECT_IFRS17_MODEL} actions run inside the
 * {@code IFRS17_MODEL} agenda group and only fire when the caller focuses
 * that group via {@code TenantRuleEngine.evaluateInGroup("IFRS17_MODEL", …)}
 * — see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The caller reads the
 * mutated fact after the fire.
 */
@Getter
@Setter
@NoArgsConstructor
public class IfrsPortfolioFact {

    // ── Read-only inputs ────────────────────────────────────────────────────

    /** Portfolio identifier (from {@code ifrs17_portfolio.id}). */
    private UUID portfolioId;

    /** Line-agnostic {@code InsuranceLine} name — {@code LIFE}, {@code HEALTH}, ... */
    private String insuranceLine;

    /** Cohort year (e.g. 2026). Used by templates for cohort-range filtering. */
    private Integer cohortYear;

    /** ISO-3166 alpha-2 jurisdiction code (e.g. {@code ZW}, {@code ZA}). */
    private String jurisdictionCode;

    /** Reporting currency (ISO-4217, e.g. {@code USD}). */
    private String reportingCurrency;

    /** Portfolio display name — human-readable, used in audit messages. */
    private String portfolioName;

    /** When the portfolio was created (from {@code ifrs17_portfolio.created_at}). */
    private Instant portfolioCreatedAt;

    // ── Rule-mutated outputs ────────────────────────────────────────────────

    /** Measurement model: {@code PAA} | {@code GMM} | {@code VFA}. Null until a rule fires. */
    private String measurementModel;

    /**
     * Coverage-unit pattern: {@code TIME} | {@code SUM_INSURED_TIME} |
     * {@code SUM_AT_RISK_TIME} | {@code CLAIM_FREQUENCY_TIME}. Null until a rule fires.
     */
    private String coverageUnitPattern;

    /**
     * Variable-fee pattern (VFA only): {@code FIXED_PCT} | {@code TIERED} |
     * {@code NAV_LINKED}. Null when {@code measurementModel} != {@code VFA}.
     */
    private String variableFeePattern;

    /** Finance-expense presentation: {@code PL_ONLY} | {@code OCI_OPTION}. Null until a rule fires. */
    private String financeExpensePresentation;

    /** Name of the rule that fired — captured for audit + envelope disclosure. */
    private String appliedRuleName;

    /** Trace of every rule mutation. Useful in dry-runs and audit. */
    private List<RuleResult> results = new ArrayList<>();

    /**
     * SELECT_IFRS17_MODEL action — record the chosen measurement model plus
     * supporting shape choices. Called from DRL by {@code SelectIfrs17ModelEmitter}.
     *
     * @param model     one of {@code PAA} / {@code GMM} / {@code VFA}
     * @param coverage  coverage-unit pattern (e.g. {@code TIME})
     * @param fee       variable-fee pattern for VFA (nullable for non-VFA)
     * @param expense   finance-expense presentation (e.g. {@code PL_ONLY})
     * @param ruleName  name of the firing rule, captured for audit
     */
    public void applyModel(String model, String coverage, String fee, String expense, String ruleName) {
        this.measurementModel = model;
        this.coverageUnitPattern = coverage;
        this.variableFeePattern = fee;
        this.financeExpensePresentation = expense;
        this.appliedRuleName = ruleName;
        this.results.add(new RuleResult("SELECT_IFRS17_MODEL", model, ruleName));
    }
}
