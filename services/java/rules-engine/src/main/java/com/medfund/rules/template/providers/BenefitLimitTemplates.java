package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class BenefitLimitTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.BENEFIT_LIMIT; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("R03 - Benefit limit exhausted",
                 "Reject claims when the benefit category's annual limit has been used up.",
                 RuleCategory.BENEFIT_LIMIT, 80,
                 all(cond("member.benefitRemaining", "LESS_THAN_OR_EQUALS", "0")),
                 reject("R03", "Claim rejected: benefit limit for this category has been exhausted"))
        );
    }
}
