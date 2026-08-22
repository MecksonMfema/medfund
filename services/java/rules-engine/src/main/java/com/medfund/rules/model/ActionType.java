package com.medfund.rules.model;

/**
 * Types of actions a rule can produce when its conditions are met.
 *
 * <p>Each action is paired with an {@code ActionEmitter} in the DRL compiler
 * that translates it into the corresponding fact-mutation call (e.g. REJECT
 * becomes {@code $claim.addRejection(code, message)}). Adding a new action
 * type is two steps: add the enum value here, register a new emitter bean
 * — no edits to {@code DrlCompiler} itself.
 */
public enum ActionType {
    // ── Claims pipeline outputs ──────────────────────────────────────────────
    REJECT,
    FLAG_FOR_REVIEW,
    WARN,
    CAP_TO_TARIFF,

    // ── Cost-sharing ─────────────────────────────────────────────────────────
    /** Apply a percentage or fixed-amount co-pay to a claim line. */
    APPLY_COPAY,

    // ── Benefit-limit proration ──────────────────────────────────────────────
    /**
     * Route a scheme-change proration decision to a specific
     * {@code ProrationStrategy} (e.g. CALENDAR, DELTA_CREDIT, RESET). The
     * strategy name is passed via {@code RuleAction.value}. Consumed by
     * {@code ProrationService} after firing rules in agenda-group BENEFIT_PRORATION.
     */
    APPLY_PRORATION_STRATEGY,

    // ── Member lifecycle outputs ─────────────────────────────────────────────
    /** Assign an age-group label (e.g. "CHILD", "ADULT", "SENIOR") to a member. */
    SET_AGE_GROUP,
    /** Auto-renew the member's scheme. */
    AUTO_RENEW,
    /** Auto-terminate membership (e.g. non-payment threshold reached). */
    TERMINATE_MEMBERSHIP,
    /** Flag enrollment for human underwriter review with a level (LOW/MED/HIGH). */
    REQUIRE_UNDERWRITING,
    /** Apply a multiplier to the standard premium for an under-writing finding. */
    APPLY_LOADED_PREMIUM,

    // ── Contributions outputs ────────────────────────────────────────────────
    /** Set the contribution premium amount for the period. */
    SET_PREMIUM,
    /** Apply a late-payment fee. */
    APPLY_LATE_FEE,

    // ── Finance outputs ──────────────────────────────────────────────────────
    /** Schedule (or block) a payment run for a provider. */
    SCHEDULE_PAYMENT_RUN,
    /** Withhold a percentage of a provider payment pending review. */
    WITHHOLD_PAYMENT,
    /** Mark a candidate match in reconciliation. */
    MATCH_RECORDS,

    // ── Reinsurance outputs ──────────────────────────────────────────────────
    /**
     * Cede an adjudicated claim or paid contribution to a reinsurance treaty.
     * Populates a CEDE_TO_TREATY {@code RuleResult} on {@code ClaimFact}
     * carrying treatyId + cededAmount + optional layerId. Consumed by the
     * reinsurance loss/premium-cession consumers in finance-service.
     *
     * <p>Action fields (see {@code RuleAction}):
     * <ul>
     *   <li>{@code rejectionCode} — treaty id (UUID string)</li>
     *   <li>{@code value} — {@code "PCT:<pct>"} for proportional treaties
     *       (Quota/Surplus Share), or {@code "XOL:<retention>;<limit>;<layerId>"}
     *       for excess-of-loss / stop-loss layer bands.</li>
     *   <li>{@code message} — human-readable audit note.</li>
     * </ul>
     */
    CEDE_TO_TREATY
}
