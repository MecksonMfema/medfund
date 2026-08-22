package com.medfund.rules.model;

/**
 * Categories for classifying tenant rules.
 *
 * <p>Each category corresponds to a stage in the insurance lifecycle. Adding
 * a new category is the first half of supporting a new policy domain — the
 * second half is registering a {@code TemplateProvider} bean for the category
 * so the New Rule modal has starting points to offer. The compiler treats
 * categories as opaque strings, so adding a new one here is non-breaking.
 */
public enum RuleCategory {
    // ── Member lifecycle ─────────────────────────────────────────────────────
    /** Onboarding, suspension, termination, reinstatement, scheme/group transfers. */
    MEMBER_LIFECYCLE,
    /** How tenant slices age bands (child/adult/senior boundaries vary by tenant). */
    AGE_GROUP,
    /** Risk assessment at enrollment — pre-existing conditions, exam thresholds, loadings. */
    UNDERWRITING,

    // ── Contributions ────────────────────────────────────────────────────────
    /** Premium calculation: scheme × age × dependant × region. */
    CONTRIBUTION_PRICING,
    /** Billing cycle, late fees, payment plans, currency transfers, back-dated adjustments. */
    CONTRIBUTION_BILLING,

    // ── Claims pipeline ──────────────────────────────────────────────────────
    ELIGIBILITY,
    WAITING_PERIOD,
    BENEFIT_LIMIT,
    /**
     * How a member's per-benefit annual limit is prorated when they change scheme
     * mid-year. Rules in this category are agenda-gated — they only fire when
     * {@code ProrationService} sets focus during stage 3 of adjudication, not
     * during the normal stage-7 tenant-rule sweep.
     */
    BENEFIT_PRORATION,
    /** Percentage / fixed co-pays and out-of-pocket caps. */
    CO_PAYMENT,
    PRE_AUTHORIZATION,
    TARIFF_PRICING,
    /**
     * Per-line adjustments driven by tariff modifiers (bilateral, assistant-surgeon,
     * after-hours, multi-procedure, paediatric/geriatric loading, …). Rules in this
     * category are agenda-gated — they only fire when the caller sets focus via
     * {@code RuleEvaluationService.evaluateModifiers(...)} on the adjudication path,
     * not during the stage-7 tenant-rule sweep. Rules read modifier codes off
     * {@code ClaimDetailFact.modifiers} and mutate {@code ClaimDetailFact.approvedAmount}
     * in place; the caller reads the mutated fact after the fire. Percentages are
     * tenant policy (rule content), never engine code.
     */
    MODIFIER_ADJUSTMENT,
    CLINICAL_VALIDATION,

    // ── Finance ──────────────────────────────────────────────────────────────
    /** Provider payment runs — schedule, advance payments, holdbacks, CTC payments. */
    PROVIDER_PAYMENT,
    /** Reconciliation matching rules between claims, payments, and bank records. */
    RECONCILIATION,

    // ── Reinsurance ──────────────────────────────────────────────────────────
    /**
     * Cession rules — decide when and how much of a claim (or contribution) to
     * cede to a reinsurance treaty. Rules in this category are agenda-gated:
     * they only fire when the reinsurance consumer explicitly focuses the
     * REINSURANCE group, never during the stage-7 tenant-rule sweep.
     */
    REINSURANCE
}
