package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level assertions for the SELECT_IFRS17_MODEL DRL output. Pins the
 * encoding branches so a change to the emitter cannot silently ship a
 * broken DRL string or an unrecognised model / coverage / fee / expense
 * choice into the IFRS 17 chunk-shaping pipeline.
 */
class SelectIfrs17ModelEmitterTest {

    private final SelectIfrs17ModelEmitter emitter = new SelectIfrs17ModelEmitter();

    @Test
    void emit_paa_carriesModelAndDefaults() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:PAA:TIME:null:PL_ONLY");
        action.setMessage("PAA — HEALTH short-duration");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$portfolio.applyModel(");
        assertThat(drl).contains("\"PAA\", \"TIME\", null, \"PL_ONLY\"");
        assertThat(drl).contains("\"PAA — HEALTH short-duration\"");
    }

    @Test
    void emit_gmm_withOciOption() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:GMM:TIME:null:OCI_OPTION");
        action.setMessage("GMM — LIFE");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"GMM\", \"TIME\", null, \"OCI_OPTION\"");
    }

    @Test
    void emit_vfa_carriesFeePattern() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:VFA:SUM_INSURED_TIME:FIXED_PCT:OCI_OPTION");
        action.setMessage("VFA — unit-linked");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"VFA\", \"SUM_INSURED_TIME\", \"FIXED_PCT\", \"OCI_OPTION\"");
    }

    @Test
    void emit_unknownModel_fallsBackToPaaSafeDefault() {
        // A tenant typo cannot leak an unrecognised model into the shaping pipeline.
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:BBA:TIME:null:PL_ONLY");
        action.setMessage("Typo");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$portfolio.applyModel(\"PAA\", \"TIME\", null, \"PL_ONLY\", \"Typo\")");
    }

    @Test
    void emit_unknownCoverage_fallsBackToTime() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:PAA:PREMIUM_TIME:null:PL_ONLY");
        action.setMessage("bad coverage");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"PAA\", \"TIME\", null, \"PL_ONLY\"");
    }

    @Test
    void emit_unknownFee_becomesNull() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:VFA:TIME:HYBRID:OCI_OPTION");
        action.setMessage("bad fee");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"VFA\", \"TIME\", null, \"OCI_OPTION\"");
    }

    @Test
    void emit_unknownExpense_fallsBackToPlOnly() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:PAA:TIME:null:CAPITAL_RESERVE");
        action.setMessage("bad expense");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"PAA\", \"TIME\", null, \"PL_ONLY\"");
    }

    @Test
    void emit_missingValue_fallsBackToAllSafeDefaults() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue(null);
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$portfolio.applyModel(\"PAA\", \"TIME\", null, \"PL_ONLY\", \"\")");
    }

    @Test
    void emit_missingPrefix_fallsBackToAllSafeDefaults() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("GMM");
        action.setMessage("no prefix");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$portfolio.applyModel(\"PAA\", \"TIME\", null, \"PL_ONLY\", \"no prefix\")");
    }

    @Test
    void emit_partialValue_defaultsMissingSegments() {
        // Only model + coverage supplied; fee + expense default to null + PL_ONLY.
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:GMM:CLAIM_FREQUENCY_TIME");
        action.setMessage("partial");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"GMM\", \"CLAIM_FREQUENCY_TIME\", null, \"PL_ONLY\"");
    }

    @Test
    void emit_messageEscaping_quotesEmbeddedQuotesAndBackslashes() {
        RuleAction action = new RuleAction();
        action.setType("SELECT_IFRS17_MODEL");
        action.setValue("IFRS17_MODEL:PAA:TIME:null:PL_ONLY");
        action.setMessage("Line \"A\" — 2026\\Q1");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\\\"A\\\"");
        assertThat(drl).contains("\\\\Q1");
    }
}
