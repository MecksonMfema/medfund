package com.medfund.user.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * State machine for a request to move a member between groups.
 * Mirrors the {@code scheme_changes} shape so operators recognise
 * the workflow (PENDING → APPROVED → APPLIED, with REJECTED /
 * CANCELLED as terminal branches).
 *
 * <p>Back-dated requests (effective_date &lt; today) bypass the
 * state machine and go straight to APPLIED — the arrears / rebate
 * is posted asynchronously by GroupChangedConsumer in
 * contributions-service.
 */
@Getter
@Setter
@Table("pending_group_changes")
public class PendingGroupChange {

    @Id
    private UUID id;

    @Column("member_id")
    private UUID memberId;

    @Column("from_group_id")
    private UUID fromGroupId;

    @Column("to_group_id")
    private UUID toGroupId;

    private String status = "PENDING";

    @Column("requested_date")
    private LocalDate requestedDate;

    @Column("effective_date")
    private LocalDate effectiveDate;

    private String reason;

    @Column("rejection_reason")
    private String rejectionReason;

    @Column("approved_by")
    private UUID approvedBy;

    @Column("approved_at")
    private Instant approvedAt;

    @Column("applied_at")
    private Instant appliedAt;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;
}
