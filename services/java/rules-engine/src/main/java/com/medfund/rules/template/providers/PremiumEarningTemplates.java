package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.all;
import static com.medfund.rules.template.TemplateBuilder.any;
import static com.medfund.rules.template.TemplateBuilder.cond;
import static com.medfund.rules.template.TemplateBuilder.rule;

/**
 * Seed templates for the {@code PREMIUM_EARNING} rule category. Each template
 * ships a skeleton {@code ACCRUE_PREMIUM} action encoding an
 * {@code EARNING_METHOD:<name>[:<loading-pct>]} value. Because these rules
 * are agenda-gated (see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) they
 * never fire during the default tenant-rule sweep — the premium consumer
 * in contributions-service explicitly focuses the {@code PREMIUM_EARNING}
 * agenda group on each {@code medfund.user.policy-issued} event and per
 * nightly period-close pass.
 */
@Component
public class PremiumEarningTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.PREMIUM_EARNING;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("E70 - Daily-linear earning (default annual bind)",
                 "Default earning method for annual-bind lines. Strips the written premium "
                       + "pro rata by day across coverage_start..coverage_end. Applies to LIFE, "
                       + "FUNERAL, DISABILITY, VEHICLE, and PROPERTY. Priority 100 so a more "
                       + "specific rule (E71 / E72) can override for a targeted line or product.",
                 RuleCategory.PREMIUM_EARNING, 100,
                 any(cond("premium.insuranceLine", "EQUALS", "LIFE"),
                     cond("premium.insuranceLine", "EQUALS", "FUNERAL"),
                     cond("premium.insuranceLine", "EQUALS", "DISABILITY"),
                     cond("premium.insuranceLine", "EQUALS", "VEHICLE"),
                     cond("premium.insuranceLine", "EQUALS", "PROPERTY")),
                 accruePremium("EARNING_METHOD:DAILY_LINEAR",
                               "Default 1/365ths earning")),

            rule("E71 - Monthly 24ths (IPEC ZW opt-in)",
                 "IPEC-style 24ths strip for motor lines in ZW-regulated tenants. First "
                       + "period accrues 1/24 of the premium, then 1/12 per subsequent period "
                       + "with the tail spread across the final period. Applies to VEHICLE by "
                       + "default; extend the condition to include PROPERTY if the tenant's "
                       + "jurisdiction requires it.",
                 RuleCategory.PREMIUM_EARNING, 90,
                 all(cond("premium.insuranceLine", "EQUALS", "VEHICLE")),
                 accruePremium("EARNING_METHOD:MONTHLY_24THS",
                               "IPEC-mandated 24ths for motor")),

            rule("E72 - Linear-with-loading (front-loaded whole-life)",
                 "Front-loads a percentage of the premium into the first period and strips "
                       + "the remainder linearly. Value encodes the front-load as "
                       + "EARNING_METHOD:LINEAR_WITH_LOADING:<pct>. Adjust the pct or add "
                       + "product-code conditions to target other front-loaded products.",
                 RuleCategory.PREMIUM_EARNING, 80,
                 all(cond("premium.insuranceLine", "EQUALS", "LIFE"),
                     cond("premium.productCode",   "EQUALS", "WHOLE_LIFE")),
                 accruePremium("EARNING_METHOD:LINEAR_WITH_LOADING:15",
                               "15% front-loading for whole-life products"))
        );
    }

    private static RuleAction accruePremium(String encodedValue, String message) {
        RuleAction a = new RuleAction();
        a.setType("ACCRUE_PREMIUM");
        a.setValue(encodedValue);
        a.setMessage(message);
        return a;
    }
}
