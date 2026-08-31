package com.medfund.rules.fact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the mutation surface {@code SetPmbClassificationEmitter} calls into.
 * Any change to the fact's slot names or semantics breaks the DRL — pin them
 * here so the change surfaces as a test failure, not a Drools compile error.
 */
class PmbClassificationFactTest {

    @Test
    void setPmbClassification_recordsCodeRuleNameAndAppendsResult() {
        PmbClassificationFact fact = new PmbClassificationFact();
        fact.setDiagnosisCode("A15.0");
        fact.setProcedureCode("0100");

        fact.setPmbClassification("PMB-001", "Rule A");

        assertThat(fact.isPmb()).isTrue();
        assertThat(fact.getPmbConditionCode()).isEqualTo("PMB-001");
        assertThat(fact.getAppliedRuleName()).isEqualTo("Rule A");
        assertThat(fact.getResults()).hasSize(1);
        RuleResult r = fact.getResults().get(0);
        assertThat(r.getType()).isEqualTo("SET_PMB_CLASSIFICATION");
        assertThat(r.getCode()).isEqualTo("PMB-001");
        assertThat(r.getMessage()).isEqualTo("Rule A");
    }

    @Test
    void setPmbClassification_multipleFires_lastMatchWins_butAllResultsRecorded() {
        // Two PMB rules can match a claim; the fact keeps every mutation for
        // audit but the caller reads the LAST condition code + isPmb.
        PmbClassificationFact fact = new PmbClassificationFact();

        fact.setPmbClassification("PMB-001", "Rule A (lower)");
        fact.setPmbClassification("PMB-002", "Rule B (higher)");

        assertThat(fact.getResults()).hasSize(2);
        assertThat(fact.getPmbConditionCode()).isEqualTo("PMB-002");
        assertThat(fact.getAppliedRuleName()).isEqualTo("Rule B (higher)");
        assertThat(fact.isPmb()).isTrue();
    }

    @Test
    void setPmbClassification_nullCode_skipsMutation_recordsAuditRow() {
        // A typo in a tenant rule (or a bad emitter fallback) can't silently
        // mark a claim PMB without a code — the fact leaves isPmb=false but
        // still records the attempt so the audit trail is complete.
        PmbClassificationFact fact = new PmbClassificationFact();

        fact.setPmbClassification(null, "Rule with typo");

        assertThat(fact.isPmb()).isFalse();
        assertThat(fact.getPmbConditionCode()).isNull();
        assertThat(fact.getAppliedRuleName()).isNull();
        assertThat(fact.getResults()).hasSize(1);
        assertThat(fact.getResults().get(0).getCode()).isNull();
        assertThat(fact.getResults().get(0).getMessage()).isEqualTo("Rule with typo");
    }

    @Test
    void setPmbClassification_blankCode_skipsMutation_recordsAuditRow() {
        PmbClassificationFact fact = new PmbClassificationFact();

        fact.setPmbClassification("   ", "Blank rule");

        assertThat(fact.isPmb()).isFalse();
        assertThat(fact.getPmbConditionCode()).isNull();
        assertThat(fact.getResults()).hasSize(1);
        assertThat(fact.getResults().get(0).getCode()).isNull();
    }

    @Test
    void resultsList_isNotNullOnFreshFact() {
        PmbClassificationFact fact = new PmbClassificationFact();

        assertThat(fact.getResults()).isNotNull().isEmpty();
        assertThat(fact.isPmb()).isFalse();
    }
}
