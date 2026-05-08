package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class ContributionBillingTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.CONTRIBUTION_BILLING; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("BILL01 - Late fee after 30 days overdue",
                 "Apply a flat 25.00 late fee once a contribution is more than 30 days overdue.",
                 RuleCategory.CONTRIBUTION_BILLING, 80,
                 all(cond("contribution.daysOverdue", "GREATER_THAN", "30"),
                     cond("contribution.paid",        "EQUALS",       "false")),
                 action("APPLY_LATE_FEE", "25.00",
                        "Flat late fee — contribution overdue more than 30 days")),

            rule("BILL02 - Escalated late fee after 90 days",
                 "Members 90+ days overdue trigger an escalated 100.00 fee on top of any earlier ones.",
                 RuleCategory.CONTRIBUTION_BILLING, 70,
                 all(cond("contribution.daysOverdue", "GREATER_THAN", "90"),
                     cond("contribution.paid",        "EQUALS",       "false")),
                 action("APPLY_LATE_FEE", "100.00",
                        "Escalated late fee — contribution overdue more than 90 days"))
        );
    }
}
