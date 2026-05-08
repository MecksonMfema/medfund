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
    MATCH_RECORDS
}
