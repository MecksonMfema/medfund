package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * SET_PMB_CLASSIFICATION action emitter. Encodes the matched CMS PMB
 * condition code into a single value token via {@link RuleAction#getValue()}:
 *
 * <pre>PMB_CONDITION_CODE:&lt;code&gt;</pre>
 *
 * <p>The parsed code + rule message are passed straight through to
 * {@code PmbClassificationFact.setPmbClassification(...)}. Bad or missing
 * values emit the mutation call with {@code null} so the fact's own null-check
 * short-circuits — a typo in a tenant rule cannot silently mark a claim PMB
 * without a code (the claim row would then carry {@code is_pmb=TRUE} but a
 * NULL {@code pmb_condition_code}, breaking the PMB spend aggregation).
 *
 * <p>Rules with this action must live in the {@code PMB_CLASSIFICATION}
 * category — see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}. The
 * claims-service {@code RulesEnginePmbClassifier} focuses that agenda group
 * explicitly per (claim, diagnosis, procedure) probe so PMB rules never fire
 * during the stage-7 tenant-rule sweep.
 */
@Component
public class SetPmbClassificationEmitter implements ActionEmitter {

    static final String VALUE_PREFIX = "PMB_CONDITION_CODE:";

    @Override
    public String type() {
        return "SET_PMB_CLASSIFICATION";
    }

    @Override
    public void emit(StringBuilder drl, RuleAction action) {
        String message = action.getMessage() != null ? action.getMessage() : "";
        String raw     = action.getValue()   != null ? action.getValue().toString().trim() : "";

        String literal = "null";
        if (raw.startsWith(VALUE_PREFIX)) {
            String candidate = raw.substring(VALUE_PREFIX.length()).trim();
            if (!candidate.isEmpty()) {
                literal = quoted(candidate);
            }
        }

        drl.append("    $pmbClassification.setPmbClassification(")
           .append(literal).append(", ")
           .append(quoted(message))
           .append(");\n");
    }
}
