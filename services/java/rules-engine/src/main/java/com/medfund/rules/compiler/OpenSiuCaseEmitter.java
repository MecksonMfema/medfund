package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * {@code OPEN_SIU_CASE} action emitter — the only action a
 * {@code FRAUD_TRIAGE} rule can take. Sets {@code fraudFlag.emitCase = true}
 * so claims-service's {@code SiuCaseService.evaluateTriage} reads the flag
 * back after the agenda group fires and opens an {@code siu_case}.
 *
 * <p>Rules with this action must live in the {@code FRAUD_TRIAGE} category —
 * see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. Fires only when
 * {@code SiuCaseService} explicitly focuses the {@code FRAUD_TRIAGE}
 * agenda group per fraud_flag; never during the stage-7 tenant-rules sweep.
 *
 * <p>The action carries no parameters — the mutation is a straight
 * boolean flip. {@link RuleAction#getMessage()} is captured only for
 * audit / trace inspection.
 */
@Component
public class OpenSiuCaseEmitter implements ActionEmitter {

    @Override
    public String type() {
        return "OPEN_SIU_CASE";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        drl.append("    $fraudFlag.setEmitCase(true);\n");
    }
}
