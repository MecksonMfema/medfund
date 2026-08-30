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
 * Seed templates for the {@code IFRS17_MODEL} rule category. Each template
 * ships a skeleton {@code SELECT_IFRS17_MODEL} action encoding
 * {@code IFRS17_MODEL:<model>:<coverage>:<fee>:<expense>} — see
 * {@link com.medfund.rules.compiler.SelectIfrs17ModelEmitter} for the
 * value grammar. Because these rules are agenda-gated (see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) they never fire during the
 * default tenant-rule sweep — finance-service's
 * {@code Ifrs17ShapingService} explicitly focuses the {@code IFRS17_MODEL}
 * agenda group per portfolio at IFRS 17 report-submit time and reads the
 * chosen model + supporting shape choices back off the fact.
 */
@Component
public class Ifrs17ModelTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.IFRS17_MODEL;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of(
            rule("I90 - PAA (Premium Allocation Approach)",
                 "Route short-duration portfolios (typically ≤12 months coverage) to the "
                       + "Premium Allocation Approach per IFRS 17.53-59. Default target is HEALTH "
                       + "where the annual renewal cycle keeps coverage duration inside the PAA "
                       + "eligibility window; extend or narrow the insurance-line condition to "
                       + "match your tenant's short-duration lines. Coverage-unit pattern defaults "
                       + "to TIME (proportional to elapsed coverage period); finance-expense "
                       + "presentation defaults to P&L only (no OCI split).",
                 RuleCategory.IFRS17_MODEL, 100,
                 all(cond("portfolio.insuranceLine", "EQUALS", "HEALTH")),
                 selectIfrs17Model("IFRS17_MODEL:PAA:TIME:null:PL_ONLY",
                                   "PAA selected — short-duration HEALTH")),

            rule("I91 - GMM (General Measurement Model)",
                 "Route long-duration portfolios to the General Measurement Model per IFRS "
                       + "17.32-52. Default target is LIFE where the multi-decade coverage "
                       + "horizon rules out PAA eligibility. Coverage-unit pattern defaults to "
                       + "TIME; finance-expense presentation defaults to the OCI option so "
                       + "insurance-finance income/expense splits between P&L and OCI per IFRS "
                       + "17.88(b) — flip to PL_ONLY if your tenant elects the single-line "
                       + "presentation.",
                 RuleCategory.IFRS17_MODEL, 90,
                 all(cond("portfolio.insuranceLine", "EQUALS", "LIFE")),
                 selectIfrs17Model("IFRS17_MODEL:GMM:TIME:null:OCI_OPTION",
                                   "GMM selected — long-duration LIFE")),

            rule("I92 - VFA (Variable Fee Approach)",
                 "Route direct-participation (unit-linked) portfolios to the Variable Fee "
                       + "Approach per IFRS 17.71 + B101-B118. Default target is any LIFE "
                       + "portfolio where the tenant admin subsequently opts in via the "
                       + "underwriting funds admin (Phase 15 §8) — the shaping service filters "
                       + "on VFA-tagged portfolios only. Variable-fee pattern defaults to "
                       + "FIXED_PCT (single fee % applied to NAV growth); switch to TIERED for "
                       + "AUM-band scaling, NAV_LINKED for pattern-based caps. "
                       + "Finance-expense presentation defaults to the OCI option.",
                 RuleCategory.IFRS17_MODEL, 80,
                 all(cond("portfolio.insuranceLine", "EQUALS", "LIFE")),
                 selectIfrs17Model("IFRS17_MODEL:VFA:TIME:FIXED_PCT:OCI_OPTION",
                                   "VFA selected — direct-participation LIFE"))
        );
    }

    private static RuleAction selectIfrs17Model(String encodedValue, String message) {
        RuleAction a = new RuleAction();
        a.setType("SELECT_IFRS17_MODEL");
        a.setValue(encodedValue);
        a.setMessage(message);
        return a;
    }
}
