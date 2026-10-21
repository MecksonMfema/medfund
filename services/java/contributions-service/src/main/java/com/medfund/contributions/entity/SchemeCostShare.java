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
 * Per-scheme cost-share configuration. Temporal (G15): every edit inserts a
 * new row keyed by {@code effective_from} rather than mutating in place, so a
 * claim adjudicated last week can still resolve the config that was effective
 * then. Look up via
 * {@link com.medfund.contributions.repository.SchemeCostShareRepository#findEffective}.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("scheme_cost_share")
public class SchemeCostShare {

    @Id
    private UUID id;

    @Column("scheme_id")
    private UUID schemeId;

    /** Policy year — aligns with {@code beneficiary_benefits.policy_year} (G17). */
    @Column("policy_year")
    private Integer policyYear;

    private BigDecimal deductible;

    @Column("out_of_pocket_max")
    private BigDecimal outOfPocketMax;

    /** INDIVIDUAL | FAMILY | EMBEDDED (G8). */
    @Column("deductible_scope")
    private String deductibleScope;

    @Column("oop_scope")
    private String oopScope;

    /** RECOVER_FROM_MEMBER | ABSORB_BY_FUND (G11). */
    @Column("shortfall_policy")
    private String shortfallPolicy;

    @Column("currency_code")
    private String currencyCode;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    /** NULL = currently effective. */
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
