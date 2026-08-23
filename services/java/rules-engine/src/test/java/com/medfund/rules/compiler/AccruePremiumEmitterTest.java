package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level assertions for the ACCRUE_PREMIUM DRL output. Complements the
 * end-to-end round-trip tests in {@link DrlCompilerTest} — this class pins
 * the encoding branches so a change to the emitter cannot silently ship
 * a broken DRL string.
 */
class AccruePremiumEmitterTest {

    private final AccruePremiumEmitter emitter = new AccruePremiumEmitter();

    @Test
    void emit_dailyLinearEncoding_carriesMethodWithNullLoading() {
        RuleAction action = new RuleAction();
        action.setType("ACCRUE_PREMIUM");
        action.setValue("EARNING_METHOD:DAILY_LINEAR");
        action.setMessage("Default 1/365ths earning");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        // Method → first arg quoted, loading percent → null (no colon-tail).
        assertThat(drl).contains("$premium.accrue(");
        assertThat(drl).contains("\"DAILY_LINEAR\"");
        assertThat(drl).contains(", null, ");
        assertThat(drl).contains("\"Default 1/365ths earning\"");
    }

    @Test
    void emit_monthly24thsEncoding_carriesMethodWithNullLoading() {
        RuleAction action = new RuleAction();
        action.setType("ACCRUE_PREMIUM");
        action.setValue("EARNING_METHOD:MONTHLY_24THS");
        action.setMessage("IPEC 24ths");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("$premium.accrue(");
        assertThat(drl).contains("\"MONTHLY_24THS\"");
        assertThat(drl).contains(", null, ");
        assertThat(drl).contains("\"IPEC 24ths\"");
    }

    @Test
    void emit_linearWithLoadingEncoding_parsesPercentIntoBigDecimalArg() {
        RuleAction action = new RuleAction();
        action.setType("ACCRUE_PREMIUM");
        action.setValue("EARNING_METHOD:LINEAR_WITH_LOADING:15");
        action.setMessage("Whole-life 15% front-load");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"LINEAR_WITH_LOADING\"");
        // Loading percent → BigDecimal literal, not null.
        assertThat(drl).contains("new java.math.BigDecimal(\"15\")");
        assertThat(drl).contains("\"Whole-life 15% front-load\"");
    }

    @Test
    void emit_missingValue_fallsBackToDailyLinear() {
        RuleAction action = new RuleAction();
        action.setType("ACCRUE_PREMIUM");
        action.setValue(null);
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        // Neither EARNING_METHOD: token → safe DAILY_LINEAR default, no NPE.
        assertThat(drl).contains("$premium.accrue(\"DAILY_LINEAR\", null, \"\")");
    }

    @Test
    void emit_malformedLoadingPercent_sanitisesToZero() {
        // A tenant typo in the loading percent must not break DRL compilation.
        RuleAction action = new RuleAction();
        action.setType("ACCRUE_PREMIUM");
        action.setValue("EARNING_METHOD:LINEAR_WITH_LOADING:not-a-number");
        action.setMessage("Typoed pct");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("\"LINEAR_WITH_LOADING\"");
        // sanitizeDecimal returns "0" for a non-numeric loading percent.
        assertThat(drl).contains("new java.math.BigDecimal(\"0\")");
    }

    @Test
    void emit_messageEscaping_quotesEmbeddedQuotesAndBackslashes() {
        RuleAction action = new RuleAction();
        action.setType("ACCRUE_PREMIUM");
        action.setValue("EARNING_METHOD:DAILY_LINEAR");
        action.setMessage("Line \"A\" — 2026\\Q1");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        // Both a literal backslash and a literal quote inside the message get
        // escaped in the emitted DRL so the string literal stays parseable.
        assertThat(drl).contains("\\\"A\\\"");
        assertThat(drl).contains("\\\\Q1");
    }
}
