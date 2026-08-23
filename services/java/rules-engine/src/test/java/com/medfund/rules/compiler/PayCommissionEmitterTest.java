package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level assertions for the PAY_COMMISSION DRL output. Complements the
 * end-to-end round-trip tests in {@link DrlCompilerTest} — this class pins
 * the encoding branches so a change to the emitter cannot silently ship
 * a broken DRL string.
 */
class PayCommissionEmitterTest {

    private final PayCommissionEmitter emitter = new PayCommissionEmitter();

    @Test
    void emit_rateCardEncoding_carriesIdWithZeroAmountMarker() {
        RuleAction action = new RuleAction();
        action.setType("PAY_COMMISSION");
        action.setRejectionCode("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        action.setValue("RATE_CARD:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        action.setMessage("Health base");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        // Producer id → first arg, ZERO marker → second arg, rate-card id → third arg.
        assertThat(drl).contains("$contribution.addCommission(");
        assertThat(drl).contains("\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"");
        assertThat(drl).contains("java.math.BigDecimal.ZERO");
        assertThat(drl).contains("\"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb\"");
        assertThat(drl).contains("\"Health base\"");
    }

    @Test
    void emit_kickerEncoding_computesPremiumTimesBp() {
        RuleAction action = new RuleAction();
        action.setType("PAY_COMMISSION");
        action.setRejectionCode("cccccccc-cccc-cccc-cccc-cccccccccccc");
        action.setValue("KICKER:150:promo");
        action.setMessage("Q3 promo kicker");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        // Amount = premiumAmount × 150 × 10^-4 (i.e. 1.5%).
        assertThat(drl).contains("$contribution.getPremiumAmount()");
        assertThat(drl).contains("multiply(new java.math.BigDecimal(\"150\"))");
        assertThat(drl).contains("movePointLeft(4)");
        // Rate-card id slot is empty for a KICKER-only rule.
        assertThat(drl).contains("addCommission(\"cccccccc-cccc-cccc-cccc-cccccccccccc\"");
        assertThat(drl).contains("\"\"");
    }

    @Test
    void emit_missingValue_fallsBackToZeroAmountMarker() {
        RuleAction action = new RuleAction();
        action.setType("PAY_COMMISSION");
        action.setRejectionCode("dddddddd-dddd-dddd-dddd-dddddddddddd");
        action.setValue(null);
        action.setMessage("");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        // Neither RATE_CARD: nor KICKER: → safe zero-amount marker, no NPE.
        assertThat(drl).contains("java.math.BigDecimal.ZERO");
        assertThat(drl).contains("addCommission(\"dddddddd-dddd-dddd-dddd-dddddddddddd\"");
    }

    @Test
    void emit_emptyProducerId_encodesAsEmptyString() {
        // Empty producer id → CommissionCalcService defers to the member's
        // currently-assigned producer via member_producer_assignment.
        RuleAction action = new RuleAction();
        action.setType("PAY_COMMISSION");
        action.setRejectionCode(null);
        action.setValue("KICKER:10");
        action.setMessage("Default-producer 10bp bump");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        assertThat(drl).contains("addCommission(\"\", ");
        assertThat(drl).contains("multiply(new java.math.BigDecimal(\"10\"))");
    }

    @Test
    void emit_messageEscaping_quotesEmbeddedQuotesAndBackslashes() {
        RuleAction action = new RuleAction();
        action.setType("PAY_COMMISSION");
        action.setRejectionCode("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        action.setValue("KICKER:5");
        action.setMessage("Broker \"Alice\" — Q3\\Bonus");

        StringBuilder drl = new StringBuilder();
        emitter.emit(drl, action);

        // Both a literal backslash and a literal quote inside the message get
        // escaped in the emitted DRL so the string literal stays parseable.
        assertThat(drl).contains("\\\"Alice\\\"");
        assertThat(drl).contains("\\\\Bonus");
    }
}
