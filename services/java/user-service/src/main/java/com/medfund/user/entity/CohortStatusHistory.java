package com.medfund.user.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only journal of IFRS 17 cohort_type transitions per Phase 15 §4
 * (I11). Two write paths land here:
 * <ul>
 *   <li>{@code MANUAL} — tenant admin edits cohort_type via
 *       {@code Ifrs17CohortController.update}</li>
 *   <li>{@code AUTO}   — Phase 15 §15 onerous-test compute (posted from
 *       ai-service, populated in a later phase)</li>
 * </ul>
 * Leave {@code id} null on insert per {@code bug_r2dbc_pre_populated_id_update_mode}.
 */
@Table("cohort_status_history")
public class CohortStatusHistory {

    @Id
    private UUID id;

    @Column("cohort_id")
    private UUID cohortId;

    @Column("from_status")
    private String fromStatus;

    @Column("to_status")
    private String toStatus;

    @Column("transition_reason")
    private String transitionReason;

    @Column("transition_source")
    private String transitionSource;

    @Column("source_run_id")
    private UUID sourceRunId;

    @Column("effective_at")
    private Instant effectiveAt;

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
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public String getTransitionReason() { return transitionReason; }
    public void setTransitionReason(String transitionReason) { this.transitionReason = transitionReason; }
    public String getTransitionSource() { return transitionSource; }
    public void setTransitionSource(String transitionSource) { this.transitionSource = transitionSource; }
    public UUID getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(UUID sourceRunId) { this.sourceRunId = sourceRunId; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public String getReasonNote() { return reasonNote; }
    public void setReasonNote(String reasonNote) { this.reasonNote = reasonNote; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
