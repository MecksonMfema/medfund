package com.medfund.rules.template;

import com.medfund.rules.model.Condition;
import com.medfund.rules.model.ConditionGroup;
import com.medfund.rules.model.RuleAction;
import com.medfund.rules.model.RuleCategory;
import com.medfund.rules.model.RuleDefinition;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Tiny builder helpers shared by every {@link TemplateProvider} so each
 * provider stays a flat list of one-call template definitions and the
 * boilerplate ({@code id} generation, default priority, action wiring) lives
 * in one place.
 */
public final class TemplateBuilder {

    private TemplateBuilder() {}

    public static RuleDefinition rule(String name, RuleCategory category,
                                      int priority, ConditionGroup conditions, RuleAction action) {
        return rule(name, null, category, priority, conditions, action);
    }

    public static RuleDefinition rule(String name, String description, RuleCategory category,
                                      int priority, ConditionGroup conditions, RuleAction action) {
        RuleDefinition r = new RuleDefinition();
        r.setId(UUID.randomUUID().toString());
        r.setName(name);
        r.setDescription(description);
        r.setCategory(category.name());
        r.setPriority(priority);
        r.setEnabled(true);
        r.setStatus("ACTIVE");
        r.setVersion(1);
        r.setConditions(conditions);
        r.setAction(action);
        return r;
    }

    public static ConditionGroup all(Condition... conditions) {
        return group("AND", conditions);
    }

    public static ConditionGroup any(Condition... conditions) {
        return group("OR", conditions);
    }

    public static ConditionGroup group(String operator, Condition... conditions) {
        ConditionGroup group = new ConditionGroup();
        group.setOperator(operator);
        group.setItems(List.of(conditions));
        return group;
    }

    public static Condition cond(String field, String operator, Object value) {
        Condition c = new Condition();
        c.setField(field);
        c.setOperator(operator);
        c.setValue(value);
        return c;
    }

    // ── Convenience action factories ─────────────────────────────────────────

    public static RuleAction reject(String code, String message) {
        RuleAction a = new RuleAction();
        a.setType("REJECT");
        a.setRejectionCode(code);
        a.setMessage(message);
        return a;
    }

    public static RuleAction flagForReview(String message) {
        RuleAction a = new RuleAction();
        a.setType("FLAG_FOR_REVIEW");
        a.setMessage(message);
        return a;
    }

    public static RuleAction warn(String message) {
        RuleAction a = new RuleAction();
        a.setType("WARN");
        a.setMessage(message);
        return a;
    }

    public static RuleAction capToTariff(String message) {
        RuleAction a = new RuleAction();
        a.setType("CAP_TO_TARIFF");
        a.setMessage(message);
        return a;
    }

    public static RuleAction applyProrationStrategy(String strategyName, String message) {
        RuleAction a = new RuleAction();
        a.setType("APPLY_PRORATION_STRATEGY");
        a.setValue(strategyName);
        a.setMessage(message);
        return a;
    }

    public static RuleAction action(String type, Object value, String message) {
        RuleAction a = new RuleAction();
        a.setType(type);
        a.setValue(value);
        a.setMessage(message);
        return a;
    }

    public static RuleAction action(String type, String code, Object value, String message) {
        RuleAction a = new RuleAction();
        a.setType(type);
        a.setRejectionCode(code);
        a.setValue(value);
        a.setMessage(message);
        return a;
    }
}
