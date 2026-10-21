package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fact for the {@code PREMIUM_EARNING} rule category. Populated at policy-bind
 * time by the contributions-service {@code PolicyIssuedConsumer}; the
 * {@code AccruePremiumEmitter} fires rules that pick an earning method and
 * record it on this fact for downstream consumption by
 * {@code PremiumEarningExecutor}.
 *
 * <p>Action methods invoked from DRL:
 * <ul>
 *   <li>{@link #accrue(String, BigDecimal, String)} — set the earning method
 *       and optional loading percent chosen by the fired rule.</li>
 * </ul>
 *
 * <p>Rules with {@code ACCRUE_PREMIUM} actions run inside the
 * {@code PREMIUM_EARNING} agenda group and only fire when the caller focuses
 * that group — see {@code DrlCompiler.AGENDA_GATED_CATEGORIES}.
 */
@Getter
@Setter
@NoArgsConstructor
public class PremiumFact {

    /** Surrogate id of the policy the fact was built for. */
    private String policyId;

    /** Discriminator matching the source table (e.g. {@code LIFE_POLICY}, {@code VEHICLE}). */
    private String policySource;

    /** Line-agnostic {@code InsuranceLine} name — {@code LIFE}, {@code HEALTH}, ... */
    private String insuranceLine;

    /** Optional product code for finer-grained targeting (e.g. {@code WHOLE_LIFE}). */
    private String productCode;

    /** Tenant that owns the policy. */
    private String tenantId;

    /** Written premium at bind, in {@link #currencyCode}. */
    private BigDecimal writtenPremium;
    private String currencyCode;

    private LocalDate coverageStart;
    private LocalDate coverageEnd;
    private OffsetDateTime boundAt;

    private String portfolioId;
    private String cohortId;

    /**
     * Earning method chosen by the fired rule. Defaults to
     * {@code DAILY_LINEAR} so a policy with no matching tenant rule still
     * earns.
     */
    private String earningMethod = "DAILY_LINEAR";

    /** Front-loaded percent for {@code LINEAR_WITH_LOADING}; null otherwise. */
    private BigDecimal loadingPercent;

    /** Trace of every rule mutation. Useful in dry-runs and audit. */
    private List<RuleResult> results = new ArrayList<>();

    /**
     * ACCRUE_PREMIUM action — record the chosen earning method + optional
     * loading percent. Called from DRL by {@code AccruePremiumEmitter}.
     */
    public void accrue(String method, BigDecimal loadingPct, String reason) {
        this.earningMethod = method;
        this.loadingPercent = loadingPct;
        this.results.add(new RuleResult("ACCRUE_PREMIUM", method, reason));
    }
}
