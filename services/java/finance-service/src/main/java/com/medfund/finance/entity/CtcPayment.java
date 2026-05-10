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
@Table("ctc_payments")
public class CtcPayment {

    @Id
    private UUID id;

    @Column("group_id")
    private UUID groupId;

    @Column("member_id")
    private UUID memberId;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    @Column("contribution_id")
    private UUID contributionId;

    private Boolean committed = false;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private UUID createdBy;
}
