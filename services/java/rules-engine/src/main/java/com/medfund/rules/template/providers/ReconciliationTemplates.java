package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class ReconciliationTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.RECONCILIATION; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("RECON01 - Auto-match settled provider runs",
                 "Mark a payment-run record as reconciled once it's been verified and settled.",
                 RuleCategory.RECONCILIATION, 80,
                 all(cond("paymentRun.providerVerified", "EQUALS", "true"),
                     cond("paymentRun.amountDue",        "GREATER_THAN", "0")),
                 action("MATCH_RECORDS", "AUTO", null,
                        "Verified provider with positive due amount — auto-match candidate"))
        );
    }
}
