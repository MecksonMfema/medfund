package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Table("advance_payments")
public class AdvancePayment {

    @Id
    private UUID id;

    @Column("payment_id")
    private UUID paymentId;

    @Column("provider_id")
    private UUID providerId;

    @Column("member_id")
    private UUID memberId;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    @Column("payment_method")
    private String paymentMethod;

    private String reference;

    private String comment;

    @CreatedDate
    @Column("recorded_at")
    private Instant recordedAt;

    @Column("recorded_by")
    private UUID recordedBy;
}
