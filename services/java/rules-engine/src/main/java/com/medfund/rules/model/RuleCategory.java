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
    REINSURANCE,

    // ── Commission ───────────────────────────────────────────────────────────
    /**
     * Producer commission calculation. Agenda-gated — the commission consumer
     * in finance-service focuses this group per {@code medfund.contributions.paid}
     * event, so commission rules never fire during the stage-7 tenant sweep.
     * The base rate is looked up in {@code commission_rate_card}; rules in
     * this category encode conditional kickers (tier bonuses, promo periods,
     * sliding-scale overrides, waivers) on top of that base.
     */
    COMMISSION,

    // ── Premium earning ──────────────────────────────────────────────────────
    /**
     * Policy-bind earning-method selection. Agenda-gated — the premium
     * consumer in contributions-service focuses this group per
     * {@code medfund.user.policy-issued} event and per nightly period-close
     * pass, so earning rules never fire during the stage-7 tenant sweep.
     * The default earning method is {@code DAILY_LINEAR}; rules in this
     * category route a policy to a specific method
     * ({@code MONTHLY_24THS}, {@code LINEAR_WITH_LOADING:<pct>}) based on
     * the {@code PremiumFact} shape (line, product code, coverage window).
     */
    PREMIUM_EARNING,

    // ── Actuarial ────────────────────────────────────────────────────────────
    /**
     * Loss-development-factor selection for IBNR / loss-triangle reports.
     * Agenda-gated — finance-service's {@code TriangleShapingService} focuses
     * this group before building the shaped triangle so actuarial rules
     * never fire during the stage-7 tenant sweep. The default LDF method
     * is {@code volume}; rules in this category route a triangle to a
     * specific method ({@code simple}, {@code 5yr}) based on the
     * {@code TriangleFact} shape (line, currency, period).
     */
    ACTUARIAL,

    // ── IFRS 17 ──────────────────────────────────────────────────────────────
    /**
     * IFRS 17 measurement-model selection (PAA / GMM / VFA) per portfolio +
     * cohort year. Agenda-gated — finance-service's {@code Ifrs17ShapingService}
     * focuses this group per portfolio at report-submit time so IFRS 17 rules
     * never fire during the stage-7 tenant sweep. Rules in this category
     * mutate {@code IfrsPortfolioFact} — measurementModel, coverageUnitPattern,
     * variableFeePattern, financeExpensePresentation — via
     * {@code SELECT_IFRS17_MODEL} actions. Each tenant is seeded with 8
     * industry-default rules on provisioning (see the
     * {@code seed_ifrs17_model_default_rules} migration).
     */
    IFRS17_MODEL,

    // ── Regulatory parameter override ────────────────────────────────────────
    /**
     * Tenant escape hatch for numeric parameters in regulator reports (IPEC
     * solvency, CMS solvency + cost-ratio, NAIC Schedule P + F provision
     * percentages, and every future regulator that reads YAML defaults from
     * {@code shared/src/main/resources/regulatory-defaults/}). Agenda-gated —
     * finance-service's {@code RegulatoryParameterResolver} focuses this group
     * per parameter lookup so overrides never fire during the stage-7 tenant
     * sweep. Rules in this category mutate {@code RegulatoryParameterFact}'s
     * {@code parameterValue} slot via {@code SET_REGULATORY_PARAMETER} actions.
     * When no matching rule fires the resolver falls back to the bundled YAML
     * default; both missing → fail-loud
     * {@code RegulatoryParameterMissingException}. See Phase 16 §Phase-15 REG15.
     */
    REGULATORY_PARAMETER,

    // ── PMB classification (Phase 17 §B REG7) ────────────────────────────────
    /**
     * CMS Prescribed Minimum Benefit classification. Fires at claim
     * adjudication time (from {@code PmbClassificationExecutor} in
     * claims-service, after the standard rule sweep) and at backfill time
     * (from {@code PmbBackfillJob}). Agenda-gated — the classifier explicitly
     * focuses {@code PMB_CLASSIFICATION} so these rules never fire during the
     * stage-7 tenant-rule sweep alongside eligibility / co-pay / tariff
     * evaluations. Rules mutate {@code PmbClassificationFact} via
     * {@code SET_PMB_CLASSIFICATION} actions carrying the matched CMS PMB
     * condition code. When no rule matches the classifier records
     * {@code is_pmb = FALSE} on the claim row.
     */
    PMB_CLASSIFICATION
}
