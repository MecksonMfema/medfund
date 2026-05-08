package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class MemberLifecycleTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.MEMBER_LIFECYCLE; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("LIFE01 - Auto-terminate after 6 months arrears",
                 "Trigger termination when the member has been six full months in arrears.",
                 RuleCategory.MEMBER_LIFECYCLE, 90,
                 all(cond("lifecycle.contributionsInArrearsMonths", "GREATER_THAN_OR_EQUALS", "6")),
                 action("TERMINATE_MEMBERSHIP", "ARREARS_6M", null,
                        "Auto-terminated for non-payment (>=6 months arrears)")),

            rule("LIFE02 - Auto-renew active members",
                 "Members in good standing are eligible for auto-renewal at scheme anniversary.",
                 RuleCategory.MEMBER_LIFECYCLE, 50,
                 all(cond("lifecycle.currentStatus",                "EQUALS",   "ACTIVE"),
                     cond("lifecycle.contributionsInArrearsMonths", "EQUALS",   "0")),
                 action("AUTO_RENEW", null, "Member is up-to-date and eligible for auto-renewal"))
        );
    }
}
