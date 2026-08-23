package com.medfund.finance.producer.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Manual commission adjustment (V097). Four-eyes lifecycle mirrors
 * {@code Cession} for facultative reinsurance:
 * {@code DRAFT → APPROVED → COMMITTED} (terminal) or
 * {@code DRAFT|APPROVED → VOIDED} (terminal).
 *
 * <p>{@code adjustmentType} tags the operator intent
 * ({@code EX_GRATIA}, {@code VOID}, {@code MANUAL_CLAWBACK},
 * {@code MANUAL_REVERSAL}); {@code adjustmentAmount} is signed — a positive
 * amount credits the producer, a negative amount reverses. On COMMIT the
 * service writes a compensating {@code commission_transaction} row and
 * links it via {@link #committedTxnId}.
 *
 * <p>{@code justification} is required at DRAFT-time and enforced at both
 * the DB layer ({@code ca_justification_len_ck} ≥ 20 chars) and the service
 * layer for a friendlier 400.
 */
@Getter
@Setter
@Table("commission_adjustment")
public class CommissionAdjustment {

    @Id
    private UUID id;

    @Column("reference")
    private String reference;

    @Column("target_commission_transaction_id")
    private UUID targetCommissionTransactionId;

    @Column("adjustment_type")
    private String adjustmentType;

    @Column("adjustment_amount")
    private BigDecimal adjustmentAmount;

    @Column("native_currency")
    private String nativeCurrency;

    @Column("justification")
    private String justification;

    @Column("status")
    private String status = "DRAFT";

    @Column("approver_actor_id")
    private UUID approverActorId;

    @Column("approver_actor_email")
    private String approverActorEmail;

    @Column("approved_at")
    private OffsetDateTime approvedAt;

    @Column("committed_at")
    private OffsetDateTime committedAt;

    @Column("committed_txn_id")
    private UUID committedTxnId;

    @Column("voided_at")
    private OffsetDateTime voidedAt;

    @Column("voided_reason")
    private String voidedReason;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
