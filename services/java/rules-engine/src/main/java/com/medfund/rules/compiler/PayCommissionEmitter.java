package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * PAY_COMMISSION action emitter. Multiplexes between two encodings via
 * {@link RuleAction#getValue()}:
 *
 * <ul>
 *   <li>{@code RATE_CARD:<uuid>} — look up the rate card server-side and pay
 *       {@code contribution.premiumAmount * card.baseRatePct / 100}. The
 *       rate-card fetch happens in {@code CommissionCalcService}; the DRL
 *       only records intent + carries the rate-card id through with a
 *       zero-amount marker.</li>
 *   <li>{@code KICKER:<pct-bp>[:<reason>]} — additive kicker in basis points.
 *       The consumer sums kickers on top of the rate-card base amount.
 *       Negative bp values are discounts. Amount is computed at DRL fire
 *       time as {@code premiumAmount * bp * 10^-4}.</li>
 * </ul>
 *
 * <p>{@link RuleAction#getRejectionCode() rejectionCode} carries the producer
 * id (UUID string), or empty string to defer to the member's currently-assigned
 * producer (looked up on the consumer side via
 * {@code member_producer_assignment}).
 *
 * <p>Rules with this action must live in the {@code COMMISSION} category —
 * see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The commission consumer
 * in finance-service focuses that agenda group explicitly on each
 * {@code medfund.contributions.paid} event, so kicker rules never fire
 * during the stage-7 tenant-rule sweep. The per-tenant KieContainer
 * isolation invariant from {@code bug_rules_engine_tenant_isolation}
 * continues to apply — the consumer looks up the caller's tenant
 * KieContainer via {@code TenantRuleEngine.loadRules(tenantId)},
 * never the shared session.
 */
@Component
public class PayCommissionEmitter implements ActionEmitter {

    @Override
    public String type() {
        return "PAY_COMMISSION";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        String producerId = action.getRejectionCode() != null ? action.getRejectionCode() : "";
        String message    = action.getMessage()       != null ? action.getMessage()       : "";
        String raw        = action.getValue()         != null ? action.getValue().toString().trim() : "";

        String rateCardId = "";
        String amountExpr;

        if (raw.startsWith("KICKER:")) {
            String[] parts = raw.substring(7).split(":", 2);
            String bp = parts.length > 0 ? sanitizeDecimal(parts[0]) : "0";
            amountExpr = "$contribution.getPremiumAmount()"
                       + ".multiply(new java.math.BigDecimal(\"" + escape(bp) + "\"))"
                       + ".movePointLeft(4)";
        } else if (raw.startsWith("RATE_CARD:")) {
            rateCardId = raw.substring(10).trim();
            // Zero amount: consumer resolves via rate-card lookup — DRL just
            // records intent + carries the rate-card id through.
            amountExpr = "java.math.BigDecimal.ZERO";
        } else {
            amountExpr = "java.math.BigDecimal.ZERO";
        }

        drl.append("    $contribution.addCommission(")
           .append(quoted(producerId)).append(", ")
           .append(amountExpr).append(", ")
           .append(quoted(rateCardId)).append(", ")
           .append(quoted(message))
           .append(");\n");
    }

    /**
     * Defensive numeric parse — bad decimals fall back to "0" so a typo in
     * the tenant rule cannot break DRL compilation.
     */
    private String sanitizeDecimal(String s) {
        if (s == null) return "0";
        try {
            return new java.math.BigDecimal(s.trim()).toPlainString();
        } catch (NumberFormatException e) {
            return "0";
        }
    }
}
