package com.medfund.rules.template.providers;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.compiler.SetRegulatoryParameterEmitter;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.RegulatoryParameterFact;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RegulatoryParameterTemplatesTest {

    private final RegulatoryParameterTemplates provider = new RegulatoryParameterTemplates();

    @Test
    void category_isRegulatoryParameter() {
        assertThat(provider.category()).isEqualTo(RuleCategory.REGULATORY_PARAMETER);
    }

    @Test
    void templates_shipsThreeStartingPointsCoveringIpecCmsAndNaic() {
        List<RuleDefinition> templates = provider.templates();

        assertThat(templates).hasSize(3);
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.getCategory()).isEqualTo("REGULATORY_PARAMETER");
            assertThat(t.getAction().getType()).isEqualTo("SET_REGULATORY_PARAMETER");
            assertThat(t.getAction().getValue()).asString().startsWith("PARAMETER_VALUE:");
        });
        // One template per shipped regulator YAML — IPEC, CMS, NAIC.
        assertThat(templates)
                .extracting(t -> t.getConditions().getItems().stream()
                        .filter(c -> "regulatoryParameter.jurisdiction".equals(c.getField()))
                        .findFirst().orElseThrow().getValue().toString())
                .containsExactlyInAnyOrder("ZW_IPEC_SHORT_TERM", "ZA_CMS_MEDICAL_SCHEME", "US_NAIC");
    }

    @Test
    void templates_compileToAgendaGatedDrl() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetRegulatoryParameterEmitter()));

        for (RuleDefinition template : provider.templates()) {
            String drl = compiler.compile(template);
            assertThat(drl)
                    .as("template '%s' must be agenda-gated so it doesn't fire in the stage-7 sweep",
                        template.getName())
                    .contains("agenda-group \"REGULATORY_PARAMETER\"");
            assertThat(drl).contains("$regulatoryParameter.setParameterValue(");
        }
    }

    @Test
    void ipecMinSolvencyRatio_firesWhenLoadedIntoEngine() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetRegulatoryParameterEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-ipec", provider.templates());

        RegulatoryParameterFact fact = paramFor("min_solvency_ratio", "ZW_IPEC_SHORT_TERM");
        engine.evaluateInGroup("t-ipec", "REGULATORY_PARAMETER", fact);

        assertThat(fact.getParameterValue()).isEqualByComparingTo(new BigDecimal("1.30"));
        assertThat(fact.getAppliedRuleName()).contains("IPEC");
        assertThat(fact.getResults()).hasSize(1);
        assertThat(fact.getResults().get(0).getType()).isEqualTo("SET_REGULATORY_PARAMETER");
    }

    @Test
    void unmatchedParameterKey_leavesFactUntouched() {
        // No template targets `not_a_real_parameter` — fact stays as caller built it.
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetRegulatoryParameterEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-ipec", provider.templates());

        RegulatoryParameterFact fact = paramFor("not_a_real_parameter", "ZW_IPEC_SHORT_TERM");
        engine.evaluateInGroup("t-ipec", "REGULATORY_PARAMETER", fact);

        assertThat(fact.getParameterValue()).isNull();
        assertThat(fact.getAppliedRuleName()).isNull();
        assertThat(fact.getResults()).isEmpty();
    }

    @Test
    void tenantIsolation_tenantARulesDoNotFireForTenantB() {
        // bug_rules_engine_tenant_isolation guard — two tenants, each with different
        // rule sets, must not cross-contaminate each other's KieContainer. Tenant B
        // never calls loadRules — the engine's pass-through short-circuit must not
        // reach for tenant A's KieContainer.
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SetRegulatoryParameterEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-a", provider.templates());

        RegulatoryParameterFact factForB = paramFor("min_solvency_ratio", "ZW_IPEC_SHORT_TERM");
        engine.evaluateInGroup("t-b", "REGULATORY_PARAMETER", factForB);

        assertThat(engine.hasRulesLoaded("t-b"))
                .as("engine must not have implicitly loaded rules for tenant B")
                .isFalse();
        assertThat(factForB.getParameterValue())
                .as("tenant B has no rules — tenant A's IPEC override must not fire")
                .isNull();
    }

    private RegulatoryParameterFact paramFor(String key, String jurisdiction) {
        RegulatoryParameterFact fact = new RegulatoryParameterFact();
        fact.setParameterKey(key);
        fact.setJurisdiction(jurisdiction);
        fact.setEffectiveFrom(LocalDate.of(2026, 6, 30));
        return fact;
    }
}
