package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level assertions for the SET_PMB_CLASSIFICATION DRL output. Pins the
 * encoding branches so a change to the emitter can't silently ship a broken
 * DRL string, a lost condition-code, or an accidental PMB-without-code write.
 */
class SetPmbClassificationEmitterTest {

    private final SetPmbClassificationEmitter emitter = new SetPmbClassificationEmitter();

    @Test
    void emit_conditionCode_carriesQuotedCodeAndMessage() {
        RuleAction action = new RuleAction();
        action.setType("SET_PMB_CLASSIFICATION");
        action.setValue("PMB_CONDITION_CODE:PMB-001");
        action.setMessage("Pulmonary tuberculosis PMB");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$pmbClassification.setPmbClassification(");
        assertThat(drl).contains("\"PMB-001\"");
        assertThat(drl).contains("\"Pulmonary tuberculosis PMB\"");
    }

    @Test
    void emit_missingValue_fallsBackToNullLiteral() {
        RuleAction action = new RuleAction();
        action.setType("SET_PMB_CLASSIFICATION");
        action.setValue(null);
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$pmbClassification.setPmbClassification(null, \"\")");
    }

    @Test
    void emit_missingPrefix_fallsBackToNullLiteral() {
        // Rule value must carry the PMB_CONDITION_CODE: prefix; missing prefix
        // parses as null so the fact's null-check keeps isPmb=false.
        RuleAction action = new RuleAction();
        action.setType("SET_PMB_CLASSIFICATION");
        action.setValue("PMB-001");
        action.setMessage("No prefix");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$pmbClassification.setPmbClassification(null, \"No prefix\")");
    }

    @Test
    void emit_emptyValueAfterPrefix_fallsBackToNullLiteral() {
        RuleAction action = new RuleAction();
        action.setType("SET_PMB_CLASSIFICATION");
        action.setValue("PMB_CONDITION_CODE:");
        action.setMessage("Empty code");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$pmbClassification.setPmbClassification(null, \"Empty code\")");
    }

    @Test
    void emit_blankValueAfterPrefix_fallsBackToNullLiteral() {
        RuleAction action = new RuleAction();
        action.setType("SET_PMB_CLASSIFICATION");
        action.setValue("PMB_CONDITION_CODE:   ");
        action.setMessage("Blank code");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$pmbClassification.setPmbClassification(null, \"Blank code\")");
    }

    @Test
    void emit_messageEscaping_quotesEmbeddedQuotesAndBackslashes() {
        RuleAction action = new RuleAction();
        action.setType("SET_PMB_CLASSIFICATION");
        action.setValue("PMB_CONDITION_CODE:PMB-042");
        action.setMessage("Note \"A\" — path\\value");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\\\"A\\\"");
        assertThat(drl).contains("path\\\\value");
    }

    @Test
    void emit_codeWithSpecialChars_quotedButNotEscapedTwice() {
        // PMB condition codes come in a small alphabet but the emitter still
        // pushes them through the same escape pass. Verify a code containing
        // an underscore + hyphen round-trips clean.
        RuleAction action = new RuleAction();
        action.setType("SET_PMB_CLASSIFICATION");
        action.setValue("PMB_CONDITION_CODE:PMB_dialysis-042");
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"PMB_dialysis-042\"");
    }
}
