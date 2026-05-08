package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class WaitingPeriodTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.WAITING_PERIOD; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("R02-GEN - General illness (90 days)",
                 "Block elective claims during the first 90 days unless the claim is an emergency or accident.",
                 RuleCategory.WAITING_PERIOD, 85,
                 all(cond("member.daysSinceEnrollment", "LESS_THAN", "90"),
                     cond("claim.isEmergency", "EQUALS", "false"),
                     cond("claim.isAccident",  "EQUALS", "false")),
                 reject("R02", "Claim rejected: general illness waiting period of 90 days not met")),

            rule("R02-MAT - Maternity (300 days)",
                 "Standard 10-month maternity waiting period.",
                 RuleCategory.WAITING_PERIOD, 84,
                 all(cond("claim.benefitCategory",         "EQUALS",    "MATERNITY"),
                     cond("member.daysSinceEnrollment",    "LESS_THAN", "300")),
                 reject("R02", "Claim rejected: maternity waiting period of 300 days not met")),

            rule("R02-DEN - Dental prosthetics (180 days)",
                 "Six-month wait before dental prosthetics are covered.",
                 RuleCategory.WAITING_PERIOD, 83,
                 all(cond("claim.benefitCategory",         "EQUALS",    "DENTAL_PROSTHETICS"),
                     cond("member.daysSinceEnrollment",    "LESS_THAN", "180")),
                 reject("R02", "Claim rejected: dental prosthetics waiting period of 180 days not met"))
        );
    }
}
