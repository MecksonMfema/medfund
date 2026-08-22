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
 * Seed templates for the reinsurance rule category. Each template ships a
 * skeleton — treaty id, cession rate, and layer bands are placeholders the
 * underwriter fills in during rule authoring. Because CEDE_TO_TREATY rules
 * are agenda-gated (see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) they
 * never fire during the default tenant-rule sweep — the reinsurance
 * consumers in finance-service explicitly focus the REINSURANCE agenda
 * group per event.
 */
@Component
public class ReinsuranceTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.REINSURANCE;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("R60 - Quota Share cede (proportional, single line)",
                 "Cede a fixed percentage of every HEALTH claim above zero to the named treaty. "
                       + "Replace <treaty-id> with the target Treaty UUID and adjust the 30% rate "
                       + "to the treaty's cession ratio.",
                 RuleCategory.REINSURANCE, 50,
                 all(cond("claim.amount",      "GREATER_THAN", "0"),
                     cond("scheme.insuranceLine", "EQUALS",    "HEALTH")),
                 cedeToTreaty("<treaty-id>", "PCT:30",
                              "Quota Share 30% cession")),

            rule("R61 - Surplus Share cede (proportional, single line)",
                 "Same shape as Quota Share but typically used above a fund retention. Cede a fixed "
                       + "share of the claim to the surplus reinsurer. Rate reflects the surplus split.",
                 RuleCategory.REINSURANCE, 49,
                 all(cond("claim.amount",      "GREATER_THAN", "50000"),
                     cond("scheme.insuranceLine", "EQUALS",    "HEALTH")),
                 cedeToTreaty("<treaty-id>", "PCT:60",
                              "Surplus Share 60% cession above 50k retention")),

            rule("R62 - Excess of Loss cede (single layer)",
                 "Cede the portion of the claim that sits inside an XoL layer band. "
                       + "Value encodes retention;limit;layerId — the emitter computes "
                       + "max(0, min(limit, amount - retention)) at rule-eval time.",
                 RuleCategory.REINSURANCE, 48,
                 all(cond("claim.amount",      "GREATER_THAN", "100000"),
                     cond("scheme.insuranceLine", "EQUALS",    "HEALTH")),
                 cedeToTreaty("<treaty-id>", "XOL:100000;500000;<layer-id>",
                              "XoL layer: 500k xs 100k")),

            rule("R63 - Stop Loss cede (aggregate)",
                 "Stop-loss cede on aggregate claim tally above a fund retention. Same XOL shape "
                       + "as R62 — the layer contains the aggregate band, not per-claim excess.",
                 RuleCategory.REINSURANCE, 47,
                 all(cond("claim.amount",      "GREATER_THAN", "0")),
                 cedeToTreaty("<treaty-id>", "XOL:0;1000000;<layer-id>",
                              "Stop Loss: 1M aggregate cover")),

            // Phase 6 — proportional premium cede fired per contribution paid.
            // The reinsurance premium-cession consumer builds a ClaimFact from
            // the paid contribution's amount (see PremiumCessionService), so
            // the same claim.amount / scheme.insuranceLine shape as R60 works
            // uniformly for loss + premium cedes.
            rule("R64 - Quota Share cede on premium payment (proportional)",
                 "Cede a fixed percentage of every paid contribution to the named treaty. "
                       + "The premium-cession consumer projects the contribution into a "
                       + "ClaimFact whose amount = contribution amount, then focuses the "
                       + "REINSURANCE agenda — so this rule shape mirrors R60 exactly. "
                       + "Replace <treaty-id> with the target Treaty UUID and set the "
                       + "PCT rate to the treaty's cession ratio.",
                 RuleCategory.REINSURANCE, 46,
                 all(cond("claim.amount",      "GREATER_THAN", "0"),
                     cond("scheme.insuranceLine", "EQUALS",    "HEALTH")),
                 cedeToTreaty("<treaty-id>", "PCT:30",
                              "Quota Share 30% premium cession"))
        );
    }

    private static RuleAction cedeToTreaty(String treatyId, String encodedValue, String message) {
        RuleAction a = new RuleAction();
        a.setType("CEDE_TO_TREATY");
        a.setRejectionCode(treatyId);
        a.setValue(encodedValue);
        a.setMessage(message);
        return a;
    }
}
