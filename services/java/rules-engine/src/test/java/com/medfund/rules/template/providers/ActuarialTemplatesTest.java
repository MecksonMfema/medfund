package com.medfund.rules.template.providers;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.compiler.SelectLdfEmitter;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.TriangleFact;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActuarialTemplatesTest {

    private final ActuarialTemplates provider = new ActuarialTemplates();

    @Test
    void category_isActuarial() {
        assertThat(provider.category()).isEqualTo(RuleCategory.ACTUARIAL);
    }

    @Test
    void templates_shipsThreeStartingPoints() {
        List<RuleDefinition> templates = provider.templates();
        assertThat(templates).hasSize(3);
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.getCategory()).isEqualTo("ACTUARIAL");
            assertThat(t.getAction().getType()).isEqualTo("SELECT_LDF");
            assertThat(t.getAction().getValue()).asString().startsWith("LDF_METHOD:");
        });
    }

    @Test
    void templates_compileToAgendaGatedDrl() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectLdfEmitter()));

        for (RuleDefinition template : provider.templates()) {
            String drl = compiler.compile(template);
            assertThat(drl)
                    .as("template '%s' must be agenda-gated so it doesn't fire in the stage-7 sweep",
                        template.getName())
                    .contains("agenda-group \"ACTUARIAL\"");
            assertThat(drl).contains("$triangle.selectLdf(");
        }
    }

    @Test
    void healthTriangle_selects5YrLdfWhenLoadedIntoEngine() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectLdfEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-health", provider.templates());

        TriangleFact fact = healthTriangle();
        engine.evaluateInGroup("t-health", "ACTUARIAL", fact);

        assertThat(fact.getLdfMethod()).isEqualTo("5yr");
        assertThat(fact.getResults()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(fact.getResults().get(0).getType()).isEqualTo("SELECT_LDF");
    }

    @Test
    void funeralTriangle_selectsSimpleAverageWhenLoadedIntoEngine() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectLdfEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-funeral", provider.templates());

        TriangleFact fact = triangleFor("FUNERAL");
        engine.evaluateInGroup("t-funeral", "ACTUARIAL", fact);

        // A81 (priority 90) beats A80 (priority 100) — Drools fires higher-salience first.
        // For FUNERAL both A80 (any line) and A81 (funeral-only) match; A81 wins on
        // its more-specific match by using a lower priority number (fires later,
        // overrides earlier). Cross-check via Drools' salience semantics.
        // (Higher salience fires first; last-write-wins on the fact.)
        assertThat(fact.getLdfMethod()).isEqualTo("simple");
    }

    @Test
    void lifeTriangle_fallsBackToVolumeDefault() {
        // A80 matches any of the six listed lines including LIFE; no more-specific
        // rule targets LIFE, so the volume-weighted default sticks.
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectLdfEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-life", provider.templates());

        TriangleFact fact = triangleFor("LIFE");
        engine.evaluateInGroup("t-life", "ACTUARIAL", fact);

        assertThat(fact.getLdfMethod()).isEqualTo("volume");
    }

    private TriangleFact healthTriangle() {
        return triangleFor("HEALTH");
    }

    private TriangleFact triangleFor(String line) {
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
