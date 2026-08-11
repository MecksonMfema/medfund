package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class CoPaymentTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.CO_PAYMENT; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("CP01 - 20% co-pay on out-of-network providers",
                 "Members pay 20% of the claim amount when the provider is out of network.",
                 RuleCategory.CO_PAYMENT, 70,
                 all(cond("provider.inNetwork", "EQUALS", "false")),
                 action("APPLY_COPAY", "20",
                        "20% co-pay — out-of-network provider")),

            rule("CP02 - Fixed co-pay on optical claims",
                 "Members pay a fixed 25.00 on every optical claim.",
                 RuleCategory.CO_PAYMENT, 60,
                 all(cond("claim.benefitCategory", "EQUALS", "OPTICAL")),
                 action("APPLY_COPAY", "FIXED:25",
                        "Fixed 25.00 co-pay — optical claim")),

            // V077 waiver templates (G14). All three fire APPLY_COPAY with a zero
            // value; CostShareCalculator interprets that as "member owes no copay
            // on this claim" and the rule-override branch (G4) short-circuits.

            rule("WAIVE_PREVENTIVE - Waive copay on preventive care",
                 "Preventive-care claims incur no member copay. Higher priority than "
                    + "any percentage or fixed-copay rule so preventive services stay free "
                    + "at the point of service.",
                 RuleCategory.CO_PAYMENT, 90,
                 all(cond("claim.benefitCategory", "EQUALS", "PREVENTIVE")),
                 action("APPLY_COPAY", "FIXED:0",
                        "Preventive care — copay waived")),

            rule("WAIVE_EMERGENCY_ADMISSION - Waive copay on emergency admissions",
                 "Emergency-admission claims incur no member copay. A member cannot "
                    + "be asked to co-pay at the door of an ER — the fund absorbs the copay.",
                 RuleCategory.CO_PAYMENT, 85,
                 all(cond("claim.isEmergency", "EQUALS", "true")),
                 action("APPLY_COPAY", "FIXED:0",
                        "Emergency admission — copay waived")),

            rule("WAIVE_IN_NETWORK_TIER_1 - Waive copay for tier-1 in-network providers",
                 "Tier-1 in-network providers (the fund's preferred partners) incur no "
                    + "member copay. Tenants configure tier membership via the network-tier "
                    + "picker; TIER_1 is the highest-preference band.",
                 RuleCategory.CO_PAYMENT, 80,
                 all(cond("provider.inNetwork",   "EQUALS", "true"),
                     cond("provider.networkTier", "EQUALS", "TIER_1")),
                 action("APPLY_COPAY", "FIXED:0",
                        "Tier-1 in-network provider — copay waived"))
        );
    }
}
