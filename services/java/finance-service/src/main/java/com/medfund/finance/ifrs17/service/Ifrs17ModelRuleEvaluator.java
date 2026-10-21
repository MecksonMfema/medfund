package com.medfund.finance.ifrs17.service;

import com.medfund.rules.fact.IfrsPortfolioFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Fires the {@code IFRS17_MODEL} rules-engine agenda group against a
 * {@link IfrsPortfolioFact} for one portfolio and returns the mutated fact so
 * the shaping service (§17) can extract the chosen measurement model + coverage
 * unit pattern + variable fee pattern + finance-expense presentation.
 *
 * <p>Mirrors the {@code ActuarialRulesEvaluator} pattern: hydrate tenant rules
 * via {@link TenantRuleLoader#ensureLoaded(UUID)}, then dispatch through
 * {@link RuleEvaluationService#evaluateInGroup(String, String, Object...)} so
 * ONLY the IFRS17_MODEL agenda group fires (per {@code DrlCompiler}'s
 * {@code AGENDA_GATED_CATEGORIES} configuration). The mutated fact carries the
 * outputs — callers read them directly rather than parsing {@code RuleResult}s.
 *
 * <p>Fallback: absent a matching rule, the fact's {@code measurementModel} stays
 * null. Callers should default to {@code PAA} when null (industry convention
 * for short-duration lines — matches the seed rules planted in V165).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17ModelRuleEvaluator {

    static final String AGENDA_GROUP = "IFRS17_MODEL";
    static final String DEFAULT_MODEL = "PAA";
    static final String DEFAULT_COVERAGE_UNIT_PATTERN = "TIME";
    static final String DEFAULT_FINANCE_EXPENSE_PRESENTATION = "PL_ONLY";

    private final TenantRuleLoader tenantRuleLoader;
    private final RuleEvaluationService ruleEvaluationService;

    /**
     * Build a fact for one portfolio + reporting context, fire IFRS17_MODEL
     * rules, and return the mutated fact. The caller reads
     * {@link IfrsPortfolioFact#getMeasurementModel()} etc.
     */
    public Mono<IfrsPortfolioFact> evaluate(UUID tenantId, IfrsPortfolioFactBuilder builder) {
        IfrsPortfolioFact fact = builder.build();
        return tenantRuleLoader.ensureLoaded(tenantId)
                .then(ruleEvaluationService.evaluateInGroup(
                        tenantId.toString(), AGENDA_GROUP, fact))
                .thenReturn(applyDefaults(fact))
                .onErrorResume(err -> {
                    log.warn("[ifrs17-model-rules] tenant {} portfolio {} rule evaluation failed — "
                                    + "falling back to defaults ({})",
                            tenantId, fact.getPortfolioId(), err.getMessage());
                    return Mono.just(applyDefaults(fact));
                });
    }

    /**
     * Applies industry-default fallbacks on the fact when no rule fires.
     * Mutation-in-place is safe because the fact is single-use per call.
     */
    private IfrsPortfolioFact applyDefaults(IfrsPortfolioFact fact) {
        if (fact.getMeasurementModel() == null) {
            fact.setMeasurementModel(DEFAULT_MODEL);
        }
        if (fact.getCoverageUnitPattern() == null) {
            fact.setCoverageUnitPattern(DEFAULT_COVERAGE_UNIT_PATTERN);
        }
        if (fact.getFinanceExpensePresentation() == null) {
            fact.setFinanceExpensePresentation(DEFAULT_FINANCE_EXPENSE_PRESENTATION);
        }
        return fact;
    }

    /**
     * Convenience builder — shaping supplies portfolio identity + reporting
     * context; the evaluator wires the fact + fires.
     */
    public static IfrsPortfolioFactBuilder factFor() {
        return new IfrsPortfolioFactBuilder();
    }

    public static final class IfrsPortfolioFactBuilder {
        private UUID portfolioId;
        private String insuranceLine;
        private Integer cohortYear;
        private String jurisdictionCode;
        private String reportingCurrency;
        private String portfolioName;
        private Instant portfolioCreatedAt;

        public IfrsPortfolioFactBuilder portfolioId(UUID v) { this.portfolioId = v; return this; }
        public IfrsPortfolioFactBuilder insuranceLine(String v) { this.insuranceLine = v; return this; }
        public IfrsPortfolioFactBuilder cohortYear(Integer v) { this.cohortYear = v; return this; }
        public IfrsPortfolioFactBuilder jurisdictionCode(String v) { this.jurisdictionCode = v; return this; }
        public IfrsPortfolioFactBuilder reportingCurrency(String v) { this.reportingCurrency = v; return this; }
        public IfrsPortfolioFactBuilder portfolioName(String v) { this.portfolioName = v; return this; }
        public IfrsPortfolioFactBuilder portfolioCreatedAt(Instant v) { this.portfolioCreatedAt = v; return this; }

        IfrsPortfolioFact build() {
            IfrsPortfolioFact f = new IfrsPortfolioFact();
            f.setPortfolioId(portfolioId);
            f.setInsuranceLine(insuranceLine);
            f.setCohortYear(cohortYear);
            f.setJurisdictionCode(jurisdictionCode);
            f.setReportingCurrency(reportingCurrency);
            f.setPortfolioName(portfolioName);
            f.setPortfolioCreatedAt(portfolioCreatedAt);
            return f;
        }
    }
}
