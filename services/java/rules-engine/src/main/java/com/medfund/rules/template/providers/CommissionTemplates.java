package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.all;
import static com.medfund.rules.template.TemplateBuilder.cond;
import static com.medfund.rules.template.TemplateBuilder.rule;

/**
 * Seed templates for the producer commission rule category. The base
 * commission rate is looked up in {@code commission_rate_card} by
 * {@code CommissionCalcService} — templates here cover the two
 * user-authored shapes on top:
 *
 * <ul>
 *   <li>{@code RATE_CARD:<uuid>} — pin a specific rate card for a
 *       (line, tier) combination.</li>
 *   <li>{@code KICKER:<pct-bp>[:<reason>]} — additive/subtractive kicker
 *       expressed in basis points.</li>
 * </ul>
 *
 * <p>PAY_COMMISSION rules are agenda-gated (see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}). The commission consumer in
 * finance-service focuses the COMMISSION agenda group per
 * {@code medfund.contributions.paid} event, so kickers never fire during
 * the stage-7 tenant-rule sweep.
 */
@Component
public class CommissionTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.COMMISSION;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("R70 - Pin rate card for a producer tier (HEALTH)",
                 "Match paid HEALTH contributions and pay commission according to the "
                       + "named rate card. Replace <rate-card-id> with the target card UUID. "
                       + "Leave the producer field empty to defer to the member's "
                       + "currently-assigned producer (via member_producer_assignment).",
                 RuleCategory.COMMISSION, 60,
                 all(cond("contribution.insuranceLine", "EQUALS", "HEALTH")),
                 payCommission("", "RATE_CARD:<rate-card-id>",
                               "Health base commission per rate card")),

            rule("R71 - Promo kicker (basis points on top of base)",
                 "Additive kicker on top of the rate-card base. Value encodes basis "
                       + "points - 100 bp = 1%. Replace <producer-id> with the target "
                       + "producer UUID (or leave empty to apply to whoever is assigned). "
                       + "Use a negative bp value to discount, positive to bump.",
                 RuleCategory.COMMISSION, 55,
                 all(cond("contribution.insuranceLine", "EQUALS", "HEALTH")),
                 payCommission("<producer-id>", "KICKER:25:Q3 promo",
                               "Q3 promotional kicker (+25 bp)")),

            rule("R72 - Sub-producer split for hierarchy rollup",
                 "Kicker awarded to a sub-producer whose parent producer is the assigned "
                       + "one - the hierarchy walk happens on the consumer side. Combine "
                       + "with R70 or a rate card that pays the master to model an "
                       + "override commission structure.",
                 RuleCategory.COMMISSION, 50,
                 all(cond("contribution.insuranceLine", "EQUALS", "HEALTH")),
                 payCommission("<sub-producer-id>", "KICKER:50:sub-producer share",
                               "Sub-producer 0.5% split"))
        );
    }

    private static RuleAction payCommission(String producerId, String encodedValue, String message) {
        RuleAction a = new RuleAction();
        a.setType("PAY_COMMISSION");
        a.setRejectionCode(producerId);
        a.setValue(encodedValue);
        a.setMessage(message);
        return a;
    }
}
