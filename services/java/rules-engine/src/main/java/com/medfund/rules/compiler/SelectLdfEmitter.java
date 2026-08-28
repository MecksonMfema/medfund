package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * SELECT_LDF action emitter. Encodes the loss-development-factor selection
 * method into a single value token via {@link RuleAction#getValue()}:
 *
 * <ul>
 *   <li>{@code LDF_METHOD:volume} — volume-weighted (chainladder default).
 *       Each period's LDF is the ratio of cumulative losses summed across
 *       cohorts.</li>
 *   <li>{@code LDF_METHOD:simple} — simple average of per-cohort LDFs.
 *       Sensitive to outlier cohorts; useful when volume is uneven.</li>
 *   <li>{@code LDF_METHOD:5yr} — five-year weighted average. Volume-weighted
 *       across the most recent five accident cohorts; damps distant history.</li>
 * </ul>
 *
 * <p>The value is passed straight through to {@code TriangleFact.selectLdf(...)};
 * finance-service's actuarial pipeline reads {@code ldfMethod} off the fact
 * after the DRL fires and passes it as the job parameter to ai-service. Bad
 * or missing values fall back to {@code volume} so a typo in a tenant rule
 * cannot break the compute.
 *
 * <p>Rules with this action must live in the {@code ACTUARIAL} category —
 * see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The finance-service
 * {@code TriangleShapingService} focuses that agenda group explicitly per
 * IBNR / loss-triangle submit, so actuarial rules never fire during the
 * stage-7 tenant-rule sweep. The per-tenant KieContainer isolation
 * invariant from {@code bug_rules_engine_tenant_isolation} continues to
 * apply — the caller looks up the tenant KieContainer via
 * {@code TenantRuleEngine.loadRules(tenantId)}.
 */
@Component
public class SelectLdfEmitter implements ActionEmitter {

    /** Valid LDF selection method names. Any other value falls back to volume. */
    private static final Set<String> ALLOWED_METHODS = Set.of("volume", "simple", "5yr");

    @Override
    public String type() {
        return "SELECT_LDF";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        String message = action.getMessage() != null ? action.getMessage() : "";
        String raw     = action.getValue()   != null ? action.getValue().toString().trim() : "";

        String method;
        if (raw.startsWith("LDF_METHOD:")) {
            String candidate = raw.substring("LDF_METHOD:".length()).trim();
            method = ALLOWED_METHODS.contains(candidate) ? candidate : "volume";
        } else {
            method = "volume";
        }

        drl.append("    $triangle.selectLdf(")
           .append(quoted(method)).append(", ")
           .append(quoted(message))
           .append(");\n");
    }
}
