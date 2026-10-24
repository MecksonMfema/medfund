package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * {@code REQUIRE_MANUAL_REVIEW} action emitter for the {@code VERIFICATION}
 * category (KYC horizontal, Plan A Phase 3). Sets
 * {@code verification.requireManualReview = true} so kyc-service reads the flag
 * back after the agenda group fires and routes the verification to a human
 * reviewer.
 *
 * <p>Rules with this action must live in the {@code VERIFICATION} category — see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The action carries no parameters;
 * {@link RuleAction#getMessage()} is captured only for audit / trace.
 */
@Component
public class RequireManualReviewEmitter implements ActionEmitter {

    @Override
    public String type() {
        return "REQUIRE_MANUAL_REVIEW";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        drl.append("    $verification.setRequireManualReview(true);\n");
    }
}
