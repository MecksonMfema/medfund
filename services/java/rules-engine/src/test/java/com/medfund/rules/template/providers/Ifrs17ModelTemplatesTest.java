package com.medfund.rules.template.providers;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.compiler.SelectIfrs17ModelEmitter;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.IfrsPortfolioFact;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Ifrs17ModelTemplatesTest {

    private final Ifrs17ModelTemplates provider = new Ifrs17ModelTemplates();

    @Test
    void category_isIfrs17Model() {
        assertThat(provider.category()).isEqualTo(RuleCategory.IFRS17_MODEL);
    }

    @Test
    void templates_shipsThreeStartingPointsCoveringPaaGmmVfa() {
        List<RuleDefinition> templates = provider.templates();

        assertThat(templates).hasSize(3);
        assertThat(templates).allSatisfy(t -> {
            assertThat(t.getCategory()).isEqualTo("IFRS17_MODEL");
            assertThat(t.getAction().getType()).isEqualTo("SELECT_IFRS17_MODEL");
            assertThat(t.getAction().getValue()).asString().startsWith("IFRS17_MODEL:");
        });
        assertThat(templates)
                .extracting(t -> t.getAction().getValue().toString())
                .anyMatch(v -> v.contains(":PAA:"))
                .anyMatch(v -> v.contains(":GMM:"))
                .anyMatch(v -> v.contains(":VFA:"));
    }

    @Test
    void templates_compileToAgendaGatedDrl() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectIfrs17ModelEmitter()));

        for (RuleDefinition template : provider.templates()) {
            String drl = compiler.compile(template);
            assertThat(drl)
                    .as("template '%s' must be agenda-gated so it doesn't fire in the stage-7 sweep",
                        template.getName())
                    .contains("agenda-group \"IFRS17_MODEL\"");
            assertThat(drl).contains("$portfolio.applyModel(");
        }
    }

    @Test
    void healthPortfolio_selectsPaaWhenLoadedIntoEngine() {
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectIfrs17ModelEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-health", provider.templates());

        IfrsPortfolioFact fact = portfolioFor("HEALTH");
        engine.evaluateInGroup("t-health", "IFRS17_MODEL", fact);

        assertThat(fact.getMeasurementModel()).isEqualTo("PAA");
        assertThat(fact.getCoverageUnitPattern()).isEqualTo("TIME");
        assertThat(fact.getVariableFeePattern()).isNull();
        assertThat(fact.getFinanceExpensePresentation()).isEqualTo("PL_ONLY");
        assertThat(fact.getAppliedRuleName()).contains("PAA");
        assertThat(fact.getResults()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(fact.getResults().get(0).getType()).isEqualTo("SELECT_IFRS17_MODEL");
    }

    @Test
    void lifePortfolio_selectsVfaLastWinsOverGmm() {
        // Both I91 (GMM, salience 90) and I92 (VFA, salience 80) match LIFE.
        // Higher salience fires first; last-write-wins on the fact — so I92 (VFA) ends up applied.
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectIfrs17ModelEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-life", provider.templates());

        IfrsPortfolioFact fact = portfolioFor("LIFE");
        engine.evaluateInGroup("t-life", "IFRS17_MODEL", fact);

        assertThat(fact.getMeasurementModel()).isEqualTo("VFA");
        assertThat(fact.getVariableFeePattern()).isEqualTo("FIXED_PCT");
        assertThat(fact.getFinanceExpensePresentation()).isEqualTo("OCI_OPTION");
    }

    @Test
    void unmatchedLine_leavesFactUntouched() {
        // Nothing in the seeded templates targets FUNERAL — fact stays as shaping built it.
        DrlCompiler compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new SelectIfrs17ModelEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-funeral", provider.templates());

        IfrsPortfolioFact fact = portfolioFor("FUNERAL");
        engine.evaluateInGroup("t-funeral", "IFRS17_MODEL", fact);

        assertThat(fact.getMeasurementModel()).isNull();
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
                new SelectIfrs17ModelEmitter()));
        TenantRuleEngine engine = new TenantRuleEngine(compiler);
        engine.loadRules("t-a", provider.templates());

        IfrsPortfolioFact factForB = portfolioFor("HEALTH");
        engine.evaluateInGroup("t-b", "IFRS17_MODEL", factForB);

        assertThat(engine.hasRulesLoaded("t-b"))
                .as("engine must not have implicitly loaded rules for tenant B")
                .isFalse();
        assertThat(factForB.getMeasurementModel())
                .as("tenant B has no rules — tenant A's HEALTH → PAA rule must not fire")
                .isNull();
    }

    private IfrsPortfolioFact portfolioFor(String line) {
        IfrsPortfolioFact fact = new IfrsPortfolioFact();
        fact.setPortfolioId(UUID.randomUUID());
        fact.setInsuranceLine(line);
        fact.setCohortYear(2026);
        fact.setJurisdictionCode("ZW");
        fact.setReportingCurrency("USD");
        fact.setPortfolioName("Test portfolio — " + line);
        fact.setPortfolioCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return fact;
    }
}
