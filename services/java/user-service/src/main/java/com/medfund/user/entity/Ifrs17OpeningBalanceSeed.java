package com.medfund.user.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 15 §7 (I29) — tenant-admin override for auto-derived IFRS 17
 * opening balances. Keyed by (portfolio, cohort, currency, balance_type,
 * effective_from). §17 shaping consults this table first; falls back to
 * earning_schedule (LRC) / claims_reserve_history (LIC) when no seed row
 * exists for the tuple.
 *
 * <p>Leave {@code id} null on insert per {@code bug_r2dbc_pre_populated_id_update_mode}.
 */
@Table("ifrs17_opening_balance_seed")
public class Ifrs17OpeningBalanceSeed {

    @Id
    private UUID id;

    @Column("portfolio_id")
    private UUID portfolioId;

    @Column("cohort_id")
    private UUID cohortId;

    @Column("currency")
    private String currency;

    @Column("balance_type")
    private String balanceType;

    @Column("amount")
    private BigDecimal amount;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("reason_note")
    private String reasonNote;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;

    @Column("created_at")
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPortfolioId() { return portfolioId; }
    public void setPortfolioId(UUID portfolioId) { this.portfolioId = portfolioId; }
    public UUID getCohortId() { return cohortId; }
    public void setCohortId(UUID cohortId) { this.cohortId = cohortId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getBalanceType() { return balanceType; }
    public void setBalanceType(String balanceType) { this.balanceType = balanceType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public String getReasonNote() { return reasonNote; }
    public void setReasonNote(String reasonNote) { this.reasonNote = reasonNote; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
