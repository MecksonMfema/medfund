package com.medfund.user.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 15 §8 (I4) — append-only per-policy unit ledger. Purchases, sales,
 * rollovers, fund switches and fee deductions for unit-linked (VFA) policies.
 * §16 VFA reads the running-balance snapshot at reporting-period boundaries.
 *
 * <p>{@code policy_id} references any line-specific policy table
 * (life_policies, funeral_policies, ...) — no FK, kept line-agnostic per
 * parent-plan Invariant "core is line-neutral".
 *
 * <p>Leave {@code id} null on insert per {@code bug_r2dbc_pre_populated_id_update_mode}.
 */
@Table("policy_unit_ledger")
public class PolicyUnitLedger {

    @Id
    private UUID id;

    @Column("policy_id")
    private UUID policyId;

    @Column("fund_id")
    private UUID fundId;

    @Column("transaction_date")
    private LocalDate transactionDate;

    @Column("transaction_type")
    private String transactionType;

    @Column("units")
    private BigDecimal units;

    @Column("price")
    private BigDecimal price;

    @Column("created_at")
    private Instant createdAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public UUID getFundId() { return fundId; }
    public void setFundId(UUID fundId) { this.fundId = fundId; }
    public LocalDate getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDate transactionDate) { this.transactionDate = transactionDate; }
    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
    public BigDecimal getUnits() { return units; }
    public void setUnits(BigDecimal units) { this.units = units; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
}
