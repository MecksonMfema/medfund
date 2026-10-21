package com.medfund.finance.actuarial.service;

import com.medfund.rules.fact.TriangleFact;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit-level assertions for the Phase-16 rules-engine bridge that finance-service
 * uses to pick an {@code ldfMethod} before publishing an IBNR/LOSS job. Pins:
 *
 * <ul>
 *   <li>Default fall-through when no rule fires ({@code volume}).</li>
 *   <li>Rule-mutated fact surfaces its selected method + audit-line rule name.</li>
 *   <li>Engine failure falls back to {@code volume} rather than propagating.</li>
 * </ul>
 */
class ActuarialRulesEvaluatorTest {

    private TenantRuleLoader tenantRuleLoader;
    private RuleEvaluationService ruleEvaluationService;
    private ActuarialRulesEvaluator evaluator;

    @BeforeEach
    void setUp() {
        tenantRuleLoader = mock(TenantRuleLoader.class);
        ruleEvaluationService = mock(RuleEvaluationService.class);
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        evaluator = new ActuarialRulesEvaluator(tenantRuleLoader, ruleEvaluationService);
    }

    @Test
    void selectMethod_noRuleFired_returnsVolumeDefault() {
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("ACTUARIAL"), any()))
                .thenReturn(Mono.just(List.of()));

        StepVerifier.create(evaluator.selectMethod(UUID.randomUUID(), request("HEALTH")))
                .assertNext(selection -> {
                    assertThat(selection.method()).isEqualTo("volume");
                    assertThat(selection.ruleName()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void selectMethod_ruleFires_returnsMutatedMethodAndRuleAuditLine() {
        // Emulate a rule firing on the TriangleFact by mutating the passed-in
        // instance — that's exactly what SelectLdfEmitter's DRL does at runtime.
        AtomicReference<TriangleFact> captured = new AtomicReference<>();
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("ACTUARIAL"), any()))
                .thenAnswer(invocation -> {
                    TriangleFact fact = (TriangleFact) invocation.getArgument(2);
                    captured.set(fact);
                    fact.selectLdf("5yr", "HEALTH 5-year weighted rule");
                    return Mono.just(List.of());
                });

        StepVerifier.create(evaluator.selectMethod(UUID.randomUUID(), request("HEALTH")))
                .assertNext(selection -> {
                    assertThat(selection.method()).isEqualTo("5yr");
                    assertThat(selection.ruleName()).isEqualTo("HEALTH 5-year weighted rule");
                })
                .verifyComplete();

        TriangleFact fact = captured.get();
        assertThat(fact.getInsuranceLine()).isEqualTo("HEALTH");
        assertThat(fact.getGrain()).isEqualTo("quarter");
        assertThat(fact.getReportingCurrency()).isEqualTo("USD");
    }

    @Test
    void selectMethod_engineFailure_fallsBackToVolume() {
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("ACTUARIAL"), any()))
                .thenReturn(Mono.error(new RuntimeException("KieContainer exploded")));

        StepVerifier.create(evaluator.selectMethod(UUID.randomUUID(), request("LIFE")))
                .assertNext(selection -> {
                    assertThat(selection.method()).isEqualTo("volume");
                    assertThat(selection.ruleName()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void selectMethod_nullInsuranceLine_defaultsAllOntoFact() {
        AtomicReference<TriangleFact> captured = new AtomicReference<>();
        when(ruleEvaluationService.evaluateInGroup(anyString(), eq("ACTUARIAL"), any()))
                .thenAnswer(invocation -> {
                    captured.set((TriangleFact) invocation.getArgument(2));
                    return Mono.just(List.of());
                });

        StepVerifier.create(evaluator.selectMethod(UUID.randomUUID(),
                        new ActuarialRulesEvaluator.ActuarialRuleRequest(
                                null, "quarter",
                                LocalDate.parse("2024-01-01"), LocalDate.parse("2026-06-30"), "USD")))
                .assertNext(selection -> assertThat(selection.method()).isEqualTo("volume"))
                .verifyComplete();

        assertThat(captured.get().getInsuranceLine()).isEqualTo("ALL");
    }

    private ActuarialRulesEvaluator.ActuarialRuleRequest request(String line) {
        return new ActuarialRulesEvaluator.ActuarialRuleRequest(
                line, "quarter",
                LocalDate.parse("2024-01-01"), LocalDate.parse("2026-06-30"),
                "USD");
    }
}
