package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class ClinicalValidationTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.CLINICAL_VALIDATION; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("R17 - Gender-inappropriate procedure",
                 "Reject maternity claims for male members.",
                 RuleCategory.CLINICAL_VALIDATION, 65,
                 all(cond("claim.benefitCategory", "EQUALS", "MATERNITY"),
                     cond("member.gender",         "EQUALS", "MALE")),
                 reject("R17", "Claim rejected: procedure is not appropriate for the member's gender")),

            rule("R18 - Age-inappropriate procedure (paediatric for adult)",
                 "Reject paediatric procedures for members over 18.",
                 RuleCategory.CLINICAL_VALIDATION, 64,
                 all(cond("claim.benefitCategory", "EQUALS",       "PAEDIATRIC"),
                     cond("member.age",            "GREATER_THAN", "18")),
                 reject("R18", "Claim rejected: paediatric procedure is not appropriate for member's age"))
        );
    }
}
