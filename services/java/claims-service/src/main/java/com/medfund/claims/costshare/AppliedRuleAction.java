package com.medfund.claims.costshare;

import com.medfund.rules.fact.RuleResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * A structured pass-through of a rules-engine {@link RuleResult} that the
 * cost-share calculator can consume without re-parsing the summarised
 * {@code TenantRules} StageResult string.
 *
 * <p>Type maps to the action kind (e.g. {@code APPLY_COPAY},
 * {@code CAP_TO_TARIFF}). {@code value} is the raw action value string as the
 * template authored it: {@code "20"}, {@code "FIXED:25"}, {@code "0"}.
 *
 * <p>Priority tie-break for competing APPLY_COPAY fires: Drools fires by
 * salience (highest first). The first APPLY_COPAY in the list is therefore
 * the winner per G4. If salience is unset the natural rule-order applies —
 * this is deliberately a small MVP behaviour; a formal priority column on
 * {@link RuleResult} is deferred.
 */
public record AppliedRuleAction(
        String type,
        String value,
        String message,
        BigDecimal adjustedAmount) {

    /**
     * Copy the flat {@code type} / {@code code}-shaped fields from a
     * {@link RuleResult} into the structured record the calculator wants.
     * The template's action <em>value</em> is carried on {@code code}
     * ({@code "20"} / {@code "FIXED:25"} / {@code "0"}) — that's the
     * convention baked into every {@code CoPaymentTemplates} entry today.
     */
    public static AppliedRuleAction from(RuleResult r) {
        return new AppliedRuleAction(r.getType(), r.getCode(), r.getMessage(), r.getAdjustedAmount());
    }

    public static List<AppliedRuleAction> fromAll(List<RuleResult> results) {
        if (results == null || results.isEmpty()) return List.of();
        return results.stream().map(AppliedRuleAction::from).toList();
    }
}
