package com.medfund.user.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 15 §8 (I4) — daily NAV series per fund. Read-only after insert;
 * corrections go via a new row on the next valuation_date (never edit).
 *
 * <p>Leave {@code id} null on insert per {@code bug_r2dbc_pre_populated_id_update_mode}.
 */
@Table("fund_nav_history")
public class FundNavHistory {

    @Id
    private UUID id;

    @Column("fund_id")
    private UUID fundId;

    @Column("valuation_date")
    private LocalDate valuationDate;

    @Column("nav_per_unit")
    private BigDecimal navPerUnit;

    @Column("source")
    private String source;

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
    public LocalDate getValuationDate() { return valuationDate; }
    public void setValuationDate(LocalDate valuationDate) { this.valuationDate = valuationDate; }
    public BigDecimal getNavPerUnit() { return navPerUnit; }
    public void setNavPerUnit(BigDecimal navPerUnit) { this.navPerUnit = navPerUnit; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
}
