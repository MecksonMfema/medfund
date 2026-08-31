package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * SET_REGULATORY_PARAMETER action emitter. Encodes the numeric override into
 * a single value token via {@link RuleAction#getValue()}:
 *
 * <pre>PARAMETER_VALUE:&lt;decimal&gt;</pre>
 *
 * <p>The decimal is parsed as {@link BigDecimal}; the parsed value + rule
 * message are passed straight through to
 * {@code RegulatoryParameterFact.setParameterValue(...)}. Bad or missing
 * values emit a fact mutation with {@code null} so the caller's fallback
 * chain (rules-engine → bundled YAML default → fail-loud) still runs — a
 * typo in a tenant rule cannot silently write a zero to the report cell.
 *
 * <p>Rules with this action must live in the {@code REGULATORY_PARAMETER}
 * category — see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The
 * finance-service {@code RegulatoryParameterResolver} focuses that agenda
 * group explicitly per parameter lookup so regulatory-parameter rules never
 * fire during the stage-7 tenant-rule sweep. The per-tenant KieContainer
 * isolation invariant from {@code bug_rules_engine_tenant_isolation}
 * continues to apply.
 */
@Component
public class SetRegulatoryParameterEmitter implements ActionEmitter {

    static final String VALUE_PREFIX = "PARAMETER_VALUE:";

    @Override
    public String type() {
        return "SET_REGULATORY_PARAMETER";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        String message = action.getMessage() != null ? action.getMessage() : "";
        String raw     = action.getValue()   != null ? action.getValue().toString().trim() : "";

        String literal = "null";
        if (raw.startsWith(VALUE_PREFIX)) {
            String candidate = raw.substring(VALUE_PREFIX.length()).trim();
            if (!candidate.isEmpty()) {
                try {
                    // Round-trip through BigDecimal so a value like "0.30" or
                    // "-1.5e2" reaches the DRL as a canonical decimal literal
                    // Drools + BigDecimal-ctor both accept.
                    literal = "new java.math.BigDecimal(\""
                            + new BigDecimal(candidate).toPlainString() + "\")";
                } catch (NumberFormatException e) {
                    // Fall through — literal stays null so the caller's
                    // fallback chain picks the bundled YAML default.
                    literal = "null";
                }
            }
        }

        drl.append("    $regulatoryParameter.setParameterValue(")
           .append(literal).append(", ")
           .append(quoted(message))
           .append(");\n");
    }
}
