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
    /** Percentage / fixed co-pays and out-of-pocket caps. */
    CO_PAYMENT,
    PRE_AUTHORIZATION,
    TARIFF_PRICING,
    CLINICAL_VALIDATION,

    // ── Finance ──────────────────────────────────────────────────────────────
    /** Provider payment runs — schedule, advance payments, holdbacks, CTC payments. */
    PROVIDER_PAYMENT,
    /** Reconciliation matching rules between claims, payments, and bank records. */
    RECONCILIATION
}
