package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class ContributionPricingTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.CONTRIBUTION_PRICING; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("CP01 - Base premium for adults on STANDARD",
                 "Anchor premium for adult members on the STANDARD scheme. Tenants override per scheme.",
                 RuleCategory.CONTRIBUTION_PRICING, 90,
                 all(cond("contribution.schemeId",   "EQUALS", "STANDARD"),
                     cond("contribution.memberAge",  "GREATER_THAN_OR_EQUALS", "18"),
                     cond("contribution.memberAge",  "LESS_THAN",              "65")),
                 action("SET_PREMIUM", "100.00", "Standard adult premium")),

            rule("CP02 - Senior surcharge on STANDARD",
                 "Adults aged 65+ on STANDARD pay a higher premium.",
                 RuleCategory.CONTRIBUTION_PRICING, 80,
                 all(cond("contribution.schemeId",   "EQUALS",                 "STANDARD"),
                     cond("contribution.memberAge",  "GREATER_THAN_OR_EQUALS", "65")),
                 action("SET_PREMIUM", "150.00", "Senior premium on STANDARD scheme")),

            rule("CP03 - Per-dependant surcharge",
                 "Add a per-dependant surcharge through the loaded-premium multiplier.",
                 RuleCategory.CONTRIBUTION_PRICING, 60,
                 all(cond("contribution.dependantCount", "GREATER_THAN", "0")),
                 action("APPLY_LOADED_PREMIUM", "1.10",
                        "Per-dependant 10% loading"))
        );
    }
}
