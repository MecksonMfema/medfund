package com.medfund.user.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only movement journal for IFRS 17 loss-component balances per
 * Phase 15 §5 (I19). Two write paths land here:
 * <ul>
 *   <li>{@code AUTO}   — Phase 15 §15 onerous-test compute posts
 *       INITIAL_RECOGNITION + RELEASE rows from ai-service.</li>
 *   <li>{@code MANUAL} — tenant admin adjustments via the "Loss component"
 *       tab in the cohort admin UI.</li>
 * </ul>
 * The {@code cohort_loss_component_current} matview aggregates rows to the
 * current balance per (portfolio, cohort, currency). Leave {@code id} null
 * on insert per {@code bug_r2dbc_pre_populated_id_update_mode}.
 */
@Table("cohort_loss_component_history")
public class CohortLossComponentHistory {

    @Id
    private UUID id;

    @Column("cohort_id")
    private UUID cohortId;

    @Column("effective_at")
    private Instant effectiveAt;

    @Column("movement_type")
    private String movementType;

    @Column("amount")
    private BigDecimal amount;

    @Column("currency")
    private String currency;

    @Column("source_run_id")
    private UUID sourceRunId;

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
    public UUID getCohortId() { return cohortId; }
    public void setCohortId(UUID cohortId) { this.cohortId = cohortId; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public UUID getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(UUID sourceRunId) { this.sourceRunId = sourceRunId; }
    public String getReasonNote() { return reasonNote; }
    public void setReasonNote(String reasonNote) { this.reasonNote = reasonNote; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
