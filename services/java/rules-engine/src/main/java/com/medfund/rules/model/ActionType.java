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
    CEDE_TO_TREATY,

    // ── Commission outputs ───────────────────────────────────────────────────
    /**
     * Pay a producer commission on a paid contribution. Populates a
     * PAY_COMMISSION {@code RuleResult} on {@code ContributionFact} carrying
     * producerId (via code) + amount + rateCardId. Consumed by
     * {@code CommissionCalcService} in finance-service.
     *
     * <p>Action fields (see {@code RuleAction}):
     * <ul>
     *   <li>{@code rejectionCode} — producer id (UUID string). Optional —
     *       an empty value defers to the member's currently-assigned producer.</li>
     *   <li>{@code value} — {@code "RATE_CARD:<uuid>"} to look up a rate
     *       card and pay {@code contribution.premiumAmount * card.baseRatePct},
     *       or {@code "KICKER:<pct-bp>[:<reason>]"} to add or subtract basis
     *       points to whatever the rate-card lookup produced.</li>
     *   <li>{@code message} — human-readable audit note.</li>
     * </ul>
     */
    PAY_COMMISSION,

    // ── Premium-earning outputs ──────────────────────────────────────────────
    /**
     * Route a policy to a specific premium-earning method at bind time.
     * Populates {@code PremiumFact.earningMethod} + {@code loadingPercent}
     * so the downstream {@code PremiumEarningExecutor} in contributions-service
     * knows which strip strategy to apply when writing {@code earning_schedule}
     * rows. Rules with this action must live in the {@code PREMIUM_EARNING}
     * category (agenda-gated per {@code DrlCompiler.AGENDA_GATED_CATEGORIES}).
     *
     * <p>Action fields (see {@code RuleAction}):
     * <ul>
     *   <li>{@code value} — {@code EARNING_METHOD:<name>[:<loading-pct>]}
     *       where {@code name} is one of {@code DAILY_LINEAR},
     *       {@code MONTHLY_24THS}, or {@code LINEAR_WITH_LOADING}. The
     *       optional trailing decimal is only used by
     *       {@code LINEAR_WITH_LOADING} (front-loaded percent).</li>
     *   <li>{@code message} — human-readable audit note recorded on
     *       {@code PremiumFact.results}.</li>
     * </ul>
     */
    ACCRUE_PREMIUM,

    // ── Actuarial outputs ────────────────────────────────────────────────────
    /**
     * Route an IBNR / loss-triangle report to a specific LDF selection
     * method. Populates {@code TriangleFact.ldfMethod} so the downstream
     * chain-ladder compute in ai-service knows which LDF strategy to
     * apply. Rules with this action must live in the {@code ACTUARIAL}
     * category (agenda-gated per {@code DrlCompiler.AGENDA_GATED_CATEGORIES}).
     *
     * <p>Action fields (see {@code RuleAction}):
     * <ul>
     *   <li>{@code value} — {@code LDF_METHOD:<name>} where {@code name}
     *       is one of {@code volume}, {@code simple}, or {@code 5yr}.</li>
     *   <li>{@code message} — human-readable audit note recorded on
     *       {@code TriangleFact.results}.</li>
     * </ul>
     */
    SELECT_LDF,

    // ── IFRS 17 outputs ──────────────────────────────────────────────────────
    /**
     * Select the IFRS 17 measurement model (PAA / GMM / VFA) plus supporting
     * shape choices (coverage-unit pattern, variable-fee pattern for VFA,
     * finance-expense presentation) for a portfolio. Populates
     * {@code IfrsPortfolioFact.measurementModel},
     * {@code coverageUnitPattern}, {@code variableFeePattern} (VFA only),
     * and {@code financeExpensePresentation}. Rules with this action must
     * live in the {@code IFRS17_MODEL} category (agenda-gated per
     * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}).
     *
     * <p>Action fields (see {@code RuleAction}):
     * <ul>
     *   <li>{@code value} — {@code IFRS17_MODEL:<model>:<coverage>:<fee>:<expense>}
     *       where {@code model} is one of {@code PAA}, {@code GMM}, {@code VFA};
     *       {@code coverage} is one of {@code TIME}, {@code SUM_INSURED_TIME},
     *       {@code SUM_AT_RISK_TIME}, {@code CLAIM_FREQUENCY_TIME};
     *       {@code fee} is one of {@code FIXED_PCT}, {@code TIERED},
     *       {@code NAV_LINKED}, or {@code null} for non-VFA models;
     *       {@code expense} is one of {@code PL_ONLY}, {@code OCI_OPTION}.</li>
     *   <li>{@code message} — human-readable audit note recorded on
     *       {@code IfrsPortfolioFact.results}.</li>
     * </ul>
     */
    SELECT_IFRS17_MODEL,

    // ── Regulatory-parameter outputs ─────────────────────────────────────────
    /**
     * Override a numeric parameter that would otherwise resolve from a bundled
     * regulator YAML default (see
     * {@code shared/src/main/resources/regulatory-defaults/}). Populates
     * {@code RegulatoryParameterFact.parameterValue}. Rules with this action
     * must live in the {@code REGULATORY_PARAMETER} category (agenda-gated per
     * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) — finance-service's
     * {@code RegulatoryParameterResolver} focuses that group per parameter
     * lookup so overrides never fire during the stage-7 tenant sweep.
     *
     * <p>Action fields (see {@code RuleAction}):
     * <ul>
     *   <li>{@code value} — {@code PARAMETER_VALUE:<decimal>} where
     *       {@code decimal} is parsed as {@link java.math.BigDecimal} (e.g.
     *       {@code PARAMETER_VALUE:1.45}). Bad or missing values fall back to
     *       the bundled YAML default so a typo in a tenant rule cannot break
     *       the compute.</li>
     *   <li>{@code message} — human-readable audit note recorded on
     *       {@code RegulatoryParameterFact.results}.</li>
     * </ul>
     */
    SET_REGULATORY_PARAMETER,

    // ── PMB classification outputs (Phase 17 §B REG7) ────────────────────────
    /**
     * Classify a claim as PMB (Prescribed Minimum Benefit) and record the
     * matched CMS condition code. Populates {@code PmbClassificationFact}'s
     * {@code isPmb} + {@code pmbConditionCode} slots. Rules with this action
     * must live in the {@code PMB_CLASSIFICATION} category (agenda-gated per
     * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}) — the claims-service
     * {@code RulesEnginePmbClassifier} focuses that group per claim so PMB
     * rules never fire during the stage-7 tenant sweep.
     *
     * <p>Action fields (see {@code RuleAction}):
     * <ul>
     *   <li>{@code value} — {@code PMB_CONDITION_CODE:<code>} where
     *       {@code code} is the CMS PMB condition code (up to 20 chars,
     *       matches {@code claims.pmb_condition_code}). Missing / blank
     *       values fall back to a NOT_PMB no-op so a typo in a tenant rule
     *       cannot silently mark a claim as PMB without a code.</li>
     *   <li>{@code message} — human-readable audit note recorded on
     *       {@code PmbClassificationFact.results}.</li>
     * </ul>
     */
    SET_PMB_CLASSIFICATION
}
