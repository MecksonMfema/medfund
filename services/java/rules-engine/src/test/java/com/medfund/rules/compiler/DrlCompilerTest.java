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
                new ActionEmitters.MatchRecordsEmitter(),
                new CedeToTreatyEmitter(),
                new PayCommissionEmitter(),
                new AccruePremiumEmitter(),
                new SelectLdfEmitter(),
                new SetRegulatoryParameterEmitter()
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
    void compile_reinsuranceProportionalRule_addsAgendaGroupAndCedes() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("Quota Share 30%");
        rule.setCategory("REINSURANCE");
        rule.setPriority(50);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("claim.amount", "GREATER_THAN", "0")));
        RuleAction action = new RuleAction();
        action.setType("CEDE_TO_TREATY");
        action.setRejectionCode("11111111-1111-1111-1111-111111111111");
        action.setValue("PCT:30");
        action.setMessage("Quota Share 30% cession");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        // Agenda-group gate keeps cession rules out of the stage-7 sweep.
        assertThat(drl).contains("agenda-group \"REINSURANCE\"");
        assertThat(drl).contains("$claim.addCession(");
        // Treaty id + human-readable message flow through as string params.
        assertThat(drl).contains("11111111-1111-1111-1111-111111111111");
        assertThat(drl).contains("Quota Share 30% cession");
        // Proportional arithmetic uses BigDecimal.movePointLeft(2) for pct÷100.
        assertThat(drl).contains("multiply(new java.math.BigDecimal(\"30\"))");
        assertThat(drl).contains("movePointLeft(2)");
    }

    @Test
    void compile_reinsuranceXolRule_emitsRetentionLayerMath() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("XoL layer 500k xs 100k");
        rule.setCategory("REINSURANCE");
        rule.setPriority(48);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("claim.amount", "GREATER_THAN", "100000")));
        RuleAction action = new RuleAction();
        action.setType("CEDE_TO_TREATY");
        action.setRejectionCode("22222222-2222-2222-2222-222222222222");
        action.setValue("XOL:100000;500000;33333333-3333-3333-3333-333333333333");
        action.setMessage("XoL layer: 500k xs 100k");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        assertThat(drl).contains("agenda-group \"REINSURANCE\"");
        // max(0, min(limit, amount - retention)) — chained BigDecimal ops.
        assertThat(drl).contains("subtract(new java.math.BigDecimal(\"100000\"))");
        assertThat(drl).contains("min(new java.math.BigDecimal(\"500000\"))");
        assertThat(drl).contains("max(java.math.BigDecimal.ZERO)");
        // Layer id passes through as the third arg to addCession.
        assertThat(drl).contains("33333333-3333-3333-3333-333333333333");
    }

    @Test
    void compile_commissionRule_addsAgendaGroupAndAddCommission() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("Broker A kicker 25bp");
        rule.setCategory("COMMISSION");
        rule.setPriority(50);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("contribution.insuranceLine", "EQUALS", "HEALTH")));
        RuleAction action = new RuleAction();
        action.setType("PAY_COMMISSION");
        action.setRejectionCode("44444444-4444-4444-4444-444444444444");
        action.setValue("KICKER:25:promo");
        action.setMessage("Promo kicker Q3");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        // Agenda-group gate keeps commission rules out of the stage-7 sweep.
        assertThat(drl).contains("agenda-group \"COMMISSION\"");
        // ContributionFact is auto-bound via DrlCompiler.factForAction even
        // though the rule's only condition is on insuranceLine (an attribute).
        assertThat(drl).contains("$contribution : ContributionFact(");
        assertThat(drl).contains("$contribution.addCommission(");
        // Producer id + message flow through as string params.
        assertThat(drl).contains("44444444-4444-4444-4444-444444444444");
        assertThat(drl).contains("Promo kicker Q3");
        // KICKER encoding: premiumAmount × bp × 10^-4.
        assertThat(drl).contains("$contribution.getPremiumAmount()");
        assertThat(drl).contains("multiply(new java.math.BigDecimal(\"25\"))");
        assertThat(drl).contains("movePointLeft(4)");
    }

    @Test
    void compile_commissionRateCardRule_carriesRateCardIdWithZeroAmount() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("Broker A base");
        rule.setCategory("COMMISSION");
        rule.setPriority(60);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("contribution.insuranceLine", "EQUALS", "HEALTH")));
        RuleAction action = new RuleAction();
        action.setType("PAY_COMMISSION");
        action.setRejectionCode("");   // defer to member's assigned producer
        action.setValue("RATE_CARD:55555555-5555-5555-5555-555555555555");
        action.setMessage("Health base rate");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        // Rate-card lookup encoding emits zero amount + carries the id via
        // the third arg to addCommission — the consumer resolves the amount.
        assertThat(drl).contains("$contribution.addCommission(");
        assertThat(drl).contains("java.math.BigDecimal.ZERO");
        assertThat(drl).contains("55555555-5555-5555-5555-555555555555");
        // Empty producer id encodes as \"\" so the emitted DRL still compiles.
        assertThat(drl).contains("addCommission(\"\", ");
    }

    @Test
    void compile_premiumEarningRule_addsAgendaGroupAndAccrue() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("VEHICLE 24ths");
        rule.setCategory("PREMIUM_EARNING");
        rule.setPriority(90);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("premium.insuranceLine", "EQUALS", "VEHICLE")));
        RuleAction action = new RuleAction();
        action.setType("ACCRUE_PREMIUM");
        action.setValue("EARNING_METHOD:MONTHLY_24THS");
        action.setMessage("IPEC 24ths for motor");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        // Agenda-group gate keeps earning rules out of the stage-7 sweep.
        assertThat(drl).contains("agenda-group \"PREMIUM_EARNING\"");
        // PremiumFact is auto-bound via DrlCompiler.factForAction even
        // though the rule's only condition is on insuranceLine.
        assertThat(drl).contains("$premium : PremiumFact(");
        assertThat(drl).contains("$premium.accrue(");
        assertThat(drl).contains("\"MONTHLY_24THS\"");
        // Loading percent stays null for the non-LOADING method.
        assertThat(drl).contains(", null, ");
        assertThat(drl).contains("\"IPEC 24ths for motor\"");
    }

    @Test
    void compile_premiumEarningLoadingRule_carriesLoadingPercent() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("Whole-life 15% front-load");
        rule.setCategory("PREMIUM_EARNING");
        rule.setPriority(80);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("premium.insuranceLine", "EQUALS", "LIFE"),
                condition("premium.productCode",   "EQUALS", "WHOLE_LIFE")));
        RuleAction action = new RuleAction();
        action.setType("ACCRUE_PREMIUM");
        action.setValue("EARNING_METHOD:LINEAR_WITH_LOADING:15");
        action.setMessage("Whole-life 15% front-load");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        assertThat(drl).contains("agenda-group \"PREMIUM_EARNING\"");
        assertThat(drl).contains("$premium.accrue(");
        assertThat(drl).contains("\"LINEAR_WITH_LOADING\"");
        assertThat(drl).contains("new java.math.BigDecimal(\"15\")");
    }

    @Test
    void compile_actuarialRule_addsAgendaGroupAndSelectsLdf() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("HEALTH 5yr weighted");
        rule.setCategory("ACTUARIAL");
        rule.setPriority(80);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("triangle.insuranceLine", "EQUALS", "HEALTH")));
        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue("LDF_METHOD:5yr");
        action.setMessage("5-year weighted for HEALTH");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        assertThat(drl).contains("agenda-group \"ACTUARIAL\"");
        assertThat(drl).contains("$triangle : TriangleFact(");
        assertThat(drl).contains("$triangle.selectLdf(");
        assertThat(drl).contains("\"5yr\"");
        assertThat(drl).contains("\"5-year weighted for HEALTH\"");
    }

    @Test
    void compile_regulatoryParameterRule_addsAgendaGroupAndSetsValue() {
        RuleDefinition rule = new RuleDefinition();
        rule.setName("IPEC min_solvency_ratio override");
        rule.setCategory("REGULATORY_PARAMETER");
        rule.setPriority(100);
        rule.setEnabled(true);
        rule.setConditions(conditions("AND",
                condition("regulatoryParameter.parameterKey", "EQUALS", "min_solvency_ratio"),
                condition("regulatoryParameter.jurisdiction", "EQUALS", "ZW_IPEC_SHORT_TERM")));
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue("PARAMETER_VALUE:1.45");
        action.setMessage("Regulator uplift 2027-Q1");
        rule.setAction(action);

        String drl = compiler.compile(rule);

        // Agenda-group gate keeps regulatory-parameter rules out of the stage-7 sweep.
        assertThat(drl).contains("agenda-group \"REGULATORY_PARAMETER\"");
        assertThat(drl).contains("$regulatoryParameter : RegulatoryParameterFact(");
        assertThat(drl).contains("parameterKey == \"min_solvency_ratio\"");
        assertThat(drl).contains("jurisdiction == \"ZW_IPEC_SHORT_TERM\"");
        // Decimal literal round-trips through BigDecimal constructor so a rule
        // typo cannot silently ship an unparseable literal.
        assertThat(drl).contains("$regulatoryParameter.setParameterValue(");
        assertThat(drl).contains("new java.math.BigDecimal(\"1.45\")");
        assertThat(drl).contains("\"Regulator uplift 2027-Q1\"");
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
