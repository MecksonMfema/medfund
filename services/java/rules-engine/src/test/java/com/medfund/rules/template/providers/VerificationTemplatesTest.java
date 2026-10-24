package com.medfund.rules.template.providers;

import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.compiler.GateActivationEmitter;
import com.medfund.rules.compiler.RequireManualReviewEmitter;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.VerificationFact;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationTemplatesTest {

    private final VerificationTemplates provider = new VerificationTemplates();

    @Test
    void category_isVerification() {
        assertThat(provider.category()).isEqualTo(RuleCategory.VERIFICATION);
    }

    @Test
    void templates_shipTwoStarters_withExpectedActions() {
        List<RuleDefinition> templates = provider.templates();

        assertThat(templates).hasSize(2);
        assertThat(templates).allSatisfy(t ->
                assertThat(t.getCategory()).isEqualTo("VERIFICATION"));

        assertThat(templates.get(0).getAction().getType()).isEqualTo("REQUIRE_MANUAL_REVIEW");
        assertThat(templates.get(1).getAction().getType()).isEqualTo("GATE_ACTIVATION");

        assertThat(templates.get(0).getConditions().getItems()).hasSize(1);
        assertThat(templates.get(1).getConditions().getItems()).hasSize(1);
    }

    @Test
    void templates_compileToAgendaGatedDrl() {
        DrlCompiler compiler = verificationCompiler();

        for (RuleDefinition template : provider.templates()) {
            String drl = compiler.compile(template);
            assertThat(drl)
                    .as("template '%s' must be agenda-gated so it doesn't fire in the stage-7 sweep",
                        template.getName())
                    .contains("agenda-group \"VERIFICATION\"");
            String expectedEmit = "GATE_ACTIVATION".equals(template.getAction().getType())
                    ? "$verification.setGateActivation(true);"
                    : "$verification.setRequireManualReview(true);";
            assertThat(drl)
                    .as("template '%s' must emit the right flag mutation", template.getName())
                    .contains(expectedEmit);
        }
    }

    @Test
    void manualReviewTemplate_firesOnScreeningHit() {
        TenantRuleEngine engine = new TenantRuleEngine(verificationCompiler());
        engine.loadRules("t-verif-review", List.of(provider.templates().get(0)));

        VerificationFact hit = VerificationFact.builder().screeningResult("hit").build();
        engine.evaluateInGroup("t-verif-review", "VERIFICATION", hit);
        assertThat(hit.isRequireManualReview()).isTrue();

        VerificationFact clear = VerificationFact.builder().screeningResult("clear").build();
        engine.evaluateInGroup("t-verif-review", "VERIFICATION", clear);
        assertThat(clear.isRequireManualReview()).isFalse();
    }

    @Test
    void gateActivationTemplate_firesAtOrAboveRequiredTier() {
        TenantRuleEngine engine = new TenantRuleEngine(verificationCompiler());
        engine.loadRules("t-verif-gate", List.of(provider.templates().get(1)));

        VerificationFact belowTier = VerificationFact.builder().verificationLevel(1).build();
        engine.evaluateInGroup("t-verif-gate", "VERIFICATION", belowTier);
        assertThat(belowTier.isGateActivation()).isFalse();

        VerificationFact atTier = VerificationFact.builder().verificationLevel(2).build();
        engine.evaluateInGroup("t-verif-gate", "VERIFICATION", atTier);
        assertThat(atTier.isGateActivation()).isTrue();
    }

    @Test
    void tenantIsolation_tenantARulesDoNotFireForTenantB() {
        // bug_rules_engine_tenant_isolation guard.
        TenantRuleEngine engine = new TenantRuleEngine(verificationCompiler());
        engine.loadRules("t-a", provider.templates());

        VerificationFact factForB = VerificationFact.builder().screeningResult("hit").build();
        engine.evaluateInGroup("t-b", "VERIFICATION", factForB);

        assertThat(engine.hasRulesLoaded("t-b")).isFalse();
        assertThat(factForB.isRequireManualReview())
                .as("tenant B has no rules — tenant A's VERIFICATION templates must not fire")
                .isFalse();
    }

    private static DrlCompiler verificationCompiler() {
        return new DrlCompiler(List.of(
                new RequireManualReviewEmitter(),
                new GateActivationEmitter()));
    }
}
