package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level assertions for the SELECT_LDF DRL output. Pins the encoding
 * branches so a change to the emitter cannot silently ship a broken DRL
 * string or an unrecognised LDF method into ai-service's chain-ladder
 * compute.
 */
class SelectLdfEmitterTest {

    private final SelectLdfEmitter emitter = new SelectLdfEmitter();

    @Test
    void emit_volumeMethod_carriesQuotedNameAndMessage() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue("LDF_METHOD:volume");
        action.setMessage("Default volume-weighted");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$triangle.selectLdf(");
        assertThat(drl).contains("\"volume\"");
        assertThat(drl).contains("\"Default volume-weighted\"");
    }

    @Test
    void emit_simpleMethod_carriesQuotedName() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue("LDF_METHOD:simple");
        action.setMessage("Simple average");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"simple\"");
    }

    @Test
    void emit_fiveYearMethod_carriesQuotedName() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue("LDF_METHOD:5yr");
        action.setMessage("5-year weighted");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"5yr\"");
    }

    @Test
    void emit_unknownMethod_fallsBackToVolume() {
        // A tenant typo cannot leak an unrecognised method into ai-service,
        // which would raise instead of computing.
        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue("LDF_METHOD:bornhuetter-ferguson");
        action.setMessage("Typo");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$triangle.selectLdf(\"volume\", \"Typo\")");
    }

    @Test
    void emit_missingValue_fallsBackToVolume() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue(null);
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$triangle.selectLdf(\"volume\", \"\")");
    }

    @Test
    void emit_missingPrefix_fallsBackToVolume() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue("5yr");
        action.setMessage("No prefix");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$triangle.selectLdf(\"volume\", \"No prefix\")");
    }

    @Test
    void emit_messageEscaping_quotesEmbeddedQuotesAndBackslashes() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_LDF");
        action.setValue("LDF_METHOD:volume");
        action.setMessage("Line \"A\" — 2026\\Q1");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\\\"A\\\"");
        assertThat(drl).contains("\\\\Q1");
    }
}
