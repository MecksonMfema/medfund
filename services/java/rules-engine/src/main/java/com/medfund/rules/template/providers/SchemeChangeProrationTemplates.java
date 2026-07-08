package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

/**
 * Seed templates for the BENEFIT_PRORATION category. Each shipped template
 * demonstrates a common way tenants might want to route a scheme-change
 * proration decision. The visual rule builder in the Angular admin portal
 * picks these up automatically via the {@code TemplateProvider} SPI.
 *
 * <p>Rules in this category are agenda-gated — they only fire when
 * {@code ProrationService} sets focus on the BENEFIT_PRORATION agenda-group
 * during stage 3 of adjudication. The seven baked-in strategy names come from
 * {@code ProrationStrategy}: NONE, DELTA_CREDIT, RATIO_CARRY, CALENDAR,
 * SPLIT_YEAR, WAITING_PERIOD_ON_INCREMENT, HYBRID_BY_DIRECTION.
 */
@Component
public class SchemeChangeProrationTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.BENEFIT_PRORATION; }

    public List<RuleDefinition> templates() {
        return List.of(
            // T1 — route by benefit category. Common shape: dental gets a
            // fresh full limit on scheme change, other categories prorate.
            rule("Fresh dental limit on scheme change",
                 "For claims where the benefit category is DENTAL, use the new " +
                 "scheme's full annual limit (NONE strategy — no proration). All " +
                 "other categories fall through to the tenant default.",
                 RuleCategory.BENEFIT_PRORATION, 80,
                 all(cond("claim.benefitCategory", "EQUALS", "DENTAL")),
                 applyProrationStrategy("NONE",
                     "DENTAL claims start fresh under the new scheme's full limit")),

            // T2 — pin a specific scheme. Useful when a tenant offers a
            // marketing-driven upgrade scheme where mid-year switches should
            // always use CALENDAR to protect the loss ratio.
            rule("CALENDAR proration for premium schemes",
                 "When the member's current scheme is the premium tier, prorate the " +
                 "new-scheme limit by the fraction of the year remaining.",
                 RuleCategory.BENEFIT_PRORATION, 70,
                 all(cond("claim.schemeId", "EQUALS", "REPLACE_WITH_PREMIUM_SCHEME_UUID")),
                 applyProrationStrategy("CALENDAR",
                     "Premium scheme mid-year switches prorate by calendar")),

            // T3 — increment-waiting-period for high-value benefits. Guards
            // against adverse selection on rich benefits (e.g. optical, ICU).
            rule("Increment waiting period for high-value benefits",
                 "When a benefit's annual limit exceeds 5000 and the member is within " +
                 "60 days of a scheme change, cap them at the OLD scheme's limit until " +
                 "the increment unlocks.",
                 RuleCategory.BENEFIT_PRORATION, 60,
                 all(cond("member.benefitLimit", "GREATER_THAN", 5000),
                     cond("member.daysSinceSchemeChange", "LESS_THAN", 60)),
                 applyProrationStrategy("WAITING_PERIOD_ON_INCREMENT",
                     "High-value benefit — increment gated 60 days"))
        );
    }
}
