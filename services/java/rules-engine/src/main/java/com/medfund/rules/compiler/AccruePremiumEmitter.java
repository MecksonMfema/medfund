package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * ACCRUE_PREMIUM action emitter. Encodes the earning method + optional
 * front-loaded percent into a single value token via
 * {@link RuleAction#getValue()}:
 *
 * <ul>
 *   <li>{@code EARNING_METHOD:DAILY_LINEAR} — default 1/365ths strip. Every
 *       {@code earning_schedule} row for the policy earns pro rata by day.</li>
 *   <li>{@code EARNING_METHOD:MONTHLY_24THS} — IPEC-style 24ths for motor.
 *       Each month accrues 1/24 of the premium in month 1, then 1/12 per
 *       month thereafter with the tail spread across the final period.</li>
 *   <li>{@code EARNING_METHOD:LINEAR_WITH_LOADING:<pct>} — front-loads
 *       {@code pct}% of the premium into the first period and strips the
 *       remainder linearly across the rest.</li>
 * </ul>
 *
 * <p>The value is passed straight through to {@code PremiumFact.accrue(...)};
 * the executor in contributions-service reads {@code earningMethod} +
 * {@code loadingPercent} off the fact after the DRL fires to decide which
 * strip strategy to apply. Bad or missing values fall back to
 * {@code DAILY_LINEAR} so a typo in a tenant rule cannot break the executor.
 *
 * <p>Rules with this action must live in the {@code PREMIUM_EARNING} category
 * — see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The premium consumer
 * in contributions-service focuses that agenda group explicitly per
 * {@code medfund.user.policy-issued} event, so earning rules never fire
 * during the stage-7 tenant-rule sweep. The per-tenant KieContainer
 * isolation invariant from {@code bug_rules_engine_tenant_isolation}
 * continues to apply — the consumer looks up the caller's tenant
 * KieContainer via {@code TenantRuleEngine.loadRules(tenantId)}.
 */
@Component
public class AccruePremiumEmitter implements ActionEmitter {

    @Override
    public String type() {
        return "ACCRUE_PREMIUM";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        String message = action.getMessage() != null ? action.getMessage() : "";
        String raw     = action.getValue()   != null ? action.getValue().toString().trim() : "";

        String method;
        String loadingExpr;

        if (raw.startsWith("EARNING_METHOD:")) {
            String rest = raw.substring("EARNING_METHOD:".length());
            String[] parts = rest.split(":", 2);
            method = parts[0].trim();
            if (method.isEmpty()) {
                method = "DAILY_LINEAR";
            }
            if (parts.length == 2) {
                String pct = sanitizeDecimal(parts[1]);
                loadingExpr = "new java.math.BigDecimal(\"" + escape(pct) + "\")";
            } else {
                loadingExpr = "null";
            }
        } else {
            // Any other shape — including empty or malformed values — falls
            // back to the safe default so a typo can't break DRL compilation.
            method = "DAILY_LINEAR";
            loadingExpr = "null";
        }

        drl.append("    $premium.accrue(")
           .append(quoted(method)).append(", ")
           .append(loadingExpr).append(", ")
           .append(quoted(message))
           .append(");\n");
    }

    /**
     * Defensive numeric parse — bad decimals fall back to "0" so a typo in
     * a tenant rule cannot break DRL compilation.
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
