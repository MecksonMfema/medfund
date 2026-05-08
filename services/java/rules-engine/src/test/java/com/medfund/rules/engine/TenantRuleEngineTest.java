package com.medfund.rules.engine;

import com.medfund.rules.compiler.ActionEmitters;
import com.medfund.rules.compiler.DrlCompiler;
import com.medfund.rules.fact.ClaimFact;
import com.medfund.rules.fact.MemberFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.model.Condition;
import com.medfund.rules.model.ConditionGroup;
import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TenantRuleEngineTest {

    private DrlCompiler compiler;
    private TenantRuleEngine engine;

    @BeforeEach
    void setUp() {
        compiler = new DrlCompiler(List.of(
                new ActionEmitters.RejectEmitter(),
                new ActionEmitters.FlagEmitter(),
                new ActionEmitters.WarnEmitter(),
                new ActionEmitters.CapToTariffEmitter()
        ));
        engine = new TenantRuleEngine(compiler);
    }

    @Test
    void evaluate_withRejectionRule_rejectsClaim() {
        RuleDefinition rule = buildSuspendedMemberRule();
        engine.loadRules("test-tenant", List.of(rule));

        ClaimFact claim = createClaimFact();
        MemberFact member = createMemberFact("SUSPENDED");

        List<RuleResult> results = engine.evaluate("test-tenant", claim, member);

        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(r ->
                "REJECT".equals(r.getType()) && "R01".equals(r.getCode()));
    }

    @Test
    void evaluate_withPassingRule_noRejections() {
        RuleDefinition rule = buildSuspendedMemberRule();
        engine.loadRules("test-tenant", List.of(rule));

        ClaimFact claim = createClaimFact();
        MemberFact member = createMemberFact("ACTIVE");

        List<RuleResult> results = engine.evaluate("test-tenant", claim, member);

        assertThat(results).noneMatch(r -> "REJECT".equals(r.getType()));
    }

    @Test
    void evaluate_noRulesLoaded_returnsEmpty() {
        ClaimFact claim = createClaimFact();
        MemberFact member = createMemberFact("SUSPENDED");

        List<RuleResult> results = engine.evaluate("unknown-tenant", claim, member);

        assertThat(results).isEmpty();
    }

    @Test
    void hasRulesLoaded_afterLoad_returnsTrue() {
        RuleDefinition rule = buildSuspendedMemberRule();
        engine.loadRules("test-tenant", List.of(rule));

        assertThat(engine.hasRulesLoaded("test-tenant")).isTrue();
    }

    @Test
    void removeRules_afterRemove_returnsFalse() {
        RuleDefinition rule = buildSuspendedMemberRule();
        engine.loadRules("test-tenant", List.of(rule));

        engine.removeRules("test-tenant");

        assertThat(engine.hasRulesLoaded("test-tenant")).isFalse();
    }

    // --- Helpers ---

    private RuleDefinition buildSuspendedMemberRule() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("Member Must Be Active");
        rule.setCategory("ELIGIBILITY");
        rule.setPriority(100);
        rule.setStatus("ACTIVE");
        rule.setVersion(1);
        rule.setEnabled(true);

        var cond = new Condition();
        cond.setField("member.status");
        cond.setOperator("EQUALS");
        cond.setValue("SUSPENDED");

        var group = new ConditionGroup();
        group.setOperator("AND");
        group.setItems(List.of(cond));

        rule.setConditions(group);

        var action = new RuleAction();
        action.setType("REJECT");
        action.setRejectionCode("R01");
        action.setMessage("Member is not active");
        rule.setAction(action);

        return rule;
    }

    private ClaimFact createClaimFact() {
        var claim = new ClaimFact();
        claim.setClaimId("CLM-001");
        claim.setMemberId("MBR-001");
        claim.setAmount(new BigDecimal("500.00"));
        claim.setBenefitCategory("GENERAL");
        return claim;
    }

    private MemberFact createMemberFact(String status) {
        var member = new MemberFact();
        member.setMemberId("MBR-001");
        member.setStatus(status);
        member.setDaysSinceEnrollment(365);
        return member;
    }
}
