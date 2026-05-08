package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class EligibilityTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.ELIGIBILITY; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("R01 - Member must be active",
                 "Reject any claim from a member whose status is not ACTIVE.",
                 RuleCategory.ELIGIBILITY, 100,
                 all(cond("member.status", "NOT_EQUALS", "ACTIVE")),
                 reject("R01", "Claim rejected: member is not in active status")),

            rule("R11 - Contributions not in arrears > 3 months",
                 "Reject when the member has more than three months of unpaid contributions.",
                 RuleCategory.ELIGIBILITY, 95,
                 all(cond("member.arrearsMonths", "GREATER_THAN", "3")),
                 reject("R11", "Claim rejected: member contributions in arrears for more than 3 months")),

            rule("R15 - Claim submitted within 90 days of service",
                 "Reject claims older than 90 days from the date of service.",
                 RuleCategory.ELIGIBILITY, 90,
                 all(cond("claim.daysSinceService", "GREATER_THAN", "90")),
                 reject("R15", "Claim rejected: submitted more than 90 days after date of service"))
        );
    }
}
