package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Tiered copay row — 1:N with {@link BenefitCostShare} when its
 * {@code copayType='TIERED'}. {@code tier_name} is a free-text label
 * for MVP (G16); a formal {@code network_tiers} reference table is
 * deferred as follow-up F5.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("benefit_cost_share_tier")
public class BenefitCostShareTier {

    @Id
    private UUID id;

    @Column("benefit_cost_share_id")
    private UUID benefitCostShareId;

    /** e.g. "TIER_1", "IN_NETWORK", "PREFERRED". */
    @Column("tier_name")
    private String tierName;

    @Column("copay_amount")
    private BigDecimal copayAmount;

    @Column("copay_percentage")
    private BigDecimal copayPercentage;

    @Column("copay_max")
    private BigDecimal copayMax;
}
