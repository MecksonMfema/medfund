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
                        "Fixed 25.00 co-pay — optical claim"))
        );
    }
}
