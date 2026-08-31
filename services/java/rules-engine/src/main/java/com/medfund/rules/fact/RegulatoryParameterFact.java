package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fact for the {@code REGULATORY_PARAMETER} rule category. Populated at
 * regulator-report compute time by finance-service's
 * {@code RegulatoryParameterResolver}; the {@code SetRegulatoryParameterEmitter}
 * fires rules that override a bundled YAML default for a numeric parameter
 * (IPEC {@code min_solvency_ratio}, NAIC {@code unauthorized_reinsurer_provision_percentage},
 * etc.) and record the chosen value on this fact for the caller to consume.
 *
 * <p>Read-only inputs (populated by the resolver) describe the parameter
 * being resolved; the rule filters on {@code parameterKey} +
 * {@code jurisdiction} and mutates {@code parameterValue} via
 * {@link #setParameterValue(BigDecimal, String)}.
 *
 * <p>Rules with {@code SET_REGULATORY_PARAMETER} actions run inside the
 * {@code REGULATORY_PARAMETER} agenda group and only fire when the caller
 * focuses that group via
 * {@code RuleEvaluationService.evaluateInGroup("REGULATORY_PARAMETER", …)}
 * — see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The caller reads the
 * mutated fact after the fire.
 */
@Getter
@Setter
@NoArgsConstructor
public class RegulatoryParameterFact {

    // ── Read-only inputs ────────────────────────────────────────────────────

    /** Parameter identifier as it appears in the YAML {@code parameters:} block. */
    private String parameterKey;

    /**
     * {@code TenantJurisdiction} name — {@code ZW_IPEC_SHORT_TERM},
     * {@code ZA_CMS_MEDICAL_SCHEME}, {@code US_NAIC}, ...
     */
    private String jurisdiction;

    /**
     * Report period-end for effective-dating rules ("only apply this
     * override from 2027-01-01 onwards"). Rules typically compare
     * {@code effectiveFrom} against this via a condition operator.
     */
    private LocalDate effectiveFrom;

    // ── Rule-mutated output ─────────────────────────────────────────────────

    /** Chosen parameter value. Null until a rule fires. */
    private BigDecimal parameterValue;

    /** Name of the rule that fired — captured for audit + envelope disclosure. */
    private String appliedRuleName;

    /** Trace of every rule mutation. Useful in dry-runs and audit. */
    private List<RuleResult> results = new ArrayList<>();

    /**
     * SET_REGULATORY_PARAMETER action — record the chosen value. Called from
     * DRL by {@code SetRegulatoryParameterEmitter}.
     *
     * @param value     the override value; must be non-null
     * @param ruleName  name of the firing rule, captured for audit
     */
    public void setParameterValue(BigDecimal value, String ruleName) {
        this.parameterValue = value;
        this.appliedRuleName = ruleName;
        this.results.add(new RuleResult("SET_REGULATORY_PARAMETER",
                value == null ? null : value.toPlainString(), ruleName));
    }
}
