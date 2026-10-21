package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * SELECT_IFRS17_MODEL action emitter. Encodes the IFRS 17 measurement-model
 * selection into a single value token via {@link RuleAction#getValue()}:
 *
 * <pre>IFRS17_MODEL:&lt;model&gt;:&lt;coverage&gt;:&lt;fee&gt;:&lt;expense&gt;</pre>
 *
 * <ul>
 *   <li>{@code model} — {@code PAA} | {@code GMM} | {@code VFA}.</li>
 *   <li>{@code coverage} — {@code TIME} | {@code SUM_INSURED_TIME} |
 *       {@code SUM_AT_RISK_TIME} | {@code CLAIM_FREQUENCY_TIME}.</li>
 *   <li>{@code fee} — {@code FIXED_PCT} | {@code TIERED} | {@code NAV_LINKED}
 *       for VFA; the literal string {@code null} for non-VFA models.</li>
 *   <li>{@code expense} — {@code PL_ONLY} | {@code OCI_OPTION}.</li>
 * </ul>
 *
 * <p>The chosen values are passed straight through to
 * {@code IfrsPortfolioFact.applyModel(...)}; finance-service's IFRS 17
 * shaping pipeline reads {@code measurementModel} +
 * {@code coverageUnitPattern} + {@code variableFeePattern} +
 * {@code financeExpensePresentation} off the fact after the DRL fires and
 * uses them as the per-chunk compute inputs to ai-service. Bad or missing
 * values fall back to safe defaults ({@code PAA}, {@code TIME},
 * {@code null}, {@code PL_ONLY}) so a typo in a tenant rule cannot break
 * the compute.
 *
 * <p>Rules with this action must live in the {@code IFRS17_MODEL} category
 * — see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The finance-service
 * {@code Ifrs17ShapingService} focuses that agenda group explicitly per
 * IFRS 17 report submit, so IFRS 17 rules never fire during the stage-7
 * tenant-rule sweep. The per-tenant KieContainer isolation invariant from
 * {@code bug_rules_engine_tenant_isolation} continues to apply.
 */
@Component
public class SelectIfrs17ModelEmitter implements ActionEmitter {

    private static final Set<String> ALLOWED_MODELS =
            Set.of("PAA", "GMM", "VFA");
    private static final Set<String> ALLOWED_COVERAGE_PATTERNS =
            Set.of("TIME", "SUM_INSURED_TIME", "SUM_AT_RISK_TIME", "CLAIM_FREQUENCY_TIME");
    private static final Set<String> ALLOWED_FEE_PATTERNS =
            Set.of("FIXED_PCT", "TIERED", "NAV_LINKED");
    private static final Set<String> ALLOWED_EXPENSE_PRESENTATIONS =
            Set.of("PL_ONLY", "OCI_OPTION");

    @Override
    public String type() {
        return "SELECT_IFRS17_MODEL";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        String message = action.getMessage() != null ? action.getMessage() : "";
        String raw     = action.getValue()   != null ? action.getValue().toString().trim() : "";

        String model = "PAA";
        String coverage = "TIME";
        String fee = null;
        String expense = "PL_ONLY";

        if (raw.startsWith("IFRS17_MODEL:")) {
            String body = raw.substring("IFRS17_MODEL:".length());
            String[] parts = body.split(":", -1);
            if (parts.length >= 1 && ALLOWED_MODELS.contains(parts[0])) {
                model = parts[0];
            }
            if (parts.length >= 2 && ALLOWED_COVERAGE_PATTERNS.contains(parts[1])) {
                coverage = parts[1];
            }
            if (parts.length >= 3) {
                String feeToken = parts[2];
                if (!feeToken.isEmpty() && !"null".equalsIgnoreCase(feeToken)
                        && ALLOWED_FEE_PATTERNS.contains(feeToken)) {
                    fee = feeToken;
                }
            }
            if (parts.length >= 4 && ALLOWED_EXPENSE_PRESENTATIONS.contains(parts[3])) {
                expense = parts[3];
            }
        }

        drl.append("    $portfolio.applyModel(")
           .append(quoted(model)).append(", ")
           .append(quoted(coverage)).append(", ")
           .append(fee == null ? "null" : quoted(fee)).append(", ")
           .append(quoted(expense)).append(", ")
           .append(quoted(message))
           .append(");\n");
    }
}
