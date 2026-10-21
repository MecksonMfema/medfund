package com.medfund.finance.producer.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Commission rate card (V094). Lookup-driven base commission rate per
 * insurance line + optional producer tier, valid across an effective-date
 * window. {@code clawbackWindowDays} bounds how long after accrual a
 * lapse can claw back the commission (see {@code CommissionClawbackService}).
 */
@Getter
@Setter
@Table("commission_rate_card")
public class CommissionRateCard {

    @Id
    private UUID id;

    @Column("name")
    private String name;

    @Column("insurance_line")
    private String insuranceLine;

    @Column("producer_tier")
    private String producerTier;

    @Column("base_rate_pct")
    private BigDecimal baseRatePct;

    @Column("clawback_window_days")
    private Integer clawbackWindowDays;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("effective_to")
    private LocalDate effectiveTo;

    @Column("is_active")
    private Boolean active = true;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
