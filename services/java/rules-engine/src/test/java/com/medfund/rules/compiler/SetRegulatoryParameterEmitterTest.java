package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level assertions for the SET_REGULATORY_PARAMETER DRL output. Pins the
 * encoding branches so a change to the emitter cannot silently ship a broken
 * DRL string, an unparseable literal, or a lost fallback-to-YAML path.
 */
class SetRegulatoryParameterEmitterTest {

    private final SetRegulatoryParameterEmitter emitter = new SetRegulatoryParameterEmitter();

    @Test
    void emit_decimalValue_carriesBigDecimalLiteralAndMessage() {
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue("PARAMETER_VALUE:1.45");
        action.setMessage("Regulator uplift 2027-Q1");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$regulatoryParameter.setParameterValue(");
        assertThat(drl).contains("new java.math.BigDecimal(\"1.45\")");
        assertThat(drl).contains("\"Regulator uplift 2027-Q1\"");
    }

    @Test
    void emit_integerValue_carriesCanonicalDecimalLiteral() {
        // Even an integer input is written as "5" (the plain-string form) so
        // the BigDecimal ctor accepts it as a decimal literal.
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue("PARAMETER_VALUE:5");
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("new java.math.BigDecimal(\"5\")");
    }

    @Test
    void emit_scientificNotation_normalisedToPlainString() {
        // -1.5e2 → -150. Round-trip through BigDecimal.toPlainString so the DRL
        // never carries scientific-notation literals that Drools + BigDecimal
        // handle differently.
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue("PARAMETER_VALUE:-1.5e2");
        action.setMessage("scientific");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("new java.math.BigDecimal(\"-150\")");
    }

    @Test
    void emit_missingValue_fallsBackToNullLiteral() {
        // Null literal in the DRL means the caller's fallback chain (rules-engine
        // → bundled YAML → fail-loud) still runs — a typo in a tenant rule cannot
        // silently zero the report cell.
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue(null);
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$regulatoryParameter.setParameterValue(null, \"\")");
    }

    @Test
    void emit_missingPrefix_fallsBackToNullLiteral() {
        // Rule value must carry the PARAMETER_VALUE: prefix; missing prefix
        // parses as null so the caller's fallback picks the YAML default.
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue("0.30");
        action.setMessage("No prefix");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$regulatoryParameter.setParameterValue(null, \"No prefix\")");
    }

    @Test
    void emit_unparseableValue_fallsBackToNullLiteral() {
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue("PARAMETER_VALUE:not-a-number");
        action.setMessage("Bad literal");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$regulatoryParameter.setParameterValue(null, \"Bad literal\")");
    }

    @Test
    void emit_emptyValueAfterPrefix_fallsBackToNullLiteral() {
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue("PARAMETER_VALUE:");
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$regulatoryParameter.setParameterValue(null, \"\")");
    }

    @Test
    void emit_messageEscaping_quotesEmbeddedQuotesAndBackslashes() {
        RuleAction action = new RuleAction();
        action.setType("SET_REGULATORY_PARAMETER");
        action.setValue("PARAMETER_VALUE:0.30");
        action.setMessage("Note \"A\" — path\\value");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\\\"A\\\"");
        assertThat(drl).contains("path\\\\value");
    }
}
