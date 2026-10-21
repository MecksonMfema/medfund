package com.medfund.claims.siu.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SIU (Special Investigations Unit) case — one row per investigation into
 * one or more claims flagged as suspicious. Phase 19 §A ships an MVP
 * 3-state machine:
 * <pre>
 *   OPEN → UNDER_REVIEW → CLOSED_CONFIRMED_FRAUD | CLOSED_DISMISSED_FALSE_POSITIVE
 * </pre>
 * §B Phase 8 widens the CHECK constraint (via V174) to include ASSIGNED,
 * PENDING_APPROVAL, REOPENED, CLOSED_REFERRED_LAW_ENFORCEMENT, and
 * CLOSED_ACTION_TAKEN, plus four-eyes staging columns.
 *
 * <p>Auto-opened cases carry {@code opened_by = SYSTEM_ACTOR} (all-zero UUID)
 * per feedback_audit_actor_email — {@code opened_by_email} is populated
 * with the platform system-actor identity, never null.
 */
@Getter
@Setter
@Table("siu_case")
public class SiuCase {

    @Id
    private UUID id;

    @Column("case_number")      private String caseNumber;
    @Column("status")           private String status;
    @Column("priority")         private String priority;
    // Default to an empty array so the NOT NULL DEFAULT '{}' column round-trips
    // cleanly through R2DBC — a null field would UPDATE the column back to
    // NULL and violate the NOT NULL constraint on the second save().
    @Column("tags")             private String[] tags = new String[0];
    @Column("assigned_to")      private UUID assignedTo;
    @Column("opened_by")        private UUID openedBy;
    @Column("opened_by_email")  private String openedByEmail;
    @Column("opened_at")        private OffsetDateTime openedAt;
    @Column("closed_by")        private UUID closedBy;
    @Column("closed_by_email")  private String closedByEmail;
    @Column("closed_at")        private OffsetDateTime closedAt;
    @Column("closure_reason")   private String closureReason;
    @Column("outcome")          private String outcome;
    @Column("saved_amount")     private BigDecimal savedAmount;
    @Column("saved_currency")   private String savedCurrency;

    // §B Phase 8 four-eyes staging — populated by proposeClosure
    // (UNDER_REVIEW → PENDING_APPROVAL), consumed and copied onto the
    // terminal closed_* fields by approveClosure. Supervisor must be a
    // different actor from proposed_by per FR6.
    @Column("proposed_by")              private UUID proposedBy;
    @Column("proposed_by_email")        private String proposedByEmail;
    @Column("proposed_at")              private OffsetDateTime proposedAt;
    @Column("proposed_outcome")         private String proposedOutcome;
    @Column("proposed_saved_amount")    private BigDecimal proposedSavedAmount;
    @Column("proposed_saved_currency")  private String proposedSavedCurrency;
    @Column("proposed_closure_reason")  private String proposedClosureReason;

    // The DB defaults these to NOW() on INSERT (NOT NULL columns). Callers
    // must populate them explicitly in Java before any subsequent save() —
    // Spring Data auditing here would only support LocalDateTime, and the
    // rest of the SIU stack has settled on OffsetDateTime for wire parity
    // with the AI-service fraud event contract.
    @Column("created_at")       private OffsetDateTime createdAt;
    @Column("updated_at")       private OffsetDateTime updatedAt;
}
