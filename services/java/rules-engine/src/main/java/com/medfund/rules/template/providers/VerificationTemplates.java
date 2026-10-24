package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.all;
import static com.medfund.rules.template.TemplateBuilder.cond;
import static com.medfund.rules.template.TemplateBuilder.rule;

/**
 * Seed templates for the {@code VERIFICATION} rule category (KYC horizontal,
 * Plan A Phase 3). Templates mutate the two {@code VerificationFact} result
 * flags: {@code REQUIRE_MANUAL_REVIEW} flips {@code requireManualReview = true};
 * {@code GATE_ACTIVATION} flips {@code gateActivation = true}. kyc-service reads
 * both flags after the agenda group fires.
 *
 * <p>Rules in this category are agenda-gated (see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) so they only fire when kyc-service
 * explicitly focuses the {@code VERIFICATION} group per subject — never during
 * the stage-7 tenant-rule sweep.
 *
 * <p>Line-neutral by design: conditions key off {@code subjectType},
 * {@code insuranceLine}, {@code country}, {@code screeningResult}, and
 * {@code verificationLevel} — the subject is a party, never a "patient".
 *
 * <p>Two shipped starters:
 * <ol>
 *   <li><b>manual-review-on-screening-hit</b> — routes a verification to a human
 *       reviewer when sanctions/PEP screening returns a hit. The common
 *       starter; most tenants want a hit to block straight-through activation.</li>
 *   <li><b>gate-activation-when-verified</b> — signals that a verification tier
 *       at or above level 2 is sufficient to gate activation of the subject's
 *       policy/benefit. Change the level to match the tenant's required tier.</li>
 * </ol>
 */
@Component
public class VerificationTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.VERIFICATION;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("VERIF1 - Require manual review on screening hit",
                 "Route a verification to a human reviewer whenever sanctions/PEP "
                       + "screening returns a hit. Mirrors the common default policy - "
                       + "most tenants want a screening hit to block straight-through "
                       + "activation until an officer clears it.",
                 RuleCategory.VERIFICATION, 100,
                 all(cond("verification.screeningResult", "EQUALS", "hit")),
                 requireManualReview("Sanctions/PEP screening hit")),

            rule("VERIF2 - Gate activation when verification tier is sufficient",
                 "Signal that the subject's verification tier is high enough to gate "
                       + "activation of their policy/benefit. Change the level to match "
                       + "the tenant's required tier per line/jurisdiction - a higher "
                       + "tier is how enhanced per-line requirements are expressed "
                       + "(never a second verification row).",
                 RuleCategory.VERIFICATION, 90,
                 all(cond("verification.verificationLevel", "GREATER_THAN_OR_EQUALS", 2)),
                 gateActivation("Verification tier sufficient to gate activation"))
        );
    }

    private static RuleAction requireManualReview(String message) {
        RuleAction a = new RuleAction();
        a.setType("REQUIRE_MANUAL_REVIEW");
        a.setMessage(message);
        return a;
    }

    private static RuleAction gateActivation(String message) {
        RuleAction a = new RuleAction();
        a.setType("GATE_ACTIVATION");
        a.setMessage(message);
        return a;
    }
}
