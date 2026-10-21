package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * CEDE_TO_TREATY action emitter. Kept in its own file because it carries
 * more encoding logic than the other emitters — the {@code value} field
 * of {@link RuleAction} multiplexes between two treaty shapes:
 *
 * <ul>
 *   <li>{@code PCT:<pct>} — proportional (Quota/Surplus Share). Emits
 *       DRL that computes {@code cededAmount = $claim.amount * pct/100}.</li>
 *   <li>{@code XOL:<retention>;<limit>;<layerId>} — excess-of-loss /
 *       stop-loss layer. Emits DRL that computes
 *       {@code cededAmount = max(0, min(limit, amount - retention))}
 *       and passes {@code layerId} through to the cession row.</li>
 * </ul>
 *
 * <p>{@link RuleAction#getRejectionCode() rejectionCode} carries the treaty id.
 *
 * <p>Rules with this action must live in the {@code REINSURANCE} category —
 * see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The reinsurance consumers
 * in finance-service focus that agenda group explicitly on each event, so
 * cession rules never fire during the stage-7 tenant-rule sweep. The
 * per-tenant KieContainer isolation invariant from
 * {@code bug_rules_engine_tenant_isolation} continues to apply — the
 * consumer looks up the caller's tenant KieContainer via
 * {@code TenantRuleEngine.loadRules(tenantId)}, never the shared session.
 */
@Component
public class CedeToTreatyEmitter implements ActionEmitter {

    @Override
    public String type() {
        return "CEDE_TO_TREATY";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        String treatyId = action.getRejectionCode() != null ? action.getRejectionCode() : "";
        String message  = action.getMessage()       != null ? action.getMessage()       : "";
        String raw      = action.getValue()         != null ? action.getValue().toString().trim() : "PCT:0";

        String layerId = "";
        String amountExpr;

        if (raw.startsWith("XOL:")) {
            String[] parts = raw.substring(4).split(";", 3);
            String retention = parts.length > 0 ? sanitizeDecimal(parts[0]) : "0";
            String limit     = parts.length > 1 ? sanitizeDecimal(parts[1]) : "0";
            if (parts.length > 2) layerId = parts[2].trim();
            amountExpr = "$claim.getAmount()"
                       + ".subtract(new java.math.BigDecimal(\"" + escape(retention) + "\"))"
                       + ".min(new java.math.BigDecimal(\"" + escape(limit) + "\"))"
                       + ".max(java.math.BigDecimal.ZERO)";
        } else {
            String pct = raw.startsWith("PCT:") ? raw.substring(4) : raw;
            amountExpr = "$claim.getAmount()"
                       + ".multiply(new java.math.BigDecimal(\"" + escape(sanitizeDecimal(pct)) + "\"))"
                       + ".movePointLeft(2)";
        }

        drl.append("    $claim.addCession(")
           .append(quoted(treatyId)).append(", ")
           .append(amountExpr).append(", ")
           .append(quoted(layerId)).append(", ")
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
