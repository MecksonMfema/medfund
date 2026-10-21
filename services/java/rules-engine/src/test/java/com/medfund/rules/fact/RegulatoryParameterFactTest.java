package com.medfund.rules.fact;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the mutation surface {@code SetRegulatoryParameterEmitter} calls into.
 * Any change to the fact's slot names or semantics breaks the DRL — pin them
 * here so the change surfaces as a test failure, not a Drools compile error.
 */
class RegulatoryParameterFactTest {

    @Test
    void setParameterValue_recordsValueRuleNameAndAppendsResult() {
        RegulatoryParameterFact fact = new RegulatoryParameterFact();
        fact.setParameterKey("min_solvency_ratio");
        fact.setJurisdiction("ZW_IPEC_SHORT_TERM");

        fact.setParameterValue(new BigDecimal("1.45"), "Rule A");

        assertThat(fact.getParameterValue()).isEqualByComparingTo(new BigDecimal("1.45"));
        assertThat(fact.getAppliedRuleName()).isEqualTo("Rule A");
        assertThat(fact.getResults()).hasSize(1);
        RuleResult result = fact.getResults().get(0);
        assertThat(result.getType()).isEqualTo("SET_REGULATORY_PARAMETER");
        assertThat(result.getCode()).isEqualTo("1.45");
        assertThat(result.getMessage()).isEqualTo("Rule A");
    }

    @Test
    void setParameterValue_multipleFires_appendsEveryResult() {
        // Two rules can match a lookup (higher-priority + fallback-priority);
        // the fact keeps every mutation for audit — the caller reads the last
        // one for the winning value.
        RegulatoryParameterFact fact = new RegulatoryParameterFact();

        fact.setParameterValue(new BigDecimal("1.20"), "Rule A (lower)");
        fact.setParameterValue(new BigDecimal("1.45"), "Rule B (higher)");

        assertThat(fact.getResults()).hasSize(2);
        assertThat(fact.getParameterValue()).isEqualByComparingTo(new BigDecimal("1.45"));
        assertThat(fact.getAppliedRuleName()).isEqualTo("Rule B (higher)");
    }

    @Test
    void setParameterValue_nullValue_stillRecordsResult() {
        // The emitter falls back to null when a tenant rule's value is a typo;
        // recording the mutation with null in the trace keeps the audit line
        // meaningful ("Rule fired but yielded no value → YAML default used").
        RegulatoryParameterFact fact = new RegulatoryParameterFact();

        fact.setParameterValue(null, "Rule with typo");

        assertThat(fact.getParameterValue()).isNull();
        assertThat(fact.getAppliedRuleName()).isEqualTo("Rule with typo");
        assertThat(fact.getResults()).hasSize(1);
        assertThat(fact.getResults().get(0).getCode()).isNull();
    }

    @Test
    void resultsList_isNotNullOnFreshFact() {
        RegulatoryParameterFact fact = new RegulatoryParameterFact();

        assertThat(fact.getResults()).isNotNull().isEmpty();
    }
}
