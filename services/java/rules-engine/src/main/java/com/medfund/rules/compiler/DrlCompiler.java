package com.medfund.rules.compiler;

import com.medfund.rules.model.Condition;
import com.medfund.rules.model.ConditionGroup;
import com.medfund.rules.model.Operator;
import com.medfund.rules.model.RuleDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compiles JSON {@link RuleDefinition} objects into Drools DRL.
 *
 * <p>Two extension points keep the compiler open-closed as new domains
 * are added:
 * <ul>
 *   <li><b>Fact registry</b> — {@link #FACT_MAPPINGS} maps a field-prefix
 *       (e.g. {@code claim.}) to a Drools variable + class name. Adding a
 *       new fact: append one entry here and add the {@code import} to
 *       {@link #IMPORTS}.</li>
 *   <li><b>Action emitters</b> — every {@link com.medfund.rules.model.ActionType}
 *       has a Spring-bean {@link ActionEmitter} that writes its DRL
 *       consequence. Adding a new action: drop a new emitter implementation
 *       and {@code DrlCompiler} picks it up via the injected list — no
 *       edits here.</li>
 * </ul>
 */
@Component
public class DrlCompiler {

    private static final String IMPORTS = String.join("",
            "import com.medfund.rules.fact.ClaimFact;\n",
            "import com.medfund.rules.fact.ClaimDetailFact;\n",
            "import com.medfund.rules.fact.MemberFact;\n",
            "import com.medfund.rules.fact.DependantFact;\n",
            "import com.medfund.rules.fact.ProviderFact;\n",
            "import com.medfund.rules.fact.FamilyFact;\n",
            "import com.medfund.rules.fact.ContributionFact;\n",
            "import com.medfund.rules.fact.MemberLifecycleFact;\n",
            "import com.medfund.rules.fact.PaymentRunFact;\n",
            "import com.medfund.rules.fact.SchemeChangeContext;\n",
            "import com.medfund.rules.fact.TimeFact;\n",
            "import java.math.BigDecimal;\n");

    /**
     * Categories whose rules are gated behind a Drools agenda-group with the same
     * name. They only fire when the caller explicitly {@code setFocus}es on the
     * group — this is how {@code ProrationService} invokes BENEFIT_PRORATION
     * rules in stage 3 without them also firing during the stage-7 tenant-rules
     * sweep. All other categories stay in MAIN and fire by default.
     */
    private static final Set<String> AGENDA_GATED_CATEGORIES = Set.of("BENEFIT_PRORATION");

    private static final Map<String, FactMapping> FACT_MAPPINGS;
    static {
        Map<String, FactMapping> m = new LinkedHashMap<>();
        m.put("claim",        new FactMapping("$claim",        "ClaimFact"));
        m.put("claimDetail",  new FactMapping("$detail",       "ClaimDetailFact"));
        m.put("member",       new FactMapping("$member",       "MemberFact"));
        m.put("dependant",    new FactMapping("$dependant",    "DependantFact"));
        m.put("provider",     new FactMapping("$provider",     "ProviderFact"));
        m.put("family",       new FactMapping("$family",       "FamilyFact"));
        m.put("contribution", new FactMapping("$contribution", "ContributionFact"));
        m.put("lifecycle",    new FactMapping("$lifecycle",    "MemberLifecycleFact"));
        m.put("paymentRun",   new FactMapping("$paymentRun",   "PaymentRunFact"));
        m.put("schemeChange", new FactMapping("$schemeChange", "SchemeChangeContext"));
        m.put("time",         new FactMapping("$time",         "TimeFact"));
        FACT_MAPPINGS = Map.copyOf(m);
    }

    /** Action-type → emitter, populated from every {@link ActionEmitter} bean. */
    private final Map<String, ActionEmitter> emittersByType;

    public DrlCompiler(List<ActionEmitter> emitters) {
        Map<String, ActionEmitter> registry = new LinkedHashMap<>();
        for (ActionEmitter e : emitters) {
            registry.put(e.type().toUpperCase(), e);
        }
        this.emittersByType = Map.copyOf(registry);
    }

    /** Compile a single rule into a complete DRL string (with imports). */
    public String compile(RuleDefinition rule) {
        StringBuilder drl = new StringBuilder();
        drl.append(IMPORTS).append("\n");
        appendRule(drl, rule);
        return drl.toString();
    }

    /** Compile multiple rules into one DRL string with shared imports. */
    public String compileAll(List<RuleDefinition> rules) {
        StringBuilder drl = new StringBuilder();
        drl.append(IMPORTS).append("\n");
        for (RuleDefinition rule : rules) {
            if (rule.isEnabled()) {
                appendRule(drl, rule);
                drl.append("\n");
            }
        }
        return drl.toString();
    }

    // ── Rule body ────────────────────────────────────────────────────────────

    private void appendRule(StringBuilder drl, RuleDefinition rule) {
        drl.append("rule \"").append(escapeQuotes(rule.getName())).append("\"\n");
        drl.append("  salience ").append(rule.getPriority()).append("\n");
        String cat = rule.getCategory();
        if (cat != null && AGENDA_GATED_CATEGORIES.contains(cat.toUpperCase())) {
            drl.append("  agenda-group \"").append(cat.toUpperCase()).append("\"\n");
        }
        drl.append("  when\n");
        appendConditions(drl, rule.getConditions(),
                rule.getAction() == null ? null : rule.getAction().getType());
        drl.append("  then\n");
        appendAction(drl, rule);
        drl.append("end\n");
    }

    /**
     * Bind every fact referenced by a condition, plus the fact each action
     * needs to mutate. Auto-binding the action's target fact is what lets
     * a rule with no conditions on, say, {@code lifecycle.*} still call
     * {@code $lifecycle.terminate(...)} in its consequence.
     */
    private void appendConditions(StringBuilder drl, ConditionGroup group, String actionType) {
        Map<String, List<Condition>> byFact = new LinkedHashMap<>();
        if (group != null && group.getItems() != null) {
            for (Condition c : group.getItems()) {
                byFact.computeIfAbsent(getFactType(c.getField()), k -> new ArrayList<>()).add(c);
            }
        }

        // Bind the fact the action will mutate so DRL compiles even when
        // a rule's conditions don't reference it.
        String actionFact = factForAction(actionType);
        if (actionFact != null && !byFact.containsKey(actionFact)) {
            byFact.put(actionFact, new ArrayList<>());
        }
        // Defensive default: keep $claim bound for legacy rules that don't
        // declare an action but still expect $claim available.
        if (byFact.isEmpty()) {
            byFact.put("claim", new ArrayList<>());
        }

        boolean isOr = group != null && "OR".equalsIgnoreCase(group.getOperator());

        for (Map.Entry<String, List<Condition>> entry : byFact.entrySet()) {
            FactMapping mapping = FACT_MAPPINGS.get(entry.getKey());
            if (mapping == null) continue;

            String constraints = entry.getValue().stream()
                    .map(this::toConstraint)
                    .collect(Collectors.joining(isOr ? " || " : ", "));

            drl.append("    ").append(mapping.variable).append(" : ")
               .append(mapping.className).append("(").append(constraints).append(")\n");
        }
    }

    /** Which fact does a given action type mutate? Drives auto-binding above. */
    private String factForAction(String type) {
        if (type == null) return null;
        return switch (type.toUpperCase()) {
            case "REJECT", "FLAG_FOR_REVIEW", "WARN", "APPLY_COPAY",
                 "APPLY_PRORATION_STRATEGY"                                   -> "claim";
            case "CAP_TO_TARIFF"                                              -> "claimDetail";
            case "SET_AGE_GROUP", "AUTO_RENEW", "TERMINATE_MEMBERSHIP",
                 "REQUIRE_UNDERWRITING"                                       -> "lifecycle";
            case "SET_PREMIUM", "APPLY_LATE_FEE", "APPLY_LOADED_PREMIUM"      -> "contribution";
            case "SCHEDULE_PAYMENT_RUN", "WITHHOLD_PAYMENT", "MATCH_RECORDS"  -> "paymentRun";
            default                                                            -> null;
        };
    }

    private String toConstraint(Condition c) {
        return getProperty(c.getField()) + " " + mapOperator(c.getOperator()) + " " + formatValue(c.getValue());
    }

    private void appendAction(StringBuilder drl, RuleDefinition rule) {
        if (rule.getAction() == null || rule.getAction().getType() == null) return;
        ActionEmitter emitter = emittersByType.get(rule.getAction().getType().toUpperCase());
        if (emitter != null) {
            emitter.emit(drl, rule.getAction());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String getFactType(String field) {
        int dot = field.indexOf('.');
        return dot > 0 ? field.substring(0, dot) : field;
    }

    private String getProperty(String field) {
        int dot = field.indexOf('.');
        return dot > 0 ? field.substring(dot + 1) : field;
    }

    private String mapOperator(String operator) {
        if (operator == null) return "==";
        try { return Operator.valueOf(operator.toUpperCase()).toDrl(); }
        catch (IllegalArgumentException e) { return operator; }
    }

    private String formatValue(Object value) {
        if (value == null) return "null";
        String str = value.toString();
        if ("true".equalsIgnoreCase(str) || "false".equalsIgnoreCase(str)) return str.toLowerCase();
        if (isNumeric(str)) return str;
        return "\"" + escapeQuotes(str) + "\"";
    }

    private boolean isNumeric(String s) {
        try { Double.parseDouble(s); return true; } catch (NumberFormatException e) { return false; }
    }

    private String escapeQuotes(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class FactMapping {
        final String variable;
        final String className;
        FactMapping(String variable, String className) {
            this.variable = variable;
            this.className = className;
        }
    }
}
