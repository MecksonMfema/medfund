package com.medfund.finance.actuarial.service;

import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.fact.TriangleFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Fires the {@code ACTUARIAL} rules-engine agenda group against a
 * {@link TriangleFact} built from an IBNR / loss-triangle submission and
 * returns the {@code ldfMethod} the tenant's rules selected. Callers use
 * the returned method as the {@code ldfMethod} job parameter when
 * publishing to ai-service's chain-ladder compute.
 *
 * <p>The rules-engine returns the empty list for non-{@code ClaimFact}
 * evaluations (see {@link RuleEvaluationService#evaluate}), so the caller
 * reads the mutated ldfMethod straight off the fact instance rather than
 * from the returned {@link RuleResult} list — same idiom as
 * {@code CommissionCalcService.fireCommissionRules(...)}.
 *
 * <p>Absent any matching rule the default {@code volume} pre-seeded on the
 * fact stays put — the caller sees {@code volume} and the pipeline runs
 * on chainladder-python's volume-weighted default.
 *
 * <p>Rule name of the highest-priority fired rule (if any) is surfaced
 * separately via {@link Selection#ruleName()} for the XLSX header audit
 * line ("Rule applied: <name>").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActuarialRulesEvaluator {

    static final String AGENDA_GROUP = "ACTUARIAL";
    static final String DEFAULT_METHOD = "volume";

    private final TenantRuleLoader tenantRuleLoader;
    private final RuleEvaluationService ruleEvaluationService;

    /**
     * Fire ACTUARIAL rules for {@code tenantId} against the passed
     * {@code request}. Returns a {@link Selection} carrying the selected
     * LDF method and the name of the last-firing rule (empty when no
     * rule fired).
     */
    public Mono<Selection> selectMethod(UUID tenantId, ActuarialRuleRequest request) {
        TriangleFact fact = buildFact(request);
        return tenantRuleLoader.ensureLoaded(tenantId)
                .then(ruleEvaluationService.evaluateInGroup(
                        tenantId.toString(), AGENDA_GROUP, fact))
                .map(ignored -> Selection.from(fact))
                .onErrorResume(err -> {
                    log.warn("[actuarial-rules] tenant {} rule evaluation failed — falling back to '{}' ({})",
                            tenantId, DEFAULT_METHOD, err.getMessage());
                    return Mono.just(new Selection(DEFAULT_METHOD, null));
                });
    }

    private TriangleFact buildFact(ActuarialRuleRequest request) {
        TriangleFact fact = new TriangleFact();
        fact.setInsuranceLine(request.insuranceLine() != null ? request.insuranceLine() : "ALL");
        fact.setGrain(request.grain());
        fact.setPeriodStart(request.periodStart());
        fact.setPeriodEnd(request.periodEnd());
        fact.setReportingCurrency(request.reportingCurrency());
        // Default already 'volume' on the fact — leave it so a no-op sweep
        // still reads the safe default.
        return fact;
    }

    /** Immutable pass-through request. */
    public record ActuarialRuleRequest(
            String insuranceLine,
            String grain,
            LocalDate periodStart,
            LocalDate periodEnd,
            String reportingCurrency) {}

    /**
     * Outcome of the rules sweep. {@link #method()} is always populated
     * (defaults to {@code volume}); {@link #ruleName()} is null when no
     * rule fired.
     */
    public record Selection(String method, String ruleName) {

        static Selection from(TriangleFact fact) {
            List<RuleResult> results = fact.getResults();
            if (results == null || results.isEmpty()) {
                return new Selection(
                        fact.getLdfMethod() != null ? fact.getLdfMethod() : DEFAULT_METHOD,
                        null);
            }
            // The last SELECT_LDF result wins — Drools fires higher-salience
            // first, so the last-fired (lowest salience among matches) is the
            // most specific override. RuleResult carries the method in code
            // and the audit message in message.
            RuleResult last = results.get(results.size() - 1);
            return new Selection(
                    fact.getLdfMethod() != null ? fact.getLdfMethod() : DEFAULT_METHOD,
                    last.getMessage());
        }
    }
}
