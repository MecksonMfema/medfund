package com.medfund.finance.regulatory.aml.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tenant-scoped AML/STR alert row (V167). Workflow:
 * {@code RAISED → REVIEWED → FILED} (terminal) or
 * {@code RAISED|REVIEWED → CLOSED} (terminal, not reportable).
 *
 * <p>Rule-2: unqualified table name — never prefix {@code public.} on tenant
 * tables (bug_public_prefix_silent_rollback).
 */
@Getter
@Setter
@NoArgsConstructor
@Table("suspicious_transaction_alert")
public class SuspiciousTransactionAlert {

    @Id
    private UUID id;

    @Column("raised_by_actor_id")
    private UUID raisedByActorId;

    @Column("raised_by_actor_email")
    private String raisedByActorEmail;

    @Column("raised_at")
    private OffsetDateTime raisedAt;

    @Column("transaction_ref")
    private String transactionRef;

    @Column("transaction_type")
    private String transactionType;

    @Column("amount_native")
    private BigDecimal amountNative;

    @Column("currency")
    private String currency;

    @Column("member_id")
    private UUID memberId;

    @Column("provider_id")
    private UUID providerId;

    @Column("description")
    private String description;

    @Column("status")
    private String status;

    @Column("reviewer_actor_id")
    private UUID reviewerActorId;

    @Column("reviewer_actor_email")
    private String reviewerActorEmail;

    @Column("reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column("review_note")
    private String reviewNote;

    @Column("filer_actor_id")
    private UUID filerActorId;

    @Column("filer_actor_email")
    private String filerActorEmail;

    @Column("filed_at")
    private OffsetDateTime filedAt;

    @Column("filed_ref")
    private String filedRef;

    @Column("filed_xlsx_ref")
    private String filedXlsxRef;

    @Column("closer_actor_id")
    private UUID closerActorId;

    @Column("closer_actor_email")
    private String closerActorEmail;

    @Column("closed_at")
    private OffsetDateTime closedAt;

    @Column("closed_reason")
    private String closedReason;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;
}
