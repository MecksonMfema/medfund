package com.medfund.user.endorsement.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-policy endorsement (V110). Four-eyes lifecycle mirrors
 * {@code CommissionAdjustment} (V097) —
 * {@code DRAFT → APPROVED → COMMITTED} (terminal) or
 * {@code DRAFT|APPROVED → VOIDED} (terminal). Contributions-service
 * transitions {@code COMMITTED → COMPUTED} once the retro earning-schedule
 * recompute finishes.
 *
 * <p>{@code premiumDelta} is signed and expressed in the policy's native
 * currency; the earning-schedule recompute preserves the currency and never
 * re-denominates. {@code effectiveFrom} snaps to 1st-of-month per
 * {@code feedback_effective_date_snap} (enforced at the service layer, not
 * the DB — DATE has no calendar-snap constraint syntax).
 */
@Getter
@Setter
@Table("endorsement")
public class Endorsement {

    @Id
    private UUID id;

    @Column("reference")
    private String reference;

    @Column("policy_id")
    private UUID policyId;

    @Column("policy_source")
    private String policySource;

    @Column("insurance_line")
    private String insuranceLine;

    @Column("change_type")
    private String changeType;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("premium_delta")
    private BigDecimal premiumDelta;

    @Column("currency_code")
    private String currencyCode;

    @Column("reason")
    private String reason;

    @Column("status")
    private String status = "DRAFT";

    @Column("draft_actor_id")
    private UUID draftActorId;

    @Column("draft_actor_email")
    private String draftActorEmail;

    @Column("draft_at")
    private Instant draftAt;

    @Column("approve_actor_id")
    private UUID approveActorId;

    @Column("approve_actor_email")
    private String approveActorEmail;

    @Column("approve_at")
    private Instant approveAt;

    @Column("commit_actor_id")
    private UUID commitActorId;

    @Column("commit_actor_email")
    private String commitActorEmail;

    @Column("commit_at")
    private Instant commitAt;

    @Column("voided_reason")
    private String voidedReason;

    @Column("voided_at")
    private Instant voidedAt;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
