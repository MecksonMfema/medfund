package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class ProviderPaymentTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.PROVIDER_PAYMENT; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("PR01 - Schedule payment runs on the 15th",
                 "Trigger the standard provider payment run on the 15th of each month.",
                 RuleCategory.PROVIDER_PAYMENT, 90,
                 all(cond("time.dayOfMonth",            "EQUALS", "15"),
                     cond("paymentRun.providerVerified", "EQUALS", "true")),
                 action("SCHEDULE_PAYMENT_RUN", "TODAY",
                        "Mid-month standard payment run")),

            rule("PR02 - Withhold 10% from unverified providers",
                 "Hold back 10% of the run amount until provider verification is complete.",
                 RuleCategory.PROVIDER_PAYMENT, 80,
                 all(cond("paymentRun.providerVerified", "EQUALS", "false")),
                 action("WITHHOLD_PAYMENT", "10",
                        "Withhold 10% — provider not yet verified")),

            rule("PR03 - Block runs with no claims processed",
                 "Don't pay providers in months where no claims have been settled.",
                 RuleCategory.PROVIDER_PAYMENT, 70,
                 all(cond("paymentRun.outstandingClaimsCount", "EQUALS", "0")),
                 action("WITHHOLD_PAYMENT", "100",
                        "Withhold 100% — no claims processed in this period"))
        );
    }
}
