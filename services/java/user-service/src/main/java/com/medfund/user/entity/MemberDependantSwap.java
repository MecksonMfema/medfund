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
 * State machine for a role-swap between a member and one of their
 * dependants. On apply the dependant is promoted into {@code members},
 * the old member is demoted into {@code dependants}, sibling dependants
 * re-parent under the new principal, and both old rows are marked
 * {@code status='swapped'} with the {@code swapped_to_id} pointer
 * updated so historical claims/contributions can follow the redirect
 * without rewriting the FK.
 */
@Getter
@Setter
@Table("member_dependant_swaps")
public class MemberDependantSwap {

    @Id
    private UUID id;

    @Column("old_member_id")
    private UUID oldMemberId;

    @Column("dependant_id")
    private UUID dependantId;

    /** Populated on apply — the members row promoted from the dependant. */
    @Column("new_member_id")
    private UUID newMemberId;

    /** Populated on apply — the dependants row that hosts the demoted old principal. */
    @Column("old_dependant_id")
    private UUID oldDependantId;

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
