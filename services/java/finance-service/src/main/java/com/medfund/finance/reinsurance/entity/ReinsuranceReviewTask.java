package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single reinsurance manual-review task (V090). Populated primarily by
 * claim-regression detection in {@code ReinsuranceLossCessionConsumer}
 * (a re-adjudication that drops {@code approvedAmount} below a prior
 * cession's basis, or a REJECTED reversing an APPROVED). Also holds
 * recovery-dispute and manual-void surfaces the operator may open by
 * hand.
 *
 * <p>Statuses: OPEN → IN_PROGRESS → RESOLVED_VOID / RESOLVED_KEEP /
 * DISMISSED. RESOLVED_VOID cascades to the linked cession + recovery in
 * {@link com.medfund.finance.reinsurance.service.ReinsuranceReviewTaskService}.
 */
@Getter
@Setter
@Table("reinsurance_review_task")
public class ReinsuranceReviewTask {

    @Id
    private UUID id;

    @Column("task_type")
    private String taskType;

    @Column("cession_id")
    private UUID cessionId;

    @Column("recovery_id")
    private UUID recoveryId;

    @Column("claim_id")
    private UUID claimId;

    @Column("treaty_id")
    private UUID treatyId;

    @Column("status")
    private String status;

    @Column("assignee_user_id")
    private UUID assigneeUserId;

    @Column("due_by")
    private OffsetDateTime dueBy;

    @Column("create_reason")
    private String createReason;

    @Column("resolution_notes")
    private String resolutionNotes;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
