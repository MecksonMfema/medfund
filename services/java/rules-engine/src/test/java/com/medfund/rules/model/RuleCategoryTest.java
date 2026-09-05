package com.medfund.rules.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in category-catalog stability. Enum values are string keys stored
 * in tenant rows — deleting or renaming one silently breaks existing rules.
 * If a value is added the test must be updated in the same commit.
 */
class RuleCategoryTest {

    @Test
    void reinsuranceCategory_isDeclared() {
        // Phase 10 addition — used by CEDE_TO_TREATY templates + rules.
        assertThat(RuleCategory.valueOf("REINSURANCE"))
                .isEqualTo(RuleCategory.REINSURANCE);
    }

    @Test
    void commissionCategory_isDeclared() {
        // Phase 11 addition — used by PAY_COMMISSION emitter + kicker rules.
        // Agenda-gated so it only fires when CommissionCalcService focuses
        // the group on a medfund.contributions.paid event.
        assertThat(RuleCategory.valueOf("COMMISSION"))
                .isEqualTo(RuleCategory.COMMISSION);
    }

    @Test
    void premiumEarningCategory_isDeclared() {
        // Phase 12 addition — used by ACCRUE_PREMIUM emitter + premium-earning
        // templates. Agenda-gated so it only fires when the premium consumer
        // in contributions-service focuses the group on a policy-issued event
        // or during the nightly period-close pass.
        assertThat(RuleCategory.valueOf("PREMIUM_EARNING"))
                .isEqualTo(RuleCategory.PREMIUM_EARNING);
    }

    @Test
    void actuarialCategory_isDeclared() {
        // Phase 14 addition — used by SELECT_LDF emitter + ACTUARIAL templates.
        // Agenda-gated so it only fires when finance-service's
        // TriangleShapingService focuses the group before shaping an IBNR /
        // loss triangle.
        assertThat(RuleCategory.valueOf("ACTUARIAL"))
                .isEqualTo(RuleCategory.ACTUARIAL);
    }

    @Test
    void ifrs17ModelCategory_isDeclared() {
        // Phase 15 §9 addition — used by SELECT_IFRS17_MODEL emitter + IFRS17_MODEL
        // templates. Agenda-gated so it only fires when finance-service's
        // Ifrs17ShapingService focuses the group per portfolio at IFRS 17
        // report-submit time.
        assertThat(RuleCategory.valueOf("IFRS17_MODEL"))
                .isEqualTo(RuleCategory.IFRS17_MODEL);
    }

    @Test
    void regulatoryParameterCategory_isDeclared() {
        // Phase 16 §Phase-15 addition — used by SET_REGULATORY_PARAMETER emitter
        // + REGULATORY_PARAMETER templates. Agenda-gated so it only fires when
        // finance-service's RegulatoryParameterResolver focuses the group per
        // parameter lookup at regulator-report compute time.
        assertThat(RuleCategory.valueOf("REGULATORY_PARAMETER"))
                .isEqualTo(RuleCategory.REGULATORY_PARAMETER);
    }

    @Test
    void pmbClassificationCategory_isDeclared() {
        // Phase 17 §B REG7 addition — used by SET_PMB_CLASSIFICATION emitter
        // + PMB_CLASSIFICATION templates. Agenda-gated so it only fires when
        // claims-service's RulesEnginePmbClassifier focuses the group per
        // (claim, diagnosis, procedure) probe at adjudication + backfill time.
        assertThat(RuleCategory.valueOf("PMB_CLASSIFICATION"))
                .isEqualTo(RuleCategory.PMB_CLASSIFICATION);
    }

    @Test
    void fraudTriageCategory_isDeclared() {
        // Phase 19 §A addition — used by the FRAUD_TRIAGE template provider +
        // rules dispatched from claims-service's FraudFlaggedConsumer. Agenda-gated
        // ("FRAUD_RULES") so triage rules only fire when SiuCaseService.evaluateTriage
        // focuses the group per fraud_flag event — never during the stage-7 tenant sweep.
        assertThat(RuleCategory.valueOf("FRAUD_TRIAGE"))
                .isEqualTo(RuleCategory.FRAUD_TRIAGE);
    }

    @Test
    void enumHasExpectedCatalogSize() {
        // Guardrail: if this fires, a new category was added — update the
        // Angular RULE_CATEGORIES + permissions catalog + tenant rules docs.
        assertThat(RuleCategory.values()).hasSize(24);
    }
}
