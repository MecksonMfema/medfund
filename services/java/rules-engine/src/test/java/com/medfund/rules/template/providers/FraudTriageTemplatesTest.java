package com.medfund.rules.template.providers;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.compiler.OpenSiuCaseEmitter;
import com.medfund.rules.compiler.SuppressSiuCaseEmitter;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.FraudFlagFact;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudTriageTemplatesTest {

    private final FraudTriageTemplates provider = new FraudTriageTemplates();

    @Test
    void category_isFraudTriage() {
        assertThat(provider.category()).isEqualTo(RuleCategory.FRAUD_TRIAGE);
    }

    @Test
    void templates_shipsSix_threeStartersPlusThreePatternRecognition() {
        List<RuleDefinition> templates = provider.templates();

        assertThat(templates).hasSize(6);
        assertThat(templates).allSatisfy(t ->
                assertThat(t.getCategory()).isEqualTo("FRAUD_TRIAGE"));

        // First five templates open cases; the sixth suppresses.
        assertThat(templates.get(0).getAction().getType()).isEqualTo("OPEN_SIU_CASE");
        assertThat(templates.get(1).getAction().getType()).isEqualTo("OPEN_SIU_CASE");
        assertThat(templates.get(2).getAction().getType()).isEqualTo("OPEN_SIU_CASE");
        assertThat(templates.get(3).getAction().getType()).isEqualTo("OPEN_SIU_CASE");
        assertThat(templates.get(4).getAction().getType()).isEqualTo("OPEN_SIU_CASE");
        assertThat(templates.get(5).getAction().getType()).isEqualTo("SUPPRESS_SIU_CASE");

        // Condition arity per template — regressions here mean the DSL shape drifted.
        assertThat(templates.get(0).getConditions().getItems()).hasSize(1);
        assertThat(templates.get(1).getConditions().getItems()).hasSize(2);
        assertThat(templates.get(2).getConditions().getItems()).hasSize(1);
        assertThat(templates.get(3).getConditions().getItems()).hasSize(2);
        assertThat(templates.get(4).getConditions().getItems()).hasSize(1);
        assertThat(templates.get(5).getConditions().getItems()).hasSize(1);

        // never-auto-open ships at salience 1 so it fires last per FR6.
        assertThat(templates.get(5).getPriority()).isEqualTo(1);
    }

    @Test
    void templates_compileToAgendaGatedDrl() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new OpenSiuCaseEmitter(),
                new SuppressSiuCaseEmitter()));

        for (RuleDefinition template : provider.templates()) {
            String drl = compiler.compile(template);
            assertThat(drl)
                    .as("template '%s' must be agenda-gated so it doesn't fire in the stage-7 sweep",
                        template.getName())
                    .contains("agenda-group \"FRAUD_TRIAGE\"");
            String expectedEmit = "SUPPRESS_SIU_CASE".equals(template.getAction().getType())
                    ? "$fraudFlag.setEmitCase(false);"
                    : "$fraudFlag.setEmitCase(true);";
            assertThat(drl)
                    .as("template '%s' must emit the right setEmitCase mutation", template.getName())
                    .contains(expectedEmit);
        }
    }

    @Test
    void thresholdTemplate_firesWhenRiskScoreExceedsMinimum() {
        TenantRuleEngine engine = fraudEngine();
        // Use only the threshold template to isolate the assertion from the
        // amount + watchlist rules (which key on ZERO claimAmount / null
        // providerId in the probe fact and could produce a false positive
        // via the watchlist EQUALS-null coincidence).
        engine.loadRules("t-fraud-threshold", List.of(provider.templates().get(0)));

        FraudFlagFact hit = FraudFlagFact.builder().riskScore(new BigDecimal("0.90")).build();
        engine.evaluateInGroup("t-fraud-threshold", "FRAUD_TRIAGE", hit);
        assertThat(hit.isEmitCase()).isTrue();

        FraudFlagFact miss = FraudFlagFact.builder().riskScore(new BigDecimal("0.50")).build();
        engine.evaluateInGroup("t-fraud-threshold", "FRAUD_TRIAGE", miss);
        assertThat(miss.isEmitCase()).isFalse();
    }

    @Test
    void thresholdAmountTemplate_firesOnlyWhenBothExceedMinima() {
        TenantRuleEngine engine = fraudEngine();
        engine.loadRules("t-fraud-amount", List.of(provider.templates().get(1)));

        FraudFlagFact scoreOnly = FraudFlagFact.builder()
                .riskScore(new BigDecimal("0.80"))
                .claimAmount(new BigDecimal("1000")).build();
        engine.evaluateInGroup("t-fraud-amount", "FRAUD_TRIAGE", scoreOnly);
        assertThat(scoreOnly.isEmitCase()).isFalse();

        FraudFlagFact bothMatch = FraudFlagFact.builder()
                .riskScore(new BigDecimal("0.80"))
                .claimAmount(new BigDecimal("6000")).build();
        engine.evaluateInGroup("t-fraud-amount", "FRAUD_TRIAGE", bothMatch);
        assertThat(bothMatch.isEmitCase()).isTrue();
    }

    // ── §B Phase 9 — pattern-recognition templates ───────────────────────

    @Test
    void repeatOffenderTemplate_firesOnlyWhenBothCountAndHighRiskMatch() {
        TenantRuleEngine engine = fraudEngine();
        // Isolate template index 3 (repeat-offender) so the fixture doesn't
        // accidentally trigger the threshold rule via its default 0.85 gate.
        engine.loadRules("t-fraud-repeat", List.of(provider.templates().get(3)));

        FraudFlagFact belowCount = FraudFlagFact.builder()
                .riskLevel("HIGH")
                .historicalMemberFlagCount(2)
                .build();
        engine.evaluateInGroup("t-fraud-repeat", "FRAUD_TRIAGE", belowCount);
        assertThat(belowCount.isEmitCase()).isFalse();

        FraudFlagFact notHigh = FraudFlagFact.builder()
                .riskLevel("MEDIUM")
                .historicalMemberFlagCount(5)
                .build();
        engine.evaluateInGroup("t-fraud-repeat", "FRAUD_TRIAGE", notHigh);
        assertThat(notHigh.isEmitCase()).isFalse();

        FraudFlagFact bothMatch = FraudFlagFact.builder()
                .riskLevel("HIGH")
                .historicalMemberFlagCount(4)
                .build();
        engine.evaluateInGroup("t-fraud-repeat", "FRAUD_TRIAGE", bothMatch);
        assertThat(bothMatch.isEmitCase()).isTrue();
    }

    @Test
    void providerHighFlagPatternTemplate_firesAtCountThreshold() {
        TenantRuleEngine engine = fraudEngine();
        engine.loadRules("t-fraud-provider", List.of(provider.templates().get(4)));

        FraudFlagFact below = FraudFlagFact.builder()
                .historicalProviderHighFlagCount(4).build();
        engine.evaluateInGroup("t-fraud-provider", "FRAUD_TRIAGE", below);
        assertThat(below.isEmitCase()).isFalse();

        FraudFlagFact atThreshold = FraudFlagFact.builder()
                .historicalProviderHighFlagCount(5).build();
        engine.evaluateInGroup("t-fraud-provider", "FRAUD_TRIAGE", atThreshold);
        assertThat(atThreshold.isEmitCase()).isTrue();
    }

    @Test
    void neverAutoOpenTemplate_overridesThresholdWhenBothLoaded() {
        // Salience 1 (never-auto-open) fires AFTER salience 100 (threshold);
        // the final fact state is emitCase=false, so the tenant's policy is
        // "manual triage only" even though the threshold rule matched.
        TenantRuleEngine engine = fraudEngine();
        engine.loadRules("t-fraud-suppress", List.of(
                provider.templates().get(0),   // threshold (salience 100)
                provider.templates().get(5))); // never-auto-open (salience 1)

        FraudFlagFact fact = FraudFlagFact.builder()
                .riskScore(new BigDecimal("0.99"))
                .build();
        engine.evaluateInGroup("t-fraud-suppress", "FRAUD_TRIAGE", fact);
        assertThat(fact.isEmitCase())
                .as("never-auto-open at salience 1 must override threshold at salience 100")
                .isFalse();
    }

    @Test
    void neverAutoOpenTemplate_aloneSuppressesAnyFlag() {
        TenantRuleEngine engine = fraudEngine();
        engine.loadRules("t-fraud-only-suppress", List.of(provider.templates().get(5)));

        FraudFlagFact fact = FraudFlagFact.builder()
                .riskScore(new BigDecimal("0.99"))
                .build();
        engine.evaluateInGroup("t-fraud-only-suppress", "FRAUD_TRIAGE", fact);
        assertThat(fact.isEmitCase()).isFalse();
    }

    private static TenantRuleEngine fraudEngine() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new OpenSiuCaseEmitter(),
                new SuppressSiuCaseEmitter()));
        return new TenantRuleEngine(compiler);
    }

    @Test
    void tenantIsolation_tenantARulesDoNotFireForTenantB() {
        // bug_rules_engine_tenant_isolation guard.
        TenantRuleEngine engine = fraudEngine();
        engine.loadRules("t-a", provider.templates());

        FraudFlagFact factForB = FraudFlagFact.builder()
                .riskScore(new BigDecimal("0.99"))
                .providerId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .build();
        engine.evaluateInGroup("t-b", "FRAUD_TRIAGE", factForB);

        assertThat(engine.hasRulesLoaded("t-b"))
                .as("engine must not have implicitly loaded rules for tenant B")
                .isFalse();
        assertThat(factForB.isEmitCase())
                .as("tenant B has no rules — tenant A's FRAUD_TRIAGE templates must not fire")
                .isFalse();
    }
}
