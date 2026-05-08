package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class UnderwritingTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.UNDERWRITING; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("UW01 - Pre-existing conditions require review",
                 "Flag enrollment for human underwriter when the applicant declares pre-existing conditions.",
                 RuleCategory.UNDERWRITING, 90,
                 all(cond("lifecycle.hasPreExistingConditions", "EQUALS", "true")),
                 action("REQUIRE_UNDERWRITING", "HIGH",
                        "Pre-existing conditions declared — manual review required")),

            rule("UW02 - Senior smokers loaded by 25%",
                 "Apply a risk loading on the standard premium for smoking applicants over 50.",
                 RuleCategory.UNDERWRITING, 80,
                 all(cond("lifecycle.smoker", "EQUALS",      "true"),
                     cond("lifecycle.age",    "GREATER_THAN", "50")),
                 action("APPLY_LOADED_PREMIUM", "1.25",
                        "Senior smoker — premium loaded 25%")),

            rule("UW03 - High BMI requires medical exam",
                 "Underwriting flag when BMI is 35 or above.",
                 RuleCategory.UNDERWRITING, 70,
                 all(cond("lifecycle.bmi", "GREATER_THAN_OR_EQUALS", "35")),
                 action("REQUIRE_UNDERWRITING", "MED",
                        "BMI >= 35 — medical exam required before approval"))
        );
    }
}
