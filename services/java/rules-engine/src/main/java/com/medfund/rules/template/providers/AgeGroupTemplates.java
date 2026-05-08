package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class AgeGroupTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.AGE_GROUP; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("AG-CHILD - Members under 18 are CHILD",
                 "Tenant-default child band. Adjust the upper bound (LESS_THAN 18) to match scheme rules.",
                 RuleCategory.AGE_GROUP, 90,
                 all(cond("lifecycle.age", "LESS_THAN", "18")),
                 action("SET_AGE_GROUP", "CHILD", "Member classified as CHILD")),

            rule("AG-ADULT - Members 18-64 are ADULT",
                 "The default working-age band.",
                 RuleCategory.AGE_GROUP, 80,
                 all(cond("lifecycle.age", "GREATER_THAN_OR_EQUALS", "18"),
                     cond("lifecycle.age", "LESS_THAN",              "65")),
                 action("SET_AGE_GROUP", "ADULT", "Member classified as ADULT")),

            rule("AG-SENIOR - Members 65+ are SENIOR",
                 "Senior band. Premium rules typically use this to apply higher rates.",
                 RuleCategory.AGE_GROUP, 70,
                 all(cond("lifecycle.age", "GREATER_THAN_OR_EQUALS", "65")),
                 action("SET_AGE_GROUP", "SENIOR", "Member classified as SENIOR"))
        );
    }
}
