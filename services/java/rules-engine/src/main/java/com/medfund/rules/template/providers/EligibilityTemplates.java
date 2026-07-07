package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.medfund.rules.template.TemplateBuilder.*;

@Component
public class EligibilityTemplates implements TemplateProvider {

    public RuleCategory category() { return RuleCategory.ELIGIBILITY; }

    public List<RuleDefinition> templates() {
        return List.of(
            rule("R01 - Member must be active",
                 "Reject any claim from a member whose status is not ACTIVE.",
                 RuleCategory.ELIGIBILITY, 100,
                 all(cond("member.status", "NOT_EQUALS", "ACTIVE")),
                 reject("R01", "Claim rejected: member is not in active status")),

            rule("R11 - Contributions not in arrears > 3 months",
                 "Reject when the member has more than three months of unpaid contributions.",
                 RuleCategory.ELIGIBILITY, 95,
                 all(cond("member.arrearsMonths", "GREATER_THAN", "3")),
                 reject("R11", "Claim rejected: member contributions in arrears for more than 3 months")),

            rule("R15 - Claim submitted within 90 days of service",
                 "Reject claims older than 90 days from the date of service.",
                 RuleCategory.ELIGIBILITY, 90,
                 all(cond("claim.daysSinceService", "GREATER_THAN", "90")),
                 reject("R15", "Claim rejected: submitted more than 90 days after date of service")),

            // ── Senior-citizen starter rules (2026-07-07). Complement the
            //    schema-level scheme.min_age/max_age gate: use these when the
            //    tenant wants the cutoff limited to one insurance line or
            //    tied to a soft condition rather than a hard schema bound. ──

            rule("R50 - HEALTH cutoff at 65",
                 "Reject HEALTH claims from members over 65 (typical health-line pattern). Adjust the threshold to match the scheme's underwriting policy.",
                 RuleCategory.ELIGIBILITY, 88,
                 all(cond("member.age",             "GREATER_THAN", "65"),
                     cond("scheme.insuranceLine",   "EQUALS",       "HEALTH")),
                 reject("R50", "Claim rejected: member is above the health-scheme age cutoff")),

            rule("R51 - Cash claims blocked for seniors",
                 "Block cash payouts to members aged 65+; direct-to-provider only. Common for funeral / long-term-care benefits.",
                 RuleCategory.ELIGIBILITY, 87,
                 all(cond("member.age",         "GREATER_THAN_OR_EQUALS", "65"),
                     cond("claim.payoutMode",   "EQUALS",                 "CASH")),
                 reject("R51", "Cash claims not permitted for seniors — provider will be paid directly"))
        );
    }
}
