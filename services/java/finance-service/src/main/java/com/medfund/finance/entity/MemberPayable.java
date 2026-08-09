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
 * Source-of-truth "the fund owes this member" row, written by
 * {@code ClaimAdjudicatedConsumer} when a claim adjudicates with
 * {@code payee_type=MEMBER}. Consumed by CTC payments (V069) and, later,
 * by member-payment-run features.
 */
@Getter
@Setter
@Table("member_payables")
public class MemberPayable {

    @Id
    private UUID id;

    @Column("member_id")
    private UUID memberId;

    @Column("claim_id")
    private UUID claimId;

    @Column("claim_number")
    private String claimNumber;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    /** 'open' | 'applied' | 'reversed'. */
    private String status;

    @Column("recorded_at")
    private Instant recordedAt;

    @Column("recorded_by")
    private UUID recordedBy;
}
