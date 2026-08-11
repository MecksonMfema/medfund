package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
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
 * Per-benefit cost-share configuration. 1:1-nullable with {@code scheme_benefits}:
 * a benefit with no row here has no cost share. Temporal (G15).
 *
 * <p>{@link #copayType} governs which of {@link #copayAmount},
 * {@link #copayPercentage}, and {@link BenefitCostShareTier tier rows} apply:
 * <ul>
 *   <li>FLAT — use {@code copayAmount}</li>
 *   <li>PERCENT — use {@code copayPercentage} (0.0000-100.0000)</li>
 *   <li>TIERED — pick a matching {@link BenefitCostShareTier} by {@code tier_name}</li>
 *   <li>NULL — no copay (may still carry coinsurance)</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@Table("benefit_cost_share")
public class BenefitCostShare {

    @Id
    private UUID id;

    @Column("scheme_benefit_id")
    private UUID schemeBenefitId;

    /** FLAT | PERCENT | TIERED | NULL (coinsurance-only or no cost share). */
    @Column("copay_type")
    private String copayType;

    @Column("copay_amount")
    private BigDecimal copayAmount;

    /** 0.0000-100.0000. */
    @Column("copay_percentage")
    private BigDecimal copayPercentage;

    @Column("copay_max")
    private BigDecimal copayMax;

    @Column("coinsurance_rate")
    private BigDecimal coinsuranceRate;

    @Column("applies_to_deductible")
    private Boolean appliesToDeductible = Boolean.TRUE;

    @Column("applies_to_oop_max")
    private Boolean appliesToOopMax = Boolean.TRUE;

    /** per_visit | per_day | per_admission | per_script. */
    private String basis;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("effective_to")
    private LocalDate effectiveTo;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private UUID createdBy;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
