package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sub-ledger row against a {@link MemberCostShareLiability}: one entry per
 * receipt / member-paid-provider event / write-off applied to the parent
 * liability (V078, Phase 4 copayments).
 *
 * <p>{@code source} discriminator:
 * <ul>
 *   <li>{@code MEMBER_PAYMENT} — member paid the tenant; {@code receiptTransactionId}
 *       links back to {@code transactions.id}.</li>
 *   <li>{@code MEMBER_PAID_PROVIDER} — cash-first path, member paid the
 *       provider directly; synthetic row written at liability creation time,
 *       {@code receiptTransactionId} is null.</li>
 *   <li>{@code WRITE_OFF} — bad-debt style close-out; {@code receiptTransactionId}
 *       is null.</li>
 * </ul>
 */
@Getter
@Setter
@Table("member_cost_share_settlement")
public class MemberCostShareSettlement {

    @Id
    private UUID id;

    @Column("liability_id")
    private UUID liabilityId;

    @Column("receipt_transaction_id")
    private UUID receiptTransactionId;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    /** MEMBER_PAYMENT | MEMBER_PAID_PROVIDER | WRITE_OFF. */
    private String source;

    @Column("settled_at")
    private Instant settledAt;

    @Column("created_by")
    private UUID createdBy;
}
