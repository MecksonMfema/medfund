package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;

/**
 * Pluggable action-to-DRL translator. Each {@link com.medfund.rules.model.ActionType}
 * gets its own emitter implementation that appends the corresponding fact-mutation
 * call to the rule's {@code then} block.
 *
 * <p>Adding a new action is two steps:
 * <ol>
 *   <li>Add the value to {@link com.medfund.rules.model.ActionType}.</li>
 *   <li>Drop a new {@code @Component} implementation here. {@code DrlCompiler}
 *       picks it up automatically via the injected list — no edits.</li>
 * </ol>
 */
public interface ActionEmitter {

    /** The {@link com.medfund.rules.model.ActionType#name()} this emitter handles. */
    String type();

    /**
     * Append the consequence DRL for {@code action} to {@code drl}. Implementations
     * should write valid Java statements ending in semicolons; the surrounding
     * {@code then ... end} structure is added by {@link DrlCompiler}.
     */
    void emit(StringBuilder drl, RuleAction action);

    // ── Helpers shared by implementations ────────────────────────────────────

    default String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    default String quoted(String s) {
        return "\"" + escape(s) + "\"";
    }
}
