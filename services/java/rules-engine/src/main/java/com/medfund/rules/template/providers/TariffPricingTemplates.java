package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class TariffPricingTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.TARIFF_PRICING; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("TAR01 - Cap billed amount to tariff",
                 "Approved amount on every claim line is reduced to the tariff schedule rate.",
                 RuleCategory.TARIFF_PRICING, 70,
                 all(cond("claimDetail.billedAmount", "GREATER_THAN", "0")),
                 capToTariff("Billed amount capped to tariff rate"))
        );
    }
}
