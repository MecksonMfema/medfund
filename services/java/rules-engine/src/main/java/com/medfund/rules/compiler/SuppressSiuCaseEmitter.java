package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * {@code SUPPRESS_SIU_CASE} action emitter (Phase 19 §B Phase 9) — the
 * explicit off-switch for the {@code FRAUD_TRIAGE} category. Sets
 * {@code fraudFlag.emitCase = false}. Only meaningful in combination with
 * {@link OpenSiuCaseEmitter} — a suppress-only tenant rule set is
 * equivalent to disabling all templates.
 *
 * <p>Rules using this action ship at the lowest priority so they fire
 * <em>after</em> any {@link OpenSiuCaseEmitter} rule in the same
 * agenda-group pass; Drools' salience ordering guarantees the suppress
 * rule wins the final state per fact.
 *
 * <p>Like {@link OpenSiuCaseEmitter} the action carries no parameters —
 * the mutation is a straight boolean flip; {@link RuleAction#getMessage()}
 * survives only for audit / trace inspection.
 */
@Component
public class SuppressSiuCaseEmitter implements ActionEmitter {

    @Override
    public String type() {
        return "SUPPRESS_SIU_CASE";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        drl.append("    $fraudFlag.setEmitCase(false);\n");
    }
}
