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
                        "BMI >= 35 — medical exam required before approval")),

            // ── Senior-citizen starters (2026-07-07). Use these when the
            //    scheme accepts older joiners but at a higher premium — the
            //    APPLY_LOADED_PREMIUM action multiplies the age-band base
            //    price on the member's first invoice. Complements schema
            //    scheme.min_age/max_age (which is a hard reject). ──

            rule("UW04 - Senior 65+ loaded 50%",
                 "Apply a 50% premium loading for members joining at 65 or older. Adjust the factor and threshold per line (LIFE typically uses 1.5, FUNERAL 1.25).",
                 RuleCategory.UNDERWRITING, 65,
                 all(cond("lifecycle.age", "GREATER_THAN_OR_EQUALS", "65")),
                 action("APPLY_LOADED_PREMIUM", "1.5",
                        "Senior joiner — premium loaded 50%")),

            rule("UW05 - Late-joiner senior review",
                 "Flag underwriting when a member joins after 65 — often paired with UW04 for lines that require both a loading AND a manual review.",
                 RuleCategory.UNDERWRITING, 60,
                 all(cond("lifecycle.age", "GREATER_THAN_OR_EQUALS", "65")),
                 action("REQUIRE_UNDERWRITING", "MED",
                        "Late-joiner senior — human underwriter must confirm terms"))
        );
    }
}
