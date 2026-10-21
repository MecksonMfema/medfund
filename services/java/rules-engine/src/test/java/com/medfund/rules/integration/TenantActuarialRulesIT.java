package com.medfund.rules.integration;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.compiler.SelectLdfEmitter;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.TriangleFact;
import com.medfund.rules.model.Condition;
import com.medfund.rules.model.ConditionGroup;
import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression for the ACTUARIAL rule category: a hand-authored rule
 * must select the correct LDF method on a {@link TriangleFact} when fired via
 * the ACTUARIAL agenda group. Together with {@code SelectLdfEmitterTest}
 * (unit level on the emitter) and {@code ActuarialTemplatesTest} (seed
 * templates), this test pins the "author → compile → load → evaluate" pipeline
 * consumed by finance-service's {@code TriangleShapingService} in Phase 16.
 *
 * <p>The agenda-group gate is the load-bearing invariant: the same rule must
 * <em>not</em> fire during a stage-7 (unfocused) sweep. Both branches are
 * asserted.
 */
class TenantActuarialRulesIT {

    private static final String TENANT = "tenant-actuarial-it";

    @Test
    void handAuthoredRule_selectsLdfMethodOnFocusedFire() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectLdfEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules(TENANT, List.of(healthFiveYearRule()));

        TriangleFact fact = triangle("HEALTH");
        engine.evaluateInGroup(TENANT, "ACTUARIAL", fact);

        assertThat(fact.getLdfMethod()).isEqualTo("5yr");
        assertThat(fact.getResults()).hasSize(1);
        assertThat(fact.getResults().get(0).getType()).isEqualTo("SELECT_LDF");
        assertThat(fact.getResults().get(0).getCode()).isEqualTo("5yr");
    }

    @Test
    void handAuthoredRule_stayDormantDuringUnfocusedSweep() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectLdfEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules(TENANT, List.of(healthFiveYearRule()));

        TriangleFact fact = triangle("HEALTH");
        // Plain evaluate() — no agenda focus. Agenda-gated rules must not fire.
        engine.evaluate(TENANT, fact);

        assertThat(fact.getLdfMethod())
                .as("agenda-gated rule must stay dormant during the stage-7 sweep")
                .isEqualTo("volume");
        assertThat(fact.getResults()).isEmpty();
    }

    @Test
    void handAuthoredRule_missingMatchLeavesDefaultVolume() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectLdfEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules(TENANT, List.of(healthFiveYearRule()));

        TriangleFact fact = triangle("LIFE");   // rule targets HEALTH only
        engine.evaluateInGroup(TENANT, "ACTUARIAL", fact);

        assertThat(fact.getLdfMethod()).isEqualTo("volume");
        assertThat(fact.getResults()).isEmpty();
    }

    private RuleDefinition healthFiveYearRule() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("HEALTH 5yr weighted after 2024");
        rule.setCategory("ACTUARIAL");
        rule.setPriority(80);
        rule.setStatus("ACTIVE");
        rule.setVersion(1);
        rule.setEnabled(true);

        Condition line = new Condition();
        line.setField("triangle.insuranceLine");
        line.setOperator("EQUALS");
        line.setValue("HEALTH");

        ConditionGroup group = new ConditionGroup();
        group.setOperator("AND");
        group.setItems(List.of(line));
        rule.setConditions(group);

        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue("LDF_METHOD:5yr");
        action.setMessage("HEALTH 5-year weighted (IT)");
        rule.setAction(action);
        return rule;
    }

    private TriangleFact triangle(String line) {
        TriangleFact fact = new TriangleFact();
        fact.setInsuranceLine(line);
        fact.setGrain("quarter");
        fact.setPeriodStart(LocalDate.parse("2024-01-01"));
        fact.setPeriodEnd(LocalDate.parse("2026-06-30"));
        fact.setCohortCount(10);
        fact.setReportingCurrency("USD");
        return fact;
    }
}
