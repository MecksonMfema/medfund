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
 * Bridge row: how much of a {@link MemberPayable} has been consumed and by
 * what source. Today CTC is the only writer ({@code source_type='CTC'});
 * future member-payment-run features can write {@code source_type='PAYMENT'}
 * without a schema change. {@code amount_applied} is signed — positive on
 * a CTC commit, negative on a CTC reversal, netting back to the pre-CTC
 * consumption when a compensating reversal lands.
 */
@Getter
@Setter
@Table("member_payable_applications")
public class MemberPayableApplication {

    @Id
    private UUID id;

    @Column("member_payable_id")
    private UUID memberPayableId;

    @Column("source_type")
    private String sourceType;

    @Column("source_id")
    private UUID sourceId;

    @Column("amount_applied")
    private BigDecimal amountApplied;

    @Column("currency_code")
    private String currencyCode;

    @Column("applied_at")
    private Instant appliedAt;

    @Column("applied_by")
    private UUID appliedBy;
}
