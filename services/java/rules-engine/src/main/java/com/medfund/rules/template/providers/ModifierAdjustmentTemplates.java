package com.medfund.rules.template.providers;

import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;
import com.medfund.rules.template.TemplateProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TemplateProvider stub for {@link RuleCategory#MODIFIER_ADJUSTMENT}. Ships
 * with an empty template list — modifier rules mutate
 * {@code ClaimDetailFact.approvedAmount} on a per-line fact, which the
 * platform's condition/action DSL (see {@code TemplateBuilder} — {@code cond}
 * on a dotted field, {@code action} of {@code APPLY_COPAY} / {@code FLAG} /
 * etc.) does not yet express. Rather than ship a template that quietly
 * targets the wrong fact or fires no-ops, we register the category so it
 * appears in the New Rule modal but leave the starting-point library empty
 * until the DSL grows a {@code detail.*} field selector and a
 * {@code SET_APPROVED_AMOUNT} action verb.
 *
 * <p>In the interim tenants author modifier rules via the raw DRL escape
 * hatch. Reference DRL sketch (bilateral 50% on secondary procedures):
 *
 * <pre>{@code
 * rule "Modifier BIL — halve secondary procedure amounts"
 *     agenda-group "MODIFIER_ADJUSTMENT"
 *     when
 *         $d : ClaimDetailFact(modifiers contains "BIL", procedureRank > 1)
 *     then
 *         $d.setApprovedAmount($d.getBilledAmount().multiply(new java.math.BigDecimal("0.5")));
 * end
 * }</pre>
 *
 * Percentages, modifier codes, and rank thresholds are tenant policy and
 * belong in rule content — never here.
 */
@Component
public class ModifierAdjustmentTemplates implements TemplateProvider {

    @Override
    public RuleCategory category() {
        return RuleCategory.MODIFIER_ADJUSTMENT;
    }

    @Override
    public List<RuleDefinition> templates() {
        return List.of();
    }
}
