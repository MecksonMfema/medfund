package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class PreAuthorizationTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.PRE_AUTHORIZATION; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("R04 - Pre-authorisation required but not obtained",
                 "Reject elective procedures lacking pre-authorisation.",
                 RuleCategory.PRE_AUTHORIZATION, 75,
                 all(cond("claim.isElective", "EQUALS", "true"),
                     cond("claim.hasPreAuth", "EQUALS", "false")),
                 reject("R04", "Claim rejected: pre-authorisation is required for elective procedures but was not obtained")),

            rule("R05 - Pre-authorisation expired",
                 "Reject when the procedure's pre-auth has lapsed.",
                 RuleCategory.PRE_AUTHORIZATION, 74,
                 all(cond("claim.hasPreAuth",     "EQUALS", "true"),
                     cond("claim.preAuthStatus",  "EQUALS", "EXPIRED")),
                 reject("R05", "Claim rejected: pre-authorisation has expired"))
        );
    }
}
