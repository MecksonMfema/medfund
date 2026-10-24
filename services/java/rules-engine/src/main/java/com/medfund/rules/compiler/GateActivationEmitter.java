package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * {@code GATE_ACTIVATION} action emitter for the {@code VERIFICATION} category
 * (KYC horizontal, Plan A Phase 3). Sets
 * {@code verification.gateActivation = true} so kyc-service reads the flag back
 * after the agenda group fires and treats the verification tier as sufficient
 * to gate activation of the subject's policy/benefit.
 *
 * <p>Rules with this action must live in the {@code VERIFICATION} category — see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The action carries no parameters;
 * {@link RuleAction#getMessage()} is captured only for audit / trace.
 */
@Component
public class GateActivationEmitter implements ActionEmitter {

    @Override
    public String type() {
        return "GATE_ACTIVATION";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        drl.append("    $verification.setGateActivation(true);\n");
    }
}
