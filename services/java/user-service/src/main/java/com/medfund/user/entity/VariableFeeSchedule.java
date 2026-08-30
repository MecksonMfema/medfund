package com.medfund.user.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 15 §8 (I4) — effective-date-versioned variable fee % per fund.
 * §16 VFA compute reads the fee for the CSM release portion (IFRS 17.B119).
 * Fee is a decimal fraction: 0.0150 = 1.50% p.a.
 *
 * <p>Leave {@code id} null on insert per {@code bug_r2dbc_pre_populated_id_update_mode}.
 */
@Table("variable_fee_schedule")
public class VariableFeeSchedule {

    @Id
    private UUID id;

    @Column("fund_id")
    private UUID fundId;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("effective_to")
    private LocalDate effectiveTo;

    @Column("fee_percentage")
    private BigDecimal feePercentage;

    @Column("created_at")
    private Instant createdAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getFundId() { return fundId; }
    public void setFundId(UUID fundId) { this.fundId = fundId; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
    public BigDecimal getFeePercentage() { return feePercentage; }
    public void setFeePercentage(BigDecimal feePercentage) { this.feePercentage = feePercentage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
}
