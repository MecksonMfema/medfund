package com.medfund.contributions.premium.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row per (policy × source × period × endorsement?) — the storage grain
 * described in Phase 12 §A U7. Populated by
 * {@code PolicyIssuedConsumer} for annual-bind lines,
 * {@code BillingContributionEarningHook} for HEALTH (one row per
 * {@code Contribution}), and by {@code PolicyEndorsedConsumer} (Phase 9) for
 * endorsement retro rows.
 */
@Getter
@Setter
@Table("earning_schedule")
public class EarningSchedule {

    @Id
    private UUID id;

    /** FK to the source policy row (or the {@code Contribution} row for HEALTH). */
    @Column("policy_id")
    private UUID policyId;

    /**
     * Discriminator for the source table — {@code LIFE_POLICY},
     * {@code FUNERAL_POLICY}, {@code DISABILITY_POLICY}, {@code TRAVEL_POLICY},
     * {@code VEHICLE_POLICY}, {@code PROPERTY_POLICY}, or {@code CONTRIBUTION}.
     */
    @Column("policy_source")
    private String policySource;

    @Column("insurance_line")
    private String insuranceLine;

    @Column("period_start")
    private LocalDate periodStart;

    @Column("period_end")
    private LocalDate periodEnd;

    /** Written premium apportioned to this period, in {@link #currencyCode}. */
    @Column("written_amount")
    private BigDecimal writtenAmount;

    /**
     * Amount earned by {@link #periodEnd}; null while the period is still
     * open. Filled in by {@code PremiumEarningExecutor} once
     * {@code period_end < today}.
     */
    @Column("earned_at_period_end")
    private BigDecimal earnedAtPeriodEnd;

    /** Fixed at row creation from the policy's currency; never re-denominated. */
    @Column("currency_code")
    private String currencyCode;

    /** True for rows written by an endorsement rewrite (delta rows). */
    @Column("is_endorsement")
    private boolean endorsement;

    @Column("endorsement_id")
    private UUID endorsementId;

    @Column("portfolio_id")
    private UUID portfolioId;

    @Column("cohort_id")
    private UUID cohortId;

    /** Snapshot of the DRL template that fired for this policy at bind. */
    @Column("earning_method")
    private String earningMethod;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;

    /**
     * Phase 13 §B per L6: idempotency ref for a policy-status-triggered
     * closure. Populated by {@code EarningScheduleClosureService.closeOut /
     * freeze}; nulled out by {@code resume / reinstate}. NULL for rows
     * untouched by policy-status events.
     */
    @Column("closure_ref")
    private UUID closureRef;

    /**
     * Phase 13 §B per L6: TRUE when a policy-status transition (LAPSE,
     * TERMINATE, SUSPEND) has taken the row out of the nightly earning
     * loop. The nightly {@code PremiumEarningExecutor} scan explicitly
     * skips {@code is_closure = TRUE} rows.
     */
    @Column("is_closure")
    private boolean closure;
}
