package com.medfund.rules.compiler;

import com.medfund.rules.model.Condition;
import com.medfund.rules.model.ConditionGroup;
import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DrlCompilerTest {

    private DrlCompiler compiler;

    @BeforeEach
    void setUp() {
        // Hand-wire every emitter the existing tests exercise. Adding a new
        // ActionEmitter implementation is a one-line addition here.
        compiler = new DrlCompiler(java.util.List.of(
                new ActionEmitters.RejectEmitter(),
                new ActionEmitters.FlagEmitter(),
                new ActionEmitters.WarnEmitter(),
                new ActionEmitters.CapToTariffEmitter(),
                new ActionEmitters.ApplyCopayEmitter(),
                new ActionEmitters.ApplyProrationStrategyEmitter(),
                new ActionEmitters.SetAgeGroupEmitter(),
                new ActionEmitters.AutoRenewEmitter(),
                new ActionEmitters.TerminateEmitter(),
                new ActionEmitters.RequireUnderwritingEmitter(),
                new ActionEmitters.ApplyLoadedPremiumEmitter(),
                new ActionEmitters.SetPremiumEmitter(),
                new ActionEmitters.ApplyLateFeeEmitter(),
                new ActionEmitters.SchedulePaymentRunEmitter(),
                new ActionEmitters.WithholdPaymentEmitter(),
                new ActionEmitters.MatchRecordsEmitter()
        ));
    }

    @Test
    void compile_eligibilityRule_generatesValidDrl() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("Member Must Be Active");
        rule.setCategory("ELIGIBILITY");
        rule.setPriority(100);
        rule.setStatus("ACTIVE");
        rule.setVersion(1);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("member.status", "EQUALS", "SUSPENDED")));
        rule.setAction(rejectAction("R01", "Member is not active"));

        String drl = compiler.compile(rule);

        assertThat(drl).contains("rule \"Member Must Be Active\"");
        assertThat(drl).contains("salience 100");
        assertThat(drl).contains("$member : MemberFact(");
        assertThat(drl).contains("status == \"SUSPENDED\"");
        assertThat(drl).contains("$claim.addRejection(\"R01\"");
    }

    @Test
    void compile_waitingPeriodRule_generatesValidDrl() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("General Waiting Period");
        rule.setCategory("WAITING_PERIOD");
        rule.setPriority(85);
        rule.setStatus("ACTIVE");
        rule.setVersion(1);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("claim.benefitCategory", "EQUALS", "GENERAL"),
                condition("member.daysSinceEnrollment", "LESS_THAN", 90)));
        rule.setAction(rejectAction("R02", "Waiting period not met"));

        String drl = compiler.compile(rule);

        assertThat(drl).contains("rule \"General Waiting Period\"");
        assertThat(drl).contains("benefitCategory == \"GENERAL\"");
        assertThat(drl).contains("daysSinceEnrollment < 90");
        assertThat(drl).contains("$claim : ClaimFact(");
        assertThat(drl).contains("$member : MemberFact(");
    }

    @Test
    void compile_benefitProrationRule_addsAgendaGroupAndSetsStrategy() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("Dental fresh limit");
        rule.setCategory("BENEFIT_PRORATION");
        rule.setPriority(80);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("claim.benefitCategory", "EQUALS", "DENTAL")));
        RuleAction action = new RuleAction();
        action.setType("APPLY_PRORATION_STRATEGY");
        action.setValue("CALENDAR");
        action.setMessage("Prorate by calendar");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        // Agenda-group gate must be present — otherwise the rule would fire during
        // the stage-7 tenant-rules sweep instead of the stage-3 proration invocation.
        assertThat(drl).contains("agenda-group \"BENEFIT_PRORATION\"");
        assertThat(drl).contains("$claim.setProrationStrategy(\"CALENDAR\")");
        assertThat(drl).contains("benefitCategory == \"DENTAL\"");
    }

    @Test
    void compile_nonProrationRule_hasNoAgendaGroup() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("Ordinary Eligibility");
        rule.setCategory("ELIGIBILITY");
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("member.status", "EQUALS", "SUSPENDED")));
        rule.setAction(rejectAction("R01", "Suspended"));

        String drl = compiler.compile(rule);

        assertThat(drl).doesNotContain("agenda-group");
    }

    @Test
    void compileAll_multipleRules_generatesAllRules() {
        RuleDefinition rule1 = new RuleDefinition();
        rule1.setName("Rule Alpha");
        rule1.setCategory("ELIGIBILITY");
        rule1.setPriority(100);
        rule1.setEnabled(true);
        rule1.setConditions(conditions("AND",
                condition("member.status", "EQUALS", "SUSPENDED")));
        rule1.setAction(rejectAction("R01", "Not active"));

        RuleDefinition rule2 = new RuleDefinition();
        rule2.setName("Rule Beta");
        rule2.setCategory("WAITING_PERIOD");
        rule2.setPriority(80);
        rule2.setEnabled(true);
        rule2.setConditions(conditions("AND",
                condition("member.daysSinceEnrollment", "LESS_THAN", 90)));
        rule2.setAction(rejectAction("R02", "Waiting period"));

        String drl = compiler.compileAll(List.of(rule1, rule2));

        assertThat(drl).contains("rule \"Rule Alpha\"");
        assertThat(drl).contains("rule \"Rule Beta\"");
    }

    // --- Helpers ---

    private ConditionGroup conditions(String operator, Condition... conds) {
        var group = new ConditionGroup();
        group.setOperator(operator);
        group.setItems(List.of(conds));
        return group;
    }

    private Condition condition(String field, String op, Object value) {
        var c = new Condition();
        c.setField(field);
        c.setOperator(op);
        c.setValue(value);
        return c;
    }

    private RuleAction rejectAction(String code, String message) {
        var action = new RuleAction();
        action.setType("REJECT");
        action.setRejectionCode(code);
        action.setMessage(message);
        return action;
    }
}
