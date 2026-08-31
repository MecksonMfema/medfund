package com.medfund.rules.template.providers;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.compiler.SetPmbClassificationEmitter;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.PmbClassificationFact;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PmbClassificationTemplatesTest {

    private final PmbClassificationTemplates provider = new PmbClassificationTemplates();

    @Test
    void category_isPmbClassification() {
        assertThat(provider.category()).isEqualTo(RuleCategory.PMB_CLASSIFICATION);
    }

    @Test
    void templates_shipsTwoStarters_diagnosisOnlyAndDiagnosisPlusProcedure() {
        List<RuleDefinition> templates = provider.templates();

        assertThat(templates).hasSize(2);
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.getCategory()).isEqualTo("PMB_CLASSIFICATION");
            assertThat(t.getAction().getType()).isEqualTo("SET_PMB_CLASSIFICATION");
            assertThat(t.getAction().getValue()).asString().startsWith("PMB_CONDITION_CODE:");
        });
        // First template: diagnosis-only. Second template: diagnosis + procedure.
        assertThat(templates.get(0).getConditions().getItems()).hasSize(1);
        assertThat(templates.get(1).getConditions().getItems()).hasSize(2);
    }

    @Test
    void templates_compileToAgendaGatedDrl() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetPmbClassificationEmitter()));

        for (RuleDefinition template : provider.templates()) {
            String drl = compiler.compile(template);
            assertThat(drl)
                    .as("template '%s' must be agenda-gated so it doesn't fire in the stage-7 sweep",
                        template.getName())
                    .contains("agenda-group \"PMB_CLASSIFICATION\"");
            assertThat(drl).contains("$pmbClassification.setPmbClassification(");
        }
    }

    @Test
    void diagnosisOnlyTemplate_firesWhenDiagnosisMatches() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetPmbClassificationEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-pmb", provider.templates());

        PmbClassificationFact fact = probe("A15.0", "9999");
        engine.evaluateInGroup("t-pmb", "PMB_CLASSIFICATION", fact);

        assertThat(fact.isPmb()).isTrue();
        assertThat(fact.getPmbConditionCode()).isEqualTo("PMB-001");
        // appliedRuleName is populated from the ACTION message per emitter
        // convention (see SetPmbClassificationEmitter — matches
        // SetRegulatoryParameterEmitter). The rule name itself is captured
        // elsewhere in the RuleResult trace.
        assertThat(fact.getAppliedRuleName()).contains("diagnosis");
    }

    @Test
    void unmatchedDiagnosis_leavesFactUntouched() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetPmbClassificationEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-pmb", provider.templates());

        PmbClassificationFact fact = probe("Z99.9", "0000");
        engine.evaluateInGroup("t-pmb", "PMB_CLASSIFICATION", fact);

        assertThat(fact.isPmb()).isFalse();
        assertThat(fact.getPmbConditionCode()).isNull();
        assertThat(fact.getResults()).isEmpty();
    }

    @Test
    void diagnosisPlusProcedureTemplate_firesOnlyWhenBothMatch() {
        // PMB2 template targets (N18.6, HD-CENTRE). Diagnosis-only match must not fire it.
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetPmbClassificationEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-pmb", provider.templates());

        PmbClassificationFact diagnosisOnly = probe("N18.6", "OTHER");
        engine.evaluateInGroup("t-pmb", "PMB_CLASSIFICATION", diagnosisOnly);
        assertThat(diagnosisOnly.isPmb()).isFalse();

        PmbClassificationFact bothMatch = probe("N18.6", "HD-CENTRE");
        engine.evaluateInGroup("t-pmb", "PMB_CLASSIFICATION", bothMatch);
        assertThat(bothMatch.isPmb()).isTrue();
        assertThat(bothMatch.getPmbConditionCode()).isEqualTo("PMB-070");
    }

    @Test
    void tenantIsolation_tenantARulesDoNotFireForTenantB() {
        // bug_rules_engine_tenant_isolation guard — same as REGULATORY_PARAMETER.
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetPmbClassificationEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-a", provider.templates());

        PmbClassificationFact factForB = probe("A15.0", "9999");
        engine.evaluateInGroup("t-b", "PMB_CLASSIFICATION", factForB);

        assertThat(engine.hasRulesLoaded("t-b"))
                .as("engine must not have implicitly loaded rules for tenant B")
                .isFalse();
        assertThat(factForB.isPmb())
                .as("tenant B has no rules — tenant A's PMB template must not fire")
                .isFalse();
    }

    private PmbClassificationFact probe(String diagnosis, String procedure) {
        PmbClassificationFact fact = new PmbClassificationFact();
        fact.setClaimId("claim-123");
        fact.setDiagnosisCode(diagnosis);
        fact.setProcedureCode(procedure);
        return fact;
    }
}
